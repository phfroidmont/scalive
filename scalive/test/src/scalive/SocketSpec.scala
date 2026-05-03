package scalive

import zio.*
import zio.http.URL
import zio.http.codec.HttpCodec
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object SocketSpec extends ZIOSpecDefault:

  enum Msg:
    case FromClient
    case FromServer
    case SetTitle

  final case class Model(counter: Int = 0, staticFlag: Option[Boolean] = None)

  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private def makeLiveView(serverStream: ZStream[Any, Nothing, Msg]) =
    new LiveView[Msg, Model]:
      def mount(ctx: MountContext) =
        ctx.subscriptions
          .start("server")(serverStream).as(Model(staticFlag = Some(ctx.staticChanged)))

      def handleMessage(model: Model, ctx: MessageContext) =
        case Msg.FromClient => ZIO.succeed(model.copy(counter = model.counter + 1))
        case Msg.FromServer => ZIO.succeed(model.copy(counter = model.counter + 10))
        case Msg.SetTitle   => ctx.title.set("Updated title").as(model)

      def render(model: Model): HtmlElement[Msg] =
        div(
          idAttr := "root",
          phx.onClick(Msg.FromClient),
          span(model.counter.toString),
          span(model.staticFlag.map(_.toString).getOrElse("none"))
        )

  private def makeSocket(ctx: LiveContext, lv: LiveView[Msg, Model]) =
    Socket.start("id", "token", lv, ctx, meta)

  private def withOutbox[Msg, Model, A](
    socket: Socket[Msg, Model]
  )(
    f: Queue[(Payload, WebSocketMessage.Meta)] => Task[A]
  ): Task[A] =
    withOutboxAndInit(socket)((_, queue) => f(queue))

  private def withOutboxAndInit[Msg, Model, A](
    socket: Socket[Msg, Model]
  )(
    f: ((Payload, WebSocketMessage.Meta), Queue[(Payload, WebSocketMessage.Meta)]) => Task[A]
  ): Task[A] =
    for
      queue  <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
      fiber  <- socket.outbox.runForeach(queue.offer).fork
      init   <- queue.take
      result <- f(init, queue).ensuring(fiber.interrupt)
    yield result

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
      case Diff.Value(current)   => current == value
      case Diff.Dynamic(_, diff) => containsValue(diff, value)
      case _                     => false

  private def diffFromPayload(payload: Payload): Option[Diff] =
    payload match
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(diff))          => Some(diff)
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff))              => Some(diff)
      case Payload.Reply(ReplyStatus.Ok, LiveResponse.InterceptReply(_, diff)) => diff
      case Payload.Diff(diff)                                                  => Some(diff)
      case _                                                                   => None

  override def spec = suite("SocketSpec")(
    test("emits init diff and exposes lifecycle flags") {
      val ctx = LiveContext(staticChanged = true)
      val lv  = makeLiveView(ZStream.empty)

      for
        socket <- makeSocket(ctx, lv)
        msg    <- socket.outbox.take(1).runHead.some
      yield assertTrue(
        msg._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(diff)) =>
            containsValue(diff, "true")
          case _ => false,
        msg._2 == meta
      )
    },
    test("server stream emits diff") {
      val lv = makeLiveView(ZStream.succeed(Msg.FromServer))

      for
        socket <- makeSocket(LiveContext(staticChanged = false), lv)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "10")))
    },
    test("emits title updates on top-level diff") {
      val lv = makeLiveView(ZStream.succeed(Msg.SetTitle))

      for
        socket <- makeSocket(LiveContext(staticChanged = false), lv)
        update <- socket.outbox.drop(1).runHead.some
      yield
        val title = update._1 match
          case Payload.Diff(Diff.Tag(_, _, _, _, value, _, _, _)) => value
          case Payload
                .Reply(ReplyStatus.Ok, LiveResponse.Diff(Diff.Tag(_, _, _, _, value, _, _, _))) =>
            value
          case _ => None
        assertTrue(title.contains("Updated title"))
    },
    test("runs handleParams during connected bootstrap") {
      val ctx = LiveContext(staticChanged = false)
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Int]:
               override val queryCodec: LiveQueryCodec[(Option[String], String)] =
                 LiveQueryCodec.custom(
                   decodeFn = url => Right(url.queryParam("q") -> url.path.encode),
                   encodeFn = _ => Right("?")
                 )

               def mount(ctx: MountContext) =
                 callsRef.update(_ :+ "mount").as(0)

               override def handleParams(
                 model: Int,
                 params: (Option[String], String),
                 _url: URL,
                 ctx: ParamsContext
               ) =
                 val _         = ctx
                 val (q, path) = params
                 callsRef.update(_ :+ s"params:${q.getOrElse("")}:$path").as(model)

               def handleMessage(model: Int, ctx: MessageContext) =
                 (_: Unit) => ZIO.succeed(model)

               def render(model: Int): HtmlElement[Unit] = div(model.toString)
        initialUrl <- ZIO.fromEither(URL.decode("/?q=1")).orDie
        socket     <- Socket.start("id", "token", lv, ctx, meta, initialUrl = initialUrl)
        _          <- socket.outbox.take(1).runCollect
        calls      <- callsRef.get
      yield assertTrue(calls == List("mount", "params:1:/"))
      end for
    },
    test("emits live navigation when bootstrap handleParams patches") {
      val ctx = LiveContext(staticChanged = false)
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Int]:
               def mount(ctx: MountContext) =
                 ZIO.succeed(0)

               override def handleParams(
                 model: Int,
                 query: queryCodec.Out,
                 url: URL,
                 ctx: ParamsContext
               ) =
                 val _    = query
                 val path = url.path.encode
                 callsRef.update(_ :+ path) *>
                   (if path == "/start" then ctx.nav.pushPatch("/done").as(model + 1)
                    else ZIO.succeed(model + 10))

               def handleMessage(model: Int, ctx: MessageContext) =
                 (_: Unit) => ZIO.succeed(model)

               def render(model: Int): HtmlElement[Unit] = div(model.toString)
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket     <- Socket.start("id", "token", lv, ctx, meta, initialUrl = initialUrl)
        messages   <- socket.outbox.take(2).runCollect
        calls      <- callsRef.get
      yield assertTrue(
        calls == List("/start", "/done"),
        messages.drop(1).headOption.exists { case (payload, _) =>
          payload == Payload.LiveNavigation("/done", LivePatchKind.Push)
        }
      )
      end for
    },
    test("resolves query-only bootstrap patches against current path") {
      val ctx = LiveContext(staticChanged = false)
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Int]:
               override val queryCodec: LiveQueryCodec[Option[String]] =
                 LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

               def mount(ctx: MountContext) =
                 ZIO.succeed(0)

               override def handleParams(
                 model: Int,
                 query: Option[String],
                 url: URL,
                 ctx: ParamsContext
               ) =
                 val current = s"${url.path.encode}:${query.getOrElse("")}"
                 callsRef.update(_ :+ current) *>
                   (if query.isEmpty then ctx.nav.pushPatch(queryCodec, Some("1")).as(model)
                    else ZIO.succeed(model))

               def handleMessage(model: Int, ctx: MessageContext) =
                 (_: Unit) => ZIO.succeed(model)

               def render(model: Int): HtmlElement[Unit] = div(model.toString)
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket     <- Socket.start("id", "token", lv, ctx, meta, initialUrl = initialUrl)
        messages   <- socket.outbox.take(2).runCollect
        calls      <- callsRef.get
      yield assertTrue(
        calls == List("/start:", "/start:1"),
        messages.drop(1).headOption.exists { case (payload, _) =>
          payload == Payload.LiveNavigation("/start?q=1", LivePatchKind.Push)
        }
      )
      end for
    },
    test("live patch handleParams redirect loops emit an error") {
      final case class LoopModel(shouldLoop: Boolean)

      val ctx = LiveContext(staticChanged = false)
      val lv  = new LiveView[Unit, LoopModel]:
        override val queryCodec: LiveQueryCodec[Unit] = LiveQueryCodec.none

        def mount(ctx: MountContext) =
          ZIO.succeed(LoopModel(shouldLoop = false))

        override def handleParams(
          model: LoopModel,
          query: queryCodec.Out,
          url: URL,
          ctx: ParamsContext
        ) =
          if url.queryParam("loop").contains("true") then
            if model.shouldLoop then ctx.nav.pushPatch("?loop=true").as(model)
            else ZIO.succeed(model.copy(shouldLoop = false))
          else ZIO.succeed(model.copy(shouldLoop = true))

        def handleMessage(model: LoopModel, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: LoopModel): HtmlElement[Unit] = div(model.shouldLoop.toString)

      for
        initialUrl <- ZIO.fromEither(URL.decode("/redirectloop")).orDie
        socket     <- Socket.start("id", "token", lv, ctx, meta, initialUrl = initialUrl)
        result     <- withOutbox(socket) { outbox =>
                    for
                      reply   <- socket.livePatch("?loop=true", meta)
                      emitted <- outbox.take
                    yield assertTrue(
                      reply == Payload.okReply(LiveResponse.Empty),
                      emitted._1 == Payload.Error
                    )
                  }
      yield result
    },
    test("raw event hooks can reply under top-level r") {
      val replyValue = Json.Obj("ok" -> Json.Bool(true))
      val lv         = new LiveView[Unit, String]:
        override def hooks: LiveHooks[Unit, String] =
          LiveHooks.empty.rawEvent("intercept") { (model, event, _) =>
            if event.bindingId == "intercept" then
              ZIO.succeed(LiveEventHookResult.haltReply("intercepted", replyValue))
            else ZIO.succeed(LiveEventHookResult.cont(model))
          }

        def mount(ctx: MountContext) =
          ZIO.succeed("ready")

        def handleMessage(model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: String): HtmlElement[Unit] = div(model)

      val event: Payload.Event =
        Payload.Event(`type` = "click", event = "intercept", value = Json.Obj.empty)

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _     <- socket.inbox.offer(event -> meta)
                      reply <- outbox.take
                    yield assertTrue(
                      reply._1 match
                        case Payload.Reply(
                              ReplyStatus.Ok,
                              LiveResponse.InterceptReply(`replyValue`, Some(diff))
                            ) =>
                          containsValue(diff, "intercepted")
                        case _ => false
                    )
                  }
      yield result
    },
    test("routes self-targeted live component events to component state") {
      object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
        object Msg

        def mount(props: Unit, ctx: MountContext) =
          ZIO.succeed(0)

        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          (_: Msg.type) => ZIO.succeed(model + 1)

        def render(props: Unit, model: Int, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), model.toString)

      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(CounterComponent, id = "counter", props = ()))

      val event: Payload.Event = Payload.Event(
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
                    yield assertTrue(diffFromPayload(reply._1).exists(containsValue(_, "1")))
                  }
      yield result
    },
    test("sendUpdate applies typed props to an existing live component") {
      object LabelComponent extends LiveComponent[String, Unit, String]:
        def mount(props: String, ctx: MountContext) =
          ZIO.succeed(props)

        override def update(props: String, model: String, ctx: UpdateContext) =
          ZIO.succeed(props)

        def handleMessage(props: String, model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(props: String, model: String, self: ComponentRef[Unit]) =
          div(model)

      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ctx.components.sendUpdate[LabelComponent.type]("label", "updated").as(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(
            button(phx.onClick(()), "update"),
            liveComponent(LabelComponent, id = "label", props = "initial")
          )

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _     <- socket.inbox.offer(event -> meta)
                      reply <- outbox.take
                    yield assertTrue(diffFromPayload(reply._1).exists(containsValue(_, "updated")))
                  }
      yield result
    }
  )
end SocketSpec
