package scalive.protocol.phoenix

import scala.collection.mutable
import scala.util.boundary
import scala.util.boundary.break

import zio.Chunk
import zio.ChunkBuilder
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
  // The CID of the component holding each active component; the root holds those it lacks.
  private[phoenix] val owners: Map[Int, Int],
  private[phoenix] val tokenCids: Map[PhoenixRenderedEncoder.IdentityKey, Int],
  private[phoenix] val nextCid: Long,
  private[phoenix] val streamRefs: Map[PhoenixRenderedEncoder.IdentityKey, String],
  private[phoenix] val nextStreamRef: Long,
  private[phoenix] val csrfToken: Option[String],
  private[phoenix] val pendingDeletionCids: Set[Int],
  private[phoenix] val componentStatics: PhoenixRenderedEncoder.ComponentStatics):

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
      owners,
      tokenCids,
      nextCid,
      streamRefs,
      nextStreamRef,
      csrfToken,
      pendingDeletionCids ++ cids.filter(components.contains),
      componentStatics.removed(cids)
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
      owners,
      retainedTokenCids,
      nextCid,
      retainedStreamRefs,
      nextStreamRef,
      csrfToken,
      pendingDeletionCids -- acknowledged,
      componentStatics.removed(deleted)
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

  /** What a dynamic holds: its text, kept as it is, or the component a `phx-target` names. */
  private[phoenix] type ProjectedValue = String | ProjectedValue.ComponentTarget

  private[phoenix] object ProjectedValue:
    final case class ComponentTarget(ref: Object, identity: Object, name: String)

  /** A part of a projected node: a static, a dynamic, or what it holds, such as a child node. */
  sealed private[phoenix] trait ProjectedPart

  private[phoenix] object ProjectedPart:
    final case class Static(value: String) extends ProjectedPart
    final case class Dynamic(
      slot: TemplateSlotId,
      value: ProjectedValue,
      attributeName: Option[String])
        extends ProjectedPart
    final case class Target(value: ProjectedValue.ComponentTarget) extends ProjectedPart

    /** A finalized disconnected child program. Its template ids and slots have their own namespace.
      */
    final case class Nested(value: ProjectedNode)   extends ProjectedPart
    final case class Component(cid: Int)            extends ProjectedPart
    final case class Keyed(value: ProjectedKeyed)   extends ProjectedPart
    final case class Stream(value: ProjectedStream) extends ProjectedPart
    final case class StreamDomId(value: String)     extends ProjectedPart

    /** The CID on a component's root element. The browser adds it to the statics it renders, so
      * only the HTML the server renders holds it, and instances of a component share statics.
      */
    final case class ComponentRoot(cid: Int) extends ProjectedPart

  /** How a node's statics are sent. One with `own` statics is sent within its parent as a nested
    * rendered object, so replacing it resends that object and nothing around it. `lifted` holds the
    * positions of the statics the browser received among the dynamics of the node's rendered
    * object, when it shares its template with others that differ in them.
    *
    * Nodes share these instances, so that a node holds one field for both.
    */
  final private[phoenix] case class NodeStatics(own: Boolean, lifted: Vector[Int])

  private[phoenix] object NodeStatics:
    val Inline: NodeStatics = NodeStatics(own = false, Vector.empty)
    val Own: NodeStatics    = NodeStatics(own = true, Vector.empty)

  /** A projected node, which is itself a part of its parent in the same program. */
  final private[phoenix] case class ProjectedNode(
    id: TemplateId,
    parts: Vector[ProjectedPart],
    statics: NodeStatics = NodeStatics.Inline)
      extends ProjectedPart:
    private[phoenix] def ownStatics: Boolean = statics.own
    private[phoenix] def lifted: Vector[Int] = statics.lifted

    // Computed once per instance. An update copies only the nodes on the path to what it changes,
    // so the summaries of everything else carry over. A leaf, which holds nothing but statics and
    // dynamics, as a text does, shares one summary and reads what it holds from its few parts, so
    // it is read through the accessors below.
    private lazy val summary: Summary = Summary.of(this)

    private def isLeaf: Boolean = summary eq Summary.leaf

    /** The CIDs this subtree holds, nested LiveViews included but not the components' content. */
    private[phoenix] def cids: Set[Int] = summary.cids

    /** The streams this subtree holds, nested LiveViews and stream rows included. */
    private[phoenix] def streams: Vector[ProjectedStream] = summary.streams

    /** How many dynamics this subtree adds to the rendered object holding it. */
    private[phoenix] def dynamics: Int =
      if !isLeaf then summary.dynamics
      else
        var count = 0
        var index = 0
        while index < parts.length do
          parts(index) match
            case _: ProjectedPart.Dynamic | _: ProjectedPart.StreamDomId => count += 1
            case _                                                       => ()
          index += 1
        count

    // The bounds of the template ids and slots of this program subtree.
    private[phoenix] def idMin: Long   = if isLeaf then id.value else summary.idMin
    private[phoenix] def idMax: Long   = if isLeaf then id.value else summary.idMax
    private[phoenix] def slotMin: Long = if isLeaf then leafSlot(lowest = true) else summary.slotMin
    private[phoenix] def slotMax: Long =
      if isLeaf then leafSlot(lowest = false) else summary.slotMax

    private def leafSlot(lowest: Boolean): Long =
      var bound = if lowest then Long.MaxValue else Long.MinValue
      var index = 0
      while index < parts.length do
        parts(index) match
          case ProjectedPart.Dynamic(slot, _, _) =>
            bound = if lowest then math.min(bound, slot.value) else math.max(bound, slot.value)
          case _ => ()
        index += 1
      bound

    /** Whether the template id `target` may be in this program subtree. */
    private[phoenix] def mayHoldId(target: TemplateId): Boolean =
      idMin <= target.value && target.value <= idMax

    /** Whether the slot `target` may be in this program subtree. */
    private[phoenix] def mayHoldSlot(target: TemplateSlotId): Boolean =
      slotMin <= target.value && target.value <= slotMax
  end ProjectedNode

  /** What one projected subtree holds, for a node holding other nodes, components or rows.
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
    /** The summary every leaf shares. Only its CIDs and streams, none, are read. */
    val leaf: Summary = Summary(0L, 0L, Long.MaxValue, Long.MinValue, Set.empty, Vector.empty, 0)

    def of(node: ProjectedNode): Summary =
      if isLeaf(node.parts) then leaf
      else
        val builder = Builder(node.id.value)
        val parts   = node.parts.iterator
        while parts.hasNext do
          parts.next() match
            case ProjectedPart.Static(_) | ProjectedPart.Target(_) |
                ProjectedPart.ComponentRoot(_) =>
              ()
            case ProjectedPart.Dynamic(slot, _, _) =>
              builder.slotMin = math.min(builder.slotMin, slot.value)
              builder.slotMax = math.max(builder.slotMax, slot.value)
              builder.dynamics += 1
            case ProjectedPart.StreamDomId(_) => builder.dynamics += 1
            case child: ProjectedNode         =>
              builder.include(child, sameProgram = true)
              builder.dynamics += (if child.ownStatics then 1 else child.dynamics)
            case ProjectedPart.Nested(child) =>
              builder.include(child, sameProgram = false)
              builder.dynamics += child.dynamics
            case ProjectedPart.Component(cid) =>
              builder.cids = builder.cids + cid
              builder.dynamics += 1
            case ProjectedPart.Keyed(keyed) =>
              val rows = keyed.rows.iterator
              while rows.hasNext do builder.include(rows.next().child, sameProgram = true)
              builder.dynamics += 1
            case ProjectedPart.Stream(stream) =>
              builder.streams = builder.streams :+ stream
              val rows = stream.rows.iterator
              while rows.hasNext do builder.include(rows.next().child, sameProgram = true)
              builder.dynamics += 1
        builder.result()
    end of

    // A leaf holds nothing but statics and dynamics.
    private def isLeaf(parts: Vector[ProjectedPart]): Boolean =
      var index = 0
      var leaf  = true
      while leaf && index < parts.length do
        leaf = parts(index) match
          case _: ProjectedPart.Static | _: ProjectedPart.Dynamic | _: ProjectedPart.Target |
              _: ProjectedPart.StreamDomId | _: ProjectedPart.ComponentRoot =>
            true
          case _ => false
        index += 1
      leaf

    // Mutable fields rather than local variables, which closures would box one by one.
    final private class Builder(id: Long):
      var idMin    = id
      var idMax    = id
      var slotMin  = Long.MaxValue
      var slotMax  = Long.MinValue
      var cids     = Set.empty[Int]
      var streams  = Vector.empty[ProjectedStream]
      var dynamics = 0

      def include(child: ProjectedNode, sameProgram: Boolean): Unit =
        if sameProgram then
          idMin = math.min(idMin, child.idMin)
          idMax = math.max(idMax, child.idMax)
          slotMin = math.min(slotMin, child.slotMin)
          slotMax = math.max(slotMax, child.slotMax)
        if child.cids.nonEmpty then cids = cids ++ child.cids
        if child.streams.nonEmpty then streams = streams ++ child.streams

      def result(): Summary = Summary(idMin, idMax, slotMin, slotMax, cids, streams, dynamics)
  end Summary
  final private[phoenix] case class ProjectedComponent(
    token: Object,
    ref: Object,
    refIdentity: Object,
    root: ProjectedNode)
  final private[phoenix] case class ProjectedStreamRow(domId: String, child: ProjectedNode)
  final private[phoenix] case class ProjectedKeyedRow(id: RowId, child: ProjectedNode)
  // What the browser receives of the row at `index`, by the index the row had before when it moved.
  final private[phoenix] case class ProjectedKeyedRowUpdate(
    index: Int,
    oldIndex: Option[Int],
    changes: Option[SparseChanges],
    full: Boolean)
  private[phoenix] object ProjectedKeyedRowUpdate:
    def whole(index: Int): ProjectedKeyedRowUpdate =
      ProjectedKeyedRowUpdate(index, None, None, full = true)
  final private[phoenix] case class ProjectedKeyed(
    id: TemplateId,
    rows: Vector[ProjectedKeyedRow],
    // By ascending index, only for the rows the browser receives something of.
    updates: Vector[ProjectedKeyedRowUpdate],
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
    sentStatics: Option[StreamStatics])

  /** The statics the rows of a stream share, which shape the payload of each row.
    *
    * A static the rows differ in is None, and is sent among each row's dynamics. The browser
    * renders each row it receives from the last statics it was sent, so the statics only ever gain
    * differences: those it holds then cover every row it holds, and a payload shapes them from the
    * rows it sends alone.
    */
  private[phoenix] enum StreamStatics:
    case NoRows
    case Shared(statics: Vector[Option[String]], dynamics: Int)

    /** The statics the browser receives. */
    lazy val values: Vector[String] = this match
      case NoRows                    => Vector("")
      case Shared(statics, dynamics) =>
        val values  = Vector.newBuilder[String]
        val current = StringBuilder()
        statics.indices.foreach { index =>
          statics(index) match
            case Some(value) => current.append(value)
            case None        =>
              values += current.result()
              current.clear()
          if index < dynamics then
            values += current.result()
            current.clear()
        }
        values += current.result()
        values.result()

    /** These statics, also covering a row of `statics` and `dynamics`. */
    def including(
      statics: Vector[String],
      dynamics: Vector[Json]
    ): Either[PhoenixEncodingError, StreamStatics] = this match
      case NoRows => Right(Shared(statics.map(Some(_)), dynamics.length))
      case shared @ Shared(sharedStatics, sharedDynamics) =>
        if statics.length != sharedStatics.length || dynamics.length != sharedDynamics then
          Left(PhoenixEncodingError.InvalidStreamRowTemplate)
        else if sharedStatics.indices.forall(index =>
            sharedStatics(index).forall(_ == statics(index))
          )
        then Right(shared)
        else
          Right(
            Shared(
              sharedStatics.indices
                .map(index => sharedStatics(index).filter(_ == statics(index))).toVector,
              sharedDynamics
            )
          )

    /** The dynamics of a row of `statics` and `dynamics` these statics cover. */
    def shape(statics: Vector[String], dynamics: Vector[Json]): Vector[Json] = this match
      case NoRows                       => dynamics
      case Shared(sharedStatics, count) =>
        val shaped = Vector.newBuilder[Json]
        sharedStatics.indices.foreach { index =>
          if sharedStatics(index).isEmpty then shaped += Json.Str(statics(index))
          if index < count then shaped += dynamics(index)
        }
        shaped.result()
  end StreamStatics
  final private[phoenix] case class SparseChanges(
    slots: Set[TemplateSlotId],
    streams: Set[TemplateId],
    keyedTargets: Set[TemplateId] = Set.empty,
    // Nodes the browser has to receive again in full.
    replaced: Set[TemplateId] = Set.empty,
    // Components whose CID slot the diff sends again, only so that it reaches them.
    touchedComponents: Set[Int] = Set.empty)

  /** The statics the browser holds for a component: those of its rendered object, and of each
    * nested rendered object in it, by the key of the dynamic holding it. A comprehension is not
    * part of it, as it always sends its own statics.
    *
    * The browser builds a component whose `s` is another one's CID from the statics of that one,
    * and resolves each nested rendered object that has no `s` from the one at the same key. A
    * component may share statics with another of the same tree, whatever renders them.
    */
  final private[phoenix] case class StaticTree(
    statics: Json,
    nested: Vector[(String, StaticTree)]):
    // Computed once, as the index of the trees the browser holds hashes it at each lookup.
    override val hashCode: Int = (statics, nested).##

    /** This tree after the browser merges `diff`, a diff of a rendered object of this tree. */
    def patched(diff: Json.Obj): StaticTree =
      val next = nested.map { entry =>
        val (key, tree) = entry
        diff.get(key) match
          case Some(value: Json.Obj) =>
            val patched =
              if value.get("s").nonEmpty then StaticTree.of(value) else tree.patched(value)
            if patched eq tree then entry else key -> patched
          case _ => entry
      }
      if next.indices.forall(index => next(index) eq nested(index)) then this
      else StaticTree(statics, next)

    /** `rendered`, a rendered object of this tree sent in full, with `statics` as its `s` and no
      * `s` in the nested rendered objects the browser resolves from it.
      */
    def sharing(rendered: Json.Obj, statics: Option[Json]): Json.Obj =
      Json.Obj(rendered.fields.flatMap {
        case ("s", _)                       => statics.map("s" -> _)
        case field @ (key, value: Json.Obj) =>
          nested
            .collectFirst { case (`key`, tree) => key -> tree.sharing(value, None) }
            .orElse(Some(field))
        case field => Some(field)
      })
  end StaticTree

  private[phoenix] object StaticTree:
    /** The tree of `rendered`, a rendered object sent in full. */
    def of(rendered: Json.Obj): StaticTree =
      val nested        = Vector.newBuilder[(String, StaticTree)]
      var statics: Json = Json.Null
      rendered.fields.foreach {
        case ("s", value)                                     => statics = value
        case (key, value: Json.Obj) if value.get("k").isEmpty => nested += key -> of(value)
        case _                                                => ()
      }
      StaticTree(statics, nested.result())

  /** The static tree of each component the browser holds and may share, with the components holding
    * each tree. A component pending deletion is not in it, as the browser may drop it.
    *
    * The components of a tree share one instance of it, as many components hold the same one.
    */
  final private[phoenix] class ComponentStatics private (
    private val trees: Map[Int, StaticTree],
    private val holders: Map[StaticTree, Holders]):
    def tree(cid: Int): Option[StaticTree] = trees.get(cid)

    /** A component other than `cid` holding `tree`. */
    def holder(tree: StaticTree, cid: Int): Option[Int] =
      holders.get(tree).flatMap(_.cids.find(_ != cid))

    def updated(cid: Int, tree: StaticTree): ComponentStatics =
      if trees.get(cid).contains(tree) then this
      else
        val rest    = removed(Vector(cid))
        val current = rest.holders.getOrElse(tree, Holders(tree, Set.empty))
        ComponentStatics(
          rest.trees.updated(cid, current.tree),
          rest.holders.updated(current.tree, current.copy(cids = current.cids + cid))
        )

    def removed(cids: Iterable[Int]): ComponentStatics =
      cids.foldLeft(this) { (all, cid) =>
        all.trees.get(cid) match
          case None       => all
          case Some(tree) =>
            val current = all.holders(tree)
            val others  = current.cids - cid
            ComponentStatics(
              all.trees - cid,
              if others.isEmpty then all.holders - tree
              else all.holders.updated(tree, current.copy(cids = others))
            )
      }
  end ComponentStatics

  private[phoenix] object ComponentStatics:
    val empty: ComponentStatics = ComponentStatics(Map.empty, Map.empty)

  /** The components holding a tree, with the instance of the tree they share. */
  final private[phoenix] case class Holders(tree: StaticTree, cids: Set[Int])

  /** The components one render reaches, with the index its `phx-target` values resolve against. */
  final private class Components(
    all: Map[Int, ProjectedComponent],
    // The CIDs the render reaches. The other components wait for the browser to destroy them.
    active: Set[Int],
    // The statics the payload sent each stream, which the state then records as sent.
    val sentStatics: SentStatics = SentStatics(),
    // How the payload sent the statics of each rendered object it sent in full.
    val sentLifts: SentLifts = SentLifts(),
    // The static tree the browser holds for each component the payload sent, once it merges it.
    val sentTrees: mutable.Map[Int, StaticTree] = mutable.Map.empty):
    /** These components, recording nothing as sent, for rows flattened only to shape statics. */
    def unsent: Components = Components(all, active)

    def apply(cid: Int): ProjectedComponent       = get(cid).get
    def get(cid: Int): Option[ProjectedComponent] = all.get(cid).filter(_ => active.contains(cid))
    def isEmpty: Boolean                          = active.isEmpty

    // A target names its component by the component's ref, or by its token or ref identity.
    private lazy val byRef      = index(component => Vector(component.ref))
    private lazy val byIdentity = index(component => Vector(component.token, component.refIdentity))

    /** The CID of the one component `target` names, or None when it names none or several. */
    def targeted(target: ProjectedValue.ComponentTarget): Option[Int] =
      val cids = byRef.getOrElse(IdentityKey(target.ref), Set.empty) ++
        byIdentity.getOrElse(IdentityKey(target.identity), Set.empty)
      Option.when(cids.size == 1)(cids.head)

    private def index(keys: ProjectedComponent => Vector[Object]): Map[IdentityKey, Set[Int]] =
      active.toVector
        .flatMap(cid => keys(all(cid)).map(key => IdentityKey(key) -> cid))
        .groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
  end Components

  final private class SentStatics extends java.util.IdentityHashMap[ProjectedStream, StreamStatics]

  /** How a payload sent the statics of each rendered object it sent in full. */
  final private class SentLifts extends java.util.IdentityHashMap[ProjectedNode, NodeStatics]

  /** The statics of the nested templates in one comprehension, sent once as its `p`.
    *
    * Templates with as many statics that differ only in some of them, such as rows that put their
    * key in an attribute, are sent as one when that is shorter: each rendered object sends the text
    * its template differs in among its dynamics, as stream rows do, so the browser renders the same
    * HTML.
    */
  final private class TemplateTable:
    private val indexes = mutable.LinkedHashMap.empty[Vector[String], Int]
    // The rendered objects that refer to a template, each with the template it refers to.
    private val users = mutable.ArrayBuffer.empty[(ProjectedNode, Int)]

    def reference(node: ProjectedNode, statics: Vector[String]): Int =
      val index = indexes.getOrElseUpdate(statics, indexes.size)
      users += node -> index
      index

    /** `payload`, which refers to this table, with its `p`. Records in `lifts` how it sent the
      * statics of each rendered object it holds.
      */
    def share(payload: Json.Obj, lifts: SentLifts): Json.Obj =
      if indexes.isEmpty then payload
      else
        val templates = indexes.keys.toVector
        val useCounts = Array.ofDim[Int](templates.length)
        users.foreach((_, index) => useCounts(index) += 1)
        val merges = Array.fill(templates.length)(Option.empty[MergedTemplate])
        templates.indices.groupBy(templates(_).length).valuesIterator.foreach { group =>
          if group.length > 1 then
            MergedTemplate.of(group.toVector, templates, useCounts).foreach { merged =>
              group.foreach(index => merges(index) = Some(merged))
            }
        }
        users.foreach { (node, index) =>
          val statics = merges(index) match
            case Some(merged) => merged.nodeStatics(node.ownStatics)
            case None         => if node.ownStatics then NodeStatics.Own else NodeStatics.Inline
          lifts.put(node, statics): Unit
        }
        val reshaped =
          if merges.forall(_.isEmpty) then payload
          else reshape(payload, templates, merges).asInstanceOf[Json.Obj]
        val entries = templates.indices.flatMap { index =>
          merges(index) match
            case None                                  => Some(index -> templates(index))
            case Some(merged) if merged.index == index => Some(index -> merged.statics)
            case Some(_)                               => None
        }
        val table = Json.Obj(entries.map { (index, statics) =>
          index.toString -> Json.Arr(statics.map(Json.Str(_))*)
        }*)
        Json.Obj(reshaped.fields :+ ("p" -> table))
  end TemplateTable

  /** `json` with each rendered object whose template was merged referring to the merged one. */
  private def reshape(
    json: Json,
    templates: Vector[Vector[String]],
    merges: Array[Option[MergedTemplate]]
  ): Json = json match
    case obj: Json.Obj =>
      // Null until a value changes, so that what is kept is not copied.
      var changed: Array[(String, Json)] = null
      val fields                         = obj.fields
      var index                          = 0
      while index < fields.length do
        val (key, value) = fields(index)
        val next         = reshape(value, templates, merges)
        if !(next eq value) then
          if changed == null then changed = fields.toArray
          changed(index) = key -> next
        index += 1
      val current = if changed == null then fields else Chunk.fromArray(changed)
      current.headOption match
        // A rendered object sent in full: its statics, then its dynamics in order.
        case Some(("s", Json.Num(value))) if merges(value.intValue).nonEmpty =>
          val template = value.intValue
          Json.Obj(merges(template).get.shape(templates(template), current))
        case _ => if changed == null then obj else Json.Obj(current)
    case array: Json.Arr =>
      val elements = array.elements
      val mapped   = elements.map(reshape(_, templates, merges))
      if elements.indices.exists(index => !(mapped(index) eq elements(index))) then Json.Arr(mapped)
      else array
    case other => other

  /** A static that merged templates differ in, sharing `prefix` characters before the text they
    * differ in and `suffix` characters after it.
    */
  final private case class Hole(position: Int, prefix: Int, suffix: Int):
    def text(statics: Vector[String]): String =
      val value = statics(position)
      value.substring(prefix, value.length - suffix)

  /** Templates sent as the one at `index`, whose `statics` have a hole where they differ. Every
    * rendered object of these templates sends the text of each hole among its dynamics.
    */
  final private case class MergedTemplate(index: Int, statics: Vector[String], holes: Vector[Hole]):
    private val reference = "s" -> Json.Num(index)
    private val lifted    = holes.map(_.position)
    private val inline    = NodeStatics(own = false, lifted)
    private val own       = NodeStatics(own = true, lifted)

    /** How a node sends its statics with this template, shared by the nodes that do. */
    def nodeStatics(ownStatics: Boolean): NodeStatics = if ownStatics then own else inline

    /** The fields of a rendered object of `statics`, sent in full as `fields`, with this template.
      */
    def shape(statics: Vector[String], fields: Chunk[(String, Json)]): Chunk[(String, Json)] =
      val shaped = ChunkBuilder.make[(String, Json)](fields.length + holes.length)
      shaped += reference
      var hole = 0
      var key  = 0
      statics.indices.foreach { position =>
        if hole < holes.length && holes(hole).position == position then
          shaped += key.toString -> Json.Str(holes(hole).text(statics))
          hole += 1
          key += 1
        if position < statics.length - 1 then
          // The dynamics before the first hole keep their keys.
          val field = fields(position + 1)
          shaped += (if hole == 0 then field else key.toString -> field._2)
          key += 1
      }
      shaped.result()

  private object MergedTemplate:
    /** The templates at `group`, which have as many statics, merged into one, or None when sending
      * them apart is shorter.
      */
    def of(
      group: Vector[Int],
      templates: Vector[Vector[String]],
      useCounts: Array[Int]
    ): Option[MergedTemplate] =
      val first = templates(group.head)
      val holes = first.indices.flatMap { position =>
        val values = group.map(templates(_)(position))
        Option.when(values.exists(_ != values.head))(hole(position, values))
      }.toVector
      val statics = Vector.newBuilder[String]
      val current = StringBuilder()
      var next    = 0
      first.indices.foreach { position =>
        val value = first(position)
        if next < holes.length && holes(next).position == position then
          val hole = holes(next)
          current.append(value.substring(0, hole.prefix))
          statics += current.result()
          current.clear()
          current.append(value.substring(value.length - hole.suffix))
          next += 1
        else current.append(value)
        if position < first.length - 1 then
          statics += current.result()
          current.clear()
      }
      statics += current.result()
      val merged = MergedTemplate(group.head, statics.result(), holes)

      val apart = group.map(index => entryLength(index, templates(index))).sum
      // Each hole adds a dynamic, after those the rendered object already sends.
      val dynamics  = first.length - 1
      val addedKeys = (dynamics until dynamics + holes.length).map(key => digits(key) + 4).sum
      val lifted    = group.map { index =>
        val text = holes.map(hole => jsonLength(hole.text(templates(index)))).sum
        useCounts(index) * (text + addedKeys)
      }.sum
      Option.when(entryLength(merged.index, merged.statics) + lifted < apart)(merged)
    end of

    /** A hole at `position`, keeping in the template what all of `values` start and end with. */
    private def hole(position: Int, values: Vector[String]): Hole =
      val shortest = values.map(_.length).min
      var prefix   = 0
      while prefix < shortest && values.forall(_.charAt(prefix) == values.head.charAt(prefix)) do
        prefix += 1
      var suffix                       = 0
      def fromEnd(value: String): Char = value.charAt(value.length - 1 - suffix)
      while prefix + suffix < shortest && values.forall(fromEnd(_) == fromEnd(values.head)) do
        suffix += 1
      // A hole never splits a surrogate pair, which JSON could not encode on either side of it.
      if prefix > 0 && values.head.charAt(prefix - 1).isHighSurrogate then prefix -= 1
      if suffix > 0 && values.head.charAt(values.head.length - suffix).isLowSurrogate then
        suffix -= 1
      Hole(position, prefix, suffix)

    // The length of `"index":[statics],` in `p`.
    private def entryLength(index: Int, statics: Vector[String]): Int =
      digits(index) + 3 + 2 + statics.map(jsonLength).sum + statics.length - 1 + 1

    private def digits(value: Int): Int = value.toString.length

    // The length in UTF-8 bytes of `text` as a JSON string, quotes included.
    private def jsonLength(text: String): Int =
      var length = 2
      var index  = 0
      while index < text.length do
        val char = text.charAt(index)
        length +=
          (if char == '"' || char == '\\' || char == '\n' || char == '\r' || char == '\t' ||
             char == '\b' || char == '\f'
           then 2
           else if char < ' ' then 6
           else if char < 0x80 then 1
           else if char < 0x800 then 2
           // Each half of a surrogate pair, which takes 4 bytes.
           else if char.isSurrogate then 2
           else 3)
        index += 1
      length
  end MergedTemplate

  final private class ProjectionContext(
    state: Option[PhoenixRenderedState],
    initialCsrfToken: Option[String] = None):
    // The state's components with those this update projects or changes, which `changedCids` lists.
    private var currentComponents     = state.fold(Map.empty[Int, ProjectedComponent])(_.components)
    val changedCids: mutable.Set[Int] = mutable.Set.empty
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

    def components: Map[Int, ProjectedComponent] = currentComponents

    def updateComponent(cid: Int, component: ProjectedComponent): Unit =
      currentComponents = currentComponents.updated(cid, component)
      changedCids += cid

    def tokenCids: Map[IdentityKey, Int] =
      if newlyAllocated.isEmpty then existing else existing ++ newlyAllocated

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
      state <- finish(root, context, validate = true, None)
      sent = Components(state.components, state.activeCids)
      json <- fullPayload(state, sent)
    yield clearPending(None, state, context.changedCids, sent) -> json

  /** Projects a disconnected render with fresh connection-local CIDs and inlines components. */
  private[scalive] def fullHtml(
    tree: EvaluatedTree,
    csrfToken: Option[String] = None
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, String)] =
    val context = ProjectionContext(None, csrfToken)
    for
      root  <- project(tree.root, None, context)
      state <- finish(root, context, validate = true, None)
      components = Components(state.components, state.activeCids)
      html <- renderHtml(state.root, components)
    yield clearPending(None, state, context.changedCids, components) -> html

  def update(
    state: PhoenixRenderedState,
    delta: RenderDelta
  ): Either[PhoenixEncodingError, (PhoenixRenderedState, Json.Obj)] = delta match
    case RenderDelta.Empty         => Right(state -> Json.Obj.empty)
    case RenderDelta.Replace(tree) =>
      val context = ProjectionContext(Some(state))
      for
        root <- project(tree.root, None, context)
        next <- finish(root, context, validate = true, Some(state))
        sent = Components(next.components, next.activeCids)
        json <- fullPayload(next, sent)
      yield clearPending(None, next, next.activeCids, sent) -> json

    case update: RenderDelta.Update =>
      val context = ProjectionContext(Some(state))
      for
        result <- applyDelta(state.root, update, None, context)
        // A sparse update changes only what its diff reaches, which the differ derives from the
        // validated tree it follows; the whole graph is validated again when it is resent.
        next <- finish(result.root, context, validate = result.structural, Some(state))
        sent = Components(next.components, next.activeCids)
        json <-
          if result.structural then fullPayload(next, sent)
          else
            val fullDiffCids = result.changedComponents ++ context.allocatedCids
            val pendingDiffs = fullDiffCids.iterator.map(_ -> Option.empty[SparseChanges]).toMap ++
              context.componentDiffs
            val diffs = pendingDiffs.map { case (cid, changes) =>
              if state.pendingDeletionCids.contains(cid) then cid -> None else cid -> changes
            }
            sparsePayload(state, next, result.changes, diffs, sent)
      yield
        // A full payload sends every node again, so each is marked as sent anew.
        if result.structural then clearPending(None, next, next.activeCids, sent) -> json
        else clearPending(Some(state), next, context.changedCids, sent)           -> json

  private[scalive] def html(state: PhoenixRenderedState): Either[PhoenixEncodingError, String] =
    renderHtml(state.root, Components(state.components, state.activeCids))

  private val emptyStreamOperations =
    ProjectedStreamOperations(Vector.empty, Vector.empty, reset = false)

  /** `state` with what its payload sent marked as sent. A node `previous` holds as it is was marked
    * when the payload that sent it was made, so only the nodes the update made are visited.
    */
  private def clearPending(
    previous: Option[PhoenixRenderedState],
    state: PhoenixRenderedState,
    changedCids: Iterable[Int],
    sent: Components
  ): PhoenixRenderedState =
    val sentStatics = sent.sentStatics
    val sentLifts   = sent.sentLifts

    // Records the stream statics and lifted statics the client now holds, so the next diff can
    // tell if they changed and number its dynamics as the client does. What has nothing to clear
    // keeps its instance, and with it its summary.
    def clearNode(node: ProjectedNode, previous: Option[ProjectedNode]): ProjectedNode =
      val statics = sentLifts.get(node)
      if statics == null && previous.exists(_ eq node) then node
      else
        // Everything in a node sent in full was sent in full, kept instances included.
        val previousParts =
          if statics != null then None
          else previous.map(_.parts).filter(_.length == node.parts.length)
        val parts = mapKeepingInstances(node.parts) { (part, position) =>
          val previousPart = previousParts.map(_(position))
          part match
            case child: ProjectedNode =>
              val cleared =
                clearNode(child, previousPart.collect { case value: ProjectedNode => value })
              cleared
            case ProjectedPart.Nested(child) =>
              val cleared =
                clearNode(child, previousPart.collect { case ProjectedPart.Nested(value) => value })
              if cleared eq child then part else ProjectedPart.Nested(cleared)
            case ProjectedPart.Keyed(keyed) =>
              val previousRows = previousPart
                .collect { case ProjectedPart.Keyed(value) => value.rows }
                .getOrElse(Vector.empty)
              val rows = clearRows(keyed.rows, previousRows)
              if (rows eq keyed.rows) && keyed.updates.isEmpty && !keyed.includeStatics &&
                !keyed.changed
              then part
              else
                ProjectedPart.Keyed(
                  keyed.copy(
                    rows = rows,
                    updates = Vector.empty,
                    includeStatics = false,
                    changed = false
                  )
                )
            case ProjectedPart.Stream(stream) =>
              val previousRows = previousPart
                .collect { case ProjectedPart.Stream(value) => value.rows }
                .getOrElse(Vector.empty)
              val previousRow = previousStreamRows(stream, previousRows)
              val rows        = mapKeepingInstances(stream.rows) { (row, _) =>
                val index = previousRow.indexOf(row.domId)
                if index >= 0 && (previousRows(index).child eq row.child) then row
                else
                  val previousChild = Option.when(index >= 0)(previousRows(index).child)
                  val cleared       = clearNode(row.child, previousChild)
                  if cleared eq row.child then row else row.copy(child = cleared)
              }
              ProjectedPart.Stream(
                stream.copy(
                  rows = rows,
                  operations = emptyStreamOperations,
                  sentStatics = Option(sentStatics.get(stream)).orElse(stream.sentStatics)
                )
              )
            case part => part
          end match
        }
        val cleared = if parts eq node.parts then node else node.copy(parts = parts)
        if statics == null || (statics eq cleared.statics) then cleared
        else cleared.copy(statics = statics)
      end if
    end clearNode

    // Rows mostly keep their positions, so a row's previous instance is looked up by position
    // first, and by key only when the rows moved. A row kept as it was, with nothing to record, is
    // kept without being visited.
    def clearRows(
      rows: Vector[ProjectedKeyedRow],
      previous: Vector[ProjectedKeyedRow]
    ): Vector[ProjectedKeyedRow] =
      lazy val previousByKey = previous.iterator.map(row => row.id -> row.child).toMap
      mapKeepingInstances(rows) { (row, position) =>
        val inPlace = position < previous.length && previous(position).id == row.id
        if inPlace && (previous(position).child eq row.child) && sentLifts.get(row.child) == null
        then row
        else
          val previousChild =
            if inPlace then Some(previous(position).child) else previousByKey.get(row.id)
          val cleared = clearNode(row.child, previousChild)
          if cleared eq row.child then row else row.copy(child = cleared)
      }
    val root = clearNode(state.root, previous.map(_.root))
    // The other components are as they were when the payload that sent them was made.
    val components = changedCids.foldLeft(state.components) { (all, cid) =>
      val component         = all(cid)
      val previousComponent = previous.flatMap(_.components.get(cid))
      if previousComponent.exists(_ eq component) then all
      else
        all.updated(
          cid,
          component.copy(root = clearNode(component.root, previousComponent.map(_.root)))
        )
    }
    // A payload sending the root in full makes the browser drop the components it held.
    val heldStatics = if previous.isEmpty then ComponentStatics.empty else state.componentStatics
    val componentStatics = sent.sentTrees.foldLeft(heldStatics) { case (all, (cid, tree)) =>
      all.updated(cid, tree)
    }
    PhoenixRenderedState(
      root,
      components,
      state.activeCids,
      state.owners,
      state.tokenCids,
      state.nextCid,
      state.streamRefs,
      state.nextStreamRef,
      state.csrfToken,
      state.pendingDeletionCids,
      componentStatics
    )
  end clearPending

  /** `values` with `f` applied to each value and its position, or `values` itself when `f` returns
    * every value as it is.
    */
  private def mapKeepingInstances[A <: AnyRef](values: Vector[A])(f: (A, Int) => A): Vector[A] =
    // Null until a value changes, so that mapping what is kept allocates nothing.
    var mapped: mutable.Builder[A, Vector[A]] = null
    var position                              = 0
    while position < values.length do
      val value = values(position)
      val next  = f(value, position)
      if mapped == null && !(next eq value) then
        mapped = Vector.newBuilder[A]
        mapped ++= values.iterator.take(position)
      if mapped != null then mapped += next
      position += 1
    if mapped == null then values else mapped.result()

  final private case class TagParts(open: ProjectedPart, close: ProjectedPart)

  // Every element of a tag opens and closes the same way, so its elements share these parts
  // rather than each holding its own copy. An application names a bounded set of tags, but
  // `HtmlTag` takes any name, so the cache stops growing at a size no page should reach.
  private val tagPartsCache = java.util.concurrent.ConcurrentHashMap[String, TagParts]()
  private val maxCachedTags = 1024
  private val endOfOpeningTag: ProjectedPart = ProjectedPart.Static(">")

  private def tagParts(tag: String): TagParts =
    val cached = tagPartsCache.get(tag)
    if cached != null then cached
    else
      val parts = TagParts(ProjectedPart.Static(s"<$tag"), ProjectedPart.Static(s"</$tag>"))
      if tagPartsCache.size < maxCachedTags then tagPartsCache.putIfAbsent(tag, parts): Unit
      parts

  private def project(
    node: EvaluatedNode,
    componentRootCid: Option[Int],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, ProjectedNode] = node match
    case text: EvaluatedNode.Text =>
      val value = if text.raw then text.value else Escaping.escape(text.value)
      val part  =
        if text.slot.nonEmpty then ProjectedPart.Dynamic(text.slot.get, value, None)
        else ProjectedPart.Static(value)
      Right(ProjectedNode(text.id, Vector(part)))
    case element: EvaluatedNode.Element =>
      val opening = Vector.newBuilder[ProjectedPart]
      val tag     = tagParts(element.tag)
      opening += tag.open
      val csrfMarker = element.attributes.exists(attribute =>
        attribute.name == "data-scalive-csrf" &&
          attribute.value.contains(AttributeValue.Text("true"))
      )
      element.attributes.foreach(attribute =>
        if attribute.name != "data-scalive-csrf" then projectAttribute(attribute, opening)
      )
      componentRootCid.foreach(cid => opening += ProjectedPart.ComponentRoot(cid))
      opening += endOfOpeningTag
      if csrfMarker then
        context.csrfToken.foreach(token =>
          opening += ProjectedPart.Static(
            s"<input type=\"hidden\" name=\"_csrf_token\" value=\"${Escaping.escape(token)}\">"
          )
        )
      traverse(element.children)(child => project(child, None, context)).map { children =>
        children.foreach(child => opening += child)
        if !element.void then opening += tag.close
        ProjectedNode(element.id, opening.result())
      }
    // What can take another shape has statics of its own, as a Phoenix conditional does.
    case flash: EvaluatedNode.Flash =>
      traverse(flash.child.toVector)(child => project(child, None, context)).map(children =>
        ProjectedNode(flash.id, children, statics = NodeStatics.Own)
      )
    case choice: EvaluatedNode.Choice =>
      traverse(choice.children)(child => project(child, None, context)).map(children =>
        ProjectedNode(choice.id, children, statics = NodeStatics.Own)
      )
    case keyed: EvaluatedNode.Keyed =>
      traverse(keyed.rows)(row =>
        project(row.child, None, context).map(ProjectedKeyedRow(row.id, _))
      ).map { rows =>
        val updates = rows.indices.map(ProjectedKeyedRowUpdate.whole).toVector
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
            context.updateComponent(
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
              statics = NodeStatics.Own
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
    attribute: EvaluatedAttribute,
    parts: mutable.Builder[ProjectedPart, Vector[ProjectedPart]]
  ): Unit =
    val value = projectAttributeValue(attribute.name, attribute.value)
    attribute.value match
      case Some(AttributeValue.Composite(static, _)) if attribute.slot.nonEmpty =>
        parts += ProjectedPart.Static(s" ${attribute.name}=\"${Escaping.escape(static)}")
        parts += ProjectedPart.Dynamic(attribute.slot.get, value, Some(attribute.name))
        parts += closingQuote
      case Some(AttributeValue.Composite(static, dynamic)) =>
        val text = if dynamic.isEmpty then static else s"$static $dynamic"
        parts += ProjectedPart.Static(s" ${attribute.name}=\"${Escaping.escape(text)}\"")
      case _ if attribute.slot.nonEmpty =>
        parts += ProjectedPart.Dynamic(attribute.slot.get, value, Some(attribute.name))
      case _ =>
        value match
          case text: String                           => parts += ProjectedPart.Static(text)
          case target: ProjectedValue.ComponentTarget => parts += ProjectedPart.Target(target)

  private val closingQuote: ProjectedPart = ProjectedPart.Static("\"")

  // A composite value's static tokens are in the statics, so its slot holds the dynamic ones.
  private def projectAttributeValue(
    name: String,
    attributeValue: Option[AttributeValue]
  ): ProjectedValue =
    attributeValue match
      case None                                       => ""
      case Some(AttributeValue.Presence)              => s" $name"
      case Some(AttributeValue.Text(text))            => s" $name=\"${Escaping.escape(text)}\""
      case Some(AttributeValue.Composite(_, dynamic)) =>
        if dynamic.isEmpty then "" else s" ${Escaping.escape(dynamic)}"
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
        if raw then value else Escaping.escape(value)
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
    case RenderChange.Keyed(id, rowCount, rows) =>
      val targets = findKeyed(current.root, id)
      targets match
        case Vector()       => Left(PhoenixEncodingError.UnknownTarget(id))
        case Vector(target) =>
          applyKeyedChange(target, rowCount, rows, context).map { case (keyed, update) =>
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
          current.root.cids.contains(cid) &&
            context.components.get(cid).exists(component => component.token eq token)
        )
      owned match
        case None      => Left(PhoenixEncodingError.UnknownComponentToken)
        case Some(cid) =>
          val component = context.components(cid)
          applyDelta(component.root, delta, Some(cid), context).map { update =>
            context.updateComponent(cid, component.copy(root = update.root))
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
            case child: ProjectedNode       => search(child, around, child.ownStatics)
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
    rowCount: Int,
    changes: Vector[KeyedRowChange],
    context: ProjectionContext
  ): Either[PhoenixEncodingError, (ProjectedKeyed, ProgramUpdate)] =
    validateKeyedChanges(keyed.rows, rowCount, changes).flatMap { _ =>
      val changedRows = new Array[ProjectedKeyedRow](changes.length)
      val updates     = Vector.newBuilder[ProjectedKeyedRowUpdate]
      val slots       = Set.newBuilder[TemplateSlotId]
      val components  = Set.newBuilder[Int]
      val streams     = Set.newBuilder[TemplateId]
      val targets     = Set.newBuilder[TemplateId]
      val replaced    = Set.newBuilder[TemplateId]
      boundary:
        var position = 0
        while position < changes.length do
          changes(position) match
            case KeyedRowChange.Insert(index, row) =>
              val before = context.projectedCids.toSet
              project(row.child, None, context) match
                case Left(error)  => break(Left(error))
                case Right(child) =>
                  components ++= context.projectedCids.toSet -- before
                  changedRows(position) = ProjectedKeyedRow(row.id, child)
                  updates += ProjectedKeyedRowUpdate.whole(index)
            case KeyedRowChange.Retain(index, oldIndex, _, rowChanges) =>
              val oldRow = keyed.rows(oldIndex)
              val moved  = oldIndex != index
              if rowChanges.isEmpty then
                changedRows(position) = oldRow
                if moved then updates += ProjectedKeyedRowUpdate(index, Some(oldIndex), None, false)
              else
                applyChanges(oldRow.child, rowChanges, context) match
                  case Left(error)   => break(Left(error))
                  case Right(update) =>
                    val changed = update.structural || update.changedSlots.nonEmpty ||
                      update.changedStreams.nonEmpty || update.changedKeyedTargets.nonEmpty ||
                      update.replaced.nonEmpty
                    changedRows(position) = oldRow.copy(child = update.root)
                    if moved || changed then
                      updates += ProjectedKeyedRowUpdate(
                        index,
                        if moved then Some(oldIndex) else None,
                        if changed && !update.structural then Some(update.changes) else None,
                        full = update.structural
                      )
                    slots ++= update.changedSlots
                    components ++= update.changedComponents
                    streams ++= update.changedStreams
                    targets ++= update.changedKeyedTargets
                    replaced ++= update.replaced
          end match
          position += 1
        end while
        val aggregate = ProgramUpdate(
          ProjectedNode(keyed.id, Vector.empty),
          slots.result(),
          structural = false,
          components.result(),
          streams.result(),
          targets.result(),
          replaced.result()
        )
        Right(
          keyed.copy(
            rows = keyedRows(keyed.rows, rowCount, changes, changedRows),
            updates = updates.result(),
            includeStatics = false,
            changed = true
          ) -> aggregate
        )
    }
  end applyKeyedChange

  // Patching a vector copies a path of its tree, so beyond this many rows rebuilding it is cheaper.
  private val maxPatchedKeyedRows = 32

  /** The rows after a validated keyed change, in which `changedRows` are the rows of `changes`. Few
    * changes patch the old rows, and more rebuild them once.
    */
  private def keyedRows(
    oldRows: Vector[ProjectedKeyedRow],
    rowCount: Int,
    changes: Vector[KeyedRowChange],
    changedRows: Array[ProjectedKeyedRow]
  ): Vector[ProjectedKeyedRow] =
    if changes.length <= maxPatchedKeyedRows then
      var rows     = oldRows.take(rowCount)
      var position = 0
      while position < changes.length do
        val index = changes(position).index
        rows =
          if index < rows.length then rows.updated(index, changedRows(position))
          else rows :+ changedRows(position)
        position += 1
      rows
    else
      val rows     = Vector.newBuilder[ProjectedKeyedRow]
      var position = 0
      var index    = 0
      while index < rowCount do
        if position < changes.length && changes(position).index == index then
          rows += changedRows(position)
          position += 1
        else rows += oldRows(index)
        index += 1
      rows.result()

  /** Checks that a keyed change lists its rows by ascending index, that each index it does not list
    * keeps an old row, and that the new rows hold each row once: a retained row is the old row at
    * its old index, which no other row keeps, and an inserted row is not an old row.
    */
  private def validateKeyedChanges(
    oldRows: Vector[ProjectedKeyedRow],
    rowCount: Int,
    changes: Vector[KeyedRowChange]
  ): Either[PhoenixEncodingError, Unit] =
    val listed   = java.util.BitSet()
    val retained = java.util.BitSet()
    val inserted = mutable.LongMap.empty[Unit]
    var previous = -1
    var appended = 0
    var valid    = rowCount >= 0
    var position = 0
    while valid && position < changes.length do
      val change = changes(position)
      val index  = change.index
      valid = previous < index && index < rowCount
      if valid then
        listed.set(index)
        if index >= oldRows.length then appended += 1
        previous = index
        valid = change match
          case KeyedRowChange.Retain(_, oldIndex, id, _) =>
            val known = 0 <= oldIndex && oldIndex < oldRows.length &&
              oldRows(oldIndex).id == id && !retained.get(oldIndex)
            if known then retained.set(oldIndex)
            known
          case KeyedRowChange.Insert(_, row) =>
            val unique = !inserted.contains(row.id.value)
            if unique then inserted.update(row.id.value, ())
            unique
      position += 1
    // Each index past the old rows holds a listed row.
    valid &&= appended == math.max(rowCount - oldRows.length, 0)
    // A retained row does not stay at its old index too.
    var oldIndex = retained.nextSetBit(0)
    while valid && oldIndex >= 0 do
      valid = oldIndex >= rowCount || listed.get(oldIndex)
      oldIndex = retained.nextSetBit(oldIndex + 1)
    valid &&= inserted.isEmpty || !oldRows.exists(row => inserted.contains(row.id.value))
    if valid then Right(()) else Left(PhoenixEncodingError.InvalidKeyedRowChange)
  end validateKeyedChanges

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
        rows = insert.at match
          case StreamAt.First                               => insert.row +: rows
          case StreamAt.Index(value) if value < rows.length =>
            rows.patch(value, Vector(insert.row), 0)
          case _ => rows :+ insert.row
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
      case child: ProjectedNode =>
        val next = update(child)
        if !(next eq child) then changed = true
        next
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
      case child: ProjectedNode         => Iterator(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.iterator.map(_.child)
      case ProjectedPart.Stream(stream) => stream.rows.iterator.map(_.child)
      case _                            => Iterator.empty
    }

  /** The state after an update of `previous` that ends at `root`. The components the root and the
    * changed components reach are read again only when one of those programs holds other CIDs.
    */
  private def finish(
    root: ProjectedNode,
    context: ProjectionContext,
    validate: Boolean,
    previous: Option[PhoenixRenderedState]
  ): Either[PhoenixEncodingError, PhoenixRenderedState] =
    val components = context.components

    def sameCids(before: ProjectedNode, after: ProjectedNode): Boolean =
      (before eq after) || before.cids == after.cids
    def sameGraph(state: PhoenixRenderedState): Boolean =
      sameCids(state.root, root) && context.changedCids.forall(cid =>
        state.components
          .get(cid).exists(component => sameCids(component.root, components(cid).root))
      )
    val graph = previous match
      case _ if validate                   => validateGraph(root, components)
      case Some(state) if sameGraph(state) => Right(ComponentGraph(state.activeCids, state.owners))
      case _                               => reachableCids(root, components)
    for
      reachable <- graph
      streams    = root.streams ++ components.valuesIterator.flatMap(_.root.streams)
      streamRefs = streams.iterator.map(stream => IdentityKey(stream.identity) -> stream.ref).toMap
    yield PhoenixRenderedState(
      root,
      components,
      reachable.active,
      reachable.owners,
      context.tokenCids,
      context.nextCid,
      streamRefs,
      context.nextStreamRef,
      context.csrfToken,
      context.pendingDeletionCids -- reachable.active,
      previous.fold(ComponentStatics.empty)(_.componentStatics)
    )
  end finish

  /** The components a render reaches, and the CID of the component holding each of them. */
  final private case class ComponentGraph(active: Set[Int], owners: Map[Int, Int])

  /** The CIDs reachable from `root`, read from the summaries of the programs that hold them. */
  private def reachableCids(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, ComponentGraph] =
    val seen   = mutable.HashSet.empty[Int]
    val owners = Map.newBuilder[Int, Int]
    def loop(program: ProjectedNode, owner: Option[Int]): Either[PhoenixEncodingError, Unit] =
      program.cids.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) { (result, cid) =>
        result.flatMap { _ =>
          components.get(cid) match
            case Some(component) if seen.add(cid) =>
              owner.foreach(value => owners += cid -> value)
              loop(component.root, Some(cid))
            case _ => Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
        }
      }
    loop(root, None).map(_ => ComponentGraph(seen.toSet, owners.result()))

  private def validateGraph(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent]
  ): Either[PhoenixEncodingError, ComponentGraph] =
    val seenCids    = mutable.HashSet.empty[Int]
    val owners      = Map.newBuilder[Int, Int]
    val seenTokens  = mutable.HashSet.empty[IdentityKey]
    val seenStreams = mutable.HashSet.empty[IdentityKey]
    val targets     = Vector.newBuilder[ProjectedValue.ComponentTarget]

    def loopProgram(
      program: ProjectedNode,
      owner: Option[Int]
    ): Either[PhoenixEncodingError, Unit] =
      val streams = Vector.newBuilder[ProjectedStream]
      val cids    = Vector.newBuilder[Int]
      validateProgram(program, ProgramContents(streams, targets, cids)).flatMap { _ =>
        val duplicateStream =
          streams.result().find(stream => !seenStreams.add(IdentityKey(stream.identity)))
        if duplicateStream.nonEmpty then Left(PhoenixEncodingError.DuplicateStreamIdentity)
        else
          cids.result().foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) { (result, cid) =>
            result.flatMap { _ =>
              components.get(cid) match
                case None            => Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
                case Some(component) =>
                  if !seenCids.add(cid) then Left(PhoenixEncodingError.DuplicateComponentRoot(cid))
                  else if !seenTokens.add(IdentityKey(component.token)) then
                    Left(PhoenixEncodingError.DuplicateComponentToken)
                  else
                    owner.foreach(value => owners += cid -> value)
                    loopProgram(component.root, Some(cid))
            }
          }
      }

    loopProgram(root, None).flatMap { _ =>
      val active        = seenCids.toSet
      val activeTargets = Components(components, active)
      targets
        .result().foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) { (result, target) =>
          result.flatMap { _ =>
            if activeTargets.targeted(target).nonEmpty then Right(())
            else Left(PhoenixEncodingError.UnknownComponentTarget)
          }
        }.map(_ => ComponentGraph(active, owners.result()))
    }
  end validateGraph

  /** What a program and the programs nested in it hold that its graph is validated against. */
  final private class ProgramContents(
    val streams: mutable.Growable[ProjectedStream],
    val targets: mutable.Growable[ProjectedValue.ComponentTarget],
    val cids: mutable.Growable[Int])

  /** Checks that the template ids and slots of `root` are unique, and adds what it holds to
    * `contents`, in one walk.
    */
  private def validateProgram(
    root: ProjectedNode,
    contents: ProgramContents
  ): Either[PhoenixEncodingError, Unit] =
    val ids   = mutable.HashSet.empty[TemplateId]
    val slots = mutable.HashSet.empty[TemplateSlotId]

    // The first error the walk finds, which ends it.
    def loop(node: ProjectedNode): Option[PhoenixEncodingError] =
      if !ids.add(node.id) then Some(PhoenixEncodingError.DuplicateTarget(node.id))
      else
        var error = Option.empty[PhoenixEncodingError]
        val parts = node.parts.iterator
        while error.isEmpty && parts.hasNext do
          error = parts.next() match
            case ProjectedPart.Dynamic(slot, value, _) =>
              value match
                case target: ProjectedValue.ComponentTarget => contents.targets += target
                case _                                      => ()
              Option.when(!slots.add(slot))(PhoenixEncodingError.DuplicateSlot(slot))
            case ProjectedPart.Target(target) =>
              contents.targets += target
              None
            case ProjectedPart.Component(cid) =>
              contents.cids += cid
              None
            case child: ProjectedNode         => loop(child)
            case ProjectedPart.Nested(child)  => validateProgram(child, contents).left.toOption
            case ProjectedPart.Keyed(keyed)   => loopRows(keyed.rows.iterator.map(_.child))
            case ProjectedPart.Stream(stream) =>
              contents.streams += stream
              loopRows(stream.rows.iterator.map(_.child))
            case _ => None
        error

    def loopRows(rows: Iterator[ProjectedNode]): Option[PhoenixEncodingError] =
      var error = Option.empty[PhoenixEncodingError]
      while error.isEmpty && rows.hasNext do error = loop(rows.next())
      error

    loop(root).toLeft(())
  end validateProgram

  private[phoenix] def retainStreamRefs(
    root: ProjectedNode,
    components: Map[Int, ProjectedComponent],
    streamRefs: Map[IdentityKey, String]
  ): Map[IdentityKey, String] =
    val identities = (root.streams.iterator ++
      components.valuesIterator.flatMap(_.root.streams))
      .map(stream => IdentityKey(stream.identity)).toSet
    streamRefs.filter((identity, _) => identities.contains(identity))

  private def fullPayload(
    state: PhoenixRenderedState,
    active: Components
  ): Either[PhoenixEncodingError, Json.Obj] =
    rendered(state.root, active, None, None).flatMap { root =>
      if active.isEmpty then Right(root)
      else componentObject(active, state.activeCids).map(value => root.add("c", value))
    }

  private def sparsePayload(
    previous: PhoenixRenderedState,
    state: PhoenixRenderedState,
    changed: SparseChanges,
    changedComponents: Map[Int, Option[SparseChanges]],
    active: Components
  ): Either[PhoenixEncodingError, Json.Obj] =
    val activeChanges = changedComponents.view.filterKeys(state.activeCids.contains).toMap
    rendered(state.root, active, Some(changed), None, Some(previous.root)).flatMap { root =>
      val touches =
        componentTouches(state.owners, activeChanges.keySet, root != Json.Obj.empty)
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
    owners: Map[Int, Int],
    changed: Set[Int],
    rootRenders: Boolean
  ): Map[Int, Set[Int]] =
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

  // The browser drops the components it holds when it receives the root's statics.
  private def componentObject(
    components: Components,
    cids: Set[Int]
  ): Either[PhoenixEncodingError, Json.Obj] =
    val sharing = SharedStatics(ComponentStatics.empty, components)
    traverse(cids.toVector.sorted)(cid =>
      rendered(components(cid).root, components, None, None).map(value =>
        cid.toString -> sharing.share(cid, value).add("r", Json.Num(1))
      )
    ).map(fields => Json.Obj(fields*))

  private def componentDiffs(
    previous: PhoenixRenderedState,
    components: Components,
    cids: Map[Int, Option[SparseChanges]]
  ): Either[PhoenixEncodingError, Json.Obj] =
    val sharing = SharedStatics(previous.componentStatics, components)
    traverse(cids.toVector.sortBy(_._1)) { case (cid, changes) =>
      val previousRoot = previous.components.get(cid).map(_.root)
      rendered(components(cid).root, components, changes, None, previousRoot).map { value =>
        cid.toString ->
          (if changes.isEmpty then sharing.share(cid, value).add("r", Json.Num(1))
           else sharing.merged(cid, value))
      }
    }.map(fields => Json.Obj(fields*))

  /** Shares the statics of the components one payload sends in full with a component the browser
    * `held` before it, or one sent earlier in the payload, and records in `components` the static
    * tree the browser then holds for each component the payload sends.
    */
  final private class SharedStatics(held: ComponentStatics, components: Components):
    // The first component of each tree the payload sends with its own statics.
    private val sent = mutable.HashMap.empty[StaticTree, Int]

    /** `rendered`, the component `cid` sent in full, referring to the statics of another component
      * of the same tree when there is one.
      */
    def share(cid: Int, rendered: Json.Obj): Json.Obj =
      val tree = StaticTree.of(rendered)
      components.sentTrees.update(cid, tree)
      held.holder(tree, cid).map(-_).orElse(sent.get(tree)) match
        case Some(reference) => tree.sharing(rendered, Some(Json.Num(reference)))
        case None            =>
          sent.update(tree, cid)
          rendered

    /** Records the tree the browser holds once it merges `diff`, a diff of the component `cid`. */
    def merged(cid: Int, diff: Json.Obj): Json.Obj =
      held.tree(cid).foreach { tree =>
        val next = tree.patched(diff)
        if !(next eq tree) then components.sentTrees.update(cid, next)
      }
      diff

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
          .map(sent => Json.Obj(liftedKeys(root.lifted, sent)*))
      case None =>
        flatten(root, components, templates).map { case (statics, dynamics) =>
          val encodedStatics = templates match
            case Some(table) => Json.Num(table.reference(root, statics))
            case None        => Json.Arr(statics.map(Json.Str(_))*)
          val sent = dynamics.zipWithIndex.map((value, index) => index.toString -> value)
          Json.Obj((("s" -> encodedStatics) +: sent)*)
        }

  /** The dynamics of `sent`, by index, numbered as the browser holds them: after each of the
    * `lifted` statics it received among them.
    */
  private def liftedKeys(lifted: Vector[Int], sent: Vector[(String, Json)])
    : Vector[(String, Json)] =
    if lifted.isEmpty then sent
    else
      sent.map { (key, value) =>
        val index = key.toInt
        (index + lifted.count(_ <= index)).toString -> value
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

    // The first error the walk finds, which ends it.
    def loop(node: ProjectedNode): Option[PhoenixEncodingError] =
      var error = Option.empty[PhoenixEncodingError]
      val parts = node.parts.iterator
      while error.isEmpty && parts.hasNext do
        error = parts.next() match
          case ProjectedPart.Static(value) =>
            current.append(value)
            None
          case ProjectedPart.Dynamic(_, value: String, _) =>
            dynamic(Json.Str(value))
            None
          case ProjectedPart.Dynamic(_, value: ProjectedValue.ComponentTarget, _) =>
            renderTarget(value, components).map(text => dynamic(Json.Str(text))).left.toOption
          case ProjectedPart.Target(value) =>
            renderTarget(value, components).map(text => current.append(text)).left.toOption
          case child: ProjectedNode =>
            if child.ownStatics then
              rendered(child, components, None, templates).map(dynamic).left.toOption
            else loop(child)
          case ProjectedPart.Nested(child) => loop(child)
          case ProjectedPart.Keyed(keyed)  =>
            keyedJson(keyed, None, components, None, templates).map(dynamic).left.toOption
          case ProjectedPart.Component(cid) =>
            dynamic(Json.Num(cid))
            None
          case ProjectedPart.StreamDomId(value) =>
            dynamic(Json.Str(value))
            None
          case ProjectedPart.Stream(stream) =>
            streamJson(stream, None, components, None, templates).map(dynamic).left.toOption
          case ProjectedPart.ComponentRoot(_) => None
      error
    end loop

    loop(root).toLeft {
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
        !node.cids.exists(changes.touchedComponents.contains)

    def loop(
      node: ProjectedNode,
      previous: Option[ProjectedNode]
    ): Either[PhoenixEncodingError, Unit] =
      if unchanged(node, previous) then
        index += node.dynamics
        Right(())
      else
        val previousParts = previous.map(_.parts).filter(_.length == node.parts.length)
        node.parts.zipWithIndex.foldLeft[Either[PhoenixEncodingError, Unit]](Right(())) {
          case (result, (part, position)) =>
            result.flatMap { _ =>
              val previousPart = previousParts.map(_(position))
              part match
                case ProjectedPart.Static(_) | ProjectedPart.Target(_) |
                    ProjectedPart.ComponentRoot(_) =>
                  Right(())
                case ProjectedPart.Dynamic(slot, value: String, _) =>
                  next(Option.when(changes.slots.contains(slot))(Json.Str(value)))
                  Right(())
                case ProjectedPart.Dynamic(slot, value: ProjectedValue.ComponentTarget, _) =>
                  if changes.slots.contains(slot) then
                    renderTarget(value, components).map(text => next(Some(Json.Str(text))))
                  else Right(next(None))
                case child: ProjectedNode =>
                  val previousChild = previousPart.collect {
                    case value: ProjectedNode if value.id == child.id => value
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
    val previousRows           = previous.fold(Vector.empty[ProjectedKeyedRow])(_.rows)
    lazy val previousRowsByKey =
      previousRows.iterator.map(row => row.id -> row.child).toMap
    // Rows mostly keep their positions, so a row's previous instance is looked up where the row
    // was first, and by key only when it is not there.
    def previousChild(row: ProjectedKeyedRow, at: Int): Option[ProjectedNode] =
      if at < previousRows.length && previousRows(at).id == row.id then Some(previousRows(at).child)
      else previousRowsByKey.get(row.id)
    def keptInstance(row: ProjectedKeyedRow, at: Int): Boolean =
      if at < previousRows.length && previousRows(at).id == row.id then
        previousRows(at).child eq row.child
      else previousRowsByKey.get(row.id).exists(_ eq row.child)

    val updates = sparseChanges match
      case None          => keyed.rows.indices.map(ProjectedKeyedRowUpdate.whole).toVector
      case Some(changes) =>
        val touched  = changes.touchedComponents
        val selected = Vector.newBuilder[ProjectedKeyedRowUpdate]
        var explicit = 0
        var index    = 0
        while index < keyed.rows.length do
          val row = keyed.rows(index)
          if explicit < keyed.updates.length && keyed.updates(explicit).index == index then
            val update = keyed.updates(explicit)
            explicit += 1
            // A row's own diff still has to reach the components it holds.
            if !update.full && row.child.cids.exists(touched.contains) then
              val rowChanges = update.changes.getOrElse(SparseChanges(Set.empty, Set.empty))
              selected += update.copy(changes = Some(rowChanges.touching(touched)))
            else selected += update
          else if changes.replaced.nonEmpty && changes.replaced.contains(row.child.id) then
            selected += ProjectedKeyedRowUpdate.whole(index)
          else if !keptRow(row.child, keptInstance(row, index), changes) &&
            rowChanged(row.child, changes)
          then selected += ProjectedKeyedRowUpdate(index, None, Some(changes), full = false)
          index += 1
        selected.result()
    val shouldEmit = sparseChanges.isEmpty || keyed.changed ||
      sparseChanges.exists(_.keyedTargets.contains(keyed.id)) || updates.nonEmpty
    if !shouldEmit then Right(Json.Obj.empty)
    else
      val table = templates.getOrElse(TemplateTable())
      traverse(updates) { update =>
        val row   = keyed.rows(update.index)
        val child =
          if update.full then rendered(row.child, components, None, Some(table))
          else
            update.changes match
              case Some(changes) =>
                rendered(
                  row.child,
                  components,
                  Some(changes),
                  Some(table),
                  previousChild(row, update.oldIndex.getOrElse(update.index))
                )
              case None => Right(Json.Obj.empty)
        child.map { payload =>
          val boxed       = Json.Obj("0" -> payload)
          val value: Json = update.oldIndex match
            case None                                        => boxed
            case Some(oldIndex) if payload == Json.Obj.empty => Json.Num(oldIndex)
            case Some(oldIndex)                              => Json.Arr(Json.Num(oldIndex), boxed)
          update.index.toString -> value
        }
      }.map { entries =>
        val move        = updates.exists(_.oldIndex.nonEmpty)
        val keyedFields = entries ++ Vector("kc" -> Json.Num(keyed.rows.length)) ++
          (if move then Vector("km" -> Json.Bool(true)) else Vector.empty)
        val fields = (if sparseChanges.isEmpty || keyed.includeStatics then
                        Vector("s" -> Json.Arr(Json.Str(""), Json.Str("")))
                      else Vector.empty) :+
          ("k" -> Json.Obj(keyedFields*))
        sharingTemplates(templates, table, components, Json.Obj(fields*))
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
    val previousRows     = previous.fold(Vector.empty)(_.rows)
    lazy val previousRow = previousStreamRows(stream, previousRows)
    // Asked of the rows in order, as their previous instances are found with a cursor.
    def changed(row: ProjectedStreamRow, changes: SparseChanges): Boolean =
      val index = previousRow.indexOf(row.domId)
      val kept  = index >= 0 && (previousRows(index).child eq row.child)
      !keptRow(row.child, kept, changes) && rowChanged(row.child, changes)
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
      val table = templates.getOrElse(TemplateTable())
      for
        selected <- traverse(selectedRows)(row => flatten(row.child, components, Some(table)))
        base     <- baseStatics(stream, components)
        statics  <- selected.foldLeft[Either[PhoenixEncodingError, StreamStatics]](Right(base)) {
                     case (result, (rowStatics, dynamics)) =>
                       result.flatMap(_.including(rowStatics, dynamics))
                   }
      yield
        components.sentStatics.put(stream, statics): Unit
        val selectedPayloads = selected.map { (rowStatics, dynamics) =>
          val shaped = statics.shape(rowStatics, dynamics)
          Json.Obj(shaped.zipWithIndex.map { case (value, index) => index.toString -> value }*)
        }
        val keyedFields = selectedPayloads.zipWithIndex.map { case (value, index) =>
          index.toString -> value
        } :+ ("kc" -> Json.Num(selectedPayloads.length))
        val metadata = streamMetadata(stream)
        // A row whose static structure changed, such as a conditional that now renders,
        // needs new statics: the client renders every inserted row from the last ones sent.
        val includeStatics =
          sparseChanges.isEmpty || !stream.sentStatics.exists(_.values == statics.values)
        val fields =
          (if includeStatics then Vector("s" -> Json.Arr(statics.values.map(Json.Str(_))*))
           else Vector.empty) ++
            Vector("k" -> Json.Obj(keyedFields*), "stream" -> metadata)
        sharingTemplates(templates, table, components, Json.Obj(fields*))
  end streamJson

  /** The statics the rows of `stream` share before a payload adds the rows it sends: those the
    * browser holds, or, before it holds any or once the stream is reset, those of every row.
    */
  private def baseStatics(
    stream: ProjectedStream,
    components: Components
  ): Either[PhoenixEncodingError, StreamStatics] =
    stream.sentStatics.filter(_ => !stream.operations.reset) match
      case Some(sent) => Right(sent)
      case None       =>
        // These rows only shape the statics; what they reference is not sent.
        val rows    = stream.operations.inserts.iterator.map(_.row) ++ stream.rows.iterator
        val shaping = components.unsent
        rows.foldLeft[Either[PhoenixEncodingError, StreamStatics]](Right(StreamStatics.NoRows)) {
          (result, row) =>
            for
              statics   <- result
              flattened <- flatten(row.child, shaping, Some(TemplateTable()))
              included  <- statics.including(flattened._1, flattened._2)
            yield included
        }

  private def previousStreamRows(
    stream: ProjectedStream,
    previous: Vector[ProjectedStreamRow]
  ): StreamRowCursor[ProjectedStreamRow] =
    StreamRowCursor(
      previous,
      _.domId,
      stream.operations.inserts.iterator.map(_.row.domId).toSet,
      stream.operations.deletes.length
    )

  // Only the outermost comprehension sends the table: the browser ignores a nested one's.
  private def sharingTemplates(
    inherited: Option[TemplateTable],
    table: TemplateTable,
    components: Components,
    payload: Json.Obj
  ): Json.Obj =
    if inherited.nonEmpty then payload else table.share(payload, components.sentLifts)

  // A row the update kept as it was, holding no component the diff has to reach, did not change.
  private def keptRow(row: ProjectedNode, kept: Boolean, changes: SparseChanges): Boolean =
    kept && !row.cids.exists(changes.touchedComponents.contains)

  private def rowChanged(root: ProjectedNode, changes: SparseChanges): Boolean =
    rowContainsChangedSlot(root, changes.slots) ||
      rowContainsChangedStream(root, changes.streams) ||
      rowContainsChangedKeyed(root, changes.keyedTargets) ||
      collectIds(root).exists(changes.replaced.contains) ||
      root.cids.exists(changes.touchedComponents.contains)

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
        case (result, ProjectedPart.Dynamic(_, value: String, _)) =>
          result.map(_ => output.append(value)).map(_ => ())
        case (result, ProjectedPart.Dynamic(_, value: ProjectedValue.ComponentTarget, _)) =>
          result.flatMap(_ => renderTarget(value, components).map(output.append).map(_ => ()))
        case (result, ProjectedPart.Target(value)) =>
          result.flatMap(_ => renderTarget(value, components).map(output.append).map(_ => ()))
        case (result, child: ProjectedNode)        => result.flatMap(_ => loop(child))
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
        case (result, ProjectedPart.ComponentRoot(cid)) =>
          result.map(_ => output.append(s" data-phx-component=\"$cid\"")).map(_ => ())
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

  // A collection's rows are built from other templates than the collection's own, so a search
  // that finds the collection does not look through its rows.
  private def findKeyed(node: ProjectedNode, id: TemplateId): Vector[ProjectedKeyed] =
    if !node.mayHoldId(id) then Vector.empty
    else
      node.parts.flatMap {
        case ProjectedPart.Keyed(keyed) if keyed.id == id => Vector(keyed)
        case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => findKeyed(row.child, id))
        case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => findKeyed(row.child, id))
        case child: ProjectedNode         => findKeyed(child, id)
        case _                            => Vector.empty
      }

  private def findStreams(node: ProjectedNode, id: TemplateId): Vector[ProjectedStream] =
    if !node.mayHoldId(id) then Vector.empty
    else
      node.parts.flatMap {
        case ProjectedPart.Stream(stream) if stream.id == id => Vector(stream)
        case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => findStreams(row.child, id))
        case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => findStreams(row.child, id))
        case child: ProjectedNode         => findStreams(child, id)
        case _                            => Vector.empty
      }

  private def collectDynamics(root: ProjectedNode): Vector[(TemplateSlotId, ProjectedValue)] =
    root.parts.flatMap {
      case ProjectedPart.Dynamic(slot, value, _) => Vector(slot -> value)
      case child: ProjectedNode                  => collectDynamics(child)
      case ProjectedPart.Keyed(keyed)   => keyed.rows.flatMap(row => collectDynamics(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectDynamics(row.child))
      case _                            => Vector.empty
    }

  private def collectStreams(root: ProjectedNode): Vector[ProjectedStream] =
    root.parts.flatMap {
      case ProjectedPart.Stream(stream) =>
        stream +: stream.rows.flatMap(row => collectStreams(row.child))
      case child: ProjectedNode        => collectStreams(child)
      case ProjectedPart.Nested(child) => collectStreams(child)
      case ProjectedPart.Keyed(keyed)  => keyed.rows.flatMap(row => collectStreams(row.child))
      case _                           => Vector.empty
    }

  private def collectKeyed(root: ProjectedNode): Vector[ProjectedKeyed] =
    root.parts.flatMap {
      case ProjectedPart.Keyed(keyed) =>
        keyed +: keyed.rows.flatMap(row => collectKeyed(row.child))
      case ProjectedPart.Stream(stream) => stream.rows.flatMap(row => collectKeyed(row.child))
      case child: ProjectedNode         => collectKeyed(child)
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
      case child: ProjectedNode         => collectIds(child)
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
      case (result, RenderChange.Keyed(id, _, _)) =>
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
    // Mapping allocates only the results, where a builder would allocate room for 32 of them.
    boundary:
      Right(values.toVector.map { value =>
        f(value) match
          case Right(result) => result
          case Left(error)   => break(Left(error))
      })
end PhoenixRenderedEncoder
