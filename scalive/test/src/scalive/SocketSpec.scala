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

      def render(model: Model): HtmlElement[Msg] =
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

               def render(model: Int): HtmlElement[Unit] =
                 div(model.toString)

               def subscriptions(model: Int) = ZStream.empty
        initialUrl <- ZIO.fromEither(URL.decode("/?q=1")).orDie
        socket     <- Socket.start(
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
      end for
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

               def render(model: Int): HtmlElement[Unit] =
                 div(model.toString)

               def subscriptions(model: Int) = ZStream.empty
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket     <- Socket.start(
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
            case _                                                   => false
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

               def mount = ZIO.succeed(0)

               override def handleParams(model: Int, query: Option[String], url: URL) =
                 val current = s"${url.path.encode}:${query.getOrElse("")}"
                 callsRef.update(_ :+ current) *>
                   (if query.isEmpty then LiveContext.pushPatch(queryCodec, Some("1")).as(model)
                    else ZIO.succeed(model))

               def handleMessage(model: Int) = _ => ZIO.succeed(model)

               def render(model: Int): HtmlElement[Unit] =
                 div(model.toString)

               def subscriptions(model: Int) = ZStream.empty
        initialUrl <- ZIO.fromEither(URL.decode("/start")).orDie
        socket     <- Socket.start(
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
            case _                                                        => false
        }
      )
      end for
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
          case _                                                  => None

        assertTrue(
          diff._1.isInstanceOf[Payload.Diff],
          title.contains("Updated title")
        )
    },
    test("cids_destroyed replies with prunable cids") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
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
        socket       <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        repliesFiber <- socket.outbox.drop(1).take(2).runCollect.fork
        _            <- socket.inbox.offer(event -> meta)
        _            <- socket.inbox.offer(event -> meta)
        replies      <- repliesFiber.join
        first  = replies.head
        second = replies.tail.head
      yield
        val firstCids = first._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Raw(zio.json.ast.Json.Obj(fields))
              ) =>
            fields
              .collectFirst {
                case (
                      "cids",
                      zio.json.ast.Json.Arr(values)
                    ) =>
                  values.collect { case zio.json.ast.Json.Num(v) => v.intValue }
              }.getOrElse(Vector.empty)
          case _ => Vector.empty

        val secondCids = second._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Raw(zio.json.ast.Json.Obj(fields))
              ) =>
            fields
              .collectFirst {
                case (
                      "cids",
                      zio.json.ast.Json.Arr(values)
                    ) =>
                  values.collect { case zio.json.ast.Json.Num(v) => v.intValue }
              }.getOrElse(Vector.empty)
          case _ => Vector.empty

        assertTrue(
          first._1.isInstanceOf[Payload.Reply],
          second._1.isInstanceOf[Payload.Reply],
          firstCids == Vector(1),
          secondCids.isEmpty
        )
      end for
    },
    test("intercept replies are encoded under top-level r") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
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
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join.some
      yield
        val hasReplyAtTopLevelR = reply._1 match
          case payload: Payload.Reply =>
            payload.toJsonAST.toOption
              .collect { case Json.Obj(fields) => fields }
              .exists(_.collectFirst { case ("response", Json.Obj(responseFields)) =>
                responseFields
                  .collectFirst {
                    case (
                          "diff",
                          Json.Obj(diffFields)
                        ) =>
                      diffFields.exists {
                        case ("r", Json.Obj(replyObj)) =>
                          replyObj.exists {
                            case ("kind", Json.Str("hook")) => true
                            case _                          => false
                          }
                        case _ =>
                          false
                      }
                  }.contains(true)
              }.contains(true))
          case _ => false

        assertTrue(hasReplyAtTopLevelR)
      end for
    },
    test("renders stateful live components with stable component ids") {
      object CounterComponent extends LiveComponent[String, Unit, String]:
        def mount(props: String)                            = ZIO.succeed(props)
        override def update(props: String, model: String)   = ZIO.succeed(props)
        def handleMessage(model: String)                    = _ => ZIO.succeed(model)
        def render(model: String, self: ComponentRef[Unit]) =
          button(phx.target(self), model)

      val lv = new LiveView[Unit, String]:
        def mount                                    = ZIO.succeed("first")
        def handleMessage(model: String)             = _ => ZIO.succeed("second")
        def render(model: String): HtmlElement[Unit] =
          div(phx.onClick(()), liveComponent(CounterComponent, id = "counter", props = model))
        def subscriptions(model: String) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div"), 0),
        value = Json.Obj.empty
      )

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        init       <- socket.outbox.take(1).runHead.some
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join.some
      yield
        val initHasComponent = init._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.InitDiff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.contains(1)
          case _ => false

        val updateHasSameComponent = reply._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.contains(1)
          case _ => false

        assertTrue(initHasComponent, updateHasSameComponent)
    },
    test("routes self-targeted live component events to component state") {
      object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
        object Msg

        def mount(props: Unit)        = ZIO.succeed(0)
        def handleMessage(model: Int) = { case Msg => ZIO.succeed(model + 1) }
        def render(model: Int, self: ComponentRef[Msg.type]) =
          button(
            phx.onClick(Msg),
            phx.target(self),
            model.toString
          )

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(CounterComponent, id = "counter", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join.some
      yield
        val componentUpdated = reply._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.get(1).exists {
              case Diff.Tag(_, dynamic, _, _, _, _, _, _) =>
                dynamic.exists {
                  case Diff.Dynamic(_, Diff.Value("1")) => true
                  case _                                => false
                }
              case _ => false
            }
          case _ => false

        assertTrue(componentUpdated)
    },
    test("untargeted live component events do not route to component state") {
      object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
        object Msg

        def mount(props: Unit)        = ZIO.succeed(0)
        def handleMessage(model: Int) = { case Msg => ZIO.succeed(model + 1) }
        def render(model: Int, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), model.toString)

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(CounterComponent, id = "counter", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
        value = Json.Obj.empty
      )

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join.some
      yield
        val componentUpdated = reply._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.get(1).exists {
              case Diff.Tag(_, dynamic, _, _, _, _, _, _) =>
                dynamic.exists {
                  case Diff.Dynamic(_, Diff.Value("1")) => true
                  case _                                => false
                }
              case _ => false
            }
          case _ => false

        assertTrue(!componentUpdated)
    },
    test("sendUpdate applies typed props to an existing live component") {
      class LabelComponent extends LiveComponent[String, Unit, String]:
        def mount(props: String)                            = ZIO.succeed(props)
        override def update(props: String, model: String)   = ZIO.succeed(props)
        def handleMessage(model: String)                    = _ => ZIO.succeed(model)
        def render(model: String, self: ComponentRef[Unit]) =
          div(phx.target(self), model)

      val lv = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) =
          _ => LiveContext.sendUpdate[LabelComponent]("label", "updated").as(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            button(phx.onClick(()), "update"),
            liveComponent(LabelComponent(), id = "label", props = "initial")
          )
        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join.some
      yield
        val componentUpdated = reply._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.get(1).exists {
              case Diff.Tag(_, dynamic, _, _, _, _, _, _) =>
                dynamic.exists {
                  case Diff.Dynamic(_, Diff.Value("updated")) => true
                  case _                                      => false
                }
              case _ => false
            }
          case _ => false

        assertTrue(componentUpdated)
    },
    test("live component streams with the same name are scoped per component") {
      object StreamComponent extends LiveComponent[String, Unit, LiveStream[String]]:
        private val Items = LiveStreamDef.byId[String, String]("items")(identity)

        def mount(props: String)                          = LiveContext.stream(Items, List(props))
        def handleMessage(model: LiveStream[String])      = _ => ZIO.succeed(model)
        def render(model: LiveStream[String], self: ComponentRef[Unit]) =
          ul(
            idAttr       := s"items-${model.entries.head.value}",
            phx.onUpdate := "stream",
            model.stream { (domId, item) => li(idAttr := domId, item) }
          )

      val lv = new LiveView[Unit, Unit]:
        def mount                         = ZIO.unit
        def handleMessage(model: Unit)    = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            liveComponent(StreamComponent, id = "one", props = "one"),
            liveComponent(StreamComponent, id = "two", props = "two")
          )
        def subscriptions(model: Unit) = ZStream.empty

      def containsValue(diff: Diff, value: String): Boolean =
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
          case Diff.Value(current) => current == value
          case Diff.Dynamic(_, diff) => containsValue(diff, value)
          case _ => false

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        init   <- socket.outbox.take(1).runHead.some
      yield
        val scoped = init._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.InitDiff(Diff.Tag(_, _, _, _, _, components, _, _))) =>
            val first  = components.get(1)
            val second = components.get(2)
            first.exists(diff => containsValue(diff, "one") && !containsValue(diff, "two")) &&
            second.exists(diff => containsValue(diff, "two") && !containsValue(diff, "one"))
          case _ => false

        assertTrue(scoped)
    },
    test("live component stream updates are scoped to the target component") {
      object StreamComponent extends LiveComponent[String, Unit, StreamComponent.Model]:
        final case class Model(prefix: String, items: LiveStream[String])

        private val Items = LiveStreamDef.byId[String, String]("items")(identity)

        def mount(props: String) =
          LiveContext.stream(Items, List(props)).map(items => Model(props, items))
        def handleMessage(model: Model) =
          _ =>
            LiveContext
              .streamInsert(Items, s"${model.prefix}-added")
              .map(items => model.copy(items = items))
        def render(model: Model, self: ComponentRef[Unit]) =
          ul(
            idAttr       := s"items-${model.prefix}",
            phx.onUpdate := "stream",
            phx.onClick(()),
            phx.target(self),
            model.items.stream { (domId, item) => li(idAttr := domId, item) }
          )

      val lv = new LiveView[Unit, Unit]:
        def mount                         = ZIO.unit
        def handleMessage(model: Unit)    = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            liveComponent(StreamComponent, id = "one", props = "one"),
            liveComponent(StreamComponent, id = "two", props = "two")
          )
        def subscriptions(model: Unit) = ZStream.empty

      def containsValue(diff: Diff, value: String): Boolean =
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

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 3),
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        replyFiber <- socket.outbox.drop(1).runHead.fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join.some
      yield
        val scoped = reply._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))) =>
            components.get(1).exists(diff => containsValue(diff, "one-added")) &&
            !components.get(2).exists(diff => containsValue(diff, "one-added"))
          case _ => false

        assertTrue(scoped)
    },
    test("cids_destroyed removes live component state before remount") {
      object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
        object Msg

        def mount(props: Unit)        = ZIO.succeed(0)
        def handleMessage(model: Int) = { case Msg => ZIO.succeed(model + 1) }
        def render(model: Int, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), model.toString)

      enum ParentMsg:
        case Toggle

      val lv = new LiveView[ParentMsg, Boolean]:
        def mount                         = ZIO.succeed(true)
        def handleMessage(model: Boolean) = { case ParentMsg.Toggle => ZIO.succeed(!model) }
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Toggle), "toggle"),
            if model then liveComponent(CounterComponent, id = "counter", props = ()) else "gone"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      val incrementEvent: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:1:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )
      val toggleEvent: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )
      val destroyedEvent: Payload.Event = Payload.Event(
        `type` = "click",
        event = "cids_destroyed",
        value = Json.Obj("cids" -> Json.Arr(Json.Num(1)))
      )

      for
        socket       <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        repliesFiber <- socket.outbox.drop(1).take(4).runCollect.fork
        _            <- socket.inbox.offer(incrementEvent -> meta)
        _            <- socket.inbox.offer(toggleEvent -> meta)
        _            <- socket.inbox.offer(destroyedEvent -> meta)
        _            <- socket.inbox.offer(toggleEvent -> meta)
        replies      <- repliesFiber.join
      yield
        val componentRemountedFresh = replies.last._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.get(2).exists {
              case Diff.Tag(_, dynamic, _, _, _, _, _, _) =>
                dynamic.exists {
                  case Diff.Dynamic(_, Diff.Value("0")) => true
                  case _                                => false
                }
              case _ => false
            }
          case _ => false

        assertTrue(componentRemountedFresh)
    },
    test("removed live component keeps state until client confirms cids_destroyed") {
      object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
        object Msg

        def mount(props: Unit)        = ZIO.succeed(0)
        def handleMessage(model: Int) = { case Msg => ZIO.succeed(model + 1) }
        def render(model: Int, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), model.toString)

      enum ParentMsg:
        case Toggle

      val lv = new LiveView[ParentMsg, Boolean]:
        def mount                         = ZIO.succeed(true)
        def handleMessage(model: Boolean) = { case ParentMsg.Toggle => ZIO.succeed(!model) }
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Toggle), "toggle"),
            if model then liveComponent(CounterComponent, id = "counter", props = ()) else "gone"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      val incrementEvent: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:1:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )
      val toggleEvent: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      for
        socket       <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        repliesFiber <- socket.outbox.drop(1).take(3).runCollect.fork
        _            <- socket.inbox.offer(incrementEvent -> meta)
        _            <- socket.inbox.offer(toggleEvent -> meta)
        _            <- socket.inbox.offer(toggleEvent -> meta)
        replies      <- repliesFiber.join
      yield
        val componentPreserved = replies.last._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.get(1).exists {
              case Diff.Tag(_, dynamic, _, _, _, _, _, _) =>
                dynamic.exists {
                  case Diff.Dynamic(_, Diff.Value("1")) => true
                  case _                                => false
                }
              case _ => false
            }
          case _ => false

        assertTrue(componentPreserved)
    },
    test("component events can push patch") {
      object NavComponent extends LiveComponent[Unit, NavComponent.Msg.type, Unit]:
        object Msg

        def mount(props: Unit)         = ZIO.unit
        def handleMessage(model: Unit) = { case Msg => LiveContext.pushPatch("/next").as(model) }
        def render(model: Unit, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), "patch")

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(NavComponent, id = "nav", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket        <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        messagesFiber <- socket.outbox.drop(1).take(2).runCollect.fork
        _             <- socket.inbox.offer(event -> meta)
        messages      <- messagesFiber.join
      yield
        val navigated = messages.exists {
          case (Payload.LiveNavigation("/next", LivePatchKind.Push), _) => true
          case _                                                        => false
        }

        assertTrue(navigated)
    },
    test("component events can replace patch") {
      object NavComponent extends LiveComponent[Unit, NavComponent.Msg.type, Unit]:
        object Msg

        def mount(props: Unit)         = ZIO.unit
        def handleMessage(model: Unit) = { case Msg => LiveContext.replacePatch("/next").as(model) }
        def render(model: Unit, self: ComponentRef[Msg.type]) =
          button(phx.onClick(Msg), phx.target(self), "patch")

      val lv = new LiveView[Unit, Unit]:
        def mount                                  = ZIO.unit
        def handleMessage(model: Unit)             = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(NavComponent, id = "nav", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
        value = Json.Obj.empty,
        cid = Some(1)
      )

      for
        socket        <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        messagesFiber <- socket.outbox.drop(1).take(2).runCollect.fork
        _             <- socket.inbox.offer(event -> meta)
        messages      <- messagesFiber.join
      yield
        val navigated = messages.exists {
          case (Payload.LiveNavigation("/next", LivePatchKind.Replace), _) => true
          case _                                                           => false
        }

        assertTrue(navigated)
    }
  )
end SocketSpec
