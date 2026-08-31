package scalive.runtime.connection

import zio.*

final private[scalive] class SerialWriter[A] private (
  val capacity: Int,
  queue: Queue[SerialWriter.Entry[A]],
  terminal: Promise[Nothing, SerialWriter.Error],
  gate: Semaphore,
  inFlight: Ref[Option[SerialWriter.Entry[A]]],
  write: A => Task[Unit]):
  import SerialWriter.*

  def send(value: A): IO[Error, Unit] =
    enqueue(Some(value)).flatMap(_.await)

  /** Installs one bounded write without waiting for the network operation to complete. */
  def offer(value: A): IO[Error, Unit] =
    enqueue(Some(value)).unit

  /** Waits for queue capacity and installs one write without waiting for the sink. */
  def offerAwait(value: A): IO[Error, Unit] =
    enqueueEventually(Some(value)).unit

  /** Waits until every write accepted before this call has completed. */
  def drain: IO[Error, Unit] =
    enqueueEventually(None).flatMap(_.await)

  private def enqueueEventually(value: Option[A]): IO[Error, Promise[Error, Unit]] =
    enqueue(value).catchSome { case Error.Saturated(_) => ZIO.yieldNow *> enqueueEventually(value) }

  def awaitFailure: UIO[Error] = terminal.await

  def pollFailure: UIO[Option[Error]] = terminal.poll.flatMap(ZIO.foreach(_)(identity))

  private def enqueue(value: Option[A]): IO[Error, Promise[Error, Unit]] =
    for
      result <- Promise.make[Error, Unit]
      _      <- gate.withPermit {
             terminal.poll.flatMap {
               case Some(error) => error.flatMap(ZIO.fail(_))
               case None        =>
                 queue.offer(Entry(value, result)).flatMap {
                   case true  => ZIO.unit
                   case false => ZIO.fail(Error.Saturated(capacity))
                 }
             }
           }
    yield result

  def close: UIO[Unit] =
    gate.withPermit {
      for
        current <- terminal.poll
        error   <- current match
                   case Some(existing) => existing
                   case None           => terminal.succeed(Error.Shutdown).as(Error.Shutdown)
        active <- inFlight.getAndSet(None)
        _      <- ZIO.foreachDiscard(active)(_.result.fail(error).unit)
        _      <- failPending(error)
        _      <- queue.shutdown
      yield ()
    }

  private def run: UIO[Unit] =
    ZIO
      .uninterruptibleMask { restore =>
        restore(queue.take).flatMap { entry =>
          gate
            .withPermit {
              terminal.poll.flatMap {
                case Some(error) => error.flatMap(entry.result.fail(_).unit).as(false)
                case None        => inFlight.set(Some(entry)).as(true)
              }
            }.flatMap {
              case false => ZIO.unit
              case true  =>
                val writeEntry = entry.value match
                  case Some(value) => ZIO.suspend(write(value)).mapError(Error.WriteFailed.apply)
                  case None        => ZIO.unit
                restore(
                  writeEntry.raceFirst(terminal.await.flatMap(ZIO.fail(_)))
                ).exit.flatMap(exit => complete(entry, exit))
            }
        }
      }.flatMap(_ => run).catchAllCause(failUnexpected)

  private def complete(entry: Entry[A], exit: Exit[Error, Unit]): UIO[Unit] =
    gate.withPermit {
      terminal.poll.flatMap {
        case Some(error) =>
          error.flatMap { existing =>
            inFlight.set(None) *> entry.result.fail(existing).unit
          }
        case None =>
          exit match
            case Exit.Success(_)     => inFlight.set(None) *> entry.result.succeed(()).unit
            case Exit.Failure(cause) =>
              val error = cause.failureOption.getOrElse(Error.WriteFailed(cause.squash))
              terminal.succeed(error).unit *> inFlight.set(None) *> entry.result.fail(error).unit *>
                failPending(error) *> queue.shutdown
      }
    }

  private def failPending(error: Error): UIO[Unit] =
    queue.takeAll
      .catchAllCause(_ => ZIO.succeed(Chunk.empty)).flatMap(entries =>
        ZIO.foreachDiscard(entries)(_.result.fail(error).unit)
      )

  private def failUnexpected(cause: Cause[Error]): UIO[Unit] =
    if cause.isInterruptedOnly then ZIO.unit
    else
      val error = cause.failureOption.getOrElse(Error.WriteFailed(cause.squash))
      gate.withPermit {
        terminal.poll.flatMap {
          case Some(_) => ZIO.unit
          case None    =>
            terminal.succeed(error).unit *>
              inFlight
                .getAndSet(None).flatMap(
                  ZIO.foreachDiscard(_)(_.result.fail(error).unit)
                ) *>
              failPending(error) *>
              queue.shutdown
        }
      }
end SerialWriter

private[scalive] object SerialWriter:
  enum Error extends Exception:
    case InvalidCapacity(capacity: Int)
    case Saturated(capacity: Int)
    case Shutdown
    case WriteFailed(cause: Throwable)

  final private case class Entry[A](value: Option[A], result: Promise[Error, Unit])

  def make[A](capacity: Int)(write: A => Task[Unit]): ZIO[Scope, Error, SerialWriter[A]] =
    if capacity <= 0 then ZIO.fail(Error.InvalidCapacity(capacity))
    else
      for
        queue    <- Queue.dropping[Entry[A]](capacity)
        terminal <- Promise.make[Nothing, Error]
        gate     <- Semaphore.make(1L)
        inFlight <- Ref.make(Option.empty[Entry[A]])
        writer = SerialWriter(capacity, queue, terminal, gate, inFlight, write)
        _ <- writer.run.interruptible.forkScoped
        _ <- ZIO.addFinalizer(writer.close)
      yield writer
