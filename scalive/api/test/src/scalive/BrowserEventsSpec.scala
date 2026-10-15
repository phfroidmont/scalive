package scalive

import zio.json.ast.Json
import zio.test.*

object BrowserEventsSpec extends ZIOSpecDefault:
  private enum Msg:
    case Moved(x: Int)
    case Closed

  private val Moved  = BrowserToServerEvent[Int]("moved")
  private val Closed = BrowserToServerEvent[Json]("closed")

  override def spec = suite("BrowserEventsSpec")(
    test("decodes a declared event into its message and ignores undeclared names") {
      val events = BrowserEvents.on(Moved)(Msg.Moved(_)).on(Closed)(_ => Msg.Closed)

      assertTrue(
        events.decode("moved", Json.Num(3)) == Some(Right(Msg.Moved(3))),
        events.decode("closed", Json.Obj()) == Some(Right(Msg.Closed)),
        events.decode("moved", Json.Str("left")).exists(_.isLeft),
        events.decode("resized", Json.Num(3)).isEmpty
      )
    },
    test("rejects declaring the same name twice") {
      val duplicate =
        scala.util.Try(BrowserEvents.on(Moved)(Msg.Moved(_)).on(Moved)(Msg.Moved(_)))

      assertTrue(duplicate.failed.toOption.exists(_.getMessage.contains("'moved'")))
    }
  )
end BrowserEventsSpec
