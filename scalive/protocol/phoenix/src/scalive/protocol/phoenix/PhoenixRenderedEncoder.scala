package scalive.protocol.phoenix

import scala.collection.mutable

import zio.json.ast.Json

import scalive.Escaping
import scalive.render.*

private[scalive] enum PhoenixEncodingError:
  case UnknownTarget(id: TemplateId)
  case DuplicateTarget(id: TemplateId)
  case UnknownSlot(slot: TemplateSlotId)
  case DuplicateSlot(slot: TemplateSlotId)
  case SlotKindMismatch(slot: TemplateSlotId)

/** Immutable projection state for Phoenix's rendered `s`/numeric-key protocol. */
final private[scalive] case class PhoenixRenderedState private[phoenix] (
  private[phoenix] val root: PhoenixRenderedEncoder.ProjectedNode)

private[scalive] object PhoenixRenderedEncoder:
  private[phoenix] enum ProjectedPart:
    case Static(value: String)
    case Dynamic(slot: TemplateSlotId, value: String, attributeName: Option[String])
    case Node(value: ProjectedNode)

  final private[phoenix] case class ProjectedNode(id: TemplateId, parts: Vector[ProjectedPart])

  def initial(
    tree: EvaluatedTree
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] =
    val root = project(tree.root)
    validate(root).map { _ =>
      val state = PhoenixRenderedState(root)
      state -> full(root)
    }

  def update(
    state: PhoenixRenderedState,
    delta: RenderDelta
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] = delta match
    case RenderDelta.Empty              => Right(state -> Json.Obj.empty)
    case RenderDelta.Replace(tree)      => initial(tree)
    case RenderDelta.Update(_, changes) =>
      detectDuplicateChanges(changes).flatMap { _ =>
        changes
          .foldLeft[Either[PhoenixEncodingError, (ProjectedNode, Vector[TemplateSlotId])]](
            Right(state.root -> Vector.empty)
          ) { case (result, change) =>
            result.flatMap { case (root, changedSlots) =>
              applyChange(root, change).map { updated =>
                val slots = change match
                  case RenderChange.Text(slot, _, _)      => changedSlots :+ slot
                  case RenderChange.Attribute(slot, _, _) => changedSlots :+ slot
                  case RenderChange.Replace(_, _)         => changedSlots
                updated -> slots
              }
            }
          }.flatMap { case (root, changedSlots) =>
            validate(root).map { _ =>
              val next = PhoenixRenderedState(root)
              val json =
                if changes.exists(_.isInstanceOf[RenderChange.Replace]) then full(root)
                else sparse(root, changedSlots.toSet)
              next -> json
            }
          }
      }

  private def project(node: EvaluatedNode): ProjectedNode = node match
    case text: EvaluatedNode.Text =>
      val value = if text.raw then text.value else Escaping.escape(text.value)
      val part  = text.slot match
        case Some(slot) => ProjectedPart.Dynamic(slot, value, None)
        case None       => ProjectedPart.Static(value)
      ProjectedNode(text.id, Vector(part))
    case element: EvaluatedNode.Element =>
      val opening = Vector.newBuilder[ProjectedPart]
      opening += ProjectedPart.Static(s"<${element.tag}")
      element.attributes.foreach { attribute =>
        attribute.slot match
          case Some(slot) =>
            opening += ProjectedPart.Dynamic(
              slot,
              attributeFragment(attribute.name, attribute.value),
              Some(attribute.name)
            )
          case None =>
            opening += ProjectedPart.Static(attributeFragment(attribute.name, attribute.value))
      }
      opening += ProjectedPart.Static(">")
      element.children.foreach(child => opening += ProjectedPart.Node(project(child)))
      if !element.void then opening += ProjectedPart.Static(s"</${element.tag}>")
      ProjectedNode(element.id, opening.result())

  private def attributeFragment(name: String, value: Option[AttributeValue]): String = value match
    case None                            => ""
    case Some(AttributeValue.Presence)   => s" $name"
    case Some(AttributeValue.Text(text)) => s" $name=\"${Escaping.escape(text)}\""

  private def applyChange(
    root: ProjectedNode,
    change: RenderChange
  ): Either[PhoenixEncodingError, ProjectedNode] = change match
    case RenderChange.Text(slot, value, raw) =>
      updateSlot(root, slot, None, if raw then value else Escaping.escape(value))
    case RenderChange.Attribute(slot, name, value) =>
      updateSlot(root, slot, Some(name), attributeFragment(name, value))
    case RenderChange.Replace(id, node) =>
      val matches = countTargets(root, id)
      if matches == 0 then Left(PhoenixEncodingError.UnknownTarget(id))
      else if matches > 1 then Left(PhoenixEncodingError.DuplicateTarget(id))
      else Right(replaceTarget(root, id, project(node)))

  private def updateSlot(
    root: ProjectedNode,
    slot: TemplateSlotId,
    expectedAttribute: Option[String],
    value: String
  ): Either[PhoenixEncodingError, ProjectedNode] =
    val occurrences = collectDynamics(root).count(_._1 == slot)
    if occurrences == 0 then Left(PhoenixEncodingError.UnknownSlot(slot))
    else if occurrences > 1 then Left(PhoenixEncodingError.DuplicateSlot(slot))
    else
      var mismatch                                 = false
      def loop(node: ProjectedNode): ProjectedNode = node.copy(parts = node.parts.map {
        case ProjectedPart.Dynamic(`slot`, _, attributeName) =>
          if attributeName != expectedAttribute then
            mismatch = true
            ProjectedPart.Dynamic(slot, value, attributeName)
          else ProjectedPart.Dynamic(slot, value, attributeName)
        case ProjectedPart.Node(child) => ProjectedPart.Node(loop(child))
        case part                      => part
      })
      val updated = loop(root)
      if mismatch then Left(PhoenixEncodingError.SlotKindMismatch(slot)) else Right(updated)

  private def countTargets(node: ProjectedNode, id: TemplateId): Int =
    (if node.id == id then 1 else 0) + node.parts.collect { case ProjectedPart.Node(child) =>
      countTargets(child, id)
    }.sum

  private def replaceTarget(
    node: ProjectedNode,
    id: TemplateId,
    replacement: ProjectedNode
  ): ProjectedNode =
    if node.id == id then replacement
    else
      node.copy(parts = node.parts.map {
        case ProjectedPart.Node(child) => ProjectedPart.Node(replaceTarget(child, id, replacement))
        case part                      => part
      })

  private def validate(root: ProjectedNode): Either[PhoenixEncodingError, Unit] =
    val ids   = mutable.HashSet.empty[TemplateId]
    val slots = mutable.HashSet.empty[TemplateSlotId]
    def loop(node: ProjectedNode): Either[PhoenixEncodingError, Unit] =
      if !ids.add(node.id) then Left(PhoenixEncodingError.DuplicateTarget(node.id))
      else
        node.parts.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
          case (result, ProjectedPart.Dynamic(slot, _, _)) =>
            result.flatMap(_ =>
              if slots.add(slot) then Right(()) else Left(PhoenixEncodingError.DuplicateSlot(slot))
            )
          case (result, ProjectedPart.Node(child)) => result.flatMap(_ => loop(child))
          case (result, _)                         => result
        }
    loop(root)

  private def detectDuplicateChanges(
    changes: Vector[RenderChange]
  ): Either[PhoenixEncodingError, Unit] =
    val slots   = mutable.HashSet.empty[TemplateSlotId]
    val targets = mutable.HashSet.empty[TemplateId]
    changes.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
      case (result, RenderChange.Text(slot, _, _)) =>
        result.flatMap(_ =>
          if slots.add(slot) then Right(()) else Left(PhoenixEncodingError.DuplicateSlot(slot))
        )
      case (result, RenderChange.Attribute(slot, _, _)) =>
        result.flatMap(_ =>
          if slots.add(slot) then Right(()) else Left(PhoenixEncodingError.DuplicateSlot(slot))
        )
      case (result, RenderChange.Replace(id, _)) =>
        result.flatMap(_ =>
          if targets.add(id) then Right(()) else Left(PhoenixEncodingError.DuplicateTarget(id))
        )
    }

  private def full(root: ProjectedNode): Json.Obj =
    val (statics, dynamics) = flatten(root)
    val fields              = Vector("s" -> Json.Arr(statics.map(Json.Str(_))*)) ++
      dynamics.zipWithIndex.map { case ((_, value), index) => index.toString -> Json.Str(value) }
    Json.Obj(fields*)

  private def sparse(root: ProjectedNode, changed: Set[TemplateSlotId]): Json.Obj =
    val (_, dynamics) = flatten(root)
    Json.Obj(dynamics.zipWithIndex.collect {
      case ((slot, value), index) if changed.contains(slot) => index.toString -> Json.Str(value)
    }*)

  private def flatten(root: ProjectedNode): (Vector[String], Vector[(TemplateSlotId, String)]) =
    val statics                           = Vector.newBuilder[String]
    val dynamics                          = Vector.newBuilder[(TemplateSlotId, String)]
    val current                           = StringBuilder()
    def append(node: ProjectedNode): Unit = node.parts.foreach {
      case ProjectedPart.Static(value)           => current.append(value)
      case ProjectedPart.Dynamic(slot, value, _) =>
        statics += current.result()
        current.clear()
        dynamics += slot -> value
      case ProjectedPart.Node(child) => append(child)
    }
    append(root)
    statics += current.result()
    statics.result() -> dynamics.result()

  private def collectDynamics(root: ProjectedNode): Vector[(TemplateSlotId, String)] =
    flatten(root)._2
end PhoenixRenderedEncoder
