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

  private enum ParentMsg:
    case Rerender
    case HideChild

  private def childLiveView =
    new LiveView[ChildMsg, Int]:
      def mount = ZIO.succeed(0)
      def handleMessage(model: Int) = { case ChildMsg.Increment => ZIO.succeed(model + 1) }
      def render(model: Int): HtmlElement[ChildMsg] =
        button(phx.onClick(ChildMsg.Increment), model.toString)
      def subscriptions(model: Int) = ZStream.empty

  private def navigatingChildLiveView =
    new LiveView[ChildMsg, Int]:
      def mount = ZIO.succeed(0)
      def handleMessage(model: Int) = { case ChildMsg.Increment =>
        LiveContext.pushPatch("/child-next").as(model + 1)
      }
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
    test("renders nested LiveView content during disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        child = new LiveView[Unit, String]:
                  override val queryCodec: LiveQueryCodec[Option[String]] =
                    LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

                  def mount = callsRef.update(_ :+ "child mount").as("mount")

                  override def handleParams(model: String, params: Option[String], _url: URL) =
                    callsRef.update(_ :+ s"child params:${params.getOrElse("")}")
                      .as(s"$model:${params.getOrElse("")}")

                  def handleMessage(model: String) = _ => ZIO.succeed(model)

                  def render(model: String): HtmlElement[Unit] =
                    div(idAttr := "child-content", s"child $model")

                  def subscriptions(model: String) = ZStream.empty
        parent = new LiveView[Unit, Unit]:
                   def mount = ZIO.unit
                   def handleMessage(model: Unit) = _ => ZIO.succeed(model)
                   def render(model: Unit): HtmlElement[Unit] = div(liveView("child", child))
                   def subscriptions(model: Unit) = ZStream.empty
        routes =
          LiveRoutes(layout = identityLayout)(
            Method.GET / Root -> liveHandler(parent)
          )
        response <- runRequest(routes, "/?q=1")
        body     <- response.body.asString
        calls    <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        calls == List("child mount", "child params:1"),
        body.contains("child mount:1"),
        body.contains("data-phx-child-id=\"child\""),
        body.contains("data-phx-parent-id=\"lv:phx-"),
        body.contains("data-phx-session=")
      )
    },
    test("sticky nested LiveView renders sticky marker during disconnected render") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView, sticky = true))
        def subscriptions(model: Unit) = ZStream.empty

      val routes =
        LiveRoutes(layout = identityLayout)(
          Method.GET / Root -> liveHandler(parent)
        )

      for
        response <- runRequest(routes, "/")
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("data-phx-child-id=\"child\""),
        body.contains("data-phx-sticky")
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
        joined      <- channel.joinNested(childTopic, entry.token, false, childMeta, URL.root)
        childSocket <- channel.socket(childTopic).some
        outQueue    <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        _           <- childSocket.outbox.runForeach(outQueue.offer).forkScoped
        _           <- outQueue.take
        _           <- ZIO.yieldNow.repeatN(5)
        _           <- channel.event(childTopic, event, childMeta.copy(eventType = "event"))
        reply       <- outQueue.take
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
    },
    test("parent leave removes child sockets and nested registry entries") {
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
        _          <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry      <- channel.nestedEntry(childTopic).some
        _          <- channel.joinNested(childTopic, entry.token, false, rootMeta.copy(topic = childTopic), URL.root)
        childBefore <- channel.socket(childTopic)
        _          <- channel.leave(rootTopic)
        parentAfter <- channel.socket(rootTopic)
        childAfter <- channel.socket(childTopic)
        entryAfter <- channel.nestedEntry(childTopic)
      yield assertTrue(childBefore.nonEmpty, parentAfter.isEmpty, childAfter.isEmpty, entryAfter.isEmpty))
    },
    test("parent leave preserves sticky child socket and registry entry") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView, sticky = true))
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
        _           <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry       <- channel.nestedEntry(childTopic).some
        _           <- channel.joinNested(childTopic, entry.token, false, childMeta, URL.root)
        childSocket <- channel.socket(childTopic).some
        outQueue    <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        _           <- childSocket.outbox.runForeach(outQueue.offer).forkScoped
        _           <- outQueue.take
        _           <- channel.leave(rootTopic)
        parentAfter <- channel.socket(rootTopic)
        childAfter  <- channel.socket(childTopic)
        entryAfter  <- channel.nestedEntry(childTopic)
        _           <- channel.event(childTopic, event, childMeta.copy(eventType = "event"))
        reply       <- outQueue.take
      yield
        val childUpdated = reply match
          case (
                Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)),
                WebSocketMessage.Meta(_, _, `childTopic`, _)
              ) =>
            containsValue(diff, "1")
          case _ => false

        assertTrue(parentAfter.isEmpty, childAfter.nonEmpty, entryAfter.nonEmpty, childUpdated))
    },
    test("child leave removes only child socket and nested registry entry") {
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
        _       <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry   <- channel.nestedEntry(childTopic).some
        _       <- channel.joinNested(childTopic, entry.token, false, rootMeta.copy(topic = childTopic), URL.root)
        _       <- channel.leave(childTopic)
        parent  <- channel.socket(rootTopic)
        child   <- channel.socket(childTopic)
        removed <- channel.nestedEntry(childTopic)
      yield assertTrue(parent.nonEmpty, child.isEmpty, removed.isEmpty))
    },
    test("parent rerender preserves stable nested LiveView topic") {
      val parent = new LiveView[ParentMsg, Int]:
        def mount = ZIO.succeed(0)
        def handleMessage(model: Int) =
          case ParentMsg.Rerender => ZIO.succeed(model + 1)
          case ParentMsg.HideChild => ZIO.succeed(model)
        def render(model: Int): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Rerender), model.toString),
            liveView("child", childLiveView)
          )
        def subscriptions(model: Int) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        before <- channel.nestedEntry(childTopic).some
        _      <- channel.event(rootTopic, event, rootMeta.copy(eventType = "event"))
        after  <- channel.nestedEntry(childTopic).some
      yield assertTrue(before.parentTopic == rootTopic, after.parentTopic == rootTopic))
    },
    test("removed nested LiveView registry remains until client cleanup") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount = ZIO.succeed(true)
        def handleMessage(model: Boolean) =
          case ParentMsg.HideChild => ZIO.succeed(false)
          case ParentMsg.Rerender  => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.HideChild), "hide"),
            if model then liveView("child", childLiveView) else "hidden"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        before <- channel.nestedEntry(childTopic)
        _      <- channel.event(rootTopic, event, rootMeta.copy(eventType = "event"))
        after  <- channel.nestedEntry(childTopic)
      yield assertTrue(before.nonEmpty, after.nonEmpty))
    },
    test("nested child pushPatch emits navigation for child topic only") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", navigatingChildLiveView))
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
        _           <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry       <- channel.nestedEntry(childTopic).some
        _           <- channel.joinNested(childTopic, entry.token, false, childMeta, URL.root)
        childSocket <- channel.socket(childTopic).some
        outQueue    <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        _           <- childSocket.outbox.runForeach(outQueue.offer).forkScoped
        _           <- outQueue.take
        _           <- ZIO.yieldNow.repeatN(5)
        _           <- channel.event(childTopic, event, childMeta.copy(eventType = "event"))
        first       <- outQueue.take
        second      <- outQueue.take
        navigation = first match
                       case (Payload.LiveNavigation(_, _), _) => first
                       case _                                 => second
      yield assertTrue(
        navigation == (Payload.LiveNavigation("/child-next", WebSocketMessage.LivePatchKind.Push) -> childMeta.copy(messageRef = None, eventType = "event"))
      ))
    },
    test("parent patch does not reset stable nested child registration") {
      val parent = new LiveView[ParentMsg, Int]:
        def mount = ZIO.succeed(0)
        def handleMessage(model: Int) =
          case ParentMsg.Rerender =>
            LiveContext.pushPatch("/parent-next").as(model + 1)
          case ParentMsg.HideChild => ZIO.succeed(model)
        def render(model: Int): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Rerender), model.toString),
            liveView("child", childLiveView)
          )
        def subscriptions(model: Int) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        before <- channel.nestedEntry(childTopic).some
        _      <- channel.event(rootTopic, event, rootMeta.copy(eventType = "event"))
        after  <- channel.nestedEntry(childTopic).some
      yield assertTrue(before.parentTopic == rootTopic, after.parentTopic == rootTopic))
    }
  )
end LiveRoutesLifecycleSpec
