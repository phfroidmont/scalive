package scalive

import scala.annotation.targetName

import zio.*

enum AsyncValue[+A]:
  case Empty
  case Loading(previous: Option[A])
  case Ok(value: A)
  case Failed(previous: Option[A], cause: Cause[Throwable])
  case Cancelled(previous: Option[A], reason: Option[String])

object AsyncValue:
  def empty[A]: AsyncValue[A] = AsyncValue.Empty

  def loading[A]: AsyncValue[A] = AsyncValue.Loading(None)

  def ok[A](value: A): AsyncValue[A] = AsyncValue.Ok(value)

  def currentValue[A](value: AsyncValue[A]): Option[A] =
    value match
      case AsyncValue.Ok(current)            => Some(current)
      case AsyncValue.Loading(previous)      => previous
      case AsyncValue.Failed(previous, _)    => previous
      case AsyncValue.Cancelled(previous, _) => previous
      case AsyncValue.Empty                  => None

  def currentlyLoading[A](value: AsyncValue[A]): Boolean =
    value match
      case AsyncValue.Loading(_) => true
      case _                     => false

  def currentlyOk[A](value: AsyncValue[A]): Boolean =
    value match
      case AsyncValue.Ok(_) => true
      case _                => false

  def markLoading[A](current: AsyncValue[A], reset: Boolean = false): AsyncValue[A] =
    AsyncValue.Loading(if reset then None else currentValue(current))

  def applyResult[A](current: AsyncValue[A], result: LiveAsyncResult[A]): AsyncValue[A] =
    val previous = currentValue(current)
    result match
      case LiveAsyncResult.Ok(value)         => AsyncValue.Ok(value)
      case LiveAsyncResult.Failed(cause)     => AsyncValue.Failed(previous, cause)
      case LiveAsyncResult.Cancelled(reason) => AsyncValue.Cancelled(previous, reason)

  extension [A](value: AsyncValue[A])
    @targetName("asyncValueOption")
    def valueOption: Option[A] = AsyncValue.currentValue(value)
    @targetName("asyncIsLoading")
    def isLoading: Boolean = AsyncValue.currentlyLoading(value)
    @targetName("asyncIsOk")
    def isOk: Boolean = AsyncValue.currentlyOk(value)
    @targetName("asyncLoading")
    def loading(reset: Boolean = false): AsyncValue[A] = AsyncValue.markLoading(value, reset)
    @targetName("asyncUpdated")
    def updated(result: LiveAsyncResult[A]): AsyncValue[A] = AsyncValue.applyResult(value, result)
end AsyncValue

enum LiveAsyncResult[+A]:
  case Ok(value: A)
  case Failed(cause: Cause[Throwable])
  case Cancelled(reason: Option[String])

enum AsyncStartMode:
  case Restart
  case KeepExisting

trait LiveAsyncRuntime:
  def start[A, Msg](
    name: String,
    mode: AsyncStartMode
  )(
    effect: Task[A]
  )(
    toMsg: LiveAsyncResult[A] => Msg
  ): UIO[Unit]

  def startAssign[A, Model](
    name: String,
    mode: AsyncStartMode
  )(
    effect: Task[A]
  )(
    update: (Model, LiveAsyncResult[A]) => Model
  ): UIO[Unit]

  def cancel(name: String, reason: Option[String]): UIO[Unit]

object LiveAsyncRuntime:
  object Disabled extends LiveAsyncRuntime:
    def start[A, Msg](
      name: String,
      mode: AsyncStartMode
    )(
      effect: Task[A]
    )(
      toMsg: LiveAsyncResult[A] => Msg
    ): UIO[Unit] =
      val _ = (name, mode, effect, toMsg)
      ZIO.unit

    def startAssign[A, Model](
      name: String,
      mode: AsyncStartMode
    )(
      effect: Task[A]
    )(
      update: (Model, LiveAsyncResult[A]) => Model
    ): UIO[Unit] =
      val _ = (name, mode, effect, update)
      ZIO.unit

    def cancel(name: String, reason: Option[String]): UIO[Unit] =
      val _ = (name, reason)
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
  case Message(name: String, message: Any)
  case Assign(update: Any => Any)

final private[scalive] case class LiveAsyncCompletion(
  owner: LiveAsyncOwner,
  event: LiveAsyncCompletionEvent)
