package scalive.runtime.kernel

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.Queue as ImmutableQueue

import zio.Cause
import zio.Exit
import zio.Promise
import zio.Queue
import zio.Scope
import zio.Task
import zio.UIO
import zio.ZIO

import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.PreparedResourceRegistry

/** Single-owner, protocol-neutral lifecycle state machine. */
final private[scalive] class SessionKernel[Msg, Model] private (
  val lifecycle: LifecycleId,
  val epoch: Epoch,
  config: SessionConfig,
  logic: SessionLogic[Msg, Model],
  renderProgram: RenderProgram[Model, Msg],
  outbound: OutboundReservations[RenderDelta],
  mailbox: Queue[SessionKernel.Envelope[Msg, Model]],
  terminal: Promise[Nothing, SessionState[Msg, Model]],
  sessionScope: Scope.Closeable,
  activeOwner: SessionKernel.ActiveRenderOwner,
  shutdown: Promise[Nothing, Unit]):
  import SessionKernel.*

  def submit(command: SessionCommand[Msg]): ZIO[Any, SessionRejection, TurnResult] =
    for
      id <- ZIO.fromEither(CommandId.fresh()).mapError(SessionRejection.IdentityUnavailable(_))
      response <- Promise.make[SessionRejection, TurnResult]
      accepted <- offer(Envelope.Execute(id, command, response))
      result   <- if accepted then awaitResponse(response) else rejectOffer
    yield result

  def inspect: ZIO[Any, SessionRejection, Committed[Msg, Model]] =
    for
      response <- Promise.make[SessionRejection, Committed[Msg, Model]]
      accepted <- offer(Envelope.Inspect(response))
      result   <- if accepted then awaitResponse(response) else rejectOffer
    yield result

  def awaitTermination: UIO[SessionState[Msg, Model]] = terminal.await

  def close: UIO[Unit] =
    shutdown.succeed(()).unit *> terminal.await.unit *> sessionScope.close(Exit.unit)

  private def offer(envelope: Envelope[Msg, Model]): UIO[Boolean] =
    terminal.poll.flatMap {
      case Some(_) => ZIO.succeed(false)
      case None    =>
        mailbox
          .offer(envelope).foldCauseZIO(
            cause =>
              terminal.poll.flatMap {
                case Some(_) => ZIO.succeed(false)
                case None    => ZIO.failCause(cause)
              },
            ZIO.succeed(_)
          )
    }

  private def controlled[E, A](effect: ZIO[Any, E, A]): ZIO[Any, E, A] =
    effect.raceFirst(shutdown.await *> ZIO.interrupt)

  private def awaitResponse[A](
    response: Promise[SessionRejection, A]
  ): ZIO[Any, SessionRejection, A] =
    val terminalResponse = terminal.await.flatMap { state =>
      response.poll.flatMap {
        case Some(result) => result
        case None         => ZIO.fail(SessionRejection.Terminal(stateName(state)))
      }
    }
    response.await.raceFirst(terminalResponse)

  private def rejectOffer[A]: ZIO[Any, SessionRejection, A] =
    terminal.poll.flatMap {
      case Some(state) =>
        state.flatMap(value => ZIO.fail(SessionRejection.Terminal(stateName(value))))
      case None => ZIO.fail(SessionRejection.MailboxSaturated(config.mailboxCapacity))
    }

  private[kernel] def run(ready: Promise[SessionFailure, Unit]): UIO[Unit] =
    val bootstrapping: SessionState[Msg, Model] = SessionState.Bootstrapping(epoch)
    val bootstrap                               = runTurn(
      previous = None,
      draft = phase(SessionStage.BootstrapHandler)(logic.bootstrap),
      continuations = ImmutableQueue.empty
    )

    controlled(bootstrap).exit
      .flatMap {
        case Exit.Success(outcome) =>
          ready.succeed(()).unit *>
            loop(
              SessionState.Active(epoch, outcome.committed),
              outcome.continuations
            )
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          ready.fail(SessionFailure.Interrupted()).unit
        case Exit.Failure(cause) =>
          val failure = failureFrom(cause, SessionStage.BootstrapHandler)
          ready.fail(failure).unit *>
            crash(bootstrapping, failure)
      }.onInterrupt(ready.fail(SessionFailure.Interrupted()).unit)
      .ensuring(cleanupSession)

  private def loop(
    state: SessionState.Active[Msg, Model],
    continuations: ImmutableQueue[Msg]
  ): UIO[Unit] =
    continuations.dequeueOption match
      case Some((message, remaining)) =>
        executeTurn(state, remaining, logic.handle(state.committed.model, message), None)
      case None =>
        controlled(mailbox.take).flatMap {
          case Envelope.Inspect(response) =>
            response.succeed(state.committed).unit *> loop(state, continuations)
          case Envelope.Execute(commandId, command, response) =>
            resolve(state, command) match
              case Left(rejection) =>
                response.fail(rejection).unit *> loop(state, continuations)
              case Right(message) =>
                executeTurn(
                  state,
                  continuations,
                  logic.handle(state.committed.model, message),
                  Some(commandId -> response)
                )
        }

  private def executeTurn(
    state: SessionState.Active[Msg, Model],
    continuations: ImmutableQueue[Msg],
    draft: Task[TurnDraft[Msg, Model]],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])]
  ): UIO[Unit] =
    controlled(
      runTurn(Some(state.committed), phase(SessionStage.Handler)(draft), continuations)
    ).exit.flatMap {
      case Exit.Success(outcome) =>
        val complete = response.fold[UIO[Unit]](ZIO.unit) { case (command, promise) =>
          promise
            .succeed(
              TurnResult(command, outcome.turn, outcome.committed.revision, outcome.delta)
            ).unit
        }
        complete *> loop(SessionState.Active(epoch, outcome.committed), outcome.continuations)
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fold[UIO[Unit]](ZIO.unit)(_._2.fail(SessionRejection.Terminal("closed")).unit)
      case Exit.Failure(cause) =>
        val failure  = failureFrom(cause, SessionStage.Handler)
        val complete = response.fold[UIO[Unit]](ZIO.unit) { case (_, promise) =>
          promise.fail(SessionRejection.SessionFailed(failure)).unit
        }
        complete *> closeCommitted(state.committed).exit.flatMap { cleanup =>
          crash(state, failure) *> restoreCleanup(Vector(cleanup))
        }
    }

  private def runTurn(
    previous: Option[Committed[Msg, Model]],
    draft: ZIO[Any, SessionFailure, TurnDraft[Msg, Model]],
    continuations: ImmutableQueue[Msg]
  ): ZIO[Any, SessionFailure, TurnOutcome[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        turnId    <- restore(identity(TurnId.fresh()))
        nextDraft <- restore(draft)
        candidate <- restore(buildCandidate(turnId, previous, nextDraft, continuations.size))
        committed <- restore(commit(previous, candidate, continuations)).onExit {
                       case Exit.Success(_) => ZIO.unit
                       case Exit.Failure(_) => candidate.render.stagedScope.closeFromOwner
                     }
      yield TurnOutcome(
        turnId,
        committed.value,
        committed.continuations,
        candidate.delta
      )
    }

  private def buildCandidate(
    turnId: TurnId,
    previous: Option[Committed[Msg, Model]],
    draft: TurnDraft[Msg, Model],
    existingContinuations: Int
  ): ZIO[Any, SessionFailure, TurnCandidate[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        candidateScope <- CandidateScope.make
        registry       <- PreparedResourceRegistry.make(candidateScope.addFinalizer)
        result         <- restore(
                    for
                      _ <- phase(SessionStage.ResourcePreparation)(logic.prepare(draft, registry))
                      resources <- registry.result
                      render    <- renderPhase(
                                  renderProgram.evaluateIn(
                                    draft.model,
                                    previous.map(_.render),
                                    candidateScope
                                  )
                                )
                      reservation <- reserve(candidateScope)
                      _           <- phase(SessionStage.AfterRender)(logic.afterRender(draft))
                      _ <- validateContinuations(existingContinuations, draft.continuations.size)
                      revision <- identity(TurnRevision.fresh())
                      delta = previous match
                                case Some(committed) =>
                                  TreeDiffer.diff(committed.render.tree, render.tree)
                                case None => TreeDiffer.initial(render.tree)
                    yield TurnCandidate(
                      turnId,
                      revision,
                      draft,
                      render,
                      resources,
                      delta,
                      reservation
                    )
                  ).onExit {
                    case Exit.Success(_) => ZIO.unit
                    case Exit.Failure(_) => candidateScope.closeFromOwner
                  }
      yield result
    }

  private def reserve(
    candidateScope: CandidateScope
  ): ZIO[Any, SessionFailure, OutboundReservation[RenderDelta]] =
    ZIO.uninterruptible {
      reservationPhase(outbound.reserve).flatMap { reservation =>
        candidateScope
          .addFinalizer(reservation.release).onError(_ => reservation.release).as(reservation)
      }
    }

  private def commit(
    previous: Option[Committed[Msg, Model]],
    candidate: TurnCandidate[Msg, Model],
    continuations: ImmutableQueue[Msg]
  ): ZIO[Any, SessionFailure, CommitResult[Msg, Model]] =
    val commitTail = for
      render <- ZIO.succeed(candidate.render.commit)
      next = Committed(
               candidate.draft.model,
               render,
               candidate.resources,
               candidate.revision
             )
      nextContinuations = continuations.enqueueAll(candidate.draft.continuations)
      _ <- activeOwner.activate(render.scope)
      _ <- candidate.resources.activate
      _ <- ZIO.foreachDiscard(previous)(_.resources.markStale)
      _ <- candidate.reservation.publish(OutboundBatch.single(candidate.delta))
    yield CommitResult(next, nextContinuations)

    ZIO.uninterruptible(commitTail.exit).flatMap {
      case Exit.Success(result) =>
        ZIO.uninterruptible(retire(previous)).as(result)
      case Exit.Failure(cause) =>
        val scopes = candidate.render.stagedScope +:
          previous.map(_.render.scope).toVector
        ZIO.foreach(scopes)(_.closeFromOwner.exit).flatMap { cleanup =>
          val cleanupDetails = cleanup.collect { case Exit.Failure(error) => error.prettyPrint }
          val details        = (cause.prettyPrint +: cleanupDetails).mkString("\n")
          ZIO.fail(SessionFailure.CommitDefect(details))
        }
    }
  end commit

  private def retire(
    previous: Option[Committed[Msg, Model]]
  ): ZIO[Any, SessionFailure, Unit] =
    ZIO.foreachDiscard(previous) { committed =>
      committed.render.close.foldCauseZIO(
        cause =>
          ZIO.fail(
            SessionFailure.StageFailed(SessionStage.Retirement, cause.prettyPrint)
          ),
        _ => ZIO.unit
      )
    }

  private def resolve(
    state: SessionState.Active[Msg, Model],
    command: SessionCommand[Msg]
  ): Either[SessionRejection, Msg] =
    if command.expectedEpoch != state.epoch then
      Left(SessionRejection.InvalidEpoch(state.epoch, command.expectedEpoch))
    else
      command match
        case SessionCommand.Message(_, message)              => Right(message)
        case SessionCommand.ClientEvent(_, binding, payload) =>
          state.committed.render.bindings.resolve(binding) match
            case None            => Left(SessionRejection.UnknownBinding(binding))
            case Some(operation) =>
              operation.dispatch(payload).left.map(SessionRejection.BindingFailed(binding, _))

  private def validateContinuations(
    existing: Int,
    added: Int
  ): ZIO[Any, SessionFailure, Unit] =
    val total = existing.toLong + added.toLong
    if total <= config.continuationCapacity.toLong then ZIO.unit
    else
      ZIO.fail(
        SessionFailure.StageFailed(
          SessionStage.Validation,
          s"continuation capacity ${config.continuationCapacity} exceeded by $total entries"
        )
      )

  private def identity[A](
    result: Either[RuntimeIdentityError, A]
  ): ZIO[Any, SessionFailure, A] =
    ZIO
      .fromEither(result).mapError(error =>
        SessionFailure.StageFailed(SessionStage.Identity, error.toString)
      )

  private def phase[A](
    stage: SessionStage
  )(
    effect: Task[A]
  ): ZIO[Any, SessionFailure, A] =
    effect.foldCauseZIO(
      cause =>
        if cause.isInterruptedOnly then ZIO.interrupt
        else ZIO.fail(SessionFailure.StageFailed(stage, cause.prettyPrint)),
      ZIO.succeed(_)
    )

  private def renderPhase[A](
    effect: ZIO[Any, RenderError, A]
  ): ZIO[Any, SessionFailure, A] =
    effect.foldCauseZIO(
      cause =>
        if cause.isInterruptedOnly then ZIO.interrupt
        else ZIO.fail(SessionFailure.StageFailed(SessionStage.Render, cause.prettyPrint)),
      ZIO.succeed(_)
    )

  private def reservationPhase[A](
    effect: ZIO[Any, OutboundReservationError, A]
  ): ZIO[Any, SessionFailure, A] =
    effect.foldCauseZIO(
      cause =>
        if cause.isInterruptedOnly then ZIO.interrupt
        else
          ZIO.fail(
            SessionFailure.StageFailed(SessionStage.OutputReservation, cause.prettyPrint)
          )
      ,
      ZIO.succeed(_)
    )

  private def closeCommitted(committed: Committed[Msg, Model]): UIO[Unit] =
    committed.render.scope.closeFromOwner

  private def crash(
    state: SessionState[Msg, Model],
    failure: SessionFailure
  ): UIO[Unit] =
    ZIO.logWarning(
      s"session lifecycle=${lifecycle.value} epoch=${epoch.value} crashed from ${stateName(state)}: ${failure.getMessage}"
    ) *>
      terminal.succeed(SessionState.Crashed(epoch, failure)).unit

  private def cleanupSession: UIO[Unit] =
    for
      active  <- activeOwner.close.exit
      program <- renderProgram.close.exit
      _       <- closeMailbox
      _       <- restoreCleanup(Vector(active, program))
    yield ()

  private def restoreCleanup(exits: Vector[Exit[Nothing, Unit]]): UIO[Unit] =
    val failures = exits.collect { case Exit.Failure(cause) => cause }
    failures.reduceOption(_ ++ _).fold[UIO[Unit]](ZIO.unit)(ZIO.failCause(_))

  private def closeMailbox: UIO[Unit] =
    for
      _             <- terminal.succeed(SessionState.Closed(epoch))
      terminalState <- terminal.await
      rejection = SessionRejection.Terminal(stateName(terminalState))
      pending <- mailbox.takeAll
      _       <- ZIO.foreachDiscard(pending) {
             case Envelope.Execute(_, _, response) => response.fail(rejection).unit
             case Envelope.Inspect(response)       => response.fail(rejection).unit
           }
      _ <- mailbox.shutdown
    yield ()
end SessionKernel

private[scalive] object SessionKernel:
  private enum Envelope[Msg, Model]:
    case Execute(
      id: CommandId,
      command: SessionCommand[Msg],
      response: Promise[SessionRejection, TurnResult])
    case Inspect(response: Promise[SessionRejection, Committed[Msg, Model]])

  final private case class CommitResult[Msg, Model](
    value: Committed[Msg, Model],
    continuations: ImmutableQueue[Msg])

  final private case class TurnOutcome[Msg, Model](
    turn: TurnId,
    committed: Committed[Msg, Model],
    continuations: ImmutableQueue[Msg],
    delta: RenderDelta)

  final private class ActiveRenderOwner(
    current: AtomicReference[Option[CandidateScope]]):
    def activate(scope: CandidateScope): UIO[Unit] = ZIO.succeed(current.set(Some(scope)))

    def close: UIO[Unit] =
      ZIO.suspendSucceed(
        current.getAndSet(None).fold[UIO[Unit]](ZIO.unit)(_.closeFromOwner)
      )

  private object ActiveRenderOwner:
    def make: UIO[ActiveRenderOwner] =
      ZIO.succeed(ActiveRenderOwner(AtomicReference(Option.empty[CandidateScope])))

  def start[Msg, Model](
    config: SessionConfig,
    logic: SessionLogic[Msg, Model],
    renderProgram: RenderProgram[Model, Msg],
    outbound: OutboundReservations[RenderDelta]
  ): ZIO[Scope, SessionFailure, SessionKernel[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        lifecycle <- ZIO
                       .fromEither(LifecycleId.fresh()).mapError(error =>
                         SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                       )
        sessionScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        mailbox      <- Queue.dropping[Envelope[Msg, Model]](config.mailboxCapacity)
        terminal     <- Promise.make[Nothing, SessionState[Msg, Model]]
        ready        <- Promise.make[SessionFailure, Unit]
        shutdown     <- Promise.make[Nothing, Unit]
        activeOwner  <- ActiveRenderOwner.make
        kernel = SessionKernel(
                   lifecycle,
                   Epoch.initial,
                   config,
                   logic,
                   renderProgram,
                   outbound,
                   mailbox,
                   terminal,
                   sessionScope,
                   activeOwner,
                   shutdown
                 )
        _ <- sessionScope.addFinalizerExit(_ => renderProgram.close)
        _ <- kernel.run(ready).interruptible.forkIn(sessionScope)
        _ <- ZIO.addFinalizer(kernel.close)
        _ <- restore(ready.await).onExit {
               case Exit.Success(_) => ZIO.unit
               case Exit.Failure(_) => kernel.close
             }
      yield kernel
    }

  private def failureFrom(
    cause: Cause[SessionFailure],
    fallback: SessionStage
  ): SessionFailure =
    cause.failureOption.getOrElse(SessionFailure.StageFailed(fallback, cause.prettyPrint))

  private def stateName[Msg, Model](state: SessionState[Msg, Model]): String = state match
    case SessionState.Bootstrapping(_) => "bootstrapping"
    case SessionState.Active(_, _)     => "active"
    case SessionState.Navigating(_, _) => "navigating"
    case SessionState.Closing(_, _)    => "closing"
    case SessionState.Crashed(_, _)    => "crashed"
    case SessionState.Closed(_)        => "closed"
end SessionKernel
