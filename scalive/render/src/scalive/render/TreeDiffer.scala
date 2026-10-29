package scalive.render

import scala.collection.mutable

/** One protocol-neutral semantic change within an evaluated tree. */
enum RenderChange:
  case Text(slot: TemplateSlotId, value: String, raw: Boolean)
  case Attribute(slot: TemplateSlotId, name: String, value: Option[AttributeValue])
  case Replace(id: TemplateId, node: EvaluatedNode)

  /** A keyed container now holding `rowCount` rows. `rows` lists, by ascending index, the rows
    * inserted and the retained rows that changed or moved; every other index keeps the row it held.
    */
  case Keyed(id: TemplateId, rowCount: Int, rows: Vector[KeyedRowChange])
  case Stream(
    id: TemplateId,
    identity: scalive.streams.LiveStreamIdentity,
    generation: Long,
    operations: EvaluatedNode.StreamOperations)

  /** A child-program delta scoped by the exact semantic component instance that owns its ids. */
  case Component(instanceToken: Object, delta: RenderDelta)

/** One row of a keyed-container change, at `index` in the new order. */
enum KeyedRowChange:
  def index: Int

  case Insert(index: Int, row: EvaluatedNode.KeyedRow)

  /** The row that was at `oldIndex`, with what changed in it. */
  case Retain(index: Int, oldIndex: Int, id: RowId, changes: Vector[RenderChange])

/** Exact semantic difference between committed and candidate render trees. */
enum RenderDelta:
  case Empty
  case Replace(tree: EvaluatedTree)
  case Update(revision: RenderRevision, changes: Vector[RenderChange])

/** Computes exact render deltas without using hashes as evidence of equality.
  *
  * Trees from different render programs are always replaced in full. Within one program, a node the
  * evaluation kept as it was is skipped, retained revisions skip proven-equal nodes, and structural
  * mismatches produce explicit replacements.
  */
