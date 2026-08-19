package scalive.runtime.connection

import zio.*

import scalive.BindingPayload
import scalive.LiveView
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.kernel.*

/** Owns one connected, unrouted root lifecycle. */
final private[scalive] class RootConnection[Msg, Model] private (
  val epoch: Epoch,
  config: ConnectionConfig,
  kernel: SessionKernel[Msg, Model],
  outbound: InMemoryOutboundReservations[SessionOutput],
  writer: SerialWriter[ConnectionOutput],
  ingress: Queue[RootConnection.Event],
  ingressGate: Semaphore,
  pending: Ref[Map[CommandId, Promise[ConnectionError, Unit]]],
  failure: Promise[Nothing, ConnectionError],
  bootstrapReady: Promise[ConnectionError, Unit],
  closing: Promise[Nothing, Unit],
  closed: Promise[Nothing, Unit]):
  import RootConnection.*

  def submitEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Unit] =
    enqueueEvent(command, binding, payload).flatMap(_.await)

  /** Installs one event in the bounded ingress queue without waiting for its eventual reply. */
  def offerEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Unit] =
    enqueueEvent(command, binding, payload).unit

  private def enqueueEvent(
    command: CommandId,
    binding: BindingId,
    payload: BindingPayload
  ): IO[ConnectionError, Promise[ConnectionError, Unit]] =
    ZIO.uninterruptible {
      for
        response <- Promise.make[ConnectionError, Unit]
        _        <- ingressGate
               .withPermit {
                 closing.isDone.flatMap {
                   case true  => ZIO.fail(ConnectionError.Closed)
                   case false =>
                     pending.modify { current =>
                       if current.contains(command) then
                         Left(ConnectionError.DuplicateCommand(command)) -> current
                       else Right(()) -> current.updated(command, response)
                     }.absolve *> ingress.offer(Event(command, binding, payload)).flatMap {
                       case true  => ZIO.unit
                       case false =>
                         ZIO.fail(ConnectionError.IngressSaturated(config.ingressCapacity))
                     }
                 }
               }.tapError {
                 case error: ConnectionError.IngressSaturated => terminate(error)
                 case _                                       => ZIO.unit
               }
      yield response
    }

  def awaitFailure: UIO[ConnectionError] = failure.await

  private[connection] def ingressDepth: UIO[Int] = ingress.size

  private[connection] def pendingCount: UIO[Int] = pending.get.map(_.size)

  private[connection] def isClosing: UIO[Boolean] = closing.isDone

  /** Control-plane shutdown; it never waits for space in an application queue. */
  def close: UIO[Unit] =
    ZIO.uninterruptibleMask { restore =>
      closing.succeed(()).flatMap {
        case false => restore(closed.await)
        case true  =>
          for
            _ <- ingressGate.withPermit {
                   for
                     queued <- ingress.takeAll
                     _      <- ZIO.foreachDiscard(queued)(event =>
                            complete(event.command, Left(ConnectionError.Closed))
                          )
                     _ <- ingress.shutdown
                   yield ()
                 }
            _ <- kernel.close
            _ <- outbound.shutdown
            _ <- writer.close
            _ <- failPending(ConnectionError.Closed)
            _ <- closed.succeed(())
          yield ()
      }
    }

  private def runIngress: UIO[Unit] =
    ingress.take
      .flatMap { event =>
        kernel
          .submit(
            event.command,
            SessionCommand.ClientEvent(epoch, event.binding, event.payload)
          ).foldZIO(
            rejection => reject(event.command, rejection),
            _ => awaitCompletion(event.command)
          ) *> runIngress
      }.catchAllCause(_ => ZIO.unit)

  private def reject(command: CommandId, rejection: SessionRejection): UIO[Unit] =
    closing.isDone.flatMap {
      case true  => complete(command, Left(ConnectionError.Closed))
      case false =>
        rejection match
          case _: SessionRejection.UnknownBinding | _: SessionRejection.BindingFailed =>
            writer
              .send(ConnectionOutput.Rejected(command, rejection)).foldZIO(
                error => completeAfterWriterClose(command, error),
                _ => complete(command, Right(()))
              )
          case SessionRejection.SessionFailed(sessionFailure) =>
            terminate(ConnectionError.SessionFailed(sessionFailure))
          case other => terminate(ConnectionError.KernelRejected(other))
    }

  private def runOutbound(first: Boolean): UIO[Unit] =
    outbound.take.foldZIO(
      error =>
        closing.isDone.flatMap {
          case true  => ZIO.unit
          case false => terminate(ConnectionError.OutboundFailed(error.toString))
        },
      batch => writeBatch(batch.items, first).flatMap(runOutbound)
    )

  private def writeBatch(outputs: Vector[SessionOutput], first: Boolean): UIO[Boolean] =
    outputs.foldLeft[UIO[Boolean]](ZIO.succeed(first)) { (state, output) =>
      state.flatMap { isFirst =>
        val connectionOutput =
          if isFirst then ConnectionOutput.Joined(output.delta)
          else
            output.command match
              case Some(command) => ConnectionOutput.Reply(command, output.delta)
              case None          => ConnectionOutput.Diff(output.delta)

        writer
          .send(connectionOutput).foldZIO(
            error =>
              closing.isDone
                .flatMap {
                  case false => terminate(writerFailure(error))
                  case true  =>
                    connectionOutput match
                      case ConnectionOutput.Reply(command, _) =>
                        complete(command, Left(ConnectionError.Closed))
                      case ConnectionOutput.Rejected(command, _) =>
                        complete(command, Left(ConnectionError.Closed))
                      case ConnectionOutput.Joined(_) =>
                        bootstrapReady.fail(ConnectionError.Closed).unit
                      case ConnectionOutput.Diff(_) => ZIO.unit
                }.as(false),
            _ =>
              val signal = connectionOutput match
                case ConnectionOutput.Joined(_)            => bootstrapReady.succeed(()).unit
                case ConnectionOutput.Reply(command, _)    => complete(command, Right(()))
                case ConnectionOutput.Rejected(command, _) => complete(command, Right(()))
                case ConnectionOutput.Diff(_)              => ZIO.unit
              signal.as(false)
          )
      }
    }

  private def monitorKernel: UIO[Unit] =
    kernel.awaitTermination.flatMap {
      case SessionState.Crashed(_, sessionFailure) =>
        terminate(ConnectionError.SessionFailed(sessionFailure))
      case _ => ZIO.unit
    }

  private def terminate(error: ConnectionError): UIO[Unit] =
    failure.succeed(error).flatMap {
      case true =>
        bootstrapReady.fail(error).unit *> failPending(error) *> close
      case false => ZIO.unit
    }

  private def complete(command: CommandId, result: Either[ConnectionError, Unit]): UIO[Unit] =
    pending.get.flatMap(_.get(command) match
      case Some(response) =>
        response.done(Exit.fromEither(result)).unit *> pending.update(_ - command)
      case None => ZIO.unit)

  private def failPending(error: ConnectionError): UIO[Unit] =
    pending
      .getAndSet(Map.empty).flatMap(values => ZIO.foreachDiscard(values.values)(_.fail(error).unit))

  private def awaitCompletion(command: CommandId): UIO[Unit] =
    pending.get.flatMap(_.get(command).fold[UIO[Unit]](ZIO.unit)(_.await.ignore))

  private def writerFailure(error: SerialWriter.Error): ConnectionError = error match
    case SerialWriter.Error.WriteFailed(cause) => ConnectionError.SinkFailed(cause)
    case other                                 => ConnectionError.OutboundFailed(other.toString)

  private def completeAfterWriterClose(
    command: CommandId,
    error: SerialWriter.Error
  ): UIO[Unit] =
    closing.isDone.flatMap {
      case true  => complete(command, Left(ConnectionError.Closed))
      case false => terminate(writerFailure(error))
    }
