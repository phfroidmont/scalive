package scalive

import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object LifecycleHookSpec extends ZIOSpecDefault:
  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private def click(
    path: Vector[String],
    attrIndex: Int = 0,
    value: Json = Json.Obj.empty,
    cid: Option[Int] = None
  ): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = value,
      cid = cid
    )

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

  private def diffFromPayload(payload: Payload): Option[Diff] =
    payload match
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(diff)) => Some(diff)
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff))     => Some(diff)
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.InterceptReply(_, diff)) => diff
      case Payload.Diff(diff) => Some(diff)
      case _                  => None

  private def withOutbox[Msg, Model, A](socket: Socket[Msg, Model])(
    f: Queue[(Payload, WebSocketMessage.Meta)] => Task[A]
  ): Task[A] =
    for
      queue  <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
      fiber  <- socket.outbox.runForeach(queue.offer).fork
      result <- f(queue).ensuring(fiber.interrupt)
    yield result

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  def spec = suite("LifecycleHookSpec")(
    test("connected context is false for disconnected render and true for socket mount") {
      for
        callsRef <- Ref.make(Vector.empty[String])
        lv = new LiveView[Unit, Boolean]:
               def mount(ctx: MountContext) =
                 callsRef.update(_ :+ s"mount:${ctx.connected}").as(ctx.connected)

               def handleMessage(model: Boolean, ctx: MessageContext) =
                 (_: Unit) => ZIO.succeed(model)

               def render(model: Boolean): HtmlElement[Unit] = div(model.toString)
        routes   = scalive.Live.router(scalive.live(lv))
        response <- runRequest(routes, "/")
        body     <- response.body.asString
        _        <- ZIO.scoped(Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta))
        calls    <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("false"),
        calls == Vector("mount:false", "mount:true")
      )
    },
    test("event hooks continue in attach order before handleMessage") {
      enum Msg:
        case Inc

      val lv = new LiveView[Msg, Int]:
        override def hooks: LiveHooks[Msg, Int] =
          LiveHooks.empty[Msg, Int]
            .event("add") { (model, msg, event, _) =>
              msg match
                case Msg.Inc if event.params.get("amount").contains("10") =>
                  ZIO.succeed(LiveEventHookResult.cont(model + 10))
                case _ => ZIO.succeed(LiveEventHookResult.cont(model))
            }
            .event("double") { (model, msg, _, _) =>
              msg match
                case Msg.Inc => ZIO.succeed(LiveEventHookResult.cont(model * 2))
            }

        def mount(ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(model: Int, ctx: MessageContext) =
              case Msg.Inc => ZIO.succeed(model + 1)

        def render(model: Int): HtmlElement[Msg] =
          div(span(model.toString), button(phx.onClick(Msg.Inc), "inc"))

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "tag:1:button"),
                               value = Json.Obj("amount" -> Json.Str("10"))
                             ) -> meta
                           )
                      reply <- outbox.take
                    yield assertTrue(diffFromPayload(reply._1).exists(containsValue(_, "21")))
                  }
      yield result)
    },
    test("event hook halt replies and skips handleMessage") {
      enum Msg:
        case Inc

      val replyValue = Json.Obj("msg" -> Json.Str("halted"))
      val lv = new LiveView[Msg, Int]:
        override def hooks: LiveHooks[Msg, Int] =
          LiveHooks.empty[Msg, Int].event("halt") { (model, _, _, _) =>
            ZIO.succeed(LiveEventHookResult.haltReply(model + 5, replyValue))
          }

        def mount(ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(model: Int, ctx: MessageContext) =
              case Msg.Inc => ZIO.succeed(model + 100)

        def render(model: Int): HtmlElement[Msg] =
          div(span(model.toString), button(phx.onClick(Msg.Inc), "inc"))

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _     <- outbox.take
                      _     <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      reply <- outbox.take
                    yield
                      val halted = reply._1 match
                        case Payload.Reply(
                              ReplyStatus.Ok,
                              LiveResponse.InterceptReply(`replyValue`, Some(diff))
                            ) =>
                          containsValue(diff, "5") && !containsValue(diff, "100")
                        case _ => false
                      assertTrue(halted)
                  }
      yield result)
    },
    test("dynamic event hook detach removes only the named hook") {
      enum Msg:
        case Inc
        case Detach

      val lv = new LiveView[Msg, Int]:
        override def hooks: LiveHooks[Msg, Int] =
          LiveHooks.empty[Msg, Int].event("add") { (model, msg, _, _) =>
            msg match
              case Msg.Inc    => ZIO.succeed(LiveEventHookResult.cont(model + 10))
              case Msg.Detach => ZIO.succeed(LiveEventHookResult.cont(model))
          }

        def mount(ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(model: Int, ctx: MessageContext) =
              case Msg.Inc    => ZIO.succeed(model + 1)
              case Msg.Detach => ctx.hooks.event.detach("add").as(model)

        def render(model: Int): HtmlElement[Msg] =
          div(
            span(model.toString),
            button(phx.onClick(Msg.Inc), "inc"),
            button(phx.onClick(Msg.Detach), "detach")
          )

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      first <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:2:button")) -> meta)
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      second <- outbox.take
                    yield assertTrue(
                      diffFromPayload(first._1).exists(containsValue(_, "11")),
                      diffFromPayload(second._1).exists(containsValue(_, "12"))
                    )
                  }
      yield result)
    },
    test("params hooks can transform the model around handleParams") {
      val lv = new LiveView[Unit, String]:
        override def hooks: LiveHooks[Unit, String] =
          LiveHooks.empty[Unit, String].params("url") { (model, url, _) =>
            ZIO.succeed(LiveHookResult.cont(s"$model|hook:${url.path.encode}"))
          }

        def mount(ctx: MountContext) =
          ZIO.succeed("mount")

        override def handleParams(model: String, query: queryCodec.Out, url: URL, ctx: ParamsContext) =
          ZIO.succeed(s"$model|params:${url.path.encode}")

        def handleMessage(model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: String): HtmlElement[Unit] = div(idAttr := "root", model)

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
        diffFromPayload(init._1).exists(diff =>
          containsValue(diff, "mount|params:/start|hook:/start") ||
            containsValue(diff, "mount|hook:/start|params:/start")
        )
      )
    },
    test("info hooks can halt subscription messages") {
      enum Msg:
        case Tick

      val lv = new LiveView[Msg, Int]:
        override def hooks: LiveHooks[Msg, Int] =
          LiveHooks.empty[Msg, Int].info("halt") { (model, msg, _) =>
            msg match
              case Msg.Tick => ZIO.succeed(LiveHookResult.halt(model + 10))
          }

        def mount(ctx: MountContext) =
          ctx.subscriptions.start("tick")(ZStream.succeed(Msg.Tick)).as(0)

        def handleMessage(model: Int, ctx: MessageContext) =
              case Msg.Tick => ZIO.succeed(model + 1)

        def render(model: Int): HtmlElement[Msg] = div(model.toString)

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "10"))))
    },
    test("async hooks see async task completions before handleMessage") {
      enum Msg:
        case Loaded(value: String)

      val lv = new LiveView[Msg, String]:
        override def hooks: LiveHooks[Msg, String] =
          LiveHooks.empty[Msg, String].async("prefix") { (model, event, _) =>
            event.result match
              case LiveAsyncResult.Succeeded(Msg.Loaded(value)) if event.name == "load" =>
                ZIO.succeed(LiveHookResult.cont(s"$model|hook:$value"))
              case _ => ZIO.succeed(LiveHookResult.cont(model))
          }

        def mount(ctx: MountContext) =
          ctx.async.start("load")(ZIO.succeed("loaded"))(Msg.Loaded(_)).as("mount")

        def handleMessage(model: String, ctx: MessageContext) =
              case Msg.Loaded(value) => ZIO.succeed(s"$model|handle:$value")

        def render(model: String): HtmlElement[Msg] = div(idAttr := "root", model)

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(
        diffFromPayload(update._1).exists(containsValue(_, "mount|hook:loaded|handle:loaded"))
      ))
    },
    test("afterRender hooks run after initial render and message render") {
      enum Msg:
        case Inc

      for
        calls <- Ref.make(Vector.empty[Int])
        lv = new LiveView[Msg, Int]:
               override def hooks: LiveHooks[Msg, Int] =
                 LiveHooks.empty[Msg, Int].afterRender("record") { (model, _) =>
                   calls.update(_ :+ model).as(model)
                 }

               def mount(ctx: MountContext) =
                 ZIO.succeed(0)

               def handleMessage(model: Int, ctx: MessageContext) =
                     case Msg.Inc => ZIO.succeed(model + 1)

               def render(model: Int): HtmlElement[Msg] =
                 div(button(phx.onClick(Msg.Inc), "inc"), span(model.toString))
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      _ <- outbox.take
                      recorded <- calls.get
                    yield assertTrue(recorded == Vector(0, 1))
                  }
      yield result
    },
    test("component event hooks run before component handleMessage") {
      object HookedComponent extends LiveComponent[Unit, HookedComponent.Msg, Int]:
        enum Msg:
          case Inc

        override def hooks: ComponentLiveHooks[Unit, Msg, Int] =
          ComponentLiveHooks.empty[Unit, Msg, Int].event("add") { (_, model, msg, _, _) =>
            msg match
              case Msg.Inc => ZIO.succeed(LiveEventHookResult.cont(model + 10))
          }

        def mount(props: Unit, ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
              case Msg.Inc => ZIO.succeed(model + 1)

        def render(props: Unit, model: Int, self: ComponentRef[Msg]) =
          div(button(phx.onClick(Msg.Inc), phx.target(self), "inc"), span(model.toString))

      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(idAttr := "root", liveComponent(HookedComponent, id = "hooked", props = ()))

      val event = click(Vector("root:div", "component:0:1", "tag:0:button"), cid = Some(1))

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _     <- outbox.take
                      _     <- socket.inbox.offer(event -> meta)
                      reply <- outbox.take
                    yield assertTrue(diffFromPayload(reply._1).exists(containsValue(_, "11")))
                  }
      yield result)
    }
  )
end LifecycleHookSpec