object TreeDiffer:
  def initial(tree: EvaluatedTree): RenderDelta = RenderDelta.Replace(tree)

  def diff(previous: EvaluatedTree, current: EvaluatedTree): RenderDelta =
    if previous.programIdentity != current.programIdentity then RenderDelta.Replace(current)
    else if previous.root.id != current.root.id then RenderDelta.Replace(current)
    else if previous.root eq current.root then RenderDelta.Empty
    else
      val changes = diffNode(previous.root, current.root)
      if changes.isEmpty then RenderDelta.Empty
      else RenderDelta.Update(current.revision, changes)

  private def diffNode(previous: EvaluatedNode, current: EvaluatedNode): Vector[RenderChange] =
    if previous eq current then Vector.empty
    else diffDistinct(previous, current)

  private def diffDistinct(previous: EvaluatedNode, current: EvaluatedNode): Vector[RenderChange] =
    (previous, current) match
      case (left: EvaluatedNode.Text, right: EvaluatedNode.Text)
          if left.revision == right.revision =>
        Vector.empty
      case (left: EvaluatedNode.Text, right: EvaluatedNode.Text)
          if left.id == right.id && left.slot == right.slot && right.slot.nonEmpty =>
        Vector(RenderChange.Text(right.slot.get, right.value, right.raw))
      case (left: EvaluatedNode.Element, right: EvaluatedNode.Element)
          if sameElementShape(left, right) =>
        val attributeChanges =
          left.attributes.zip(right.attributes).flatMap { case (oldAttribute, newAttribute) =>
            if oldAttribute.revision == newAttribute.revision then None
            else if newAttribute.slot.isEmpty then None
            else
              Some(
                RenderChange.Attribute(newAttribute.slot.get, newAttribute.name, newAttribute.value)
              )
          }
        val childChanges       = left.children.zip(right.children).flatMap(diffNode)
        val changedWithoutSlot =
          left.attributes.zip(right.attributes).exists { case (oldAttribute, newAttribute) =>
            oldAttribute.revision != newAttribute.revision && newAttribute.slot.isEmpty
          }
        if changedWithoutSlot then Vector(RenderChange.Replace(left.id, right))
        else attributeChanges ++ childChanges
      case (left: EvaluatedNode.Flash, right: EvaluatedNode.Flash) if left.id == right.id =>
        (left.child, right.child) match
          case (None, None)                                                   => Vector.empty
          case (Some(oldChild), Some(newChild)) if oldChild.id == newChild.id =>
            diffNode(oldChild, newChild)
          case _ => Vector(RenderChange.Replace(left.id, right))
      case (left: EvaluatedNode.Choice, right: EvaluatedNode.Choice) if left.id == right.id =>
        if sameBy(left.children, right.children)(_.id) then
          left.children.zip(right.children).flatMap(diffNode)
        else Vector(RenderChange.Replace(left.id, right))
      case (left: EvaluatedNode.Keyed, right: EvaluatedNode.Keyed) if left.id == right.id =>
        val rows = Vector.newBuilder[KeyedRowChange]
        if sameBy(left.rows, right.rows)(_.id) then
          // Rows in the same order are compared by position, without mapping the old rows.
          var index = 0
          while index < right.rows.length do
            val row     = right.rows(index)
            val changes = diffNode(left.rows(index).child, row.child)
            if changes.nonEmpty then rows += KeyedRowChange.Retain(index, index, row.id, changes)
            index += 1
        else
          val oldIndexes = mutable.LongMap[Int]()
          oldIndexes.sizeHint(left.rows.length)
          left.rows.indices.foreach(index => oldIndexes.update(left.rows(index).id.value, index))
          var index = 0
          while index < right.rows.length do
            val row      = right.rows(index)
            val oldIndex = oldIndexes.getOrElse(row.id.value, -1)
            if oldIndex < 0 then rows += KeyedRowChange.Insert(index, row)
            else
              val changes = diffNode(left.rows(oldIndex).child, row.child)
              if oldIndex != index || changes.nonEmpty then
                rows += KeyedRowChange.Retain(index, oldIndex, row.id, changes)
            index += 1
        val changed = rows.result()
        if changed.isEmpty && left.rows.length == right.rows.length then Vector.empty
        else Vector(RenderChange.Keyed(right.id, right.rows.length, changed))
      case (left: EvaluatedNode.Component, right: EvaluatedNode.Component)
          if left.id == right.id && left.applicationId == right.applicationId =>
        (left.resolution, right.resolution) match
          case (None, None) => Vector.empty
          case (Some(oldValue), Some(newValue))
              if oldValue.instanceToken eq newValue.instanceToken =>
            TreeDiffer.diff(oldValue.child, newValue.child) match
              case RenderDelta.Empty => Vector.empty
              case delta => Vector(RenderChange.Component(newValue.instanceToken, delta))
          case _ => Vector(RenderChange.Replace(left.id, right))
      case (left: EvaluatedNode.Nested, right: EvaluatedNode.Nested)
          if left.id == right.id && left.applicationId == right.applicationId =>
        (left.resolution, right.resolution) match
          case (None, None) => Vector.empty
          case (Some(oldValue), Some(newValue))
              if oldValue.instanceToken eq newValue.instanceToken =>
            Vector.empty
          case _ => Vector(RenderChange.Replace(left.id, right))
      case (left: EvaluatedNode.Stream, right: EvaluatedNode.Stream)
          if left.id == right.id && (left.identity eq right.identity) =>
        // A stream keeps its last operations until the next one, so they are sent only when the
        // browser has not seen this generation. Rows they insert are sent whole; diff the others.
        val operations      = right.operations
        val sendsOperations = left.generation != right.generation &&
          (operations.inserts.nonEmpty || operations.deletes.nonEmpty || operations.reset)
        val operatedIds =
          if sendsOperations then operations.inserts.map(_.row.domId).toSet else Set.empty[String]
        val deleted = if sendsOperations then operations.deletes.length else 0
        val oldRows =
          StreamRowCursor[EvaluatedNode.StreamRow](left.rows, _.domId, operatedIds, deleted)
        val rowChanges = right.rows.flatMap { row =>
          if operatedIds.contains(row.domId) then Vector.empty
          else
            val index = oldRows.indexOf(row.domId)
            if index < 0 then Vector.empty else diffNode(left.rows(index).child, row.child)
        }
        val streamChanges =
          if sendsOperations then
            Vector(RenderChange.Stream(right.id, right.identity, right.generation, operations))
          else Vector.empty
        streamChanges ++ rowChanges
      case _ if previous.revision == current.revision => Vector.empty
      case _ => Vector(RenderChange.Replace(previous.id, current))

  private def sameElementShape(
    previous: EvaluatedNode.Element,
    current: EvaluatedNode.Element
  ): Boolean =
    previous.id == current.id && previous.tag == current.tag && previous.void == current.void &&
      sameBy(previous.attributes, current.attributes)(_.name) &&
      sameBy(previous.attributes, current.attributes)(_.slot) &&
      sameBy(previous.children, current.children)(_.id)

  // Inline, so comparing ids neither boxes them nor maps the vectors to compare.
  private inline def sameBy[A, B](
    previous: Vector[A],
    current: Vector[A]
  )(
    inline key: A => B
  ): Boolean =
    previous.length == current.length && previous.indices.forall(index =>
      key(previous(index)) == key(current(index))
    )
end TreeDiffer