end RootConnection

private[scalive] object RootConnection:
  final private case class Event(command: CommandId, binding: BindingId, payload: BindingPayload)

  def start[Msg, Model](
    config: ConnectionConfig,
    metadata: RootConnectionMetadata,
    liveView: LiveView[Msg, Model],
    sink: ConnectionOutput => Task[Unit]
  ): ZIO[Scope, ConnectionError, RootConnection[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        program <- ZIO.acquireRelease(
                     ZIO
                       .fromEither(RenderProgram.compile(liveView.view))
                       .mapError(ConnectionError.RenderCompilationFailed.apply)
                   )(_.close)
        sessionConfig <- ZIO
                           .fromEither(
                             SessionConfig.make(
                               config.kernelMailboxCapacity,
                               config.continuationCapacity
                             )
                           ).mapError(error => ConnectionError.OutboundFailed(error.toString))
        outbound <- InMemoryOutboundReservations
                      .make[SessionOutput](config.outboundReservationCapacity)
                      .mapError(error => ConnectionError.OutboundFailed(error.toString))
        writer <- SerialWriter
                    .make[ConnectionOutput](config.writerCapacity)(sink)
                    .mapError(error => ConnectionError.OutboundFailed(error.toString))
        mountContext   = RootMountContext.connected[Msg, Model](metadata)
        messageContext = RootMessageContext[Msg, Model](metadata)
        logic          = SessionLogic[Msg, Model](
                  bootstrap = ZIO.suspend(liveView.mount(mountContext)).map(TurnDraft(_)),
                  handle = (model, message) =>
                    ZIO
                      .suspend(liveView.handleMessage(model, messageContext)(message)).map(
                        TurnDraft(_)
                      )
                )
        kernel <- SessionKernel
                    .start(sessionConfig, logic, program, outbound)
                    .mapError(ConnectionError.SessionFailed.apply)
        ingress        <- Queue.dropping[Event](config.ingressCapacity)
        ingressGate    <- Semaphore.make(1L)
        pending        <- Ref.make(Map.empty[CommandId, Promise[ConnectionError, Unit]])
        failure        <- Promise.make[Nothing, ConnectionError]
        bootstrapReady <- Promise.make[ConnectionError, Unit]
        closing        <- Promise.make[Nothing, Unit]
        closed         <- Promise.make[Nothing, Unit]
        connection = RootConnection(
                       kernel.epoch,
                       config,
                       kernel,
                       outbound,
                       writer,
                       ingress,
                       ingressGate,
                       pending,
                       failure,
                       bootstrapReady,
                       closing,
                       closed
                     )
        _ <- connection.runOutbound(first = true).interruptible.forkScoped
        _ <- connection.runIngress.interruptible.forkScoped
        _ <- connection.monitorKernel.interruptible.forkScoped
        _ <- ZIO.addFinalizer(connection.close)
        _ <- restore(bootstrapReady.await).onError(_ => connection.close)
      yield connection
    }
end RootConnection
