package scalive

import zio.*
import zio.http.URL
import zio.http.codec.HttpCodec
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object SocketSpec extends ZIOSpecDefault:

  enum Msg:
    case FromClient
    case FromServer
    case SetTitle

  final case class Model(counter: Int = 0, staticFlag: Option[Boolean] = None)

  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private def makeLiveView(serverStream: ZStream[LiveView.SubscriptionsContext, Nothing, Msg]) =
    new LiveView[Msg, Model]:
      def mount =
        LiveContext.staticChanged.map(flag => Model(staticFlag = Some(flag)))

      def handleMessage(model: Model) = {
        case Msg.FromClient => ZIO.succeed(model.copy(counter = model.counter + 1))
        case Msg.FromServer => ZIO.succeed(model.copy(counter = model.counter + 10))
        case Msg.SetTitle   => LiveContext.putTitle("Updated title").as(model)
      }

      def render(model: Model): HtmlElement =
        div(
          idAttr := "root",
          phx.onClick(Msg.FromClient),
          model.counter.toString
        )

      def subscriptions(model: Model) = serverStream

  private def makeSocket(ctx: LiveContext, lv: LiveView[Msg, Model]) =
    Socket.start("id", "token", lv, ctx, meta)

  override def spec = suite("SocketSpec")(
    test("emits init diff and uses LiveContext") {
      val ctx = LiveContext(staticChanged = true)
      val lv  = makeLiveView(ZStream.empty)
      for
        socket <- makeSocket(ctx, lv)
        msgs   <- socket.outbox.take(1).runHead
      yield assertTrue(
        msgs.size == 1,
        msgs.head._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(_)) => true
          case _                                                       => false
        ,
        msgs.head._2 == meta
      )
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

          def mount = callsRef.update(_ :+ "mount").as(0)

          override def handleParams(model: Int, params: (Option[String], String), _url: URL) =
            val (q, path) = params
            callsRef
              .update(_ :+ s"params:${q.getOrElse("")}:$path")
              .as(model)

          def handleMessage(model: Int) = _ => ZIO.succeed(model)

          def render(model: Int): HtmlElement =
            div(model.toString)

          def subscriptions(model: Int) = ZStream.empty
        initialUrl <- ZIO.fromEither(URL.decode("/?q=1")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    ctx,
                    meta,
                    initialUrl = initialUrl
                  )
        _     <- socket.outbox.take(1).runCollect
        calls <- callsRef.get
      yield assertTrue(calls == List("mount", "params:1:/"))
    },
    test("emits live navigation when bootstrap handleParams patches") {
      val ctx = LiveContext(staticChanged = false)
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Int]:
          def mount = ZIO.succeed(0)

          override def handleParams(model: Int, _query: queryCodec.Out, url: URL) =
            val path = url.path.encode
            callsRef.update(_ :+ path) *>
              (if path == "/start" then LiveContext.pushPatch("/done").as(model + 1)
               else ZIO.succeed(model + 10))

          def handleMessage(model: Int) = _ => ZIO.succeed(model)

          def render(model: Int): HtmlElement =
            div(model.toString)

          def subscriptions(model: Int) = ZStream.empty
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    ctx,
                    meta,
                    initialUrl = initialUrl
                  )
        messages <- socket.outbox.take(2).runCollect
        calls    <- callsRef.get
      yield assertTrue(
        calls == List("/start", "/done"),
        messages.size == 2,
        messages.head._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(_)) => true
          case _                                                       => false
        ,
        messages.drop(1).headOption.exists { case (payload, _) =>
          payload match
            case Payload.LiveNavigation("/done", LivePatchKind.Push) => true
            case _                                                    => false
        }
      )
    },
    test("resolves query-only bootstrap patches against current path") {
      val ctx = LiveContext(staticChanged = false)
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Int]:
          override val queryCodec: LiveQueryCodec[Option[String]] =
            LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

          def mount = ZIO.succeed(0)

          override def handleParams(model: Int, query: Option[String], url: URL) =
            val current = s"${url.path.encode}:${query.getOrElse("")}"
            callsRef.update(_ :+ current) *>
              (if query.isEmpty then LiveContext.pushPatch(queryCodec, Some("1")).as(model)
               else ZIO.succeed(model))

          def handleMessage(model: Int) = _ => ZIO.succeed(model)

          def render(model: Int): HtmlElement =
            div(model.toString)

          def subscriptions(model: Int) = ZStream.empty
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    ctx,
                    meta,
                    initialUrl = initialUrl
                  )
        messages <- socket.outbox.take(2).runCollect
        calls    <- callsRef.get
      yield assertTrue(
        calls == List("/start:", "/start:1"),
        messages.drop(1).headOption.exists { case (payload, _) =>
          payload match
            case Payload.LiveNavigation("/start?q=1", LivePatchKind.Push) => true
            case _                                                    => false
        }
      )
    },
    test("server stream emits diff") {
      val ctx = LiveContext(staticChanged = false)
      val lv  = makeLiveView(ZStream.succeed(Msg.FromServer))
      for
        socket <- makeSocket(ctx, lv)
        diff   <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diff._1.isInstanceOf[Payload.Diff])
    },
    test("shutdown stops outbox") {
      val ctx = LiveContext(staticChanged = false)
      val lv  = makeLiveView(ZStream.empty)
      for
        socket <- makeSocket(ctx, lv)
        _      <- socket.shutdown
        res    <- socket.outbox.runCollect
      yield assertTrue(res.nonEmpty)
    },
    test("emits title updates on top-level diff") {
      val ctx = LiveContext(staticChanged = false)
      val lv  = makeLiveView(ZStream.succeed(Msg.SetTitle))
      for
        socket <- makeSocket(ctx, lv)
        diff   <- socket.outbox.drop(1).runHead.some
      yield
        val title = diff._1 match
          case Payload.Diff(Diff.Tag(_, _, _, _, title, _, _, _)) => title
          case _                                                   => None

        assertTrue(
          diff._1.isInstanceOf[Payload.Diff],
          title.contains("Updated title")
        )
    },
    test("cids_destroyed replies with prunable cids") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement =
          div(
            component(1, span("A")),
            component(2, span("B"))
          )

        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = "cids_destroyed",
        value = zio.json.ast.Json.Obj(
          "cids" -> zio.json.ast.Json.Arr(zio.json.ast.Json.Num(1), zio.json.ast.Json.Num(3))
        ),
        uploads = None,
        cid = None,
        meta = None
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        repliesFiber <- socket.outbox.drop(1).take(2).runCollect.fork
        _      <- socket.inbox.offer(event -> meta)
        _      <- socket.inbox.offer(event -> meta)
        replies <- repliesFiber.join
        first = replies.head
        second = replies.tail.head
      yield
        val firstCids = first._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Raw(zio.json.ast.Json.Obj(fields))
              ) =>
            fields.collectFirst {
              case (
                    "cids",
                    zio.json.ast.Json.Arr(values)
                  ) => values.collect { case zio.json.ast.Json.Num(v) => v.intValue }
            }.getOrElse(Vector.empty)
          case _ => Vector.empty

        val secondCids = second._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Raw(zio.json.ast.Json.Obj(fields))
              ) =>
            fields.collectFirst {
              case (
                    "cids",
                    zio.json.ast.Json.Arr(values)
                  ) => values.collect { case zio.json.ast.Json.Num(v) => v.intValue }
            }.getOrElse(Vector.empty)
          case _ => Vector.empty

        assertTrue(
          first._1.isInstanceOf[Payload.Reply],
          second._1.isInstanceOf[Payload.Reply],
          firstCids == Vector(1),
          secondCids.isEmpty
        )
    },
    test("intercept replies are encoded under top-level r") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement =
          div("constant")

        def subscriptions(model: Unit) = ZStream.empty

        override def interceptEvent(model: Unit, event: String, value: Json) =
          ZIO.succeed(
            InterceptResult.haltReply(
              model,
              Json.Obj("kind" -> Json.Str("hook"))
            )
          )

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = "any",
        value = Json.Obj.empty,
        uploads = None,
        cid = None,
        meta = None
      )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _      <- socket.inbox.offer(event -> meta)
        reply  <- replyFiber.join.some
      yield
        val hasReplyAtTopLevelR = reply._1 match
          case payload: Payload.Reply =>
            payload
              .toJsonAST
              .toOption
              .collect { case Json.Obj(fields) => fields }
              .exists(_.collectFirst {
                case ("response", Json.Obj(responseFields)) =>
                  responseFields.collectFirst {
                    case (
                          "diff",
                          Json.Obj(diffFields)
                        ) => diffFields.exists {
                        case ("r", Json.Obj(replyObj)) =>
                          replyObj.exists {
                            case ("kind", Json.Str("hook")) => true
                            case _                             => false
                          }
                        case _                        =>
                          false
                      }
                  }.contains(true)
              }.contains(true))
          case _                    => false

        assertTrue(hasReplyAtTopLevelR)
    }
  )
end SocketSpec
