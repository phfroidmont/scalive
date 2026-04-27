package scalive

import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object FlashSpec extends ZIOSpecDefault:
  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private enum Msg:
    case Show
    case ShowBoth
    case ClearInfo
    case ClearAll
    case PushPatch
    case ReplacePatch
    case PushNavigate
    case Rerender

  private val identityLayout: HtmlElement[?] => HtmlElement[?] = element => element

  private def containsValue(diff: Diff, value: String): Boolean =
    diff match
      case Diff.Tag(_, dynamic, _, _, _, components, _, _) =>
        dynamic.exists(d => containsValue(d.diff, value)) || components.values.exists(
          containsValue(_, value)
        )
      case Diff.Comprehension(_, entries, _, _, _) =>
        entries.exists {
          case Diff.Dynamic(_, diff)          => containsValue(diff, value)
          case Diff.IndexMerge(_, _, diff)    => containsValue(diff, value)
          case _                              => false
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
    test("root events can put and client-clear keyed flash") {
      val lv = new LiveView[Msg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = {
          case Msg.Show => LiveContext.putFlash("info", "Saved").as(model)
          case _        => ZIO.succeed(model)
        }
        def render(model: Unit): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(phx.onClick(Msg.Show), "show"),
            flash("info")(message =>
              p(idAttr := "flash", phx.clearFlash, phx.value("key") := "info", message)
            )
          )
        def subscriptions(model: Unit) = ZStream.empty

      val clear: Payload.Event = Payload.Event(
        `type` = "click",
        event = "lv:clear-flash",
        value = Json.Obj("key" -> Json.Str("info"))
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
    test("server-side clearFlash removes keyed or all flash") {
      val lv = new LiveView[Msg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = {
          case Msg.ShowBoth =>
            LiveContext.putFlash("info", "Info") *>
              LiveContext.putFlash("error", "Error").as(model)
          case Msg.ClearInfo => LiveContext.clearFlash("info").as(model)
          case Msg.ClearAll  => LiveContext.clearFlash.as(model)
          case _             => ZIO.succeed(model)
        }
        def render(model: Unit): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(idAttr := "show", phx.onClick(Msg.ShowBoth), "show"),
            button(idAttr := "clear-info", phx.onClick(Msg.ClearInfo), "clear-info"),
            button(idAttr := "clear-all", phx.onClick(Msg.ClearAll), "clear-all"),
            flash("info")(message => p(idAttr := "info", message)),
            flash("error")(message => p(idAttr := "error", message))
          )
        def subscriptions(model: Unit) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button"), 1) -> meta)
                      shown <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button"), 1) -> meta)
                      clearInfo <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:2:button"), 1) -> meta)
                      clearAll <- outbox.take
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
        def mount = ZIO.unit
        def handleMessage(model: Unit) = {
          case RootMsg.SetError => LiveContext.putFlash("error", "stale").as(model)
          case RootMsg.Redirect =>
            LiveContext.putFlash("info", "fresh") *>
              LiveContext.redirect("/target").as(model)
        }
        def render(model: Unit): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.SetError), "set-error"),
            button(phx.onClick(RootMsg.Redirect), "redirect"),
            flash("info")(message => p(idAttr := "info", message)),
            flash("error")(message => p(idAttr := "error", message))
          )
        def subscriptions(model: Unit) = ZStream.empty

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
                      _     <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      shown <- outbox.take
                      _     <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      empty <- outbox.take
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
    test("pushNavigate flash only includes flash set during the navigating event") {
      enum RootMsg:
        case SetError
        case Navigate

      val tokenConfig = TokenConfig.default
      val lv = new LiveView[RootMsg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = {
          case RootMsg.SetError => LiveContext.putFlash("error", "stale").as(model)
          case RootMsg.Navigate =>
            LiveContext.putFlash("info", "fresh") *>
              LiveContext.pushNavigate("/target").as(model)
        }
        def render(model: Unit): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.SetError), "set-error"),
            button(phx.onClick(RootMsg.Navigate), "navigate"),
            flash("error")(message => p(idAttr := "error", message))
          )
        def subscriptions(model: Unit) = ZStream.empty

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
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      _ <- outbox.take
                      navigation <- outbox.take
                      flashValues = navigation._1 match
                                      case Payload.LiveRedirect(
                                            "/target",
                                            LivePatchKind.Push,
                                            Some(token)
                                          ) => FlashToken.decode(tokenConfig, token)
                                      case _ => None
                    yield assertTrue(flashValues.contains(Map("info" -> "fresh")))
                  }
      yield result
    },
    test("pushPatch flash replaces previous event flash") {
      enum RootMsg:
        case SetError
        case Patch

      val lv = new LiveView[RootMsg, String]:
        def mount = ZIO.succeed("start")
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.encode)
        def handleMessage(model: String) = {
          case RootMsg.SetError => LiveContext.putFlash("error", "stale").as(model)
          case RootMsg.Patch =>
            LiveContext.putFlash("info", "fresh") *>
              LiveContext.pushPatch("/flash-root?patched=true").as(model)
        }
        def render(model: String): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.SetError), "set-error"),
            button(phx.onClick(RootMsg.Patch), "patch"),
            span(idAttr := "url", model),
            flash("info")(message => p(idAttr := "info", message)),
            flash("error")(message => p(idAttr := "error", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      errorReply <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      _ <- outbox.take
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
      val lv = new LiveView[RootMsg, String]:
        def mount = ZIO.succeed("start")
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          if url.path.encode == "/redirecting" then LiveContext.redirect("/target").as(model)
          else ZIO.succeed(url.encode)
        def handleMessage(model: String) = { case RootMsg.Patch =>
          LiveContext.putFlash("info", "Patched") *>
            LiveContext.pushPatch("/redirecting").as(model)
        }
        def render(model: String): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.Patch), "patch"),
            flash("info")(message => p(idAttr := "info", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    tokenConfig = tokenConfig,
                    initialUrl = initialUrl
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      empty <- outbox.take
                      navigation <- outbox.take
                      patchReply <- socket.livePatch("/redirecting", meta)
                      redirect <- outbox.take
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
    test("client-side live_patch clears previous event flash") {
      enum RootMsg:
        case SetError

      val lv = new LiveView[RootMsg, String]:
        def mount = ZIO.succeed("start")
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.encode)
        def handleMessage(model: String) = {
          case RootMsg.SetError => LiveContext.putFlash("error", "stale").as(model)
        }
        def render(model: String): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.SetError), "set-error"),
            span(idAttr := "url", model),
            flash("error")(message => p(idAttr := "error", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      errorReply <- outbox.take
                      patchReply <- socket.livePatch("/flash-root?patched=true", meta)
                    yield assertTrue(
                      diffFromReply(errorReply._1).exists(containsValue(_, "stale")),
                      diffFromReply(patchReply).exists(diff =>
                        containsValue(diff, "/flash-root?patched=true") && !containsValue(diff, "stale")
                      )
                    )
                  }
      yield result
    },
    test("client-side live_patch clears mount flash") {
      val lv = new LiveView[Unit, String]:
        def mount = LiveContext.putFlash("info", "Mounted").as("start")
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.encode)
        def handleMessage(model: String) = _ => ZIO.succeed(model)
        def render(model: String): HtmlElement[Unit] =
          div(
            idAttr := "root",
            span(idAttr := "url", model),
            flash("info")(message => p(idAttr := "info", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl
                  )
        result <- withOutbox(socket) { _ =>
                    for patchReply <- socket.livePatch("/flash-root?patched=true", meta)
                    yield assertTrue(
                      diffFromReply(patchReply).exists(diff =>
                        containsValue(diff, "/flash-root?patched=true") && !containsValue(diff, "Mounted")
                      )
                    )
                  }
      yield result
    },
    test("component events can put root flash") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg.type, Unit]:
        object Msg
        def mount(props: Unit) = ZIO.unit
        def handleMessage(model: Unit) = { case Msg =>
          LiveContext.putFlash("info", "Component saved").as(model)
        }
        def render(model: Unit, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), "show")

      val lv = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            idAttr := "root",
            liveComponent(FlashComponent, id = "flash", props = ()),
            flash("info")(message => p(idAttr := "flash-message", message))
          )
        def subscriptions(model: Unit) = ZStream.empty

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
                    yield assertTrue(
                      diffFromReply(reply._1).exists(containsValue(_, "Component saved"))
                    )
                  }
      yield result
    },
    test("component redirect carries only flash set during the redirecting event") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg, Unit]:
        enum Msg:
          case SetError
          case Redirect

        def mount(props: Unit) = ZIO.unit
        def handleMessage(model: Unit) = {
          case Msg.SetError => LiveContext.putFlash("error", "stale").as(model)
          case Msg.Redirect =>
            LiveContext.putFlash("info", "fresh") *>
              LiveContext.redirect("/target").as(model)
        }
        def render(model: Unit, self: ComponentRef[Msg]) =
          div(
            button(phx.onClick(Msg.SetError), phx.target(self), "set-error"),
            button(phx.onClick(Msg.Redirect), phx.target(self), "redirect"),
            flash("error")(message => p(idAttr := "component-error", message))
          )

      val tokenConfig = TokenConfig.default
      val lv = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(idAttr := "root", liveComponent(FlashComponent, id = "flash", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

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
                      _ <- socket.inbox.offer(componentClick(0) -> meta)
                      stale <- outbox.take
                      _ <- socket.inbox.offer(componentClick(1) -> meta)
                      empty <- outbox.take
                      redirect <- outbox.take
                      flashValues = redirect._1 match
                                      case Payload.Redirect("/target", Some(token)) =>
                                        FlashToken.decode(tokenConfig, token)
                                      case _ => None
                    yield assertTrue(
                      diffFromReply(stale._1).exists(containsValue(_, "stale")),
                      empty._1 == Payload.okReply(LiveResponse.Empty),
                      flashValues.contains(Map("info" -> "fresh"))
                    )
                  }
      yield result
    },
    test("component pushNavigate carries only flash set during the navigating event") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg, Unit]:
        enum Msg:
          case SetError
          case Navigate

        def mount(props: Unit) = ZIO.unit
        def handleMessage(model: Unit) = {
          case Msg.SetError => LiveContext.putFlash("error", "stale").as(model)
          case Msg.Navigate =>
            LiveContext.putFlash("info", "fresh") *>
              LiveContext.pushNavigate("/target").as(model)
        }
        def render(model: Unit, self: ComponentRef[Msg]) =
          div(
            button(phx.onClick(Msg.SetError), phx.target(self), "set-error"),
            button(phx.onClick(Msg.Navigate), phx.target(self), "navigate"),
            flash("error")(message => p(idAttr := "component-error", message))
          )

      val tokenConfig = TokenConfig.default
      val lv = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(idAttr := "root", liveComponent(FlashComponent, id = "flash", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

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
                      _ <- socket.inbox.offer(componentClick(0) -> meta)
                      stale <- outbox.take
                      _ <- socket.inbox.offer(componentClick(1) -> meta)
                      empty <- outbox.take
                      navigation <- outbox.take
                      flashValues = navigation._1 match
                                      case Payload.LiveRedirect(
                                            "/target",
                                            LivePatchKind.Push,
                                            Some(token)
                                          ) => FlashToken.decode(tokenConfig, token)
                                      case _ => None
                    yield assertTrue(
                      diffFromReply(stale._1).exists(containsValue(_, "stale")),
                      empty._1 == Payload.okReply(LiveResponse.Empty),
                      flashValues.contains(Map("info" -> "fresh"))
                    )
                  }
      yield result
    },
    test("component pushPatch flash replaces previous event flash") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg, Unit]:
        enum Msg:
          case SetError
          case Patch

        def mount(props: Unit) = ZIO.unit
        def handleMessage(model: Unit) = {
          case Msg.SetError => LiveContext.putFlash("error", "stale").as(model)
          case Msg.Patch =>
            LiveContext.putFlash("info", "fresh") *>
              LiveContext.pushPatch("/flash-root?patched=true").as(model)
        }
        def render(model: Unit, self: ComponentRef[Msg]) =
          div(
            button(phx.onClick(Msg.SetError), phx.target(self), "set-error"),
            button(phx.onClick(Msg.Patch), phx.target(self), "patch"),
            flash("info")(message => p(idAttr := "component-info", message)),
            flash("error")(message => p(idAttr := "component-error", message))
          )

      val lv = new LiveView[Unit, String]:
        def mount = ZIO.succeed("start")
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.encode)
        def handleMessage(model: String) = _ => ZIO.succeed(model)
        def render(model: String): HtmlElement[Unit] =
          div(
            idAttr := "root",
            liveComponent(FlashComponent, id = "flash", props = ()),
            span(idAttr := "url", model)
          )
        def subscriptions(model: String) = ZStream.empty

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
                    initialUrl = initialUrl
                  )
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(componentClick(0) -> meta)
                      stale <- outbox.take
                      _ <- socket.inbox.offer(componentClick(1) -> meta)
                      empty <- outbox.take
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
    test("component-targeted lv:clear-flash clears keyed or all flash") {
      object FlashComponent extends LiveComponent[Unit, FlashComponent.Msg, Unit]:
        enum Msg:
          case SetBoth

        def mount(props: Unit) = ZIO.unit
        def handleMessage(model: Unit) = { case Msg.SetBoth =>
          LiveContext.putFlash("info", "Info") *>
            LiveContext.putFlash("error", "Error").as(model)
        }
        def render(model: Unit, self: ComponentRef[Msg]) =
          div(
            button(phx.onClick(Msg.SetBoth), phx.target(self), "set"),
            span(phx.clearFlash, phx.target(self), "clear-all"),
            span(phx.clearFlash, phx.target(self), phx.value("key") := "info", "clear-info"),
            flash("info")(message => p(idAttr := "info", message)),
            flash("error")(message => p(idAttr := "error", message))
          )

      val lv = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(idAttr := "root", liveComponent(FlashComponent, id = "flash", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      val setBoth = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1", "tag:0:button"), 0),
        value = Json.Obj.empty,
        cid = Some(1)
      )
      val clearInfo = Payload.Event(
        `type` = "click",
        event = "lv:clear-flash",
        value = Json.Obj("key" -> Json.Str("info")),
        cid = Some(1)
      )
      val clearAll = Payload.Event(
        `type` = "click",
        event = "lv:clear-flash",
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(setBoth -> meta)
                      shown <- outbox.take
                      _ <- socket.inbox.offer(clearInfo -> meta)
                      infoCleared <- outbox.take
                      _ <- socket.inbox.offer(clearAll -> meta)
                      allCleared <- outbox.take
                    yield assertTrue(
                      diffFromReply(shown._1).exists(diff =>
                        containsValue(diff, "Info") && containsValue(diff, "Error")
                      ),
                      !diffFromReply(infoCleared._1).exists(containsValue(_, "Info")),
                      !diffFromReply(allCleared._1).exists(diff =>
                        containsValue(diff, "Info") || containsValue(diff, "Error")
                      )
                    )
                  }
      yield result
    },
    test("flash survives event-triggered pushPatch") {
      val lv = new LiveView[Msg, String]:
        def mount = ZIO.succeed("start")
        def handleMessage(model: String) = {
          case Msg.PushPatch =>
            LiveContext.putFlash("info", "Patched") *>
              LiveContext.pushPatch("/next").as(model)
          case _ => ZIO.succeed(model)
        }
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.path.encode)
        def render(model: String): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(phx.onClick(Msg.PushPatch), "patch"),
            span(idAttr := "path", model),
            flash("info")(message => p(idAttr := "flash", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      emptyReply <- outbox.take
                      navigation <- outbox.take
                      patchReply <- socket.livePatch("/next", meta)
                    yield assertTrue(
                      emptyReply._1 == Payload.okReply(LiveResponse.Empty),
                      navigation._1 == Payload.LiveNavigation("/next", LivePatchKind.Push),
                      diffFromReply(patchReply).exists(diff =>
                        containsValue(diff, "/next") && containsValue(diff, "Patched")
                      )
                    )
                  }
      yield result
    },
    test("flash survives event-triggered replacePatch") {
      val lv = new LiveView[Msg, String]:
        def mount = ZIO.succeed("start")
        def handleMessage(model: String) = {
          case Msg.ReplacePatch =>
            LiveContext.putFlash("info", "Replaced") *>
              LiveContext.replacePatch("/next").as(model)
          case _ => ZIO.succeed(model)
        }
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.path.encode)
        def render(model: String): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(phx.onClick(Msg.ReplacePatch), "patch"),
            span(idAttr := "path", model),
            flash("info")(message => p(idAttr := "flash", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      emptyReply <- outbox.take
                      navigation <- outbox.take
                      patchReply <- socket.livePatch("/next", meta)
                    yield assertTrue(
                      emptyReply._1 == Payload.okReply(LiveResponse.Empty),
                      navigation._1 == Payload.LiveNavigation("/next", LivePatchKind.Replace),
                      diffFromReply(patchReply).exists(diff =>
                        containsValue(diff, "/next") && containsValue(diff, "Replaced")
                      )
                    )
                  }
      yield result
    },
    test("flash is sent with event-triggered pushNavigate") {
      val tokenConfig = TokenConfig.default

      val source = new LiveView[Msg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = {
          case Msg.PushNavigate =>
            LiveContext.putFlash("info", "Navigated") *>
              LiveContext.pushNavigate("/target").as(model)
          case _ => ZIO.succeed(model)
        }
        def render(model: Unit): HtmlElement[Msg] =
          div(
            idAttr := "root",
            button(phx.onClick(Msg.PushNavigate), "navigate")
          )
        def subscriptions(model: Unit) = ZStream.empty

      val target = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            idAttr := "target",
            flash("info")(message => p(idAttr := "flash", message))
          )
        def subscriptions(model: Unit) = ZStream.empty

      for
        socket <- Socket.start(
                    "id",
                    "token",
                    source,
                    LiveContext(staticChanged = false),
                    meta,
                    tokenConfig = tokenConfig
                  )
        (emptyReply, flashValues) <- withOutbox(socket) { outbox =>
                                     for
                                       _ <- socket.inbox.offer(
                                              click(Vector("root:div", "tag:0:button")) -> meta
                                            )
                                       emptyReply <- outbox.take
                                       navigation <- outbox.take
                                       flashToken = navigation._1 match
                                                      case Payload.LiveRedirect(
                                                            "/target",
                                                            LivePatchKind.Push,
                                                            Some(token)
                                                          ) => Some(token)
                                                      case _ => None
                                       flashValues = flashToken.flatMap(FlashToken.decode(tokenConfig, _))
                                     yield emptyReply -> flashValues
                                   }
        targetSocket <- Socket.start(
                          "target",
                          "token",
                          target,
                          LiveContext(staticChanged = false),
                          meta,
                          tokenConfig = tokenConfig,
                          initialFlash = flashValues.getOrElse(Map.empty)
                        )
        init <- targetSocket.outbox.take(1).runHead.some
      yield assertTrue(
        emptyReply._1 == Payload.okReply(LiveResponse.Empty),
        flashValues.contains(Map("info" -> "Navigated")),
        diffFromReply(init._1).exists(containsValue(_, "Navigated"))
      )
    },
    test("redirect carries flash through cookie to disconnected render") {
      val tokenConfig = TokenConfig.default

      val source = new LiveView[Unit, Unit]:
        def mount = ZIO.unit
        override def handleParams(model: Unit, query: queryCodec.Out, url: URL) =
          LiveContext.putFlash("info", "Redirected") *>
            LiveContext.redirect("/target").as(model)
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div("source")
        def subscriptions(model: Unit)             = ZStream.empty

      val target = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(flash("info")(message => p(idAttr := "flash", message)))
        def subscriptions(model: Unit) = ZStream.empty

      val routes = LiveRoutes(layout = identityLayout, tokenConfig = tokenConfig)(
        Method.GET / "source" -> liveHandler(source),
        Method.GET / "target" -> liveHandler(target)
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
        rendered   <- run("/target", flashToken)
        body       <- rendered.body.asString
      yield assertTrue(
        redirected.status.isRedirection,
        redirected.rawHeader("location").contains("/target"),
        flashToken.isDefined,
        rendered.status == Status.Ok,
        body.contains("Redirected")
      )
    },
    test("mount redirect carries flash through cookie to disconnected render") {
      val tokenConfig = TokenConfig.default

      val source = new LiveView[Unit, Unit]:
        def mount =
          LiveContext.putFlash("info", "Mounted") *>
            LiveContext.redirect("/target")
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div("source")
        def subscriptions(model: Unit)             = ZStream.empty

      val target = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(flash("info")(message => p(idAttr := "flash", message)))
        def subscriptions(model: Unit) = ZStream.empty

      val routes = LiveRoutes(layout = identityLayout, tokenConfig = tokenConfig)(
        Method.GET / "source" -> liveHandler(source),
        Method.GET / "target" -> liveHandler(target)
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
    test("mount pushNavigate carries flash through cookie to disconnected render") {
      val tokenConfig = TokenConfig.default

      val source = new LiveView[Unit, Unit]:
        def mount =
          LiveContext.pushNavigate("/target") *>
            LiveContext.putFlash("info", "Mounted")
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div("source")
        def subscriptions(model: Unit)             = ZStream.empty

      val target = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(flash("info")(message => p(idAttr := "flash", message)))
        def subscriptions(model: Unit) = ZStream.empty

      val routes = LiveRoutes(layout = identityLayout, tokenConfig = tokenConfig)(
        Method.GET / "source" -> liveHandler(source),
        Method.GET / "target" -> liveHandler(target)
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
    test("flash survives bootstrap handleParams patch loop") {
      val lv = new LiveView[Unit, String]:
        def mount = ZIO.succeed("mount")
        def handleMessage(model: String) = _ => ZIO.succeed(model)
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          if url.path.encode == "/start" then
            LiveContext.putFlash("info", "Boot patched") *>
              LiveContext.pushPatch("/done").as(model)
          else ZIO.succeed(url.path.encode)
        def render(model: String): HtmlElement[Unit] =
          div(
            idAttr := "root",
            span(idAttr := "path", model),
            flash("info")(message => p(idAttr := "flash", message))
          )
        def subscriptions(model: String) = ZStream.empty

      for
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl
                  )
        init <- socket.outbox.take(1).runHead.some
      yield assertTrue(
        diffFromReply(init._1).exists(diff =>
          containsValue(diff, "/done") && containsValue(diff, "Boot patched")
        )
      )
    },
    test("nested LiveView flash is scoped to the child socket") {
      enum ChildMsg:
        case Show

      val rootTopic  = "lv:flash-root"
      val childTopic = "lv:flash-root-child"
      val rootMeta   = WebSocketMessage.Meta(Some(1), Some(1), rootTopic, "phx_join")
      val childMeta  = rootMeta.copy(topic = childTopic)

      val child = new LiveView[ChildMsg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = { case ChildMsg.Show =>
          LiveContext.putFlash("info", "Child flash").as(model)
        }
        def render(model: Unit): HtmlElement[ChildMsg] =
          div(
            idAttr := "child",
            button(phx.onClick(ChildMsg.Show), "show"),
            flash("info")(message => p(idAttr := "child-flash", message))
          )
        def subscriptions(model: Unit) = ZStream.empty

      val parent = new LiveView[Msg, Int]:
        def mount = ZIO.succeed(0)
        def handleMessage(model: Int) = { case Msg.Rerender => ZIO.succeed(model + 1)
        case _ => ZIO.succeed(model) }
        def render(model: Int): HtmlElement[Msg] =
          div(
            idAttr := "parent",
            button(phx.onClick(Msg.Rerender), model.toString),
            flash("info")(message => p(idAttr := "parent-flash", message)),
            liveView("child", child)
          )
        def subscriptions(model: Int) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry  <- channel.nestedEntry(childTopic).some
        _      <- channel.joinNested(childTopic, entry.token, false, childMeta, URL.root)
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
    },
    test("nested child pushPatch transfers flash to the root live_patch") {
      enum ChildMsg:
        case Patch

      val rootTopic  = "lv:flash-root"
      val childTopic = "lv:flash-root-child"
      val rootMeta   = WebSocketMessage.Meta(Some(1), Some(1), rootTopic, "phx_join")
      val childMeta  = rootMeta.copy(topic = childTopic)

      val child = new LiveView[ChildMsg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = { case ChildMsg.Patch =>
          LiveContext.putFlash("info", "Child patched") *>
            LiveContext.pushPatch("/flash-root?patched=true").as(model)
        }
        def render(model: Unit): HtmlElement[ChildMsg] =
          div(
            idAttr := "child",
            button(phx.onClick(ChildMsg.Patch), "patch")
          )
        def subscriptions(model: Unit) = ZStream.empty

      val parent = new LiveView[Unit, String]:
        def mount = ZIO.succeed("start")
        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(url.encode)
        def handleMessage(model: String) = _ => ZIO.succeed(model)
        def render(model: String): HtmlElement[Unit] =
          div(
            idAttr := "parent",
            span(idAttr := "url", model),
            flash("info")(message => p(idAttr := "parent-flash", message)),
            liveView("child", child)
          )
        def subscriptions(model: String) = ZStream.empty

      ZIO.scoped(for
        initialUrl <- ZIO.fromEither(URL.decode("/flash-root")).orDie
        channel    <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, initialUrl)
        entry  <- channel.nestedEntry(childTopic).some
        _      <- channel.joinNested(childTopic, entry.token, false, childMeta, initialUrl)
        childSocket <- channel.socket(childTopic).some
        childOut <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        childFiber <- childSocket.outbox.runForeach(childOut.offer).fork
        result <- (for
                    _ <- childOut.take
                    _ <- channel.event(
                           childTopic,
                           click(Vector("root:div", "tag:0:button")),
                           childMeta.copy(eventType = "event")
                         )
                    emptyReply <- childOut.take
                    navigation <- childOut.take
                    patchReply <- channel.livePatch(
                                    rootTopic,
                                    "/flash-root?patched=true",
                                    rootMeta.copy(eventType = "live_patch")
                                  )
                  yield assertTrue(
                    emptyReply._1 == Payload.okReply(LiveResponse.Empty),
                    navigation._1 == Payload.LiveNavigation("/flash-root?patched=true", LivePatchKind.Push),
                    diffFromReply(patchReply).exists(diff =>
                      containsValue(diff, "/flash-root?patched=true") &&
                        containsValue(diff, "Child patched")
                    )
                  )).ensuring(childFiber.interrupt)
      yield result)
    }
  )
end FlashSpec
