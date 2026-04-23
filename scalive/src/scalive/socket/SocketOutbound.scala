package scalive
package socket

import zio.*
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

private[scalive] object SocketOutbound:
  def startServerFiber[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): RIO[Scope, Fiber.Runtime[Throwable, Unit]] =
    serverMsgStream(state).runForeach((msg, meta) => handleServerMsg(msg, meta, state)).fork

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
      state.inbox.shutdown *>
      state.outHub.shutdown *>
      clientFiber.interrupt.unit *>
      serverFiber.interrupt.unit

  private def serverMsgStream[Msg, Model](
    state: RuntimeState[Msg, Model]
  ): ZStream[Any, Nothing, (Msg, WebSocketMessage.Meta)] =
    (ZStream.fromZIO(state.lvStreamRef.get) ++ state.lvStreamRef.changes)
      .flatMapParSwitch(1, 1)(identity)
      .map(_ -> state.meta.copy(messageRef = None, eventType = "diff"))

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
            .toZIO(state.lv.update(currentModel)(msg))
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
end SocketOutbound
