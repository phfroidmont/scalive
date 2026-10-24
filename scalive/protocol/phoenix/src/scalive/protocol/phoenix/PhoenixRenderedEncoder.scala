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
  case UnresolvedNested(id: TemplateId)
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
  case InvalidKeyedRowChange
  case StreamRefExhausted

/** Connection-local projection state for Phoenix's rendered protocol and component CIDs. */
final private[scalive] class PhoenixRenderedState private[phoenix] (
  private[phoenix] val root: PhoenixRenderedEncoder.ProjectedNode,
  private[phoenix] val components: Map[Int, PhoenixRenderedEncoder.ProjectedComponent],
  private[phoenix] val activeCids: Set[Int],
  private[phoenix] val tokenCids: Map[PhoenixRenderedEncoder.IdentityKey, Int],
  private[phoenix] val nextCid: Long,
  private[phoenix] val streamRefs: Map[PhoenixRenderedEncoder.IdentityKey, String],
  private[phoenix] val nextStreamRef: Long,
  private[phoenix] val csrfToken: Option[String],
  private[phoenix] val pendingDeletionCids: Set[Int]):

  private[scalive] def cidForToken(token: Object): Option[Int] =
    tokenCids.get(PhoenixRenderedEncoder.IdentityKey(token))

  private[scalive] def tokenForCid(cid: Int): Option[Object] = components.get(cid).map(_.token)

  private[scalive] def componentCid(token: Object): Option[Int] = cidForToken(token)

  private[scalive] def componentToken(cid: Int): Option[Object] = tokenForCid(cid)

  private[scalive] def streamRef(identity: LiveStreamIdentity): Option[String] =
    streamRefs.get(PhoenixRenderedEncoder.IdentityKey(identity))

  private[scalive] def markComponentsForDeletion(cids: Vector[Int]): PhoenixRenderedState =
    PhoenixRenderedState(
      root,
      components,
      activeCids,
      tokenCids,
      nextCid,
      streamRefs,
      nextStreamRef,
      csrfToken,
      pendingDeletionCids ++ cids.filter(components.contains)
    )

  private[scalive] def destroyComponents(
    cids: Vector[Int]
  ): (PhoenixRenderedState, Vector[Int]) =
    val acknowledged = cids.distinct.filter(pendingDeletionCids.contains)
    val deleted = acknowledged.filter(cid => components.contains(cid) && !activeCids.contains(cid))
    val deletedSet         = deleted.toSet
    val retained           = components -- deletedSet
    val retainedTokenCids  = tokenCids.filterNot((_, cid) => deletedSet.contains(cid))
    val retainedStreamRefs = PhoenixRenderedEncoder.retainStreamRefs(
      root,
      retained,
      streamRefs
    )
    PhoenixRenderedState(
      root,
      retained,
      activeCids -- deletedSet,
      retainedTokenCids,
      nextCid,
      retainedStreamRefs,
      nextStreamRef,
      csrfToken,
      pendingDeletionCids -- acknowledged
    ) -> deleted
