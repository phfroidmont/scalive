package scalive.runtime.kernel

import zio.*

import scalive.runtime.contracts.*

final private[scalive] case class RuntimeCorrelation(
  connection: ConnectionId,
  lifecycle: LifecycleId,
  epoch: Epoch,
  command: Option[CommandId] = None,
  turn: Option[TurnId] = None,
  revision: Option[TurnRevision] = None,
  navigation: Option[NavigationId] = None,
  resource: Option[ResourceId] = None)

private[scalive] enum RuntimeCommandKind:
  case ClientEvent
  case ComponentClientEvent
  case Message
  case AsyncCompletion
  case ManagedAsync
  case ManagedSubscription
  case ManagedSubscriptionEnded
  case ComponentMessage
  case ComponentUpdate
  case ComponentAsyncCompletion
  case Upload
  case ParamsPatch
  case Internal

private[scalive] enum RuntimeInitiator:
  case Browser, Application, ManagedResource, Runtime

private[scalive] enum RuntimeFailure:
  case Stage(stage: SessionStage)
  case CommitDefect
  case NavigationTimeout
  case NavigationRedirectOverflow
  case NavigationDeferredOverflow
  case MailboxSaturated
  case IngressSaturated
  case Writer
  case UploadEntry
  case Protocol
  case Cleanup
  case RuntimeDefect

private[scalive] enum RuntimeTerminal:
  case Crashed, Closed, Redirected

private[scalive] enum RuntimeEvent:
  case CommandAccepted(
    correlation: RuntimeCorrelation,
    kind: RuntimeCommandKind,
    initiator: RuntimeInitiator,
    queueDepth: Int)
  case TurnStarted(
    correlation: RuntimeCorrelation,
    kind: RuntimeCommandKind,
    initiator: RuntimeInitiator)
  case HandlerCompleted(correlation: RuntimeCorrelation, durationNanos: Long)
  case CandidateRenderStarted(correlation: RuntimeCorrelation)
  case CandidateValidated(correlation: RuntimeCorrelation)
  case DiffCompleted(correlation: RuntimeCorrelation)
  case StateCommitted(correlation: RuntimeCorrelation)
  case OutputPublished(correlation: RuntimeCorrelation)
  case ResourceActivated(correlation: RuntimeCorrelation)
  case ResourceRetired(correlation: RuntimeCorrelation)
  case TurnFailed(correlation: RuntimeCorrelation, failure: RuntimeFailure)
  case SessionTerminated(correlation: RuntimeCorrelation, terminal: RuntimeTerminal)

  def context: RuntimeCorrelation = this match
    case CommandAccepted(value, _, _, _) => value
    case TurnStarted(value, _, _)        => value
    case HandlerCompleted(value, _)      => value
    case CandidateRenderStarted(value)   => value
    case CandidateValidated(value)       => value
    case DiffCompleted(value)            => value
    case StateCommitted(value)           => value
    case OutputPublished(value)          => value
    case ResourceActivated(value)        => value
    case ResourceRetired(value)          => value
    case TurnFailed(value, _)            => value
    case SessionTerminated(value, _)     => value

  def name: String = this match
    case _: CommandAccepted        => "command_accepted"
    case _: TurnStarted            => "turn_started"
    case _: HandlerCompleted       => "handler_completed"
    case _: CandidateRenderStarted => "candidate_render_started"
    case _: CandidateValidated     => "candidate_validated"
    case _: DiffCompleted          => "diff_completed"
    case _: StateCommitted         => "state_committed"
    case _: OutputPublished        => "output_published"
    case _: ResourceActivated      => "resource_activated"
    case _: ResourceRetired        => "resource_retired"
    case _: TurnFailed             => "turn_failed"
    case _: SessionTerminated      => "session_terminated"
end RuntimeEvent

private[scalive] trait RuntimeEventSink:
  def emit(event: RuntimeEvent): UIO[Unit]

final private[scalive] class RuntimeObserver private (sink: RuntimeEventSink):
  def emit(event: RuntimeEvent): UIO[Unit] = sink.emit(event).catchAllCause(_ => ZIO.unit)

private[scalive] object RuntimeObserver:
  val noop: RuntimeObserver = fromFunction(_ => ZIO.unit)

  val logging: RuntimeObserver = fromFunction { event =>
    val correlation = event.context
    ZIO.logDebug(
      s"runtime_event=${event.name} connection=${correlation.connection.value} " +
        s"lifecycle=${correlation.lifecycle.value} epoch=${correlation.epoch.value}"
    )
  }

  def fromFunction(emitEvent: RuntimeEvent => UIO[Unit]): RuntimeObserver =
    new RuntimeObserver(
      new RuntimeEventSink:
        def emit(event: RuntimeEvent): UIO[Unit] = emitEvent(event)
    )
