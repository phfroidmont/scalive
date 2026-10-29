package scalive.render

import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.VectorBuilder
import scala.util.boundary
import scala.util.boundary.break

import zio.Exit
import zio.Scope
import zio.UIO

import scalive.ComponentRef
import scalive.streams.LiveStreamIdentity
import scalive.streams.StreamAt
import scalive.streams.StreamLimit

/** The semantic value of an evaluated HTML attribute before serialization. */
sealed trait AttributeValue

object AttributeValue:
  final case class Text(value: String) extends AttributeValue

  /** A composite attribute's text: its static tokens, which stay in the template, then its dynamic
    * tokens, which the attribute's slot carries. `static` is never empty, which the template that
    * makes it checks once; `dynamic` is empty when no dynamic token remains.
    */
  final case class Composite private[render] (static: String, dynamic: String)
      extends AttributeValue
  case object Presence                                     extends AttributeValue
  final case class ComponentTarget(ref: ComponentRef[Any]) extends AttributeValue

final case class EvaluatedAttribute private[render] (
  name: String,
  value: Option[AttributeValue],
  slot: OptionalSlotId,
  revision: RenderRevision)

/** A protocol-neutral evaluated HTML node with exact template identity and revision. */
sealed trait EvaluatedNode:
  def id: TemplateId
  def revision: RenderRevision

  /** Whether this subtree holds a component or a nested LiveView, which runtime resolution fills
    * in. Resolution keeps a subtree without either as it is.
    */
  private[render] def holdsResolvable: Boolean = resolvability != Resolvability.Static

  /** Whether this subtree holds a component or a nested LiveView that is not resolved yet, here or
    * within what is resolved in it.
    */
  private[render] def holdsUnresolved: Boolean = resolvability == Resolvability.Unresolved

  // Computed once per node. A render kept across turns keeps its nodes, so checking that it is
  // finalized again costs only the nodes the turn made.
  private[render] lazy val resolvability: Resolvability = this match
    case element: EvaluatedNode.Element => Resolvability.of(element.children)
    case flash: EvaluatedNode.Flash     => Resolvability.of(flash.child)
    case choice: EvaluatedNode.Choice   => Resolvability.of(choice.children)
    case keyed: EvaluatedNode.Keyed     => Resolvability.of(keyed.rows.view.map(_.child))
    // Stream operations may insert rows that are not among the rendered ones.
    case stream: EvaluatedNode.Stream =>
      Resolvability.Resolved.max(Resolvability.of(stream.rows.view.map(_.child)))
    case component: EvaluatedNode.Component =>
      component.resolution.fold(Resolvability.Unresolved)(value =>
        Resolvability.Resolved.max(value.child.root.resolvability)
      )
    case nested: EvaluatedNode.Nested =>
      nested.resolution.fold(Resolvability.Unresolved)(value =>
        Resolvability.Resolved.max(Resolvability.of(value.child.map(_.root)))
      )
    case _: EvaluatedNode.Text => Resolvability.Static
end EvaluatedNode

/** What resolution has left to fill in a subtree, in increasing order. */
private[render] enum Resolvability:
  // Nothing: the subtree holds no component and no nested LiveView.
  case Static
  // Components or nested LiveViews, all of them resolved.
  case Resolved
  case Unresolved

  def max(other: Resolvability): Resolvability = if other.ordinal > ordinal then other else this

private[render] object Resolvability:
  def of(nodes: Iterable[EvaluatedNode]): Resolvability =
    val iterator = nodes.iterator
    var result   = Static
    while result != Unresolved && iterator.hasNext do
      result = result.max(iterator.next().resolvability)
    result

