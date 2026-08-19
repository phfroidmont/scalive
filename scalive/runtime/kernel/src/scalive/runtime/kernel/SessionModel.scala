package scalive.runtime.kernel

import java.time.Duration
import java.time.Instant

import zio.Promise
import zio.Task
import zio.ZIO
import zio.http.URL
import zio.json.ast.Json

import scalive.BindingPayload
import scalive.FlashKind
import scalive.LiveAsyncEvent
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

final private[scalive] case class SessionConfig private (
  mailboxCapacity: Int,
  continuationCapacity: Int,
  navigationDeferredCapacity: Int,
  navigationTimeout: Duration,
  navigationRedirectLimit: Int)

private[scalive] object SessionConfig:
  def make(
    mailboxCapacity: Int,
    continuationCapacity: Int
  ): Either[SessionConfig.Error, SessionConfig] =
    make(
      mailboxCapacity,
      continuationCapacity,
      continuationCapacity,
      Duration.ofSeconds(5),
      20
    )

  def make(
    mailboxCapacity: Int,
    continuationCapacity: Int,
    navigationDeferredCapacity: Int,
    navigationTimeout: Duration,
    navigationRedirectLimit: Int
  ): Either[SessionConfig.Error, SessionConfig] =
    if mailboxCapacity <= 0 then Left(Error.InvalidMailboxCapacity(mailboxCapacity))
    else if continuationCapacity <= 0 then
      Left(Error.InvalidContinuationCapacity(continuationCapacity))
    else if navigationDeferredCapacity <= 0 then
      Left(Error.InvalidNavigationDeferredCapacity(navigationDeferredCapacity))
    else if navigationTimeout.isZero || navigationTimeout.isNegative then
      Left(Error.NonPositiveNavigationTimeout)
    else if navigationRedirectLimit <= 0 then
      Left(Error.InvalidNavigationRedirectLimit(navigationRedirectLimit))
    else
      Right(
        SessionConfig(
          mailboxCapacity,
          continuationCapacity,
          navigationDeferredCapacity,
          navigationTimeout,
          navigationRedirectLimit
        )
      )

  enum Error:
    case InvalidMailboxCapacity(capacity: Int)
    case InvalidContinuationCapacity(capacity: Int)
    case InvalidNavigationDeferredCapacity(capacity: Int)
    case NonPositiveNavigationTimeout
    case InvalidNavigationRedirectLimit(limit: Int)
end SessionConfig

enum NavigationKind:
  case PushPatch
  case ReplacePatch
  case PushNavigate
  case ReplaceNavigate
  case Redirect

  def isPatch: Boolean = this match
    case PushPatch | ReplacePatch => true
    case _                        => false

final private[scalive] case class NavigationRequest(
  destination: URL,
  kind: NavigationKind,
  flash: Map[FlashKind, String] = Map.empty)

final private[scalive] case class NavigationOutput(
  id: NavigationId,
  destination: URL,
  kind: NavigationKind,
  flash: Map[FlashKind, String])

final private[scalive] case class ClientEffect(name: String, payload: Json)

final private[scalive] case class SessionEffects(
  pageTitle: Option[String] = None,
  clientEvents: Vector[ClientEffect] = Vector.empty)

enum SessionCommand[+Msg]:
  case ClientEvent(
    epoch: Epoch,
    binding: BindingId,
    payload: BindingPayload,
    eventName: Option[String] = None,
    rawJson: Option[String] = None)             extends SessionCommand[Nothing]
  case Message[Msg](epoch: Epoch, message: Msg) extends SessionCommand[Msg]
  case AsyncCompletion[Msg](epoch: Epoch, event: LiveAsyncEvent[Msg]) extends SessionCommand[Msg]
  case ParamsPatch(epoch: Epoch, destination: URL) extends SessionCommand[Nothing]

  def expectedEpoch: Epoch = this match
    case ClientEvent(epoch, _, _, _, _) => epoch
    case Message(epoch, _)              => epoch
    case AsyncCompletion(epoch, _)      => epoch
    case ParamsPatch(epoch, _)          => epoch

