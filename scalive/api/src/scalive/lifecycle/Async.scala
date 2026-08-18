package scalive

import scala.annotation.targetName

enum LiveAsyncResult[+A]:
  case Succeeded(value: A)
  case Failed(cause: Throwable)
  case Cancelled(reason: Option[String])

final case class LiveAsyncEvent[+Msg](
  name: AsyncKey[Any],
  result: LiveAsyncResult[Msg])

enum AsyncValue[+A]:
  case Empty
  case Loading(previous: Option[A])
  case Ok(value: A)
  case Failed(previous: Option[A], cause: Throwable)
  case Cancelled(previous: Option[A], reason: Option[String])

object AsyncValue:
  def empty[A]: AsyncValue[A]        = AsyncValue.Empty
  def loading[A]: AsyncValue[A]      = AsyncValue.Loading(None)
  def ok[A](value: A): AsyncValue[A] = AsyncValue.Ok(value)

  def currentValue[A](value: AsyncValue[A]): Option[A] = value match
    case AsyncValue.Empty                  => None
    case AsyncValue.Loading(previous)      => previous
    case AsyncValue.Ok(current)            => Some(current)
    case AsyncValue.Failed(previous, _)    => previous
    case AsyncValue.Cancelled(previous, _) => previous

  def currentlyLoading[A](value: AsyncValue[A]): Boolean = value match
    case AsyncValue.Loading(_) => true
    case _                     => false

  def currentlyOk[A](value: AsyncValue[A]): Boolean = value match
    case AsyncValue.Ok(_) => true
    case _                => false

  def markLoading[A](current: AsyncValue[A], reset: Boolean = false): AsyncValue[A] =
    AsyncValue.Loading(if reset then None else currentValue(current))

  def applyResult[A](current: AsyncValue[A], result: LiveAsyncResult[A]): AsyncValue[A] =
    val previous = currentValue(current)
    result match
      case LiveAsyncResult.Succeeded(value)  => AsyncValue.Ok(value)
      case LiveAsyncResult.Failed(cause)     => AsyncValue.Failed(previous, cause)
      case LiveAsyncResult.Cancelled(reason) => AsyncValue.Cancelled(previous, reason)

  extension [A](value: AsyncValue[A])
    @targetName("asyncValueOption") def valueOption: Option[A] = currentValue(value)
    @targetName("asyncIsLoading") def isLoading: Boolean       = currentlyLoading(value)
    @targetName("asyncIsOk") def isOk: Boolean                 = currentlyOk(value)
    @targetName("asyncLoading") def loading(reset: Boolean = false): AsyncValue[A] =
      markLoading(value, reset)
    @targetName("asyncUpdated") def updated(result: LiveAsyncResult[A]): AsyncValue[A] =
      applyResult(value, result)
end AsyncValue
