package scalive
package socket

import zio.*
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketOutbound:
  private enum ServerEvent[+Msg]:
    case Message(value: Msg)
    case Async(value: LiveAsyncCompletion)

  private enum MessageHookStage:
    case Info
    case Async(name: String)

  def startServerFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    serverEventStream(state).runForeach((event, meta) => handleServerEvent(event, meta, state)).fork

  def buildOutbox[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)] =
    ZStream.fromIterable(
      (Payload.okReply(LiveResponse.InitDiff(state.initDiff)) -> state.meta) +:
        state.bootstrapPayloads.toList
    ) ++ ZStream
      .unwrapScoped(ZStream.fromHubScoped(state.outHub)).filterNot {
        case (Payload.Diff(diff), _) => diff.isEmpty
        case _                       => false
      }

  def buildShutdown[Msg, Model](
    state: RuntimeState[Msg, Model],
    clientFiber: Fiber.Runtime[Throwable, Unit],
    serverFiber: Fiber.Runtime[Throwable, Unit]
  ): UIO[Unit] =
    ZIO.uninterruptible(
      state.outHub.publish(Payload.Close -> state.meta) *>
        SocketAsyncRuntime.interruptAll(state.asyncTasksRef) *>
        state.inbox.shutdown *>
        state.asyncQueue.shutdown *>
        state.outHub.shutdown *>
        clientFiber.interrupt.unit *>
        serverFiber.interrupt.unit
    )

  private def serverEventStream[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (ServerEvent[Msg], WebSocketMessage.Meta)] =
    val messages = SocketSubscriptionRuntime
      .stream(state.subscriptionsRef)
      .map(ServerEvent.Message(_) -> state.meta.copy(messageRef = None, eventType = "diff"))
    val async = ZStream
      .fromQueue(state.asyncQueue)
      .map(ServerEvent.Async(_) -> state.meta.copy(messageRef = None, eventType = "diff"))
    messages.merge(async)

  private def handleServerEvent[Msg, Model](
    event: ServerEvent[Msg],
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    state.lifecycleLock.withPermit {
      event match
        case ServerEvent.Message(msg) => handleServerMsg(msg, meta, state, MessageHookStage.Info)
        case ServerEvent.Async(completion) => handleAsyncCompletion(completion, meta, state)
    }

  private def handleServerMsg[Msg, Model](
    msg: Msg,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model],
    hookStage: MessageHookStage
  ): Task[Unit] =
    for
      (currentModel, rendered)   <- state.ref.get
      (updatedModel, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          runMessageHooks(currentModel, msg, hookStage, state.ctx).flatMap {
            case LiveHookResult.Continue(hookModel) =>
              state.lv
                .handleMessage(hookModel, state.ctx.messageContext[Msg, Model])(msg)
                .map(LiveHookResult.Continue(_))
            case halt @ LiveHookResult.Halt(_) => ZIO.succeed(halt)
          }
        )
      _ <- updatedModel match
             case LiveHookResult.Halt(hookModel) =>
               navigation match
                 case Some(command) =>
                   state.patchRedirectCountRef.set(0) *>
                     SocketInbound.handleNavigationCommand(
                       hookModel,
                       command,
                       meta,
                       state
                     )
                 case None =>
                   for
                     diff <-
                       SocketModelRuntime.updateModelAndSubscriptions(rendered, hookModel, state)
                     _ <- SocketModelRuntime.publishPayload(Payload.Diff(diff), meta, state)
                     _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
                   yield ()
             case LiveHookResult.Continue(hookModel) =>
               navigation match
                 case Some(command) =>
                   state.patchRedirectCountRef.set(0) *>
                     SocketInbound.handleNavigationCommand(
                       hookModel,
                       command,
                       meta,
                       state
                     )
                 case None =>
                   for
                     diff <-
                       SocketModelRuntime.updateModelAndSubscriptions(
                         rendered,
                         hookModel,
                         state
                       )
                     _ <- SocketModelRuntime.publishPayload(Payload.Diff(diff), meta, state)
                     _ <- SocketFlashRuntime.resetNavigation(state.flashRef)
                   yield ()
    yield ()

  private def runMessageHooks[Msg, Model](
    model: Model,
    msg: Msg,
    hookStage: MessageHookStage,
    ctx: LiveContext
  ): Task[LiveHookResult[Model]] =
    hookStage match
      case MessageHookStage.Info =>
        ctx.hooks.runInfo(model, msg, ctx)
      case MessageHookStage.Async(name) =>
        ctx.hooks.runAsync(model, LiveAsyncEvent(name, LiveAsyncResult.Succeeded(msg)), ctx)

  private def handleAsyncCompletion[Msg, Model](
    completion: LiveAsyncCompletion,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    completion.owner match
      case LiveAsyncOwner.Root =>
        completion.event match
          case LiveAsyncCompletionEvent.Succeeded(name, message) =>
            state.msgClassTag.unapply(message) match
              case Some(msg) => handleServerMsg(msg, meta, state, MessageHookStage.Async(name))
              case None      =>
                ZIO.logWarning(
                  s"Ignoring async message ${message.getClass.getName}: expected ${state.msgClassTag.runtimeClass.getName}"
                )
          case LiveAsyncCompletionEvent.Failed(name, cause) =>
            handleAsyncFailure(name, cause, meta, state)
      case LiveAsyncOwner.Component(cid) =>
        completion.event match
          case LiveAsyncCompletionEvent.Succeeded(name, message) =>
            for
              (_, rendered) <- state.ref.get
              _             <- SocketComponentRuntime.handleComponentAsyncSuccess(
                     cid,
                     name,
                     message,
                     rendered,
                     meta,
                     state
                   )
            yield ()
          case LiveAsyncCompletionEvent.Failed(name, cause) =>
            for
              (_, rendered) <- state.ref.get
              _             <- SocketComponentRuntime.handleComponentAsyncFailure(
                     cid,
                     name,
                     cause,
                     rendered,
                     meta,
                     state
                   )
            yield ()

  private def handleAsyncFailure[Msg, Model](
    name: String,
    cause: Throwable,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (currentModel, rendered)   <- state.ref.get
      (updatedModel, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          state.ctx.hooks.runAsync(
            currentModel,
            LiveAsyncEvent(name, LiveAsyncResult.Failed(cause)),
            state.ctx
          )
        )
      model = updatedModel match
                case LiveHookResult.Continue(value) => value
                case LiveHookResult.Halt(value)     => value
      _ <- navigation match
             case Some(command) =>
               state.patchRedirectCountRef.set(0) *>
                 SocketInbound.handleNavigationCommand(model, command, meta, state)
             case None =>
               for
                 diff <- SocketModelRuntime.updateModelAndSubscriptions(rendered, model, state)
                 _    <- SocketModelRuntime.publishPayload(Payload.Diff(diff), meta, state)
                 _    <- SocketFlashRuntime.resetNavigation(state.flashRef)
               yield ()
    yield ()
end SocketOutbound