object EvaluatedNode:
  final case class Element private[render] (
    id: TemplateId,
    tag: String,
    void: Boolean,
    attributes: Vector[EvaluatedAttribute],
    children: Vector[EvaluatedNode],
    revision: RenderRevision)
      extends EvaluatedNode

  final case class Text private[render] (
    id: TemplateId,
    slot: OptionalSlotId,
    value: String,
    raw: Boolean,
    revision: RenderRevision)
      extends EvaluatedNode

  /** A transparent, retained insertion point for one flash declaration. */
  final case class Flash private[render] (
    id: TemplateId,
    child: Option[Element],
    revision: RenderRevision)
      extends EvaluatedNode

  /** A transparent insertion point whose selected children may change. */
  final case class Choice private[render] (
    id: TemplateId,
    children: Vector[EvaluatedNode],
    revision: RenderRevision)
      extends EvaluatedNode

  final case class KeyedRow private[render] (id: RowId, child: Element)

  final case class StreamRow private[render] (domId: String, child: Element)

  final case class StreamInsert private[render] (
    row: StreamRow,
    at: StreamAt,
    limit: Option[StreamLimit],
    updateOnly: Boolean)

  final case class StreamOperations private[render] (
    inserts: Vector[StreamInsert],
    deletes: Vector[String],
    reset: Boolean)

  /** A transparent collection insertion point. Row identity is independent of current order. */
  final case class Keyed private[render] (
    id: TemplateId,
    rows: Vector[KeyedRow],
    revision: RenderRevision)
      extends EvaluatedNode

  /** Transparent component declaration, finalized by runtime resolution before commit. */
  final case class Component private[render] (
    id: TemplateId,
    applicationId: String,
    resolution: Option[ComponentResolution],
    revision: RenderRevision)
      extends EvaluatedNode

  /** Transparent nested LiveView declaration; topology is owned by runtime code. */
  final case class Nested private[render] (
    id: TemplateId,
    applicationId: String,
    resolution: Option[NestedResolution],
    revision: RenderRevision)
      extends EvaluatedNode

  /** A transparent semantic stream snapshot and its pending protocol-neutral operations. */
  final case class Stream private[render] (
    id: TemplateId,
    identity: LiveStreamIdentity,
    generation: Long,
    rows: Vector[StreamRow],
    operations: StreamOperations,
    revision: RenderRevision)
      extends EvaluatedNode
end EvaluatedNode

/** The immutable semantic tree consumed by both exact diffing and full HTML rendering. */
final case class EvaluatedTree private[render] (
  root: EvaluatedNode.Element,
  private[render] val programIdentity: RenderProgramId):
  def revision: RenderRevision = root.revision

/** Closeable ownership boundary for resources prepared during one render evaluation.
  *
  * Runtime modules attach finalizers before evaluation. Successful commit retains the scope for the
  * resulting [[CommittedRender]]; failure, discard, or owner shutdown closes it.
  */
final class CandidateScope private (
  private val scope: Scope.Closeable,
  private val state: AtomicReference[CandidateScope.State]):
  import CandidateScope.State

  private[scalive] def beginEvaluation(): Either[RenderError, Unit] =
    Either.cond(
      state.compareAndSet(State.Fresh, State.Evaluating),
      (),
      RenderError.CandidateScopeUnavailable()
    )

  private[scalive] def completeEvaluation(): Either[RenderError, Unit] =
    Either.cond(
      state.compareAndSet(State.Evaluating, State.Ready),
      (),
      RenderError.CandidateScopeUnavailable()
    )

  private[render] def retain(): Boolean = state.compareAndSet(State.Ready, State.Committed)

  private[render] def discard: UIO[Unit] = closeCandidate

  private def closeCandidate: UIO[Unit] =
    zio.ZIO.suspendSucceed {
      val current = state.get()
      current match
        case State.Fresh | State.Evaluating | State.Ready =>
          if state.compareAndSet(current, State.Closed) then scope.close(Exit.unit)
          else closeCandidate
        case State.Committed | State.Closed => zio.ZIO.unit
    }

  private[render] def closeCommitted: UIO[Unit] = close(State.Committed)

  private[scalive] def closeFromOwner: UIO[Unit] =
    zio.ZIO.suspendSucceed {
      val current = state.get()
      if current == State.Closed then zio.ZIO.unit
      else if state.compareAndSet(current, State.Closed) then scope.close(Exit.unit)
      else closeFromOwner
    }

  private def close(expected: State): UIO[Unit] =
    if state.compareAndSet(expected, State.Closed) then scope.close(Exit.unit)
    else zio.ZIO.unit

  def isClosed: Boolean = state.get() == State.Closed

  private[scalive] def addFinalizer(finalizer: UIO[Any]): UIO[Unit] =
    scope.addFinalizerExit(_ => finalizer)
end CandidateScope

