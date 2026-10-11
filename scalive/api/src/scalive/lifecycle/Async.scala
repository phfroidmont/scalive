package scalive

import scala.annotation.targetName

/** How one task started with [[Async.start]] ended. Carry it in a message to the owner. */
enum LiveAsyncResult[+A]:
  /** The task succeeded with `value`.
    *
    * @param value
    *   The value the task produced.
    */
  case Succeeded(value: A)

  /** The task failed with `cause`. Show stable copy to the visitor rather than the message.
    *
    * @param cause
    *   The error the task failed with.
    */
  case Failed(cause: Throwable)

  /** The task was interrupted by [[Async.cancel Async.cancel]], with the reason it was given.
    *
    * @param reason
    *   The reason passed to [[Async.cancel]], if any.
    */
  case Cancelled(reason: Option[String])

/** A task that ended, as passed to `async` lifecycle hooks before the owner handles its message.
  *
  * @param name
  *   The key the task was started under.
  * @param result
  *   How the task ended. A success carries the message built by the task's `toMsg`, not the task's
  *   value.
  */
final case class LiveAsyncEvent[+Msg](
  name: AsyncKey[Any],
  result: LiveAsyncResult[Msg])

/** The state of background work, kept in the model to render loading, result, and failure.
  *
  * Start from [[AsyncValue.loading]] or [[AsyncValue.empty]], call `loading()` when work starts,
  * and apply each [[LiveAsyncResult]] with `updated`. Loading, failure, and cancellation keep the
  * last successful value, so a refresh can keep showing it. See
  * [AsyncValue](https://scalive.dev/guides/async-work#model-finite-work-with-asyncvalue).
  */
enum AsyncValue[+A]:
  /** No work has started. */
  case Empty

  /** Work is running; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    */
  case Loading(previous: Option[A])

  /** The last work succeeded with `value`.
    *
    * @param value
    *   The result of the last work.
    */
  case Ok(value: A)

  /** The last work failed; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    * @param cause
    *   The error the last work failed with.
    */
  case Failed(previous: Option[A], cause: Throwable)

  /** The last work was cancelled; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    * @param reason
    *   The reason passed to [[Async.cancel]], if any.
    */
  case Cancelled(previous: Option[A], reason: Option[String])
end AsyncValue

/** Constructors and operations for [[AsyncValue]].
  *
  * Prefer the extension methods `loading`, `updated`, and `valueOption`; the named functions are
  * the same operations in prefix form.
  */
object AsyncValue:
  /** A value for work that has not started. */
  def empty[A]: AsyncValue[A] = AsyncValue.Empty

  /** A value for work that is running, with no earlier result. */
  def loading[A]: AsyncValue[A] = AsyncValue.Loading(None)

  /** A value holding a successful result. */
  def ok[A](value: A): AsyncValue[A] = AsyncValue.Ok(value)

  /** The current or last successful value, if any, like `valueOption`. */
  def currentValue[A](value: AsyncValue[A]): Option[A] = value match
    case AsyncValue.Empty                  => None
    case AsyncValue.Loading(previous)      => previous
    case AsyncValue.Ok(current)            => Some(current)
    case AsyncValue.Failed(previous, _)    => previous
    case AsyncValue.Cancelled(previous, _) => previous

  /** Whether work is running, like `isLoading`. */
  def currentlyLoading[A](value: AsyncValue[A]): Boolean = value match
    case AsyncValue.Loading(_) => true
    case _                     => false

  /** Whether the last work succeeded, like `isOk`. */
  def currentlyOk[A](value: AsyncValue[A]): Boolean = value match
    case AsyncValue.Ok(_) => true
    case _                => false

  /** Marks work as running, like `loading`. */
  def markLoading[A](current: AsyncValue[A], reset: Boolean = false): AsyncValue[A] =
    AsyncValue.Loading(if reset then None else currentValue(current))

  /** Applies a task's result, like `updated`. */
  def applyResult[A](current: AsyncValue[A], result: LiveAsyncResult[A]): AsyncValue[A] =
    val previous = currentValue(current)
    result match
      case LiveAsyncResult.Succeeded(value)  => AsyncValue.Ok(value)
      case LiveAsyncResult.Failed(cause)     => AsyncValue.Failed(previous, cause)
      case LiveAsyncResult.Cancelled(reason) => AsyncValue.Cancelled(previous, reason)

  extension [A](value: AsyncValue[A])
    /** The current or last successful value, if any. */
    @targetName("asyncValueOption") def valueOption: Option[A] = currentValue(value)

    /** Whether work is running. */
    @targetName("asyncIsLoading") def isLoading: Boolean = currentlyLoading(value)

    /** Whether the last work succeeded. */
    @targetName("asyncIsOk") def isOk: Boolean = currentlyOk(value)

    /** Marks work as running, keeping the last successful value unless `reset` is `true`. */
    @targetName("asyncLoading") def loading(reset: Boolean = false): AsyncValue[A] =
      markLoading(value, reset)

    /** Applies a task's result: success replaces the value; failure and cancellation keep it. */
    @targetName("asyncUpdated") def updated(result: LiveAsyncResult[A]): AsyncValue[A] =
      applyResult(value, result)
end AsyncValue
