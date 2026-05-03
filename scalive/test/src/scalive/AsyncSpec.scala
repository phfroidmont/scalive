package scalive

import zio.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.Payload

object AsyncSpec extends ZIOSpecDefault:
  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private object Tasks:
    val Load     = "load"
    val Patch    = "patch"
    val Navigate = "navigate"
    val Redirect = "redirect"
    val Flash    = "flash"

  private enum Msg:
    case Start
    case Cancel
    case Loaded(value: String)
    case PatchLoaded(value: String)
    case NavigateLoaded(value: Unit)
    case RedirectLoaded(value: Unit)
    case FlashLoaded(value: String)

  private def containsValue(diff: Diff, value: String): Boolean =
    diff match
      case Diff.Tag(_, dynamic, _, _, _, components, _, _) =>
        dynamic.exists(d => containsValue(d.diff, value)) || components.values.exists(
          containsValue(_, value)
        )
      case Diff.Value(current)  => current == value
      case Diff.Dynamic(_, diff) => containsValue(diff, value)
      case _                    => false

  private def diffFromPayload(payload: Payload): Option[Diff] =
    payload match
      case Payload.Reply(_, WebSocketMessage.LiveResponse.Diff(diff)) => Some(diff)
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

  private def awaitWithin[A](effect: UIO[A], duration: Duration): UIO[Option[A]] =
    zio.test.Live.live(effect.timeout(duration))

  private object UpdateAsyncComponent
      extends LiveComponent[UpdateAsyncComponent.Action, UpdateAsyncComponent.Msg, UpdateAsyncComponent.Model]:
    enum Action:
      case Ok
      case Navigate
      case Patch
      case Redirect
      case NavigateFlash

    enum Msg:
      case Done(value: String)

    final case class Model(
      action: Action,
      started: Boolean = false,
      result: String = "loading")

    private val TaskName = "component-update-task"

    def mount(props: Action, ctx: MountContext) =
      ZIO.succeed(Model(props))

    override def update(props: Action, model: Model, ctx: UpdateContext) =
      val next = model.copy(action = props)
      if next.started then ZIO.succeed(next)
      else
        ctx.async
          .start(TaskName)(effect(props))(Msg.Done(_))
          .as(next.copy(started = true, result = "loading"))

    def handleMessage(props: Action, model: Model, ctx: MessageContext) =
          case Msg.Done(value) =>
            model.action match
              case Action.Ok => ZIO.succeed(model.copy(result = value))
              case Action.Navigate =>
                ctx.nav.pushNavigate("/start_async?test=ok").as(model)
              case Action.Patch =>
                ctx.nav.pushPatch("/start_async?test=ok").as(model)
              case Action.Redirect =>
                ctx.nav.redirect("/not_found").as(model)
              case Action.NavigateFlash =>
                ctx.flash.put("info", value) *>
                  ctx.nav.pushNavigate("/start_async?test=ok").as(model)

    def render(props: Action, model: Model, self: ComponentRef[Msg]) =
      div(s"lc: ${model.result}")

    private def effect(action: Action): Task[String] =
      action match
        case Action.Ok            => ZIO.succeed("good")
        case Action.Navigate      => ZIO.succeed("navigate")
        case Action.Patch         => ZIO.succeed("patch")
        case Action.Redirect      => ZIO.succeed("redirect")
        case Action.NavigateFlash => ZIO.succeed("hello")

  override def spec = suite("AsyncSpec")(
    test("completed async task sends typed message and pushes diff") {
      val lv = new LiveView[Msg, String]:
        def mount(ctx: MountContext) =
          ctx.async
            .start(Tasks.Load)(ZIO.succeed("loaded"))(Msg.Loaded(_))
            .as("loading")

        def handleMessage(model: String, ctx: MessageContext) =
              case Msg.Loaded(value) => ZIO.succeed(value)
              case _                 => ZIO.succeed(model)

        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "loaded")))
    },
    test("failed async task is exposed to async hooks") {
      val lv = new LiveView[Msg, String]:
        override def hooks: LiveHooks[Msg, String] =
          LiveHooks.empty.async("failure") { (model, event, _) =>
            event.result match
              case LiveAsyncResult.Failed(_) if event.name == Tasks.Load =>
                ZIO.succeed(LiveHookResult.cont("failed"))
              case _ => ZIO.succeed(LiveHookResult.cont(model))
          }

        def mount(ctx: MountContext) =
          ctx.async
            .start(Tasks.Load)(ZIO.fail(new RuntimeException("boom")))(Msg.Loaded(_))
            .as("loading")

        def handleMessage(model: String, ctx: MessageContext) =
          (_: Msg) => ZIO.succeed(model)

        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "failed")))
    },
    test("async completion message can push patch") {
      val lv = new LiveView[Msg, String]:
        def mount(ctx: MountContext) =
          ctx.async
            .start(Tasks.Patch)(ZIO.succeed("done"))(Msg.PatchLoaded(_))
            .as("/start")

        def handleMessage(model: String, ctx: MessageContext) =
              case Msg.PatchLoaded(_) => ctx.nav.pushPatch("/async-done").as(model)
              case _                  => ZIO.succeed(model)

        override def handleParams(model: String, query: queryCodec.Out, url: zio.http.URL, ctx: ParamsContext) =
          ZIO.succeed(url.path.encode)

        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        navigation <- socket.outbox.drop(1).runHead.some
        patchReply <- socket.livePatch("/async-done", meta)
      yield assertTrue(
        navigation._1 == Payload.LiveNavigation("/async-done", LivePatchKind.Push),
        patchReply.response match
          case WebSocketMessage.LiveResponse.Diff(diff) => containsValue(diff, "/async-done")
          case _                                       => false
      )
    },
    test("async completion message can push navigate") {
      val lv = new LiveView[Msg, String]:
        def mount(ctx: MountContext) =
          ctx.async.start(Tasks.Navigate)(ZIO.unit)(Msg.NavigateLoaded(_)).as("loading")

        def handleMessage(model: String, ctx: MessageContext) =
              case Msg.NavigateLoaded(_) => ctx.nav.pushNavigate("/start_async?test=ok").as(model)
              case _                     => ZIO.succeed(model)

        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        navigation <- socket.outbox.drop(1).runHead.some
      yield assertTrue(
        navigation._1 == Payload.LiveRedirect("/start_async?test=ok", LivePatchKind.Push, None)
      )
    },
    test("async completion message can redirect") {
      val lv = new LiveView[Msg, String]:
        def mount(ctx: MountContext) =
          ctx.async.start(Tasks.Redirect)(ZIO.unit)(Msg.RedirectLoaded(_)).as("loading")

        def handleMessage(model: String, ctx: MessageContext) =
              case Msg.RedirectLoaded(_) => ctx.nav.redirect("/not_found").as(model)
              case _                     => ZIO.succeed(model)

        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)

      for
        socket   <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        redirect <- socket.outbox.drop(1).runHead.some
      yield assertTrue(redirect._1 == Payload.Redirect("/not_found", None))
    },
    test("async completion message can put flash") {
      val lv = new LiveView[Msg, String]:
        def mount(ctx: MountContext) =
          ctx.async.start(Tasks.Flash)(ZIO.succeed("hello"))(Msg.FlashLoaded(_)).as("loading")

        def handleMessage(model: String, ctx: MessageContext) =
              case Msg.FlashLoaded(message) => ctx.flash.put("info", message).as("loaded")
              case _                        => ZIO.succeed(model)

        def render(model: String): HtmlElement[Msg] =
          div(
            idAttr := "root",
            span(model),
            flash("info")(message => span(idAttr := "flash", s"flash:$message"))
          )

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "flash:hello")))
    },
    test("event handlers can start async tasks") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount(ctx: MountContext) =
                 ZIO.succeed("idle")

               def handleMessage(model: String, ctx: MessageContext) =
                     case Msg.Start =>
                       ctx.async
                         .start(Tasks.Load)(release.await.as("event-loaded"))(Msg.Loaded(_))
                         .as("loading")
                     case Msg.Loaded(value) => ZIO.succeed(value)
                     case _                 => ZIO.succeed(model)

               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", button(phx.onClick(Msg.Start), "start"), span(model))
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _       <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      loading <- outbox.take
                      _       <- release.succeed(())
                      loaded  <- outbox.take
                    yield assertTrue(
                      diffFromPayload(loading._1).exists(containsValue(_, "loading")),
                      diffFromPayload(loaded._1).exists(containsValue(_, "event-loaded"))
                    )
                  }
      yield result
    },
    test("cancelAsync interrupts the running task") {
      for
        stopped <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount(ctx: MountContext) =
                 ctx.async
                   .start(Tasks.Load)(
                      ZIO.never.as("loaded").ensuring(stopped.succeed(()).unit)
                   )(Msg.Loaded(_))
                   .as("loading")

               def handleMessage(model: String, ctx: MessageContext) =
                     case Msg.Cancel => ctx.async.cancel(Tasks.Load).as("cancelled")
                     case Msg.Loaded(value) => ZIO.succeed(value)
                     case _                 => ZIO.succeed(model)

               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", button(phx.onClick(Msg.Cancel), "cancel"), span(model))
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _      <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      update <- outbox.take
                      done   <- awaitWithin(stopped.await, 1.second)
                    yield assertTrue(
                      diffFromPayload(update._1).exists(containsValue(_, "cancelled")),
                      done.contains(())
                    )
                  }
      yield result
    },
    test("restarting an async task suppresses the previous completion") {
      for
        oldRelease <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount(ctx: MountContext) =
                 for
                   _ <- ctx.async.start(Tasks.Load)(oldRelease.await.as("old"))(Msg.Loaded(_))
                   _ <- ctx.async.start(Tasks.Load)(ZIO.succeed("new"))(Msg.Loaded(_))
                 yield "loading"

               def handleMessage(model: String, ctx: MessageContext) =
                     case Msg.Loaded(value) => ZIO.succeed(value)
                     case _                 => ZIO.succeed(model)

               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", model)
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
        _      <- oldRelease.succeed(())
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) => containsValue(diff, "new") && !containsValue(diff, "old")
          case _                  => false
      )
    },
    test("live components can start async tasks during update") {
      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) =
          ZIO.unit

        def handleMessage(model: Unit, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(UpdateAsyncComponent, id = "lc", props = UpdateAsyncComponent.Action.Ok))

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "lc: good")))
    },
    test("live component async completion can push navigate") {
      val lv = componentNavigationView(UpdateAsyncComponent.Action.Navigate)

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        navigation <- socket.outbox.drop(1).collect {
                        case (payload @ Payload.LiveRedirect(_, _, _), _) => payload
                      }.runHead.some
      yield assertTrue(
        navigation == Payload.LiveRedirect("/start_async?test=ok", LivePatchKind.Push, None)
      )
    },
    test("live component async completion can push patch") {
      val lv = new LiveView[Unit, String]:
        override val queryCodec: LiveQueryCodec[Option[String]] =
          LiveQueryCodec.fromZioHttp(zio.http.codec.HttpCodec.query[String]("test").optional)

        def mount(ctx: MountContext) =
          ZIO.succeed("none")

        override def handleParams(model: String, test: Option[String], _url: zio.http.URL, ctx: ParamsContext) =
          ZIO.succeed(test.getOrElse("none"))

        def handleMessage(model: String, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(model: String): HtmlElement[Unit] =
          div(
            p(s"test:$model"),
            liveComponent(UpdateAsyncComponent, id = "lc", props = UpdateAsyncComponent.Action.Patch)
          )

      for
        initialUrl <- ZIO.fromEither(zio.http.URL.decode("/start_async?test=patch")).orDie
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    initialUrl = initialUrl
                  )
        navigation <- socket.outbox.drop(1).collect {
                        case (payload @ Payload.LiveNavigation(_, _), _) => payload
                      }.runHead.some
        patchReply <- socket.livePatch("/start_async?test=ok", meta)
      yield assertTrue(
        navigation == Payload.LiveNavigation("/start_async?test=ok", LivePatchKind.Push),
        patchReply.response match
          case WebSocketMessage.LiveResponse.Diff(diff) => containsValue(diff, "test:ok")
          case _                                       => false
      )
    },
    test("live component async completion can redirect") {
      val lv = componentNavigationView(UpdateAsyncComponent.Action.Redirect)

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        redirect <- socket.outbox.drop(1).collect {
                      case (payload @ Payload.Redirect(_, _), _) => payload
                    }.runHead.some
      yield assertTrue(redirect == Payload.Redirect("/not_found", None))
    },
    test("live component async completion can navigate with flash") {
      val tokenConfig = TokenConfig.default
      val lv          = componentNavigationView(UpdateAsyncComponent.Action.NavigateFlash)

      for
        socket <- Socket.start(
                    "id",
                    "token",
                    lv,
                    LiveContext(staticChanged = false),
                    meta,
                    tokenConfig = tokenConfig
                  )
        navigation <- socket.outbox.drop(1).collect {
                        case (payload @ Payload.LiveRedirect(_, _, _), _) => payload
                      }.runHead.some
        flashValues = navigation match
                        case Payload.LiveRedirect(
                              "/start_async?test=ok",
                              LivePatchKind.Push,
                              Some(token)
                            ) => FlashToken.decode(tokenConfig, token)
                        case _ => None
      yield assertTrue(flashValues.contains(Map("info" -> "hello")))
    },
    test("live components can start scoped async tasks") {
      object AsyncComponent
          extends LiveComponent[Promise[Nothing, Unit], AsyncComponent.Msg, (Promise[Nothing, Unit], String)]:
        private val Load = "component-load"

        enum Msg:
          case Start
          case Loaded(value: String)

        def mount(props: Promise[Nothing, Unit], ctx: MountContext) =
          ZIO.succeed(props -> "idle")

        def handleMessage(
          props: Promise[Nothing, Unit],
          model: (Promise[Nothing, Unit], String),
          ctx: MessageContext
        ) =
              case Msg.Start =>
                ctx.async
                  .start(Load)(model._1.await.as("component-loaded"))(Msg.Loaded(_))
                  .as(model._1 -> "loading")
              case Msg.Loaded(value) => ZIO.succeed(model._1 -> value)

        override def update(
          props: Promise[Nothing, Unit],
          model: (Promise[Nothing, Unit], String),
          ctx: UpdateContext
        ) =
          ZIO.succeed(model)

        def render(
          props: Promise[Nothing, Unit],
          model: (Promise[Nothing, Unit], String),
          self: ComponentRef[Msg]
        ) =
          button(phx.onClick(Msg.Start), phx.target(self), model._2)

      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Unit, Unit]:
               def mount(ctx: MountContext) =
                 ZIO.unit

               def handleMessage(model: Unit, ctx: MessageContext) =
                 (_: Unit) => ZIO.succeed(model)

               def render(model: Unit): HtmlElement[Unit] =
                 div(liveComponent(AsyncComponent, id = "async", props = release))
        event = Payload.Event(
                  `type` = "click",
                  event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
                  value = Json.Obj.empty,
                  cid = Some(1)
                )
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _      <- socket.inbox.offer(event -> meta)
                      _      <- outbox.take
                      _      <- release.succeed(())
                      loaded <- outbox.take
                    yield assertTrue(
                      diffFromPayload(loaded._1).exists(containsValue(_, "component-loaded"))
                    )
                  }
      yield result
    },
    test("socket shutdown interrupts root async tasks") {
      for
        started <- Promise.make[Nothing, Unit]
        stopped <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount(ctx: MountContext) =
                 ctx.async
                   .start(Tasks.Load)(
                      ZIO.acquireReleaseWith(ZIO.unit)(_ => stopped.succeed(()).unit) { _ =>
                        started.succeed(()) *> ZIO.never.as("loaded")
                     }
                   )(Msg.Loaded(_))
                   .as("loading")

               def handleMessage(model: String, ctx: MessageContext) =
                 (_: Msg) => ZIO.succeed(model)

               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", model)
        result <- ZIO.scoped {
                     for
                       socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
                       ready  <- awaitWithin(started.await, 1.second)
                       _      <- socket.shutdown
                       done   <- awaitWithin(stopped.await, 1.second)
                    yield assertTrue(ready.contains(()), done.contains(()))
                   }
      yield result
    },
    test("confirmed component removal interrupts component async tasks") {
      object InterruptComponent extends LiveComponent[Promise[Nothing, Unit], Unit, Boolean]:
        private val Load = "component-interrupt"

        def mount(props: Promise[Nothing, Unit], ctx: MountContext) =
          ZIO.succeed(false)

        override def update(props: Promise[Nothing, Unit], started: Boolean, ctx: UpdateContext) =
          if started then ZIO.succeed(started)
          else
            ctx.async
              .start(Load)(
                ZIO.never.as(()).ensuring(props.succeed(()).unit)
              )(_ => ())
              .as(true)

        def handleMessage(props: Promise[Nothing, Unit], model: Boolean, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model)

        def render(props: Promise[Nothing, Unit], model: Boolean, self: ComponentRef[Unit]) =
          div("component")

      enum ParentMsg:
        case Toggle

      for
        stopped <- Promise.make[Nothing, Unit]
        lv = new LiveView[ParentMsg, Boolean]:
               def mount(ctx: MountContext) =
                 ZIO.succeed(true)

               def handleMessage(model: Boolean, ctx: MessageContext) =
                     case ParentMsg.Toggle => ZIO.succeed(!model)

               def render(model: Boolean): HtmlElement[ParentMsg] =
                 div(
                   button(phx.onClick(ParentMsg.Toggle), "toggle"),
                   if model then liveComponent(InterruptComponent, id = "interrupt", props = stopped)
                   else "gone"
                 )
        destroyed: Payload.Event = Payload.Event(
                                     `type` = "click",
                                     event = "cids_destroyed",
                                     value = Json.Obj("cids" -> Json.Arr(Json.Num(1)))
                                   )
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _    <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      _    <- outbox.take
                      _    <- socket.inbox.offer(destroyed -> meta)
                      _    <- outbox.take
                      done <- awaitWithin(stopped.await, 1.second)
                    yield assertTrue(done.contains(()))
                  }
      yield result
    }
  )

  private def componentNavigationView(action: UpdateAsyncComponent.Action): LiveView[Unit, Unit] =
    new LiveView[Unit, Unit]:
      def mount(ctx: MountContext) =
        ZIO.unit

      def handleMessage(model: Unit, ctx: MessageContext) =
        (_: Unit) => ZIO.succeed(model)

      def render(model: Unit): HtmlElement[Unit] =
        div(liveComponent(UpdateAsyncComponent, id = "lc", props = action))
end AsyncSpec
