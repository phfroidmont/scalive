package scalive

import zio.*
import zio.json.*
import zio.Queue
import zio.stream.ZStream

final case class Socket[Event: JsonCodec] private (
  id: String,
  token: String,
  // lv: LiveView[CliEvt, SrvEvt],
  inbox: Queue[(Event, WebSocketMessage.Meta)],
  outbox: Hub[(Diff, WebSocketMessage.Meta)],
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
      outbox <- Hub.bounded[(Diff, WebSocketMessage.Meta)](4)
      initDiff = lv.diff(trackUpdates = false)
      _     <- outbox.publish(initDiff -> meta).unit
      _     <- outbox.size.flatMap(s => ZIO.log(s.toString)) // FIXME
      lvRef <- Ref.make(lv)
      fiber <- ZStream
                 .fromQueue(inbox)
                 .mapZIO { (msg, meta) =>
                   for
                     lv <- lvRef.get
                     _    = lv.handleEvent(msg)
                     diff = lv.diff()
                     _ <- outbox.publish(diff -> meta)
                   yield ()
                 }
                 .runDrain
                 .forkScoped
      stop = inbox.shutdown *> outbox.shutdown *> fiber.interrupt.unit
    yield Socket[Event](id, token, inbox, outbox, fiber, stop)
