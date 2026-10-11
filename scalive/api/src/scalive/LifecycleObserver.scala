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
  /** Handles one event.
    *
    * Scalive waits for the returned effect and ignores its failures, so keep it fast and hand slow
    * work such as network export to a bounded queue.
    */
  def observe(event: LifecycleEvent): UIO[Unit]

  /** Sends each event to this observer and then `that` observer. */
  final def andThen(that: LifecycleObserver): LifecycleObserver =
    LifecycleObserver.fromFunction(event => safely(event) *> that.safely(event))

  final private[scalive] def safely(event: LifecycleEvent): UIO[Unit] =
    ZIO.suspendSucceed(observe(event)).catchAllCause(_ => ZIO.unit)

/** Constructors for [[LifecycleObserver]] values. */
object LifecycleObserver:
  /** An observer that ignores every event. */
  val none: LifecycleObserver = fromFunction(_ => ZIO.unit)

  /** Creates an observer that passes each event to `consume`. */
  def fromFunction(consume: LifecycleEvent => UIO[Unit]): LifecycleObserver =
    new LifecycleObserver:
      def observe(event: LifecycleEvent): UIO[Unit] = consume(event)

/** Coordinates one connected LiveView lifecycle without exposing application or protocol data.
  *
  * @param connectionId
  *   The ID of the WebSocket connection. Root and nested LiveViews joined over the same WebSocket
  *   share it, and a reconnect gets a new one.
  * @param lifecycleId
  *   The ID of the LiveView lifecycle, unique on its connection. A LiveView's first connected
  *   lifecycle on a connection reuses the ID issued by its disconnected render, so the ID stays the
  *   same across reconnects; combine it with `connectionId` to tell connections apart. A rejoin
  *   after a crash and a LiveView reached by live navigation get a new ID.
  */
final case class ConnectedLifecycleContext(
  connectionId: Long,
  lifecycleId: Long)

/** Identifies the phase in which a LiveView mount ran. */
enum LifecycleMount:
  /** A mount during the disconnected HTTP render.
    *
    * @param lifecycleId
    *   The ID of the lifecycle. For the root LiveView of the page, the matching disconnected render
    *   event reports the same ID; each nested LiveView in the render has its own.
    */
  case Disconnected(lifecycleId: Long)

  /** A mount at the start of a connected lifecycle.
    *
    * @param lifecycle
    *   The connected lifecycle that mounted.
    */
  case Connected(lifecycle: ConnectedLifecycleContext)

/** Identifies whether a join targets a root or nested LiveView. */
enum LifecycleJoinTarget:
  /** A join for the root LiveView of a page. */
  case Root

  /** A join for a LiveView nested inside another LiveView. */
  case Nested

/** Client-reported Phoenix join counters.
  *
  * The standard Phoenix LiveView client starts `mounts` at zero and increments it after every
  * successful join. It resets `attempts` after success. These values are untrusted diagnostics and
  * must never be used for authorization.
  *
  * @param mounts
  *   The client's `_mounts` counter, or `None` when it is missing, malformed, or negative.
  * @param attempts
  *   The client's `_mount_attempts` counter, or `None` when it is missing, malformed, or negative.
  */
final case class LifecycleJoinAttempt(
  mounts: Option[Long],
  attempts: Option[Long]):
  /** Returns `true` when `mounts` is positive, meaning the client reports an earlier join. */
  def isReconnect: Boolean = mounts.exists(_ > 0L)

  /** Returns `true` when `attempts` is positive, meaning the client reports a retried join. */
  def isRetry: Boolean = attempts.exists(_ > 0L)

/** Source of work processed by one connected lifecycle turn. */
enum LifecycleTurnKind:
  /** The first turn of a connected lifecycle, which mounts the LiveView and renders it. */
  case Bootstrap

  /** A browser event handled by the LiveView. */
  case BrowserEvent

  /** A browser event targeted at a LiveComponent. */
  case ComponentBrowserEvent

  /** A message submitted directly to the LiveView, outside a browser event, async result, or
    * subscription.
    */
  case Message

  /** The result of async work, such as a task started with [[Async.start]] by the LiveView or one
    * of its LiveComponents.
    */
  case AsyncCompletion

  /** A value from a managed subscription, or the end of its stream. */
  case Subscription

  /** A message or async result submitted directly to one LiveComponent. */
  case ComponentMessage

  /** An upload control step, such as a preflight or progress update. */
  case Upload

  /** A live patch to a new URL within the same LiveView. */
  case ParamsPatch

  /** A turn that Scalive starts itself, without a command. */
  case Internal
