package scalive

import scala.annotation.targetName

import zio.*

/** Persistent UI state for an asynchronous value.
  *
  * Unlike [[LiveAsyncResult]], which describes one task completion, `AsyncValue` retains enough
  * state to render loading, failure, and cancellation while optionally preserving the last value.
  * Applications explicitly mark a value as loading when starting work and apply the corresponding
  * result from their typed completion message.
  *
  * @tparam A
  *   the successfully loaded value type
  */
enum AsyncValue[+A]:
  /** No task has produced a value and no task is currently represented as loading. */
  case Empty

  /** A task is in progress, optionally retaining the last available value.
    *
    * @param previous
    *   the value available before the task started
    */
  case Loading(previous: Option[A])

  /** A task completed successfully.
    *
    * @param value
    *   the current successful value
    */
  case Ok(value: A)

  /** A task failed, optionally retaining the last available value.
    *
    * @param previous
    *   the value available before the failed task started
    * @param cause
    *   the task failure
    */
  case Failed(previous: Option[A], cause: Throwable)

  /** A task was cancelled explicitly, optionally retaining the last available value.
    *
    * @param previous
    *   the value available before the cancelled task started
    * @param reason
    *   the application-supplied cancellation reason, if any
    */
  case Cancelled(previous: Option[A], reason: Option[String])
end AsyncValue

/** Constructors, transitions, and query syntax for [[AsyncValue]]. */
object AsyncValue:
  /** Returns an empty async value with the requested value type. */
  def empty[A]: AsyncValue[A] = AsyncValue.Empty

  /** Returns a loading async value with no previous value. */
  def loading[A]: AsyncValue[A] = AsyncValue.Loading(None)

  /** Returns a successfully loaded async value containing `value`. */
  def ok[A](value: A): AsyncValue[A] = AsyncValue.Ok(value)

  /** Returns the current or retained value, if one exists.
    *
    * Successful values and the `previous` value in loading, failed, and cancelled states are
    * returned. `Empty` returns `None`.
    */
  def currentValue[A](value: AsyncValue[A]): Option[A] =
    value match
      case AsyncValue.Ok(current)            => Some(current)
      case AsyncValue.Loading(previous)      => previous
      case AsyncValue.Failed(previous, _)    => previous
      case AsyncValue.Cancelled(previous, _) => previous
      case AsyncValue.Empty                  => None

  /** Returns whether `value` is specifically in the loading state. */
  def currentlyLoading[A](value: AsyncValue[A]): Boolean =
    value match
      case AsyncValue.Loading(_) => true
      case _                     => false

  /** Returns whether `value` is specifically in the successful state. */
  def currentlyOk[A](value: AsyncValue[A]): Boolean =
    value match
      case AsyncValue.Ok(_) => true
      case _                => false

  /** Transitions `current` to loading.
    *
    * @param current
    *   the state whose current or retained value may be preserved
    * @param reset
    *   whether to discard the current value instead of storing it as `previous`
    */
  def markLoading[A](current: AsyncValue[A], reset: Boolean = false): AsyncValue[A] =
    AsyncValue.Loading(if reset then None else currentValue(current))

  /** Applies one task completion to `current`.
    *
    * Success replaces the current value. Failure and cancellation preserve the current or retained
    * value as `previous`.
    *
    * @param current
    *   the state before the task completed
    * @param result
    *   the task completion to apply
    */
  def applyResult[A](current: AsyncValue[A], result: LiveAsyncResult[A]): AsyncValue[A] =
    val previous = currentValue(current)
    result match
      case LiveAsyncResult.Succeeded(value)  => AsyncValue.Ok(value)
      case LiveAsyncResult.Failed(cause)     => AsyncValue.Failed(previous, cause)
      case LiveAsyncResult.Cancelled(reason) => AsyncValue.Cancelled(previous, reason)

  extension [A](value: AsyncValue[A])
    /** Returns the current or retained value, if one exists. */
    @targetName("asyncValueOption")
    def valueOption: Option[A] = AsyncValue.currentValue(value)

    /** Returns whether this value is specifically in the loading state. */
    @targetName("asyncIsLoading")
    def isLoading: Boolean = AsyncValue.currentlyLoading(value)

    /** Returns whether this value is specifically in the successful state. */
    @targetName("asyncIsOk")
    def isOk: Boolean = AsyncValue.currentlyOk(value)

    /** Transitions this value to loading, preserving its current value unless `reset` is true. */
    @targetName("asyncLoading")
    def loading(reset: Boolean = false): AsyncValue[A] = AsyncValue.markLoading(value, reset)

    /** Applies `result`, preserving the current value on failure or cancellation. */
    @targetName("asyncUpdated")
    def updated(result: LiveAsyncResult[A]): AsyncValue[A] = AsyncValue.applyResult(value, result)
