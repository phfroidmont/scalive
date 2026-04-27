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

  private def click(path: Vector[String], attrIndex: Int = 0, value: Json = Json.Obj.empty)
    : Payload.Event =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = value
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
