package scalive.protocol.phoenix

import scala.collection.mutable

import zio.json.ast.Json

import scalive.ComponentTarget
import scalive.Escaping
import scalive.render.*
import scalive.streams.*

private[scalive] enum PhoenixEncodingError:
  case UnknownTarget(id: TemplateId)
  case DuplicateTarget(id: TemplateId)
  case UnknownSlot(slot: TemplateSlotId)
  case DuplicateSlot(slot: TemplateSlotId)
  case SlotKindMismatch(slot: TemplateSlotId)
  case UnresolvedComponent(id: TemplateId)
  case UnknownComponentToken
  case DuplicateComponentToken
  case DuplicateComponentRoot(cid: Int)
  case UnknownComponentTarget
  case ComponentIdExhausted
  case UnknownStreamTarget(id: TemplateId)
  case DuplicateStreamTarget(id: TemplateId)
  case UnknownStreamIdentity
  case DuplicateStreamIdentity
  case DuplicateStreamDomId(id: String)
  case InvalidStreamDomId
  case InvalidStreamPosition
  case InvalidStreamLimit
  case InvalidStreamRowTemplate
  case StreamRefExhausted

/** Connection-local projection state for Phoenix's rendered protocol and component CIDs. */
final private[scalive] class PhoenixRenderedState private[phoenix] (
  private[phoenix] val root: PhoenixRenderedEncoder.ProjectedNode,
  private[phoenix] val components: Map[Int, PhoenixRenderedEncoder.ProjectedComponent],
  private[phoenix] val tokenCids: Map[PhoenixRenderedEncoder.IdentityKey, Int],
  private[phoenix] val nextCid: Long,
  private[phoenix] val streamRefs: Map[PhoenixRenderedEncoder.IdentityKey, String],
  private[phoenix] val nextStreamRef: Long):

  private[scalive] def cidForToken(token: Object): Option[Int] =
    tokenCids.get(PhoenixRenderedEncoder.IdentityKey(token))

  private[scalive] def tokenForCid(cid: Int): Option[Object] = components.get(cid).map(_.token)

  private[scalive] def componentCid(token: Object): Option[Int] = cidForToken(token)

  private[scalive] def componentToken(cid: Int): Option[Object] = tokenForCid(cid)

  private[scalive] def streamRef(identity: LiveStreamIdentity): Option[String] =
    streamRefs.get(PhoenixRenderedEncoder.IdentityKey(identity))

