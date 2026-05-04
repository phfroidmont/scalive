package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.*
import zio.http.codec.HttpCodec
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object LiveRoutesLifecycleSpec extends ZIOSpecDefault:

  private val rootTopic        = "lv:root"
  private val childTopic       = "lv:child"
  private val secondChildTopic = "lv:second"
  private val grandchildTopic  = "lv:grandchild"
  private val rootMeta         = WebSocketMessage.Meta(Some(1), Some(1), rootTopic, "phx_join")

  private enum ChildMsg:
    case Increment
    case PushPatch(to: String)
    case ReplacePatch(to: String)
    case PushNavigate(to: String)
    case ReplaceNavigate(to: String)
    case Redirect(to: String)

  private enum ParentMsg:
    case Rerender
    case HideChild
    case ShowChild
    case AddSecondChild

  private def childLiveView =
    new LiveView[ChildMsg, Int]:
      def mount(ctx: MountContext) =
        ZIO.succeed(0)

      def handleMessage(model: Int, ctx: MessageContext) =
            case ChildMsg.Increment => ZIO.succeed(model + 1)
            case _                  => ZIO.succeed(model)

      def render(model: Int): HtmlElement[ChildMsg] =
        button(phx.onClick(ChildMsg.Increment), model.toString)

  private def nestedChildLiveView =
    new LiveView[ChildMsg, Int]:
      def mount(ctx: MountContext) =
        ZIO.succeed(0)

      def handleMessage(model: Int, ctx: MessageContext) =
            case ChildMsg.Increment => ZIO.succeed(model + 1)
            case _                  => ZIO.succeed(model)

      def render(model: Int): HtmlElement[ChildMsg] =
        div(
          button(phx.onClick(ChildMsg.Increment), s"child $model"),
          liveView("grandchild", childLiveView)
        )

  private def navigatingChildLiveView(command: ChildMsg) =
    new LiveView[ChildMsg, Int]:
      def mount(ctx: MountContext) =
        ZIO.succeed(0)

      def handleMessage(model: Int, ctx: MessageContext) =
            case ChildMsg.Increment           => ZIO.succeed(model + 1)
            case ChildMsg.PushPatch(to)       => ctx.nav.pushPatch(to).as(model + 1)
            case ChildMsg.ReplacePatch(to)    => ctx.nav.replacePatch(to).as(model + 1)
            case ChildMsg.PushNavigate(to)    => ctx.nav.pushNavigate(to).as(model + 1)
            case ChildMsg.ReplaceNavigate(to) => ctx.nav.replaceNavigate(to).as(model + 1)
            case ChildMsg.Redirect(to)        => ctx.nav.redirect(to).as(model + 1)

      def render(model: Int): HtmlElement[ChildMsg] =
        button(phx.onClick(command), model.toString)

  private def redirectAwareParent(child: LiveView[ChildMsg, Int]) =
    new LiveView[Unit, String]:
      override val queryCodec: LiveQueryCodec[Option[String]] =
        LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("redirect").optional)

      def mount(ctx: MountContext) =
        ZIO.succeed("none")

      override def handleParams(model: String, redirect: Option[String], _url: URL, ctx: ParamsContext) =
        ZIO.succeed(redirect.getOrElse("none"))

      def handleMessage(model: String, ctx: MessageContext) =
        (_: Unit) => ZIO.succeed(model)

      def render(model: String): HtmlElement[Unit] =
        div(
          p(s"Redirect: $model"),
          liveView("child", child)
        )

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

  private def url(path: String): UIO[URL] =
    ZIO.fromEither(URL.decode(path)).orDie

  private def click(path: Vector[String], attrIndex: Int = 0): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = Json.Obj.empty
    )

  private def joinRoot[Msg: ClassTag, Model](
    channel: LiveChannel,
    liveView: LiveView[Msg, Model],
    initialUrl: URL = URL.root
  ): RIO[Scope, Unit] =
    val ctx = LiveContext(
      staticChanged = false,
      nestedLiveViews = channel.nestedRuntime(rootTopic)
    )
    channel.join(rootTopic, "root-token", liveView, ctx, rootMeta, initialUrl)

  private def joinNested(
    channel: LiveChannel,
    topic: String,
    meta: Meta,
    initialUrl: URL = URL.root
  ): RIO[Scope, Socket[?, ?]] =
    for
      entryOpt  <- channel.nestedEntry(topic)
      entry     <- ZIO.fromOption(entryOpt).orElseFail(new NoSuchElementException(topic))
      _         <- channel.joinNested(topic, entry.token, false, meta, initialUrl)
      socketOpt <- channel.socket(topic)
      socket    <- ZIO.fromOption(socketOpt).orElseFail(new NoSuchElementException(topic))
    yield socket

  private def subscribe(socket: Socket[?, ?]): RIO[Scope, Queue[(Payload, Meta)]] =
    for
      outQueue <- Queue.unbounded[(Payload, Meta)]
      _        <- socket.outbox.runForeach(outQueue.offer).forkScoped
      _        <- outQueue.take
    yield outQueue

  private def takeMatching(
    outQueue: Queue[(Payload, Meta)]
  )(predicate: (Payload, Meta) => Boolean): Task[(Payload, Meta)] =
    outQueue.take
      .repeatUntil { case (payload, meta) => predicate(payload, meta) }
      .timeoutFail(new RuntimeException("Timed out waiting for matching socket payload"))(3.seconds)

  override def spec = suite("LiveRoutesLifecycleSpec")(
    test("runs mount then handleParams before disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Unit]:
               override val queryCodec: LiveQueryCodec[Option[String]] =
                 LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

               def mount(ctx: MountContext) =
                  callsRef.update(_ :+ "mount").as(())

               override def handleParams(model: Unit, params: Option[String], _url: URL, ctx: ParamsContext) =
                 callsRef.update(_ :+ s"params:${params.getOrElse("")}").as(model)

               def handleMessage(model: Unit, ctx: MessageContext) =
                 (_: Unit) => ZIO.succeed(model)

               def render(model: Unit): HtmlElement[Unit] = div("ok")
        routes   = scalive.Live.router(scalive.live(lv))
        response <- runRequest(routes, "/?q=1")
        calls    <- callsRef.get
      yield assertTrue(response.status == Status.Ok, calls == List("mount", "params:1"))
    },
    test("honors initial pushPatch with HTTP redirect") {
      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        override def handleParams(model: Unit, query: queryCodec.Out, _url: URL, ctx: ParamsContext) =
          ctx.nav.pushPatch("/target").as(model)

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] = div("ok")

      val routes = scalive.Live.router(scalive.live(lv))

      for response <- runRequest(routes, "/")
      yield assertTrue(response.status.isRedirection, response.rawHeader("location").contains("/target"))
    },
    test("renders nested LiveView content during disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        child = new LiveView[Unit, String]:
                  override val queryCodec: LiveQueryCodec[Option[String]] =
                    LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

                  def mount(ctx: MountContext) =
                    callsRef.update(_ :+ "child mount").as("mount")

                  override def handleParams(model: String, params: Option[String], _url: URL, ctx: ParamsContext) =
                    callsRef.update(_ :+ s"child params:${params.getOrElse("")}")
                      .as(s"$model:${params.getOrElse("")}")

                  def handleMessage(model: String, ctx: MessageContext) =
                    (_: Unit) => ZIO.succeed(model)

                  def render(model: String): HtmlElement[Unit] =
                    div(idAttr := "child-content", s"child $model")
        parent = new LiveView[Unit, Unit]:
                   def mount(ctx: MountContext) =
                     ZIO.unit

                   def handleMessage(model: Unit, ctx: MessageContext) =
                     (_: Unit) => ZIO.succeed(model)

                   def render(model: Unit): HtmlElement[Unit] = div(liveView("child", child))
        routes   = scalive.Live.router(scalive.live(parent))
        response <- runRequest(routes, "/?q=1")
        body     <- response.body.asString
        calls    <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        calls == List("child mount", "child params:1"),
        body.contains("child mount:1"),
        body.contains("data-phx-child-id=\"child\""),
        body.contains("data-phx-parent-id=\"phx-"),
        body.contains("data-phx-session=")
      )
    },
    test("connected parent render registers nested LiveView for join") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] = div(liveView("child", childLiveView))

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        _       <- joinRoot(channel, parent)
        entry   <- channel.nestedEntry(childTopic)
      yield assertTrue(entry.exists(_.parentTopic == rootTopic)))
    },
    test("nested LiveView joins without URL use the parent current URL") {
      val child = new LiveView[Unit, String]:
        override val queryCodec: LiveQueryCodec[Option[String]] =
          LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

        def mount(ctx: MountContext) =
          ZIO.succeed("mount")

        override def handleParams(model: String, params: Option[String], _url: URL, ctx: ParamsContext) =
          ZIO.succeed(params.getOrElse(model))

        def handleMessage(model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: String): HtmlElement[Unit] =
          div(s"child:$model")

      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] = div(liveView("child", child))

      ZIO.scoped(for
        initialUrl <- url("/parent?q=1")
        channel    <- LiveChannel.make(TokenConfig.default)
        _          <- joinRoot(channel, parent, initialUrl)
        entry      <- channel.nestedEntry(childTopic).some
        result     <- channel.tryJoinNested(
                        childTopic,
                        entry.token,
                        staticChanged = false,
                        rootMeta.copy(topic = childTopic),
                        initialUrl = None
                      )
        childSocket <- channel.socket(childTopic).some
        init        <- childSocket.outbox.take(1).runHead.some
      yield
        val renderedWithParentUrl = init._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(diff)) =>
            containsValue(diff, "child:1")
          case _ => false

        assertTrue(result == NestedJoinResult.Joined, renderedWithParentUrl)
      )
    },
    test("dynamically added nested LiveViews can join descendants and handle events") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount(ctx: MountContext) =
          ZIO.succeed(false)

        def handleMessage(model: Boolean, ctx: MessageContext) =
              case ParentMsg.ShowChild => ZIO.succeed(true)
              case ParentMsg.HideChild => ZIO.succeed(false)
              case _                   => ZIO.succeed(model)

        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.ShowChild), "show"),
            if model then liveView("child", nestedChildLiveView) else "hidden"
          )

      val showChild      = click(Vector("root:div", "tag:0:button"))
      val grandchildMeta = rootMeta.copy(topic = grandchildTopic)

      ZIO.scoped(for
        channel      <- LiveChannel.make(TokenConfig.default)
        _            <- joinRoot(channel, parent)
        rootSocket   <- channel.socket(rootTopic).some
        rootOutbox   <- subscribe(rootSocket)
        before       <- channel.nestedEntry(childTopic)
        _            <- channel.event(rootTopic, showChild, rootMeta.copy(eventType = "event"))
        _            <- takeMatching(rootOutbox) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(_)), meta) =>
                            meta.topic == rootTopic
                          case _ => false
                        }
        childEntry   <- channel.nestedEntry(childTopic).some
        _            <- joinNested(channel, childTopic, rootMeta.copy(topic = childTopic))
        grandEntry   <- channel.nestedEntry(grandchildTopic).some
        grandSocket  <- joinNested(channel, grandchildTopic, grandchildMeta)
        grandOutbox  <- subscribe(grandSocket)
        _            <- channel.event(grandchildTopic, click(Vector("root:button")), grandchildMeta.copy(eventType = "event"))
        grandUpdated <- takeMatching(grandOutbox) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), meta) =>
                            meta.topic == grandchildTopic && containsValue(diff, "1")
                          case _ => false
                        }
      yield assertTrue(
        before.isEmpty,
        childEntry.parentTopic == rootTopic,
        grandEntry.parentTopic == childTopic,
        grandUpdated._2.topic == grandchildTopic
      ))
    },
    test("multiple nested children of the same LiveView type keep distinct topics") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView), liveView("second", childLiveView))

      val childMeta  = rootMeta.copy(topic = childTopic)
      val secondMeta = rootMeta.copy(topic = secondChildTopic)

      ZIO.scoped(for
        channel      <- LiveChannel.make(TokenConfig.default)
        _            <- joinRoot(channel, parent)
        childEntry   <- channel.nestedEntry(childTopic).some
        secondEntry  <- channel.nestedEntry(secondChildTopic).some
        childSocket  <- joinNested(channel, childTopic, childMeta)
        secondSocket <- joinNested(channel, secondChildTopic, secondMeta)
        childOutbox  <- subscribe(childSocket)
        secondOutbox <- subscribe(secondSocket)
        _            <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        _            <- channel.event(secondChildTopic, click(Vector("root:button")), secondMeta.copy(eventType = "event"))
        childUpdated <- takeMatching(childOutbox) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), meta) =>
                            meta.topic == childTopic && containsValue(diff, "1")
                          case _ => false
                        }
        secondUpdated <- takeMatching(secondOutbox) {
                           case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), meta) =>
                             meta.topic == secondChildTopic && containsValue(diff, "1")
                           case _ => false
                         }
      yield assertTrue(
        childEntry.parentTopic == rootTopic,
        secondEntry.parentTopic == rootTopic,
        childTopic != secondChildTopic,
        childUpdated._2.topic == childTopic,
        secondUpdated._2.topic == secondChildTopic
      ))
    },
    test("duplicate nested LiveView ids fail the parent render") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView), liveView("child", childLiveView))

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        exit    <- joinRoot(channel, parent).exit
      yield
        val failedWithDuplicate = exit match
          case Exit.Failure(cause) => cause.prettyPrint.contains("Duplicate nested LiveView id 'child'")
          case Exit.Success(_)     => false

        assertTrue(failedWithDuplicate))
    },
    test("LiveComponents can render nested LiveViews") {
      object HostComponent extends LiveComponent[Unit, Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) =
          ZIO.unit

        def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(props: Unit, model: Unit, self: ComponentRef[Unit]) =
          div(liveView("child", childLiveView))

      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(HostComponent, id = "host", props = ()))

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        _       <- joinRoot(channel, parent)
        entry   <- channel.nestedEntry(childTopic)
      yield assertTrue(entry.exists(_.parentTopic == rootTopic)))
    },
    test("parent leave removes child sockets and nested registry entries") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] = div(liveView("child", childLiveView))

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        _       <- joinRoot(channel, parent)
        entry   <- channel.nestedEntry(childTopic).some
        _       <- channel.joinNested(childTopic, entry.token, false, rootMeta.copy(topic = childTopic), URL.root)
        childBefore <- channel.socket(childTopic)
        _           <- channel.leave(rootTopic)
        parentAfter <- channel.socket(rootTopic)
        childAfter  <- channel.socket(childTopic)
        entryAfter  <- channel.nestedEntry(childTopic)
      yield assertTrue(childBefore.nonEmpty, parentAfter.isEmpty, childAfter.isEmpty, entryAfter.isEmpty))
    },
    test("nested child pushNavigate emits a live redirect") {
      val parent    = redirectAwareParent(navigatingChildLiveView(ChildMsg.PushNavigate("/thermo?redirect=push")))
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        navigation  <- takeMatching(outQueue) {
                         case (Payload.LiveRedirect(to, LivePatchKind.Push, _), meta) =>
                           meta.topic == childTopic && to == "/thermo?redirect=push"
                         case _ => false
                       }
      yield assertTrue(navigation._2.topic == childTopic))
    },
    test("nested child pushPatch emits navigation and parent live_patch updates params") {
      val parent    = redirectAwareParent(navigatingChildLiveView(ChildMsg.PushPatch("/thermo?redirect=patch")))
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        navigation  <- takeMatching(outQueue) {
                         case (Payload.LiveNavigation("/thermo?redirect=patch", LivePatchKind.Push), meta) =>
                           meta.topic == childTopic
                         case _ => false
                       }
        reply <- channel.livePatch(rootTopic, "/thermo?redirect=patch", rootMeta.copy(eventType = "live_patch"))
      yield
        val parentUpdated = reply match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) => containsValue(diff, "Redirect: patch")
          case _                                                       => false

        assertTrue(navigation._2.topic == childTopic, parentUpdated))
    },
    test("nested child redirect emits an internal redirect") {
      val parent    = redirectAwareParent(navigatingChildLiveView(ChildMsg.Redirect("/thermo?redirect=redirect")))
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        redirect    <- takeMatching(outQueue) {
                         case (Payload.Redirect("/thermo?redirect=redirect", _), meta) =>
                           meta.topic == childTopic
                         case _ => false
                       }
      yield assertTrue(redirect._2.topic == childTopic))
    },
    test("parent patch does not reset stable nested child registration") {
      val parent = new LiveView[ParentMsg, Int]:
        def mount(ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(model: Int, ctx: MessageContext) =
              case ParentMsg.Rerender => ctx.nav.pushPatch("/parent-next").as(model + 1)
              case ParentMsg.HideChild => ZIO.succeed(model)
              case _                   => ZIO.succeed(model)

        def render(model: Int): HtmlElement[ParentMsg] =
          div(button(phx.onClick(ParentMsg.Rerender), model.toString), liveView("child", childLiveView))

      val patchEvent: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        _       <- joinRoot(channel, parent)
        before  <- channel.nestedEntry(childTopic).some
        _       <- channel.event(rootTopic, patchEvent, rootMeta.copy(eventType = "event"))
        after   <- channel.nestedEntry(childTopic).some
      yield assertTrue(before.parentTopic == rootTopic, after.parentTopic == rootTopic))
    }
  )
end LiveRoutesLifecycleSpec
