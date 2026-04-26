package scalive

import zio.*
import zio.http.*
import zio.http.codec.HttpCodec
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object LiveRoutesLifecycleSpec extends ZIOSpecDefault:

  private val identityLayout: HtmlElement[?] => HtmlElement[?] = element => element
  private val rootTopic                                             = "lv:root"
  private val childTopic                                            = "lv:root-child"
  private val rootMeta = WebSocketMessage.Meta(Some(1), Some(1), rootTopic, "phx_join")

  private enum ChildMsg:
    case Increment

  private def childLiveView =
    new LiveView[ChildMsg, Int]:
      def mount = ZIO.succeed(0)
      def handleMessage(model: Int) = { case ChildMsg.Increment => ZIO.succeed(model + 1) }
      def render(model: Int): HtmlElement[ChildMsg] =
        button(phx.onClick(ChildMsg.Increment), model.toString)
      def subscriptions(model: Int) = ZStream.empty

  private def containsValue(diff: Diff, value: String): Boolean =
    diff match
      case Diff.Tag(_, dynamic, _, _, _, components, _, _) =>
        dynamic.exists(d => containsValue(d.diff, value)) || components.values.exists(
          containsValue(_, value)
        )
      case Diff.Value(current)  => current == value
      case Diff.Dynamic(_, diff) => containsValue(diff, value)
      case _                    => false

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  override def spec = suite("LiveRoutesLifecycleSpec")(
    test("runs mount then handleParams before disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Unit]:

               override val queryCodec: LiveQueryCodec[Option[String]] =
                 LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

               def mount = callsRef.update(_ :+ "mount").as(())

                 override def handleParams(model: Unit, params: Option[String], _url: URL) =
                  callsRef.update(_ :+ s"params:${params.getOrElse("")}").as(model)

               def handleMessage(model: Unit) = _ => ZIO.succeed(model)

                def render(model: Unit): HtmlElement[Unit] =
                 div("ok")

               def subscriptions(model: Unit) = ZStream.empty
        routes =
          LiveRoutes(layout = identityLayout)(
            Method.GET / Root -> liveHandler(lv)
          )
        response <- runRequest(routes, "/?q=1")
        calls    <- callsRef.get
      yield assertTrue(response.status == Status.Ok, calls == List("mount", "params:1"))
    },
    test("honors initial pushPatch with HTTP redirect") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        override def handleParams(model: Unit, _query: queryCodec.Out, _url: URL) =
          LiveContext.pushPatch("/target").as(model)

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div("ok")

        def subscriptions(model: Unit) = ZStream.empty

      val routes =
        LiveRoutes(layout = identityLayout)(
          Method.GET / Root -> liveHandler(lv)
        )

      for response <- runRequest(routes, "/")
      yield assertTrue(
        response.status.isRedirection,
        response.rawHeader("location").contains("/target")
      )
    },
    test("connected parent render registers nested LiveView for join") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _     <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry <- channel.nestedEntry(childTopic)
      yield assertTrue(entry.exists(_.parentTopic == rootTopic)))
    },
    test("nested LiveView joins and handles isolated events") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView))
        def subscriptions(model: Unit) = ZStream.empty

      val childMeta = rootMeta.copy(topic = childTopic)
      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry  <- channel.nestedEntry(childTopic).some
        joined      <- channel
                         .joinNested(childTopic, entry.token, false, childMeta, URL.root)
                         .timeoutFail(new RuntimeException("nested join timed out"))(2.seconds)
        childSocket <- channel.socket(childTopic).some
        replyFiber  <- childSocket.outbox.drop(1).runHead.fork
        _           <- ZIO.yieldNow
        _           <- channel.event(childTopic, event, childMeta.copy(eventType = "event"))
        reply       <- replyFiber.join.some.timeoutFail(new RuntimeException("nested event timed out"))(2.seconds)
      yield
        val childUpdated = reply match
          case (
                Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)),
                WebSocketMessage.Meta(_, _, `childTopic`, _)
              ) =>
            containsValue(diff, "1")
          case _ => false

        assertTrue(joined.isEmpty, childUpdated))
    },
    test("LiveComponents can render nested LiveViews") {
      object HostComponent extends LiveComponent[Unit, Unit, Unit]:
        def mount(props: Unit)                    = ZIO.unit
        def handleMessage(model: Unit)            = _ => ZIO.succeed(model)
        def render(model: Unit, self: ComponentRef[Unit]) =
          div(liveView("child", childLiveView))

      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(HostComponent, id = "host", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _     <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry <- channel.nestedEntry(childTopic)
      yield assertTrue(entry.exists(_.parentTopic == rootTopic)))
    }
  )
end LiveRoutesLifecycleSpec
