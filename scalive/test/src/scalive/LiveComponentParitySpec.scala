package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object LiveComponentParitySpec extends ZIOSpecDefault:

  private val meta = Meta(None, None, "lv:root", "event")
  private val childTopic = "lv:child"
  private val phxChangeAttr = htmlAttr("phx-change", scalive.codecs.StringAsIsEncoder)

  private enum ParentMsg:
    case Toggle
    case SendMissingUpdate

  private enum NavMsg:
    case PushNavigate
    case PushPatch
    case Redirect

  private object LabelComponent extends LiveComponent[String, Unit, String]:
    def mount(props: String, ctx: MountContext) =
      ZIO.succeed(props)
    override def update(props: String, model: String, ctx: UpdateContext) =
      ZIO.succeed(props)
    def handleMessage(props: String, model: String, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)
    def render(props: String, model: String, self: ComponentRef[Unit]) =
      div(idAttr := model, s"$model says hi")

  private object NavComponent extends LiveComponent[Unit, NavMsg, Unit]:
    def mount(props: Unit, ctx: MountContext) =
      ZIO.unit
    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
          case NavMsg.PushNavigate => ctx.nav.pushNavigate("/components?redirect=push").as(model)
          case NavMsg.PushPatch    => ctx.nav.pushPatch("/components?redirect=patch").as(model)
          case NavMsg.Redirect     => ctx.nav.redirect("/components?redirect=redirect").as(model)
    def render(props: Unit, model: Unit, self: ComponentRef[NavMsg]) =
      div(
        button(phx.onClick(NavMsg.PushNavigate), phx.target(self), "push navigate"),
        button(phx.onClick(NavMsg.PushPatch), phx.target(self), "push patch"),
        button(phx.onClick(NavMsg.Redirect), phx.target(self), "redirect")
      )

  private object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Int]:
    object Msg

    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed(0)
    def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
      (_: Msg.type) => ZIO.succeed(model + 1)
    def render(props: Unit, model: Int, self: ComponentRef[Msg.type]) =
      button(phx.onClick(Msg), phx.target(self), model.toString)

  private object RawTargetComponent extends LiveComponent[Unit, Unit, String]:
    override def hooks: ComponentLiveHooks[Unit, Unit, String] =
      ComponentLiveHooks.empty.rawEvent("raw-target") { (_, model, event, _) =>
        if event.bindingId == "validate" then ZIO.succeed(LiveEventHookResult.halt("handled"))
        else ZIO.succeed(LiveEventHookResult.cont(model))
      }

    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed("idle")

    def handleMessage(props: Unit, model: String, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model)

    def render(props: Unit, model: String, self: ComponentRef[Unit]) =
      form(phxChangeAttr := "validate", phx.target(self), span(model))

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

  private def click(path: Vector[String], attrIndex: Int = 0, cid: Option[Int] = None)
    : Payload.Event =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = Json.Obj.empty,
      cid = cid
    )

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  private def start[Msg: ClassTag, Model](lv: LiveView[Msg, Model]) =
    Socket.start("lv:root", "token", lv, LiveContext(staticChanged = false), meta)

  private def nextAfterInit(socket: Socket[?, ?]) =
    socket.outbox.drop(1).runHead.some

  private def joinRoot[Msg: ClassTag, Model](channel: LiveChannel, lv: LiveView[Msg, Model]) =
    val ctx = LiveContext(
      staticChanged = false,
      nestedLiveViews = channel.nestedRuntime(meta.topic)
    )
    channel.join(meta.topic, "root-token", lv, ctx, meta, URL.root)

  private def joinNested(channel: LiveChannel, topic: String, topicMeta: Meta) =
    for
      entryOpt <- channel.nestedEntry(topic)
      entry    <- ZIO.fromOption(entryOpt).orElseFail(new NoSuchElementException(topic))
      _        <- channel.joinNested(topic, entry.token, false, topicMeta, URL.root)
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
      .repeatUntil { case (payload, payloadMeta) => predicate(payload, payloadMeta) }
      .timeoutFail(new RuntimeException("Timed out waiting for matching socket payload"))(3.seconds)

  override def spec = suite("LiveComponentParitySpec")(
    test("component refs stringify to their cid") {
      assertTrue(ComponentRef[Unit](123).toString == "123")
    },
    test("cid-targeted static events reach component raw hooks") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(RawTargetComponent, id = "raw", props = ()))

      val event = Payload.Event(
        `type` = "form",
        event = "validate",
        value = Json.Str("value=1"),
        cid = Some(1)
      )

      for
        socket     <- start(parent)
        replyFiber <- nextAfterInit(socket).fork
        _          <- socket.inbox.offer(event -> meta)
        reply      <- replyFiber.join
      yield assertTrue(
        reply._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) =>
            containsValue(diff, "handled")
          case _ => false
      )
    },
    test("renders live components during disconnected HTTP render") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(LabelComponent, id = "chris", props = "chris"))

      val routes = scalive.Live.router(
        scalive.live(parent)
      )

      for
        response <- runRequest(routes, "/")
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("data-phx-component=\"1\""),
        body.contains("chris says hi")
      )
    },
    test("renders connected live components with stable sequential cids") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            liveComponent(LabelComponent, id = "chris", props = "chris"),
            liveComponent(LabelComponent, id = "jose", props = "jose")
          )

      for
        socket <- start(parent)
        init   <- socket.outbox.take(1).runHead.some
      yield
        val rendered = init._1 match
          case Payload.Reply(
                ReplyStatus.Ok,
                LiveResponse.InitDiff(Diff.Tag(_, _, _, _, _, components, _, _))
              ) =>
            components.keySet == Set(1, 2) &&
              components.get(1).exists(containsValue(_, "chris says hi")) &&
              components.get(2).exists(containsValue(_, "jose says hi"))
          case _ => false

        assertTrue(rendered)
    },
    test("tracks component additions and prop updates") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount(ctx: MountContext) =
          ZIO.succeed(false)
        def handleMessage(model: Boolean, ctx: MessageContext) =
              case ParentMsg.Toggle            => ZIO.succeed(true)
              case ParentMsg.SendMissingUpdate => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Toggle), "toggle"),
            liveComponent(LabelComponent, id = "chris", props = if model then "DISABLED" else "chris"),
            liveComponent(LabelComponent, id = "jose", props = if model then "DISABLED" else "jose"),
            if model then
              Seq(
                liveComponent(LabelComponent, id = "chris-new", props = "chris-new"),
                liveComponent(LabelComponent, id = "jose-new", props = "jose-new")
              )
            else Seq.empty
          )

      for
        socket     <- start(parent)
        replyFiber <- nextAfterInit(socket).fork
        _          <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
        reply      <- replyFiber.join
      yield
        val tracked = reply._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(Diff.Tag(_, _, _, _, _, components, _, _))) =>
            components.keySet == Set(1, 2, 3, 4) &&
              components.get(1).exists(containsValue(_, "DISABLED says hi")) &&
              components.get(2).exists(containsValue(_, "DISABLED says hi")) &&
              components.get(3).exists(containsValue(_, "chris-new says hi")) &&
              components.get(4).exists(containsValue(_, "jose-new says hi"))
          case _ => false

        assertTrue(tracked)
    },
    test("whole-root component removal keeps the socket alive") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount(ctx: MountContext) =
          ZIO.succeed(true)
        def handleMessage(model: Boolean, ctx: MessageContext) =
              case ParentMsg.Toggle            => ZIO.succeed(false)
              case ParentMsg.SendMissingUpdate => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          if model then
            div(button(phx.onClick(ParentMsg.Toggle), "disable"), liveComponent(LabelComponent, id = "chris", props = "chris"))
          else div("Disabled")

      for
        socket     <- start(parent)
        replyFiber <- nextAfterInit(socket).fork
        _          <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
        reply      <- replyFiber.join
      yield
        val disabled = reply._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) => containsValue(diff, "Disabled")
          case _                                                       => false

        assertTrue(disabled)
    },
    test("tracks component removals from a nested LiveView") {
      val child = new LiveView[ParentMsg, Boolean]:
        def mount(ctx: MountContext) =
          ZIO.succeed(true)
        def handleMessage(model: Boolean, ctx: MessageContext) =
              case ParentMsg.Toggle            => ZIO.succeed(false)
              case ParentMsg.SendMissingUpdate => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Toggle), "disable"),
            if model then liveComponent(LabelComponent, id = "hello", props = "Hello World")
            else "disabled"
          )

      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div(liveView("child", child))

      val childMeta = meta.copy(topic = childTopic)

      ZIO.scoped(for
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent)
        childSocket <- joinNested(channel, childTopic, childMeta)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:div", "tag:0:button")), childMeta.copy(eventType = "event"))
        removed     <- takeMatching(outQueue) {
                         case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), payloadMeta) =>
                           payloadMeta.topic == childTopic && containsValue(diff, "disabled")
                         case _ => false
                       }
      yield assertTrue(removed._2.topic == childTopic))
    },
    test("tracks root component and nested LiveView removals in the same render") {
      val child = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] = div("world")

      val parent = new LiveView[ParentMsg, Boolean]:
        def mount(ctx: MountContext) =
          ZIO.succeed(true)
        def handleMessage(model: Boolean, ctx: MessageContext) =
              case ParentMsg.Toggle            => ZIO.succeed(false)
              case ParentMsg.SendMissingUpdate => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Toggle), "disable"),
            if model then
              Seq(
                liveComponent(LabelComponent, id = "hello", props = "hello"),
                liveView("child", child)
              )
            else Seq(rawHtml("disabled"))
          )

      ZIO.scoped(for
        channel      <- LiveChannel.make(TokenConfig.default)
        _            <- joinRoot(channel, parent)
        rootSocket   <- channel.socket(meta.topic).some
        rootOutQueue <- subscribe(rootSocket)
        before       <- channel.nestedEntry(childTopic)
        _            <- channel.event(meta.topic, click(Vector("root:div", "tag:0:button")), meta.copy(eventType = "event"))
        removed      <- takeMatching(rootOutQueue) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), payloadMeta) =>
                            payloadMeta.topic == meta.topic && containsValue(diff, "disabled")
                          case _ => false
                        }
        after <- channel.nestedEntry(childTopic)
      yield assertTrue(before.nonEmpty, after.nonEmpty, removed._2.topic == meta.topic))
    },
    test("sendUpdate to a missing component is ignored instead of applied on later mount") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount(ctx: MountContext) =
          ZIO.succeed(false)
        def handleMessage(model: Boolean, ctx: MessageContext) =
              case ParentMsg.SendMissingUpdate =>
                ctx.components.sendUpdate[LabelComponent.type]("missing", "stale").as(true)
              case ParentMsg.Toggle => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.SendMissingUpdate), "add"),
            if model then liveComponent(LabelComponent, id = "missing", props = "fresh") else "hidden"
          )

      for
        socket     <- start(parent)
        replyFiber <- nextAfterInit(socket).fork
        _          <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
        reply      <- replyFiber.join
      yield
        val fresh = reply._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) =>
            containsValue(diff, "fresh says hi") && !containsValue(diff, "stale says hi")
          case _ => false

        assertTrue(fresh)
    },
    test("sendUpdate assigns survive unchanged parent rerenders") {
      val parent = new LiveView[ParentMsg, Int]:
        def mount(ctx: MountContext) =
          ZIO.succeed(0)
        def handleMessage(model: Int, ctx: MessageContext) =
              case ParentMsg.SendMissingUpdate =>
                ctx.components.sendUpdate[LabelComponent.type]("stable", "sent").as(model)
              case ParentMsg.Toggle => ZIO.succeed(model + 1)
        def render(model: Int): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.SendMissingUpdate), "send update"),
            button(phx.onClick(ParentMsg.Toggle), "rerender"),
            p(model.toString),
            liveComponent(LabelComponent, id = "stable", props = "parent")
          )

      for
        socket <- start(parent)
        firstFiber <- nextAfterInit(socket).fork
        _ <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
        first <- firstFiber.join
        secondFiber <- nextAfterInit(socket).fork
        _ <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
        second <- secondFiber.join
      yield
        val sent = first._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) =>
            containsValue(diff, "sent says hi")
          case _ => false
        val notReset = second._1 match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) =>
            !containsValue(diff, "parent says hi")
          case _ => false

        assertTrue(sent, notReset)
    },
    test("component pushNavigate emits live redirect") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(NavComponent, id = "nav", props = ()))

      for
        socket <- start(parent)
        navigationFiber <- socket.outbox.drop(1).collect {
                             case (payload @ Payload.LiveRedirect(_, _, _), _) => payload
                           }.runHead.fork
        _ <- socket.inbox.offer(
               click(Vector("root:div", "component:0:1", "tag:0:button"), cid = Some(1)) -> meta
             )
        navigation <- navigationFiber.join.some
      yield assertTrue(
        navigation == Payload.LiveRedirect("/components?redirect=push", LivePatchKind.Push, None)
      )
    },
    test("component pushPatch emits navigation and updates parent params on live_patch") {
      val parent = new LiveView[Unit, String]:
        override val queryCodec: LiveQueryCodec[Option[String]] =
          LiveQueryCodec.fromZioHttp(zio.http.codec.HttpCodec.query[String]("redirect").optional)

        def mount(ctx: MountContext) =
          ZIO.succeed("none")
        override def handleParams(model: String, redirect: Option[String], _url: URL, ctx: ParamsContext) =
          ZIO.succeed(redirect.getOrElse("none"))
        def handleMessage(model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: String): HtmlElement[Unit] =
          div(p(s"Redirect: $model"), liveComponent(NavComponent, id = "nav", props = ()))

      for
        initialUrl <- ZIO.fromEither(URL.decode("/components?redirect=none")).orDie
        socket     <- Socket.start("lv:root", "token", parent, LiveContext(staticChanged = false), meta, initialUrl = initialUrl)
        navigationFiber <- socket.outbox.drop(1).collect {
                             case (payload @ Payload.LiveNavigation(_, _), _) => payload
                           }.runHead.fork
        _ <- socket.inbox.offer(
               click(Vector("root:div", "component:1:1", "tag:1:button"), cid = Some(1)) -> meta
             )
        navigation <- navigationFiber.join.some
        reply      <- socket.livePatch("/components?redirect=patch", meta.copy(eventType = "live_patch"))
      yield
        val patched = reply match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) => containsValue(diff, "Redirect: patch")
          case _                                                       => false

        assertTrue(navigation == Payload.LiveNavigation("/components?redirect=patch", LivePatchKind.Push), patched)
    },
    test("component redirect emits redirect payload") {
      val parent = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(NavComponent, id = "nav", props = ()))

      for
        socket <- start(parent)
        redirectFiber <- socket.outbox.drop(1).collect {
                           case (payload @ Payload.Redirect(_, _), _) => payload
                         }.runHead.fork
        _ <- socket.inbox.offer(
               click(Vector("root:div", "component:0:1", "tag:2:button"), cid = Some(1)) -> meta
             )
        redirect <- redirectFiber.join.some
      yield assertTrue(redirect == Payload.Redirect("/components?redirect=redirect", None))
    }
  )
end LiveComponentParitySpec
