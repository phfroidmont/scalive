package scalive

import zio.*

final private[scalive] class TopicDispatcher[-R, A] private (
  workers: Ref.Synchronized[Map[String, Queue[TopicDispatcher.Dispatch[A]]]],
  barrierTail: Ref[UIO[Unit]],
  terminal: Ref[Boolean],
  scope: Scope,
  handler: (String, A) => ZIO[R, Throwable, Unit],
  workerFailure: Promise[Throwable, Nothing]):

  def submit(topic: String, value: A): ZIO[R, Nothing, Unit] =
    barrierTail.get.flatMap(before => enqueue(topic, TopicDispatcher.Dispatch(before, value, None)))

  def submitBarrier(topic: String, value: A): ZIO[R, Nothing, Unit] =
    for
      completed <- Promise.make[Nothing, Unit]
      before    <- barrierTail.getAndSet(completed.await)
      _         <- enqueue(topic, TopicDispatcher.Dispatch(before, value, Some(completed)))
    yield ()

  private def enqueue(
    topic: String,
    dispatch: TopicDispatcher.Dispatch[A]
  ): ZIO[R, Nothing, Unit] =
    workers.modifyZIO { current =>
      current.get(topic) match
        case Some(queue) =>
          queue.offer(dispatch).as(() -> current)
        case None =>
          for
            queue <- Queue.unbounded[TopicDispatcher.Dispatch[A]]
            _     <- queue.offer(dispatch)
            _     <- runWorker(topic, queue).forkIn(scope)
          yield () -> current.updated(topic, queue)
    }

  def failure: Task[Nothing] =
    workerFailure.await

  private def runWorker(
    topic: String,
    queue: Queue[TopicDispatcher.Dispatch[A]]
  ): ZIO[R, Throwable, Nothing] =
    queue.take
      .flatMap(dispatch =>
        (dispatch.before *> terminal.get.flatMap { failed =>
          if failed then ZIO.unit else handler(topic, dispatch.value)
        }).catchAllCause { cause =>
          if cause.isInterruptedOnly then ZIO.failCause(cause)
          else terminal.set(true) *> workerFailure.failCause(cause).unit
        }.ensuring(ZIO.foreachDiscard(dispatch.completed)(_.succeed(())))
      )
      .forever
end TopicDispatcher

private[scalive] object TopicDispatcher:
  final private case class Dispatch[A](
    before: UIO[Unit],
    value: A,
    completed: Option[Promise[Nothing, Unit]])

  def make[R, A](
    handler: (String, A) => ZIO[R, Throwable, Unit]
  ): ZIO[Scope, Nothing, TopicDispatcher[R, A]] =
    for
      workers       <- Ref.Synchronized.make(Map.empty[String, Queue[Dispatch[A]]])
      barrierTail   <- Ref.make[UIO[Unit]](ZIO.unit)
      terminal      <- Ref.make(false)
      scope         <- ZIO.service[Scope]
      workerFailure <- Promise.make[Throwable, Nothing]
    yield new TopicDispatcher(
      workers,
      barrierTail,
      terminal,
      scope,
      handler,
      workerFailure
    )
