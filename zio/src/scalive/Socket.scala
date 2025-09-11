package scalive

import scalive.WebSocketMessage.LiveResponse
import zio.*
import zio.Queue
import zio.json.*
import zio.stream.ZStream
import zio.stream.SubscriptionRef
import scalive.WebSocketMessage.Payload

final case class Socket[Msg: JsonCodec, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Msg, WebSocketMessage.Meta)],
  outbox: ZStream[Any, Nothing, (Payload, WebSocketMessage.Meta)],
  shutdown: UIO[Unit]):
  val messageCodec = JsonCodec[Msg]

object Socket:
  def start[Msg: JsonCodec, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Socket[Msg, Model]] =
    for
      inbox  <- Queue.bounded[(Msg, WebSocketMessage.Meta)](4)
      outHub <- Hub.unbounded[(Payload, WebSocketMessage.Meta)]

      initModel <- lv.init
      modelVar = Var(initModel)
      el       = lv.view(modelVar)
      ref <- Ref.make((modelVar, el))

      initDiff = el.diff(trackUpdates = false)

      lvStreamRef <- SubscriptionRef.make(lv.subscriptions(initModel))

      clientMsgStream = ZStream.fromQueue(inbox)
      serverMsgStream = (ZStream.fromZIO(lvStreamRef.get) ++ lvStreamRef.changes)
                          .flatMapParSwitch(1, 1)(identity)
                          .map(_ -> meta.copy(messageRef = None, eventType = "diff"))

      clientFiber <- clientMsgStream.runForeach { (msg, meta) =>
                       for
                         (modelVar, el) <- ref.get
                         updatedModel   <- lv.update(modelVar.currentValue)(msg)
                         _ = modelVar.set(updatedModel)
                         _ <- lvStreamRef.set(lv.subscriptions(updatedModel))
                         diff    = el.diff()
                         payload = Payload.okReply(LiveResponse.Diff(diff))
                         _ <- outHub.publish(payload -> meta)
                       yield ()
                     }.fork
      serverFiber <- serverMsgStream.runForeach { (msg, meta) =>
                       for
                         (modelVar, el) <- ref.get
                         updatedModel   <- lv.update(modelVar.currentValue)(msg)
                         _       = modelVar.set(updatedModel)
                         diff    = el.diff()
                         payload = Payload.Diff(diff)
                         _ <- outHub.publish(payload -> meta)
                       yield ()
                     }.fork
      stop =
        inbox.shutdown *> outHub.shutdown *> clientFiber.interrupt.unit *> serverFiber.interrupt.unit
      outbox =
        ZStream.succeed(
          Payload.okReply(LiveResponse.InitDiff(initDiff)) -> meta
        ) ++ ZStream.unwrapScoped(ZStream.fromHubScoped(outHub)).filterNot {
          case (Payload.Diff(diff), _) => diff.isEmpty
          case _                       => false
        }
    yield Socket[Msg, Model](id, token, inbox, outbox, stop)
    end for
  end start
end Socket