end AsyncValue

/** Describes an async completion delivered to a lifecycle async hook.
  *
  * Async hooks run before the mapped message is passed to the LiveView or LiveComponent message
  * handler. On success, `result` contains that mapped `Msg`, not the original task result. The
  * task's result type is therefore erased from `name` to `Any`; an [[AsyncKey]] can still be
  * compared with this field.
  *
  * @param name
  *   the key of the task that completed
  * @param result
  *   the mapped message on success, or the task failure or cancellation details
  * @tparam Msg
  *   the message type produced by the task's mapping function
  */
final case class LiveAsyncEvent[+Msg](
  name: AsyncKey[Any],
  result: LiveAsyncResult[Msg])

/** The outcome supplied to an async task's message-mapping function.
  *
  * Cancelling an active task with `ctx.async.cancel` produces `Cancelled` and invokes the mapper.
  * Cancelling an absent key is a no-op. Starting another task with the same key or ending the
  * owning lifecycle interrupts stale work without delivering its completion. If the mapping
  * function throws, the failure is logged, async hooks receive `Failed`, and no message handler is
  * invoked.
  *
  * @tparam A
  *   the successful task result type
  */
enum LiveAsyncResult[+A]:
  /** The task completed successfully.
    *
    * @param value
    *   the task's result
    */
  case Succeeded(value: A)

  /** The task terminated unsuccessfully.
    *
    * @param cause
    *   the squashed ZIO failure cause
    */
  case Failed(cause: Throwable)

  /** The task was cancelled through the async context.
    *
    * @param reason
    *   the application-supplied cancellation reason, if any
    */
  case Cancelled(reason: Option[String])

private[scalive] trait LiveAsyncRuntime:
  def start[A, Msg](name: String)(effect: Task[A])(toMsg: LiveAsyncResult[A] => Msg): UIO[Unit]
  def cancel(name: String, reason: Option[String]): UIO[Unit]

private[scalive] object LiveAsyncRuntime:
  object Disabled extends LiveAsyncRuntime:
    def start[A, Msg](
      name: String
    )(
      effect: Task[A]
    )(
      toMsg: LiveAsyncResult[A] => Msg
    ): UIO[Unit] =
      ZIO.unit

    def cancel(name: String, reason: Option[String]): UIO[Unit] =
      ZIO.unit

private[scalive] enum LiveAsyncOwner:
  case Root
  case Component(cid: Int)

final private[scalive] case class LiveAsyncTaskId(owner: LiveAsyncOwner, name: String)

final private[scalive] case class LiveAsyncTaskState(
  token: String,
  fiber: Fiber.Runtime[Nothing, Unit],
  cancelledEvent: Option[String] => LiveAsyncCompletionEvent)

final private[scalive] case class LiveAsyncRuntimeState(
  tasks: Map[LiveAsyncTaskId, LiveAsyncTaskState])

private[scalive] object LiveAsyncRuntimeState:
  val empty: LiveAsyncRuntimeState = LiveAsyncRuntimeState(Map.empty)

private[scalive] enum LiveAsyncCompletionEvent:
  case Succeeded(name: String, message: Any)
  case Failed(name: String, cause: Throwable, message: Any)
  case Cancelled(name: String, reason: Option[String], message: Any)
  case MappingFailed(name: String, cause: Throwable)

final private[scalive] case class LiveAsyncCompletion(
  owner: LiveAsyncOwner,
  event: LiveAsyncCompletionEvent)
