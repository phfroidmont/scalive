package scalive.runtime.kernel

import java.time.Duration as JavaDuration
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.Queue as ImmutableQueue

import zio.Cause
import zio.Exit
import zio.Promise
import zio.Queue
import zio.Ref
import zio.Scope
import zio.Task
import zio.UIO
import zio.ZIO
import zio.http.URL

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
  outbound: OutboundReservations[SessionOutput],
  mailbox: Queue[SessionKernel.Envelope[Msg, Model]],
  regularQueued: Ref[Int],
  patchAcknowledgementQueued: Ref[Boolean],
  terminal: Promise[Nothing, SessionState[Msg, Model]],
  sessionScope: Scope.Closeable,
  activeOwner: SessionKernel.ActiveRenderOwner,
  shutdown: Promise[Nothing, Unit]):
  import SessionKernel.*

  def submit(command: SessionCommand[Msg]): ZIO[Any, SessionRejection, TurnResult] =
    for
      id     <- ZIO.fromEither(CommandId.fresh()).mapError(SessionRejection.IdentityUnavailable(_))
      result <- submit(id, command)
    yield result

  private[scalive] def submit(
    id: CommandId,
    command: SessionCommand[Msg]
  ): ZIO[Any, SessionRejection, TurnResult] = enqueue(id, command).flatten

  /** Installs a command in FIFO order without forcing its producer to await deferred navigation. */
  private[scalive] def enqueue(
    id: CommandId,
    command: SessionCommand[Msg]
  ): ZIO[Any, SessionRejection, ZIO[Any, SessionRejection, TurnResult]] =
    ZIO.uninterruptible {
      for
        response <- Promise.make[SessionRejection, TurnResult]
        accepted <- offerRegular(Envelope.Execute(id, command, response))
        await    <- if accepted then ZIO.succeed(awaitResponse(response)) else rejectOffer
      yield await
    }

  /** Uses the mailbox slot reserved for the protocol adapter's server-patch acknowledgement. */
  private[scalive] def enqueuePatchAcknowledgement(
    id: CommandId,
    actualEpoch: Epoch,
    destination: URL
  ): ZIO[Any, SessionRejection, ZIO[Any, SessionRejection, TurnResult]] =
    ZIO.uninterruptible {
      for
        response <- Promise.make[SessionRejection, TurnResult]
        accepted <- offerPatchAcknowledgement(
                      Envelope.PatchAcknowledgement(id, actualEpoch, destination, response)
                    )
        await <- if accepted then ZIO.succeed(awaitResponse(response)) else rejectOffer
      yield await
    }

  def inspect: ZIO[Any, SessionRejection, Committed[Msg, Model]] =
    for
      response <- Promise.make[SessionRejection, Committed[Msg, Model]]
      accepted <- offerRegular(Envelope.Inspect(response))
      result   <- if accepted then awaitResponse(response) else rejectOffer
    yield result

  def awaitTermination: UIO[SessionState[Msg, Model]] = terminal.await

  def close: UIO[Unit] =
    shutdown.succeed(()).unit *> terminal.await.unit *> sessionScope.close(Exit.unit)

  private def offerRegular(envelope: Envelope[Msg, Model]): UIO[Boolean] =
    ZIO.uninterruptible {
      terminal.poll.flatMap {
        case Some(_) => ZIO.succeed(false)
        case None    =>
          regularQueued
            .modify { queued =>
              if queued >= config.mailboxCapacity then false -> queued
              else true                                      -> (queued + 1)
            }.flatMap {
              case false => ZIO.succeed(false)
              case true  =>
                offerMailbox(envelope)
                  .tap(accepted => ZIO.unless(accepted)(regularQueued.update(_ - 1)))
            }
      }
    }

  private def offerPatchAcknowledgement(envelope: Envelope[Msg, Model]): UIO[Boolean] =
    ZIO.uninterruptible {
      terminal.poll.flatMap {
        case Some(_) => ZIO.succeed(false)
        case None    =>
          patchAcknowledgementQueued
            .modify {
              case true  => false -> true
              case false => true  -> true
            }.flatMap {
              case false => ZIO.succeed(false)
              case true  =>
                offerMailbox(envelope)
                  .tap(accepted => ZIO.unless(accepted)(patchAcknowledgementQueued.set(false)))
            }
      }
    }

  private def offerMailbox(envelope: Envelope[Msg, Model]): UIO[Boolean] =
    mailbox
      .offer(envelope).foldCauseZIO(
        cause =>
          terminal.poll.flatMap {
            case Some(_) => ZIO.succeed(false)
            case None    => ZIO.failCause(cause)
          },
        ZIO.succeed(_)
      )

  private def takeEnvelope: UIO[Envelope[Msg, Model]] =
    controlled(mailbox.take).tap {
      case Envelope.PatchAcknowledgement(_, _, _, _) => patchAcknowledgementQueued.set(false)
      case _                                         => regularQueued.update(_ - 1)
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
    val bootstrap = phase(SessionStage.BootstrapHandler)(logic.bootstrap).flatMap { draft =>
      draft.navigation match
        case Some(request) if request.kind.isPatch =>
          ZIO.fail(
            SessionFailure.StageFailed(
              SessionStage.Validation,
              s"patch navigation ${request.kind} is unavailable during bootstrap"
            )
          )
        case Some(_) =>
          publishTerminalNavigation(draft, None, existingContinuations = 0).map(Right(_))
        case None =>
          runTurn(
            previous = None,
            draft = ZIO.succeed(draft),
            work = ImmutableQueue.empty,
            command = None
          ).map(Left(_))
    }

    controlled(bootstrap).exit
      .flatMap {
        case Exit.Success(Left(outcome)) =>
          ready.succeed(()).unit *>
            loop(
              SessionState.Active(epoch, outcome.committed),
              outcome.work
            )
        case Exit.Success(Right((_, navigation))) =>
          ready.succeed(()).unit *>
            terminal.succeed(SessionState.Redirected(epoch, navigation)).unit
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          ready.fail(SessionFailure.Interrupted()).unit
        case Exit.Failure(cause) =>
          val failure = failureFrom(cause, SessionStage.BootstrapHandler)
          ready.fail(failure).unit *>
            crash(bootstrapping, failure)
      }.onInterrupt(ready.fail(SessionFailure.Interrupted()).unit)
      .ensuring(cleanupSession)
  end run

  private def loop(
    state: SessionState[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]]
  ): UIO[Unit] =
    (state match
      case active: SessionState.Active[Msg, Model]         => activeLoop(active, work)
      case navigating: SessionState.Navigating[Msg, Model] => navigatingLoop(navigating, work)
      case _                                               => ZIO.unit
    ).onInterrupt(failWork(work, SessionRejection.Terminal("closed")))

  private def activeLoop(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]]
  ): UIO[Unit] =
    work.dequeueOption match
      case Some((Work.Continuation(message), remaining)) =>
        executeTurn(state, remaining, logic.handle(state.committed.model, message), None)
      case Some((Work.Correlated(deferred), remaining)) =>
        executeEnvelope(state, remaining, deferred.command, deferred.input, deferred.response)
      case None =>
        takeEnvelope.flatMap {
          case Envelope.Inspect(response) =>
            response.succeed(state.committed).unit *> loop(state, work)
          case Envelope.Execute(commandId, command, response) =>
            executeEnvelope(state, work, commandId, command, response)
          case Envelope.PatchAcknowledgement(_, _, _, response) =>
            response.fail(SessionRejection.UnexpectedPatch).unit *> loop(state, work)
        }

  private def executeEnvelope(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    commandId: CommandId,
    command: SessionCommand[Msg],
    response: Promise[SessionRejection, TurnResult]
  ): UIO[Unit] =
    command match
      case SessionCommand.ParamsPatch(actualEpoch, destination) =>
        if actualEpoch != state.epoch then
          response.fail(SessionRejection.InvalidEpoch(state.epoch, actualEpoch)).unit *>
            loop(state, work)
        else
          executeTurn(
            state,
            work,
            logic.handleParams(state.committed.model, destination),
            Some(commandId -> response)
          )
      case _ =>
        if command.expectedEpoch != state.epoch then
          response.fail(SessionRejection.InvalidEpoch(state.epoch, command.expectedEpoch)).unit *>
            loop(state, work)
        else
          command match
            case client: SessionCommand.ClientEvent =>
              controlled(
                phase(SessionStage.Handler)(
                  logic.interceptClientEvent(state.committed.model, client)
                )
              ).exit.flatMap {
                case Exit.Success(Some(draft)) =>
                  executeTurn(state, work, ZIO.succeed(draft), Some(commandId -> response))
                case Exit.Success(None) =>
                  resolveClient(state, client) match
                    case Left(rejection) => response.fail(rejection).unit *> loop(state, work)
                    case Right(message)  =>
                      executeTurn(
                        state,
                        work,
                        logic.handleEvent.getOrElse(logic.handle)(state.committed.model, message),
                        Some(commandId -> response)
                      )
                case Exit.Failure(cause) if cause.isInterruptedOnly =>
                  response.fail(SessionRejection.Terminal("closed")).unit
                case Exit.Failure(cause) =>
                  failActiveTurn(
                    state,
                    Some(commandId -> response),
                    failureFrom(cause, SessionStage.Handler)
                  )
              }
            case SessionCommand.Message(_, message) =>
              executeTurn(
                state,
                work,
                logic.handleInfo.getOrElse(logic.handle)(state.committed.model, message),
                Some(commandId -> response)
              )
            case SessionCommand.AsyncCompletion(_, event) =>
              logic.handleAsync match
                case Some(handler) =>
                  executeTurn(
                    state,
                    work,
                    handler(state.committed.model, event),
                    Some(commandId -> response)
                  )
                case None =>
                  event.result match
                    case scalive.LiveAsyncResult.Succeeded(message) =>
                      executeTurn(
                        state,
                        work,
                        logic.handle(state.committed.model, message),
                        Some(commandId -> response)
                      )
                    case _ =>
                      executeTurn(
                        state,
                        work,
                        ZIO.succeed(TurnDraft(state.committed.model)),
                        Some(commandId -> response)
                      )
            case SessionCommand.ParamsPatch(_, _) =>
              response.fail(SessionRejection.UnexpectedPatch).unit *> loop(state, work)

  private def navigatingLoop(
    state: SessionState.Navigating[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]]
  ): UIO[Unit] =
    val pending       = state.pending
    val takeOrTimeout = for
      now <- zio.Clock.instant
      remaining = JavaDuration.between(now, pending.deadline)
      envelope <- if remaining.isZero || remaining.isNegative then ZIO.succeed(None)
                  else
                    takeEnvelope
                      .map(Some(_)).raceFirst(
                        zio.Clock.sleep(zio.Duration.fromJava(remaining)).as(None)
                      )
    yield envelope

    takeOrTimeout.flatMap {
      case None =>
        navigationCrash(state, SessionFailure.NavigationTimedOut(pending.id, pending.destination))
      case Some(Envelope.Inspect(response)) =>
        response.succeed(pending.committed).unit *> loop(state, work)
      case Some(Envelope.Execute(commandId, command, response)) =>
        command match
          case SessionCommand.ParamsPatch(actualEpoch, destination) =>
            if actualEpoch != state.epoch then
              response.fail(SessionRejection.InvalidEpoch(state.epoch, actualEpoch)).unit *>
                loop(state, work)
            else if destination != pending.destination then
              response
                .fail(SessionRejection.MismatchedPatch(pending.destination, destination)).unit *>
                loop(state, work)
            else acknowledgePatch(state, work, commandId, response)
          case _ => deferCommand(state, work, commandId, command, response)
      case Some(Envelope.PatchAcknowledgement(commandId, actualEpoch, destination, response)) =>
        if actualEpoch != state.epoch then
          response.fail(SessionRejection.InvalidEpoch(state.epoch, actualEpoch)).unit *>
            loop(state, work)
        else if destination != pending.destination then
          response
            .fail(SessionRejection.MismatchedPatch(pending.destination, destination)).unit *>
            loop(state, work)
        else acknowledgePatch(state, work, commandId, response)
    }
  end navigatingLoop

  private def deferCommand(
    state: SessionState.Navigating[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    commandId: CommandId,
    command: SessionCommand[Msg],
    response: Promise[SessionRejection, TurnResult]
  ): UIO[Unit] =
    val pending = state.pending
    if pending.deferred.size >= config.navigationDeferredCapacity then
      navigationCrash(
        state,
        SessionFailure.NavigationDeferredOverflow(config.navigationDeferredCapacity),
        Some(response)
      )
    else
      val deferred: DeferredSessionCommand[Msg, Model] =
        DeferredSessionCommand(commandId, command, response)
      loop(
        SessionState.Navigating(epoch, pending.copy(deferred = pending.deferred :+ deferred)),
        work
      )

  private def acknowledgePatch(
    state: SessionState.Navigating[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    commandId: CommandId,
    response: Promise[SessionRejection, TurnResult]
  ): UIO[Unit] =
    val pending     = state.pending
    val replay      = work.enqueueAll(pending.deferred.map(Work.Correlated(_)))
    val paramsDraft =
      phase(SessionStage.Handler)(logic.handleParams(pending.stagedModel, pending.destination))
    controlled(paramsDraft).exit.flatMap {
      case Exit.Success(draft) if draft.navigation.nonEmpty =>
        stageNavigation(
          pending.committed,
          draft,
          work,
          Some(commandId -> response),
          pending.redirectCount + 1,
          pending.deferred
        )
      case Exit.Success(draft) =>
        executePreparedTurn(
          SessionState.Active(epoch, pending.committed),
          replay,
          ZIO.succeed(draft),
          Some(commandId -> response)
        )
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fail(SessionRejection.Terminal("closed")).unit
      case Exit.Failure(cause) =>
        val failure = failureFrom(cause, SessionStage.Handler)
        response.fail(SessionRejection.SessionFailed(failure)).unit *>
          navigationCrash(state, failure)
    }
  end acknowledgePatch

  private def executeTurn(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    draft: Task[TurnDraft[Msg, Model]],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])]
  ): UIO[Unit] =
    controlled(phase(SessionStage.Handler)(draft)).exit.flatMap {
      case Exit.Success(nextDraft) if nextDraft.navigation.nonEmpty =>
        stageNavigation(state.committed, nextDraft, work, response, 0, Vector.empty)
      case Exit.Success(nextDraft) =>
        executePreparedTurn(state, work, ZIO.succeed(nextDraft), response)
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fold[UIO[Unit]](ZIO.unit)(_._2.fail(SessionRejection.Terminal("closed")).unit)
      case Exit.Failure(cause) =>
        failActiveTurn(state, response, failureFrom(cause, SessionStage.Handler))
    }

  private def executePreparedTurn(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    draft: ZIO[Any, SessionFailure, TurnDraft[Msg, Model]],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])]
  ): UIO[Unit] =
    controlled(runTurn(Some(state.committed), draft, work, response.map(_._1))).exit.flatMap {
      case Exit.Success(outcome) =>
        val complete = response.fold[UIO[Unit]](ZIO.unit) { case (command, promise) =>
          promise
            .succeed(
              TurnResult(command, outcome.turn, outcome.committed.revision, outcome.delta)
            ).unit
        }
        complete *> loop(SessionState.Active(epoch, outcome.committed), outcome.work)
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fold[UIO[Unit]](ZIO.unit)(_._2.fail(SessionRejection.Terminal("closed")).unit)
      case Exit.Failure(cause) =>
        failActiveTurn(state, response, failureFrom(cause, SessionStage.Handler))
    }

  private def failActiveTurn(
    state: SessionState.Active[Msg, Model],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])],
    failure: SessionFailure
  ): UIO[Unit] =
    val complete = response.fold[UIO[Unit]](ZIO.unit) { case (_, promise) =>
      promise.fail(SessionRejection.SessionFailed(failure)).unit
    }
    complete *> closeCommitted(state.committed).exit.flatMap { cleanup =>
      crash(state, failure) *> restoreCleanup(Vector(cleanup))
    }

  private def runTurn(
    previous: Option[Committed[Msg, Model]],
    draft: ZIO[Any, SessionFailure, TurnDraft[Msg, Model]],
    work: ImmutableQueue[Work[Msg, Model]],
    command: Option[CommandId]
  ): ZIO[Any, SessionFailure, TurnOutcome[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        turnId    <- restore(identity(TurnId.fresh()))
        nextDraft <- restore(draft)
        candidate <- restore(buildCandidate(turnId, previous, nextDraft, work.size))
        committed <- restore(commit(previous, candidate, work, command)).onExit {
                       case Exit.Success(_) => ZIO.unit
                       case Exit.Failure(_) => candidate.render.stagedScope.closeFromOwner
                     }
      yield TurnOutcome(
        turnId,
        committed.value,
        committed.work,
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
                      finalDraft  <- phase(SessionStage.AfterRender)(logic.afterRender(draft))
                      _           <-
                        validateContinuations(existingContinuations, finalDraft.continuations.size)
                      revision <- identity(TurnRevision.fresh())
                      delta = previous match
                                case Some(committed) =>
                                  TreeDiffer.diff(committed.render.tree, render.tree)
                                case None => TreeDiffer.initial(render.tree)
                    yield TurnCandidate(
                      turnId,
                      revision,
                      finalDraft,
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
  ): ZIO[Any, SessionFailure, OutboundReservation[SessionOutput]] =
    ZIO.uninterruptible {
      reservationPhase(outbound.reserve).flatMap { reservation =>
        candidateScope
          .addFinalizer(reservation.release).onError(_ => reservation.release).as(reservation)
      }
    }

  private def commit(
    previous: Option[Committed[Msg, Model]],
    candidate: TurnCandidate[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    command: Option[CommandId]
  ): ZIO[Any, SessionFailure, CommitResult[Msg, Model]] =
    val commitTail = for
      render <- ZIO.succeed(candidate.render.commit)
      next = Committed(
               candidate.draft.model,
               candidate.draft.url.orElse(previous.map(_.url)).getOrElse(URL.root),
               render,
               candidate.resources,
               candidate.revision
             )
      nextWork = work.enqueueAll(candidate.draft.continuations.map(Work.Continuation(_)))
      _ <- activeOwner.activate(render.scope)
      _ <- candidate.resources.activate
      _ <- ZIO.foreachDiscard(previous)(_.resources.markStale)
      _ <- candidate.reservation.publish(
             OutboundBatch.single(
               SessionOutput(command, candidate.delta, None, candidate.draft.effects)
             )
           )
    yield CommitResult(next, nextWork)

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

  private def resolveClient(
    state: SessionState.Active[Msg, Model],
    command: SessionCommand.ClientEvent
  ): Either[SessionRejection, Msg] =
    state.committed.render.bindings.resolve(command.binding) match
      case None            => Left(SessionRejection.UnknownBinding(command.binding))
      case Some(operation) =>
        operation
          .dispatch(command.payload).left.map(SessionRejection.BindingFailed(command.binding, _))

  private def stageNavigation(
    committed: Committed[Msg, Model],
    draft: TurnDraft[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])],
    redirectCount: Int,
    deferred: Vector[DeferredSessionCommand[Msg, Model]]
  ): UIO[Unit] =
    val request = draft.navigation.get
    if request.kind.isPatch then
      stagePatchNavigation(committed, draft, work, response, redirectCount, deferred)
    else stageTerminalNavigation(committed, draft, work, response, deferred)

  private def stagePatchNavigation(
    committed: Committed[Msg, Model],
    draft: TurnDraft[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])],
    redirectCount: Int,
    deferred: Vector[DeferredSessionCommand[Msg, Model]]
  ): UIO[Unit] =
    val request = draft.navigation.get
    val failure =
      if redirectCount > config.navigationRedirectLimit then
        Some(SessionFailure.NavigationRedirectOverflow(config.navigationRedirectLimit))
      else None

    failure match
      case Some(value) =>
        val active: SessionState.Active[Msg, Model] = SessionState.Active(epoch, committed)
        failActiveTurn(active, response, value) *>
          failDeferred(deferred, SessionRejection.SessionFailed(value))
      case None =>
        val effect = ZIO.uninterruptibleMask { restore =>
          for
            reservation <- restore(reservationPhase(outbound.reserve))
            result      <- (for
                        navigationId <- restore(identity(NavigationId.fresh()))
                        turnId       <- restore(identity(TurnId.fresh()))
                        _   <- restore(validateContinuations(work.size, draft.continuations.size))
                        now <- zio.Clock.instant
                        deadline = now.plus(config.navigationTimeout)
                        output   = NavigationOutput(
                                   navigationId,
                                   request.destination,
                                   request.kind,
                                   request.flash
                                 )
                        pending = PendingNavigation(
                                    navigationId,
                                    committed.url,
                                    request.destination,
                                    request.kind,
                                    committed,
                                    draft.model,
                                    request.flash,
                                    deadline,
                                    redirectCount,
                                    deferred
                                  )
                        nextWork = work.enqueueAll(draft.continuations.map(Work.Continuation(_)))
                        _ <- reservation.publish(
                               OutboundBatch.single(
                                 SessionOutput(
                                   response.map(_._1),
                                   RenderDelta.Empty,
                                   Some(output),
                                   draft.effects
                                 )
                               )
                             )
                      yield (turnId, pending, nextWork)).onError(_ => reservation.release)
          yield result
        }
        controlled(effect).exit.flatMap {
          case Exit.Success((turnId, pending, nextWork)) =>
            val complete = response.fold[UIO[Unit]](ZIO.unit) { case (command, promise) =>
              promise
                .succeed(TurnResult(command, turnId, committed.revision, RenderDelta.Empty)).unit
            }
            complete *> loop(SessionState.Navigating(epoch, pending), nextWork)
          case Exit.Failure(cause) if cause.isInterruptedOnly =>
            response.fold[UIO[Unit]](ZIO.unit)(
              _._2.fail(SessionRejection.Terminal("closed")).unit
            ) *>
              failDeferred(deferred, SessionRejection.Terminal("closed"))
          case Exit.Failure(cause) =>
            val value = failureFrom(cause, SessionStage.OutputReservation)
            failActiveTurn(SessionState.Active(epoch, committed), response, value) *>
              failDeferred(deferred, SessionRejection.SessionFailed(value))
        }
    end match
  end stagePatchNavigation

  private def stageTerminalNavigation(
    committed: Committed[Msg, Model],
    draft: TurnDraft[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    response: Option[(CommandId, Promise[SessionRejection, TurnResult])],
    deferred: Vector[DeferredSessionCommand[Msg, Model]]
  ): UIO[Unit] =
    controlled(
      publishTerminalNavigation(
        draft,
        response.map(_._1),
        work.size
      )
    ).exit.flatMap {
      case Exit.Success((turnId, navigation)) =>
        val complete = response.fold[UIO[Unit]](ZIO.unit) { case (command, promise) =>
          promise
            .succeed(TurnResult(command, turnId, committed.revision, RenderDelta.Empty)).unit
        }
        val rejection = SessionRejection.Terminal("redirected")
        complete *>
          failDeferred(deferred, rejection) *>
          failWork(work, rejection) *>
          terminal.succeed(SessionState.Redirected(epoch, navigation)).unit
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        val rejection = SessionRejection.Terminal("closed")
        response.fold[UIO[Unit]](ZIO.unit)(_._2.fail(rejection).unit) *>
          failDeferred(deferred, rejection) *>
          failWork(work, rejection)
      case Exit.Failure(cause) =>
        val failure = failureFrom(cause, SessionStage.OutputReservation)
        failActiveTurn(SessionState.Active(epoch, committed), response, failure) *>
          failDeferred(deferred, SessionRejection.SessionFailed(failure)) *>
          failWork(work, SessionRejection.SessionFailed(failure))
    }
  end stageTerminalNavigation

  private def publishTerminalNavigation(
    draft: TurnDraft[Msg, Model],
    command: Option[CommandId],
    existingContinuations: Int
  ): ZIO[Any, SessionFailure, (TurnId, NavigationOutput)] =
    val request = draft.navigation.get
    ZIO.uninterruptibleMask { restore =>
      for
        reservation <- restore(reservationPhase(outbound.reserve))
        result      <- (for
                    _ <- restore(
                           validateContinuations(existingContinuations, draft.continuations.size)
                         )
                    navigationId <- restore(identity(NavigationId.fresh()))
                    turnId       <- restore(identity(TurnId.fresh()))
                    navigation = NavigationOutput(
                                   navigationId,
                                   request.destination,
                                   request.kind,
                                   request.flash
                                 )
                    _ <- reservation.publish(
                           OutboundBatch.single(
                             SessionOutput(
                               command,
                               RenderDelta.Empty,
                               Some(navigation),
                               draft.effects
                             )
                           )
                         )
                  yield turnId -> navigation).onError(_ => reservation.release)
      yield result
    }
  end publishTerminalNavigation

  private def navigationCrash(
    state: SessionState.Navigating[Msg, Model],
    failure: SessionFailure,
    additional: Option[Promise[SessionRejection, TurnResult]] = None
  ): UIO[Unit] =
    val rejection = SessionRejection.SessionFailed(failure)
    ZIO.foreachDiscard(additional)(_.fail(rejection).unit) *>
      failDeferred(state.pending.deferred, rejection) *>
      closeCommitted(state.pending.committed).exit.flatMap { cleanup =>
        crash(state, failure) *> restoreCleanup(Vector(cleanup))
      }

  private def failDeferred(
    deferred: Iterable[DeferredSessionCommand[Msg, Model]],
    rejection: SessionRejection
  ): UIO[Unit] =
    ZIO.foreachDiscard(deferred)(_.response.fail(rejection).unit)

  private def failWork(
    work: ImmutableQueue[Work[Msg, Model]],
    rejection: SessionRejection
  ): UIO[Unit] =
    ZIO.foreachDiscard(work) {
      case Work.Correlated(deferred) => deferred.response.fail(rejection).unit
      case Work.Continuation(_)      => ZIO.unit
    }

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
             case Envelope.Execute(_, _, response)                 => response.fail(rejection).unit
             case Envelope.Inspect(response)                       => response.fail(rejection).unit
             case Envelope.PatchAcknowledgement(_, _, _, response) =>
               response.fail(rejection).unit
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
    case PatchAcknowledgement(
      id: CommandId,
      epoch: Epoch,
      destination: URL,
      response: Promise[SessionRejection, TurnResult])
    case Inspect(response: Promise[SessionRejection, Committed[Msg, Model]])

  private enum Work[Msg, Model]:
    case Continuation(message: Msg)
    case Correlated(command: DeferredSessionCommand[Msg, Model])

  final private case class CommitResult[Msg, Model](
    value: Committed[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]])

  final private case class TurnOutcome[Msg, Model](
    turn: TurnId,
    committed: Committed[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
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
    outbound: OutboundReservations[SessionOutput]
  ): ZIO[Scope, SessionFailure, SessionKernel[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        lifecycle <- ZIO
                       .fromEither(LifecycleId.fresh()).mapError(error =>
                         SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                       )
        sessionScope  <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        mailbox       <- Queue.dropping[Envelope[Msg, Model]](config.mailboxCapacity + 1)
        regularQueued <- Ref.make(0)
        patchAcknowledgementQueued <- Ref.make(false)
        terminal                   <- Promise.make[Nothing, SessionState[Msg, Model]]
        ready                      <- Promise.make[SessionFailure, Unit]
        shutdown                   <- Promise.make[Nothing, Unit]
        activeOwner                <- ActiveRenderOwner.make
        kernel = SessionKernel(
                   lifecycle,
                   Epoch.initial,
                   config,
                   logic,
                   renderProgram,
                   outbound,
                   mailbox,
                   regularQueued,
                   patchAcknowledgementQueued,
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
    case SessionState.Redirected(_, _) => "redirected"
    case SessionState.Closing(_, _)    => "closing"
    case SessionState.Crashed(_, _)    => "crashed"
    case SessionState.Closed(_)        => "closed"
end SessionKernel
