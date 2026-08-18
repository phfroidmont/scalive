package scalive

import zio.*

final private[scalive] class SerialWriter[A] private (
  queue: Queue[(A, Promise[Throwable, Unit])],
  terminalState: Ref.Synchronized[Option[Cause[Throwable]]],
  terminalFailure: Promise[Throwable, Nothing],
  write: A => Task[Unit]):

  def send(value: A): Task[Unit] =
    for
      completed <- Promise.make[Throwable, Unit]
      await     <- terminalState.modifyZIO {
                 case failed @ Some(cause) =>
                   ZIO.succeed(ZIO.failCause(cause) -> failed)
                 case None =>
                   queue.offer(value -> completed).as(completed.await -> None)
               }
      _ <- await
    yield ()

  def failure: Task[Nothing] =
    terminalFailure.await

  private def run: Task[Nothing] =
    queue.take.flatMap { case (value, completed) =>
      write(value).foldCauseZIO(
        cause =>
          failPending(cause, completed) *>
            terminalFailure.failCause(cause).unit *>
            ZIO.never,
        _ => completed.succeed(()).unit
      )
    }.forever

  private def failPending(
    cause: Cause[Throwable],
    current: Promise[Throwable, Unit]
  ): UIO[Unit] =
    terminalState.modifyZIO {
      case failed @ Some(_) => current.failCause(cause).unit.as(() -> failed)
      case None             =>
        for
          pending <- queue.takeAll
          _       <- current.failCause(cause)
          _       <- ZIO.foreachDiscard(pending) { case (_, completed) =>
                 completed.failCause(cause)
               }
        yield () -> Some(cause)
    }
end SerialWriter

private[scalive] object SerialWriter:
  def make[A](write: A => Task[Unit]): ZIO[Scope, Nothing, SerialWriter[A]] =
    for
      queue           <- Queue.unbounded[(A, Promise[Throwable, Unit])]
      terminalState   <- Ref.Synchronized.make(Option.empty[Cause[Throwable]])
      terminalFailure <- Promise.make[Throwable, Nothing]
      writer = new SerialWriter(queue, terminalState, terminalFailure, write)
      _ <- writer.run.forkScoped
    yield writer
