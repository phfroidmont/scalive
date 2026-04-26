package scalive

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object ClientEventsSpec extends ZIOSpecDefault:

  enum Msg:
    case EmitEvent
    case EmitJs

  final case class Model(counter: Int = 0)

  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private def makeSocket(lv: LiveView[Msg, Model]) =
    Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)

  private def eventsFromDiff(diff: Diff): Vector[Diff.Event] =
    diff match
      case Diff.Tag(_, _, events, _, _, _, _, _) => events.toVector
      case _                                      => Vector.empty

  private def encodedEventsFromDiff(diff: Diff): Vector[(String, Json)] =
    summon[JsonEncoder[Diff]]
      .encodeJson(diff, None)
      .toString
      .fromJson[Json]
      .toOption
      .collect { case Json.Obj(fields) => fields }
      .flatMap(_.collectFirst { case ("e", Json.Arr(values)) => values })
      .map(_.collect { case Json.Arr(Chunk(Json.Str(name), payload)) => name -> payload }.toVector)
      .getOrElse(Vector.empty)

  override def spec = suite("ClientEventsSpec")(
    test("includes init pushEvent in join diff") {
      val lv = new LiveView[Msg, Model]:
        def mount =
          LiveContext.pushEvent("ready", Map("ok" -> true)).as(Model())

        def handleMessage(model: Model) = {
          case Msg.EmitEvent => ZIO.succeed(model)
          case Msg.EmitJs    => ZIO.succeed(model)
        }

        def render(model: Model): HtmlElement[Msg] =
          div(idAttr := "root", "ready")

        def subscriptions(model: Model) = ZStream.empty

      for
        socket <- makeSocket(lv)
        msg    <- socket.outbox.take(1).runHead.some
      yield
        val (events, encodedEvents) = msg._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(diff)) =>
            (eventsFromDiff(diff), encodedEventsFromDiff(diff))
          case _ =>
            (Vector.empty, Vector.empty)

        assertTrue(
          msg._1.isInstanceOf[Payload.Reply],
          events.length == 1,
          events.headOption.exists(_.name == "ready"),
          events.headOption.exists(_.payload == Json.Obj("ok" -> Json.Bool(true))),
          encodedEvents == Vector("ready" -> Json.Obj("ok" -> Json.Bool(true)))
        )
    },
    test("emits diff when only pushEvent changes") {
      val lv = new LiveView[Msg, Model]:
        def mount = Model()

        def handleMessage(model: Model) = {
          case Msg.EmitEvent =>
            LiveContext.pushEvent("tick", Map("value" -> 1)).as(model)
          case Msg.EmitJs    => ZIO.succeed(model)
        }

        def render(model: Model): HtmlElement[Msg] =
          div(idAttr := "root", "constant")

        def subscriptions(model: Model) = ZStream.succeed(Msg.EmitEvent)

      for
        socket  <- makeSocket(lv)
        server  <- socket.outbox.drop(1).runHead.some
      yield
        val events = server._1 match
          case Payload.Diff(diff) => eventsFromDiff(diff)
          case _                  => Vector.empty

        val encodedEvents = server._1 match
          case Payload.Diff(diff) => encodedEventsFromDiff(diff)
          case _                  => Vector.empty

        assertTrue(
          server._1.isInstanceOf[Payload.Diff],
          events.length == 1,
          events.headOption.exists(_.name == "tick"),
          events.headOption.exists(_.payload == Json.Obj("value" -> Json.Num(1))),
          encodedEvents == Vector("tick" -> Json.Obj("value" -> Json.Num(1)))
        )
    },
    test("pushJs sends js:exec event with encoded command") {
      val lv = new LiveView[Msg, Model]:
        def mount = Model()

        def handleMessage(model: Model) = {
          case Msg.EmitEvent => ZIO.succeed(model)
          case Msg.EmitJs    =>
            LiveContext.pushJs(JS.show(to = "#modal")).as(model)
        }

        def render(model: Model): HtmlElement[Msg] =
          div(idAttr := "root", "constant")

        def subscriptions(model: Model) = ZStream.succeed(Msg.EmitJs)

      for
        socket <- makeSocket(lv)
        server <- socket.outbox.drop(1).runHead.some
      yield
        val maybeCmd =
          server._1 match
            case Payload.Diff(diff) =>
              eventsFromDiff(diff).headOption.flatMap(_.payload match
                case Json.Obj(fields) =>
                  fields.collectFirst { case ("cmd", Json.Str(value)) => value }
                case _ => None
              )
            case _ =>
              None

        assertTrue(
          server._1.isInstanceOf[Payload.Diff],
          maybeCmd.exists(_.contains("\"show\"")),
          maybeCmd.exists(_.contains("#modal"))
        )
    },
    test("component events include pushEvent and title updates") {
      object EffectsComponent extends LiveComponent[Unit, EffectsComponent.Msg.type, Unit]:
        object Msg

        def mount(props: Unit) = ZIO.unit

        def handleMessage(model: Unit) = {
          case Msg =>
            LiveContext.pushEvent("component", Map("ok" -> true)) *>
              LiveContext.putTitle("Component title").as(model)
        }

        def render(model: Unit, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), "emit")

      val lv = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(idAttr := "root", liveComponent(EffectsComponent, id = "effects", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _     <- socket.inbox.offer(event -> meta)
        reply <- replyFiber.join.some
      yield
        val (events, title) = reply._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(diff @ Diff.Tag(_, _, _, _, title, _, _, _))
              ) =>
            (eventsFromDiff(diff), title)
          case _ => (Vector.empty, None)

        assertTrue(
          events == Vector(Diff.Event("component", Json.Obj("ok" -> Json.Bool(true)))),
          title.contains("Component title")
        )
    }
  )
end ClientEventsSpec
