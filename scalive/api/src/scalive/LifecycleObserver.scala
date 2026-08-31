package scalive

import zio.UIO
import zio.ZIO

/** Receives structured operational events from LiveView lifecycles.
  *
  * Observers run synchronously and should not block. Scalive isolates observer failures from the
  * observed lifecycle, but a slow observer can still delay it. Structured fields deliberately
  * exclude models, messages, browser payloads, credentials, and session claims. Application-thrown
  * exception causes may contain application data and require the same handling as application logs.
  */
trait LifecycleObserver:
  def observe(event: LifecycleEvent): UIO[Unit]

  /** Sends each event to this observer and then `that` observer. */
  final def andThen(that: LifecycleObserver): LifecycleObserver =
    LifecycleObserver.fromFunction(event => safely(event) *> that.safely(event))

  final private[scalive] def safely(event: LifecycleEvent): UIO[Unit] =
    ZIO.suspendSucceed(observe(event)).catchAllCause(_ => ZIO.unit)

object LifecycleObserver:
  val none: LifecycleObserver = fromFunction(_ => ZIO.unit)

  def fromFunction(consume: LifecycleEvent => UIO[Unit]): LifecycleObserver =
    new LifecycleObserver:
      def observe(event: LifecycleEvent): UIO[Unit] = consume(event)

/** Coordinates one connected LiveView lifecycle without exposing application or protocol data. */
final case class ConnectedLifecycleContext(
  connectionId: Long,
  lifecycleId: Long,
  epoch: Long)

/** Identifies the phase in which a LiveView mount ran. */
enum LifecycleMount:
  case Disconnected(lifecycleId: Long)
  case Connected(lifecycle: ConnectedLifecycleContext)

/** Identifies whether a join targets a root or nested LiveView. */
enum LifecycleJoinTarget:
  case Root, Nested

/** Client-reported Phoenix join counters.
  *
  * The standard Phoenix LiveView client starts `mounts` at zero and increments it after every
  * successful join. It resets `attempts` after success. These values are untrusted diagnostics and
  * must never be used for authorization.
  */
final case class LifecycleJoinAttempt(
  mounts: Option[Long],
  attempts: Option[Long]):
  def isReconnect: Boolean = mounts.exists(_ > 0L)
  def isRetry: Boolean     = attempts.exists(_ > 0L)

/** Source of work processed by one connected lifecycle turn. */
enum LifecycleTurnKind:
  case Bootstrap
  case BrowserEvent
  case ComponentBrowserEvent
  case Message
  case AsyncCompletion
  case Subscription
  case ComponentMessage
  case ComponentUpdate
  case Upload
  case ParamsPatch
  case Internal

/** Stable stage classification for failed lifecycle work. */
enum LifecycleFailureStage:
  case Mount
  case ConnectedTurnGuard
  case Handler
  case ResourcePreparation
  case TopologyPreparation
  case Render
  case OutputReservation
  case AfterRender
  case Validation
  case Identity
  case Retirement
  case ComponentMount
  case ComponentUpdate
  case ComponentMessage
  case ComponentAsync
  case ComponentAfterRender
  case Commit
  case Navigation
  case Writer
  case Upload
  case Cleanup
  case Runtime

/** Low-cardinality failure classification suitable for metrics and tracing attributes. */
enum LifecycleFailure:
  case Stage(stage: LifecycleFailureStage)
  case JoinRejected
  case MailboxSaturated
  case IngressSaturated
  case SubscriptionDefect
  case Interrupted

/** Structured failure with an optional cause for logging and tracing.
  *
  * Use `failure` rather than exception messages as a metric label.
  */
final case class LifecycleError(
  failure: LifecycleFailure,
  cause: Option[Throwable] = None)

/** Bounded runtime queue observed by Scalive. */
enum LifecycleQueue:
  case ConnectionIngress, ConnectionPendingCommands, KernelMailbox, Writer

/** Whether a queue depth was sampled or an offer was rejected at capacity. */
enum LifecycleQueueStatus:
  case Sampled, Saturated

/** Reason a connected lifecycle stopped. */
enum LifecycleTerminationReason:
  case Closed
  case Redirected
  case Disconnected(reason: Option[String])
  case Failed(failure: LifecycleFailure)

/** Structured lifecycle events emitted by Scalive.
  *
  * Durations use monotonic nanoseconds and exclude physical websocket transmission time.
  * Integrations should include a fallback when matching cases because new event cases may be added
  * as lifecycle coverage grows.
  */
enum LifecycleEvent:
  case DisconnectedRenderSucceeded(lifecycleId: Long, durationNanos: Long)
  case DisconnectedRenderFailed(
    lifecycleId: Long,
    durationNanos: Long,
    error: LifecycleError)
  case JoinSucceeded(
    lifecycle: ConnectedLifecycleContext,
    target: LifecycleJoinTarget,
    attempt: LifecycleJoinAttempt,
    durationNanos: Long)
  case JoinRejected(
    connectionId: Long,
    lifecycleId: Option[Long],
    target: LifecycleJoinTarget,
    attempt: LifecycleJoinAttempt,
    durationNanos: Long,
    error: LifecycleError)
  case MountSucceeded(mount: LifecycleMount, durationNanos: Long)
  case MountFailed(mount: LifecycleMount, durationNanos: Long, error: LifecycleError)
  case TurnSucceeded(
    lifecycle: ConnectedLifecycleContext,
    turnId: Long,
    commandId: Option[Long],
    kind: LifecycleTurnKind,
    durationNanos: Long)
  case TurnFailed(
    lifecycle: ConnectedLifecycleContext,
    turnId: Long,
    commandId: Option[Long],
    kind: LifecycleTurnKind,
    durationNanos: Long,
    error: LifecycleError)
  case HandlerFailed(
    lifecycle: ConnectedLifecycleContext,
    commandId: Option[Long],
    kind: LifecycleTurnKind,
    durationNanos: Long,
    error: LifecycleError)
  case SubscriptionFailed(
    lifecycle: ConnectedLifecycleContext,
    resourceId: Long,
    delivery: SubscriptionDelivery,
    error: LifecycleError)
  case QueuePressure(
    lifecycle: ConnectedLifecycleContext,
    queue: LifecycleQueue,
    depth: Int,
    capacity: Int,
    status: LifecycleQueueStatus)
  case LifecycleTerminated(
    lifecycle: ConnectedLifecycleContext,
    reason: LifecycleTerminationReason)

  def name: String = this match
    case _: DisconnectedRenderSucceeded => "disconnected_render_succeeded"
    case _: DisconnectedRenderFailed    => "disconnected_render_failed"
    case _: JoinSucceeded               => "join_succeeded"
    case _: JoinRejected                => "join_rejected"
    case _: MountSucceeded              => "mount_succeeded"
    case _: MountFailed                 => "mount_failed"
    case _: TurnSucceeded               => "turn_succeeded"
    case _: TurnFailed                  => "turn_failed"
    case _: HandlerFailed               => "handler_failed"
    case _: SubscriptionFailed          => "subscription_failed"
    case _: QueuePressure               => "queue_pressure"
    case _: LifecycleTerminated         => "lifecycle_terminated"
end LifecycleEvent