final private[scalive] case class TurnDraft[+Msg, Model](
  model: Model,
  continuations: Vector[Msg] = Vector.empty,
  url: Option[URL] = None,
  navigation: Option[NavigationRequest] = None,
  effects: SessionEffects = SessionEffects())

/** Typed lifecycle operations interpreted by the protocol-neutral session owner.
  *
  * Connected mount contexts and complete lifecycle hooks adapt to this boundary in later
  * milestones.
  */
final private[scalive] case class SessionLogic[Msg, Model](
  bootstrap: Task[TurnDraft[Msg, Model]],
  handle: (Model, Msg) => Task[TurnDraft[Msg, Model]],
  handleParams: (Model, URL) => Task[TurnDraft[Msg, Model]] = (model: Model, url: URL) =>
    ZIO.succeed(TurnDraft(model, url = Some(url))),
  interceptClientEvent: (Model, SessionCommand.ClientEvent) => Task[Option[TurnDraft[Msg, Model]]] =
    (_: Model, _: SessionCommand.ClientEvent) => ZIO.succeed(Option.empty[TurnDraft[Msg, Model]]),
  handleEvent: Option[(Model, Msg) => Task[TurnDraft[Msg, Model]]] = None,
  handleInfo: Option[(Model, Msg) => Task[TurnDraft[Msg, Model]]] = None,
  handleAsync: Option[(Model, LiveAsyncEvent[Msg]) => Task[TurnDraft[Msg, Model]]] = None,
  prepare: (TurnDraft[Msg, Model], PreparedResourceRegistry) => Task[Unit] = (
    _: TurnDraft[Msg, Model],
    _: PreparedResourceRegistry
  ) => ZIO.unit,
  afterRender: TurnDraft[Msg, Model] => Task[TurnDraft[Msg, Model]] =
    (draft: TurnDraft[Msg, Model]) => ZIO.succeed(draft))

final private[scalive] case class Committed[Msg, Model](
  model: Model,
  url: URL,
  render: CommittedRender[Msg],
  resources: PreparedResources,
  revision: TurnRevision)

final private[scalive] case class SessionOutput(
  command: Option[CommandId],
  delta: RenderDelta,
  navigation: Option[NavigationOutput] = None,
  effects: SessionEffects = SessionEffects())

final private[scalive] case class DeferredSessionCommand[Msg, Model](
  command: CommandId,
  input: SessionCommand[Msg],
  response: Promise[SessionRejection, TurnResult])

final private[scalive] case class PendingNavigation[Msg, Model](
  id: NavigationId,
  source: URL,
  destination: URL,
  kind: NavigationKind,
  committed: Committed[Msg, Model],
  stagedModel: Model,
  flash: Map[FlashKind, String],
  deadline: Instant,
  redirectCount: Int,
  deferred: Vector[DeferredSessionCommand[Msg, Model]])

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
  reservation: OutboundReservation[SessionOutput])

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
  final case class NavigationTimedOut(id: NavigationId, destination: URL)
      extends SessionFailure(
        s"navigation ${id.value} to ${destination.encode} timed out"
      )
  final case class NavigationRedirectOverflow(limit: Int)
      extends SessionFailure(s"navigation redirect limit $limit exceeded")
  final case class NavigationDeferredOverflow(capacity: Int)
      extends SessionFailure(s"navigation deferred-command capacity $capacity exceeded")

enum SessionRejection:
  case MailboxSaturated(capacity: Int)
  case InvalidEpoch(expected: Epoch, actual: Epoch)
  case UnknownBinding(binding: BindingId)
  case BindingFailed(binding: BindingId, error: Throwable)
  case UnexpectedPatch
  case MismatchedPatch(expected: URL, actual: URL)
  case IdentityUnavailable(error: RuntimeIdentityError)
  case SessionFailed(failure: SessionFailure)
  case Terminal(state: String)

final private[scalive] case class TurnResult(
  command: CommandId,
  turn: TurnId,
  revision: TurnRevision,
  delta: RenderDelta)
