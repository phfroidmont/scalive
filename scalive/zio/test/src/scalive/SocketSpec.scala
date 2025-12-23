package scalive

import zio.*
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload

object SocketSpec extends ZIOSpecDefault:

  enum Msg:
    case FromClient
    case FromServer

  final case class Model(counter: Int = 0, staticFlag: Option[Boolean] = None)

  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private def makeLiveView(serverStream: ZStream[LiveContext, Nothing, Msg]) =
    new LiveView[Msg, Model]:
      def init: Model | RIO[LiveContext, Model] =
        LiveContext.staticChanged.map(flag => Model(staticFlag = Some(flag)))

      def update(model: Model): Msg => Model | RIO[LiveContext, Model] = {
        case Msg.FromClient => ZIO.succeed(model.copy(counter = model.counter + 1))
        case Msg.FromServer => ZIO.succeed(model.copy(counter = model.counter + 10))
      }

      def view(model: Dyn[Model]): HtmlElement =
        div(
          idAttr := "root",
          phx.onClick(Msg.FromClient),
          model(_.counter.toString)
        )

      def subscriptions(model: Model): ZStream[LiveContext, Nothing, Msg] = serverStream

  private def makeSocket(ctx: LiveContext, lv: LiveView[Msg, Model]) =
    Socket.start("id", "token", lv, ctx, meta)

  override def spec = suite("SocketSpec")(
    test("emits init diff and uses LiveContext") {
      val ctx = LiveContext(staticChanged = true)
      val lv  = makeLiveView(ZStream.empty)
      for
        socket <- makeSocket(ctx, lv)
        msgs   <- socket.outbox.take(1).runHead
      yield assertTrue(
        msgs.size == 1,
        msgs.head._1 match
          case Payload.Reply("ok", LiveResponse.InitDiff(_)) => true
          case _                                             => false
        ,
        msgs.head._2 == meta
      )
    },
    test("server stream emits diff") {
      val ctx = LiveContext(staticChanged = false)
      val lv  = makeLiveView(ZStream.succeed(Msg.FromServer))
      for
        socket <- makeSocket(ctx, lv)
        diff   <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diff._1.isInstanceOf[Payload.Diff])
    },
    test("shutdown stops outbox") {
      val ctx = LiveContext(staticChanged = false)
      val lv  = makeLiveView(ZStream.empty)
      for
        socket <- makeSocket(ctx, lv)
        _      <- socket.shutdown
        res    <- socket.outbox.runCollect
      yield assertTrue(res.nonEmpty)
    }
  )
end SocketSpec
