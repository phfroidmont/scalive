package scalive

import zio.*
import zio.http.URL
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
  )
    : Payload.Event =
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

  def spec = suite("LifecycleHookSpec")(
    test("event hooks continue in attach order before handleMessage") {
      enum Msg:
        case Inc

      val lv = new LiveView[Msg, Int]:
        def mount =
          LiveContext.attachEventHook[Msg, Int]("add") { (model, msg, event) =>
            msg match
              case Msg.Inc if event.params.get("amount").contains("10") =>
                ZIO.succeed(LiveEventResult.cont(model + 10))
              case _ => ZIO.succeed(LiveEventResult.cont(model))
          } *>
            LiveContext.attachEventHook[Msg, Int]("double") { (model, msg, _) =>
              msg match
                case Msg.Inc => ZIO.succeed(LiveEventResult.cont(model * 2))
              end match
            } *>
            ZIO.succeed(0)

        def handleMessage(model: Int) = { case Msg.Inc => ZIO.succeed(model + 1) }

        def render(model: Int): HtmlElement[Msg] =
          div(span(model.toString), button(phx.onClick(Msg.Inc), "inc"))

        def subscriptions(model: Int) = ZStream.empty

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
        def mount =
          LiveContext.attachEventHook[Msg, Int]("halt") { (model, _, _) =>
            ZIO.succeed(LiveEventResult.haltReply(model + 5, replyValue))
          }.as(0)

        def handleMessage(model: Int) = { case Msg.Inc => ZIO.succeed(model + 100) }

        def render(model: Int): HtmlElement[Msg] =
          div(span(model.toString), button(phx.onClick(Msg.Inc), "inc"))

        def subscriptions(model: Int) = ZStream.empty

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
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
    test("event hook detach removes only the named hook") {
      enum Msg:
        case Inc
        case Detach

      val lv = new LiveView[Msg, Int]:
        def mount =
          LiveContext.attachEventHook[Msg, Int]("add") { (model, msg, _) =>
            msg match
              case Msg.Inc    => ZIO.succeed(LiveEventResult.cont(model + 10))
              case Msg.Detach => ZIO.succeed(LiveEventResult.cont(model))
          }.as(0)

        def handleMessage(model: Int) = {
          case Msg.Inc    => ZIO.succeed(model + 1)
          case Msg.Detach => LiveContext.detachEventHook("add").as(model)
        }

        def render(model: Int): HtmlElement[Msg] =
          div(
            span(model.toString),
            button(phx.onClick(Msg.Inc), "inc"),
            button(phx.onClick(Msg.Detach), "detach")
          )

        def subscriptions(model: Int) = ZStream.empty

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
    test("duplicate hook ids fail per lifecycle stage") {
      enum Msg:
        case Inc

      val lv = new LiveView[Msg, Int]:
        def mount =
          LiveContext.attachEventHook[Msg, Int]("same")((model, _, _) =>
            ZIO.succeed(LiveEventResult.cont(model))
          ) *>
            LiveContext.attachEventHook[Msg, Int]("same")((model, _, _) =>
              ZIO.succeed(LiveEventResult.cont(model))
            ).as(0)

        def handleMessage(model: Int) = { case Msg.Inc => ZIO.succeed(model + 1) }
        def render(model: Int): HtmlElement[Msg] = div(model.toString)
        def subscriptions(model: Int)             = ZStream.empty

      for exit <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta).exit
      yield assertTrue(exit.isFailure)
    },
    test("params hooks run before handleParams") {
      val lv = new LiveView[Unit, String]:
        def mount =
          LiveContext.attachParamsHook[String]("url") { (_, url) =>
            ZIO.succeed(LiveHookResult.cont(url.encode))
          }.as("mount")

        override def handleParams(model: String, query: queryCodec.Out, url: URL) =
          ZIO.succeed(s"$model|callback")

        def handleMessage(model: String) = _ => ZIO.succeed(model)
        def render(model: String): HtmlElement[Unit] = div(model)
        def subscriptions(model: String)             = ZStream.empty

      ZIO.scoped(for
        initialUrl <- ZIO.fromEither(URL.decode("/hooked?x=1")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl
                  )
        result <- withOutbox(socket) { outbox =>
                    outbox.take.map(reply =>
                      assertTrue(
                        diffFromPayload(reply._1).exists(containsValue(_, "/hooked?x=1|callback"))
                      )
                    )
                  }
      yield result)
    },
    test("info hook halt skips subscription handleMessage") {
      enum Msg:
        case Tick

      for
        messages <- Queue.unbounded[Msg]
        started  <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, Int]:
               def mount =
                 LiveContext.attachInfoHook[Msg, Int]("halt") { (model, msg) =>
                   msg match
                     case Msg.Tick => ZIO.succeed(LiveHookResult.halt(model + 10))
                 }.as(0)

               def handleMessage(model: Int) = { case Msg.Tick => ZIO.succeed(model + 1) }
               def render(model: Int): HtmlElement[Msg] = div(model.toString)
               def subscriptions(model: Int) =
                 ZStream.fromZIO(started.succeed(())).flatMap(_ => ZStream.fromQueue(messages))
        result <- ZIO.scoped(for
                    socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
                    result <- withOutbox(socket) { outbox =>
                                for
                                  _ <- outbox.take
                                  _ <- started.await
                                  _ <- messages.offer(Msg.Tick)
                                  diff <- outbox.take
                                yield assertTrue(
                                  diffFromPayload(diff._1).exists(containsValue(_, "10"))
                                )
                              }
                  yield result)
      yield result
    },
    test("async hooks continue in attach order before handleMessage") {
      enum Msg:
        case Loaded(result: LiveAsyncResult[String])

      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount =
                 LiveContext.attachAsyncHook[Msg, String]("name") { (model, msg, event) =>
                   msg match
                     case Msg.Loaded(LiveAsyncResult.Ok("loaded")) if event.name == "load" =>
                       ZIO.succeed(LiveHookResult.cont(s"$model:hook"))
                     case _ => ZIO.succeed(LiveHookResult.cont(model))
                 } *>
                   LiveContext.attachAsyncHook[Msg, String]("order") { (model, msg, _) =>
                     msg match
                       case Msg.Loaded(LiveAsyncResult.Ok(_)) =>
                         ZIO.succeed(LiveHookResult.cont(s"$model:second"))
                       case _ => ZIO.succeed(LiveHookResult.cont(model))
                   } *>
                   LiveContext.startAsync("load")(release.await.as("loaded"))(Msg.Loaded(_))
                     .as("loading")

               def handleMessage(model: String) = {
                 case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(s"$model:$value")
                 case _                                     => ZIO.succeed(model)
               }
               def render(model: String): HtmlElement[Msg] = div(model)
               def subscriptions(model: String)             = ZStream.empty
        result <- ZIO.scoped(for
                    socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
                    result <- withOutbox(socket) { outbox =>
                                for
                                  _     <- outbox.take
                                  _     <- release.succeed(())
                                  reply <- outbox.take
                                yield assertTrue(
                                  diffFromPayload(reply._1).exists(
                                    containsValue(_, "loading:hook:second:loaded")
                                  )
                                )
                              }
                  yield result)
      yield result
    },
    test("async hook halt skips handleMessage and detach removes the hook") {
      enum Msg:
        case Start
        case Detach
        case Loaded(result: LiveAsyncResult[String])

      val lv = new LiveView[Msg, String]:
        def mount =
          LiveContext.attachAsyncHook[Msg, String]("halt") { (model, msg, event) =>
            msg match
              case Msg.Loaded(LiveAsyncResult.Ok("done")) if event.name == "load" =>
                ZIO.succeed(LiveHookResult.halt(s"$model:hook"))
              case _ => ZIO.succeed(LiveHookResult.cont(model))
          }.as("ready")

        def handleMessage(model: String) = {
          case Msg.Start =>
            LiveContext.startAsync("load")(ZIO.succeed("done"))(Msg.Loaded(_)).as(s"$model:start")
          case Msg.Detach =>
            LiveContext.detachAsyncHook("halt").as(s"$model:detached")
          case Msg.Loaded(LiveAsyncResult.Ok(value)) =>
            ZIO.succeed(s"$model:callback:$value")
          case _ => ZIO.succeed(model)
        }

        def render(model: String): HtmlElement[Msg] =
          div(
            span(model),
            button(phx.onClick(Msg.Start), "start"),
            button(phx.onClick(Msg.Detach), "detach")
          )

        def subscriptions(model: String) = ZStream.empty

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      _ <- outbox.take
                      halted <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:2:button")) -> meta)
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      _ <- outbox.take
                      continued <- outbox.take
                    yield assertTrue(
                      diffFromPayload(halted._1).exists(containsValue(_, "ready:start:hook")),
                      !diffFromPayload(halted._1).exists(containsValue(_, "callback")),
                      diffFromPayload(continued._1).exists(
                        containsValue(_, "ready:start:hook:detached:start:callback:done")
                      )
                    )
                  }
      yield result)
    },
    test("component event hook continues and detach removes only that hook") {
      enum Msg:
        case Inc
        case Detach

      object Counter extends LiveComponent[Unit, Msg, Int]:
        def mount(props: Unit) =
          LiveContext.attachEventHook[Msg, Int]("add") { (model, msg, _) =>
            msg match
              case Msg.Inc    => ZIO.succeed(LiveEventResult.cont(model + 10))
              case Msg.Detach => ZIO.succeed(LiveEventResult.cont(model))
          }.as(0)

        def handleMessage(model: Int) = {
          case Msg.Inc    => ZIO.succeed(model + 1)
          case Msg.Detach => LiveContext.detachEventHook("add").as(model)
        }

        def render(model: Int, self: ComponentRef[Msg]): HtmlElement[Msg] =
          div(
            span(model.toString),
            button(phx.onClick(Msg.Inc), phx.target(self), "inc"),
            button(phx.onClick(Msg.Detach), phx.target(self), "detach")
          )

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div(liveComponent(Counter, "counter", ()))
        def subscriptions(model: Unit)             = ZStream.empty

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "component:0:1", "tag:1:button"),
                               cid = Some(1)
                             ) -> meta
                           )
                      first <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "component:0:1", "tag:2:button"),
                               cid = Some(1)
                             ) -> meta
                           )
                      _ <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "component:0:1", "tag:1:button"),
                               cid = Some(1)
                             ) -> meta
                           )
                      second <- outbox.take
                    yield assertTrue(
                      diffFromPayload(first._1).exists(containsValue(_, "11")),
                      diffFromPayload(second._1).exists(containsValue(_, "12"))
                    )
                  }
      yield result)
    },
    test("component event hook halt skips handleMessage") {
      enum Msg:
        case Inc

      object Counter extends LiveComponent[Unit, Msg, Int]:
        def mount(props: Unit) =
          LiveContext.attachEventHook[Msg, Int]("halt") { (model, _, _) =>
            ZIO.succeed(LiveEventResult.halt(model + 5))
          }.as(0)

        def handleMessage(model: Int) = { case Msg.Inc => ZIO.succeed(model + 100) }

        def render(model: Int, self: ComponentRef[Msg]): HtmlElement[Msg] =
          button(phx.onClick(Msg.Inc), phx.target(self), model.toString)

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div(liveComponent(Counter, "counter", ()))
        def subscriptions(model: Unit)             = ZStream.empty

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "component:0:1"),
                               attrIndex = 1,
                               cid = Some(1)
                             ) -> meta
                           )
                      reply <- outbox.take
                    yield assertTrue(
                      diffFromPayload(reply._1).exists(containsValue(_, "5")),
                      !diffFromPayload(reply._1).exists(containsValue(_, "100"))
                    )
                  }
      yield result)
    },
    test("component afterRender hook updates state for the next render only") {
      enum Msg:
        case Inc

      object Counter extends LiveComponent[Unit, Msg, Int]:
        def mount(props: Unit) =
          LiveContext.attachAfterRenderHook[Int]("multiply") { model =>
            ZIO.succeed(if model > 0 then model * 10 else model)
          }.as(0)

        def handleMessage(model: Int) = { case Msg.Inc => ZIO.succeed(model + 1) }

        def render(model: Int, self: ComponentRef[Msg]): HtmlElement[Msg] =
          button(phx.onClick(Msg.Inc), phx.target(self), model.toString)

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div(liveComponent(Counter, "counter", ()))
        def subscriptions(model: Unit)             = ZStream.empty

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "component:0:1"),
                               attrIndex = 1,
                               cid = Some(1)
                             ) -> meta
                           )
                      first <- outbox.take
                      _ <- socket.inbox.offer(
                             click(
                               Vector("root:div", "component:0:1"),
                               attrIndex = 1,
                               cid = Some(1)
                             ) -> meta
                           )
                      second <- outbox.take
                    yield assertTrue(
                      diffFromPayload(first._1).exists(containsValue(_, "1")),
                      !diffFromPayload(first._1).exists(containsValue(_, "10")),
                      diffFromPayload(second._1).exists(containsValue(_, "11"))
                    )
                  }
      yield result)
    },
    test("component unsupported hook stages fail") {
      object AttachInfo extends LiveComponent[Unit, Unit, Unit]:
        def mount(props: Unit) =
          LiveContext.attachInfoHook[Unit, Unit]("bad") { (model, _) =>
            ZIO.succeed(LiveHookResult.cont(model))
          }.as(())
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit, self: ComponentRef[Unit]): HtmlElement[Unit] = div("attach-info")

      object DetachInfo extends LiveComponent[Unit, Unit, Unit]:
        def mount(props: Unit) = LiveContext.detachInfoHook("bad").as(())
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit, self: ComponentRef[Unit]): HtmlElement[Unit] = div("detach-info")

      def parent(component: LiveComponent[Unit, Unit, Unit]) = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div(liveComponent(component, "bad", ()))
        def subscriptions(model: Unit)             = ZStream.empty

      for
        attachExit <- Socket
                        .start("id", "token", parent(AttachInfo), LiveContext(staticChanged = false), meta)
                        .exit
        detachExit <- Socket
                        .start("id", "token", parent(DetachInfo), LiveContext(staticChanged = false), meta)
                        .exit
      yield assertTrue(attachExit.isFailure, detachExit.isFailure)
    },
    test("afterRender hook updates state for the next render only") {
      enum Msg:
        case Inc

      val lv = new LiveView[Msg, Int]:
        def mount =
          LiveContext.attachAfterRenderHook[Int]("multiply") { model =>
            ZIO.succeed(if model > 0 then model * 10 else model)
          }.as(0)

        def handleMessage(model: Int) = { case Msg.Inc => ZIO.succeed(model + 1) }

        def render(model: Int): HtmlElement[Msg] =
          div(span(model.toString), button(phx.onClick(Msg.Inc), "inc"))

        def subscriptions(model: Int) = ZStream.empty

      ZIO.scoped(for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _ <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      first <- outbox.take
                      _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      second <- outbox.take
                    yield assertTrue(
                      diffFromPayload(first._1).exists(containsValue(_, "1")),
                      !diffFromPayload(first._1).exists(containsValue(_, "10")),
                      diffFromPayload(second._1).exists(containsValue(_, "11"))
                    )
                  }
      yield result)
    }
  )
end LifecycleHookSpec