object CandidateScope:
  private enum State:
    case Fresh, Evaluating, Ready, Committed, Closed

  private[scalive] def make: UIO[CandidateScope] =
    Scope.make.map(scope => CandidateScope(scope, AtomicReference(State.Fresh)))

/** Complete render state visible between lifecycle turns.
  *
  * The tree, bindings, signal samples, and retained scope always describe the same committed input.
  */
final case class CommittedRender[+Msg] private[render] (
  tree: EvaluatedTree,
  bindings: BindingTable[Msg],
  signalEvaluation: SignalEvaluation,
  componentRequirements: Vector[ComponentRequirement[Msg]],
  nestedRequirements: Vector[NestedRequirement],
  streamRequirements: Vector[StreamRequirement[Msg]],
  private[scalive] val scope: CandidateScope,
  private[render] val programIdentity: RenderProgramId):
  /** Closes this render after its replacement has become active. */
  def close: UIO[Unit] = scope.closeCommitted

  /** Whether this render read something signals do not track, so that its input alone does not tell
    * whether it would render the same again.
    */
  private[scalive] def readsUntracked: Boolean = signalEvaluation.untracked

  /** Returns whether this render's program generates `id`, now or in an earlier render, rather than
    * another program such as a component's.
    */
  private[scalive] def generates(id: BindingId): Boolean = BindingId.isFrom(id, programIdentity)

  /** This render, also resolving for a while the bindings `previous` had and this one dropped. */
  private[scalive] def retiring[M >: Msg](
    previous: CommittedRender[M],
    now: Instant
  ): CommittedRender[M] =
    if previous eq this then this
    else copy(bindings = bindings.retiring(previous.bindings, now))

/** Isolated render state awaiting lifecycle validation and commit.
  *
  * `componentRequirements` are the components the render declares, in order. It is the committed
  * render's vector itself when the render declares the same ones in the same order. A declaration
  * the render carries over from the committed render is the same requirement, and the tree holds
  * the resolution committed for it; the tree leaves each other declaration unresolved.
  */
final case class RenderCandidate[+Msg] private[render] (
  tree: EvaluatedTree,
  bindings: BindingTable[Msg],
  signalEvaluation: SignalEvaluation,
  componentRequirements: Vector[ComponentRequirement[Msg]],
  nestedRequirements: Vector[NestedRequirement],
  streamRequirements: Vector[StreamRequirement[Msg]],
  private[scalive] val stagedScope: CandidateScope,
  private[render] val programIdentity: RenderProgramId,
  private val commitTail: CandidateCommit,
  // Whether the input differs from the committed render's, or there is none: whether the view
  // rendered, in Phoenix's terms, which runs after-render hooks only then.
  private[scalive] val inputChanged: Boolean):
  /** Transfers scope ownership to a committed render. A candidate can be committed only once. */
  def commit: CommittedRender[Msg] =
    val unresolvedComponents = EvaluatedTree.unresolvedComponents(tree.root)
    val unresolvedNested     = EvaluatedTree.unresolvedNested(tree.root)
    if unresolvedComponents.nonEmpty then
      throw IllegalStateException(RenderError.UnresolvedComponents(unresolvedComponents).getMessage)
    else if unresolvedNested.nonEmpty then
      throw IllegalStateException(RenderError.UnresolvedNested(unresolvedNested).getMessage)
    else if stagedScope.retain() then
      commitTail.run()
      CommittedRender(
        tree,
        bindings,
        signalEvaluation,
        componentRequirements,
        nestedRequirements,
        streamRequirements,
        stagedScope,
        programIdentity
      )
    else throw IllegalStateException("render candidate is no longer staged")

  /** Closes candidate-owned resources without changing previously committed state. */
  def discard: UIO[Unit] = zio.ZIO.succeed(commitTail.rollback()) *> stagedScope.discard

  private[render] def newRowScopes: Map[RowId, SignalScope] = commitTail.newRowScopes

  /** Finalizes the component placeholders without evaluating the root render program again: each
    * one the tree leaves unresolved needs a resolution, and one the render carried from the
    * committed render is replaced only by the resolution it is given, if any.
    */
  private[scalive] def resolveComponents(
    resolutions: Vector[ComponentResolution]
  ): Either[RenderError, RenderCandidate[Msg]] =
    EvaluatedTree
      .resolveComponents(tree.root, resolutions)
      .map(root => if root eq tree.root then this else copy(tree = tree.copy(root = root)))

  /** The declarations the tree leaves unresolved. */
  private[scalive] def unresolvedComponents: Set[TemplateId] =
    EvaluatedTree.unresolvedComponents(tree.root).toSet

  /** Finalizes every nested LiveView placeholder owned by this render program. */
  private[scalive] def resolveNested(
    resolutions: Vector[NestedResolution]
  ): Either[RenderError, RenderCandidate[Msg]] =
    EvaluatedTree
      .resolveNested(tree.root, nestedRequirements, resolutions)
      .map(root => copy(tree = tree.copy(root = root)))
