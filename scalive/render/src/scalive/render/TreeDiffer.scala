package scalive.render

/** One protocol-neutral semantic change within an evaluated tree. */
enum RenderChange:
  case Text(slot: TemplateSlotId, value: String, raw: Boolean)
  case Attribute(slot: TemplateSlotId, name: String, value: Option[AttributeValue])
  case Replace(id: TemplateId, node: EvaluatedNode)

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
