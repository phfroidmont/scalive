package scalive

import zio.*

final case class LiveAsync[A](name: String)

enum LiveAsyncResult[+A]:
  case Ok(value: A)
  case Failed(cause: Cause[Throwable])
  case Cancelled(reason: Option[String])

enum AsyncStartMode:
  case Restart
  case KeepExisting

trait LiveAsyncRuntime:
  def start[A, Msg](
    key: LiveAsync[A],
    mode: AsyncStartMode
  )(
    effect: Task[A]
  )(
    toMsg: LiveAsyncResult[A] => Msg
  ): UIO[Unit]

  def cancel[A](key: LiveAsync[A], reason: Option[String]): UIO[Unit]

object LiveAsyncRuntime:
  object Disabled extends LiveAsyncRuntime:
    def start[A, Msg](
      key: LiveAsync[A],
      mode: AsyncStartMode
    )(
      effect: Task[A]
    )(
      toMsg: LiveAsyncResult[A] => Msg
    ): UIO[Unit] =
      val _ = (key, mode, effect, toMsg)
      ZIO.unit

    def cancel[A](key: LiveAsync[A], reason: Option[String]): UIO[Unit] =
      val _ = (key, reason)
      ZIO.unit

private[scalive] enum LiveAsyncOwner:
  case Root
  case Component(cid: Int)

final private[scalive] case class LiveAsyncTaskId(owner: LiveAsyncOwner, name: String)

final private[scalive] case class LiveAsyncTaskState(
  token: String,
  fiber: Fiber.Runtime[Nothing, Unit],
  cancelledMessage: Option[String] => Any)

final private[scalive] case class LiveAsyncRuntimeState(
  tasks: Map[LiveAsyncTaskId, LiveAsyncTaskState])

private[scalive] object LiveAsyncRuntimeState:
  val empty: LiveAsyncRuntimeState = LiveAsyncRuntimeState(Map.empty)

final private[scalive] case class LiveAsyncCompletion(
  owner: LiveAsyncOwner,
  message: Any)
