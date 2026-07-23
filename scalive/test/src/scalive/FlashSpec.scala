package scalive

import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object FlashSpec extends ZIOSpecDefault:
  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")
  private val Info  = FlashKind("info")
  private val Error = FlashKind("error")

  private def flashParamsRuntime[Msg, Model]: LiveRouteParamsRuntime[?, Msg, Model] =
    LiveRouteParamsRuntime.routed(
      zio.http.codec.PathCodec.empty / zio.http.codec.PathCodec.string("page"),
      LiveParamsCodec.path[String]
    )

  private enum Msg:
    case Show
    case ShowBoth
    case ClearInfo
    case ClearAll
    case PushPatch
    case ReplacePatch
    case PushNavigate
    case Rerender

  private def containsValue(diff: Diff, value: String): Boolean =
    diff match
      case Diff.Tag(_, dynamic, _, _, _, components, _, _) =>
        dynamic.exists(d => containsValue(d.diff, value)) || components.values.exists(
          containsValue(_, value)
        )
      case Diff.Comprehension(_, entries, _, _, _) =>
        entries.exists {
          case Diff.Dynamic(_, diff)       => containsValue(diff, value)
          case Diff.IndexMerge(_, _, diff) => containsValue(diff, value)
          case _                           => false
        }
      case Diff.Value(current)  => current == value
      case Diff.Dynamic(_, diff) => containsValue(diff, value)
      case _                    => false

  private def diffFromReply(payload: Payload): Option[Diff] =
    payload match
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(diff)) => Some(diff)
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff))     => Some(diff)
      case Payload.Diff(diff)                                         => Some(diff)
      case _                                                          => None

  private def responseCookie(response: Response, name: String): Option[String] =
    response
      .rawHeader("set-cookie")
      .flatMap(_.split(";", 2).headOption)
      .collect { case value if value.startsWith(s"$name=") => value.drop(name.length + 1) }

  private def click(path: Vector[String], attrIndex: Int = 0): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = Json.Obj.empty
    )

  private def withOutbox[Msg, Model, A](socket: Socket[Msg, Model])(
    f: Queue[(Payload, WebSocketMessage.Meta)] => Task[A]
  ): Task[A] =
    for
      queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
      fiber <- socket.outbox.runForeach(queue.offer).fork
      _     <- queue.take
      value <- f(queue).ensuring(fiber.interrupt)
    yield value

  override def spec = suite("FlashSpec")(
    test("snapshot exposes typed flash kinds") {
      for
        ref <- Ref.make(scalive.socket.FlashRuntimeState.empty)
        runtime = new scalive.socket.SocketFlashRuntime(ref)
        ctx = LiveContext(staticChanged = false, flash = runtime).messageContext[Unit, Unit]
        _        <- ctx.flash.put(Info, "Saved")
        snapshot <- ctx.flash.snapshot
      yield assertTrue(snapshot == Map(Info -> "Saved"))
    },
    test("root events can put and client-clear keyed flash") {
      val lv = new LiveView[Msg, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
              case Msg.Show => ctx.flash.put(Info, "Saved").as(model)
              case _        => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(phx.onClick(Msg.Show), "show"),
            flash(Info)(message =>
              p(idAttr := "flash", phx.clearFlash, phx.value("key") := Info.value, message)
            )
          )

      val clear: Payload.Event = Payload.Event(
        `type` = "click",
        event = "lv:clear-flash",
        value = Json.Obj("key" -> Json.Str(Info.value))
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _       <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      shown   <- outbox.take
                      _       <- socket.inbox.offer(clear -> meta)
                      cleared <- outbox.take
                    yield assertTrue(
                      diffFromReply(shown._1).exists(containsValue(_, "Saved")),
                      !diffFromReply(cleared._1).exists(containsValue(_, "Saved"))
                    )
                  }
      yield result
    },
    test("server-side clear removes keyed or all flash") {
      val lv = new LiveView[Msg, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
              case Msg.ShowBoth =>
                ctx.flash.put(Info, "Info") *>
                  ctx.flash.put(Error, "Error").as(model)
              case Msg.ClearInfo => ctx.flash.clear(Info).as(model)
              case Msg.ClearAll  => ctx.flash.clearAll.as(model)
              case _             => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(idAttr := "show", phx.onClick(Msg.ShowBoth), "show"),
            button(idAttr := "clear-info", phx.onClick(Msg.ClearInfo), "clear-info"),
            button(idAttr := "clear-all", phx.onClick(Msg.ClearAll), "clear-all"),
            flash(Info)(message => p(idAttr := "info", message)),
            flash(Error)(message => p(idAttr := "error", message))
          )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:0:button"), 1) -> meta)
                      shown     <- outbox.take
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:1:button"), 1) -> meta)
                      clearInfo <- outbox.take
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:2:button"), 1) -> meta)
                      clearAll  <- outbox.take
                    yield assertTrue(
                      diffFromReply(shown._1).exists(diff =>
                        containsValue(diff, "Info") && containsValue(diff, "Error")
                      ),
                      !diffFromReply(clearInfo._1).exists(containsValue(_, "Info")),
                      !diffFromReply(clearAll._1).exists(diff =>
                        containsValue(diff, "Info") || containsValue(diff, "Error")
                      )
                    )
                  }
      yield result
    },
    test("redirect flash only includes flash set during the redirecting event") {
      enum RootMsg:
        case SetError
        case Redirect

      val tokenConfig = TokenConfig.default
      val lv = new LiveView[RootMsg, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
              case RootMsg.SetError => ctx.flash.put(Error, "stale").as(model)
              case RootMsg.Redirect =>
                ctx.flash.put(Info, "fresh") *>
                  ctx.nav.redirectUnsafe("/target").as(model)

        def render(model: Unit): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.SetError), "set-error"),
            button(phx.onClick(RootMsg.Redirect), "redirect"),
            flash(Info)(message => p(idAttr := "info", message)),
            flash(Error)(message => p(idAttr := "error", message))
          )

      for
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    tokenConfig = tokenConfig
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _        <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      shown    <- outbox.take
                      _        <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      empty    <- outbox.take
                      redirect <- outbox.take
                      flashValues = redirect._1 match
                                      case Payload.Redirect("/target", Some(token)) =>
                                        FlashToken.decode(tokenConfig, token)
                                      case _ => None
                    yield assertTrue(
                      diffFromReply(shown._1).exists(containsValue(_, "stale")),
                      empty._1 == Payload.okReply(LiveResponse.Empty),
                      flashValues.contains(Map("info" -> "fresh"))
                    )
                  }
      yield result
    },
    test("pushPatch flash replaces previous event flash") {
      enum RootMsg:
        case SetError
        case Patch

      val lv = new RoutedLiveView[RootMsg, String, String]:
        def mount(ctx: MountContext) =
          ZIO.succeed("start")

        override def handleParams(model: String, params: String, url: URL, ctx: ParamsContext) =
          ZIO.succeed(url.encode)

        def handleMessage(model: String, ctx: MessageContext) =
              case RootMsg.SetError => ctx.flash.put(Error, "stale").as(model)
              case RootMsg.Patch =>
                ctx.flash.put(Info, "fresh") *>
                  ctx.nav.pushPatchUnsafe("/flash-root?patched=true").as(model)

        def render(model: String): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.SetError), "set-error"),
            button(phx.onClick(RootMsg.Patch), "patch"),
            span(idAttr := "url", model),
            flash(Info)(message => p(idAttr := "info", message)),
            flash(Error)(message => p(idAttr := "error", message))
          )

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl,
                    paramsRuntime = flashParamsRuntime
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _          <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      errorReply <- outbox.take
                      _          <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      _          <- outbox.take
                      navigation <- outbox.take
                      patchReply <- socket.livePatch("/flash-root?patched=true", meta)
                    yield assertTrue(
                      diffFromReply(errorReply._1).exists(containsValue(_, "stale")),
                      navigation._1 == Payload.LiveNavigation("/flash-root?patched=true", LivePatchKind.Push),
                      diffFromReply(patchReply).exists(diff =>
                        containsValue(diff, "fresh") && !containsValue(diff, "stale")
                      )
                    )
                  }
      yield result
    },
    test("patch redirect carries flash from the triggering patch") {
      enum RootMsg:
        case Patch

      val tokenConfig = TokenConfig.default
      val lv = new RoutedLiveView[RootMsg, String, String]:
        def mount(ctx: MountContext) =
          ZIO.succeed("start")

        override def handleParams(model: String, params: String, url: URL, ctx: ParamsContext) =
          if url.path.encode == "/redirecting" then ctx.nav.redirectUnsafe("/target").as(model)
          else ZIO.succeed(url.encode)

        def handleMessage(model: String, ctx: MessageContext) =
              case RootMsg.Patch =>
                ctx.flash.put(Info, "Patched") *>
                  ctx.nav.pushPatchUnsafe("/redirecting").as(model)

        def render(model: String): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.Patch), "patch"),
            flash(Info)(message => p(idAttr := "info", message))
          )

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    tokenConfig = tokenConfig,
                    initialUrl = initialUrl,
                    paramsRuntime = flashParamsRuntime
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _          <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      empty      <- outbox.take
                      navigation <- outbox.take
                      patchReply <- socket.livePatch("/redirecting", meta)
                      redirect   <- outbox.take
                      flashValues = redirect._1 match
                                      case Payload.Redirect("/target", Some(token)) =>
                                        FlashToken.decode(tokenConfig, token)
                                      case _ => None
                    yield assertTrue(
                      empty._1 == Payload.okReply(LiveResponse.Empty),
                      navigation._1 == Payload.LiveNavigation("/redirecting", LivePatchKind.Push),
                      patchReply == Payload.okReply(LiveResponse.Empty),
                      flashValues.contains(Map("info" -> "Patched"))
                    )
                  }
      yield result
    },
    test("component events can put root flash") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg.type, Unit]:
        object Msg

        def mount(props: Unit, ctx: MountContext) =
          ZIO.unit

        def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
          (_: Msg.type) => ctx.flash.put(Info, "Component saved").as(model)

        def render(props: Unit, model: Unit, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), "show")

      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(
            idAttr := "root",
            liveComponent(FlashComponent, id = "flash", props = ()),
            flash(Info)(message => p(idAttr := "flash-message", message))
          )

      val event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _     <- socket.inbox.offer(event -> meta)
                      reply <- outbox.take
                    yield assertTrue(diffFromReply(reply._1).exists(containsValue(_, "Component saved")))
                  }
      yield result
    },
    test("component pushPatch flash replaces previous event flash") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg, Unit]:
        enum Msg:
          case SetError
          case Patch

        def mount(props: Unit, ctx: MountContext) =
          ZIO.unit

        def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
              case Msg.SetError => ctx.flash.put(Error, "stale").as(model)
              case Msg.Patch =>
                ctx.flash.put(Info, "fresh") *>
                  ctx.nav.pushPatchUnsafe("/flash-root?patched=true").as(model)

        def render(props: Unit, model: Unit, self: ComponentRef[Msg]) =
          div(
            button(phx.onClick(Msg.SetError), phx.target(self), "set-error"),
            button(phx.onClick(Msg.Patch), phx.target(self), "patch"),
            flash(Info)(message => p(idAttr := "component-info", message)),
            flash(Error)(message => p(idAttr := "component-error", message))
          )

      val lv = new RoutedLiveView[Unit, String, String]:
        def mount(ctx: MountContext) =
          ZIO.succeed("start")

        override def handleParams(model: String, params: String, url: URL, ctx: ParamsContext) =
          ZIO.succeed(url.encode)

        def handleMessage(model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: String): HtmlElement[Unit] =
          div(
            idAttr := "root",
            liveComponent(FlashComponent, id = "flash", props = ()),
            span(idAttr := "url", model)
          )

      def componentClick(buttonIndex: Int): Payload.Event =
        Payload.Event(
          `type` = "click",
          event = BindingId.attrBindingId(
            Vector("root:div", "component:0:1", s"tag:$buttonIndex:button"),
            0
          ),
          value = Json.Obj.empty,
          cid = Some(1)
        )

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl,
                    paramsRuntime = flashParamsRuntime
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _          <- socket.inbox.offer(componentClick(0) -> meta)
                      stale      <- outbox.take
                      _          <- socket.inbox.offer(componentClick(1) -> meta)
                      empty      <- outbox.take
                      navigation <- outbox.take
                      patchReply <- socket.livePatch("/flash-root?patched=true", meta)
                    yield assertTrue(
                      diffFromReply(stale._1).exists(containsValue(_, "stale")),
                      empty._1 == Payload.okReply(LiveResponse.Empty),
                      navigation._1 == Payload.LiveNavigation("/flash-root?patched=true", LivePatchKind.Push),
                      diffFromReply(patchReply).exists(diff =>
                        containsValue(diff, "fresh") && !containsValue(diff, "stale")
                      )
                    )
                  }
      yield result
    },
    test("mount redirect carries flash through cookie to disconnected render") {
      val tokenConfig = TokenConfig.default

      val source = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ctx.flash.put(Info, "Mounted") *>
            ctx.nav.redirectUnsafe("/target")

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] = div("source")

      val target = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(flash(Info)(message => p(idAttr := "flash", message)))

      val routes = (scalive.Live.router @@ scalive.Live.tokenConfig(tokenConfig))(
        (scalive.live / "source")(source),
        (scalive.live / "target")(target)
      )

      def run(path: String, flashToken: Option[String] = None) =
        URL.decode(path) match
          case Left(error) => ZIO.die(error)
          case Right(url)  =>
            val request = flashToken match
              case Some(token) => Request.get(url).addCookie(Cookie.Request(FlashToken.CookieName, token))
              case None        => Request.get(url)
            ZIO.scoped(routes.runZIO(request))

      for
        redirected <- run("/source")
        flashToken = responseCookie(redirected, FlashToken.CookieName)
        flashValues = flashToken.flatMap(FlashToken.decode(tokenConfig, _))
        rendered <- run("/target", flashToken)
        body     <- rendered.body.asString
      yield assertTrue(
        redirected.status.isRedirection,
        redirected.rawHeader("location").contains("/target"),
        flashValues.contains(Map("info" -> "Mounted")),
        rendered.status == Status.Ok,
        body.contains("Mounted")
      )
    },
    test("nested LiveView flash is scoped to the child socket") {
      enum ChildMsg:
        case Show

      val rootTopic  = "lv:flash-root"
      val childTopic = "lv:child"
      val rootMeta   = WebSocketMessage.Meta(Some(1), Some(1), rootTopic, "phx_join")
      val childMeta  = rootMeta.copy(topic = childTopic)

      val child = new LiveView[ChildMsg, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
              case ChildMsg.Show => ctx.flash.put(Info, "Child flash").as(model)

        def render(model: Unit): HtmlElement[ChildMsg] =
          div(
            idAttr := "child",
            button(phx.onClick(ChildMsg.Show), "show"),
            flash(Info)(message => p(idAttr := "child-flash", message))
          )

      val parent = new LiveView[Msg, Int]:
        def mount(ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(model: Int, ctx: MessageContext) =
              case Msg.Rerender => ZIO.succeed(model + 1)
              case _            => ZIO.succeed(model)

        def render(model: Int): HtmlElement[Msg] =
          div(
            idAttr := "parent",
            button(phx.onClick(Msg.Rerender), model.toString),
            flash(Info)(message => p(idAttr := "parent-flash", message)),
            liveView("child", child)
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
        parentSocket <- channel.socket(rootTopic).some
        childOut <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        childFiber <- childSocket.outbox.runForeach(childOut.offer).fork
        parentOut <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        parentFiber <- parentSocket.outbox.runForeach(parentOut.offer).fork
        result <- (for
                    _ <- childOut.take *> parentOut.take
                    _ <- channel.event(
                           childTopic,
                           click(Vector("root:div", "tag:0:button")),
                           childMeta.copy(eventType = "event")
                         )
                    childReply <- childOut.take
                    _ <- channel.event(
                           rootTopic,
                           click(Vector("root:div", "tag:0:button")),
                           rootMeta.copy(eventType = "event")
                         )
                    parentReply <- parentOut.take
                  yield assertTrue(
                    diffFromReply(childReply._1).exists(containsValue(_, "Child flash")),
                    !diffFromReply(parentReply._1).exists(containsValue(_, "Child flash"))
                  )).ensuring(childFiber.interrupt *> parentFiber.interrupt)
      yield result)
    }
  )
end FlashSpec