end RenderCandidate

/** Infallible assignments and candidate-only cleanup performed around ownership transfer. */
final private[render] class CandidateCommit(
  private val actions: Array[() => Unit],
  private val rollbackActions: Array[() => Unit] = Array.empty,
  val newRowScopes: Map[RowId, SignalScope] = Map.empty):
  private val state = AtomicReference(CandidateCommit.State.Fresh)

  def run(): Unit =
    if !state.compareAndSet(CandidateCommit.State.Fresh, CandidateCommit.State.Committed) then
      throw IllegalStateException("candidate commit tail is no longer staged")
    runAll(actions)

  def rollback(): Unit =
    if state.compareAndSet(CandidateCommit.State.Fresh, CandidateCommit.State.RolledBack) then
      runAll(rollbackActions)

  private def runAll(values: Array[() => Unit]): Unit =
    var index = 0
    while index < values.length do
      values(index)()
      index += 1

private object CandidateCommit:
  private enum State:
    case Fresh, Committed, RolledBack

object EvaluatedTree:
  private[render] def unresolvedComponents(node: EvaluatedNode): Vector[TemplateId] = node match
    case _ if !node.holdsUnresolved     => Vector.empty
    case element: EvaluatedNode.Element => element.children.flatMap(unresolvedComponents)
    case flash: EvaluatedNode.Flash     => flash.child.toVector.flatMap(unresolvedComponents)
    case choice: EvaluatedNode.Choice   => choice.children.flatMap(unresolvedComponents)
    case keyed: EvaluatedNode.Keyed   => keyed.rows.flatMap(row => unresolvedComponents(row.child))
    case stream: EvaluatedNode.Stream => stream.rows.flatMap(row => unresolvedComponents(row.child))
    case component: EvaluatedNode.Component if component.resolution.isEmpty => Vector(component.id)
    case component: EvaluatedNode.Component                                 =>
      component.resolution.toVector.flatMap(value => unresolvedComponents(value.child.root))
    case nested: EvaluatedNode.Nested =>
      nested.resolution.toVector
        .flatMap(_.child.toVector).flatMap(tree => unresolvedComponents(tree.root))
    case _: EvaluatedNode.Text => Vector.empty

  private[render] def unresolvedNested(node: EvaluatedNode): Vector[TemplateId] = node match
    case _ if !node.holdsUnresolved     => Vector.empty
    case element: EvaluatedNode.Element => element.children.flatMap(unresolvedNested)
    case flash: EvaluatedNode.Flash     => flash.child.toVector.flatMap(unresolvedNested)
    case choice: EvaluatedNode.Choice   => choice.children.flatMap(unresolvedNested)
    case keyed: EvaluatedNode.Keyed     => keyed.rows.flatMap(row => unresolvedNested(row.child))
    case stream: EvaluatedNode.Stream   => stream.rows.flatMap(row => unresolvedNested(row.child))
    case component: EvaluatedNode.Component =>
      component.resolution.toVector.flatMap(value => unresolvedNested(value.child.root))
    case nested: EvaluatedNode.Nested if nested.resolution.isEmpty => Vector(nested.id)
    case nested: EvaluatedNode.Nested                              =>
      nested.resolution.toVector
        .flatMap(_.child.toVector).flatMap(tree => unresolvedNested(tree.root))
    case _: EvaluatedNode.Text => Vector.empty

  /** `root` with `resolutions` resolved into it. Each declaration `root` leaves unresolved needs
    * one; a declaration it carries from the committed render already holds that render's, which a
    * resolution replaces. Only what the resolutions add is checked: the instance tokens they hold
    * differ from each other.
    */
  private[render] def resolveComponents(
    root: EvaluatedNode.Element,
    resolutions: Vector[ComponentResolution]
  ): Either[RenderError, EvaluatedNode.Element] =
    val supplied = resolutions.foldLeft[Either[RenderError, Map[TemplateId, ComponentResolution]]](
      Right(Map.empty)
    ) { (result, resolution) =>
      result.flatMap { all =>
        if all.contains(resolution.location) then
          Left(
            RenderError.ComponentResolutionInvalid(
              s"duplicate component resolution ${resolution.location.value}"
            )
          )
        else if resolution.instanceToken == null then
          Left(RenderError.ComponentResolutionInvalid("component instance token is null"))
        else if unresolvedComponents(resolution.child.root).nonEmpty ||
          unresolvedNested(resolution.child.root).nonEmpty
        then
          Left(
            RenderError.ComponentResolutionInvalid(
              s"component child at ${resolution.location.value} is not finalized"
            )
          )
        else Right(all.updated(resolution.location, resolution))
      }
    }

    supplied.flatMap { values =>
      if values.isEmpty && !root.holdsUnresolved then Right(root)
      else
        val resolved = scala.collection.mutable.HashSet.empty[TemplateId]
        resolveNode(root, values, resolved).flatMap { node =>
          values.keysIterator.find(location => !resolved.contains(location)) match
            case Some(location) =>
              Left(
                RenderError.ComponentResolutionInvalid(
                  s"unknown component declaration ${location.value}"
                )
              )
            case None =>
              validateUniqueComponentTokens(values.valuesIterator.toVector)
                .map(_ => node.asInstanceOf[EvaluatedNode.Element])
        }
    }
  end resolveComponents

  private[render] def resolveNested(
    root: EvaluatedNode.Element,
    requirements: Vector[NestedRequirement],
    resolutions: Vector[NestedResolution]
  ): Either[RenderError, EvaluatedNode.Element] =
    // Nested LiveViews are not tracked by signals, so a render holds none it did not declare.
    if requirements.isEmpty && resolutions.isEmpty then Right(root)
    else resolveDeclaredNested(root, requirements, resolutions)

  private def resolveDeclaredNested(
    root: EvaluatedNode.Element,
    requirements: Vector[NestedRequirement],
    resolutions: Vector[NestedResolution]
  ): Either[RenderError, EvaluatedNode.Element] =
    val duplicateLocations = requirements.groupBy(_.location).collectFirst {
      case (location, values) if values.size > 1 => location
    }
    val duplicateApplicationIds = requirements.groupBy(_.applicationId).collectFirst {
      case (applicationId, values) if values.size > 1 => applicationId
    }

    if duplicateLocations.nonEmpty then
      Left(RenderError.NestedResolutionInvalid("duplicate nested declaration location"))
    else if duplicateApplicationIds.nonEmpty then
      Left(
        RenderError.NestedResolutionInvalid(
          s"duplicate nested application id '${duplicateApplicationIds.get}'"
        )
      )
    else
      val expected = requirements.map(requirement => requirement.location -> requirement).toMap
      val supplied = resolutions.foldLeft[Either[RenderError, Map[TemplateId, NestedResolution]]](
        Right(Map.empty)
      ) { (result, resolution) =>
        result.flatMap { all =>
          expected.get(resolution.location) match
            case None =>
              Left(
                RenderError.NestedResolutionInvalid(
                  s"unknown nested declaration ${resolution.location.value}"
                )
              )
            case Some(requirement) if !(resolution.requirement eq requirement) =>
              Left(
                RenderError.NestedResolutionInvalid("nested resolution belongs to another render")
              )
            case Some(_) if all.contains(resolution.location) =>
              Left(
                RenderError.NestedResolutionInvalid(
                  s"duplicate nested resolution ${resolution.location.value}"
                )
              )
            case Some(_)
                if resolution.applicationId != expected(resolution.location).applicationId =>
              Left(
                RenderError.NestedResolutionInvalid(
                  s"nested application id mismatch at ${resolution.location.value}"
                )
              )
            case Some(requirement) if resolution.sticky != requirement.sticky =>
              Left(
                RenderError.NestedResolutionInvalid(
                  s"nested sticky flag mismatch at ${resolution.location.value}"
                )
              )
            case Some(_) if resolution.instanceToken == null =>
              Left(RenderError.NestedResolutionInvalid("nested instance token is null"))
            case Some(_)
                if requiredNestedMetadata(resolution)
                  .exists(value => value == null || value.isEmpty) =>
              Left(RenderError.NestedResolutionInvalid("nested resolution metadata is empty"))
            case Some(_)
                if resolution.staticCredential == null ||
                  resolution.staticCredential.exists(value => value == null || value.isEmpty) =>
              Left(RenderError.NestedResolutionInvalid("nested static credential is empty"))
            case Some(_) if resolution.child == null =>
              Left(RenderError.NestedResolutionInvalid("nested child option is null"))
            case Some(_)
                if resolution.child.exists(tree =>
                  unresolvedComponents(tree.root).nonEmpty || unresolvedNested(tree.root).nonEmpty
                ) =>
              Left(
                RenderError.NestedResolutionInvalid(
                  s"nested child at ${resolution.location.value} is not finalized"
                )
              )
            case Some(_) => Right(all.updated(resolution.location, resolution))
        }
      }

      supplied.flatMap { values =>
        val missing = expected.keySet -- values.keySet
        if missing.nonEmpty then Left(RenderError.UnresolvedNested(missing.toVector))
        else resolveNestedNode(root, values).map(_.asInstanceOf[EvaluatedNode.Element])
      }
    end if
  end resolveDeclaredNested

  private def requiredNestedMetadata(resolution: NestedResolution): Vector[String] =
    Vector(
      resolution.applicationId,
      resolution.parentDomId,
      resolution.topic,
      resolution.joinCredential
    )

  private def resolveNestedNode(
    node: EvaluatedNode,
    resolutions: Map[TemplateId, NestedResolution]
  ): Either[RenderError, EvaluatedNode] = node match
    // A render resolves nothing into a nested LiveView it keeps: it declares each one again.
    case _ if !node.holdsUnresolved   => Right(node)
    case nested: EvaluatedNode.Nested =>
      resolutions
        .get(nested.id).map(value => nested.copy(resolution = Some(value))).toRight(
          RenderError.UnresolvedNested(Vector(nested.id))
        )
    // A component's child is a separate finalized render program, which this keeps as it is.
    case other => resolveContents(other, resolveNestedNode(_, resolutions))

  private def resolveNode(
    node: EvaluatedNode,
    resolutions: Map[TemplateId, ComponentResolution],
    resolved: scala.collection.mutable.Set[TemplateId]
  ): Either[RenderError, EvaluatedNode] = node match
    case _ if !node.holdsResolvable         => Right(node)
    case component: EvaluatedNode.Component =>
      // Without an option per component, as a resolution reaches each one the render holds.
      val value = resolutions.getOrElse(component.id, null)
      if value == null then
        if component.resolution.nonEmpty then Right(component)
        else Left(RenderError.UnresolvedComponents(Vector(component.id)))
      else if value.applicationId != component.applicationId then
        Left(
          RenderError.ComponentResolutionInvalid(
            s"component application id mismatch at ${component.id.value}"
          )
        )
      else
        resolved += component.id
        if component.resolution.exists(sameResolution(_, value)) then Right(component)
        else Right(component.copy(resolution = Some(value)))
    case other => resolveContents(other, resolveNode(_, resolutions, resolved))

  // A component the runtime kept resolves to the instance and the committed render it held.
  private def sameResolution(held: ComponentResolution, next: ComponentResolution): Boolean =
    (held.instanceToken eq next.instanceToken) && (held.child eq next.child) &&
      held.ref == next.ref && held.location == next.location &&
      held.applicationId == next.applicationId

  /** `node` with each node it contains resolved by `resolve`. A node whose contents all resolve to
    * themselves stays as it is, so that resolving a render again keeps what it does not change.
    */
  private def resolveContents(
    node: EvaluatedNode,
    resolve: EvaluatedNode => Either[RenderError, EvaluatedNode]
  ): Either[RenderError, EvaluatedNode] = node match
    case element: EvaluatedNode.Element =>
      resolveEach(element.children)(resolve).map(children =>
        if children eq element.children then element else element.copy(children = children)
      )
    case flash: EvaluatedNode.Flash =>
      flash.child match
        case Some(child) =>
          resolve(child).map(value =>
            if value eq child then flash
            else flash.copy(child = Some(value.asInstanceOf[EvaluatedNode.Element]))
          )
        case None => Right(flash)
    case choice: EvaluatedNode.Choice =>
      resolveEach(choice.children)(resolve).map(children =>
        if children eq choice.children then choice else choice.copy(children = children)
      )
    case keyed: EvaluatedNode.Keyed =>
      resolveEach(keyed.rows) { row =>
        resolve(row.child).map(child =>
          if child eq row.child then row
          else row.copy(child = child.asInstanceOf[EvaluatedNode.Element])
        )
      }.map(rows => if rows eq keyed.rows then keyed else keyed.copy(rows = rows))
    case stream: EvaluatedNode.Stream =>
      resolveEach(stream.rows) { row =>
        resolve(row.child).map(child =>
          if child eq row.child then row
          else row.copy(child = child.asInstanceOf[EvaluatedNode.Element])
        )
      }.map { rows =>
        if rows eq stream.rows then stream
        else
          val byId       = rows.map(row => row.domId -> row).toMap
          val operations = stream.operations.copy(inserts =
            stream.operations.inserts
              .map(insert => insert.copy(row = byId.getOrElse(insert.row.domId, insert.row)))
          )
          stream.copy(rows = rows, operations = operations)
      }
    case other => Right(other)

  /** `nodes`, each resolved by `resolve`; `nodes` itself when each resolves to itself. */
  private def resolveEach[A <: AnyRef](
    nodes: Vector[A]
  )(
    resolve: A => Either[RenderError, A]
  ): Either[RenderError, Vector[A]] =
    boundary:
      // Empty until a node resolves to another one, then the nodes resolved so far.
      var resolved = Option.empty[VectorBuilder[A]]
      nodes.indices.foreach { index =>
        val node = nodes(index)
        val next = resolve(node) match
          case Right(value) => value
          case Left(error)  => break(Left(error))
        if resolved.isEmpty && !(next eq node) then
          resolved = Some(VectorBuilder[A]().addAll(nodes.view.take(index)))
        resolved.foreach(_.addOne(next))
      }
      Right(resolved.fold(nodes)(_.result()))

  /** `node` without what resolution filled in: each component and nested LiveView in it without its
    * resolution, and every node on the way to one copied, the rest kept as it is. `node` is then
    * [[resolvedFrom]] what this returns.
    *
    * A render program records nodes across turns, for which this keeps no component's render: an
    * update of the component replaces that render in the committed tree, but not in the record.
    */
  private[render] def unresolved[Node <: EvaluatedNode](node: Node): Node =
    val stripped = node match
      case _ if !node.holdsResolvable         => node
      case component: EvaluatedNode.Component =>
        if component.resolution.isEmpty then component else component.copy(resolution = None)
      case nested: EvaluatedNode.Nested =>
        if nested.resolution.isEmpty then nested else nested.copy(resolution = None)
      // Removing a resolution cannot fail.
      case other =>
        resolveContents(other, child => Right(unresolved(child))).fold(throw _, identity)
    stripped.asInstanceOf[Node]

  /** Whether `node` is `evaluated`, or what resolving components and nested LiveViews into
    * `evaluated` made of it. Resolution keeps a node that holds neither as it is, and replaces one
    * that does by a copy that differs only in what it holds and in their resolutions.
    */
  private[render] def resolvedFrom(node: EvaluatedNode, evaluated: EvaluatedNode): Boolean =
    if node eq evaluated then true
    else if !evaluated.holdsResolvable || node.id != evaluated.id ||
      node.revision != evaluated.revision
    then false
    else
      (node, evaluated) match
        case (node: EvaluatedNode.Element, evaluated: EvaluatedNode.Element) =>
          (node.attributes eq evaluated.attributes) &&
          pairwise(node.children, evaluated.children)(resolvedFrom)
        case (node: EvaluatedNode.Flash, evaluated: EvaluatedNode.Flash) =>
          (node.child, evaluated.child) match
            case (Some(child), Some(evaluatedChild)) => resolvedFrom(child, evaluatedChild)
            case _                                   => false
        case (node: EvaluatedNode.Choice, evaluated: EvaluatedNode.Choice) =>
          pairwise(node.children, evaluated.children)(resolvedFrom)
        case (node: EvaluatedNode.Keyed, evaluated: EvaluatedNode.Keyed) =>
          pairwise(node.rows, evaluated.rows)((row, evaluatedRow) =>
            row.id == evaluatedRow.id && resolvedFrom(row.child, evaluatedRow.child)
          )
        case (node: EvaluatedNode.Stream, evaluated: EvaluatedNode.Stream) =>
          (node.identity eq evaluated.identity) && node.generation == evaluated.generation &&
          (node.operations.deletes eq evaluated.operations.deletes) &&
          node.operations.reset == evaluated.operations.reset &&
          pairwise(node.rows, evaluated.rows)(streamRowResolvedFrom) &&
          pairwise(node.operations.inserts, evaluated.operations.inserts)(
            (insert, evaluatedInsert) =>
              insert.at == evaluatedInsert.at && insert.limit == evaluatedInsert.limit &&
                insert.updateOnly == evaluatedInsert.updateOnly &&
                streamRowResolvedFrom(insert.row, evaluatedInsert.row)
          )
        case (node: EvaluatedNode.Component, evaluated: EvaluatedNode.Component) =>
          node.applicationId == evaluated.applicationId
        case (node: EvaluatedNode.Nested, evaluated: EvaluatedNode.Nested) =>
          node.applicationId == evaluated.applicationId
        case _ => false

  private def streamRowResolvedFrom(
    row: EvaluatedNode.StreamRow,
    evaluated: EvaluatedNode.StreamRow
  ): Boolean =
    row.domId == evaluated.domId && resolvedFrom(row.child, evaluated.child)

  private inline def pairwise[A](
    nodes: Vector[A],
    evaluated: Vector[A]
  )(
    inline same: (A, A) => Boolean
  ): Boolean =
    nodes.length == evaluated.length && nodes.indices.forall(index =>
      same(nodes(index), evaluated(index))
    )

  /** Rejects an instance token that `resolutions`, with the components their renders hold, hold
    * twice.
    */
  private def validateUniqueComponentTokens(
    resolutions: Vector[ComponentResolution]
  ): Either[RenderError, Unit] =
    val seen = IdentityHashMap[Object, java.lang.Boolean]()

    def loop(node: EvaluatedNode): Either[RenderError, Unit] = node match
      case _ if !node.holdsResolvable         => Right(())
      case element: EvaluatedNode.Element     => loopAll(element.children)
      case flash: EvaluatedNode.Flash         => loopAll(flash.child.toVector)
      case choice: EvaluatedNode.Choice       => loopAll(choice.children)
      case keyed: EvaluatedNode.Keyed         => loopAll(keyed.rows.map(_.child))
      case stream: EvaluatedNode.Stream       => loopAll(stream.rows.map(_.child))
      case component: EvaluatedNode.Component =>
        component.resolution match
          case None        => Left(RenderError.UnresolvedComponents(Vector(component.id)))
          case Some(value) => visit(value)
      case nested: EvaluatedNode.Nested =>
        nested.resolution match
          case None        => Left(RenderError.UnresolvedNested(Vector(nested.id)))
          case Some(value) =>
            value.child match
              case Some(child) => loop(child.root)
              case None        => Right(())
      case _: EvaluatedNode.Text => Right(())

    def visit(value: ComponentResolution): Either[RenderError, Unit] =
      if seen.put(value.instanceToken, java.lang.Boolean.TRUE) != null then
        Left(
          RenderError.ComponentResolutionInvalid(
            s"duplicate component instance token at declaration ${value.location.value}"
          )
        )
      else loop(value.child.root)

    def loopAll(nodes: Vector[EvaluatedNode]): Either[RenderError, Unit] =
      nodes.foldLeft[Either[RenderError, Unit]](Right(()))((result, node) =>
        result.flatMap(_ => loop(node))
      )

    resolutions.foldLeft[Either[RenderError, Unit]](Right(()))((result, value) =>
      result.flatMap(_ => visit(value))
    )
  end validateUniqueComponentTokens
end EvaluatedTree
