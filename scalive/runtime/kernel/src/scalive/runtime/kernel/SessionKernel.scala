package scalive.runtime.kernel

import java.time.Duration as JavaDuration
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.Queue as ImmutableQueue

import zio.Cause
import zio.Clock
import zio.Exit
import zio.Promise
import zio.Queue
import zio.Ref
import zio.Scope
import zio.Task
import zio.UIO
import zio.ZIO
import zio.http.URL

import scalive.AsyncKey
import scalive.ComponentDispatch
import scalive.ComponentRef
import scalive.LiveAsyncEvent
import scalive.LiveAsyncResult
import scalive.SubscriptionDelivery
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*

/** Single-owner, protocol-neutral lifecycle state machine. */
final private[scalive] class SessionKernel[Msg, Model] private (
  val connection: ConnectionId,
  val lifecycle: LifecycleId,
  val epoch: Epoch,
  config: SessionConfig,
  logic: SessionLogic[Msg, Model],
  renderProgram: RenderProgram[Model, Msg],
  componentEnvironment: ComponentEnvironment[Msg, Model],
  topologyPreparer: NestedTopologyPreparer,
  outbound: OutboundReservations[SessionOutput],
  mailbox: Queue[SessionKernel.Envelope[Msg, Model]],
  regularSlots: Queue[Unit],
  patchAcknowledgementQueued: Ref[Boolean],
  terminal: Promise[Nothing, SessionState[Msg, Model]],
  sessionScope: Scope.Closeable,
  activeOwner: SessionKernel.ActiveRenderOwner,
  shutdown: Promise[Nothing, Unit],
  observer: RuntimeObserver):
  import SessionKernel.*

  def submit(command: SessionCommand[Msg]): ZIO[Any, SessionRejection, TurnResult] =
    for
      id     <- ZIO.fromEither(CommandId.fresh()).mapError(SessionRejection.IdentityUnavailable(_))
      result <- submit(id, command)
    yield result

  private[scalive] def submitComponent[Message](
    component: ComponentInstanceId,
    message: Message
  ): ZIO[Any, SessionRejection, TurnResult] =
    submit(SessionCommand.ComponentMessage(epoch, component, message))

  private[scalive] def submitComponentAsync[Message](
    component: ComponentInstanceId,
    event: LiveAsyncEvent[Message]
  ): ZIO[Any, SessionRejection, TurnResult] =
    submit(
      SessionCommand.ComponentAsyncCompletion(
        epoch,
        component,
        event.asInstanceOf[LiveAsyncEvent[Any]]
      )
    )

  private[scalive] def requestComponentUpdate(
    component: ComponentInstanceId
  ): ZIO[Any, SessionRejection, TurnResult] =
    submit(SessionCommand.ComponentUpdate(epoch, component))

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
          regularSlots.poll.flatMap {
            case None     => ZIO.succeed(false)
            case Some(()) =>
              offerMailbox(envelope)
                .tap(accepted => ZIO.unless(accepted)(regularSlots.offer(()).unit))
          }
      }
    }

  /** Backpressures a runtime-owned producer without weakening external saturation rejection. */
  private def offerOwned(envelope: Envelope[Msg, Model]): UIO[Boolean] =
    takeOwnedSlot.flatMap {
      case true =>
        offerMailbox(envelope)
          .tap(accepted => ZIO.unless(accepted)(regularSlots.offer(()).unit))
      case false => ZIO.succeed(false)
    }

  private def takeOwnedSlot: UIO[Boolean] =
    controlled(regularSlots.take).exit.flatMap {
      case Exit.Success(())                               => ZIO.succeed(true)
      case Exit.Failure(cause) if cause.isInterruptedOnly => ZIO.succeed(false)
      case Exit.Failure(cause)                            => ZIO.failCause(cause)
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
      case Envelope.PatchAcknowledgement(id, actualEpoch, destination, _) =>
        patchAcknowledgementQueued.set(false) *>
          observeAccepted(id, SessionCommand.ParamsPatch(actualEpoch, destination))
      case Envelope.Execute(id, command, _) =>
        regularSlots.offer(()).unit *> observeAccepted(id, command)
      case Envelope.Inspect(_) => regularSlots.offer(()).unit
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

  private def observeAccepted(id: CommandId, command: SessionCommand[Msg]): UIO[Unit] =
    val (kind, initiator) = commandDescriptor(command)
    mailbox.size.flatMap(depth =>
      observer.emit(
        RuntimeEvent.CommandAccepted(
          RuntimeCorrelation(connection, lifecycle, epoch, command = Some(id)),
          kind,
          initiator,
          depth
        )
      )
    )

  private def commandDescriptor(
    command: SessionCommand[Msg]
  ): (RuntimeCommandKind, RuntimeInitiator) = command match
    case _: SessionCommand.ClientEvent =>
      RuntimeCommandKind.ClientEvent -> RuntimeInitiator.Browser
    case _: SessionCommand.ComponentClientEvent =>
      RuntimeCommandKind.ComponentClientEvent -> RuntimeInitiator.Browser
    case _: SessionCommand.Message[?] =>
      RuntimeCommandKind.Message -> RuntimeInitiator.Application
    case _: SessionCommand.AsyncCompletion[?] =>
      RuntimeCommandKind.AsyncCompletion -> RuntimeInitiator.Application
    case _: SessionCommand.ManagedAsync =>
      RuntimeCommandKind.ManagedAsync -> RuntimeInitiator.ManagedResource
    case _: SessionCommand.ManagedSubscription =>
      RuntimeCommandKind.ManagedSubscription -> RuntimeInitiator.ManagedResource
    case _: SessionCommand.ManagedSubscriptionEnded =>
      RuntimeCommandKind.ManagedSubscriptionEnded -> RuntimeInitiator.ManagedResource
    case _: SessionCommand.ComponentMessage =>
      RuntimeCommandKind.ComponentMessage -> RuntimeInitiator.Application
    case _: SessionCommand.ComponentUpdate =>
      RuntimeCommandKind.ComponentUpdate -> RuntimeInitiator.Runtime
    case _: SessionCommand.ComponentAsyncCompletion =>
      RuntimeCommandKind.ComponentAsyncCompletion -> RuntimeInitiator.Application
    case _: SessionCommand.Upload      => RuntimeCommandKind.Upload -> RuntimeInitiator.Browser
    case _: SessionCommand.ParamsPatch =>
      RuntimeCommandKind.ParamsPatch -> RuntimeInitiator.Browser

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
          ).flatMap {
            case StagedTurn.Committed(outcome)    => ZIO.succeed(Left(outcome))
            case StagedTurn.Navigation(candidate) =>
              if candidate.draft.navigation.exists(_.kind.isPatch) then
                discardCandidate(candidate) *>
                  ZIO.fail(
                    SessionFailure.StageFailed(
                      SessionStage.Validation,
                      "patch navigation is unavailable during bootstrap"
                    )
                  )
              else publishCandidateTerminal(candidate, None).map(Right(_))
          }
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
            terminal
              .succeed(SessionState.Redirected(epoch, navigation)).flatMap(claimed =>
                observer
                  .emit(
                    RuntimeEvent.SessionTerminated(
                      RuntimeCorrelation(
                        connection,
                        lifecycle,
                        epoch,
                        navigation = Some(navigation.id)
                      ),
                      RuntimeTerminal.Redirected
                    )
                  ).when(claimed).unit
              )
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
      case navigating: SessionState.Navigating[Msg, Model] =>
        navigatingLoop(navigating, work).onInterrupt(
          ZIO.foreachDiscard(navigating.pending.componentCandidate)(discardCandidate)
        )
      case _ => ZIO.unit
    ).onInterrupt(failWork(work, SessionRejection.Terminal("closed")))

  private def activeLoop(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]]
  ): UIO[Unit] =
    work.dequeueOption match
      case Some((Work.Continuation(message), remaining)) =>
        executeTurn(state, remaining, logic.handle(state.committed.model, message), None)
      case Some((Work.ComponentContinuation(component, message), remaining)) =>
        executeComponentTurn(state, remaining, component, ComponentAction.Message(message), None)
      case Some((Work.ManagedAsyncContinuation(owner, completion), remaining)) =>
        owner match
          case OwnerId.Root(_) => executeManagedRootAsync(state, remaining, completion, None, None)
          case OwnerId.Component(_, component) =>
            executeComponentTurn(
              state,
              remaining,
              component,
              ComponentAction.ManagedAsync(completion),
              None
            )
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
    val tracked = trackedCommand(commandId, command, response)
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
            Some(tracked)
          )
      case _ =>
        if command.expectedEpoch != state.epoch then
          response.fail(SessionRejection.InvalidEpoch(state.epoch, command.expectedEpoch)).unit *>
            loop(state, work)
        else
          command match
            case client: SessionCommand.ClientEvent =>
              val intercepted = client.event match
                case Some(event) => logic.interceptClientEvent(state.committed.model, event)
                case None        =>
                  ZIO.succeed(ClientEventInterception.Continue(TurnDraft(state.committed.model)))
              controlled(phase(SessionStage.Handler)(intercepted)).exit.flatMap {
                case Exit.Success(ClientEventInterception.Halt(draft)) =>
                  executeTurn(state, work, ZIO.succeed(draft), Some(tracked))
                case Exit.Success(ClientEventInterception.Continue(draft)) =>
                  resolveClient(state, client) match
                    case Left(rejection) => response.fail(rejection).unit *> loop(state, work)
                    case Right(ResolvedClient.Root(message)) =>
                      val handled = (logic.handleEvent, client.event) match
                        case (Some(handler), Some(event)) => handler(draft, message, event)
                        case _                            => logic.handle(draft.model, message)
                      executeTurn(state, work, handled, Some(tracked))
                    case Right(ResolvedClient.Component(component, message)) =>
                      executeComponentTurn(
                        state,
                        work,
                        component,
                        ComponentAction.Message(message),
                        Some(tracked),
                        initialDraft = Some(draft)
                      )
                case Exit.Failure(cause) if cause.isInterruptedOnly =>
                  response.fail(SessionRejection.Terminal("closed")).unit
                case Exit.Failure(cause) =>
                  failActiveTurn(
                    state,
                    Some(tracked),
                    failureFrom(cause, SessionStage.Handler)
                  )
              }
            case SessionCommand.Message(_, message) =>
              executeTurn(
                state,
                work,
                logic.handleInfo.getOrElse(logic.handle)(state.committed.model, message),
                Some(tracked)
              )
            case SessionCommand.AsyncCompletion(_, event) =>
              logic.handleAsync match
                case Some(handler) =>
                  executeTurn(
                    state,
                    work,
                    handler(state.committed.model, event),
                    Some(tracked)
                  )
                case None =>
                  event.result match
                    case scalive.LiveAsyncResult.Succeeded(message) =>
                      executeTurn(
                        state,
                        work,
                        logic.handle(state.committed.model, message),
                        Some(tracked)
                      )
                    case _ =>
                      executeTurn(
                        state,
                        work,
                        ZIO.succeed(TurnDraft(state.committed.model)),
                        Some(tracked)
                      )
            case SessionCommand.ManagedAsync(_, token, result) =>
              state.committed.managedResources.current(token) match
                case Some(ManagedResource(_, _, ManagedResourceKind.Async(mapResult))) =>
                  controlled(phase(SessionStage.Handler)(ZIO.attempt(mapResult(result)))).exit
                    .flatMap {
                      case Exit.Success(message) =>
                        val name = token.key match
                          case ResourceKey.Async(value) => value
                          case _                        => ""
                        val completion = ManagedAsyncCompletion(name, result, message)
                        token.owner match
                          case OwnerId.Root(_) =>
                            executeManagedRootAsync(
                              state,
                              work,
                              completion,
                              Some(token),
                              Some(tracked)
                            )
                          case OwnerId.Component(_, component) =>
                            executeComponentTurn(
                              state,
                              work,
                              component,
                              ComponentAction.ManagedAsync(completion),
                              Some(tracked),
                              Vector(ResourceOperation.Complete(token))
                            )
                      case Exit.Failure(cause) if cause.isInterruptedOnly =>
                        response.fail(SessionRejection.Terminal("closed")).unit
                      case Exit.Failure(cause) =>
                        failActiveTurn(
                          state,
                          Some(tracked),
                          failureFrom(cause, SessionStage.Handler)
                        )
                    }
                case _ =>
                  response.fail(SessionRejection.StaleResource(token)).unit *> loop(state, work)
            case SessionCommand.ManagedSubscription(_, token, message) =>
              state.committed.managedResources.current(token) match
                case Some(ManagedResource(_, _, ManagedResourceKind.Subscription(_))) =>
                  token.owner match
                    case OwnerId.Root(_) =>
                      executeTurn(
                        state,
                        work,
                        logic.handleInfo.getOrElse(logic.handle)(
                          state.committed.model,
                          message.asInstanceOf[Msg]
                        ),
                        Some(tracked)
                      )
                    case OwnerId.Component(_, _) =>
                      response.fail(SessionRejection.StaleResource(token)).unit *>
                        loop(state, work)
                case _ =>
                  response.fail(SessionRejection.StaleResource(token)).unit *> loop(state, work)
            case SessionCommand.ManagedSubscriptionEnded(_, token) =>
              state.committed.managedResources.current(token) match
                case Some(ManagedResource(_, _, ManagedResourceKind.Subscription(_))) =>
                  executeTurn(
                    state,
                    work,
                    ZIO.succeed(
                      TurnDraft(
                        state.committed.model,
                        resourceOperations = Vector(ResourceOperation.Complete(token))
                      )
                    ),
                    Some(tracked)
                  )
                case _ =>
                  response.fail(SessionRejection.StaleResource(token)).unit *> loop(state, work)
            case SessionCommand.ComponentMessage(_, component, message) =>
              executeComponentTurn(
                state,
                work,
                component,
                ComponentAction.Message(message),
                Some(tracked)
              )
            case SessionCommand.ComponentAsyncCompletion(_, component, event) =>
              executeComponentTurn(
                state,
                work,
                component,
                ComponentAction.Async(event),
                Some(tracked)
              )
            case SessionCommand.ComponentUpdate(_, component) =>
              executeComponentTurn(
                state,
                work,
                component,
                ComponentAction.Update,
                Some(tracked)
              )
            case SessionCommand.Upload(_, uploadCommand, mutation) =>
              logic.handleUpload match
                case Some(handler) =>
                  executeTurn(
                    state,
                    work,
                    handler(state.committed.model, uploadCommand, mutation),
                    Some(tracked)
                  )
                case None =>
                  response.fail(SessionRejection.UploadUnavailable).unit *> loop(state, work)
            case client: SessionCommand.ComponentClientEvent =>
              val intercepted = client.event match
                case Some(event) => logic.interceptClientEvent(state.committed.model, event)
                case None        =>
                  ZIO.succeed(ClientEventInterception.Continue(TurnDraft(state.committed.model)))
              controlled(phase(SessionStage.Handler)(intercepted)).exit.flatMap {
                case Exit.Success(ClientEventInterception.Halt(draft)) =>
                  executeTurn(state, work, ZIO.succeed(draft), Some(tracked))
                case Exit.Success(ClientEventInterception.Continue(draft)) =>
                  executeComponentTurn(
                    state,
                    work,
                    client.component,
                    ComponentAction.Browser(client),
                    Some(tracked),
                    initialDraft = Some(draft)
                  )
                case Exit.Failure(cause) if cause.isInterruptedOnly =>
                  response.fail(SessionRejection.Terminal("closed")).unit
                case Exit.Failure(cause) =>
                  failActiveTurn(
                    state,
                    Some(tracked),
                    failureFrom(cause, SessionStage.Handler)
                  )
              }
            case SessionCommand.ParamsPatch(_, _) =>
              response.fail(SessionRejection.UnexpectedPatch).unit *> loop(state, work)
    end match
  end executeEnvelope

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
    val pending = state.pending
    pending.componentCandidate match
      case Some(candidate) =>
        controlled(commit(Some(pending.committed), candidate, work, None, publish = false)).exit
          .flatMap {
            case Exit.Success(interim) =>
              continuePatchAcknowledgement(
                state,
                interim.value,
                interim.work,
                commandId,
                response,
                Some(pending.committed.render)
              )
            case Exit.Failure(cause) if cause.isInterruptedOnly =>
              response.fail(SessionRejection.Terminal("closed")).unit
            case Exit.Failure(cause) =>
              val failure = failureFrom(cause, SessionStage.Handler)
              val reject  = response.fail(SessionRejection.SessionFailed(failure)).unit *>
                failDeferred(state.pending.deferred, SessionRejection.SessionFailed(failure))
              failure match
                case _: SessionFailure.CommitDefect => reject *> crash(state, failure)
                case _                              => reject *> navigationCrash(state, failure)
          }
      case None =>
        continuePatchAcknowledgement(
          state,
          pending.committed,
          work,
          commandId,
          response,
          None
        )
    end match
  end acknowledgePatch

  private def continuePatchAcknowledgement(
    state: SessionState.Navigating[Msg, Model],
    base: Committed[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    commandId: CommandId,
    response: Promise[SessionRejection, TurnResult],
    diffBaseline: Option[CommittedRender[Msg]]
  ): UIO[Unit] =
    val pending = state.pending
    val replay  = work.enqueueAll(pending.deferred.map(Work.Correlated(_)))
    val tracked = trackedCommand(
      commandId,
      SessionCommand.ParamsPatch(state.epoch, pending.destination),
      response
    )
    val stagedModel =
      if pending.componentCandidate.nonEmpty then base.model else pending.stagedModel
    val paramsDraft = phase(SessionStage.Handler)(
      logic.handleParams(stagedModel, pending.destination)
    )
    controlled(paramsDraft).exit.flatMap {
      case Exit.Success(draft) if draft.navigation.nonEmpty =>
        stageNavigation(
          base,
          draft,
          work,
          Some(tracked),
          pending.redirectCount + 1,
          pending.deferred
        )
      case Exit.Success(draft) =>
        executePreparedTurn(
          SessionState.Active(epoch, base),
          replay,
          ZIO.succeed(draft),
          Some(tracked),
          diffBaseline
        )
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fail(SessionRejection.Terminal("closed")).unit
      case Exit.Failure(cause) =>
        val failure = failureFrom(cause, SessionStage.Handler)
        response.fail(SessionRejection.SessionFailed(failure)).unit *>
          navigationCrash(state, failure)
    }
  end continuePatchAcknowledgement

  private def trackedCommand(
    id: CommandId,
    command: SessionCommand[Msg],
    response: Promise[SessionRejection, TurnResult]
  ): TrackedCommand =
    val (kind, initiator) = commandDescriptor(command)
    TrackedCommand(id, kind, initiator, response)

  private def executeTurn(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    draft: Task[TurnDraft[Msg, Model]],
    response: Option[TrackedCommand]
  ): UIO[Unit] =
    controlled(phase(SessionStage.Handler)(draft)).exit.flatMap {
      case Exit.Success(nextDraft) if nextDraft.navigation.nonEmpty =>
        stageNavigation(state.committed, nextDraft, work, response, 0, Vector.empty)
      case Exit.Success(nextDraft) =>
        executePreparedTurn(state, work, ZIO.succeed(nextDraft), response)
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fold[UIO[Unit]](ZIO.unit)(
          _.response.fail(SessionRejection.Terminal("closed")).unit
        )
      case Exit.Failure(cause) =>
        failActiveTurn(state, response, failureFrom(cause, SessionStage.Handler))
    }

  private def executeComponentTurn(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    component: ComponentInstanceId,
    action: ComponentAction,
    response: Option[TrackedCommand],
    resourceOperations: Vector[ResourceOperation] = Vector.empty,
    initialDraft: Option[TurnDraft[Msg, Model]] = None
  ): UIO[Unit] =
    state.committed.components.get(component) match
      case None =>
        val rejection = SessionRejection.StaleComponent(component)
        response.fold[UIO[Unit]](ZIO.unit)(_.response.fail(rejection).unit) *> loop(state, work)
      case Some(_) =>
        runTurn(
          Some(state.committed),
          ZIO.succeed(
            initialDraft.fold(
              TurnDraft(state.committed.model, resourceOperations = resourceOperations)
            )(draft =>
              draft.copy(resourceOperations = draft.resourceOperations ++ resourceOperations)
            )
          ),
          work,
          response,
          Some(component -> action)
        ).exit.flatMap {
          case Exit.Success(result) =>
            finishStagedTurn(state, work, result, response)
          case Exit.Failure(cause) if cause.isInterruptedOnly =>
            response.fold[UIO[Unit]](ZIO.unit)(
              _.response.fail(SessionRejection.Terminal("closed")).unit
            )
          case Exit.Failure(cause) =>
            failActiveTurn(state, response, failureFrom(cause, SessionStage.ComponentMessage))
        }

  private def executeManagedRootAsync(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    completion: ManagedAsyncCompletion,
    token: Option[ResourceToken],
    response: Option[TrackedCommand]
  ): UIO[Unit] =
    val message                      = completion.message.asInstanceOf[Msg]
    val result: LiveAsyncResult[Msg] = completion.result match
      case LiveAsyncResult.Succeeded(_)      => LiveAsyncResult.Succeeded(message)
      case LiveAsyncResult.Failed(cause)     => LiveAsyncResult.Failed(cause)
      case LiveAsyncResult.Cancelled(reason) => LiveAsyncResult.Cancelled(reason)
    val event = LiveAsyncEvent(AsyncKey[Any](completion.name), result)
    val next  = logic.handleManagedAsync match
      case Some(handler) => handler(state.committed.model, event, message)
      case None          => logic.handle(state.committed.model, message)
    val operations = token.toVector.map(ResourceOperation.Complete(_))
    executeTurn(
      state,
      work,
      next.map(draft => draft.copy(resourceOperations = draft.resourceOperations ++ operations)),
      response
    )

  private def executePreparedTurn(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    draft: ZIO[Any, SessionFailure, TurnDraft[Msg, Model]],
    response: Option[TrackedCommand],
    diffBaseline: Option[CommittedRender[Msg]] = None
  ): UIO[Unit] =
    runTurn(
      Some(state.committed),
      draft,
      work,
      response,
      diffBaseline = diffBaseline
    ).exit.flatMap {
      case Exit.Success(result) =>
        finishStagedTurn(state, work, result, response)
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        response.fold[UIO[Unit]](ZIO.unit)(
          _.response.fail(SessionRejection.Terminal("closed")).unit
        )
      case Exit.Failure(cause) =>
        failActiveTurn(state, response, failureFrom(cause, SessionStage.Handler))
    }

  private def finishStagedTurn(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    result: StagedTurn[Msg, Model],
    response: Option[TrackedCommand]
  ): UIO[Unit] = result match
    case StagedTurn.Committed(outcome) =>
      val complete = response.fold[UIO[Unit]](ZIO.unit) { command =>
        command.response
          .succeed(
            TurnResult(command.id, outcome.turn, outcome.committed.revision, outcome.delta)
          ).unit
      }
      complete *> loop(SessionState.Active(epoch, outcome.committed), outcome.work)
    case StagedTurn.Navigation(candidate) =>
      stageCandidateNavigation(state, work, candidate, response)

  private def failActiveTurn(
    state: SessionState.Active[Msg, Model],
    response: Option[TrackedCommand],
    failure: SessionFailure
  ): UIO[Unit] =
    val complete = response.fold[UIO[Unit]](ZIO.unit)(
      _.response.fail(SessionRejection.SessionFailed(failure)).unit
    )
    val close = failure match
      case _: SessionFailure.CommitDefect => ZIO.unit
      case _                              => ZIO.uninterruptible(activeOwner.close)
    val correlation = RuntimeCorrelation(
      connection,
      lifecycle,
      epoch,
      command = response.map(_.id)
    )
    observer.emit(RuntimeEvent.TurnFailed(correlation, runtimeFailure(failure))) *>
      complete *> close.exit.flatMap { cleanup =>
        crash(state, failure) *> restoreCleanup(Vector(cleanup))
      }

  private def runTurn(
    previous: Option[Committed[Msg, Model]],
    draft: ZIO[Any, SessionFailure, TurnDraft[Msg, Model]],
    work: ImmutableQueue[Work[Msg, Model]],
    command: Option[TrackedCommand],
    componentAction: Option[(ComponentInstanceId, ComponentAction)] = None,
    diffBaseline: Option[CommittedRender[Msg]] = None
  ): ZIO[Any, SessionFailure, StagedTurn[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        turnId <- restore(identity(TurnId.fresh()))
        descriptor = command.fold(
                       RuntimeCommandKind.Internal -> RuntimeInitiator.Runtime
                     )(value => value.kind -> value.initiator)
        commandId       = command.map(_.id)
        turnCorrelation = RuntimeCorrelation(
                            connection,
                            lifecycle,
                            epoch,
                            command = commandId,
                            turn = Some(turnId)
                          )
        _ <- observer.emit(
               RuntimeEvent.TurnStarted(turnCorrelation, descriptor._1, descriptor._2)
             )
        handlerStarted <- Clock.nanoTime
        nextDraft      <- restore(draft)
        handlerEnded   <- Clock.nanoTime
        _              <- observer.emit(
               RuntimeEvent.HandlerCompleted(
                 turnCorrelation,
                 math.max(0L, handlerEnded - handlerStarted)
               )
             )
        _         <- observer.emit(RuntimeEvent.CandidateRenderStarted(turnCorrelation))
        candidate <- restore(
                       controlled(
                         buildCandidate(
                           turnId,
                           previous,
                           nextDraft,
                           work.size,
                           componentAction,
                           diffBaseline,
                           commandId
                         )
                       )
                     )
        result <-
          if candidate.draft.navigation.nonEmpty then ZIO.succeed(StagedTurn.Navigation(candidate))
          else
            commit(previous, candidate, work, commandId)
              .map(committed =>
                StagedTurn.Committed(
                  TurnOutcome(turnId, committed.value, committed.work, candidate.delta)
                )
              )
      yield result
    }

  private def buildCandidate(
    turnId: TurnId,
    previous: Option[Committed[Msg, Model]],
    draft: TurnDraft[Msg, Model],
    existingContinuations: Int,
    componentAction: Option[(ComponentInstanceId, ComponentAction)],
    diffBaseline: Option[CommittedRender[Msg]],
    command: Option[CommandId]
  ): ZIO[Any, SessionFailure, TurnCandidate[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        auxiliaryScope <- CandidateScope.make
        registry       <- PreparedResourceRegistry.make(auxiliaryScope.addFinalizer)
        componentOwner <- Ref.make(Option.empty[Ref[Vector[StagedComponent[Msg]]]])
        uploadRollback <- Ref.make(draft.uploadRollback)
        result         <- restore(
                    for
                      discoveryScope <- CandidateScope.make
                      discovery      <- renderPhase(
                                     renderProgram.evaluateIn(
                                       draft.model,
                                       previous.map(_.render),
                                       discoveryScope
                                     )
                                   )
                      componentResult <- stageComponents(
                                           discovery.componentRequirements,
                                           previous
                                             .map(_.components).getOrElse(
                                               ComponentForest.empty
                                                 .asInstanceOf[ComponentForest[Msg]]
                                             ),
                                           componentAction,
                                           draft
                                         )
                      _          <- componentOwner.set(Some(componentResult.staged))
                      stabilized <- stabilizeRootGraph(
                                      discovery,
                                      draft,
                                      previous
                                        .map(_.components).getOrElse(
                                          ComponentForest.empty.asInstanceOf[ComponentForest[Msg]]
                                        ),
                                      previous.map(_.render),
                                      componentResult,
                                      config.navigationRedirectLimit
                                    )
                      rootRender      = stabilized._1
                      finalComponents = stabilized._2
                      revision  <- identity(TurnRevision.fresh())
                      finalized <- prepareNestedTopology(
                                     rootRender,
                                     finalComponents,
                                     revision,
                                     auxiliaryScope
                                   )
                      render           = finalized._1
                      nestedComponents = finalized._2
                      topology         = finalized._3
                      _ <- uploadRollback.set(nestedComponents.draft.uploadRollback)
                      finalComponentIds = nestedComponents.forest.components.map(_.id).toSet
                      reconciledDraft <- phase(SessionStage.Validation)(
                                           logic.reconcileUploads(
                                             nestedComponents.draft,
                                             finalComponentIds
                                           )
                                         ).tap(next => uploadRollback.set(next.uploadRollback))
                      _ <- ZIO.foreachDiscard(nestedComponents.forest.components) { component =>
                             phase(SessionStage.Validation)(
                               componentEnvironment.validateStreams[Any](
                                 component.id,
                                 component.environmentState,
                                 component.renderCandidate.streamRequirements
                               )
                             )
                           }
                      _ <- phase(SessionStage.Validation)(
                             logic.validateStreams(
                               reconciledDraft.model,
                               render.streamRequirements
                             )
                           )
                      _ <- phase(SessionStage.ResourcePreparation)(
                             logic.prepare(reconciledDraft, registry)
                           )
                      resources <- registry.result
                      managed   <- prepareManagedResources(
                                   previous
                                     .map(_.managedResources).getOrElse(
                                       ResourceIndex.empty[ManagedResource]
                                     ),
                                   reconciledDraft.resourceOperations,
                                   nestedComponents.forest.components.map(_.id).toSet,
                                   auxiliaryScope
                                 )
                      reservation <- reserve(auxiliaryScope)
                      finalDraft  <- phase(SessionStage.AfterRender)(
                                      logic.afterRender(reconciledDraft)
                                    ).tap(next => uploadRollback.set(next.uploadRollback))
                      _ <- validateContinuations(
                             existingContinuations,
                             finalDraft.continuations.size + nestedComponents.outputs.size +
                               managed.continuations.size
                           )
                      correlation = RuntimeCorrelation(
                                      connection,
                                      lifecycle,
                                      epoch,
                                      command = command,
                                      turn = Some(turnId),
                                      revision = Some(revision)
                                    )
                      _             <- observer.emit(RuntimeEvent.CandidateValidated(correlation))
                      rollbackClaim <- Ref.make(true)
                      delta = diffBaseline.orElse(previous.map(_.render)) match
                                case Some(committed) => TreeDiffer.diff(committed.tree, render.tree)
                                case None            => TreeDiffer.initial(render.tree)
                      _ <- observer.emit(RuntimeEvent.DiffCompleted(correlation))
                    yield TurnCandidate(
                      turnId,
                      revision,
                      finalDraft,
                      render,
                      nestedComponents.forest,
                      nestedComponents.outputs,
                      topology,
                      resources,
                      managed.index,
                      managed.activations,
                      managed.retirements,
                      managed.continuations,
                      delta,
                      reservation,
                      auxiliaryScope,
                      finalDraft.uploadRollback,
                      rollbackClaim
                    )
                  ).onExit {
                    case Exit.Success(_) => ZIO.unit
                    case Exit.Failure(_) =>
                      uploadRollback.get.flatMap(retireUploads) *>
                        componentOwner.get.flatMap(
                          ZIO.foreachDiscard(_)(owner =>
                            owner.get.flatMap(ZIO.foreachDiscard(_)(_.discard))
                          )
                        ) *>
                        auxiliaryScope.closeFromOwner
                  }
      yield result
    }

  final private case class StagedComponentsResult(
    forest: ComponentForestCandidate[Msg],
    resolutions: Vector[ComponentResolution],
    outputs: Vector[ComponentOutput[Msg]],
    draft: TurnDraft[Msg, Model],
    staged: Ref[Vector[StagedComponent[Msg]]],
    identities: Ref[Set[ComponentKey]],
    outputRef: Ref[Vector[ComponentOutput[Msg]]],
    draftRef: Ref[TurnDraft[Msg, Model]],
    updates: Ref[Map[ComponentKey, ComponentUpdateRequest]])

  private def stageComponents(
    requirements: Vector[ComponentRequirement[Msg]],
    previous: ComponentForest[Msg],
    action: Option[(ComponentInstanceId, ComponentAction)],
    initialDraft: TurnDraft[Msg, Model]
  ): ZIO[Any, SessionFailure, StagedComponentsResult] =
    for
      identities <- Ref.make(Set.empty[ComponentKey])
      outputs    <- Ref.make(Vector.empty[ComponentOutput[Msg]])
      staged     <- Ref.make(Vector.empty[StagedComponent[Msg]])
      draftRef   <- Ref.make(initialDraft)
      updates    <- Ref.make(lastComponentUpdates(initialDraft.componentUpdates))
      roots      <- stageRequirements(
                 requirements,
                 None,
                 previous,
                 action,
                 identities,
                 outputs,
                 staged,
                 draftRef,
                 updates
               )
                 .onError(_ => staged.get.flatMap(values => ZIO.foreachDiscard(values)(_.discard)))
      values       <- staged.get
      emitted      <- outputs.get
      resultDraft  <- draftRef.get
      finalUpdates <- updates.get
      _            <- ZIO
             .foreachDiscard(values) { value =>
               finalUpdates.get(value.key) match
                 case Some(request) if !value.appliedUpdate.exists(_ eq request) =>
                   ZIO.fail(
                     SessionFailure.StageFailed(
                       SessionStage.Validation,
                       s"component update journal changed after '${value.key.applicationId}' was staged"
                     )
                   )
                 case _ => ZIO.unit
             }.onError(_ => ZIO.foreachDiscard(values)(_.discard))
      resolutions = roots.map(_._2)
      forest      = ComponentForestCandidate(roots.map(_._1), values, resolutions)
    yield StagedComponentsResult(
      forest,
      resolutions,
      emitted,
      resultDraft.copy(componentUpdates = Vector.empty),
      staged,
      identities,
      outputs,
      draftRef,
      updates
    )

  private def stabilizeRootGraph(
    currentRender: RenderCandidate[Msg],
    renderedDraft: TurnDraft[Msg, Model],
    previous: ComponentForest[Msg],
    previousRender: Option[CommittedRender[Msg]],
    staging: StagedComponentsResult,
    remaining: Int
  ): ZIO[Any, SessionFailure, (RenderCandidate[Msg], StagedComponentsResult)] =
    staging.draftRef.get.flatMap { currentDraft =>
      if currentDraft == renderedDraft then
        snapshotStagedComponents(staging).map(currentRender -> _)
      else if remaining <= 0 then
        currentRender.discard *>
          ZIO.fail(
            SessionFailure.StageFailed(
              SessionStage.Validation,
              "root component graph did not stabilize"
            )
          )
      else
        currentRender.discard *>
          CandidateScope.make.flatMap { scope =>
            renderPhase(
              renderProgram.evaluateIn(currentDraft.model, previousRender, scope)
            ).onError(_ => scope.closeFromOwner).flatMap { nextRender =>
              reconcileRootRequirements(nextRender.componentRequirements, previous, staging)
                .onError(_ => nextRender.discard).flatMap { reconciled =>
                  stabilizeRootGraph(
                    nextRender,
                    currentDraft,
                    previous,
                    previousRender,
                    reconciled,
                    remaining - 1
                  )
                }
            }
          }
    }

  private def reconcileRootRequirements(
    requirements: Vector[ComponentRequirement[Msg]],
    previous: ComponentForest[Msg],
    staging: StagedComponentsResult
  ): ZIO[Any, SessionFailure, StagedComponentsResult] =
    for
      values <- staging.staged.get
      roots       = values.filter(_.parent.isEmpty)
      retainedIds =
        requirements.flatMap(requirement => roots.find(_.matches(requirement)).map(_.id)).toSet
      removedIds = roots.iterator.map(_.id).filterNot(retainedIds).toSet
      _          <- removeStagedComponents(removedIds, staging.staged, staging.identities)
      reconciled <- ZIO.foreach(requirements) { requirement =>
                      staging.staged.get.flatMap { current =>
                        current.find(component =>
                          component.parent.isEmpty && component.matches(requirement)
                        ) match
                          case Some(component) =>
                            ZIO.succeed(component.id -> component.resolutionFor(requirement))
                          case None =>
                            stageComponent(
                              requirement,
                              None,
                              previous,
                              None,
                              staging.identities,
                              staging.outputRef,
                              staging.staged,
                              staging.draftRef,
                              staging.updates
                            )
                      }
                    }
      snapshot <- snapshotStagedComponents(
                    staging.copy(
                      forest = staging.forest.copy(roots = reconciled.map(_._1)),
                      resolutions = reconciled.map(_._2)
                    )
                  )
    yield snapshot

  private def snapshotStagedComponents(
    staging: StagedComponentsResult
  ): UIO[StagedComponentsResult] =
    for
      values  <- staging.staged.get
      outputs <- staging.outputRef.get
      draft   <- staging.draftRef.get
    yield staging.copy(
      forest = ComponentForestCandidate(staging.forest.roots, values, staging.resolutions),
      outputs = outputs,
      draft = draft.copy(componentUpdates = Vector.empty)
    )

  private def prepareNestedTopology(
    root: RenderCandidate[Msg],
    components: StagedComponentsResult,
    revision: TurnRevision,
    auxiliaryScope: CandidateScope
  ): ZIO[
    Any,
    SessionFailure,
    (RenderCandidate[Msg], StagedComponentsResult, PreparedNestedTopology)
  ] =
    val staged       = components.forest.components
    val renderOwners = root.asInstanceOf[RenderCandidate[Any]] +: staged.map(_.renderCandidate)
    val requirements = renderOwners.flatMap(_.nestedRequirements)
    val lifecycleRequirements = requirements.map { requirement =>
      NestedLifecycleRequirement(
        requirement.applicationId,
        requirement.sticky,
        requirement.linkParentOnCrash,
        NestedLifecycleFactory(() => requirement.create())
      )
    }
    val failed = (details: String) =>
      SessionFailure.StageFailed(SessionStage.TopologyPreparation, details)

    for
      prepared <- topologyPreparer
                    .prepare(lifecycle, epoch, revision, lifecycleRequirements)
                    .mapError(error => failed(error.toString))
      _ <- ZIO.uninterruptible(
             auxiliaryScope
               .addFinalizer(prepared.release).onError(_ => prepared.release)
           )
      _ <-
        ZIO
          .fail(
            failed(
              s"nested topology returned ${prepared.resolutions.size} resolutions for ${requirements.size} requirements"
            )
          ).unless(prepared.resolutions.size == requirements.size)
      renderResolutions <-
        ZIO.foreach(requirements.zip(prepared.resolutions)) { case (requirement, resolution) =>
          if resolution.applicationId != requirement.applicationId then
            ZIO.fail(
              failed(
                s"nested application id mismatch: expected '${requirement.applicationId}', received '${resolution.applicationId}'"
              )
            )
          else
            ZIO.succeed(
              requirement.resolve(
                resolution.instanceToken,
                resolution.parentDomId,
                resolution.topic.value,
                resolution.joinCredential.value,
                resolution.staticCredential.map(_.value),
                resolution.loading
              )
            )
        }
      counts  = renderOwners.map(_.nestedRequirements.size)
      grouped = counts
                  .foldLeft((Vector.empty[Vector[NestedResolution]], renderResolutions)) {
                    case ((all, remaining), count) =>
                      val (current, rest) = remaining.splitAt(count)
                      (all :+ current, rest)
                  }._1
      nestedRoot <- ZIO
                      .fromEither(root.resolveNested(grouped.head))
                      .mapError(error => failed(error.getMessage))
      nestedStaged <- ZIO.foreach(staged.zip(grouped.tail)) { case (component, resolutions) =>
                        ZIO
                          .fromEither(component.renderCandidate.resolveNested(resolutions))
                          .mapError(error => failed(error.getMessage))
                          .map(component.withRenderCandidate)
                      }
      finalStaged <- ZIO.foreach(nestedStaged)(rebuildComponent(_, nestedStaged, failed))
      rebuilt         = finalStaged.map(component => component.id -> component).toMap
      rootResolutions = nestedRoot.componentRequirements.zip(components.forest.roots).map {
                          case (requirement, id) => rebuilt(id).resolutionFor(requirement)
                        }
      finalRoot <- ZIO
                     .fromEither(nestedRoot.resolveComponents(rootResolutions))
                     .mapError(error => failed(error.getMessage))
      finalForest = components.forest.copy(components = finalStaged, resolutions = rootResolutions)
    yield (
      finalRoot,
      components.copy(forest = finalForest, resolutions = rootResolutions),
      prepared
    )
    end for
  end prepareNestedTopology

  private def rebuildComponent(
    component: StagedComponent[Msg],
    all: Vector[StagedComponent[Msg]],
    failed: String => SessionFailure
  ): ZIO[Any, SessionFailure, StagedComponent[Msg]] =
    for
      childResolutions <-
        ZIO.foreach(
          component.renderCandidate.componentRequirements.zip(component.children)
        ) { case (requirement, childId) =>
          ZIO
            .fromOption(
              all.find(_.id == childId)
            ).orElseFail(
              failed(
                s"component '${component.key.applicationId}' child graph is inconsistent"
              )
            ).flatMap(rebuildComponent(_, all, failed))
            .map(_.resolutionFor(requirement))
        }
      candidate <- ZIO
                     .fromEither(component.renderCandidate.resolveComponents(childResolutions))
                     .mapError(error => failed(error.getMessage))
    yield component.withRenderCandidate(candidate)

  private def removeStagedComponents(
    roots: Set[ComponentInstanceId],
    staged: Ref[Vector[StagedComponent[Msg]]],
    identities: Ref[Set[ComponentKey]]
  ): UIO[Unit] =
    if roots.isEmpty then ZIO.unit
    else
      staged.get.flatMap { values =>
        var removedIds = roots
        var changed    = true
        while changed do
          val next = removedIds ++ values.collect {
            case component if component.parent.exists(removedIds.contains) => component.id
          }
          changed = next.size != removedIds.size
          removedIds = next
        val (removed, retained) = values.partition(component => removedIds.contains(component.id))
        staged.set(retained) *>
          identities.update(_ -- removed.map(_.key)) *>
          ZIO.foreachDiscard(removed)(_.discard)
      }

  private def stageRequirements[Owner](
    requirements: Vector[ComponentRequirement[Owner]],
    parent: Option[ComponentInstanceId],
    previous: ComponentForest[Msg],
    action: Option[(ComponentInstanceId, ComponentAction)],
    identities: Ref[Set[ComponentKey]],
    outputs: Ref[Vector[ComponentOutput[Msg]]],
    staged: Ref[Vector[StagedComponent[Msg]]],
    draft: Ref[TurnDraft[Msg, Model]],
    updates: Ref[Map[ComponentKey, ComponentUpdateRequest]]
  ): ZIO[Any, SessionFailure, Vector[(ComponentInstanceId, ComponentResolution)]] =
    updates.get.flatMap { pendingUpdates =>
      val indexed = requirements.zipWithIndex
      val ordered = indexed.sortBy { case (requirement, index) =>
        val key = ComponentKey(
          ComponentDefinitionIdentity(requirement.definition.asInstanceOf[AnyRef]),
          requirement.applicationId
        )
        val actionPriority = action.exists { case (target, _) =>
          previous.byKey.get(key).exists(_.id == target)
        }
        if actionPriority then -2
        else if pendingUpdates.contains(key) then -1
        else index
      }
      ZIO
        .foreach(ordered) { case (requirement, index) =>
          stageComponent(
            requirement,
            parent,
            previous,
            action,
            identities,
            outputs,
            staged,
            draft,
            updates
          ).map(index -> _)
        }.map(_.sortBy(_._1).map(_._2))
    }

  private def stageComponent[Owner](
    requirement: ComponentRequirement[Owner],
    parent: Option[ComponentInstanceId],
    previousForest: ComponentForest[Msg],
    action: Option[(ComponentInstanceId, ComponentAction)],
    identities: Ref[Set[ComponentKey]],
    outputs: Ref[Vector[ComponentOutput[Msg]]],
    staged: Ref[Vector[StagedComponent[Msg]]],
    draft: Ref[TurnDraft[Msg, Model]],
    updates: Ref[Map[ComponentKey, ComponentUpdateRequest]]
  ): ZIO[Any, SessionFailure, (ComponentInstanceId, ComponentResolution)] =
    val key = ComponentKey(
      ComponentDefinitionIdentity(requirement.definition.asInstanceOf[AnyRef]),
      requirement.applicationId
    )
    val duplicate = identities.modify { seen =>
      if seen.contains(key) then true -> seen else false -> (seen + key)
    }

    duplicate.flatMap { isDuplicate =>
      if isDuplicate then
        ZIO.fail(
          SessionFailure.StageFailed(
            SessionStage.Validation,
            s"duplicate component identity '${requirement.applicationId}'"
          )
        )
      else
        val previous = previousForest.byKey.get(key)
        stageTypedComponent(
          requirement,
          key,
          parent,
          previous,
          previousForest,
          action,
          identities,
          outputs,
          staged,
          draft,
          updates
        )
    }
  end stageComponent

  private def stageTypedComponent[Owner](
    requirement: ComponentRequirement[Owner],
    key: ComponentKey,
    parent: Option[ComponentInstanceId],
    erasedPrevious: Option[MountedComponent[Msg]],
    previousForest: ComponentForest[Msg],
    action: Option[(ComponentInstanceId, ComponentAction)],
    identities: Ref[Set[ComponentKey]],
    outputs: Ref[Vector[ComponentOutput[Msg]]],
    staged: Ref[Vector[StagedComponent[Msg]]],
    draft: Ref[TurnDraft[Msg, Model]],
    updates: Ref[Map[ComponentKey, ComponentUpdateRequest]]
  ): ZIO[Any, SessionFailure, (ComponentInstanceId, ComponentResolution)] =
    type P = requirement.Props
    type M = requirement.Message
    type A = requirement.Model
    type O = requirement.Output

    val component  = requirement.definition
    val inputProps = requirement.props
    val old        = erasedPrevious.map(
      _.asInstanceOf[MountedComponentValue[Msg, P, M, A, O]]
    )
    val output: O => Task[Unit] = value =>
      requirement.outputMapper match
        case None         => ZIO.unit
        case Some(mapper) =>
          val message = mapper(value)
          parent match
            case None        => outputs.update(_ :+ ComponentOutput.Root(message.asInstanceOf[Msg]))
            case Some(owner) => outputs.update(_ :+ ComponentOutput.Parent(owner, message))

    ZIO.uninterruptibleMask { restore =>
      for
        id        <- old.fold(identity(ComponentInstanceId.fresh()))(value => ZIO.succeed(value.id))
        effective <- updates.get.map(_.get(key) match
                       case Some(request) => (request.props.asInstanceOf[P], Some(request))
                       case None          =>
                         val props = old match
                           case Some(value) if value.inputProps == requirement.props => value.props
                           case _ => requirement.props
                         (props, None))
        (effectiveProps, initialRequest) = effective
        ref                              = old.fold(ComponentRef.runtime[M](new Object()))(_.ref)
        program <- old.fold(
                     restore(
                       renderPhase(
                         ZIO.fromEither(
                           RenderProgram.compile[(P, A, Map[scalive.FlashKind, String]), M](
                             input => component.view(input.map(_._1), input.map(_._2), ref),
                             _._3
                           )
                         )
                       )
                     )
                   )(value => ZIO.succeed(value.program))
        stateOwner <- Ref.make(Option.empty[ComponentEnvironmentState])
        scopeOwner <- Ref.make(Option.empty[CandidateScope])
        result     <- (for
                    currentDraft <- draft.get
                    mounted      <- old match
                                 case Some(value) =>
                                   ZIO.succeed(
                                     ComponentCallbackResult(
                                       value.model,
                                       currentDraft,
                                       value.environmentState
                                     )
                                   )
                                 case None =>
                                   restore(
                                     phase(SessionStage.ComponentMount)(
                                       componentEnvironment.mount(
                                         id,
                                         component,
                                         effectiveProps,
                                         currentDraft
                                       )
                                     )
                                   )
                    _ <- stateOwner.set(Some(mounted.state)) *>
                           recordComponentDraft(draft, updates, mounted.draft)
                    needsUpdate =
                      old.isEmpty || old.exists(_.inputProps != requirement.props) ||
                        initialRequest.nonEmpty || action.contains(id -> ComponentAction.Update)
                    updated <-
                      if needsUpdate then
                        restore(
                          phase(SessionStage.ComponentUpdate)(
                            componentEnvironment.update(
                              id,
                              component,
                              effectiveProps,
                              mounted.model,
                              mounted.state,
                              mounted.draft
                            )
                          )
                        )
                      else ZIO.succeed(mounted)
                    _ <- stateOwner.set(Some(updated.state)) *>
                           recordComponentDraft(draft, updates, updated.draft)
                    acted <- action match
                               case Some((target, ComponentAction.Message(message)))
                                   if target == id =>
                                 restore(
                                   phase(SessionStage.ComponentMessage)(
                                     componentEnvironment.message[P, M, A, O](
                                       id,
                                       component,
                                       effectiveProps,
                                       updated.model,
                                       message.asInstanceOf[M],
                                       output,
                                       updated.state,
                                       updated.draft
                                     )
                                   )
                                 )
                               case Some((target, ComponentAction.Async(event))) if target == id =>
                                 restore(
                                   phase(SessionStage.ComponentAsync)(
                                     componentEnvironment.async[P, M, A, O](
                                       id,
                                       component,
                                       effectiveProps,
                                       updated.model,
                                       event.asInstanceOf[LiveAsyncEvent[M]],
                                       output,
                                       updated.state,
                                       updated.draft
                                     )
                                   )
                                 )
                               case Some((target, ComponentAction.ManagedAsync(completion)))
                                   if target == id =>
                                 val message                    = completion.message.asInstanceOf[M]
                                 val result: LiveAsyncResult[M] = completion.result match
                                   case LiveAsyncResult.Succeeded(_) =>
                                     LiveAsyncResult.Succeeded(message)
                                   case LiveAsyncResult.Failed(cause) =>
                                     LiveAsyncResult.Failed(cause)
                                   case LiveAsyncResult.Cancelled(reason) =>
                                     LiveAsyncResult.Cancelled(reason)
                                 restore(
                                   phase(SessionStage.ComponentAsync)(
                                     componentEnvironment.managedAsync[P, M, A, O](
                                       id,
                                       component,
                                       effectiveProps,
                                       updated.model,
                                       LiveAsyncEvent(AsyncKey[Any](completion.name), result),
                                       message,
                                       output,
                                       updated.state,
                                       updated.draft
                                     )
                                   )
                                 )
                               case Some((target, ComponentAction.Browser(command)))
                                   if target == id =>
                                 restore(
                                   phase(SessionStage.ComponentMessage)(
                                     componentEnvironment
                                       .browserEvent[P, M, A, O](
                                         id,
                                         component,
                                         effectiveProps,
                                         updated.model,
                                         command,
                                         componentBindingMessage(old, command).map(
                                           _.asInstanceOf[M]
                                         ),
                                         output,
                                         updated.state,
                                         updated.draft
                                       )
                                   )
                                 )
                               case _ => ZIO.succeed(updated)
                    _ <- stateOwner.set(Some(acted.state)) *>
                           recordComponentDraft(draft, updates, acted.draft)
                    stabilized <- restore(
                                    stabilizeComponentUpdates[P, M, A](
                                      id,
                                      component,
                                      key,
                                      initialRequest,
                                      effectiveProps,
                                      acted,
                                      draft,
                                      updates,
                                      config.navigationRedirectLimit
                                    )
                                  )
                    (refreshed, finalProps) = stabilized
                    appliedRequest <- updates.get.map(_.get(key))
                    _              <- stateOwner.set(Some(refreshed.state)) *>
                           recordComponentDraft(draft, updates, refreshed.draft)
                    scope     <- CandidateScope.make.tap(value => scopeOwner.set(Some(value)))
                    candidate <- restore(
                                   renderPhase(
                                     program.evaluateIn(
                                       (
                                         finalProps,
                                         refreshed.model,
                                         componentEnvironment.flash(refreshed.draft)
                                       ),
                                       old.map(_.render),
                                       scope
                                     )
                                   )
                                 )
                    childResult <- restore(
                                     stageRequirements(
                                       candidate.componentRequirements,
                                       Some(id),
                                       previousForest,
                                       action,
                                       identities,
                                       outputs,
                                       staged,
                                       draft,
                                       updates
                                     )
                                   )
                    childDraft <- draft.get
                    postChild  <- restore(
                                   stabilizeComponentUpdates[P, M, A](
                                     id,
                                     component,
                                     key,
                                     appliedRequest,
                                     finalProps,
                                     refreshed.copy(draft = childDraft),
                                     draft,
                                     updates,
                                     config.navigationRedirectLimit
                                   )
                                 )
                    (postChildResult, postChildProps) = postChild
                    postChildRequest <- updates.get.map(_.get(key))
                    _                <- stateOwner.set(Some(postChildResult.state)) *>
                           recordComponentDraft(draft, updates, postChildResult.draft)
                    finalCandidate <-
                      if sameUpdateRequest(postChildRequest, appliedRequest) then
                        ZIO.succeed(candidate)
                      else
                        candidate.discard *>
                          CandidateScope.make.tap(value => scopeOwner.set(Some(value))).flatMap {
                            replacementScope =>
                              restore(
                                renderPhase(
                                  program.evaluateIn(
                                    (
                                      postChildProps,
                                      postChildResult.model,
                                      componentEnvironment.flash(postChildResult.draft)
                                    ),
                                    old.map(_.render),
                                    replacementScope
                                  )
                                )
                              )
                          }
                    finalChildResult <-
                      if sameComponentRequirements(
                          candidate.componentRequirements,
                          finalCandidate.componentRequirements
                        )
                      then ZIO.succeed(childResult)
                      else
                        removeStagedSubtree(id, staged, identities) *>
                          stageRequirements(
                            finalCandidate.componentRequirements,
                            Some(id),
                            previousForest,
                            None,
                            identities,
                            outputs,
                            staged,
                            draft,
                            updates
                          )
                    resolved = finalCandidate
                    afterDraft <- draft.get
                    after      <- restore(
                               phase(SessionStage.ComponentAfterRender)(
                                 componentEnvironment.afterRender(
                                   id,
                                   component,
                                   postChildProps,
                                   postChildResult.model,
                                   postChildResult.state,
                                   afterDraft
                                 )
                               )
                             )
                    _ <- stateOwner.set(Some(after.state)) *>
                           recordComponentDraft(draft, updates, after.draft)
                    componentId     = id
                    componentKey    = key
                    componentParent = parent
                    stagedValue     = new StagedComponent[Msg]:
                                    val id               = componentId
                                    val key              = componentKey
                                    val parent           = componentParent
                                    val children         = finalChildResult.map(_._1)
                                    val previous         = erasedPrevious
                                    val candidateScope   = resolved.stagedScope
                                    val environmentState = after.state
                                    val appliedUpdate    = postChildRequest
                                    def matches(requirement: ComponentRequirement[?]) =
                                      (requirement.definition.asInstanceOf[AnyRef] eq
                                        component.asInstanceOf[AnyRef]) &&
                                        requirement.applicationId == key.applicationId &&
                                        requirement.props == inputProps
                                    protected def resolutionForCandidate(
                                      requirement: ComponentRequirement[?],
                                      candidate: RenderCandidate[Any]
                                    ): ComponentResolution =
                                      requirement.resolve(
                                        ref.asInstanceOf[ComponentRef[requirement.Message]],
                                        ref.asInstanceOf[AnyRef],
                                        candidate
                                          .asInstanceOf[RenderCandidate[requirement.Message]]
                                      )
                                    def resolutionFor(requirement: ComponentRequirement[?]) =
                                      resolutionForCandidate(
                                        requirement,
                                        resolved.asInstanceOf[RenderCandidate[Any]]
                                      )
                                    def renderCandidate =
                                      resolved.asInstanceOf[RenderCandidate[Any]]
                                    def discard =
                                      resolved.discard *>
                                        ZIO
                                          .when(resolved.stagedScope.isClosed)(
                                            discardEnvironmentCandidate(
                                              componentId,
                                              old,
                                              after.state
                                            ) *>
                                              ZIO.when(old.isEmpty)(program.close).unit
                                          ).unit
                                    def abortCommitted =
                                      resolved.stagedScope.closeFromOwner *>
                                        discardEnvironmentCandidate(
                                          componentId,
                                          old,
                                          after.state
                                        ) *>
                                        ZIO.when(old.isEmpty)(program.close).unit
                                    protected def commitValueFor(
                                      candidate: RenderCandidate[Any]
                                    ): MountedComponent[Msg] =
                                      MountedComponentValue[Msg, P, M, A, O](
                                        id,
                                        key,
                                        component,
                                        inputProps,
                                        postChildProps,
                                        postChildResult.model,
                                        ref,
                                        candidate.asInstanceOf[RenderCandidate[M]].commit,
                                        program,
                                        parent,
                                        finalChildResult.map(_._1),
                                        after.state,
                                        requirement.outputMapper.map(mapper =>
                                          (value: O) => mapper(value).asInstanceOf[Msg]
                                        )
                                      )
                                    def commitValue = commitValueFor(renderCandidate)
                    _ <- staged.update(_ :+ stagedValue)
                    resolution = requirement.resolve(ref, ref.asInstanceOf[AnyRef], resolved)
                  yield id -> resolution).onExit {
                    case Exit.Success(_) => ZIO.unit
                    case Exit.Failure(_) =>
                      scopeOwner.get.flatMap(ZIO.foreachDiscard(_)(_.closeFromOwner)) *>
                        stateOwner.get.flatMap(
                          ZIO.foreachDiscard(_)(state =>
                            discardEnvironmentCandidate(id, old, state)
                          )
                        ) *>
                        ZIO.when(old.isEmpty)(program.close).unit
                  }
      yield result
    }
  end stageTypedComponent

  private def stabilizeComponentUpdates[P, M, A](
    id: ComponentInstanceId,
    component: scalive.LiveComponent[P, M, A],
    key: ComponentKey,
    applied: Option[ComponentUpdateRequest],
    props: P,
    current: ComponentCallbackResult[A, Msg, Model],
    draft: Ref[TurnDraft[Msg, Model]],
    updates: Ref[Map[ComponentKey, ComponentUpdateRequest]],
    remaining: Int
  ): ZIO[Any, SessionFailure, (ComponentCallbackResult[A, Msg, Model], P)] =
    updates.get.flatMap(_.get(key) match
      case None                                          => ZIO.succeed(current -> props)
      case Some(request) if applied.exists(_ eq request) => ZIO.succeed(current -> props)
      case Some(_) if remaining <= 0                     =>
        ZIO.fail(
          SessionFailure.StageFailed(
            SessionStage.Validation,
            s"component update journal did not stabilize for '${key.applicationId}'"
          )
        )
      case Some(request) =>
        val nextProps = request.props.asInstanceOf[P]
        phase(SessionStage.ComponentUpdate)(
          componentEnvironment.update(
            id,
            component,
            nextProps,
            current.model,
            current.state,
            current.draft
          )
        ).flatMap { next =>
          recordComponentDraft(draft, updates, next.draft) *>
            stabilizeComponentUpdates(
              id,
              component,
              key,
              Some(request),
              nextProps,
              next,
              draft,
              updates,
              remaining - 1
            )
        })

  private def componentBindingMessage[P, M, A, O](
    old: Option[MountedComponentValue[Msg, P, M, A, O]],
    command: SessionCommand.ComponentClientEvent
  ): Task[M] =
    ZIO
      .fromOption(old).orElseFail(IllegalStateException("component browser target is not mounted"))
      .flatMap { mounted =>
        ZIO
          .fromOption(mounted.render.bindings.resolve(command.binding))
          .orElseFail(IllegalStateException(s"unknown component binding ${command.binding}"))
          .flatMap(operation => ZIO.fromEither(operation.dispatch(command.payload)))
          .flatMap {
            case BindingDispatch.Owner(message) => ZIO.succeed(message)
            case targeted: BindingDispatch.Targeted
                if targeted.target.asInstanceOf[AnyRef] eq mounted.ref.asInstanceOf[AnyRef] =>
              ZIO.succeed(targeted.message.asInstanceOf[M])
            case BindingDispatch.Routed(dispatch) =>
              ZIO.succeed(dispatch.message.asInstanceOf[M])
            case _ => ZIO.fail(IllegalStateException("component binding targets another component"))
          }
      }

  private def discardEnvironmentCandidate[P, M, A, O](
    id: ComponentInstanceId,
    old: Option[MountedComponentValue[Msg, P, M, A, O]],
    candidate: ComponentEnvironmentState
  ): UIO[Unit] =
    val retained = old.exists(value => value.environmentState.value eq candidate.value)
    ZIO.unless(retained)(componentEnvironment.discard(id, candidate)).unit

  private def lastComponentUpdates(
    updates: Vector[ComponentUpdateRequest]
  ): Map[ComponentKey, ComponentUpdateRequest] =
    updates.foldLeft(Map.empty[ComponentKey, ComponentUpdateRequest]) { (result, update) =>
      val key = ComponentKey(
        ComponentDefinitionIdentity(update.definition.asInstanceOf[AnyRef]),
        update.applicationId
      )
      result.updated(key, update)
    }

  private def sameUpdateRequest(
    left: Option[ComponentUpdateRequest],
    right: Option[ComponentUpdateRequest]
  ): Boolean = (left, right) match
    case (None, None)                      => true
    case (Some(a), Some(b))                => a eq b
    case (None, Some(_)) | (Some(_), None) => false

  private def sameComponentRequirements[Owner](
    left: Vector[ComponentRequirement[Owner]],
    right: Vector[ComponentRequirement[Owner]]
  ): Boolean =
    left.size == right.size && left.zip(right).forall { case (a, b) =>
      (a.definition.asInstanceOf[AnyRef] eq b.definition.asInstanceOf[AnyRef]) &&
      a.applicationId == b.applicationId && a.props == b.props
    }

  private def removeStagedSubtree(
    parent: ComponentInstanceId,
    staged: Ref[Vector[StagedComponent[Msg]]],
    identities: Ref[Set[ComponentKey]]
  ): UIO[Unit] =
    staged.get.flatMap { values =>
      var removedIds = Set(parent)
      var changed    = true
      while changed do
        val next = removedIds ++ values.collect {
          case component if component.parent.exists(removedIds.contains) => component.id
        }
        changed = next.size != removedIds.size
        removedIds = next

      val (removed, retained) =
        values.partition(component => component.parent.exists(removedIds.contains))
      staged.set(retained) *>
        identities.update(_ -- removed.map(_.key)) *>
        ZIO.foreachDiscard(removed)(_.discard)
    }

  private def recordComponentDraft(
    draftRef: Ref[TurnDraft[Msg, Model]],
    updatesRef: Ref[Map[ComponentKey, ComponentUpdateRequest]],
    draft: TurnDraft[Msg, Model]
  ): UIO[Unit] =
    draftRef.set(draft) *>
      updatesRef.update(current => current ++ lastComponentUpdates(draft.componentUpdates))

  final private case class ManagedPreparation(
    index: ResourceIndex[ManagedResource],
    activations: Vector[ManagedResource] = Vector.empty,
    retirements: Vector[ManagedResource] = Vector.empty,
    continuations: Vector[ManagedAsyncContinuation] = Vector.empty):
    def retire(resources: Vector[ManagedResource]): ManagedPreparation =
      val retiredTokens = resources.map(_.token).toSet
      copy(
        activations = activations.filterNot(resource => retiredTokens.contains(resource.token)),
        retirements = retirements ++ resources
      )

  private def prepareManagedResources(
    initial: ResourceIndex[ManagedResource],
    operations: Vector[ResourceOperation],
    activeComponents: Set[ComponentInstanceId],
    candidateScope: CandidateScope
  ): ZIO[Any, SessionFailure, ManagedPreparation] =
    val activeOperations = operations.filter {
      case ResourceOperation.StartAsync(owner, _, _, _) => ownerIsActive(owner, activeComponents)
      case ResourceOperation.CancelAsync(owner, _, _)   => ownerIsActive(owner, activeComponents)
      case ResourceOperation.StartSubscription(owner, _, _, _, _) =>
        ownerIsActive(owner, activeComponents)
      case ResourceOperation.CancelSubscription(owner, _) => ownerIsActive(owner, activeComponents)
      case ResourceOperation.Complete(token) => ownerIsActive(token.owner, activeComponents)
    }
    ZIO
      .foldLeft(activeOperations)(ManagedPreparation(initial)) { (prepared, operation) =>
        operation match
          case start: ResourceOperation.StartAsync[?, ?] =>
            val owner = start.owner
            val key   = ResourceKey.Async(start.key.value)
            for
              _        <- validateOwner(owner)
              token    <- identity(prepared.index.nextToken(owner, epoch, key))
              resource <- prepareAsyncResource(
                            token,
                            start.task.asInstanceOf[Task[Any]],
                            start.toMessage.asInstanceOf[LiveAsyncResult[Any] => Any],
                            candidateScope
                          )
              replacement = prepared.index.install(token, resource)
              retired     = prepared.retire(replacement.replaced.toVector)
            yield retired.copy(
              index = replacement.index,
              activations = retired.activations :+ resource
            )
          case ResourceOperation.CancelAsync(owner, key, reason) =>
            validateOwner(owner) *>
              ZIO.suspendSucceed {
                val removal = prepared.index.remove(owner, ResourceKey.Async(key.value))
                removal.removed match
                  case Some(
                        resource @ ManagedResource(_, _, ManagedResourceKind.Async(mapResult))
                      ) =>
                    phase(SessionStage.ResourcePreparation)(
                      ZIO.attempt(mapResult(LiveAsyncResult.Cancelled(reason)))
                    ).map { message =>
                      val completion = ManagedAsyncCompletion(
                        key.value,
                        LiveAsyncResult.Cancelled(reason),
                        message
                      )
                      prepared
                        .retire(Vector(resource)).copy(
                          index = removal.index,
                          continuations = prepared.continuations :+
                            ManagedAsyncContinuation(owner, completion)
                        )
                    }
                  case Some(_) =>
                    ZIO.fail(
                      SessionFailure.StageFailed(
                        SessionStage.Validation,
                        s"resource '${key.value}' is not an async task"
                      )
                    )
                  case None => ZIO.succeed(prepared)
              }
          case ResourceOperation.Complete(token) =>
            prepared.index.current(token) match
              case Some(resource) =>
                val removal = prepared.index.remove(token.owner, token.key)
                ZIO.succeed(
                  prepared.retire(Vector(resource)).copy(index = removal.index)
                )
              case None => ZIO.succeed(prepared)
          case start: ResourceOperation.StartSubscription[?] =>
            val owner = start.owner
            val key   = ResourceKey.Subscription(start.key.value)
            for
              _ <- validateOwner(owner)
              _ <- ZIO
                     .fail(
                       SessionFailure.StageFailed(
                         SessionStage.Validation,
                         "subscription key must not be empty"
                       )
                     ).when(start.key.value.isEmpty)
              _ <- ZIO
                     .fail(
                       SessionFailure.StageFailed(
                         SessionStage.Validation,
                         s"subscription '${start.key.value}' is already active"
                       )
                     ).when(!start.replace && prepared.index.get(owner, key).nonEmpty)
              token    <- identity(prepared.index.nextToken(owner, epoch, key))
              resource <- prepareSubscriptionResource(
                            token,
                            start.delivery,
                            start.stream.asInstanceOf[zio.stream.ZStream[Any, Nothing, Any]],
                            candidateScope
                          )
              replacement = prepared.index.install(token, resource)
              retired     = prepared.retire(replacement.replaced.toVector)
            yield retired.copy(
              index = replacement.index,
              activations = retired.activations :+ resource
            )
          case ResourceOperation.CancelSubscription(owner, key) =>
            validateOwner(owner).flatMap { _ =>
              val removal = prepared.index.remove(owner, ResourceKey.Subscription(key.value))
              ZIO.succeed(
                prepared.retire(removal.removed.toVector).copy(index = removal.index)
              )
            }
      }.map { prepared =>
        prepared.index.owners.foldLeft(prepared) {
          case (current, owner @ OwnerId.Component(ownerLifecycle, component))
              if ownerLifecycle == lifecycle && !activeComponents.contains(component) =>
            val removal = current.index.removeOwner(owner)
            current.retire(removal.removed).copy(index = removal.index)
          case (current, _) => current
        }
      }
  end prepareManagedResources

  private def ownerIsActive(owner: OwnerId, activeComponents: Set[ComponentInstanceId]): Boolean =
    owner match
      case OwnerId.Root(ownerLifecycle)                 => ownerLifecycle == lifecycle
      case OwnerId.Component(ownerLifecycle, component) =>
        ownerLifecycle == lifecycle && activeComponents.contains(component)

  private def prepareAsyncResource(
    token: ResourceToken,
    task: Task[Any],
    mapResult: LiveAsyncResult[Any] => Any,
    candidateScope: CandidateScope
  ): UIO[ManagedResource] =
    for
      scope    <- Scope.make
      prepared <- PreparedResource.make(scope.close(Exit.unit))
      _        <- candidateScope.addFinalizer(prepared.discard)
      resource = ManagedResource(token, prepared, ManagedResourceKind.Async(mapResult))
      worker   = prepared.awaitActivation.foldZIO(
                 _ => ZIO.unit,
                 _ =>
                   task.exit.flatMap { exit =>
                     val result = exit match
                       case Exit.Success(value) => LiveAsyncResult.Succeeded(value)
                       case Exit.Failure(cause) => LiveAsyncResult.Failed(cause.squash)
                     enqueueOwnedCommand(SessionCommand.ManagedAsync(epoch, token, result))
                   }
               )
      _ <- worker.forkIn(scope)
    yield resource

  private def prepareSubscriptionResource(
    token: ResourceToken,
    delivery: SubscriptionDelivery,
    stream: zio.stream.ZStream[Any, Nothing, Any],
    candidateScope: CandidateScope
  ): UIO[ManagedResource] =
    for
      scope    <- Scope.make
      prepared <- PreparedResource.make(scope.close(Exit.unit))
      _        <- candidateScope.addFinalizer(prepared.discard)
      resource = ManagedResource(token, prepared, ManagedResourceKind.Subscription(delivery))
      _ <- delivery match
             case SubscriptionDelivery.Lossless =>
               val worker = prepared.awaitActivation.foldZIO(
                 _ => ZIO.unit,
                 _ =>
                   stream
                     .runForeach(message =>
                       enqueueOwnedCommand(
                         SessionCommand.ManagedSubscription(epoch, token, message)
                       )
                     ).foldCauseZIO(
                       cause =>
                         if cause.isInterruptedOnly then ZIO.unit
                         else
                           ZIO.logWarning(
                             "managed subscription stream defect; stopping subscription"
                           ) *>
                             enqueueOwnedCommand(
                               SessionCommand.ManagedSubscriptionEnded(epoch, token)
                             )
                       ,
                       _ =>
                         enqueueOwnedCommand(
                           SessionCommand.ManagedSubscriptionEnded(epoch, token)
                         )
                     )
               )
               worker.forkIn(scope).unit
             case SubscriptionDelivery.Latest =>
               for
                 latest   <- Ref.make(Option.empty[Any])
                 signal   <- Queue.dropping[Unit](1)
                 first    <- Ref.make(true)
                 admitted <- Promise.make[Nothing, Unit]
                 done     <- Promise.make[Nothing, Unit]
                 producer = prepared.awaitActivation.foldZIO(
                              _ => ZIO.unit,
                              _ =>
                                stream
                                  .runForeach(message =>
                                    for
                                      isFirst <- first.getAndSet(false)
                                      _       <- latest.set(Some(message)) *> signal.offer(()).unit
                                      _       <- admitted.await.when(isFirst)
                                    yield ()
                                  ).catchAllCause(cause =>
                                    ZIO
                                      .logWarning(
                                        "managed subscription stream defect; stopping subscription"
                                      ).unless(cause.isInterruptedOnly).unit
                                  ).ensuring(done.succeed(()).unit *> signal.offer(()).unit)
                            )
                 consumer = prepared.awaitActivation.foldZIO(
                              _ => ZIO.unit,
                              _ => deliverLatestSubscription(token, latest, signal, admitted, done)
                            )
                 _ <- producer.forkIn(scope)
                 _ <- consumer.forkIn(scope)
               yield ()
    yield resource

  private def deliverLatestSubscription(
    token: ResourceToken,
    latest: Ref[Option[Any]],
    signal: Queue[Unit],
    admitted: Promise[Nothing, Unit],
    done: Promise[Nothing, Unit]
  ): UIO[Unit] =
    for
      _        <- signal.take
      reserved <- takeOwnedSlot
      message  <- if reserved then latest.getAndSet(None) else ZIO.none
      _        <- message match
             case Some(value) =>
               enqueueOwnedCommandReserved(
                 SessionCommand.ManagedSubscription(epoch, token, value)
               ) *> admitted.succeed(()).unit
             case None => ZIO.when(reserved)(regularSlots.offer(()).unit).unit
      completed <- done.isDone
      remaining <- latest.get
      _         <-
        if completed && remaining.isEmpty then
          enqueueOwnedCommand(SessionCommand.ManagedSubscriptionEnded(epoch, token))
        else deliverLatestSubscription(token, latest, signal, admitted, done)
    yield ()

  private def validateOwner(owner: OwnerId): ZIO[Any, SessionFailure, Unit] =
    val ownerLifecycle = owner match
      case OwnerId.Root(value)         => value
      case OwnerId.Component(value, _) => value
    ZIO
      .fail(
        SessionFailure.StageFailed(
          SessionStage.Validation,
          "resource owner belongs to another lifecycle"
        )
      ).unless(ownerLifecycle == lifecycle).unit

  private def enqueueOwnedCommand(command: SessionCommand[Msg]): UIO[Unit] =
    ZIO
      .fromEither(CommandId.fresh()).foldZIO(
        error =>
          ZIO.logError(s"managed resource command identity failed: $error") *>
            shutdown.succeed(()).unit,
        commandId =>
          Promise.make[SessionRejection, TurnResult].flatMap { response =>
            offerOwned(Envelope.Execute(commandId, command, response)).unit
          }
      )

  private def enqueueOwnedCommandReserved(command: SessionCommand[Msg]): UIO[Unit] =
    ZIO
      .fromEither(CommandId.fresh()).foldZIO(
        error =>
          regularSlots.offer(()).unit *>
            ZIO.logError(s"managed resource command identity failed: $error") *>
            shutdown.succeed(()).unit,
        commandId =>
          Promise.make[SessionRejection, TurnResult].flatMap { response =>
            offerMailbox(Envelope.Execute(commandId, command, response))
              .tap(accepted => ZIO.unless(accepted)(regularSlots.offer(()).unit)).unit
          }
      )

  private def reserve(
    candidateScope: CandidateScope
  ): ZIO[Any, SessionFailure, OutboundReservation[SessionOutput]] =
    ZIO.uninterruptible {
      reservationPhase(outbound.reserve).flatMap { reservation =>
        candidateScope
          .addFinalizer(reservation.release).onError(_ => reservation.release).as(reservation)
      }
    }

  private def discardCandidate(candidate: TurnCandidate[Msg, Model]): UIO[Unit] =
    RuntimeCleanup.all(
      Vector(
        rollbackCandidate(candidate),
        candidate.render.discard,
        RuntimeCleanup.all(candidate.components.components.map(_.discard)),
        candidate.auxiliaryScope.closeFromOwner
      )
    )

  private def commit(
    previous: Option[Committed[Msg, Model]],
    candidate: TurnCandidate[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    command: Option[CommandId],
    publish: Boolean = true
  ): ZIO[Any, SessionFailure, CommitResult[Msg, Model]] =
    val commitTail = for
      components <- ZIO.succeed(candidate.components.components.map(_.commitValue))
      render     <- ZIO.succeed(candidate.render.commit)
      forest = ComponentForest(candidate.components.roots, components)
      next   = Committed(
               candidate.draft.model,
               candidate.draft.url.orElse(previous.map(_.url)).getOrElse(URL.root),
               render,
               forest,
               candidate.resources,
               candidate.managedResources,
               candidate.auxiliaryScope,
               candidate.revision
             )
      rootWork: Vector[Work[Msg, Model]] =
        candidate.draft.continuations.map(message => Work.Continuation[Msg, Model](message))
      outputWork: Vector[Work[Msg, Model]] = candidate.outputs.map {
                                               case ComponentOutput.Root(message) =>
                                                 Work.Continuation[Msg, Model](message)
                                               case ComponentOutput.Parent(component, message) =>
                                                 Work.ComponentContinuation[Msg, Model](
                                                   component,
                                                   message
                                                 )
                                             }
      managedWork: Vector[Work[Msg, Model]] =
        candidate.managedContinuations.map(value =>
          Work.ManagedAsyncContinuation[Msg, Model](value.owner, value.completion)
        )
      nextWork = work.enqueueAll(rootWork ++ outputWork ++ managedWork)
      _ <- activeOwner.activate(closeCommitted(next))
      _ <- retireUploads(candidate.draft.uploadCommit)
      _ <- ZIO.foreachDiscard(previous)(_.resources.markStale)
      _ <- ZIO.foreachDiscard(candidate.managedRetirements)(_.prepared.markStale)
      _ <- closeManaged(candidate.managedRetirements)
      _ <- ZIO.foreachDiscard(candidate.managedRetirements)(resource =>
             observer.emit(
               RuntimeEvent.ResourceRetired(
                 RuntimeCorrelation(
                   connection,
                   lifecycle,
                   epoch,
                   command = command,
                   turn = Some(candidate.id),
                   revision = Some(candidate.revision),
                   resource = Some(resource.token.identity)
                 )
               )
             )
           )
      _ <- candidate.resources.activate
      _ <- ZIO.foreachDiscard(candidate.managedActivations)(_.prepared.activate)
      _ <- ZIO.foreachDiscard(candidate.managedActivations)(resource =>
             observer.emit(
               RuntimeEvent.ResourceActivated(
                 RuntimeCorrelation(
                   connection,
                   lifecycle,
                   epoch,
                   command = command,
                   turn = Some(candidate.id),
                   revision = Some(candidate.revision),
                   resource = Some(resource.token.identity)
                 )
               )
             )
           )
      _ <- candidate.topology.activate
      correlation = RuntimeCorrelation(
                      connection,
                      lifecycle,
                      epoch,
                      command = command,
                      turn = Some(candidate.id),
                      revision = Some(candidate.revision)
                    )
      _ <- observer.emit(RuntimeEvent.StateCommitted(correlation))
      _ <- ZIO.when(publish)(
             candidate.reservation.publish(
               OutboundBatch.single(
                 SessionOutput(
                   command,
                   candidate.delta,
                   None,
                   candidate.draft.effects,
                   candidate.draft.reply
                 )
               )
             )
           )
      _ <- observer.emit(RuntimeEvent.OutputPublished(correlation)).when(publish)
    yield CommitResult(next, nextWork)

    ZIO.uninterruptible(commitTail.exit).flatMap {
      case Exit.Success(result) =>
        ZIO.uninterruptible(retire(previous, result.value) *> candidate.topology.retire).as(result)
      case Exit.Failure(cause) =>
        val candidateCleanup = RuntimeCleanup.all(
          Vector(
            rollbackCandidate(candidate),
            candidate.render.stagedScope.closeFromOwner,
            candidate.auxiliaryScope.closeFromOwner,
            closeManaged(candidate.managedActivations),
            RuntimeCleanup.all(candidate.components.components.map(_.abortCommitted))
          )
        )
        val previousCleanup = RuntimeCleanup.all(previous.toVector.map(closeCommitted))
        ZIO.uninterruptible {
          activeOwner.forget *>
            ZIO.foreach(Vector(candidateCleanup, previousCleanup))(_.exit).flatMap { cleanup =>
              val cleanupDetails = cleanup.collect { case Exit.Failure(error) =>
                error.prettyPrint
              }
              val details = (cause.prettyPrint +: cleanupDetails).mkString("\n")
              ZIO.fail(SessionFailure.CommitDefect(details))
            }
        }
    }
  end commit

  private def retire(
    previous: Option[Committed[Msg, Model]],
    next: Committed[Msg, Model]
  ): ZIO[Any, SessionFailure, Unit] =
    ZIO.foreachDiscard(previous) { committed =>
      val rootEffects = Vector[UIO[Unit]](
        committed.render.close,
        committed.auxiliaryScope.closeFromOwner
      )
      val componentEffects = committed.components.values.flatMap { component =>
        val retained         = next.components.get(component.id)
        val ownershipEffects = retained match
          case Some(value) if value.environmentState.value eq component.environmentState.value =>
            Vector.empty
          case Some(_) =>
            Vector(componentEnvironment.discard(component.id, component.environmentState))
          case None =>
            Vector(
              component.program.close,
              componentEnvironment.close(component.id, component.environmentState)
            )
        component.render.close +: ownershipEffects
      }
      ZIO.foreach(rootEffects ++ componentEffects)(_.exit).flatMap { exits =>
        val failures = exits.collect { case Exit.Failure(cause) => cause }
        failures.reduceOption(_ ++ _) match
          case None        => ZIO.unit
          case Some(cause) =>
            ZIO.fail(
              SessionFailure.StageFailed(SessionStage.Retirement, cause.prettyPrint)
            )
      }
    }

  private def resolveClient(
    state: SessionState.Active[Msg, Model],
    command: SessionCommand.ClientEvent
  ): Either[SessionRejection, ResolvedClient[Msg]] =
    state.committed.render.bindings.resolve(command.binding) match
      case None            => Left(SessionRejection.UnknownBinding(command.binding))
      case Some(operation) =>
        operation
          .dispatch(command.payload)
          .left.map(SessionRejection.BindingFailed(command.binding, _))
          .flatMap(dispatch => resolveDispatch(state.committed.components, dispatch))

  private def resolveDispatch[Owner](
    forest: ComponentForest[Msg],
    dispatch: BindingDispatch[Owner]
  ): Either[SessionRejection, ResolvedClient[Owner]] = dispatch match
    case BindingDispatch.Owner(message)     => Right(ResolvedClient.Root(message))
    case targeted: BindingDispatch.Targeted =>
      forest.byRef(targeted.target.asInstanceOf[ComponentRef[Any]]) match
        case Some(component) => Right(ResolvedClient.Component(component.id, targeted.message))
        case None            => Left(SessionRejection.UnknownComponentTarget)
    case BindingDispatch.Routed(value) => resolveRouted(forest, value)

  private def resolveRouted[Owner](
    forest: ComponentForest[Msg],
    dispatch: ComponentDispatch
  ): Either[SessionRejection, ResolvedClient[Owner]] =
    val (applicationId, matches) = dispatch match
      case value: ComponentDispatch.Instance[?, ?, ?] =>
        val key = ComponentKey(
          ComponentDefinitionIdentity(value.instance.component.asInstanceOf[AnyRef]),
          value.instance.id
        )
        Some(value.instance.id) -> forest.byKey.get(key).toVector.map(_ -> value.message)
      case value: ComponentDispatch.OutputInstance[?, ?, ?, ?] =>
        val key = ComponentKey(
          ComponentDefinitionIdentity(value.instance.component.asInstanceOf[AnyRef]),
          value.instance.id
        )
        Some(value.instance.id) -> forest.byKey.get(key).toVector.map(_ -> value.message)
      case value: ComponentDispatch.Definition[?, ?, ?] =>
        None -> forest.values
          .filter(component =>
            component.key.definition.value eq value.component.asInstanceOf[AnyRef]
          ).map(_ -> value.message)
    matches match
      case Vector((component, message)) => Right(ResolvedClient.Component(component.id, message))
      case Vector()                     => Left(SessionRejection.UnknownComponentTarget)
      case _                            => Left(SessionRejection.AmbiguousComponent(applicationId))

  private def stageCandidateNavigation(
    state: SessionState.Active[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    candidate: TurnCandidate[Msg, Model],
    response: Option[TrackedCommand]
  ): UIO[Unit] =
    val request = candidate.draft.navigation.get
    if request.kind.isPatch || !logic.terminateOnNavigate then
      controlled(publishCandidatePatch(state.committed, work, candidate, response)).exit.flatMap {
        case Exit.Success((pending, nextWork, _)) =>
          val complete = response.fold[UIO[Unit]](ZIO.unit) { command =>
            command.response
              .succeed(
                TurnResult(command.id, candidate.id, state.committed.revision, RenderDelta.Empty)
              ).unit
          }
          complete *> loop(SessionState.Navigating(epoch, pending), nextWork)
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          discardCandidate(candidate) *>
            response.fold[UIO[Unit]](ZIO.unit)(
              _.response.fail(SessionRejection.Terminal("closed")).unit
            )
        case Exit.Failure(cause) =>
          val failure = failureFrom(cause, SessionStage.OutputReservation)
          discardCandidate(candidate) *>
            failActiveTurn(state, response, failure)
      }
    else
      controlled(publishCandidateTerminal(candidate, response.map(_.id))).exit.flatMap {
        case Exit.Success((turn, navigation)) =>
          val complete = response.fold[UIO[Unit]](ZIO.unit) { command =>
            command.response
              .succeed(
                TurnResult(command.id, turn, state.committed.revision, RenderDelta.Empty)
              ).unit
          }
          complete *>
            ZIO.uninterruptible(activeOwner.close) *>
            terminal
              .succeed(SessionState.Redirected(epoch, navigation)).flatMap(claimed =>
                observer
                  .emit(
                    RuntimeEvent.SessionTerminated(
                      RuntimeCorrelation(
                        connection,
                        lifecycle,
                        epoch,
                        response.map(_.id),
                        turn = Some(turn),
                        navigation = Some(navigation.id)
                      ),
                      RuntimeTerminal.Redirected
                    )
                  ).when(claimed).unit
              )
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          discardCandidate(candidate) *>
            response.fold[UIO[Unit]](ZIO.unit)(
              _.response.fail(SessionRejection.Terminal("closed")).unit
            )
        case Exit.Failure(cause) =>
          discardCandidate(candidate) *>
            failActiveTurn(state, response, failureFrom(cause, SessionStage.OutputReservation))
      }
    end if
  end stageCandidateNavigation

  private def publishCandidatePatch(
    committed: Committed[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    candidate: TurnCandidate[Msg, Model],
    response: Option[TrackedCommand]
  ): ZIO[
    Any,
    SessionFailure,
    (PendingNavigation[Msg, Model], ImmutableQueue[Work[Msg, Model]], NavigationOutput)
  ] =
    val request = candidate.draft.navigation.get
    for
      navigationId <- identity(NavigationId.fresh())
      now          <- zio.Clock.instant
      navigation = NavigationOutput(navigationId, request.destination, request.kind, request.flash)
      nextWork   = work
      _ <- candidate.reservation.publish(
             OutboundBatch.single(
               SessionOutput(
                 response.map(_.id),
                 RenderDelta.Empty,
                 Some(navigation),
                 candidate.draft.effects,
                 candidate.draft.reply
               )
             )
           )
      _ <- observer.emit(
             RuntimeEvent.OutputPublished(
               RuntimeCorrelation(
                 connection,
                 lifecycle,
                 epoch,
                 command = response.map(_.id),
                 turn = Some(candidate.id),
                 revision = Some(candidate.revision),
                 navigation = Some(navigationId)
               )
             )
           )
      pending = PendingNavigation(
                  navigationId,
                  committed.url,
                  request.destination,
                  request.kind,
                  committed,
                  candidate.draft.model,
                  request.flash,
                  now.plus(config.navigationTimeout),
                  0,
                  Vector.empty,
                  Some(candidate)
                )
    yield (pending, nextWork, navigation)
    end for
  end publishCandidatePatch

  private def publishCandidateTerminal(
    candidate: TurnCandidate[Msg, Model],
    command: Option[CommandId]
  ): ZIO[Any, SessionFailure, (TurnId, NavigationOutput)] =
    val request = candidate.draft.navigation.get
    for
      navigationId <- identity(NavigationId.fresh())
      navigation = NavigationOutput(navigationId, request.destination, request.kind, request.flash)
      _ <- candidate.reservation.publish(
             OutboundBatch.single(
               SessionOutput(
                 command,
                 RenderDelta.Empty,
                 Some(navigation),
                 candidate.draft.effects,
                 candidate.draft.reply
               )
             )
           )
      _ <- observer.emit(
             RuntimeEvent.OutputPublished(
               RuntimeCorrelation(
                 connection,
                 lifecycle,
                 epoch,
                 command = command,
                 turn = Some(candidate.id),
                 revision = Some(candidate.revision),
                 navigation = Some(navigationId)
               )
             )
           )
      _ <- discardCandidate(candidate)
    yield candidate.id -> navigation
  end publishCandidateTerminal

  private def stageNavigation(
    committed: Committed[Msg, Model],
    draft: TurnDraft[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    response: Option[TrackedCommand],
    redirectCount: Int,
    deferred: Vector[DeferredSessionCommand[Msg, Model]]
  ): UIO[Unit] =
    val request = draft.navigation.get
    if request.kind.isPatch || !logic.terminateOnNavigate then
      stagePatchNavigation(committed, draft, work, response, redirectCount, deferred)
    else stageTerminalNavigation(committed, draft, work, response, deferred)

  private def stagePatchNavigation(
    committed: Committed[Msg, Model],
    draft: TurnDraft[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    response: Option[TrackedCommand],
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
                                   response.map(_.id),
                                   RenderDelta.Empty,
                                   Some(output),
                                   draft.effects,
                                   draft.reply
                                 )
                               )
                             )
                        _ <- observer.emit(
                               RuntimeEvent.OutputPublished(
                                 RuntimeCorrelation(
                                   connection,
                                   lifecycle,
                                   epoch,
                                   command = response.map(_.id),
                                   turn = Some(turnId),
                                   revision = Some(committed.revision),
                                   navigation = Some(navigationId)
                                 )
                               )
                             )
                      yield (turnId, pending, nextWork)).onError(_ => reservation.release)
          yield result
        }
        controlled(effect).exit.flatMap {
          case Exit.Success((turnId, pending, nextWork)) =>
            val complete = response.fold[UIO[Unit]](ZIO.unit) { command =>
              command.response
                .succeed(TurnResult(command.id, turnId, committed.revision, RenderDelta.Empty)).unit
            }
            complete *> loop(SessionState.Navigating(epoch, pending), nextWork)
          case Exit.Failure(cause) if cause.isInterruptedOnly =>
            response.fold[UIO[Unit]](ZIO.unit)(
              _.response.fail(SessionRejection.Terminal("closed")).unit
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
    response: Option[TrackedCommand],
    deferred: Vector[DeferredSessionCommand[Msg, Model]]
  ): UIO[Unit] =
    controlled(
      publishTerminalNavigation(
        draft,
        response.map(_.id),
        work.size
      )
    ).exit.flatMap {
      case Exit.Success((turnId, navigation)) =>
        val complete = response.fold[UIO[Unit]](ZIO.unit) { command =>
          command.response
            .succeed(TurnResult(command.id, turnId, committed.revision, RenderDelta.Empty)).unit
        }
        val rejection = SessionRejection.Terminal("redirected")
        complete *>
          failDeferred(deferred, rejection) *>
          failWork(work, rejection) *>
          terminal
            .succeed(SessionState.Redirected(epoch, navigation)).flatMap(claimed =>
              observer
                .emit(
                  RuntimeEvent.SessionTerminated(
                    RuntimeCorrelation(
                      connection,
                      lifecycle,
                      epoch,
                      navigation = Some(navigation.id)
                    ),
                    RuntimeTerminal.Redirected
                  )
                ).when(claimed).unit
            )
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        val rejection = SessionRejection.Terminal("closed")
        response.fold[UIO[Unit]](ZIO.unit)(_.response.fail(rejection).unit) *>
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
                               draft.effects,
                               draft.reply
                             )
                           )
                         )
                    _ <- observer.emit(
                           RuntimeEvent.OutputPublished(
                             RuntimeCorrelation(
                               connection,
                               lifecycle,
                               epoch,
                               command = command,
                               turn = Some(turnId),
                               navigation = Some(navigationId)
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
      ZIO.foreachDiscard(state.pending.componentCandidate)(discardCandidate) *>
      ZIO.uninterruptible(activeOwner.close).exit.flatMap { cleanup =>
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
      case Work.Correlated(deferred)           => deferred.response.fail(rejection).unit
      case Work.Continuation(_)                => ZIO.unit
      case Work.ComponentContinuation(_, _)    => ZIO.unit
      case Work.ManagedAsyncContinuation(_, _) => ZIO.unit
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
    val componentCleanup = committed.components.values.map { component =>
      RuntimeCleanup.all(
        Vector(
          component.render.scope.closeFromOwner,
          component.program.close,
          componentEnvironment.close(component.id, component.environmentState)
        )
      )
    }
    RuntimeCleanup.all(
      Vector(
        closeUploads(committed.model),
        closeManaged(committed.managedResources.values),
        committed.render.scope.closeFromOwner,
        committed.auxiliaryScope.closeFromOwner
      ) ++ componentCleanup
    )

  private def retireUploads(plan: UploadRetirementPlan): UIO[Unit] =
    logic
      .retireUploads(plan).catchAllCause(_ => ZIO.logWarning("upload retirement hook failed"))

  private def rollbackCandidate(candidate: TurnCandidate[Msg, Model]): UIO[Unit] =
    candidate.uploadRollbackClaim
      .modify(claimed => claimed -> false)
      .flatMap(claimed => ZIO.when(claimed)(retireUploads(candidate.uploadRollback)).unit)

  private def closeUploads(model: Model): UIO[Unit] =
    logic
      .closeUploads(model).catchAllCause(_ => ZIO.logWarning("upload close hook failed"))

  private def closeManaged(resources: Vector[ManagedResource]): UIO[Unit] =
    PreparedResources(resources.map(_.prepared)).close

  private def crash(
    _state: SessionState[Msg, Model],
    failure: SessionFailure
  ): UIO[Unit] =
    observer.emit(
      RuntimeEvent.SessionTerminated(
        RuntimeCorrelation(connection, lifecycle, epoch),
        RuntimeTerminal.Crashed
      )
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
      closed <- terminal.succeed(SessionState.Closed(epoch))
      _      <- observer
             .emit(
               RuntimeEvent.SessionTerminated(
                 RuntimeCorrelation(connection, lifecycle, epoch),
                 RuntimeTerminal.Closed
               )
             ).when(closed)
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
      _ <- regularSlots.shutdown
    yield ()
end SessionKernel

private[scalive] object SessionKernel:
  final private case class TrackedCommand(
    id: CommandId,
    kind: RuntimeCommandKind,
    initiator: RuntimeInitiator,
    response: Promise[SessionRejection, TurnResult])

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
    case ComponentContinuation(component: ComponentInstanceId, message: Any)
    case ManagedAsyncContinuation(owner: OwnerId, completion: ManagedAsyncCompletion)
    case Correlated(command: DeferredSessionCommand[Msg, Model])

  private enum ComponentAction:
    case Message(value: Any)
    case Async(event: LiveAsyncEvent[Any])
    case ManagedAsync(completion: ManagedAsyncCompletion)
    case Update
    case Browser(command: SessionCommand.ComponentClientEvent)

  private enum ResolvedClient[+Owner]:
    case Root(message: Owner)
    case Component(component: ComponentInstanceId, message: Any)

  final private case class CommitResult[Msg, Model](
    value: Committed[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]])

  final private case class TurnOutcome[Msg, Model](
    turn: TurnId,
    committed: Committed[Msg, Model],
    work: ImmutableQueue[Work[Msg, Model]],
    delta: RenderDelta)

  private enum StagedTurn[Msg, Model]:
    case Committed(outcome: TurnOutcome[Msg, Model])
    case Navigation(candidate: TurnCandidate[Msg, Model])

  final private class ActiveRenderOwner(
    current: AtomicReference[Option[UIO[Unit]]]):
    def activate(close: UIO[Unit]): UIO[Unit] = ZIO.succeed(current.set(Some(close)))

    def forget: UIO[Unit] = ZIO.succeed(current.set(None))

    def close: UIO[Unit] =
      ZIO.suspendSucceed(
        current.getAndSet(None).fold[UIO[Unit]](ZIO.unit)(identity)
      )

  private object ActiveRenderOwner:
    def make: UIO[ActiveRenderOwner] =
      ZIO.succeed(ActiveRenderOwner(AtomicReference(Option.empty[UIO[Unit]])))

  def start[Msg, Model](
    config: SessionConfig,
    logic: SessionLogic[Msg, Model],
    renderProgram: RenderProgram[Model, Msg],
    outbound: OutboundReservations[SessionOutput],
    componentEnvironment: ComponentEnvironment[Msg, Model] =
      ComponentEnvironment.unavailable[Msg, Model],
    providedLifecycle: Option[LifecycleId] = None,
    topologyPreparer: NestedTopologyPreparer = NestedTopologyPreparer.unavailable,
    providedConnection: Option[ConnectionId] = None,
    observer: RuntimeObserver = RuntimeObserver.noop
  ): ZIO[Scope, SessionFailure, SessionKernel[Msg, Model]] =
    ZIO.uninterruptibleMask { restore =>
      for
        connection <- providedConnection match
                        case Some(value) => ZIO.succeed(value)
                        case None        =>
                          ZIO
                            .fromEither(ConnectionId.fresh()).mapError(error =>
                              SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                            )
        lifecycle <- providedLifecycle match
                       case Some(value) => ZIO.succeed(value)
                       case None        =>
                         ZIO
                           .fromEither(LifecycleId.fresh()).mapError(error =>
                             SessionFailure.StageFailed(SessionStage.Identity, error.toString)
                           )
        sessionScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        mailbox      <- Queue.bounded[Envelope[Msg, Model]](config.mailboxCapacity + 1)
        regularSlots <- Queue.bounded[Unit](config.mailboxCapacity)
        _            <- regularSlots.offerAll(Vector.fill(config.mailboxCapacity)(()))
        patchAcknowledgementQueued <- Ref.make(false)
        terminal                   <- Promise.make[Nothing, SessionState[Msg, Model]]
        ready                      <- Promise.make[SessionFailure, Unit]
        shutdown                   <- Promise.make[Nothing, Unit]
        activeOwner                <- ActiveRenderOwner.make
        kernel = SessionKernel(
                   connection,
                   lifecycle,
                   Epoch.initial,
                   config,
                   logic,
                   renderProgram,
                   componentEnvironment,
                   topologyPreparer,
                   outbound,
                   mailbox,
                   regularSlots,
                   patchAcknowledgementQueued,
                   terminal,
                   sessionScope,
                   activeOwner,
                   shutdown,
                   observer
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

  private def runtimeFailure(failure: SessionFailure): RuntimeFailure = failure match
    case SessionFailure.StageFailed(stage, _)         => RuntimeFailure.Stage(stage)
    case _: SessionFailure.CommitDefect               => RuntimeFailure.CommitDefect
    case _: SessionFailure.Interrupted                => RuntimeFailure.RuntimeDefect
    case _: SessionFailure.NavigationTimedOut         => RuntimeFailure.NavigationTimeout
    case _: SessionFailure.NavigationRedirectOverflow => RuntimeFailure.NavigationRedirectOverflow
    case _: SessionFailure.NavigationDeferredOverflow => RuntimeFailure.NavigationDeferredOverflow

  private def stateName[Msg, Model](state: SessionState[Msg, Model]): String = state match
    case SessionState.Bootstrapping(_) => "bootstrapping"
    case SessionState.Active(_, _)     => "active"
    case SessionState.Navigating(_, _) => "navigating"
    case SessionState.Redirected(_, _) => "redirected"
    case SessionState.Closing(_, _)    => "closing"
    case SessionState.Crashed(_, _)    => "crashed"
    case SessionState.Closed(_)        => "closed"
end SessionKernel
