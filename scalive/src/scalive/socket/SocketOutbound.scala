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
    state.outHub.publish(Payload.Close -> state.meta) *>
      SocketAsyncRuntime.interruptAll(state.asyncTasksRef) *>
      state.inbox.shutdown *>
      state.asyncQueue.shutdown *>
      state.outHub.shutdown *>
      clientFiber.interrupt.unit *>
      serverFiber.interrupt.unit

  private def serverEventStream[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (ServerEvent[Msg], WebSocketMessage.Meta)] =
    val messages = (ZStream.fromZIO(state.lvStreamRef.get) ++ state.lvStreamRef.changes)
      .flatMapParSwitch(1, 1)(identity)
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
    event match
      case ServerEvent.Message(msg)      => handleServerMsg(msg, meta, state)
      case ServerEvent.Async(completion) => handleAsyncCompletion(completion, meta, state)

  private def handleServerMsg[Msg, Model](
    msg: Msg,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    for
      (currentModel, rendered)   <- state.ref.get
      (updatedModel, navigation) <-
        SocketModelRuntime.captureNavigation(state)(
          LiveIO
            .toZIO(state.lv.handleMessage(currentModel)(msg))
            .provide(ZLayer.succeed(state.ctx))
        )
      _ <- navigation match
             case Some(command) =>
               state.patchRedirectCountRef.set(0) *>
                 SocketInbound.handleNavigationCommand(
                   rendered,
                   updatedModel,
                   command,
                   meta,
                   state
                 )
             case None =>
               for
                 diff <-
                   SocketModelRuntime.updateModelAndSubscriptions(
                     rendered,
                     updatedModel,
                     state
                   )
                 _ <- SocketModelRuntime.publishPayload(Payload.Diff(diff), meta, state)
               yield ()
    yield ()

  private def handleAsyncCompletion[Msg, Model](
    completion: LiveAsyncCompletion,
    meta: WebSocketMessage.Meta,
    state: RuntimeState[Msg, Model]
  ): Task[Unit] =
    completion.owner match
      case LiveAsyncOwner.Root =>
        state.msgClassTag.unapply(completion.message) match
          case Some(msg) => handleServerMsg(msg, meta, state)
          case None      =>
            ZIO.logWarning(
              s"Ignoring async message ${completion.message.getClass.getName}: expected ${state.msgClassTag.runtimeClass.getName}"
            )
      case LiveAsyncOwner.Component(cid) =>
        for
          (_, rendered) <- state.ref.get
          _             <- SocketComponentRuntime.handleComponentServerMessage(
                 cid,
                 completion.message,
                 rendered,
                 meta,
                 state
               )
        yield ()
end SocketOutbound
