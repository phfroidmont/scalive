package scalive.render

/** One protocol-neutral semantic change within an evaluated tree. */
enum RenderChange:
  case Text(slot: TemplateSlotId, value: String, raw: Boolean)
  case Attribute(slot: TemplateSlotId, name: String, value: Option[AttributeValue])
  case Replace(id: TemplateId, node: EvaluatedNode)
  case Stream(
    id: TemplateId,
    identity: scalive.streams.LiveStreamIdentity,
    generation: Long,
    operations: EvaluatedNode.StreamOperations)

  /** A child-program delta scoped by the exact semantic component instance that owns its ids. */
  case Component(instanceToken: Object, delta: RenderDelta)

/** Exact semantic difference between committed and candidate render trees. */
enum RenderDelta:
  case Empty
  case Replace(tree: EvaluatedTree)
  case Update(revision: RenderRevision, changes: Vector[RenderChange])

/** Computes exact render deltas without using hashes as evidence of equality.
  *
  * Trees from different render programs are always replaced in full. Within one program, retained
  * revisions skip proven-equal nodes and structural mismatches produce explicit replacements.
  */
object TreeDiffer:
  def initial(tree: EvaluatedTree): RenderDelta = RenderDelta.Replace(tree)

  def diff(previous: EvaluatedTree, current: EvaluatedTree): RenderDelta =
    if previous.programIdentity != current.programIdentity then RenderDelta.Replace(current)
    else if previous.revision == current.revision then RenderDelta.Empty
    else if previous.root.id != current.root.id then RenderDelta.Replace(current)
    else
      val changes = diffNode(previous.root, current.root)
      if changes.isEmpty then RenderDelta.Empty
      else RenderDelta.Update(current.revision, changes)

  private def diffNode(previous: EvaluatedNode, current: EvaluatedNode): Vector[RenderChange] =
    if previous.revision == current.revision then Vector.empty
    else
      (previous, current) match
        case (left: EvaluatedNode.Text, right: EvaluatedNode.Text)
            if left.id == right.id && left.slot == right.slot && right.slot.nonEmpty =>
          Vector(RenderChange.Text(right.slot.get, right.value, right.raw))
        case (left: EvaluatedNode.Element, right: EvaluatedNode.Element)
            if sameElementShape(left, right) =>
          val attributeChanges =
            left.attributes.zip(right.attributes).flatMap { case (oldAttribute, newAttribute) =>
              if oldAttribute.revision == newAttribute.revision then None
              else
                newAttribute.slot
                  .map(slot => RenderChange.Attribute(slot, newAttribute.name, newAttribute.value))
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
          (left.child, right.child) match
            case (None, None)                                                   => Vector.empty
            case (Some(oldChild), Some(newChild)) if oldChild.id == newChild.id =>
              diffNode(oldChild, newChild)
            case _ => Vector(RenderChange.Replace(left.id, right))
        case (left: EvaluatedNode.Keyed, right: EvaluatedNode.Keyed) if left.id == right.id =>
          if left.rows.map(_.id) != right.rows.map(_.id) then
            Vector(RenderChange.Replace(left.id, right))
          else
            left.rows
              .zip(right.rows).flatMap((oldRow, newRow) => diffNode(oldRow.child, newRow.child))
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
          Vector.empty
        case (left: EvaluatedNode.Stream, right: EvaluatedNode.Stream)
            if left.id == right.id && (left.identity eq right.identity) =>
          val operatedIds = right.operations.inserts.map(_.row.domId).toSet
          val oldRows     = left.rows.map(row => row.domId -> row.child).toMap
          val rowChanges  = right.rows.flatMap { row =>
            if operatedIds.contains(row.domId) then Vector.empty
            else oldRows.get(row.domId).toVector.flatMap(diffNode(_, row.child))
          }
          val streamChanges =
            if left.generation == right.generation then Vector.empty
            else if right.operations.inserts.nonEmpty || right.operations.deletes.nonEmpty ||
              right.operations.reset
            then
              Vector(
                RenderChange.Stream(right.id, right.identity, right.generation, right.operations)
              )
            else Vector.empty
          streamChanges ++ rowChanges
        case _ => Vector(RenderChange.Replace(previous.id, current))

  private def sameElementShape(
    previous: EvaluatedNode.Element,
    current: EvaluatedNode.Element
  ): Boolean =
    previous.id == current.id && previous.tag == current.tag && previous.void == current.void &&
      previous.attributes.length == current.attributes.length &&
      previous.attributes.zip(current.attributes).forall { case (left, right) =>
        left.name == right.name && left.slot == right.slot
      } && previous.children.length == current.children.length &&
      previous.children.zip(current.children).forall { case (left, right) => left.id == right.id }
end TreeDiffer
