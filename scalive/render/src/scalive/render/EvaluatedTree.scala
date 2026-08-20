package scalive.render

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

import zio.Exit
import zio.Scope
import zio.UIO

import scalive.ComponentRef
import scalive.streams.LiveStreamIdentity
import scalive.streams.StreamAt
import scalive.streams.StreamLimit

/** The semantic value of an evaluated HTML attribute before serialization. */
enum AttributeValue:
  case Text(value: String)
  case Presence
  case ComponentTarget[Message](ref: ComponentRef[Message])

final case class EvaluatedAttribute private[render] (
  name: String,
  value: Option[AttributeValue],
  slot: Option[TemplateSlotId],
  revision: RenderRevision)

/** A protocol-neutral evaluated HTML node with exact template identity and revision. */
sealed trait EvaluatedNode:
  def id: TemplateId
  def revision: RenderRevision

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
    slot: Option[TemplateSlotId],
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

  /** A transparent insertion point whose selected child may change. */
  final case class Choice private[render] (
    id: TemplateId,
    child: Option[EvaluatedNode],
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

/** Isolated render state awaiting lifecycle validation and commit. */
final case class RenderCandidate[+Msg] private[render] (
  tree: EvaluatedTree,
  bindings: BindingTable[Msg],
  signalEvaluation: SignalEvaluation,
  componentRequirements: Vector[ComponentRequirement[Msg]],
  nestedRequirements: Vector[NestedRequirement],
  streamRequirements: Vector[StreamRequirement[Msg]],
  private[scalive] val stagedScope: CandidateScope,
  private[render] val programIdentity: RenderProgramId,
  private val commitTail: CandidateCommit):
  /** Transfers scope ownership to a committed render. A candidate can be committed only once. */
  def commit: CommittedRender[Msg] =
    val unresolved = EvaluatedTree.unresolvedComponents(tree.root)
    if unresolved.nonEmpty then
      throw IllegalStateException(RenderError.UnresolvedComponents(unresolved).getMessage)
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

  /** Finalizes every component placeholder without evaluating the root render program again. */
  private[scalive] def resolveComponents(
    resolutions: Vector[ComponentResolution]
  ): Either[RenderError, RenderCandidate[Msg]] =
    EvaluatedTree
      .resolveComponents(tree.root, componentRequirements, resolutions)
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
    case element: EvaluatedNode.Element => element.children.flatMap(unresolvedComponents)
    case flash: EvaluatedNode.Flash     => flash.child.toVector.flatMap(unresolvedComponents)
    case choice: EvaluatedNode.Choice   => choice.child.toVector.flatMap(unresolvedComponents)
    case keyed: EvaluatedNode.Keyed   => keyed.rows.flatMap(row => unresolvedComponents(row.child))
    case stream: EvaluatedNode.Stream => stream.rows.flatMap(row => unresolvedComponents(row.child))
    case component: EvaluatedNode.Component if component.resolution.isEmpty => Vector(component.id)
    case component: EvaluatedNode.Component                                 =>
      component.resolution.toVector.flatMap(value => unresolvedComponents(value.child.root))
    case _: EvaluatedNode.Text | _: EvaluatedNode.Nested => Vector.empty

  private[render] def resolveComponents[Msg](
    root: EvaluatedNode.Element,
    requirements: Vector[ComponentRequirement[Msg]],
    resolutions: Vector[ComponentResolution]
  ): Either[RenderError, EvaluatedNode.Element] =
    val expected = requirements.map(requirement => requirement.location -> requirement).toMap
    val supplied = resolutions.foldLeft[Either[RenderError, Map[TemplateId, ComponentResolution]]](
      Right(Map.empty)
    ) { (result, resolution) =>
      result.flatMap { all =>
        if !expected.contains(resolution.location) then
          Left(
            RenderError.ComponentResolutionInvalid(
              s"unknown component declaration ${resolution.location.value}"
            )
          )
        else if all.contains(resolution.location) then
          Left(
            RenderError.ComponentResolutionInvalid(
              s"duplicate component resolution ${resolution.location.value}"
            )
          )
        else if resolution.instanceToken == null then
          Left(RenderError.ComponentResolutionInvalid("component instance token is null"))
        else if resolution.applicationId != expected(resolution.location).applicationId then
          Left(
            RenderError.ComponentResolutionInvalid(
              s"component application id mismatch at ${resolution.location.value}"
            )
          )
        else if unresolvedComponents(resolution.child.root).nonEmpty then
          Left(
            RenderError.ComponentResolutionInvalid(
              s"component child at ${resolution.location.value} is not finalized"
            )
          )
        else Right(all.updated(resolution.location, resolution))
      }
    }

    supplied.flatMap { values =>
      val missing = expected.keySet -- values.keySet
      if missing.nonEmpty then Left(RenderError.UnresolvedComponents(missing.toVector))
      else
        resolveNode(root, values).flatMap { resolved =>
          val element = resolved.asInstanceOf[EvaluatedNode.Element]
          validateUniqueComponentTokens(element).map(_ => element)
        }
    }
  end resolveComponents

  private def resolveNode(
    node: EvaluatedNode,
    resolutions: Map[TemplateId, ComponentResolution]
  ): Either[RenderError, EvaluatedNode] = node match
    case element: EvaluatedNode.Element =>
      traverseNodes(element.children, resolutions).map(children =>
        element.copy(children = children)
      )
    case flash: EvaluatedNode.Flash =>
      flash.child match
        case Some(child) =>
          resolveNode(child, resolutions)
            .map(value => flash.copy(child = Some(value.asInstanceOf[EvaluatedNode.Element])))
        case None => Right(flash)
    case choice: EvaluatedNode.Choice =>
      choice.child match
        case Some(child) =>
          resolveNode(child, resolutions).map(value => choice.copy(child = Some(value)))
        case None => Right(choice)
    case keyed: EvaluatedNode.Keyed =>
      keyed.rows
        .foldLeft[Either[RenderError, Vector[EvaluatedNode.KeyedRow]]](Right(Vector.empty)) {
          (result, row) =>
            for
              accumulated <- result
              child       <- resolveNode(row.child, resolutions)
            yield accumulated :+ row.copy(child = child.asInstanceOf[EvaluatedNode.Element])
        }.map(rows => keyed.copy(rows = rows))
    case stream: EvaluatedNode.Stream =>
      stream.rows
        .foldLeft[Either[RenderError, Vector[EvaluatedNode.StreamRow]]](Right(Vector.empty)) {
          (result, row) =>
            for
              accumulated <- result
              child       <- resolveNode(row.child, resolutions)
            yield accumulated :+ row.copy(child = child.asInstanceOf[EvaluatedNode.Element])
        }.map { rows =>
          val byId       = rows.map(row => row.domId -> row).toMap
          val operations = stream.operations.copy(inserts =
            stream.operations.inserts
              .map(insert => insert.copy(row = byId.getOrElse(insert.row.domId, insert.row)))
          )
          stream.copy(rows = rows, operations = operations)
        }
    case component: EvaluatedNode.Component =>
      resolutions
        .get(component.id)
        .map(value => component.copy(resolution = Some(value))).toRight(
          RenderError.UnresolvedComponents(Vector(component.id))
        )
    case other => Right(other)

  private def traverseNodes(
    nodes: Vector[EvaluatedNode],
    resolutions: Map[TemplateId, ComponentResolution]
  ): Either[RenderError, Vector[EvaluatedNode]] =
    nodes.foldLeft[Either[RenderError, Vector[EvaluatedNode]]](Right(Vector.empty)) {
      (result, node) =>
        for
          accumulated <- result
          resolved    <- resolveNode(node, resolutions)
        yield accumulated :+ resolved
    }

  private def validateUniqueComponentTokens(root: EvaluatedNode): Either[RenderError, Unit] =
    val seen = IdentityHashMap[Object, java.lang.Boolean]()

    def loop(node: EvaluatedNode): Either[RenderError, Unit] = node match
      case element: EvaluatedNode.Element     => loopAll(element.children)
      case flash: EvaluatedNode.Flash         => loopAll(flash.child.toVector)
      case choice: EvaluatedNode.Choice       => loopAll(choice.child.toVector)
      case keyed: EvaluatedNode.Keyed         => loopAll(keyed.rows.map(_.child))
      case stream: EvaluatedNode.Stream       => loopAll(stream.rows.map(_.child))
      case component: EvaluatedNode.Component =>
        component.resolution match
          case None        => Left(RenderError.UnresolvedComponents(Vector(component.id)))
          case Some(value) =>
            if seen.put(value.instanceToken, java.lang.Boolean.TRUE) != null then
              Left(
                RenderError.ComponentResolutionInvalid(
                  s"duplicate component instance token at declaration ${component.id.value}"
                )
              )
            else loop(value.child.root)
      case _: EvaluatedNode.Text | _: EvaluatedNode.Nested => Right(())

    def loopAll(nodes: Vector[EvaluatedNode]): Either[RenderError, Unit] =
      nodes.foldLeft[Either[RenderError, Unit]](Right(()))((result, node) =>
        result.flatMap(_ => loop(node))
      )

    loop(root)
end EvaluatedTree