end PhoenixRenderedState

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

    /** A finalized disconnected child program. Its template ids and slots have their own namespace.
      */
    case Nested(value: ProjectedNode)
    case Component(cid: Int)
    case Keyed(value: ProjectedKeyed)
    case Stream(value: ProjectedStream)
    case StreamDomId(value: String)

  /** A projected node. One with `ownStatics` is sent within its parent as a nested rendered object
    * with statics of its own, so replacing it resends that object and nothing around it.
    */
  final private[phoenix] case class ProjectedNode(
    id: TemplateId,
    parts: Vector[ProjectedPart],
    ownStatics: Boolean = false):
    // Computed once per instance. An update copies only the nodes on the path to what it changes,
    // so the summaries of everything else carry over.
    private[phoenix] lazy val summary: Summary = Summary.of(this)

    /** Whether the template id `target` may be in this program subtree. */
    private[phoenix] def mayHoldId(target: TemplateId): Boolean =
      summary.idMin <= target.value && target.value <= summary.idMax

    /** Whether the slot `target` may be in this program subtree. */
    private[phoenix] def mayHoldSlot(target: TemplateSlotId): Boolean =
      summary.slotMin <= target.value && target.value <= summary.slotMax

  /** What one projected subtree holds.
    *
    * The bounds cover the template ids and slots of the subtree's own program: nested LiveViews
    * have their own namespace. Ids are allocated as templates compile, so the bounds of siblings
    * rarely overlap, and a search descends only where the target may be.
    *
    * @param cids
    *   the CIDs the subtree holds, nested LiveViews included but not the components' own content
    * @param streams
    *   the streams the subtree holds, nested LiveViews and stream rows included
    * @param dynamics
    *   how many dynamics the subtree adds to the rendered object holding it
    */
  final private[phoenix] case class Summary(
    idMin: Long,
    idMax: Long,
    slotMin: Long,
    slotMax: Long,
    cids: Set[Int],
    streams: Vector[ProjectedStream],
    dynamics: Int)

  private[phoenix] object Summary:
    def of(node: ProjectedNode): Summary =
      var idMin    = node.id.value
      var idMax    = node.id.value
      var slotMin  = Long.MaxValue
      var slotMax  = Long.MinValue
      var cids     = Set.empty[Int]
      var streams  = Vector.empty[ProjectedStream]
      var dynamics = 0

      def include(child: ProjectedNode, sameProgram: Boolean): Unit =
        val summary = child.summary
        if sameProgram then
          idMin = math.min(idMin, summary.idMin)
          idMax = math.max(idMax, summary.idMax)
          slotMin = math.min(slotMin, summary.slotMin)
          slotMax = math.max(slotMax, summary.slotMax)
        if summary.cids.nonEmpty then cids = cids ++ summary.cids
        if summary.streams.nonEmpty then streams = streams ++ summary.streams

      node.parts.foreach {
        case ProjectedPart.Static(_) | ProjectedPart.Target(_) => ()
        case ProjectedPart.Dynamic(slot, _, _)                 =>
          slotMin = math.min(slotMin, slot.value)
          slotMax = math.max(slotMax, slot.value)
          dynamics += 1
        case ProjectedPart.Node(child) =>
          include(child, sameProgram = true)
          dynamics += (if child.ownStatics then 1 else child.summary.dynamics)
        case ProjectedPart.Nested(child) =>
          include(child, sameProgram = false)
          dynamics += child.summary.dynamics
        case ProjectedPart.Component(cid) =>
          cids = cids + cid
          dynamics += 1
        case ProjectedPart.Keyed(keyed) =>
          keyed.rows.foreach(row => include(row.child, sameProgram = true))
          dynamics += 1
        case ProjectedPart.Stream(stream) =>
          streams = streams :+ stream
          stream.rows.foreach(row => include(row.child, sameProgram = true))
          dynamics += 1
        case ProjectedPart.StreamDomId(_) => dynamics += 1
      }
      Summary(idMin, idMax, slotMin, slotMax, cids, streams, dynamics)
    end of
  end Summary
  final private[phoenix] case class ProjectedComponent(
    token: Object,
    ref: Object,
    refIdentity: Object,
    root: ProjectedNode)
  final private[phoenix] case class ProjectedStreamRow(domId: String, child: ProjectedNode)
  final private[phoenix] case class ProjectedKeyedRow(id: RowId, child: ProjectedNode)
  final private[phoenix] case class ProjectedKeyedRowUpdate(
    oldIndex: Option[Int],
    changes: Option[SparseChanges],
    full: Boolean)
  final private[phoenix] case class ProjectedKeyed(
    id: TemplateId,
    rows: Vector[ProjectedKeyedRow],
    updates: Vector[Option[ProjectedKeyedRowUpdate]],
    includeStatics: Boolean,
    changed: Boolean)
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
    // The row statics the client last received, or None before they are sent.
    sentStatics: Option[Vector[String]])
  final private[phoenix] case class SparseChanges(
    slots: Set[TemplateSlotId],
    streams: Set[TemplateId],
    keyedTargets: Set[TemplateId] = Set.empty,
    // Nodes the browser has to receive again in full.
    replaced: Set[TemplateId] = Set.empty,
    // Components whose CID slot the diff sends again, only so that it reaches them.
    touchedComponents: Set[Int] = Set.empty)

  /** The components one render reaches, with the index its `phx-target` values resolve against. */
  final private class Components(val byCid: Map[Int, ProjectedComponent]):
    def apply(cid: Int): ProjectedComponent       = byCid(cid)
    def get(cid: Int): Option[ProjectedComponent] = byCid.get(cid)
    def isEmpty: Boolean                          = byCid.isEmpty

    // A target names its component by the component's ref, or by its token or ref identity.
    private lazy val byRef      = index(component => Vector(component.ref))
    private lazy val byIdentity = index(component => Vector(component.token, component.refIdentity))

    /** The CID of the one component `target` names, or None when it names none or several. */
    def targeted(target: ProjectedValue.ComponentTarget): Option[Int] =
      val cids = byRef.getOrElse(IdentityKey(target.ref), Set.empty) ++
        byIdentity.getOrElse(IdentityKey(target.identity), Set.empty)
      Option.when(cids.size == 1)(cids.head)

    private def index(keys: ProjectedComponent => Vector[Object]): Map[IdentityKey, Set[Int]] =
      byCid.toVector
        .flatMap((cid, component) => keys(component).map(key => IdentityKey(key) -> cid))
        .groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  /** The statics of the nested templates in one comprehension, sent once as its `p`. */
  final private class TemplateTable:
    private val indexes = mutable.LinkedHashMap.empty[Vector[String], Int]

    def reference(statics: Vector[String]): Int = indexes.getOrElseUpdate(statics, indexes.size)

    def toJson: Option[Json.Obj] =
      if indexes.isEmpty then None
      else
        Some(Json.Obj(indexes.toVector.map { case (statics, index) =>
          index.toString -> Json.Arr(statics.map(Json.Str(_))*)
        }*))

  final private class ProjectionContext(
    state: Option[PhoenixRenderedState],
    initialCsrfToken: Option[String] = None):
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
    val pendingDeletionCids: Set[Int] = state.fold(Set.empty[Int])(_.pendingDeletionCids)

    val csrfToken: Option[String] = state.flatMap(_.csrfToken).orElse(initialCsrfToken)

    def nextCid: Long       = next
    def nextStreamRef: Long = nextStream

    /** The CID of `token` when it has one, without allocating one. */
    def knownCid(token: Object): Option[Int] =
      val key = IdentityKey(token)
      existing.get(key).orElse(newlyAllocated.get(key))

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
    changedStreams: Set[TemplateId] = Set.empty,
    changedKeyedTargets: Set[TemplateId] = Set.empty,
    replaced: Set[TemplateId] = Set.empty):
    def changes: SparseChanges =
      SparseChanges(changedSlots, changedStreams, changedKeyedTargets, replaced)

  def initial(
    tree: EvaluatedTree,
    csrfToken: Option[String] = None
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] =
    val context = ProjectionContext(None, csrfToken)
    for
      root  <- project(tree.root, None, context)
      state <- finish(root, context, validate = true)
      json  <- fullPayload(state)
    yield clearPending(None, state) -> json

  /** Projects a disconnected render with fresh connection-local CIDs and inlines components. */
  private[scalive] def fullHtml(
    tree: EvaluatedTree,
    csrfToken: Option[String] = None
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, String)] =
    val context = ProjectionContext(None, csrfToken)
    for
      root  <- project(tree.root, None, context)
      state <- finish(root, context, validate = true)
      html  <- renderHtml(state.root, Components(state.components))
    yield clearPending(None, state) -> html

  def update(
    state: PhoenixRenderedState,
    delta: RenderDelta
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] = delta match
    case RenderDelta.Empty         => Right(state -> Json.Obj.empty)
    case RenderDelta.Replace(tree) =>
      val context = ProjectionContext(Some(state))
      for
        root <- project(tree.root, None, context)
        next <- finish(root, context, validate = true)
        json <- fullPayload(next)
      yield clearPending(Some(state), next) -> json

    case update: RenderDelta.Update =>
      val context = ProjectionContext(Some(state))
      for
        result <- applyDelta(state.root, update, None, context)
        // A sparse update changes only what its diff reaches, which the differ derives from the
        // validated tree it follows; the whole graph is validated again when it is resent.
        next <- finish(result.root, context, validate = result.structural)
        json <-
          if result.structural then fullPayload(next)
          else
            val fullDiffCids = result.changedComponents ++ context.allocatedCids
            val pendingDiffs = fullDiffCids.iterator.map(_ -> Option.empty[SparseChanges]).toMap ++
              context.componentDiffs
            val diffs = pendingDiffs.map { case (cid, changes) =>
              if state.pendingDeletionCids.contains(cid) then cid -> None else cid -> changes
            }
            sparsePayload(state, next, result.changes, diffs)
      yield clearPending(Some(state), next) -> json

  private[scalive] def html(state: PhoenixRenderedState): Either[PhoenixEncodingError, String] =
    renderHtml(state.root, Components(activeComponents(state)))

  private val emptyStreamOperations =
    ProjectedStreamOperations(Vector.empty, Vector.empty, reset = false)

  /** `state` with what its payload sent marked as sent. A node `previous` holds as it is was marked
    * when the payload that sent it was made, so only the nodes the update made are visited.
    */
  private def clearPending(
    previous: Option[PhoenixRenderedState],
    state: PhoenixRenderedState
  ): PhoenixRenderedState =
    val stateComponents = Components(state.components)
    // Records the stream statics the client now holds, so the next diff can tell if they changed.
    def clearNode(node: ProjectedNode, previous: Option[ProjectedNode]): ProjectedNode =
      if previous.exists(_ eq node) then node
      else
        val previousParts = previous.map(_.parts).filter(_.length == node.parts.length)
        node.copy(parts = node.parts.zipWithIndex.map { (part, position) =>
          val previousPart = previousParts.map(_(position))
          part match
            case ProjectedPart.Node(child) =>
              ProjectedPart.Node(
                clearNode(child, previousPart.collect { case ProjectedPart.Node(value) => value })
              )
            case ProjectedPart.Nested(child) =>
              ProjectedPart.Nested(
                clearNode(child, previousPart.collect { case ProjectedPart.Nested(value) => value })
              )
            case ProjectedPart.Keyed(keyed) =>
              val previousRows = previousPart.fold(Map.empty[RowId, ProjectedNode]) {
                case ProjectedPart.Keyed(value) =>
                  value.rows.iterator.map(row => row.id -> row.child).toMap
                case _ => Map.empty
              }
              ProjectedPart.Keyed(
                keyed.copy(
                  rows = keyed.rows.map(row =>
                    row.copy(child = clearNode(row.child, previousRows.get(row.id)))
                  ),
                  updates = Vector.empty,
                  includeStatics = false,
                  changed = false
                )
              )
            case ProjectedPart.Stream(stream) =>
              val previousRows = previousPart.fold(Map.empty[String, ProjectedNode]) {
                case ProjectedPart.Stream(value) =>
                  value.rows.iterator.map(row => row.domId -> row.child).toMap
                case _ => Map.empty
              }
              ProjectedPart.Stream(
                stream.copy(
                  rows = stream.rows.map(row =>
                    row.copy(child = clearNode(row.child, previousRows.get(row.domId)))
                  ),
                  operations = emptyStreamOperations,
                  sentStatics = streamStatics(stream, stateComponents).toOption
                )
              )
            case part => part
          end match
        })
    val root       = clearNode(state.root, previous.map(_.root))
    val components = state.components.map { (cid, component) =>
      val previousComponent = previous.flatMap(_.components.get(cid))
      if previousComponent.exists(_ eq component) then cid -> component
      else cid -> component.copy(root = clearNode(component.root, previousComponent.map(_.root)))
    }
    PhoenixRenderedState(
      root,
      components,
      state.activeCids,
      state.tokenCids,
      state.nextCid,
      state.streamRefs,
      state.nextStreamRef,
      state.csrfToken,
      state.pendingDeletionCids
    )
  end clearPending

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
      val csrfMarker = element.attributes.exists(attribute =>
        attribute.name == "data-scalive-csrf" &&
          attribute.value.contains(AttributeValue.Text("true"))
      )
      val visibleAttributes = element.attributes.filterNot(_.name == "data-scalive-csrf")
      val attributes        = traverse(visibleAttributes)(projectAttribute)
      attributes.flatMap { projectedAttributes =>
        opening ++= projectedAttributes
        componentRootCid.foreach(cid =>
          opening += ProjectedPart.Static(s" data-phx-component=\"$cid\"")
        )
        opening += ProjectedPart.Static(">")
        if csrfMarker then
          context.csrfToken.foreach(token =>
            opening += ProjectedPart.Static(
              s"<input type=\"hidden\" name=\"_csrf_token\" value=\"${Escaping.escape(token)}\">"
            )
          )
        traverse(element.children)(child => project(child, None, context)).map { children =>
          children.foreach(child => opening += ProjectedPart.Node(child))
          if !element.void then opening += ProjectedPart.Static(s"</${element.tag}>")
          ProjectedNode(element.id, opening.result())
        }
      }
    // What can take another shape has statics of its own, as a Phoenix conditional does.
    case flash: EvaluatedNode.Flash =>
      traverse(flash.child.toVector)(child => project(child, None, context)).map(children =>
        ProjectedNode(flash.id, children.map(ProjectedPart.Node(_)), ownStatics = true)
      )
    case choice: EvaluatedNode.Choice =>
      traverse(choice.children)(child => project(child, None, context)).map(children =>
        ProjectedNode(choice.id, children.map(ProjectedPart.Node(_)), ownStatics = true)
      )
    case keyed: EvaluatedNode.Keyed =>
      traverse(keyed.rows)(row =>
        project(row.child, None, context).map(ProjectedKeyedRow(row.id, _))
      ).map { rows =>
        val updates = rows.map(_ => Some(ProjectedKeyedRowUpdate(None, None, full = true)))
        ProjectedNode(
          keyed.id,
          Vector(
            ProjectedPart.Keyed(
              ProjectedKeyed(keyed.id, rows, updates, includeStatics = true, changed = true)
            )
          )
        )
      }
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
    case nested: EvaluatedNode.Nested =>
      nested.resolution match
        case None             => Left(PhoenixEncodingError.UnresolvedNested(nested.id))
        case Some(resolution) =>
          traverse(resolution.child)(tree => project(tree.root, None, context)).map { children =>
            val opening = StringBuilder()
            opening.append("<div id=\"")
            opening.append(Escaping.escape(resolution.applicationId))
            if !resolution.sticky then
              opening.append("\" data-phx-parent-id=\"")
              opening.append(Escaping.escape(resolution.parentDomId))
            opening.append("\" data-phx-session=\"")
            opening.append(Escaping.escape(resolution.joinCredential))
            opening.append("\" data-phx-static=\"")
            resolution.staticCredential.foreach { credential =>
              opening.append(Escaping.escape(credential))
            }
            opening.append('"')
            if resolution.sticky then opening.append(" data-phx-sticky")
            if resolution.loading then opening.append(" class=\"phx-loading\"")
            opening.append('>')

            ProjectedNode(
              nested.id,
              Vector(ProjectedPart.Static(opening.result())) ++
                children.map(ProjectedPart.Nested(_)) :+
                ProjectedPart.Static("</div>"),
              ownStatics = true
            )
          }
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
      sentStatics = None
    )

  private def projectStreamRow(
    row: EvaluatedNode.StreamRow,
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProjectedStreamRow] =
    val directNested = row.child match
      case element
          if element.tag == "div" && !element.void && element.attributes.size == 1 &&
            element.attributes.headOption.exists {
              case EvaluatedAttribute("id", Some(AttributeValue.Text(value)), _, _) =>
                value == row.domId
              case _ => false
            } =>
        element.children match
          case Vector(nested: EvaluatedNode.Nested) if nested.applicationId == row.domId =>
            Some(nested)
          case _ => None
      case _ => None
    val projected = directNested.fold(project(row.child, None, context))(project(_, None, context))
    projected.map { child =>
      val idAttribute = s" id=\"${Escaping.escape(row.domId)}\""
      val parts       = child.parts.flatMap {
        case ProjectedPart.Static(value) if value.contains(idAttribute) =>
          val index = value.indexOf(idAttribute)
          Vector(
            ProjectedPart.Static(value.substring(0, index) + " id=\""),
            ProjectedPart.StreamDomId(Escaping.escape(row.domId)),
            ProjectedPart.Static("\"" + value.substring(index + idAttribute.length))
          )
        case part => Vector(part)
      }
      ProjectedStreamRow(row.domId, child.copy(parts = parts))
    }
  end projectStreamRow

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
          val updated        = current.copy(
            root = replaceTarget(current.root, id, replacement),
            changedComponents = current.changedComponents ++ newlyProjected
          )
          resentTemplate(current.root, id, replacement) match
            case Some(template) => updated.copy(replaced = updated.replaced + template)
            case None           => updated.copy(structural = true)
        }
    case RenderChange.Keyed(id, rows) =>
      val targets = findKeyed(current.root, id)
      targets match
        case Vector()       => Left(PhoenixEncodingError.UnknownTarget(id))
        case Vector(target) =>
          applyKeyedChange(target, rows, context).map { case (keyed, update) =>
            current.copy(
              root = replaceKeyed(current.root, id, keyed),
              changedSlots = current.changedSlots ++ update.changedSlots,
              changedComponents = current.changedComponents ++ update.changedComponents,
              changedStreams = current.changedStreams ++ update.changedStreams,
              changedKeyedTargets = current.changedKeyedTargets ++ update.changedKeyedTargets + id,
              replaced = current.replaced ++ update.replaced
            )
          }
        case _ => Left(PhoenixEncodingError.DuplicateTarget(id))
    case RenderChange.Stream(id, identity, _, operations) =>
      val targets = findStreams(current.root, id)
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
      val owned = context
        .knownCid(token).filter(cid =>
          current.root.summary.cids.contains(cid) &&
            context.components.get(cid).exists(component => component.token eq token)
        )
      owned match
        case None      => Left(PhoenixEncodingError.UnknownComponentToken)
        case Some(cid) =>
          val component = context.components(cid)
          applyDelta(component.root, delta, Some(cid), context).map { update =>
            context.components.update(cid, component.copy(root = update.root))
            val ownChange =
              update.structural || update.changedSlots.nonEmpty || update.changedStreams.nonEmpty ||
                update.changedKeyedTargets.nonEmpty || update.replaced.nonEmpty
            if ownChange then
              context.componentDiffs.update(
                cid,
                if update.structural then None else Some(update.changes)
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

  /** The node to send again in full when the node at `id` becomes `replacement`, or None for the
    * whole program.
    *
    * The browser keeps every static outside that node. It is the replaced node itself when that has
    * statics of its own, or holds nothing but a component CID, and otherwise the nearest template
    * around it: a node with statics of its own or a comprehension row.
    */
  private def resentTemplate(
    root: ProjectedNode,
    id: TemplateId,
    replacement: ProjectedNode
  ): Option[TemplateId] =
    def holdsOnlyComponent(node: ProjectedNode): Boolean = node.parts match
      case Vector(ProjectedPart.Component(_)) => true
      case _                                  => false

    def search(
      node: ProjectedNode,
      enclosing: Option[TemplateId],
      template: Boolean
    ): Option[(ProjectedNode, Option[TemplateId])] =
      if node.id == id then Some(node -> enclosing)
      else if !node.mayHoldId(id) then None
      else
        val around = if template then Some(node.id) else enclosing
        node.parts.iterator
          .flatMap {
            case ProjectedPart.Node(child)  => search(child, around, child.ownStatics)
            case ProjectedPart.Keyed(keyed) =>
              keyed.rows.iterator.flatMap(row => search(row.child, around, template = true))
            case ProjectedPart.Stream(stream) =>
              stream.rows.iterator.flatMap(row => search(row.child, around, template = true))
            case _ => None
          }.nextOption()

    search(root, None, template = false).flatMap { case (target, enclosing) =>
      val keepsOwnStatics = target.ownStatics && replacement.ownStatics
      val swapsComponent  = holdsOnlyComponent(target) && holdsOnlyComponent(replacement)
      if keepsOwnStatics || swapsComponent then Some(replacement.id) else enclosing
    }
  end resentTemplate

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

  private def applyKeyedChange(
    keyed: ProjectedKeyed,
    changes: Vector[KeyedRowChange],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, (ProjectedKeyed, ProgramUpdate)] =
    val indexedOldRows = keyed.rows.zipWithIndex
    val oldRows        = indexedOldRows.map { case (row, index) => row.id -> (row, index) }.toMap
    validateKeyedChanges(indexedOldRows.map(_._1.id), changes).flatMap { _ =>
      changes
        .foldLeft[
          Either[
            PhoenixEncodingError,
            (Vector[ProjectedKeyedRow], Vector[Option[ProjectedKeyedRowUpdate]], ProgramUpdate)
          ]
        ](
          Right(
            (
              Vector.empty,
              Vector.empty,
              ProgramUpdate(ProjectedNode(keyed.id, Vector.empty), Set.empty, false, Set.empty)
            )
          )
        ) {
          case (result, KeyedRowChange.Insert(row)) =>
            result.flatMap { case (rows, updates, aggregate) =>
              val before = context.projectedCids.toSet
              project(row.child, None, context).map { child =>
                val added = context.projectedCids.toSet -- before
                (
                  rows :+ ProjectedKeyedRow(row.id, child),
                  updates :+ Some(ProjectedKeyedRowUpdate(None, None, full = true)),
                  aggregate.copy(changedComponents = aggregate.changedComponents ++ added)
                )
              }
            }
          case (result, KeyedRowChange.Retain(id, rowChanges)) =>
            result.flatMap { case (rows, updates, aggregate) =>
              oldRows.get(id) match
                case None                     => Left(PhoenixEncodingError.UnknownTarget(keyed.id))
                case Some((oldRow, oldIndex)) =>
                  applyChanges(oldRow.child, rowChanges, context).map { update =>
                    val newIndex = rows.length
                    val moved    = oldIndex != newIndex
                    val changed  = update.structural || update.changedSlots.nonEmpty ||
                      update.changedStreams.nonEmpty || update.changedKeyedTargets.nonEmpty ||
                      update.replaced.nonEmpty
                    val rowUpdate =
                      if !moved && !changed then None
                      else
                        Some(
                          ProjectedKeyedRowUpdate(
                            if moved then Some(oldIndex) else None,
                            if changed && !update.structural then Some(update.changes) else None,
                            full = update.structural
                          )
                        )
                    (
                      rows :+ oldRow.copy(child = update.root),
                      updates :+ rowUpdate,
                      aggregate.copy(
                        changedSlots = aggregate.changedSlots ++ update.changedSlots,
                        changedComponents = aggregate.changedComponents ++ update.changedComponents,
                        changedStreams = aggregate.changedStreams ++ update.changedStreams,
                        changedKeyedTargets = aggregate.changedKeyedTargets ++
                          update.changedKeyedTargets,
                        replaced = aggregate.replaced ++ update.replaced
                      )
                    )
                  }
            }
        }.map { case (rows, updates, aggregate) =>
          keyed.copy(
            rows = rows,
            updates = updates,
            includeStatics = false,
            changed = true
          ) -> aggregate
        }
    }
  end applyKeyedChange

  private def validateKeyedChanges(
    oldIds: Vector[RowId],
    changes: Vector[KeyedRowChange]
  ): Either[PhoenixEncodingError, Unit] =
    val oldCounts = oldIds.groupMapReduce(identity)(_ => 1)(_ + _)
    val outputIds = changes.map {
      case KeyedRowChange.Insert(row)   => row.id
      case KeyedRowChange.Retain(id, _) => id
    }
    val uniqueOutput = outputIds.distinct.size == outputIds.size
    val validSources = changes.forall {
      case KeyedRowChange.Retain(id, _) => oldCounts.get(id).contains(1)
      case KeyedRowChange.Insert(row)   => !oldCounts.contains(row.id)
    }
    if uniqueOutput && validSources then Right(())
    else Left(PhoenixEncodingError.InvalidKeyedRowChange)

  private def applyChanges(
    root: ProjectedNode,
    changes: Vector[RenderChange],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProgramUpdate] =
    detectDuplicateChanges(changes).flatMap { _ =>
      changes.foldLeft[Either[PhoenixEncodingError, ProgramUpdate]](
        Right(ProgramUpdate(root, Set.empty, structural = false, Set.empty))
      )((result, change) => result.flatMap(applyChange(_, change, None, context)))
    }

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
    stream.copy(rows = rows, operations = operations)

  private def updateSlot(
    root: ProjectedNode,
    slot: TemplateSlotId,
    expectedAttribute: Option[String],
    value: ProjectedValue
  ): Either[PhoenixEncodingError, ProjectedNode] =
    var occurrences                              = 0
    var mismatch                                 = false
    def loop(node: ProjectedNode): ProjectedNode =
      if !node.mayHoldSlot(slot) then node
      else
        val before = occurrences
        val parts  = node.parts.map {
          case ProjectedPart.Dynamic(`slot`, _, attributeName) =>
            occurrences += 1
            if attributeName != expectedAttribute then mismatch = true
            ProjectedPart.Dynamic(slot, value, attributeName)
          case part => part
        }
        val own = if occurrences == before then node else node.copy(parts = parts)
        mapChildren(own)(loop)
    val updated = loop(root)
    if occurrences == 0 then Left(PhoenixEncodingError.UnknownSlot(slot))
    else if occurrences > 1 then Left(PhoenixEncodingError.DuplicateSlot(slot))
    else if mismatch then Left(PhoenixEncodingError.SlotKindMismatch(slot))
    else Right(updated)

  /** `node` with `update` applied to each child subtree of its program, keeping every instance that
    * `update` keeps.
    */
  private def mapChildren(node: ProjectedNode)(update: ProjectedNode => ProjectedNode)
    : ProjectedNode =
    var changed = false
    def rows[Row](
      values: Vector[Row],
      child: Row => ProjectedNode
    )(
      withChild: (Row, ProjectedNode) => Row
    ): Option[Vector[Row]] =
      var rowsChanged = false
      val next        = values.map { row =>
        val current = child(row)
        val updated = update(current)
        if updated eq current then row
        else
          rowsChanged = true
          withChild(row, updated)
      }
      Option.when(rowsChanged)(next)
    val parts = node.parts.map {
      case part @ ProjectedPart.Node(value) =>
        val next = update(value)
        if next eq value then part
        else
          changed = true
          ProjectedPart.Node(next)
      case part @ ProjectedPart.Keyed(keyed) =>
        rows(keyed.rows, _.child)((row, child) => row.copy(child = child)) match
          case None       => part
          case Some(next) =>
            changed = true
            ProjectedPart.Keyed(keyed.copy(rows = next))
      case part @ ProjectedPart.Stream(stream) =>
        rows(stream.rows, _.child)((row, child) => row.copy(child = child)) match
          case None       => part
          case Some(next) =>
            changed = true
            ProjectedPart.Stream(stream.copy(rows = next))
      case part => part
    }
    if changed then node.copy(parts = parts) else node
  end mapChildren

  // The child subtrees of a node in its own program, nested LiveViews excluded.
  private def programChildren(node: ProjectedNode): Iterator[ProjectedNode] =
    node.parts.iterator.flatMap {
      case ProjectedPart.Node(child)    => Iterator(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.iterator.map(_.child)
      case ProjectedPart.Stream(stream) => stream.rows.iterator.map(_.child)
      case _                            => Iterator.empty
    }

  private def finish(
    root: ProjectedNode,
    context: ProjectionContext,
    validate: Boolean
  ): Either[PhoenixEncodingError, PhoenixRenderedState] =
    val retained = context.components.toMap
    for
      reachable <-
        if validate then validateGraph(root, retained) else reachableCids(root, retained)
      tokens =
        retained.iterator.map { case (cid, component) => IdentityKey(component.token) -> cid }.toMap
      streams    = root.summary.streams ++ retained.valuesIterator.flatMap(_.root.summary.streams)
      streamRefs = streams.iterator.map(stream => IdentityKey(stream.identity) -> stream.ref).toMap
    yield PhoenixRenderedState(
      root,
      retained,
      reachable,
      tokens,
      context.nextCid,
      streamRefs,
      context.nextStreamRef,
      context.csrfToken,
      context.pendingDeletionCids -- reachable
    )

  /** The CIDs reachable from `root`, read from the summaries of the programs that hold them. */
  private def reachableCids(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, Set[Int]] =
    val seen = mutable.HashSet.empty[Int]
    def loop(program: ProjectedNode): Either[PhoenixEncodingError, Unit] =
      program.summary.cids.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) { (result, cid) =>
        result.flatMap { _ =>
          components.get(cid) match
            case Some(component) if seen.add(cid) => loop(component.root)
            case _ => Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
        }
      }
    loop(root).map(_ => seen.toSet)

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
      val active        = seenCids.toSet
      val activeTargets = Components(components.filter((cid, _) => active.contains(cid)))
      targets
        .result().foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) { (result, target) =>
          result.flatMap { _ =>
            if activeTargets.targeted(target).nonEmpty then Right(())
            else Left(PhoenixEncodingError.UnknownComponentTarget)
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
          case (result, ProjectedPart.Node(child))   => result.flatMap(_ => loop(child))
          case (result, ProjectedPart.Nested(child)) => result.flatMap(_ => validateProgram(child))
          case (result, ProjectedPart.Keyed(keyed))  =>
            keyed.rows.foldLeft(result)((current, row) => current.flatMap(_ => loop(row.child)))
          case (result, ProjectedPart.Stream(stream)) =>
            stream.rows.foldLeft(result)((current, row) => current.flatMap(_ => loop(row.child)))
          case (result, _) => result
        }
    loop(root)

  private def activeComponents(
    state: PhoenixRenderedState
  ): Map[Int, ProjectedComponent] =
    state.components.view.filterKeys(state.activeCids.contains).toMap

  private[phoenix] def retainStreamRefs(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent],
    streamRefs: Map[IdentityKey, String]
  ): Map[IdentityKey, String] =
    val identities = (root.summary.streams.iterator ++
      components.valuesIterator.flatMap(_.root.summary.streams))
      .map(stream => IdentityKey(stream.identity)).toSet
    streamRefs.filter((identity, _) => identities.contains(identity))

  private def fullPayload(state: PhoenixRenderedState): Either[PhoenixEncodingError, Json.Obj] =
    val active = Components(activeComponents(state))
    rendered(state.root, active, None, None).flatMap { root =>
      if active.isEmpty then Right(root)
      else componentObject(active, state.activeCids).map(value => root.add("c", value))
    }

  private def sparsePayload(
    previous: PhoenixRenderedState,
    state: PhoenixRenderedState,
    changed: SparseChanges,
    changedComponents: Map[Int, Option[SparseChanges]]
  ): Either[PhoenixEncodingError, Json.Obj] =
    val active        = Components(activeComponents(state))
    val activeChanges = changedComponents.view.filterKeys(state.activeCids.contains).toMap
    rendered(state.root, active, Some(changed), None, Some(previous.root)).flatMap { root =>
      val touches =
        componentTouches(active.byCid, activeChanges.keySet, root != Json.Obj.empty)
      val componentChanges = activeChanges ++ touches.map { case (owner, cids) =>
        owner -> activeChanges
          .get(owner).fold(
            Some(SparseChanges(Set.empty, Set.empty).touching(cids))
          )(_.map(_.touching(cids)))
      }
      if componentChanges.isEmpty then Right(root)
      else componentDiffs(previous, active, componentChanges).map(value => root.add("c", value))
    }

  /** The CID slots each component sends again, so that the diff reaches every changed component.
    *
    * The browser skips a component that a diff does not reach and keeps what is in the page, which
    * would drop a change to a component inside it. It starts rendering at the root when the diff
    * changes the root, and otherwise at each outermost changed component, so every component from
    * there to a changed one has to be reached. Keyed by the CID of the component holding each slot.
    */
  private def componentTouches(
    components: Map[Int, ProjectedComponent],
    changed: Set[Int],
    rootRenders: Boolean
  ): Map[Int, Set[Int]] =
    val owners = components.flatMap { case (owner, component) =>
      component.root.summary.cids.map(_ -> owner)
    }
    // The components around a component, innermost first.
    def around(cid: Int): List[Int] = owners.get(cid).fold(Nil)(owner => owner :: around(owner))

    changed.toVector
      .flatMap { cid =>
        val enclosing = around(cid)
        val reached   =
          if rootRenders then enclosing.length else enclosing.lastIndexWhere(changed.contains) + 1
        // Each component holds the slot of the one just inside it.
        enclosing.zip(cid :: enclosing).take(reached)
      }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap

  extension (changes: SparseChanges)
    private def touching(cids: Set[Int]): SparseChanges =
      changes.copy(touchedComponents = changes.touchedComponents ++ cids)

  private def componentObject(
    components: Components,
    cids: Set[Int]
  ): Either[PhoenixEncodingError, Json.Obj] =
    traverse(cids.toVector.sorted)(cid =>
      rendered(components(cid).root, components, None, None).map(value =>
        cid.toString -> value.add("r", Json.Num(1))
      )
    ).map(fields => Json.Obj(fields*))

  private def componentDiffs(
    previous: PhoenixRenderedState,
    components: Components,
    cids: Map[Int, Option[SparseChanges]]
  ): Either[PhoenixEncodingError, Json.Obj] =
    traverse(cids.toVector.sortBy(_._1)) { case (cid, changes) =>
      val previousRoot = previous.components.get(cid).map(_.root)
      rendered(components(cid).root, components, changes, None, previousRoot).map { value =>
        cid.toString -> (if changes.isEmpty then value.add("r", Json.Num(1)) else value)
      }
    }.map(fields => Json.Obj(fields*))

  /** Encodes `root` as a rendered object: in full when `changes` is None, and otherwise only what
    * changed since `previous`, the projection the client holds. In full, its statics are a
    * reference into `templates` when there is one.
    */
  private def rendered(
    root: ProjectedNode,
    components: Components,
    changes: Option[SparseChanges],
    templates: Option[TemplateTable],
    previous: Option[ProjectedNode] = None
  ): Either[PhoenixEncodingError, Json.Obj] =
    changes match
      case Some(sparseChanges) =>
        changedDynamics(root, previous, components, sparseChanges, templates)
          .map(sent => Json.Obj(sent*))
      case None =>
        flatten(root, components, templates).map { case (statics, dynamics) =>
          val encodedStatics = templates match
            case Some(table) => Json.Num(table.reference(statics))
            case None        => Json.Arr(statics.map(Json.Str(_))*)
          val sent = dynamics.zipWithIndex.map((value, index) => index.toString -> value)
          Json.Obj((("s" -> encodedStatics) +: sent)*)
        }

  /** Splits `root` into its statics and dynamics. A node with statics of its own is one dynamic: a
    * nested rendered object.
    */
  private def flatten(
    root: ProjectedNode,
    components: Components,
    templates: Option[TemplateTable]
  ): Either[PhoenixEncodingError, (Vector[String], Vector[Json])] =
    val statics  = Vector.newBuilder[String]
    val dynamics = Vector.newBuilder[Json]
    val current  = StringBuilder()

    def dynamic(value: Json): Unit =
      statics += current.result()
      current.clear()
      dynamics += value

    def loop(node: ProjectedNode): Either[PhoenixEncodingError, Unit] =
      node.parts.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
        case (result, ProjectedPart.Static(value)) =>
          result.map(_ => current.append(value)).map(_ => ())
        case (result, ProjectedPart.Dynamic(_, ProjectedValue.Text(value), _)) =>
          result.map(_ => dynamic(Json.Str(value)))
        case (result, ProjectedPart.Dynamic(_, value: ProjectedValue.ComponentTarget, _)) =>
          result.flatMap(_ => renderTarget(value, components).map(text => dynamic(Json.Str(text))))
        case (result, ProjectedPart.Target(value)) =>
          result.flatMap(_ => renderTarget(value, components).map(current.append).map(_ => ()))
        case (result, ProjectedPart.Node(child)) =>
          if child.ownStatics then
            result.flatMap(_ => rendered(child, components, None, templates).map(dynamic))
          else result.flatMap(_ => loop(child))
        case (result, ProjectedPart.Nested(child)) => result.flatMap(_ => loop(child))
        case (result, ProjectedPart.Keyed(keyed))  =>
          result.flatMap(_ => keyedJson(keyed, None, components, None, templates).map(dynamic))
        case (result, ProjectedPart.Component(cid))     => result.map(_ => dynamic(Json.Num(cid)))
        case (result, ProjectedPart.StreamDomId(value)) =>
          result.map(_ => dynamic(Json.Str(value)))
        case (result, ProjectedPart.Stream(stream)) =>
          result.flatMap(_ => streamJson(stream, None, components, None, templates).map(dynamic))
      }

    loop(root).map { _ =>
      statics += current.result()
      statics.result() -> dynamics.result()
    }
  end flatten

  /** The dynamics of `root` a diff of `changes` sends, by index. Everything in a node of
    * `changes.replaced` is sent in full.
    *
    * A subtree that is the same instance in `previous`, the projection the client holds, did not
    * change, so it is skipped without being visited unless it holds a component the diff has to
    * reach.
    */
  private def changedDynamics(
    root: ProjectedNode,
    previous: Option[ProjectedNode],
    components: Components,
    changes: SparseChanges,
    templates: Option[TemplateTable]
  ): Either[PhoenixEncodingError, Vector[(String, Json)]] =
    val sent  = Vector.newBuilder[(String, Json)]
    var index = 0

    def next(value: Option[Json]): Unit =
      value.foreach(json => sent += index.toString -> json)
      index += 1

    def nested(value: Json.Obj): Option[Json] = Option.when(value != Json.Obj.empty)(value)

    def unchanged(node: ProjectedNode, previous: Option[ProjectedNode]): Boolean =
      previous.exists(_ eq node) &&
        !node.summary.cids.exists(changes.touchedComponents.contains)

    def loop(
      node: ProjectedNode,
      previous: Option[ProjectedNode]
    ): Either[PhoenixEncodingError, Unit] =
      if unchanged(node, previous) then
        index += node.summary.dynamics
        Right(())
      else
        val previousParts = previous.map(_.parts).filter(_.length == node.parts.length)
        node.parts.zipWithIndex.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
          case (result, (part, position)) =>
            result.flatMap { _ =>
              val previousPart = previousParts.map(_(position))
              part match
                case ProjectedPart.Static(_) | ProjectedPart.Target(_)          => Right(())
                case ProjectedPart.Dynamic(slot, ProjectedValue.Text(value), _) =>
                  next(Option.when(changes.slots.contains(slot))(Json.Str(value)))
                  Right(())
                case ProjectedPart.Dynamic(slot, value: ProjectedValue.ComponentTarget, _) =>
                  if changes.slots.contains(slot) then
                    renderTarget(value, components).map(text => next(Some(Json.Str(text))))
                  else Right(next(None))
                case ProjectedPart.Node(child) =>
                  val previousChild = previousPart.collect {
                    case ProjectedPart.Node(value) if value.id == child.id => value
                  }
                  if changes.replaced.contains(child.id) then
                    if child.ownStatics then
                      rendered(child, components, None, templates).map(value => next(Some(value)))
                    else
                      flatten(child, components, templates).map((_, dynamics) =>
                        dynamics.foreach(value => next(Some(value)))
                      )
                  else if child.ownStatics then
                    rendered(child, components, Some(changes), templates, previousChild)
                      .map(value => next(nested(value)))
                  else loop(child, previousChild)
                case ProjectedPart.Nested(child) =>
                  loop(child, previousPart.collect { case ProjectedPart.Nested(value) => value })
                case ProjectedPart.Keyed(keyed) =>
                  val previousKeyed = previousPart.collect {
                    case ProjectedPart.Keyed(value) if value.id == keyed.id => value
                  }
                  keyedJson(keyed, previousKeyed, components, Some(changes), templates)
                    .map(value => next(nested(value)))
                case ProjectedPart.Component(cid) =>
                  next(Option.when(changes.touchedComponents.contains(cid))(Json.Num(cid)))
                  Right(())
                case ProjectedPart.StreamDomId(_) => Right(next(None))
                case ProjectedPart.Stream(stream) =>
                  val previousStream = previousPart.collect {
                    case ProjectedPart.Stream(value) if value.id == stream.id => value
                  }
                  streamJson(stream, previousStream, components, Some(changes), templates)
                    .map(value => next(nested(value)))
              end match
            }
        }

    loop(root, previous).map(_ => sent.result())
  end changedDynamics

  /** Encodes a keyed comprehension. Its rows need not share statics, so each row is a nested
    * rendered object, and the outermost comprehension sends each distinct row template once in its
    * `p`. Comprehensions nested in the rows share that table, as the browser resolves them from it.
    */
  private def keyedJson(
    keyed: ProjectedKeyed,
    previous: Option[ProjectedKeyed],
    components: Components,
    sparseChanges: Option[SparseChanges],
    templates: Option[TemplateTable]
  ): Either[PhoenixEncodingError, Json.Obj] =
    lazy val previousRows =
      previous.fold(Map.empty[RowId, ProjectedNode])(
        _.rows.iterator.map(row => row.id -> row.child).toMap
      )
    val explicit =
      if sparseChanges.isEmpty then
        keyed.rows.map(_ => Some(ProjectedKeyedRowUpdate(None, None, full = true)))
      else keyed.updates.padTo(keyed.rows.length, None)
    val touched = sparseChanges.fold(Set.empty[Int])(_.touchedComponents)
    val updates = keyed.rows.zip(explicit).map { case (row, update) =>
      update match
        // A row's own diff still has to reach the components it holds.
        case Some(update) if !update.full && row.child.summary.cids.exists(touched.contains) =>
          val changes = update.changes.getOrElse(SparseChanges(Set.empty, Set.empty))
          Some(update.copy(changes = Some(changes.touching(touched))))
        case Some(update) => Some(update)
        case None         =>
          sparseChanges.collect {
            case changes if changes.replaced.contains(row.child.id) =>
              ProjectedKeyedRowUpdate(None, None, full = true)
            case changes
                if !keptRow(row.child, previousRows.get(row.id), changes) &&
                  rowChanged(row.child, changes) =>
              ProjectedKeyedRowUpdate(None, Some(changes), full = false)
          }
    }
    val shouldEmit = sparseChanges.isEmpty || keyed.changed ||
      sparseChanges.exists(_.keyedTargets.contains(keyed.id)) || updates.exists(_.nonEmpty)
    if !shouldEmit then Right(Json.Obj.empty)
    else
      val table = templates.getOrElse(TemplateTable())
      traverse(keyed.rows.zip(updates).zipWithIndex) { case ((row, update), index) =>
        update match
          case None         => Right(None)
          case Some(change) =>
            val child =
              if change.full then rendered(row.child, components, None, Some(table))
              else
                change.changes match
                  case Some(changes) =>
                    rendered(
                      row.child,
                      components,
                      Some(changes),
                      Some(table),
                      previousRows.get(row.id)
                    )
                  case None => Right(Json.Obj.empty)
            child.map { payload =>
              val boxed       = Json.Obj("0" -> payload)
              val value: Json = change.oldIndex match
                case None                                        => boxed
                case Some(oldIndex) if payload == Json.Obj.empty => Json.Num(oldIndex)
                case Some(oldIndex) => Json.Arr(Json.Num(oldIndex), boxed)
              Some(index.toString -> value)
            }
      }.map { entries =>
        val move        = updates.exists(_.flatMap(_.oldIndex).nonEmpty)
        val keyedFields = entries.flatten ++ Vector("kc" -> Json.Num(keyed.rows.length)) ++
          (if move then Vector("km" -> Json.Bool(true)) else Vector.empty)
        val fields = (if sparseChanges.isEmpty || keyed.includeStatics then
                        Vector("s" -> Json.Arr(Json.Str(""), Json.Str("")))
                      else Vector.empty) :+
          ("k" -> Json.Obj(keyedFields*))
        Json.Obj((fields ++ sharedTemplates(templates, table))*)
      }
    end if
  end keyedJson

  /** Encodes a stream as a comprehension whose rows share its statics. The browser keeps no stream
    * row once rendered, so every row it receives is whole.
    */
  private def streamJson(
    stream: ProjectedStream,
    previous: Option[ProjectedStream],
    components: Components,
    sparseChanges: Option[SparseChanges],
    templates: Option[TemplateTable]
  ): Either[PhoenixEncodingError, Json.Obj] =
    lazy val previousRows = previous.fold(Map.empty[String, ProjectedNode])(
      _.rows.iterator.map(row => row.domId -> row.child).toMap
    )
    def changed(row: ProjectedStreamRow, changes: SparseChanges): Boolean =
      !keptRow(row.child, previousRows.get(row.domId), changes) && rowChanged(row.child, changes)
    val selectedRows = sparseChanges match
      case None                                                 => stream.rows
      case Some(changes) if changes.streams.contains(stream.id) =>
        val inserted    = stream.operations.inserts.map(_.row)
        val insertedIds = inserted.map(_.domId).toSet
        inserted ++ stream.rows.filter(row =>
          !insertedIds.contains(row.domId) && changed(row, changes)
        )
      case Some(changes) => stream.rows.filter(row => changed(row, changes))

    val shouldEmit = sparseChanges.isEmpty || selectedRows.nonEmpty ||
      sparseChanges.exists(_.streams.contains(stream.id))
    if !shouldEmit then Right(Json.Obj.empty)
    else
      val table     = templates.getOrElse(TemplateTable())
      val flattened = for
        selected <- traverse(selectedRows)(row => flatten(row.child, components, Some(table)))
        // The other rows only shape the shared statics; what they reference is not sent.
        others <- traverse(streamTemplateRows(stream))(row =>
                    flatten(row.child, components, Some(TemplateTable()))
                  )
      yield selected ++ others
      flattened
        .flatMap(normalizeStreamRows).map { case (statics, rows) =>
          val selectedCount    = selectedRows.length
          val selectedPayloads = rows.take(selectedCount).map { dynamics =>
            Json.Obj(dynamics.zipWithIndex.map { case (value, index) =>
              index.toString -> value
            }*)
          }
          val keyedFields = selectedPayloads.zipWithIndex.map { case (value, index) =>
            index.toString -> value
          } :+ ("kc" -> Json.Num(selectedCount))
          val metadata = streamMetadata(stream)
          // A row whose static structure changed, such as a conditional that now renders,
          // needs new statics: the client renders every inserted row from the last ones sent.
          val includeStatics = sparseChanges.isEmpty || !stream.sentStatics.contains(statics)
          val fields         =
            (if includeStatics then Vector("s" -> Json.Arr(statics.map(Json.Str(_))*))
             else Vector.empty) ++
              Vector("k" -> Json.Obj(keyedFields*), "stream" -> metadata)
          Json.Obj((fields ++ sharedTemplates(templates, table))*)
        }
    end if
  end streamJson

  // Only the outermost comprehension sends the table: the browser ignores a nested one's.
  private def sharedTemplates(
    inherited: Option[TemplateTable],
    table: TemplateTable
  ): Vector[(String, Json)] =
    if inherited.nonEmpty then Vector.empty else table.toJson.map("p" -> _).toVector

  // A row the update kept as it was, holding no component the diff has to reach, did not change.
  private def keptRow(
    row: ProjectedNode,
    previous: Option[ProjectedNode],
    changes: SparseChanges
  ): Boolean =
    previous.exists(_ eq row) && !row.summary.cids.exists(changes.touchedComponents.contains)

  private def rowChanged(root: ProjectedNode, changes: SparseChanges): Boolean =
    rowContainsChangedSlot(root, changes.slots) ||
      rowContainsChangedStream(root, changes.streams) ||
      rowContainsChangedKeyed(root, changes.keyedTargets) ||
      collectIds(root).exists(changes.replaced.contains) ||
      root.summary.cids.exists(changes.touchedComponents.contains)

  // Rows whose statics the client's stream template must cover.
  private def streamTemplateRows(stream: ProjectedStream): Vector[ProjectedStreamRow] =
    stream.operations.inserts.map(_.row) ++ stream.rows

  private def streamStatics(
    stream: ProjectedStream,
    components: Components
  ): Either[PhoenixEncodingError, Vector[String]] =
    traverse(streamTemplateRows(stream))(row =>
      flatten(row.child, components, Some(TemplateTable()))
    ).flatMap(normalizeStreamRows).map(_._1)

  private def normalizeStreamRows(
    rows: Vector[(Vector[String], Vector[Json])]
  ): Either[PhoenixEncodingError, (Vector[String], Vector[Vector[Json]])] =
    rows.headOption match
      case None                                => Right(Vector("") -> Vector.empty)
      case Some((firstStatics, firstDynamics)) =>
        val compatible = rows.forall { case (statics, dynamics) =>
          statics.length == firstStatics.length && dynamics.length == firstDynamics.length
        }
        if !compatible then Left(PhoenixEncodingError.InvalidStreamRowTemplate)
        else
          val statics  = Vector.newBuilder[String]
          val dynamics = rows.map(_ => Vector.newBuilder[Json])
          val current  = StringBuilder()

          firstStatics.indices.foreach { index =>
            val values = rows.map(_._1(index))
            if values.forall(_ == values.head) then current.append(values.head)
            else
              statics += current.result()
              current.clear()
              values.zip(dynamics).foreach { case (value, row) => row += Json.Str(value) }

            if index < firstDynamics.length then
              statics += current.result()
              current.clear()
              rows.zip(dynamics).foreach { case ((_, values), row) => row += values(index) }
          }

          statics += current.result()
          Right(statics.result() -> dynamics.map(_.result()))
  end normalizeStreamRows

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
    components: Components
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
        case (result, ProjectedPart.Node(child))   => result.flatMap(_ => loop(child))
        case (result, ProjectedPart.Nested(child)) => result.flatMap(_ => loop(child))
        case (result, ProjectedPart.Keyed(keyed))  =>
          keyed.rows.foldLeft(result)((current, row) => current.flatMap(_ => loop(row.child)))
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
    components: Components
  ): Either[PhoenixEncodingError, String] =
    components.targeted(value) match
      case Some(cid) => Right(s" ${value.name}=\"$cid\"")
      case None      => Left(PhoenixEncodingError.UnknownComponentTarget)

  private def countTargets(node: ProjectedNode, id: TemplateId): Int =
    if !node.mayHoldId(id) then 0
    else (if node.id == id then 1 else 0) + programChildren(node).map(countTargets(_, id)).sum

  private def replaceTarget(node: ProjectedNode, id: TemplateId, replacement: ProjectedNode)
    : ProjectedNode =
    if node.id == id then replacement
    else if !node.mayHoldId(id) then node
    else mapChildren(node)(replaceTarget(_, id, replacement))

  private def replaceStream(
    node: ProjectedNode,
    id: TemplateId,
    identity: Object,
    replacement: ProjectedStream
  ): ProjectedNode =
    if !node.mayHoldId(id) then node
    else
      val own = node.parts.indexWhere {
        case ProjectedPart.Stream(stream) => stream.id == id && (stream.identity eq identity)
        case _                            => false
      }
      if own >= 0 then node.copy(parts = node.parts.updated(own, ProjectedPart.Stream(replacement)))
      else mapChildren(node)(replaceStream(_, id, identity, replacement))

  private def replaceKeyed(
    node: ProjectedNode,
    id: TemplateId,
    replacement: ProjectedKeyed
  ): ProjectedNode =
    if !node.mayHoldId(id) then node
    else
      val own = node.parts.indexWhere {
        case ProjectedPart.Keyed(keyed) => keyed.id == id
        case _                          => false
      }
      if own >= 0 then node.copy(parts = node.parts.updated(own, ProjectedPart.Keyed(replacement)))
      else mapChildren(node)(replaceKeyed(_, id, replacement))

  private def findKeyed(node: ProjectedNode, id: TemplateId): Vector[ProjectedKeyed] =
    if !node.mayHoldId(id) then Vector.empty
    else
      node.parts.collect { case ProjectedPart.Keyed(keyed) if keyed.id == id => keyed } ++
        programChildren(node).flatMap(findKeyed(_, id))

  private def findStreams(node: ProjectedNode, id: TemplateId): Vector[ProjectedStream] =
    if !node.mayHoldId(id) then Vector.empty
    else
      node.parts.collect { case ProjectedPart.Stream(stream) if stream.id == id => stream } ++
        programChildren(node).flatMap(findStreams(_, id))

  private def componentCids(root: ProjectedNode): Vector[Int] =
    root.parts.flatMap {
      case ProjectedPart.Component(cid) => Vector(cid)
      case ProjectedPart.Node(child)    => componentCids(child)
      case ProjectedPart.Nested(child)  => componentCids(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => componentCids(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => componentCids(row.child))
      case _                            => Vector.empty
    }

  private def collectDynamics(root: ProjectedNode): Vector[(TemplateSlotId, ProjectedValue)] =
    root.parts.flatMap {
      case ProjectedPart.Dynamic(slot, value, _) => Vector(slot -> value)
      case ProjectedPart.Node(child)             => collectDynamics(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => collectDynamics(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectDynamics(row.child))
      case _                            => Vector.empty
    }

  private def collectTargets(root: ProjectedNode): Vector[ProjectedValue.ComponentTarget] =
    root.parts.flatMap {
      case ProjectedPart.Target(value)                                        => Vector(value)
      case ProjectedPart.Dynamic(_, value: ProjectedValue.ComponentTarget, _) => Vector(value)
      case ProjectedPart.Node(child)    => collectTargets(child)
      case ProjectedPart.Nested(child)  => collectTargets(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => collectTargets(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectTargets(row.child))
      case _                            => Vector.empty
    }

  private def collectStreams(root: ProjectedNode): Vector[ProjectedStream] =
    root.parts.flatMap {
      case ProjectedPart.Stream(stream) =>
        stream +: stream.rows.flatMap(row => collectStreams(row.child))
      case ProjectedPart.Node(child)   => collectStreams(child)
      case ProjectedPart.Nested(child) => collectStreams(child)
      case ProjectedPart.Keyed(keyed)  => keyed.rows.flatMap(row => collectStreams(row.child))
      case _                           => Vector.empty
    }

  private def collectKeyed(root: ProjectedNode): Vector[ProjectedKeyed] =
    root.parts.flatMap {
      case ProjectedPart.Keyed(keyed) =>
        keyed +: keyed.rows.flatMap(row => collectKeyed(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectKeyed(row.child))
      case ProjectedPart.Node(child)    => collectKeyed(child)
      case _                            => Vector.empty
    }

  private def rowContainsChangedSlot(
    root: ProjectedNode,
    changed: Set[TemplateSlotId]
  ): Boolean = collectDynamics(root).exists((slot, _) => changed.contains(slot))

  private def rowContainsChangedStream(
    root: ProjectedNode,
    changed: Set[TemplateId]
  ): Boolean = collectStreams(root).exists(stream => changed.contains(stream.id))

  private def rowContainsChangedKeyed(
    root: ProjectedNode,
    changed: Set[TemplateId]
  ): Boolean = collectKeyed(root).exists(keyed => changed.contains(keyed.id))

  private def collectIds(root: ProjectedNode): Vector[TemplateId] =
    root.id +: root.parts.flatMap {
      case ProjectedPart.Node(child)    => collectIds(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => collectIds(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectIds(row.child))
      case _                            => Vector.empty
    }

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
      case (result, RenderChange.Keyed(id, _)) =>
        result.flatMap(_ =>
          if targets.add(id) then Right(()) else Left(PhoenixEncodingError.DuplicateTarget(id))
        )
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
    val results  = Vector.newBuilder[B]
    val iterator = values.iterator
    while iterator.hasNext do
      f(iterator.next()) match
        case Right(value) => results += value
        case Left(error)  => return Left(error)
    Right(results.result())
end PhoenixRenderedEncoder
