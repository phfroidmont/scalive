package scalive

import scala.annotation.targetName

/** How one task started with [[Async.start]] ended. Carry it in a message to the owner.
  *
  * @tparam E
  *   the typed error the task can fail with
  * @tparam A
  *   the value the task produces
  */
enum LiveAsyncResult[+E, +A]:
  /** The task succeeded with `value`.
    *
    * @param value
    *   The value the task produced.
    */
  case Succeeded[+A](value: A) extends LiveAsyncResult[Nothing, A]

  /** The task failed with the typed error `error`.
    *
    * @param error
    *   The error the task failed with.
    */
  case Failed[+E](error: E) extends LiveAsyncResult[E, Nothing]

  /** The task died with a defect, such as `ZIO.die` or an exception thrown inside `ZIO.succeed`.
    *
    * The owner keeps running; Scalive also logs the defect. Show stable copy to the visitor rather
    * than the exception message.
    *
    * @param defect
    *   The defect the task died with.
    */
  case Died(defect: Throwable) extends LiveAsyncResult[Nothing, Nothing]

  /** The task was interrupted, by [[Async.cancel Async.cancel]] or by itself.
    *
    * @param reason
    *   The reason passed to [[Async.cancel]], or `None` when none was given or the task interrupted
    *   itself.
    */
  case Cancelled(reason: Option[String]) extends LiveAsyncResult[Nothing, Nothing]
end LiveAsyncResult

/** A task that ended, as passed to `async` lifecycle hooks before the owner handles its message.
  *
  * @param key
  *   The key the task was started under. Compare it with a typed key using `==`.
  * @param result
  *   How the task ended, with the task's value when it succeeded.
  * @param message
  *   The message the task's `toMsg` built from `result`, which the owner handles unless a hook
  *   halts.
  */
final case class LiveAsyncEvent[+Msg](
  key: AsyncKey[Any],
  result: LiveAsyncResult[Any, Any],
  message: Msg)

/** The state of background work, kept in the model to render loading, result, and failure.
  *
  * Start from [[AsyncValue.loading]] or [[AsyncValue.empty]], call `loading()` when work starts,
  * and apply each [[LiveAsyncResult]] with `updated`. Loading, failure, death, and cancellation
  * keep the last successful value, so a refresh can keep showing it. See
  * [AsyncValue](https://scalive.dev/guides/async-work#model-finite-work-with-asyncvalue).
  *
  * @tparam E
  *   the typed error the work can fail with
  * @tparam A
  *   the value the work produces
  */
enum AsyncValue[+E, +A]:
  /** No work has started. */
  case Empty

  /** Work is running; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    */
  case Loading[+A](previous: Option[A]) extends AsyncValue[Nothing, A]

  /** The last work succeeded with `value`.
    *
    * @param value
    *   The result of the last work.
    */
  case Ok[+A](value: A) extends AsyncValue[Nothing, A]

  /** The last work failed with a typed error; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    * @param error
    *   The error the last work failed with.
    */
  case Failed[+E, +A](previous: Option[A], error: E) extends AsyncValue[E, A]

  /** The last work died with a defect; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    * @param defect
    *   The defect the last work died with.
    */
  case Died[+A](previous: Option[A], defect: Throwable) extends AsyncValue[Nothing, A]

  /** The last work was cancelled; `previous` is the last successful value, if any.
    *
    * @param previous
    *   The last successful value, if any.
    * @param reason
    *   The reason passed to [[Async.cancel]], if any.
    */
  case Cancelled[+A](previous: Option[A], reason: Option[String]) extends AsyncValue[Nothing, A]
end AsyncValue

/** Constructors and operations for [[AsyncValue]]. */
object AsyncValue:
  /** A value for work that has not started. */
  def empty[E, A]: AsyncValue[E, A] = AsyncValue.Empty

  /** A value for work that is running, with no earlier result. */
  def loading[E, A]: AsyncValue[E, A] = AsyncValue.Loading(None)

  /** A value holding a successful result. */
  def ok[E, A](value: A): AsyncValue[E, A] = AsyncValue.Ok(value)

  extension [E, A](value: AsyncValue[E, A])
    /** The current or last successful value, if any. */
    @targetName("asyncValueOption") def valueOption: Option[A] = value match
      case AsyncValue.Empty                  => None
      case AsyncValue.Loading(previous)      => previous
      case AsyncValue.Ok(current)            => Some(current)
      case AsyncValue.Failed(previous, _)    => previous
      case AsyncValue.Died(previous, _)      => previous
      case AsyncValue.Cancelled(previous, _) => previous

    /** Whether work is running. */
    @targetName("asyncIsLoading") def isLoading: Boolean = value match
      case AsyncValue.Loading(_) => true
      case _                     => false

    /** Whether the last work succeeded. */
    @targetName("asyncIsOk") def isOk: Boolean = value match
      case AsyncValue.Ok(_) => true
      case _                => false

    /** Whether the last work failed with a typed error or died with a defect. */
    @targetName("asyncIsFailed") def isFailed: Boolean = value match
      case AsyncValue.Failed(_, _) | AsyncValue.Died(_, _) => true
      case _                                               => false

    /** The typed error the last work failed with, if it failed with one. */
    @targetName("asyncFailure") def failure: Option[E] = value match
      case AsyncValue.Failed(_, error) => Some(error)
      case _                           => None

    /** Applies `f` to the current and last successful values, keeping the state. */
    @targetName("asyncMap") def map[B](f: A => B): AsyncValue[E, B] = value match
      case AsyncValue.Empty                       => AsyncValue.Empty
      case AsyncValue.Loading(previous)           => AsyncValue.Loading(previous.map(f))
      case AsyncValue.Ok(current)                 => AsyncValue.Ok(f(current))
      case AsyncValue.Failed(previous, error)     => AsyncValue.Failed(previous.map(f), error)
      case AsyncValue.Died(previous, defect)      => AsyncValue.Died(previous.map(f), defect)
      case AsyncValue.Cancelled(previous, reason) => AsyncValue.Cancelled(previous.map(f), reason)

    /** Marks work as running, keeping the last successful value unless `reset` is `true`. */
    @targetName("asyncLoading") def loading(reset: Boolean = false): AsyncValue[E, A] =
      AsyncValue.Loading(if reset then None else valueOption)

    /** Applies a task's result: success replaces the value; failure, death, and cancellation keep
      * it.
      */
    @targetName("asyncUpdated") def updated(result: LiveAsyncResult[E, A]): AsyncValue[E, A] =
      result match
        case LiveAsyncResult.Succeeded(current) => AsyncValue.Ok(current)
        case LiveAsyncResult.Failed(error)      => AsyncValue.Failed(valueOption, error)
        case LiveAsyncResult.Died(defect)       => AsyncValue.Died(valueOption, defect)
        case LiveAsyncResult.Cancelled(reason)  => AsyncValue.Cancelled(valueOption, reason)
  end extension
end AsyncValue
