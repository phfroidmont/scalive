package scalive.render

import java.util.concurrent.atomic.AtomicReference

import zio.Exit
import zio.Scope
import zio.UIO

/** The semantic value of an evaluated HTML attribute before serialization. */
enum AttributeValue:
  case Text(value: String)
  case Presence

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
  private[scalive] val scope: CandidateScope,
  private[render] val programIdentity: RenderProgramId):
  /** Closes this render after its replacement has become active. */
  def close: UIO[Unit] = scope.closeCommitted

/** Isolated render state awaiting lifecycle validation and commit. */
final case class RenderCandidate[+Msg] private[render] (
  tree: EvaluatedTree,
  bindings: BindingTable[Msg],
  signalEvaluation: SignalEvaluation,
  private[scalive] val stagedScope: CandidateScope,
  private[render] val programIdentity: RenderProgramId,
  private val commitTail: CandidateCommit):
  /** Transfers scope ownership to a committed render. A candidate can be committed only once. */
  def commit: CommittedRender[Msg] =
    if stagedScope.retain() then
      commitTail.run()
      CommittedRender(tree, bindings, signalEvaluation, stagedScope, programIdentity)
    else throw IllegalStateException("render candidate is no longer staged")

  /** Closes candidate-owned resources without changing previously committed state. */
  def discard: UIO[Unit] = stagedScope.discard

/** Infallible, allocation-free assignments performed only after candidate ownership is claimed. */
final private[render] class CandidateCommit(private val actions: Array[() => Unit]):
  def run(): Unit =
    var index = 0
    while index < actions.length do
      actions(index)()
      index += 1
