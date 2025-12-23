package scalive

import zio.*
import zio.Queue
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

final case class Socket[Msg, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Payload.Event, WebSocketMessage.Meta)],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  shutdown: UIO[Unit])

object Socket:
  def start[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Socket[Msg, Model]] =
    ZIO.logAnnotate("lv", id) {
      for
        inbox  <- Queue.bounded[(Payload.Event, WebSocketMessage.Meta)](4)
        outHub <- Hub.unbounded[(Payload, WebSocketMessage.Meta)]

        initModel <- normalize(lv.init, ctx)
        modelVar = Var(initModel)
        el       = lv.view(modelVar)
        ref <- Ref.make((modelVar, el))

        initDiff = el.diff(trackUpdates = false)

        lvStreamRef <-
          SubscriptionRef.make(lv.subscriptions(initModel).provideLayer(ZLayer.succeed(ctx)))

        clientMsgStream = ZStream.fromQueue(inbox)
        serverMsgStream = (ZStream.fromZIO(lvStreamRef.get) ++ lvStreamRef.changes)
                            .flatMapParSwitch(1, 1)(identity)
                            .map(_ -> meta.copy(messageRef = None, eventType = "diff"))

        clientFiber <- clientMsgStream.runForeach { (event, meta) =>
                         for
                           (modelVar, el) <- ref.get
                           f              <-
                             ZIO
                               .succeed(el.findBinding(event.event))
                               .someOrFail(
                                 new IllegalArgumentException(
                                   s"No binding found for event ID ${event.event}"
                                 )
                               )
                           updatedModel <-
                             normalize(lv.update(modelVar.currentValue)(f(event.params)), ctx)
                           _ = modelVar.set(updatedModel)
                           _ <- lvStreamRef.set(
                                  lv.subscriptions(updatedModel).provideLayer(ZLayer.succeed(ctx))
                                )
                           diff    = el.diff()
                           payload = Payload.okReply(LiveResponse.Diff(diff))
                           _ <- outHub.publish(payload -> meta)
                         yield ()
                       }.fork
        serverFiber <- serverMsgStream.runForeach { (msg, meta) =>
                         for
                           (modelVar, el) <- ref.get
                           updatedModel   <- normalize(lv.update(modelVar.currentValue)(msg), ctx)
                           _       = modelVar.set(updatedModel)
                           diff    = el.diff()
                           payload = Payload.Diff(diff)
                           _ <- outHub.publish(payload -> meta)
                         yield ()
                       }.fork
        stop =
          outHub.publish(Payload.Close -> meta) *>
            inbox.shutdown *>
            outHub.shutdown *>
            clientFiber.interrupt.unit *>
            serverFiber.interrupt.unit
        outbox =
          ZStream.succeed(
            Payload.okReply(LiveResponse.InitDiff(initDiff)) -> meta
          ) ++ ZStream
            .unwrapScoped(ZStream.fromHubScoped(outHub)).filterNot {
              case (Payload.Diff(diff), _) => diff.isEmpty
              case _                       => false
            }
      yield Socket[Msg, Model](id, token, inbox, outbox, stop)
    }
  end start
end Socket