private[scalive] object PhoenixRenderedEncoder:
  final private[phoenix] class IdentityKey(val value: Object):
    override def equals(other: Any): Boolean = other match
      case key: IdentityKey => value eq key.value
      case _                => false
    override def hashCode(): Int = System.identityHashCode(value)

  private[phoenix] object IdentityKey:
    def apply(value: Object): IdentityKey = new IdentityKey(value)

  private[phoenix] enum ProjectedValue:
    case Text(value: String)
    case ComponentTarget(ref: Object, identity: Object, name: String)

  private[phoenix] enum ProjectedPart:
    case Static(value: String)
    case Dynamic(slot: TemplateSlotId, value: ProjectedValue, attributeName: Option[String])
    case Target(value: ProjectedValue.ComponentTarget)
    case Node(value: ProjectedNode)
    case Component(cid: Int)
    case Stream(value: ProjectedStream)
    case StreamDomId(value: String)

  final private[phoenix] case class ProjectedNode(id: TemplateId, parts: Vector[ProjectedPart])
  final private[phoenix] case class ProjectedComponent(
    token: Object,
    ref: Object,
    refIdentity: Object,
    root: ProjectedNode)
  final private[phoenix] case class ProjectedStreamRow(domId: String, child: ProjectedNode)
  final private[phoenix] case class ProjectedStreamInsert(
    row: ProjectedStreamRow,
    at: StreamAt,
    limit: Option[StreamLimit],
    updateOnly: Boolean)
  final private[phoenix] case class ProjectedStreamOperations(
    inserts: Vector[ProjectedStreamInsert],
    deletes: Vector[String],
    reset: Boolean)
  final private[phoenix] case class ProjectedStream(
    id: TemplateId,
    identity: Object,
    ref: String,
    rows: Vector[ProjectedStreamRow],
    operations: ProjectedStreamOperations,
    hasTemplate: Boolean,
    includeStatics: Boolean)
  final private case class SparseChanges(
    slots: Set[TemplateSlotId],
    streams: Set[TemplateId])

  final private class ProjectionContext(state: Option[PhoenixRenderedState]):
    val components: mutable.Map[Int, ProjectedComponent] = mutable.Map.from(
      state.fold(Map.empty[Int, ProjectedComponent])(_.components)
    )
    val projectedCids: mutable.Set[Int]                         = mutable.Set.empty
    val allocatedCids: mutable.Set[Int]                         = mutable.Set.empty
    val componentDiffs: mutable.Map[Int, Option[SparseChanges]] = mutable.Map.empty
    private val existing              = state.fold(Map.empty[IdentityKey, Int])(_.tokenCids)
    private val newlyAllocated        = mutable.Map.empty[IdentityKey, Int]
    private var next                  = state.fold(1L)(_.nextCid)
    private val existingStreams       = state.fold(Map.empty[IdentityKey, String])(_.streamRefs)
    private val newlyAllocatedStreams = mutable.Map.empty[IdentityKey, String]
    private var nextStream            = state.fold(0L)(_.nextStreamRef)

    def nextCid: Long       = next
    def nextStreamRef: Long = nextStream

    def cid(token: Object): Either[PhoenixEncodingError, Int] =
      val key = IdentityKey(token)
      existing.get(key).orElse(newlyAllocated.get(key)) match
        case Some(value) => Right(value)
        case None        =>
          if next > Int.MaxValue.toLong then Left(PhoenixEncodingError.ComponentIdExhausted)
          else
            val value = next.toInt
            next += 1L
            allocatedCids += value
            newlyAllocated.update(key, value)
            Right(value)

    def streamRef(identity: Object): Either[PhoenixEncodingError, String] =
      val key = IdentityKey(identity)
      existingStreams.get(key).orElse(newlyAllocatedStreams.get(key)) match
        case Some(value) => Right(value)
        case None        =>
          if nextStream == Long.MaxValue then Left(PhoenixEncodingError.StreamRefExhausted)
          else
            val value = nextStream.toString
            nextStream += 1L
            newlyAllocatedStreams.update(key, value)
            Right(value)
  end ProjectionContext

  final private case class ProgramUpdate(
    root: ProjectedNode,
    changedSlots: Set[TemplateSlotId],
    structural: Boolean,
    changedComponents: Set[Int],
    changedStreams: Set[TemplateId] = Set.empty)

  def initial(
    tree: EvaluatedTree
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] =
    val context = ProjectionContext(None)
    for
      root  <- project(tree.root, None, context)
      state <- finish(root, context)
      json  <- fullPayload(state)
    yield clearPending(state) -> json

  /** Projects a disconnected render with fresh connection-local CIDs and inlines components. */
  private[scalive] def fullHtml(
    tree: EvaluatedTree
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, String)] =
    val context = ProjectionContext(None)
    for
      root  <- project(tree.root, None, context)
      state <- finish(root, context)
      html  <- renderHtml(state.root, state.components)
    yield clearPending(state) -> html

  def update(
    state: PhoenixRenderedState,
    delta: RenderDelta
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] = delta match
    case RenderDelta.Empty         => Right(state -> Json.Obj.empty)
    case RenderDelta.Replace(tree) =>
      val context = ProjectionContext(Some(state))
      for
        root <- project(tree.root, None, context)
        next <- finish(root, context)
        json <- fullPayload(next)
      yield clearPending(next) -> json
    case update: RenderDelta.Update =>
      val context = ProjectionContext(Some(state))
      for
        result <- applyDelta(state.root, update, None, context)
        next   <- finish(result.root, context)
        json   <-
          if result.structural then fullPayload(next)
          else
            val diffs = context.componentDiffs.toMap ++
              context.allocatedCids.iterator.map(_ -> Option.empty[SparseChanges])
            sparsePayload(next, SparseChanges(result.changedSlots, result.changedStreams), diffs)
      yield clearPending(next) -> json

  private val emptyStreamOperations =
    ProjectedStreamOperations(Vector.empty, Vector.empty, reset = false)

  private def clearPending(state: PhoenixRenderedState): PhoenixRenderedState =
    def clearNode(node: ProjectedNode): ProjectedNode = node.copy(parts = node.parts.map {
      case ProjectedPart.Node(child)    => ProjectedPart.Node(clearNode(child))
      case ProjectedPart.Stream(stream) =>
        ProjectedPart.Stream(
          stream.copy(
            rows = stream.rows.map(row => row.copy(child = clearNode(row.child))),
            operations = emptyStreamOperations,
            includeStatics = false
          )
        )
      case part => part
    })
    val root       = clearNode(state.root)
    val components = state.components.view
      .mapValues(component => component.copy(root = clearNode(component.root))).toMap
    PhoenixRenderedState(
      root,
      components,
      state.tokenCids,
      state.nextCid,
      state.streamRefs,
      state.nextStreamRef
    )

  private def project(
    node: EvaluatedNode,
    componentRootCid: Option[Int],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProjectedNode] = node match
    case text: EvaluatedNode.Text =>
      val value = if text.raw then text.value else Escaping.escape(text.value)
      val part  = text.slot match
        case Some(slot) => ProjectedPart.Dynamic(slot, ProjectedValue.Text(value), None)
        case None       => ProjectedPart.Static(value)
      Right(ProjectedNode(text.id, Vector(part)))
    case element: EvaluatedNode.Element =>
      val opening = Vector.newBuilder[ProjectedPart]
      opening += ProjectedPart.Static(s"<${element.tag}")
      val attributes = traverse(element.attributes)(projectAttribute)
      attributes.flatMap { projectedAttributes =>
        opening ++= projectedAttributes
        componentRootCid.foreach(cid =>
          opening += ProjectedPart.Static(s" data-phx-component=\"$cid\"")
        )
        opening += ProjectedPart.Static(">")
        traverse(element.children)(child => project(child, None, context)).map { children =>
          children.foreach(child => opening += ProjectedPart.Node(child))
          if !element.void then opening += ProjectedPart.Static(s"</${element.tag}>")
          ProjectedNode(element.id, opening.result())
        }
      }
    case flash: EvaluatedNode.Flash =>
      traverse(flash.child.toVector)(child => project(child, None, context)).map(children =>
        ProjectedNode(flash.id, children.map(ProjectedPart.Node(_)))
      )
    case choice: EvaluatedNode.Choice =>
      traverse(choice.child.toVector)(child => project(child, None, context)).map(children =>
        ProjectedNode(choice.id, children.map(ProjectedPart.Node(_)))
      )
    case keyed: EvaluatedNode.Keyed =>
      traverse(keyed.rows)(row => project(row.child, None, context)).map(children =>
        ProjectedNode(keyed.id, children.map(ProjectedPart.Node(_)))
      )
    case component: EvaluatedNode.Component =>
      component.resolution match
        case None             => Left(PhoenixEncodingError.UnresolvedComponent(component.id))
        case Some(resolution) =>
          for
            cid   <- context.cid(resolution.instanceToken)
            child <- project(resolution.child.root, Some(cid), context)
          yield
            val ref         = resolution.ref.asInstanceOf[Object]
            val refIdentity = resolution.ref.asInstanceOf[ComponentTarget].identity
            context.components.update(
              cid,
              ProjectedComponent(resolution.instanceToken, ref, refIdentity, child)
            )
            context.projectedCids += cid
            ProjectedNode(component.id, Vector(ProjectedPart.Component(cid)))
    case nested: EvaluatedNode.Nested => Right(ProjectedNode(nested.id, Vector.empty))
    case stream: EvaluatedNode.Stream =>
      projectStream(stream, context).map(value =>
        ProjectedNode(stream.id, Vector(ProjectedPart.Stream(value)))
      )

  private def projectStream(
    stream: EvaluatedNode.Stream,
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProjectedStream] =
    for
      ref     <- context.streamRef(stream.identity)
      rows    <- traverse(stream.rows)(row => projectStreamRow(row, context))
      _       <- validateDomIds(rows.map(_.domId))
      inserts <- traverse(stream.operations.inserts)(insert =>
                   validateStreamOperation(insert.at, insert.limit).flatMap { _ =>
                     projectStreamRow(insert.row, context).map(row =>
                       ProjectedStreamInsert(
                         row,
                         insert.at,
                         insert.limit,
                         insert.updateOnly
                       )
                     )
                   }
                 )
      _ <- validateDomIds(inserts.map(_.row.domId))
      _ <- validateDomIds(stream.operations.deletes)
    yield ProjectedStream(
      stream.id,
      stream.identity,
      ref,
      rows,
      ProjectedStreamOperations(inserts, stream.operations.deletes, stream.operations.reset),
      rows.nonEmpty || inserts.nonEmpty,
      includeStatics = true
    )

  private def projectStreamRow(
    row: EvaluatedNode.StreamRow,
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProjectedStreamRow] =
    project(row.child, None, context).map { child =>
      val idAttribute = s" id=\"${Escaping.escape(row.domId)}\""
      val parts       = child.parts.flatMap {
        case ProjectedPart.Static(`idAttribute`) =>
          Vector(
            ProjectedPart.Static(" id=\""),
            ProjectedPart.StreamDomId(Escaping.escape(row.domId)),
            ProjectedPart.Static("\"")
          )
        case part => Vector(part)
      }
      ProjectedStreamRow(row.domId, child.copy(parts = parts))
    }

  private def validateStreamOperation(
    at: StreamAt,
    limit: Option[StreamLimit]
  ): Either[PhoenixEncodingError, Unit] =
    at match
      case StreamAt.Index(value) if value < 0 => Left(PhoenixEncodingError.InvalidStreamPosition)
      case _                                  =>
        limit match
          case Some(StreamLimit.KeepFirst(count)) if count <= 0 =>
            Left(PhoenixEncodingError.InvalidStreamLimit)
          case Some(StreamLimit.KeepLast(count)) if count <= 0 =>
            Left(PhoenixEncodingError.InvalidStreamLimit)
          case _ => Right(())

  private def validateDomIds(ids: Iterable[String]): Either[PhoenixEncodingError, Unit] =
    val seen = mutable.HashSet.empty[String]
    ids.find(_.isEmpty) match
      case Some(_) => Left(PhoenixEncodingError.InvalidStreamDomId)
      case None    =>
        ids.find(id => !seen.add(id)) match
          case Some(id) => Left(PhoenixEncodingError.DuplicateStreamDomId(id))
          case None     => Right(())

  private def projectAttribute(
    attribute: EvaluatedAttribute
  ): Either[PhoenixEncodingError, ProjectedPart] =
    val value = projectAttributeValue(attribute.name, attribute.value)
    attribute.slot match
      case Some(slot) => Right(ProjectedPart.Dynamic(slot, value, Some(attribute.name)))
      case None       =>
        value match
          case ProjectedValue.Text(text)              => Right(ProjectedPart.Static(text))
          case target: ProjectedValue.ComponentTarget => Right(ProjectedPart.Target(target))

  private def projectAttributeValue(
    name: String,
    attributeValue: Option[AttributeValue]
  ): ProjectedValue =
    attributeValue match
      case None                            => ProjectedValue.Text("")
      case Some(AttributeValue.Presence)   => ProjectedValue.Text(s" $name")
      case Some(AttributeValue.Text(text)) =>
        ProjectedValue.Text(s" $name=\"${Escaping.escape(text)}\"")
      case Some(AttributeValue.ComponentTarget(ref)) =>
        ProjectedValue.ComponentTarget(
          ref.asInstanceOf[Object],
          ref.asInstanceOf[ComponentTarget].identity,
          name
        )

  private def applyDelta(
    root: ProjectedNode,
    delta: RenderDelta,
    ownerCid: Option[Int],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProgramUpdate] = delta match
    case RenderDelta.Empty => Right(ProgramUpdate(root, Set.empty, structural = false, Set.empty))
    case RenderDelta.Replace(tree) =>
      val previouslyProjected = context.projectedCids.toSet
      project(tree.root, ownerCid, context).map { projected =>
        val newlyProjected = context.projectedCids.toSet -- previouslyProjected
        ProgramUpdate(projected, Set.empty, structural = true, newlyProjected)
      }
    case RenderDelta.Update(_, changes) =>
      detectDuplicateChanges(changes).flatMap { _ =>
        changes.foldLeft[Either[PhoenixEncodingError, ProgramUpdate]](
          Right(ProgramUpdate(root, Set.empty, structural = false, Set.empty))
        ) { (result, change) =>
          result.flatMap(current => applyChange(current, change, ownerCid, context))
        }
      }

  private def applyChange(
    current: ProgramUpdate,
    change: RenderChange,
    ownerCid: Option[Int],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProgramUpdate] = change match
    case RenderChange.Text(slot, value, raw) =>
      updateSlot(
        current.root,
        slot,
        None,
        ProjectedValue.Text(if raw then value else Escaping.escape(value))
      )
        .map(root => current.copy(root = root, changedSlots = current.changedSlots + slot))
    case RenderChange.Attribute(slot, name, value) =>
      updateSlot(current.root, slot, Some(name), projectAttributeValue(name, value))
        .map(root => current.copy(root = root, changedSlots = current.changedSlots + slot))
    case RenderChange.Replace(id, node) =>
      val matches = countTargets(current.root, id)
      if matches == 0 then Left(PhoenixEncodingError.UnknownTarget(id))
      else if matches > 1 then Left(PhoenixEncodingError.DuplicateTarget(id))
      else
        val replacementRootCid  = if current.root.id == id then ownerCid else None
        val previouslyProjected = context.projectedCids.toSet
        project(node, replacementRootCid, context).map { replacement =>
          val newlyProjected = context.projectedCids.toSet -- previouslyProjected
          current.copy(
            root = replaceTarget(current.root, id, replacement),
            structural = true,
            changedComponents = current.changedComponents ++ newlyProjected
          )
        }
    case RenderChange.Stream(id, identity, _, operations) =>
      val targets = collectStreams(current.root).filter(_.id == id)
      targets match
        case Vector() => Left(PhoenixEncodingError.UnknownStreamTarget(id))
        case Vector(target) if !(target.identity eq identity) =>
          Left(PhoenixEncodingError.UnknownStreamIdentity)
        case Vector(target) =>
          val previouslyProjected = context.projectedCids.toSet
          projectStreamOperations(operations, context).map { projectedOperations =>
            val updated        = applyStreamOperations(target, projectedOperations)
            val newlyProjected = context.projectedCids.toSet -- previouslyProjected
            current.copy(
              root = replaceStream(current.root, id, identity, updated),
              changedComponents = current.changedComponents ++ newlyProjected,
              changedStreams = current.changedStreams + id
            )
          }
        case _ => Left(PhoenixEncodingError.DuplicateStreamTarget(id))
    case RenderChange.Component(token, delta) =>
      val matches = componentCids(current.root).filter(cid =>
        context.components.get(cid).exists(component => component.token eq token)
      )
      matches match
        case Vector()    => Left(PhoenixEncodingError.UnknownComponentToken)
        case Vector(cid) =>
          val component = context.components(cid)
          applyDelta(component.root, delta, Some(cid), context).map { update =>
            context.components.update(cid, component.copy(root = update.root))
            val ownChange =
              update.structural || update.changedSlots.nonEmpty || update.changedStreams.nonEmpty
            if ownChange then
              context.componentDiffs.update(
                cid,
                if update.structural then None
                else Some(SparseChanges(update.changedSlots, update.changedStreams))
              )
            if update.structural then
              update.changedComponents.foreach(nested =>
                context.componentDiffs.update(nested, None)
              )
            current.copy(
              changedComponents = current.changedComponents ++ update.changedComponents ++
                (if ownChange then Set(cid) else Set.empty)
            )
          }
        case _ => Left(PhoenixEncodingError.DuplicateComponentToken)

  private def projectStreamOperations(
    operations: EvaluatedNode.StreamOperations,
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProjectedStreamOperations] =
    for
      inserts <- traverse(operations.inserts)(insert =>
                   validateStreamOperation(insert.at, insert.limit).flatMap { _ =>
                     projectStreamRow(insert.row, context).map(row =>
                       ProjectedStreamInsert(
                         row,
                         insert.at,
                         insert.limit,
                         insert.updateOnly
                       )
                     )
                   }
                 )
      _ <- validateDomIds(inserts.map(_.row.domId))
      _ <- validateDomIds(operations.deletes)
    yield ProjectedStreamOperations(inserts, operations.deletes, operations.reset)

  private def applyStreamOperations(
    stream: ProjectedStream,
    operations: ProjectedStreamOperations
  ): ProjectedStream =
    var rows = if operations.reset then Vector.empty else stream.rows
    operations.deletes.foreach(domId => rows = rows.filterNot(_.domId == domId))
    operations.inserts.foreach { insert =>
      val existing = rows.indexWhere(_.domId == insert.row.domId)
      if existing >= 0 then rows = rows.updated(existing, insert.row)
      else if !insert.updateOnly then
        val index = insert.at match
          case StreamAt.First        => 0
          case StreamAt.Last         => rows.length
          case StreamAt.Index(value) => math.min(value, rows.length)
        rows = rows.patch(index, Vector(insert.row), 0)
      insert.limit.foreach {
        case StreamLimit.KeepFirst(count) => rows = rows.take(count)
        case StreamLimit.KeepLast(count)  => rows = rows.takeRight(count)
      }
    }
    stream.copy(
      rows = rows,
      operations = operations,
      hasTemplate = stream.hasTemplate || operations.inserts.nonEmpty,
      includeStatics = !stream.hasTemplate && operations.inserts.nonEmpty
    )

  private def updateSlot(
    root: ProjectedNode,
    slot: TemplateSlotId,
    expectedAttribute: Option[String],
    value: ProjectedValue
  ): Either[PhoenixEncodingError, ProjectedNode] =
    val occurrences = collectDynamics(root).count(_._1 == slot)
    if occurrences == 0 then Left(PhoenixEncodingError.UnknownSlot(slot))
    else if occurrences > 1 then Left(PhoenixEncodingError.DuplicateSlot(slot))
    else
      var mismatch                                 = false
      def loop(node: ProjectedNode): ProjectedNode = node.copy(parts = node.parts.map {
        case ProjectedPart.Dynamic(`slot`, _, attributeName) =>
          if attributeName != expectedAttribute then mismatch = true
          ProjectedPart.Dynamic(slot, value, attributeName)
        case ProjectedPart.Node(child)    => ProjectedPart.Node(loop(child))
        case ProjectedPart.Stream(stream) =>
          ProjectedPart.Stream(
            stream.copy(rows = stream.rows.map(row => row.copy(child = loop(row.child))))
          )
        case part => part
      })
      val updated = loop(root)
      if mismatch then Left(PhoenixEncodingError.SlotKindMismatch(slot)) else Right(updated)

  private def finish(
    root: ProjectedNode,
    context: ProjectionContext
  ): Either[PhoenixEncodingError, PhoenixRenderedState] =
    for
      reachable <- validateGraph(root, context.components.toMap)
      active = context.components.toMap.view.filterKeys(reachable.contains).toMap
      tokens =
        active.iterator.map { case (cid, component) => IdentityKey(component.token) -> cid }.toMap
      streams = collectStreams(root) ++ active.valuesIterator.flatMap(component =>
                  collectStreams(component.root)
                )
      streamRefs = streams.iterator.map(stream => IdentityKey(stream.identity) -> stream.ref).toMap
    yield PhoenixRenderedState(
      root,
      active,
      tokens,
      context.nextCid,
      streamRefs,
      context.nextStreamRef
    )

  private def validateGraph(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, Set[Int]] =
    val seenCids    = mutable.HashSet.empty[Int]
    val seenTokens  = mutable.HashSet.empty[IdentityKey]
    val seenStreams = mutable.HashSet.empty[IdentityKey]
    val targets     = Vector.newBuilder[ProjectedValue.ComponentTarget]

    def loopProgram(program: ProjectedNode): Either[PhoenixEncodingError, Unit] =
      validateProgram(program).flatMap { _ =>
        val duplicateStream =
          collectStreams(program).find(stream => !seenStreams.add(IdentityKey(stream.identity)))
        if duplicateStream.nonEmpty then Left(PhoenixEncodingError.DuplicateStreamIdentity)
        else
          collectTargets(program).foreach(targets += _)
          componentCids(program).foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
            (result, cid) =>
              result.flatMap { _ =>
                components.get(cid) match
                  case None            => Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
                  case Some(component) =>
                    if !seenCids.add(cid) then
                      Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
                    else if !seenTokens.add(IdentityKey(component.token)) then
                      Left(PhoenixEncodingError.DuplicateComponentToken)
                    else loopProgram(component.root)
              }
          }
      }

    loopProgram(root).flatMap { _ =>
      val active = seenCids.toSet
      targets
        .result().foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) { (result, target) =>
          result.flatMap { _ =>
            val matches = active.count { cid =>
              val component = components(cid)
              (component.ref eq target.ref) || (component.token eq target.identity) ||
              (component.refIdentity eq target.identity)
            }
            if matches == 1 then Right(()) else Left(PhoenixEncodingError.UnknownComponentTarget)
          }
        }.map(_ => active)
    }
  end validateGraph

  private def validateProgram(root: ProjectedNode): Either[PhoenixEncodingError, Unit] =
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
          case (result, ProjectedPart.Node(child))    => result.flatMap(_ => loop(child))
          case (result, ProjectedPart.Stream(stream)) =>
            stream.rows.foldLeft(result)((current, row) => current.flatMap(_ => loop(row.child)))
          case (result, _) => result
        }
    loop(root)

  private def fullPayload(state: PhoenixRenderedState): Either[PhoenixEncodingError, Json.Obj] =
    full(state.root, state.components).flatMap { root =>
      if state.components.isEmpty then Right(root)
      else
        componentObject(state.components, state.components.keySet).map(value =>
          root.add("c", value)
        )
    }

  private def sparsePayload(
    state: PhoenixRenderedState,
    changed: SparseChanges,
    changedComponents: Map[Int, Option[SparseChanges]]
  ): Either[PhoenixEncodingError, Json.Obj] =
    sparse(state.root, changed, state.components).flatMap { root =>
      val activeChanges = changedComponents.view.filterKeys(state.components.contains).toMap
      if activeChanges.isEmpty then Right(root)
      else componentDiffs(state.components, activeChanges).map(value => root.add("c", value))
    }

  private def componentObject(
    components: Map[Int, ProjectedComponent],
    cids: Set[Int]
  ): Either[PhoenixEncodingError, Json.Obj] =
    traverse(cids.toVector.sorted)(cid =>
      full(components(cid).root, components).map(value => cid.toString -> value)
    ).map(fields => Json.Obj(fields*))

  private def componentDiffs(
    components: Map[Int, ProjectedComponent],
    cids: Map[Int, Option[SparseChanges]]
  ): Either[PhoenixEncodingError, Json.Obj] =
    traverse(cids.toVector.sortBy(_._1)) { case (cid, slots) =>
      slots
        .fold(full(components(cid).root, components))(changed =>
          sparse(components(cid).root, changed, components)
        ).map(value => cid.toString -> value)
    }.map(fields => Json.Obj(fields*))

  private def full(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, Json.Obj] = flatten(root, components, None).map {
    case (statics, dynamics) =>
      val fields = Vector("s" -> Json.Arr(statics.map(Json.Str(_))*)) ++
        dynamics.zipWithIndex.map { case ((_, value), index) => index.toString -> value }
      Json.Obj(fields*)
  }

  private def sparse(
    root: ProjectedNode,
    changed: SparseChanges,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, Json.Obj] = flatten(root, components, Some(changed)).map {
    case (_, dynamics) =>
      Json.Obj(dynamics.zipWithIndex.collect {
        case ((Some(slot), value), index) if changed.slots.contains(slot) => index.toString -> value
        case ((None, value: Json.Obj), index) if value != Json.Obj.empty  => index.toString -> value
      }*)
  }

  private def flatten(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent],
    sparseChanges: Option[SparseChanges]
  ): Either[PhoenixEncodingError, (Vector[String], Vector[(Option[TemplateSlotId], Json)])] =
    val statics  = Vector.newBuilder[String]
    val dynamics = Vector.newBuilder[(Option[TemplateSlotId], Json)]
    val current  = StringBuilder()

    def dynamic(slot: Option[TemplateSlotId], value: Json): Unit =
      statics += current.result()
      current.clear()
      dynamics += slot -> value

    def loop(node: ProjectedNode): Either[PhoenixEncodingError, Unit] =
      node.parts.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
        case (result, ProjectedPart.Static(value)) =>
          result.map(_ => current.append(value)).map(_ => ())
        case (result, ProjectedPart.Dynamic(slot, ProjectedValue.Text(value), _)) =>
          result.map(_ => dynamic(Some(slot), Json.Str(value)))
        case (result, ProjectedPart.Dynamic(slot, value: ProjectedValue.ComponentTarget, _)) =>
          result.flatMap(_ =>
            renderTarget(value, components).map(text => dynamic(Some(slot), Json.Str(text)))
          )
        case (result, ProjectedPart.Target(value)) =>
          result.flatMap(_ => renderTarget(value, components).map(current.append).map(_ => ()))
        case (result, ProjectedPart.Node(child))    => result.flatMap(_ => loop(child))
        case (result, ProjectedPart.Component(cid)) => result.map(_ => dynamic(None, Json.Num(cid)))
        case (result, ProjectedPart.StreamDomId(value)) =>
          result.map(_ => dynamic(None, Json.Str(value)))
        case (result, ProjectedPart.Stream(stream)) =>
          result.flatMap(_ =>
            streamJson(stream, components, sparseChanges).map(value => dynamic(None, value))
          )
      }

    loop(root).map { _ =>
      statics += current.result()
      statics.result() -> dynamics.result()
    }
  end flatten

  private def streamJson(
    stream: ProjectedStream,
    components: Map[Int, ProjectedComponent],
    sparseChanges: Option[SparseChanges]
  ): Either[PhoenixEncodingError, Json.Obj] =
    val selectedRows = sparseChanges match
      case None                                                 => stream.rows
      case Some(changes) if changes.streams.contains(stream.id) =>
        val inserted    = stream.operations.inserts.map(_.row)
        val insertedIds = inserted.map(_.domId).toSet
        inserted ++ stream.rows.filter(row =>
          !insertedIds.contains(row.domId) && rowContainsChangedSlot(row.child, changes.slots)
        )
      case Some(changes) =>
        stream.rows.filter(row => rowContainsChangedSlot(row.child, changes.slots))

    val shouldEmit = sparseChanges.isEmpty || selectedRows.nonEmpty ||
      sparseChanges.exists(_.streams.contains(stream.id))
    if !shouldEmit then Right(Json.Obj.empty)
    else
      val templateRows = selectedRows ++ stream.rows
      val flattened    = traverse(templateRows)(row => flatten(row.child, components, None))
      flattened.flatMap { rows =>
        val statics = rows.headOption.map(_._1).getOrElse(Vector(""))
        if rows.exists(_._1 != statics) then Left(PhoenixEncodingError.InvalidStreamRowTemplate)
        else
          val selectedCount    = selectedRows.length
          val selectedPayloads = rows.take(selectedCount).map { case (_, dynamics) =>
            Json.Obj(dynamics.zipWithIndex.map { case ((_, value), index) =>
              index.toString -> value
            }*)
          }
          val keyedFields = selectedPayloads.zipWithIndex.map { case (value, index) =>
            index.toString -> value
          } :+ ("kc" -> Json.Num(selectedCount))
          val metadata       = streamMetadata(stream)
          val includeStatics = sparseChanges.isEmpty || stream.includeStatics
          val fields         =
            (if includeStatics then Vector("s" -> Json.Arr(statics.map(Json.Str(_))*))
             else Vector.empty) ++
              Vector("k" -> Json.Obj(keyedFields*), "stream" -> metadata)
          Right(Json.Obj(fields*))
      }
  end streamJson

  private def streamMetadata(stream: ProjectedStream): Json.Arr =
    val inserts = stream.operations.inserts.map { insert =>
      val at = insert.at match
        case StreamAt.First        => 0
        case StreamAt.Last         => -1
        case StreamAt.Index(value) => value
      val limit = insert.limit match
        case None                               => Json.Null
        case Some(StreamLimit.KeepFirst(count)) => Json.Num(count)
        case Some(StreamLimit.KeepLast(count))  => Json.Num(-count)
      Json.Arr(
        Json.Str(insert.row.domId),
        Json.Num(at),
        limit,
        Json.Bool(insert.updateOnly)
      )
    }
    val values = Vector[Json](
      Json.Str(stream.ref),
      Json.Arr(inserts*),
      Json.Arr(stream.operations.deletes.map(Json.Str(_))*)
    ) ++ (if stream.operations.reset then Vector(Json.Bool(true)) else Vector.empty)
    Json.Arr(values*)

  private def renderHtml(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, String] =
    val output = StringBuilder()

    def loop(node: ProjectedNode): Either[PhoenixEncodingError, Unit] =
      node.parts.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
        case (result, ProjectedPart.Static(value)) =>
          result.map(_ => output.append(value)).map(_ => ())
        case (result, ProjectedPart.Dynamic(_, ProjectedValue.Text(value), _)) =>
          result.map(_ => output.append(value)).map(_ => ())
        case (result, ProjectedPart.Dynamic(_, value: ProjectedValue.ComponentTarget, _)) =>
          result.flatMap(_ => renderTarget(value, components).map(output.append).map(_ => ()))
        case (result, ProjectedPart.Target(value)) =>
          result.flatMap(_ => renderTarget(value, components).map(output.append).map(_ => ()))
        case (result, ProjectedPart.Node(child))    => result.flatMap(_ => loop(child))
        case (result, ProjectedPart.Component(cid)) =>
          result.flatMap(_ =>
            components.get(cid) match
              case Some(component) => loop(component.root)
              case None            => Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
          )
        case (result, ProjectedPart.Stream(stream)) =>
          stream.rows.foldLeft(result)((current, row) => current.flatMap(_ => loop(row.child)))
        case (result, ProjectedPart.StreamDomId(value)) =>
          result.map(_ => output.append(value)).map(_ => ())
      }

    loop(root).map(_ => output.result())
  end renderHtml

  private def renderTarget(
    value: ProjectedValue.ComponentTarget,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, String] =
    val matches = components.iterator.collect {
      case (cid, component)
          if (component.ref eq value.ref) || (component.token eq value.identity) ||
            (component.refIdentity eq value.identity) =>
        cid
    }.toVector
    matches match
      case Vector(cid) => Right(s" ${value.name}=\"$cid\"")
      case _           => Left(PhoenixEncodingError.UnknownComponentTarget)

  private def countTargets(node: ProjectedNode, id: TemplateId): Int =
    (if node.id == id then 1 else 0) + node.parts.map {
      case ProjectedPart.Node(child)    => countTargets(child, id)
      case ProjectedPart.Stream(stream) => stream.rows.map(row => countTargets(row.child, id)).sum
      case _                            => 0
    }.sum

  private def replaceTarget(node: ProjectedNode, id: TemplateId, replacement: ProjectedNode)
    : ProjectedNode =
    if node.id == id then replacement
    else
      node.copy(parts = node.parts.map {
        case ProjectedPart.Node(child) => ProjectedPart.Node(replaceTarget(child, id, replacement))
        case ProjectedPart.Stream(stream) =>
          ProjectedPart.Stream(
            stream.copy(rows =
              stream.rows.map(row => row.copy(child = replaceTarget(row.child, id, replacement)))
            )
          )
        case part => part
      })

  private def replaceStream(
    node: ProjectedNode,
    id: TemplateId,
    identity: Object,
    replacement: ProjectedStream
  ): ProjectedNode = node.copy(parts = node.parts.map {
    case ProjectedPart.Stream(stream) if stream.id == id && (stream.identity eq identity) =>
      ProjectedPart.Stream(replacement)
    case ProjectedPart.Stream(stream) =>
      ProjectedPart.Stream(
        stream.copy(rows =
          stream.rows.map(row =>
            row.copy(child = replaceStream(row.child, id, identity, replacement))
          )
        )
      )
    case ProjectedPart.Node(child) =>
      ProjectedPart.Node(replaceStream(child, id, identity, replacement))
    case part => part
  })

  private def componentCids(root: ProjectedNode): Vector[Int] =
    root.parts.flatMap {
      case ProjectedPart.Component(cid) => Vector(cid)
      case ProjectedPart.Node(child)    => componentCids(child)
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => componentCids(row.child))
      case _                            => Vector.empty
    }

  private def collectDynamics(root: ProjectedNode): Vector[(TemplateSlotId, ProjectedValue)] =
    root.parts.flatMap {
      case ProjectedPart.Dynamic(slot, value, _) => Vector(slot -> value)
      case ProjectedPart.Node(child)             => collectDynamics(child)
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectDynamics(row.child))
      case _                            => Vector.empty
    }

  private def collectTargets(root: ProjectedNode): Vector[ProjectedValue.ComponentTarget] =
    root.parts.flatMap {
      case ProjectedPart.Target(value)                                        => Vector(value)
      case ProjectedPart.Dynamic(_, value: ProjectedValue.ComponentTarget, _) => Vector(value)
      case ProjectedPart.Node(child)    => collectTargets(child)
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectTargets(row.child))
      case _                            => Vector.empty
    }

  private def collectStreams(root: ProjectedNode): Vector[ProjectedStream] =
    root.parts.flatMap {
      case ProjectedPart.Stream(stream) =>
        stream +: stream.rows.flatMap(row => collectStreams(row.child))
      case ProjectedPart.Node(child) => collectStreams(child)
      case _                         => Vector.empty
    }

  private def rowContainsChangedSlot(
    root: ProjectedNode,
    changed: Set[TemplateSlotId]
  ): Boolean = collectDynamics(root).exists((slot, _) => changed.contains(slot))

  private def detectDuplicateChanges(changes: Vector[RenderChange])
    : Either[PhoenixEncodingError, Unit] =
    val slots            = mutable.HashSet.empty[TemplateSlotId]
    val targets          = mutable.HashSet.empty[TemplateId]
    val tokens           = mutable.HashSet.empty[IdentityKey]
    val streamTargets    = mutable.HashSet.empty[TemplateId]
    val streamIdentities = mutable.HashSet.empty[IdentityKey]
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
      case (result, RenderChange.Stream(id, identity, _, _)) =>
        result.flatMap { _ =>
          if !streamTargets.add(id) then Left(PhoenixEncodingError.DuplicateStreamTarget(id))
          else if !streamIdentities.add(IdentityKey(identity)) then
            Left(PhoenixEncodingError.DuplicateStreamIdentity)
          else Right(())
        }
      case (result, RenderChange.Component(token, _)) =>
        result.flatMap(_ =>
          if tokens.add(IdentityKey(token)) then Right(())
          else Left(PhoenixEncodingError.DuplicateComponentToken)
        )
    }
  end detectDuplicateChanges

  private def traverse[A, B](
    values: Iterable[A]
  )(
    f: A => Either[PhoenixEncodingError, B]
  ): Either[PhoenixEncodingError, Vector[B]] =
    values.foldLeft[Either[PhoenixEncodingError, Vector[B]]](Right(Vector.empty)) { (result, value) =>
      result.flatMap(acc => f(value).map(acc :+ _))
    }
end PhoenixRenderedEncoder
