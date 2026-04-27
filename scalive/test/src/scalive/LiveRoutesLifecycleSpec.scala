package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.*
import zio.http.codec.HttpCodec
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

object LiveRoutesLifecycleSpec extends ZIOSpecDefault:

  private val identityLayout: HtmlElement[?] => HtmlElement[?] = element => element
  private val rootTopic                                             = "lv:root"
  private val childTopic                                            = "lv:root-child"
  private val secondChildTopic                                      = "lv:root-second"
  private val grandchildTopic                                       = "lv:root-child-grandchild"
  private val rootMeta = WebSocketMessage.Meta(Some(1), Some(1), rootTopic, "phx_join")

  private enum ChildMsg:
    case Increment
    case PushPatch(to: String)
    case ReplacePatch(to: String)
    case PushNavigate(to: String)
    case ReplaceNavigate(to: String)
    case Redirect(to: String)

  private enum ParentMsg:
    case Rerender
    case HideChild
    case ShowChild
    case AddSecondChild

  private def childLiveView =
    new LiveView[ChildMsg, Int]:
      def mount = ZIO.succeed(0)
      def handleMessage(model: Int) = {
        case ChildMsg.Increment => ZIO.succeed(model + 1)
        case _                  => ZIO.succeed(model)
      }
      def render(model: Int): HtmlElement[ChildMsg] =
        button(phx.onClick(ChildMsg.Increment), model.toString)
      def subscriptions(model: Int) = ZStream.empty

  private def nestedChildLiveView =
    new LiveView[ChildMsg, Int]:
      def mount = ZIO.succeed(0)
      def handleMessage(model: Int) = {
        case ChildMsg.Increment => ZIO.succeed(model + 1)
        case _                  => ZIO.succeed(model)
      }
      def render(model: Int): HtmlElement[ChildMsg] =
        div(
          button(phx.onClick(ChildMsg.Increment), s"child $model"),
          liveView("grandchild", childLiveView)
        )
      def subscriptions(model: Int) = ZStream.empty

  private def navigatingChildLiveView(command: ChildMsg) =
    new LiveView[ChildMsg, Int]:
      def mount = ZIO.succeed(0)
      def handleMessage(model: Int) = {
        case ChildMsg.Increment           => ZIO.succeed(model + 1)
        case ChildMsg.PushPatch(to)       => LiveContext.pushPatch(to).as(model + 1)
        case ChildMsg.ReplacePatch(to)    => LiveContext.replacePatch(to).as(model + 1)
        case ChildMsg.PushNavigate(to)    => LiveContext.pushNavigate(to).as(model + 1)
        case ChildMsg.ReplaceNavigate(to) => LiveContext.replaceNavigate(to).as(model + 1)
        case ChildMsg.Redirect(to)        => LiveContext.redirect(to).as(model + 1)
      }
      def render(model: Int): HtmlElement[ChildMsg] =
        button(phx.onClick(command), model.toString)
      def subscriptions(model: Int) = ZStream.empty

  private def redirectAwareParent(child: LiveView[ChildMsg, Int]) =
    new LiveView[Unit, String]:
      override val queryCodec: LiveQueryCodec[Option[String]] =
        LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("redirect").optional)

      def mount = ZIO.succeed("none")

      override def handleParams(model: String, redirect: Option[String], _url: URL) =
        ZIO.succeed(redirect.getOrElse("none"))

      def handleMessage(model: String) = _ => ZIO.succeed(model)

      def render(model: String): HtmlElement[Unit] =
        div(
          p(s"Redirect: $model"),
          liveView("child", child)
        )

      def subscriptions(model: String) = ZStream.empty

  private def containsValue(diff: Diff, value: String): Boolean =
    diff match
      case Diff.Tag(_, dynamic, _, _, _, components, _, _) =>
        dynamic.exists(d => containsValue(d.diff, value)) || components.values.exists(
          containsValue(_, value)
        )
      case Diff.Value(current)  => current == value
      case Diff.Dynamic(_, diff) => containsValue(diff, value)
      case _                    => false

  private def runRequest(routes: Routes[Any, Nothing], path: String) =
    URL.decode(path) match
      case Left(error) => ZIO.die(error)
      case Right(url)  => ZIO.scoped(routes.runZIO(Request.get(url)))

  private def url(path: String): UIO[URL] =
    ZIO.fromEither(URL.decode(path)).orDie

  private def click(path: Vector[String], attrIndex: Int = 0): Payload.Event =
    Payload.Event(
      `type` = "click",
      event = BindingId.attrBindingId(path, attrIndex),
      value = Json.Obj.empty
    )

  private def joinRoot[Msg: ClassTag, Model](
    channel: LiveChannel,
    liveView: LiveView[Msg, Model],
    initialUrl: URL = URL.root
  ): RIO[Scope, Unit] =
    val ctx = LiveContext(
      staticChanged = false,
      nestedLiveViews = channel.nestedRuntime(rootTopic)
    )
    channel.join(rootTopic, "root-token", liveView, ctx, rootMeta, initialUrl)

  private def joinNested(
    channel: LiveChannel,
    topic: String,
    meta: Meta,
    initialUrl: URL = URL.root
  ): RIO[Scope, Socket[?, ?]] =
    for
      entryOpt <- channel.nestedEntry(topic)
      entry    <- ZIO.fromOption(entryOpt).orElseFail(new NoSuchElementException(topic))
      _      <- channel.joinNested(topic, entry.token, false, meta, initialUrl)
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
      .repeatUntil { case (payload, meta) => predicate(payload, meta) }
      .timeoutFail(new RuntimeException("Timed out waiting for matching socket payload"))(3.seconds)

  override def spec = suite("LiveRoutesLifecycleSpec")(
    test("runs mount then handleParams before disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        lv = new LiveView[Unit, Unit]:

               override val queryCodec: LiveQueryCodec[Option[String]] =
                 LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

               def mount = callsRef.update(_ :+ "mount").as(())

                 override def handleParams(model: Unit, params: Option[String], _url: URL) =
                  callsRef.update(_ :+ s"params:${params.getOrElse("")}").as(model)

               def handleMessage(model: Unit) = _ => ZIO.succeed(model)

                def render(model: Unit): HtmlElement[Unit] =
                 div("ok")

               def subscriptions(model: Unit) = ZStream.empty
        routes =
          LiveRoutes(layout = identityLayout)(
            Method.GET / Root -> liveHandler(lv)
          )
        response <- runRequest(routes, "/?q=1")
        calls    <- callsRef.get
      yield assertTrue(response.status == Status.Ok, calls == List("mount", "params:1"))
    },
    test("honors initial pushPatch with HTTP redirect") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit

        override def handleParams(model: Unit, _query: queryCodec.Out, _url: URL) =
          LiveContext.pushPatch("/target").as(model)

        def handleMessage(model: Unit) = _ => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div("ok")

        def subscriptions(model: Unit) = ZStream.empty

      val routes =
        LiveRoutes(layout = identityLayout)(
          Method.GET / Root -> liveHandler(lv)
        )

      for response <- runRequest(routes, "/")
      yield assertTrue(
        response.status.isRedirection,
        response.rawHeader("location").contains("/target")
      )
    },
    test("renders nested LiveView content during disconnected render") {
      for
        callsRef <- Ref.make(List.empty[String])
        child = new LiveView[Unit, String]:
                  override val queryCodec: LiveQueryCodec[Option[String]] =
                    LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("q").optional)

                  def mount = callsRef.update(_ :+ "child mount").as("mount")

                  override def handleParams(model: String, params: Option[String], _url: URL) =
                    callsRef.update(_ :+ s"child params:${params.getOrElse("")}")
                      .as(s"$model:${params.getOrElse("")}")

                  def handleMessage(model: String) = _ => ZIO.succeed(model)

                  def render(model: String): HtmlElement[Unit] =
                    div(idAttr := "child-content", s"child $model")

                  def subscriptions(model: String) = ZStream.empty
        parent = new LiveView[Unit, Unit]:
                   def mount = ZIO.unit
                   def handleMessage(model: Unit) = _ => ZIO.succeed(model)
                   def render(model: Unit): HtmlElement[Unit] = div(liveView("child", child))
                   def subscriptions(model: Unit) = ZStream.empty
        routes =
          LiveRoutes(layout = identityLayout)(
            Method.GET / Root -> liveHandler(parent)
          )
        response <- runRequest(routes, "/?q=1")
        body     <- response.body.asString
        calls    <- callsRef.get
      yield assertTrue(
        response.status == Status.Ok,
        calls == List("child mount", "child params:1"),
        body.contains("child mount:1"),
        body.contains("data-phx-child-id=\"child\""),
        body.contains("data-phx-parent-id=\"lv:phx-"),
        body.contains("data-phx-session=")
      )
    },
    test("sticky nested LiveView renders sticky marker during disconnected render") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView, sticky = true))
        def subscriptions(model: Unit) = ZStream.empty

      val routes =
        LiveRoutes(layout = identityLayout)(
          Method.GET / Root -> liveHandler(parent)
        )

      for
        response <- runRequest(routes, "/")
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        body.contains("data-phx-child-id=\"child\""),
        body.contains("data-phx-sticky")
      )
    },
    test("connected parent render registers nested LiveView for join") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _     <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry <- channel.nestedEntry(childTopic)
      yield assertTrue(entry.exists(_.parentTopic == rootTopic)))
    },
    test("dynamically added nested LiveViews can join descendants and handle events") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount = ZIO.succeed(false)
        def handleMessage(model: Boolean) = {
          case ParentMsg.ShowChild => ZIO.succeed(true)
          case ParentMsg.HideChild => ZIO.succeed(false)
          case _                   => ZIO.succeed(model)
        }
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.ShowChild), "show"),
            if model then liveView("child", nestedChildLiveView) else "hidden"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      val showChild = click(Vector("root:div", "tag:0:button"))
      val grandchildMeta = rootMeta.copy(topic = grandchildTopic)

      ZIO.scoped(for
        channel      <- LiveChannel.make(TokenConfig.default)
        _            <- joinRoot(channel, parent)
        rootSocket   <- channel.socket(rootTopic).some
        rootOutbox   <- subscribe(rootSocket)
        before       <- channel.nestedEntry(childTopic)
        _            <- channel.event(rootTopic, showChild, rootMeta.copy(eventType = "event"))
        _            <- takeMatching(rootOutbox) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(_)), meta) =>
                            meta.topic == rootTopic
                          case _ => false
                        }
        childEntry   <- channel.nestedEntry(childTopic).some
        _            <- joinNested(channel, childTopic, rootMeta.copy(topic = childTopic))
        grandEntry   <- channel.nestedEntry(grandchildTopic).some
        grandSocket  <- joinNested(channel, grandchildTopic, grandchildMeta)
        grandOutbox  <- subscribe(grandSocket)
        _            <- channel.event(grandchildTopic, click(Vector("root:button")), grandchildMeta.copy(eventType = "event"))
        grandUpdated <- takeMatching(grandOutbox) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), meta) =>
                            meta.topic == grandchildTopic && containsValue(diff, "1")
                          case _ => false
                        }
      yield assertTrue(
        before.isEmpty,
        childEntry.parentTopic == rootTopic,
        grandEntry.parentTopic == childTopic,
        grandUpdated._2.topic == grandchildTopic
      ))
    },
    test("removed nested children and descendants are cleaned up on client leave") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount = ZIO.succeed(true)
        def handleMessage(model: Boolean) = {
          case ParentMsg.HideChild => ZIO.succeed(false)
          case ParentMsg.ShowChild => ZIO.succeed(true)
          case _                   => ZIO.succeed(model)
        }
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.HideChild), "hide"),
            if model then liveView("child", nestedChildLiveView) else "hidden"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      ZIO.scoped(for
        channel          <- LiveChannel.make(TokenConfig.default)
        _                <- joinRoot(channel, parent)
        rootSocket       <- channel.socket(rootTopic).some
        rootOutbox       <- subscribe(rootSocket)
        _                <- joinNested(channel, childTopic, rootMeta.copy(topic = childTopic))
        _                <- joinNested(channel, grandchildTopic, rootMeta.copy(topic = grandchildTopic))
        childBefore      <- channel.socket(childTopic)
        grandBefore      <- channel.socket(grandchildTopic)
        _                <- channel.event(rootTopic, click(Vector("root:div", "tag:0:button")), rootMeta.copy(eventType = "event"))
        _                <- takeMatching(rootOutbox) {
                              case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(_)), meta) =>
                                meta.topic == rootTopic
                              case _ => false
                            }
        childStillListed <- channel.nestedEntry(childTopic)
        _                <- channel.leave(childTopic)
        childAfter       <- channel.socket(childTopic)
        grandAfter       <- channel.socket(grandchildTopic)
        childEntryAfter  <- channel.nestedEntry(childTopic)
        grandEntryAfter  <- channel.nestedEntry(grandchildTopic)
      yield assertTrue(
        childBefore.nonEmpty,
        grandBefore.nonEmpty,
        childStillListed.nonEmpty,
        childAfter.isEmpty,
        grandAfter.isEmpty,
        childEntryAfter.isEmpty,
        grandEntryAfter.isEmpty
      ))
    },
    test("multiple nested children of the same LiveView type keep distinct topics") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            liveView("child", childLiveView),
            liveView("second", childLiveView)
          )
        def subscriptions(model: Unit) = ZStream.empty

      val childMeta  = rootMeta.copy(topic = childTopic)
      val secondMeta = rootMeta.copy(topic = secondChildTopic)

      ZIO.scoped(for
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent)
        childEntry  <- channel.nestedEntry(childTopic).some
        secondEntry <- channel.nestedEntry(secondChildTopic).some
        childSocket <- joinNested(channel, childTopic, childMeta)
        secondSocket <- joinNested(
                          channel,
                          secondChildTopic,
                          secondMeta
                        )
        childOutbox  <- subscribe(childSocket)
        secondOutbox <- subscribe(secondSocket)
        _            <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        _            <- channel.event(secondChildTopic, click(Vector("root:button")), secondMeta.copy(eventType = "event"))
        childUpdated <- takeMatching(childOutbox) {
                          case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), meta) =>
                            meta.topic == childTopic && containsValue(diff, "1")
                          case _ => false
                        }
        secondUpdated <- takeMatching(secondOutbox) {
                           case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)), meta) =>
                             meta.topic == secondChildTopic && containsValue(diff, "1")
                           case _ => false
                         }
      yield assertTrue(
        childEntry.parentTopic == rootTopic,
        secondEntry.parentTopic == rootTopic,
        childTopic != secondChildTopic,
        childUpdated._2.topic == childTopic,
        secondUpdated._2.topic == secondChildTopic
      ))
    },
    test("multiple nested children can be added later with fresh constructor data") {
      def cityLiveView(name: String) =
        new LiveView[ChildMsg, String]:
          def mount = ZIO.succeed(name)
          def handleMessage(model: String) = _ => ZIO.succeed(model)
          def render(model: String): HtmlElement[ChildMsg] = div(model)
          def subscriptions(model: String) = ZStream.empty

      val parent = new LiveView[ParentMsg, Boolean]:
        def mount = ZIO.succeed(false)
        def handleMessage(model: Boolean) = {
          case ParentMsg.AddSecondChild => ZIO.succeed(true)
          case _                        => ZIO.succeed(model)
        }
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.AddSecondChild), "add"),
            liveView("Tokyo", cityLiveView("Tokyo")),
            liveView("Madrid", cityLiveView("Madrid")),
            if model then liveView("Toronto", cityLiveView("Toronto")) else "pending"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      ZIO.scoped(for
        channel       <- LiveChannel.make(TokenConfig.default)
        _             <- joinRoot(channel, parent)
        rootSocket    <- channel.socket(rootTopic).some
        rootOutbox    <- subscribe(rootSocket)
        tokyo         <- channel.nestedEntry("lv:root-Tokyo")
        madrid        <- channel.nestedEntry("lv:root-Madrid")
        torontoBefore <- channel.nestedEntry("lv:root-Toronto")
        _             <- channel.event(rootTopic, click(Vector("root:div", "tag:0:button")), rootMeta.copy(eventType = "event"))
        _             <- takeMatching(rootOutbox) {
                           case (Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(_)), meta) =>
                             meta.topic == rootTopic
                           case _ => false
                         }
        torontoAfter  <- channel.nestedEntry("lv:root-Toronto")
      yield assertTrue(
        tokyo.exists(_.parentTopic == rootTopic),
        madrid.exists(_.parentTopic == rootTopic),
        torontoBefore.isEmpty,
        torontoAfter.exists(_.parentTopic == rootTopic)
      ))
    },
    test("nested LiveViews can render within comprehensions") {
      val users = Vector("chris", "jose")
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(users.splitBy(identity)((id, _) => div(liveView(id, childLiveView))))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        _       <- joinRoot(channel, parent)
        chris   <- channel.nestedEntry("lv:root-chris")
        jose    <- channel.nestedEntry("lv:root-jose")
      yield assertTrue(
        chris.exists(_.parentTopic == rootTopic),
        jose.exists(_.parentTopic == rootTopic),
        chris.nonEmpty && jose.nonEmpty
      ))
    },
    test("duplicate nested LiveView ids fail the parent render") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(
            liveView("child", childLiveView),
            liveView("child", childLiveView)
          )
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        exit    <- joinRoot(channel, parent).exit
      yield
        val failedWithDuplicate = exit match
          case Exit.Failure(cause) => cause.prettyPrint.contains("Duplicate nested LiveView id 'child'")
          case Exit.Success(_)     => false

        assertTrue(failedWithDuplicate))
    },
    test("nested LiveView joins and handles isolated events") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView))
        def subscriptions(model: Unit) = ZStream.empty

      val childMeta = rootMeta.copy(topic = childTopic)
      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry  <- channel.nestedEntry(childTopic).some
        joined      <- channel.joinNested(childTopic, entry.token, false, childMeta, URL.root)
        childSocket <- channel.socket(childTopic).some
        outQueue    <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        _           <- childSocket.outbox.runForeach(outQueue.offer).forkScoped
        _           <- outQueue.take
        _           <- ZIO.yieldNow.repeatN(5)
        _           <- channel.event(childTopic, event, childMeta.copy(eventType = "event"))
        reply       <- outQueue.take
      yield
        val childUpdated = reply match
          case (
                Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)),
                WebSocketMessage.Meta(_, _, `childTopic`, _)
              ) =>
            containsValue(diff, "1")
          case _ => false

        assertTrue(joined.isEmpty, childUpdated))
    },
    test("LiveComponents can render nested LiveViews") {
      object HostComponent extends LiveComponent[Unit, Unit, Unit]:
        def mount(props: Unit)                    = ZIO.unit
        def handleMessage(model: Unit)            = _ => ZIO.succeed(model)
        def render(model: Unit, self: ComponentRef[Unit]) =
          div(liveView("child", childLiveView))

      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(HostComponent, id = "host", props = ()))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _     <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry <- channel.nestedEntry(childTopic)
      yield assertTrue(entry.exists(_.parentTopic == rootTopic)))
    },
    test("parent leave removes child sockets and nested registry entries") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _          <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry      <- channel.nestedEntry(childTopic).some
        _          <- channel.joinNested(childTopic, entry.token, false, rootMeta.copy(topic = childTopic), URL.root)
        childBefore <- channel.socket(childTopic)
        _          <- channel.leave(rootTopic)
        parentAfter <- channel.socket(rootTopic)
        childAfter <- channel.socket(childTopic)
        entryAfter <- channel.nestedEntry(childTopic)
      yield assertTrue(childBefore.nonEmpty, parentAfter.isEmpty, childAfter.isEmpty, entryAfter.isEmpty))
    },
    test("parent leave preserves sticky child socket and registry entry") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView, sticky = true))
        def subscriptions(model: Unit) = ZStream.empty

      val childMeta = rootMeta.copy(topic = childTopic)
      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:button"), 0),
        value = Json.Obj.empty
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
        outQueue    <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
        _           <- childSocket.outbox.runForeach(outQueue.offer).forkScoped
        _           <- outQueue.take
        _           <- channel.leave(rootTopic)
        parentAfter <- channel.socket(rootTopic)
        childAfter  <- channel.socket(childTopic)
        entryAfter  <- channel.nestedEntry(childTopic)
        _           <- channel.event(childTopic, event, childMeta.copy(eventType = "event"))
        reply       <- outQueue.take
      yield
        val childUpdated = reply match
          case (
                Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)),
                WebSocketMessage.Meta(_, _, `childTopic`, _)
              ) =>
            containsValue(diff, "1")
          case _ => false

        assertTrue(parentAfter.isEmpty, childAfter.nonEmpty, entryAfter.nonEmpty, childUpdated))
    },
    test("child leave removes only child socket and nested registry entry") {
      val parent = new LiveView[Unit, Unit]:
        def mount                      = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveView("child", childLiveView))
        def subscriptions(model: Unit) = ZStream.empty

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _       <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        entry   <- channel.nestedEntry(childTopic).some
        _       <- channel.joinNested(childTopic, entry.token, false, rootMeta.copy(topic = childTopic), URL.root)
        _       <- channel.leave(childTopic)
        parent  <- channel.socket(rootTopic)
        child   <- channel.socket(childTopic)
        removed <- channel.nestedEntry(childTopic)
      yield assertTrue(parent.nonEmpty, child.isEmpty, removed.isEmpty))
    },
    test("parent rerender preserves stable nested LiveView topic") {
      val parent = new LiveView[ParentMsg, Int]:
        def mount = ZIO.succeed(0)
        def handleMessage(model: Int) =
          case ParentMsg.Rerender => ZIO.succeed(model + 1)
          case ParentMsg.HideChild => ZIO.succeed(model)
          case _                   => ZIO.succeed(model)
        def render(model: Int): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Rerender), model.toString),
            liveView("child", childLiveView)
          )
        def subscriptions(model: Int) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        before <- channel.nestedEntry(childTopic).some
        _      <- channel.event(rootTopic, event, rootMeta.copy(eventType = "event"))
        after  <- channel.nestedEntry(childTopic).some
      yield assertTrue(before.parentTopic == rootTopic, after.parentTopic == rootTopic))
    },
    test("removed nested LiveView registry remains until client cleanup") {
      val parent = new LiveView[ParentMsg, Boolean]:
        def mount = ZIO.succeed(true)
        def handleMessage(model: Boolean) =
          case ParentMsg.HideChild => ZIO.succeed(false)
          case ParentMsg.Rerender  => ZIO.succeed(model)
          case _                   => ZIO.succeed(model)
        def render(model: Boolean): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.HideChild), "hide"),
            if model then liveView("child", childLiveView) else "hidden"
          )
        def subscriptions(model: Boolean) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        before <- channel.nestedEntry(childTopic)
        _      <- channel.event(rootTopic, event, rootMeta.copy(eventType = "event"))
        after  <- channel.nestedEntry(childTopic)
      yield assertTrue(before.nonEmpty, after.nonEmpty))
    },
    test("nested child pushNavigate emits a live redirect") {
      val parent = redirectAwareParent(
        navigatingChildLiveView(ChildMsg.PushNavigate("/thermo?redirect=push"))
      )
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        navigation  <- takeMatching(outQueue) {
                         case (Payload.LiveRedirect(to, LivePatchKind.Push, _), meta) =>
                           meta.topic == childTopic && to == "/thermo?redirect=push"
                         case _ => false
                       }
      yield assertTrue(navigation._2.topic == childTopic))
    },
    test("nested child pushNavigate supports variable destinations") {
      val parent = redirectAwareParent(
        navigatingChildLiveView(ChildMsg.PushNavigate("/thermo?redirect=1234"))
      )
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        navigation  <- takeMatching(outQueue) {
                         case (Payload.LiveRedirect(to, LivePatchKind.Push, _), _) =>
                           to.matches("/thermo\\?redirect=[0-9]+")
                         case _ => false
                       }
      yield assertTrue(navigation._2.topic == childTopic))
    },
    test("nested child pushPatch emits navigation and parent live_patch updates params") {
      val parent = redirectAwareParent(
        navigatingChildLiveView(ChildMsg.PushPatch("/thermo?redirect=patch"))
      )
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        navigation  <- takeMatching(outQueue) {
                         case (Payload.LiveNavigation("/thermo?redirect=patch", LivePatchKind.Push), meta) =>
                           meta.topic == childTopic
                         case _ => false
                       }
        reply <- channel.livePatch(
                   rootTopic,
                   "/thermo?redirect=patch",
                   rootMeta.copy(eventType = "live_patch")
                 )
      yield
        val parentUpdated = reply match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) =>
            containsValue(diff, "Redirect: patch")
          case _ => false

        assertTrue(navigation._2.topic == childTopic, parentUpdated))
    },
    test("nested child pushPatch supports variable destinations") {
      val parent = redirectAwareParent(
        navigatingChildLiveView(ChildMsg.ReplacePatch("/thermo?redirect=9876"))
      )
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        navigation  <- takeMatching(outQueue) {
                         case (Payload.LiveNavigation(to, LivePatchKind.Replace), _) =>
                           to.matches("/thermo\\?redirect=[0-9]+")
                         case _ => false
                       }
        reply <- channel.livePatch(
                   rootTopic,
                   "/thermo?redirect=9876",
                   rootMeta.copy(eventType = "live_patch")
                 )
      yield
        val parentUpdated = reply match
          case Payload.Reply(ReplyStatus.Ok, LiveResponse.Diff(diff)) =>
            containsValue(diff, "Redirect: 9876")
          case _ => false

        assertTrue(navigation._1 == Payload.LiveNavigation("/thermo?redirect=9876", LivePatchKind.Replace), parentUpdated))
    },
    test("nested child redirect emits an internal redirect") {
      val parent = redirectAwareParent(
        navigatingChildLiveView(ChildMsg.Redirect("/thermo?redirect=redirect"))
      )
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        redirect    <- takeMatching(outQueue) {
                         case (Payload.Redirect("/thermo?redirect=redirect", _), meta) =>
                           meta.topic == childTopic
                         case _ => false
                       }
      yield assertTrue(redirect._2.topic == childTopic))
    },
    test("nested child redirect supports external destinations") {
      val parent = redirectAwareParent(
        navigatingChildLiveView(ChildMsg.Redirect("https://phoenixframework.org"))
      )
      val childMeta = rootMeta.copy(topic = childTopic)

      ZIO.scoped(for
        initialUrl  <- url("/thermo?redirect=none")
        channel     <- LiveChannel.make(TokenConfig.default)
        _           <- joinRoot(channel, parent, initialUrl)
        childSocket <- joinNested(channel, childTopic, childMeta, initialUrl)
        outQueue    <- subscribe(childSocket)
        _           <- channel.event(childTopic, click(Vector("root:button")), childMeta.copy(eventType = "event"))
        redirect    <- takeMatching(outQueue) {
                         case (Payload.Redirect("https://phoenixframework.org", _), meta) =>
                           meta.topic == childTopic
                         case _ => false
                       }
      yield assertTrue(redirect._2.topic == childTopic))
    },
    test("parent patch does not reset stable nested child registration") {
      val parent = new LiveView[ParentMsg, Int]:
        def mount = ZIO.succeed(0)
        def handleMessage(model: Int) =
          case ParentMsg.Rerender =>
            LiveContext.pushPatch("/parent-next").as(model + 1)
          case ParentMsg.HideChild => ZIO.succeed(model)
          case _                   => ZIO.succeed(model)
        def render(model: Int): HtmlElement[ParentMsg] =
          div(
            button(phx.onClick(ParentMsg.Rerender), model.toString),
            liveView("child", childLiveView)
          )
        def subscriptions(model: Int) = ZStream.empty

      val event: Payload.Event = Payload.Event(
        `type` = "click",
        event = BindingId.attrBindingId(Vector("root:div", "tag:0:button"), 0),
        value = Json.Obj.empty
      )

      ZIO.scoped(for
        channel <- LiveChannel.make(TokenConfig.default)
        ctx = LiveContext(
                staticChanged = false,
                nestedLiveViews = channel.nestedRuntime(rootTopic)
              )
        _      <- channel.join(rootTopic, "root-token", parent, ctx, rootMeta, URL.root)
        before <- channel.nestedEntry(childTopic).some
        _      <- channel.event(rootTopic, event, rootMeta.copy(eventType = "event"))
        after  <- channel.nestedEntry(childTopic).some
      yield assertTrue(before.parentTopic == rootTopic, after.parentTopic == rootTopic))
    }
  )
end LiveRoutesLifecycleSpec
