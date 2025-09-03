package scalive

import scalive.WebSocketMessage.LiveResponse
import zio.*
import zio.Queue
import zio.json.*
import zio.stream.ZStream

final case class Socket[Event: JsonCodec] private (
  id: String,
  token: String,
  inbox: Queue[(Event, WebSocketMessage.Meta)],
  outbox: ZStream[Any, Nothing, (LiveResponse, WebSocketMessage.Meta)],
  fiber: Fiber.Runtime[Nothing, Unit],
  shutdown: UIO[Unit]):
  val clientEventCodec = JsonCodec[Event]

object Socket:
  def start[Event: JsonCodec](
    id: String,
    token: String,
    lv: LiveView[Event],
    meta: WebSocketMessage.Meta
  ): URIO[Scope, Socket[Event]] =
    for
      inbox  <- Queue.bounded[(Event, WebSocketMessage.Meta)](4)
      outHub <- Hub.bounded[(LiveResponse, WebSocketMessage.Meta)](4)
      initDiff = lv.diff(trackUpdates = false)
      lvRef <- Ref.make(lv)
      fiber <- ZStream
                 .fromQueue(inbox)
                 .mapZIO { (msg, meta) =>
                   for
                     lv <- lvRef.get
                     _    = lv.handleEvent(msg)
                     diff = lv.diff()
                     _ <- outHub.publish(LiveResponse.Diff(diff) -> meta)
                   yield ()
                 }
                 .runDrain
                 .forkScoped
      stop = inbox.shutdown *> outHub.shutdown *> fiber.interrupt.unit
      diffStream <- ZStream.fromHubScoped(outHub)
      outbox = ZStream.succeed(LiveResponse.InitDiff(initDiff) -> meta) ++ diffStream
    yield Socket[Event](id, token, inbox, outbox, fiber, stop)
