package scalive

import zio.*
import zio.http.URL
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
    case Rerender

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
    }
  )
end FlashSpec