end LifecycleTurnKind

/** Stable stage classification for failed lifecycle work. */
enum LifecycleFailureStage:
  /** The `mount` of the LiveView failed. */
  case Mount

  /** A connected-turn guard died with a defect.
    *
    * A guard that fails with a [[LiveConnectedTurnFailure]] is a controlled outcome, not a failure.
    */
  case ConnectedTurnGuard

  /** A LiveView handler or hook failed, such as `handleMessage` or `handleParams`. */
  case Handler

  /** Preparing resources that a turn starts or cancels, such as async tasks, failed. */
  case ResourcePreparation

  /** Preparing the nested LiveViews that a render adds or removes failed. */
  case TopologyPreparation

  /** Rendering the view failed. */
  case Render

  /** Reserving room for the output of a turn failed. */
  case OutputReservation

  /** An after-render hook of the LiveView failed. */
  case AfterRender

  /** Scalive rejected the result of a turn as invalid, for example streams that do not match the
    * render or too much queued follow-up work.
    */
  case Validation

  /** Scalive could not allocate a runtime ID. */
  case Identity

  /** Releasing LiveComponents or render state that a turn removed failed. */
  case Retirement

  /** The `mount` of a LiveComponent failed. */
  case ComponentMount

  /** The `update` of a LiveComponent failed. */
  case ComponentUpdate

  /** A LiveComponent failed while handling a message or browser event. */
  case ComponentMessage

  /** A LiveComponent failed while handling an async result. */
  case ComponentAsync

  /** An after-render hook of a LiveComponent failed. */
  case ComponentAfterRender

  /** Committing a rendered turn failed. */
  case Commit

  /** A navigation timed out, redirected too many times, or had too much work queued behind it. */
  case Navigation

  /** Writing the output of the lifecycle to the WebSocket failed. */
  case Writer

  /** Processing an upload failed. */
  case Upload

  /** An unexpected runtime or protocol defect that no other stage covers. */
  case Runtime
end LifecycleFailureStage

/** Low-cardinality failure classification suitable for metrics and tracing attributes. */
enum LifecycleFailure:
  /** Work failed at a known stage.
    *
    * @param stage
    *   The stage that failed.
    */
  case Stage(stage: LifecycleFailureStage)

  /** Scalive rejected a join, for example for an invalid or stale session, or the joined LiveView
    * failed to start.
    */
  case JoinRejected

  /** The mailbox of the lifecycle was full and rejected a command. */
  case MailboxSaturated

  /** The connection rejected a command for the lifecycle because its ingress queue or pending
    * command limit was full. Scalive then stops the lifecycle.
    */
  case IngressSaturated

  /** A managed subscription stream died with a defect. */
  case SubscriptionDefect

  /** The work was interrupted before it finished, for example because the lifecycle closed. */
  case Interrupted

/** Structured failure with an optional cause for logging and tracing.
  *
  * Use `failure` rather than exception messages as a metric label.
  *
  * @param failure
  *   The low-cardinality classification of the failure.
  * @param cause
  *   The underlying error, when there is one. It may contain application data.
  */
final case class LifecycleError(
  failure: LifecycleFailure,
  cause: Option[Throwable] = None)

/** Bounded runtime queue observed by Scalive. */
enum LifecycleQueue:
  /** The connection queue of incoming commands for one lifecycle, such as browser events, before
    * they reach its mailbox. Scalive samples its depth after each accepted command.
    */
  case ConnectionIngress

  /** The limit on commands that the connection accepted for one lifecycle and has not completed.
    * Scalive reports it only when the limit rejects a command.
    */
  case ConnectionPendingCommands

  /** The mailbox of commands waiting for a turn of the lifecycle. Scalive samples its depth each
    * time the lifecycle takes a command.
    */
  case KernelMailbox

  /** The queue of lifecycle output on its way to the WebSocket. Scalive reports it only when it is
    * full.
    */
  case Writer

/** Whether a queue depth was sampled or an offer was rejected at capacity. */
enum LifecycleQueueStatus:
  /** An ordinary depth observation. */
  case Sampled

  /** An offer was rejected because the queue was full. */
  case Saturated

