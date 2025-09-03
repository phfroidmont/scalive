package scalive

import scalive.WebSocketMessage.LiveResponse
import zio.*
import zio.Queue
import zio.json.*
import zio.stream.ZStream

final case class Socket[Msg: JsonCodec, Model] private (
  id: String,
  token: String,
  inbox: Queue[(Msg, WebSocketMessage.Meta)],
  outbox: ZStream[Any, Nothing, (LiveResponse, WebSocketMessage.Meta)],
  fiber: Fiber.Runtime[Throwable, Unit],
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
      inbox     <- Queue.bounded[(Msg, WebSocketMessage.Meta)](4)
      outHub    <- Hub.bounded[(LiveResponse, WebSocketMessage.Meta)](4)
      initModel <- lv.init
      modelVar = Var(initModel)
      el       = lv.view(modelVar)
      ref <- Ref.make((modelVar, el))
      initDiff = el.diff(trackUpdates = false)
      fiber <- ZStream
                 .fromQueue(inbox)
                 .mapZIO { (msg, meta) =>
                   for
                     (modelVar, el) <- ref.get
                     updatedModel   <- lv.update(modelVar.currentValue)(msg)
                     _    = modelVar.set(updatedModel)
                     diff = el.diff()
                     _ <- outHub.publish(LiveResponse.Diff(diff) -> meta)
                   yield ()
                 }
                 .runDrain
                 .forkScoped
      stop = inbox.shutdown *> outHub.shutdown *> fiber.interrupt.unit
      diffStream <- ZStream.fromHubScoped(outHub)
      outbox = ZStream.succeed(LiveResponse.InitDiff(initDiff) -> meta) ++ diffStream
    yield Socket[Msg, Model](id, token, inbox, outbox, fiber, stop)
end Socket
