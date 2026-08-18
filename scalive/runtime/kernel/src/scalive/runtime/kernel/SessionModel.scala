package scalive.runtime.kernel

import zio.Task
import zio.ZIO

import scalive.BindingPayload
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

final private[scalive] case class SessionConfig private (
  mailboxCapacity: Int,
  continuationCapacity: Int)

private[scalive] object SessionConfig:
  def make(
    mailboxCapacity: Int,
    continuationCapacity: Int
  ): Either[SessionConfig.Error, SessionConfig] =
    if mailboxCapacity <= 0 then Left(Error.InvalidMailboxCapacity(mailboxCapacity))
    else if continuationCapacity <= 0 then
      Left(Error.InvalidContinuationCapacity(continuationCapacity))
    else Right(SessionConfig(mailboxCapacity, continuationCapacity))

  enum Error:
    case InvalidMailboxCapacity(capacity: Int)
    case InvalidContinuationCapacity(capacity: Int)

enum SessionCommand[+Msg]:
  case ClientEvent(
    epoch: Epoch,
    binding: BindingId,
    payload: BindingPayload)                    extends SessionCommand[Nothing]
  case Message[Msg](epoch: Epoch, message: Msg) extends SessionCommand[Msg]

  def expectedEpoch: Epoch = this match
    case ClientEvent(epoch, _, _) => epoch
    case Message(epoch, _)        => epoch

final private[scalive] case class TurnDraft[+Msg, Model](
  model: Model,
  continuations: Vector[Msg] = Vector.empty)

/** Typed lifecycle operations interpreted by the protocol-neutral session owner.
  *
  * Connected mount contexts and complete lifecycle hooks adapt to this boundary in later
  * milestones.
  */
final private[scalive] case class SessionLogic[Msg, Model](
  bootstrap: Task[TurnDraft[Msg, Model]],
  handle: (Model, Msg) => Task[TurnDraft[Msg, Model]],
  prepare: (TurnDraft[Msg, Model], PreparedResourceRegistry) => Task[Unit] = (
    _: TurnDraft[Msg, Model],
    _: PreparedResourceRegistry
  ) => ZIO.unit,
  afterRender: TurnDraft[Msg, Model] => Task[Unit] = (_: TurnDraft[Msg, Model]) => ZIO.unit)

final private[scalive] case class Committed[Msg, Model](
  model: Model,
  render: CommittedRender[Msg],
  resources: PreparedResources,
  revision: TurnRevision)

final private[scalive] case class PendingNavigation[Msg, Model](
  committed: Committed[Msg, Model],
  deferred: Vector[SessionCommand[Msg]])

enum SessionState[Msg, Model]:
  case Bootstrapping(epoch: Epoch)
  case Active(epoch: Epoch, committed: Committed[Msg, Model])
  case Navigating(epoch: Epoch, pending: PendingNavigation[Msg, Model])
  case Closing(epoch: Epoch, committed: Option[Committed[Msg, Model]])
  case Crashed(epoch: Epoch, failure: SessionFailure)
  case Closed(epoch: Epoch)

final private[scalive] case class TurnCandidate[Msg, Model](
  id: TurnId,
  revision: TurnRevision,
  draft: TurnDraft[Msg, Model],
  render: RenderCandidate[Msg],
  resources: PreparedResources,
  delta: RenderDelta,
  reservation: OutboundReservation[RenderDelta])

enum SessionStage:
  case BootstrapHandler
  case Handler
  case ResourcePreparation
  case Render
  case OutputReservation
  case AfterRender
  case Validation
  case Identity
  case Retirement

sealed abstract class SessionFailure(message: String) extends Exception(message)

object SessionFailure:
  final case class StageFailed(stage: SessionStage, details: String)
      extends SessionFailure(s"session stage $stage failed: $details")

  final case class CommitDefect(details: String)
      extends SessionFailure(s"session commit tail defected: $details")

  final case class Interrupted() extends SessionFailure("session initialization was interrupted")

enum SessionRejection:
  case MailboxSaturated(capacity: Int)
  case InvalidEpoch(expected: Epoch, actual: Epoch)
  case UnknownBinding(binding: BindingId)
  case BindingFailed(binding: BindingId, error: Throwable)
  case IdentityUnavailable(error: RuntimeIdentityError)
  case SessionFailed(failure: SessionFailure)
  case Terminal(state: String)

final private[scalive] case class TurnResult(
  command: CommandId,
  turn: TurnId,
  revision: TurnRevision,
  delta: RenderDelta)