/** Reason a connected lifecycle stopped. */
enum LifecycleTerminationReason:
  /** The lifecycle closed without a failure, for example because the browser left. */
  case Closed

  /** The lifecycle ended with a navigation away from the LiveView, such as a redirect. */
  case Redirected

  /** A connected-turn guard closed the WebSocket with [[LiveConnectedTurnFailure.Disconnect]].
    *
    * @param reason
    *   The reason the guard gave, if any. It may contain application data, so keep it out of metric
    *   labels.
    */
  case Disconnected(reason: Option[String])

  /** The lifecycle stopped because of a failure.
    *
    * @param failure
    *   The classification of the failure.
    */
  case Failed(failure: LifecycleFailure)

/** Structured lifecycle events emitted by Scalive.
  *
  * Durations use monotonic nanoseconds and exclude physical websocket transmission time.
  * Integrations should include a fallback when matching cases because new event cases may be added
  * as lifecycle coverage grows.
  */
enum LifecycleEvent:
  /** A disconnected HTTP render of a LiveView finished.
    *
    * The response is the rendered page, or a redirect when the LiveView navigated during mount or
    * parameter handling.
    *
    * @param lifecycleId
    *   The ID this render issued for the lifecycle. The connected root lifecycle of the page reuses
    *   it when it joins.
    * @param durationNanos
    *   The time from the start of `mount` until the response was ready, in nanoseconds.
    */
  case DisconnectedRenderSucceeded(lifecycleId: Long, durationNanos: Long)

  /** A disconnected HTTP render of a LiveView failed.
    *
    * @param lifecycleId
    *   The ID this render issued for the lifecycle.
    * @param durationNanos
    *   The time from the start of `mount` until the failure, in nanoseconds.
    * @param error
    *   The failure. Its stage is [[LifecycleFailureStage.Mount]],
    *   [[LifecycleFailureStage.Handler]], or [[LifecycleFailureStage.Render]], unless the render
    *   was interrupted.
    */
  case DisconnectedRenderFailed(
    lifecycleId: Long,
    durationNanos: Long,
    error: LifecycleError)

  /** Scalive accepted a join and started the connected lifecycle of a LiveView.
    *
    * @param lifecycle
    *   The connected lifecycle that the join started.
    * @param target
    *   Whether the join targeted a root or nested LiveView.
    * @param attempt
    *   The join counters reported by the client.
    * @param durationNanos
    *   The time from receiving the join until the lifecycle started, in nanoseconds.
    */
  case JoinSucceeded(
    lifecycle: ConnectedLifecycleContext,
    target: LifecycleJoinTarget,
    attempt: LifecycleJoinAttempt,
    durationNanos: Long)

  /** Scalive rejected a join, or the joined LiveView failed to start.
    *
    * @param connectionId
    *   The ID of the WebSocket connection that sent the join.
    * @param lifecycleId
    *   The ID of the lifecycle the join started, as in its mount events, or `None` when Scalive
    *   rejected the join before starting one.
    * @param target
    *   Whether the join targeted a root or nested LiveView.
    * @param attempt
    *   The join counters reported by the client.
    * @param durationNanos
    *   The time from receiving the join until the rejection, in nanoseconds.
    * @param error
    *   The failure, classified as [[LifecycleFailure.JoinRejected]] or
    *   [[LifecycleFailure.Interrupted]]. It carries a cause when an admitted lifecycle failed to
    *   start.
    */
  case JoinRejected(
    connectionId: Long,
    lifecycleId: Option[Long],
    target: LifecycleJoinTarget,
    attempt: LifecycleJoinAttempt,
    durationNanos: Long,
    error: LifecycleError)

  /** The `mount` of a LiveView succeeded, during a disconnected render or a connected join.
    *
    * @param mount
    *   The phase the mount ran in, with the ID of its lifecycle.
    * @param durationNanos
    *   The time spent in `mount`, in nanoseconds.
    */
  case MountSucceeded(mount: LifecycleMount, durationNanos: Long)

  /** The `mount` of a LiveView failed, during a disconnected render or a connected join.
    *
    * The enclosing disconnected render or bootstrap turn also reports a failure.
    *
    * @param mount
    *   The phase the mount ran in, with the ID of its lifecycle.
    * @param durationNanos
    *   The time spent in `mount`, in nanoseconds.
    * @param error
    *   The failure, classified as the [[LifecycleFailureStage.Mount]] stage or as
    *   [[LifecycleFailure.Interrupted]].
    */
  case MountFailed(mount: LifecycleMount, durationNanos: Long, error: LifecycleError)

  /** A connected turn finished without a failure.
    *
    * A turn processes one unit of work for the lifecycle, such as a browser event or a message. A
    * turn that a connected-turn guard halts also counts as succeeded.
    *
    * @param lifecycle
    *   The lifecycle that ran the turn.
    * @param turnId
    *   The ID of the turn, unique within the server process.
    * @param commandId
    *   The ID of the command that started the turn, or `None` for the bootstrap turn and turns that
    *   Scalive starts itself.
    * @param kind
    *   The source of the work the turn processed.
    * @param durationNanos
    *   The duration of the turn, in nanoseconds.
    */
  case TurnSucceeded(
    lifecycle: ConnectedLifecycleContext,
    turnId: Long,
    commandId: Option[Long],
    kind: LifecycleTurnKind,
    durationNanos: Long)

  /** A connected turn failed.
    *
    * @param lifecycle
    *   The lifecycle that ran the turn.
    * @param turnId
    *   The ID of the turn, unique within the server process.
    * @param commandId
    *   The ID of the command that started the turn, or `None` for the bootstrap turn and turns that
    *   Scalive starts itself.
    * @param kind
    *   The source of the work the turn processed.
    * @param durationNanos
    *   The time from the start of the turn until the failure, in nanoseconds.
    * @param error
    *   The failure and, for a stage failure, the stage where it happened.
    */
  case TurnFailed(
    lifecycle: ConnectedLifecycleContext,
    turnId: Long,
    commandId: Option[Long],
    kind: LifecycleTurnKind,
    durationNanos: Long,
    error: LifecycleError)

  /** A LiveView or LiveComponent handler or hook failed during a connected turn.
    *
    * This covers every application callback a turn runs, including the bootstrap turn, except the
    * LiveView's own `mount`, which [[LifecycleEvent.MountFailed]] reports. Scalive also reports the
    * enclosing turn as [[LifecycleEvent.TurnFailed]], with the same failure, and stops the
    * lifecycle.
    *
    * @param lifecycle
    *   The lifecycle whose handler failed.
    * @param commandId
    *   The ID of the command the handler was processing, or `None` when Scalive started the turn
    *   itself.
    * @param kind
    *   The source of the work the handler was processing.
    * @param durationNanos
    *   The time from the start of the turn until the handler failed, in nanoseconds.
    * @param error
    *   The failure, classified by the stage of the callback, such as
    *   [[LifecycleFailureStage.Handler]] or [[LifecycleFailureStage.ComponentMessage]], with the
    *   callback's error as its cause.
    */
  case HandlerFailed(
    lifecycle: ConnectedLifecycleContext,
    commandId: Option[Long],
    kind: LifecycleTurnKind,
    durationNanos: Long,
    error: LifecycleError)

  /** A managed subscription stream died with a defect.
    *
    * Scalive stops that subscription and keeps the lifecycle running.
    *
    * @param lifecycle
    *   The lifecycle that owns the subscription.
    * @param resourceId
    *   The runtime ID of the subscription, unrelated to its [[SubscriptionKey]].
    * @param delivery
    *   The delivery mode of the subscription.
    * @param error
    *   The failure, classified as [[LifecycleFailure.SubscriptionDefect]], with the defect as its
    *   cause.
    */
  case SubscriptionFailed(
    lifecycle: ConnectedLifecycleContext,
    resourceId: Long,
    delivery: SubscriptionDelivery,
    error: LifecycleError)

  /** A depth sample from a bounded runtime queue, or an offer that a full queue rejected.
    *
    * @param lifecycle
    *   The lifecycle that owns the queue.
    * @param queue
    *   The queue that Scalive observed.
    * @param depth
    *   The number of items in the queue when Scalive observed it.
    * @param capacity
    *   The maximum number of items the queue accepts.
    * @param status
    *   Whether this is an ordinary sample or a rejected offer.
    */
  case QueuePressure(
    lifecycle: ConnectedLifecycleContext,
    queue: LifecycleQueue,
    depth: Int,
    capacity: Int,
    status: LifecycleQueueStatus)

  /** A connected lifecycle stopped.
    *
    * @param lifecycle
    *   The lifecycle that stopped.
    * @param reason
    *   Why the lifecycle stopped.
    */
  case LifecycleTerminated(
    lifecycle: ConnectedLifecycleContext,
    reason: LifecycleTerminationReason)

  /** Returns the stable snake_case name of the event, such as `turn_failed`.
    *
    * Log it instead of `toString`, which can include application data from a failure cause.
    */
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
