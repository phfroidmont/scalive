package scalive

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.LivePatchKind
import scalive.WebSocketMessage.Payload

object AsyncSpec extends ZIOSpecDefault:
  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private object Tasks:
    val Load  = LiveAsync[String]("load")
    val Patch = LiveAsync[String]("patch")

  private enum Msg:
    case Start
    case StartReset
    case Cancel
    case Loaded(result: LiveAsyncResult[String])
    case PatchLoaded(result: LiveAsyncResult[String])

  private case class AssignModel(value: AsyncValue[String] = AsyncValue.Empty)

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

  private def asyncText(value: AsyncValue[String]): String =
    value match
      case AsyncValue.Empty                  => "empty"
      case AsyncValue.Loading(previous)      => s"loading:${previous.getOrElse("none")}"
      case AsyncValue.Ok(current)            => s"ok:$current"
      case AsyncValue.Failed(_, _)           => "failed"
      case AsyncValue.Cancelled(_, reason)   => s"cancelled:${reason.getOrElse("none")}"

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

  private def takeUntil(
    outbox: Queue[(Payload, WebSocketMessage.Meta)],
    max: Int
  )(p: Payload => Boolean): Task[List[Payload]] =
    def loop(remaining: Int, acc: List[Payload]): Task[List[Payload]] =
      if acc.exists(p) || remaining <= 0 then ZIO.succeed(acc.reverse)
      else outbox.take.flatMap { case (payload, _) => loop(remaining - 1, payload :: acc) }

    loop(max, Nil).timeoutFail(new RuntimeException("timed out waiting for async payload"))(2.seconds)

  override def spec = suite("AsyncSpec")(
    test("completed async task sends typed message and pushes diff") {
      val lv = new LiveView[Msg, String]:
        def mount =
          LiveContext
            .startAsync(Tasks.Load)(ZIO.succeed("loaded"))(Msg.Loaded(_))
            .as("loading")
        def handleMessage(model: String) = {
          case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(value)
          case _                                     => ZIO.succeed(model)
        }
        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)
        def subscriptions(model: String) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) => containsValue(diff, "loaded")
          case _                  => false
      )
    },
    test("failed async task sends typed failure message") {
      val lv = new LiveView[Msg, String]:
        def mount =
          LiveContext
            .startAsync(Tasks.Load)(ZIO.fail(new RuntimeException("boom")))(Msg.Loaded(_))
            .as("loading")
        def handleMessage(model: String) = {
          case Msg.Loaded(LiveAsyncResult.Failed(_)) => ZIO.succeed("failed")
          case _                                     => ZIO.succeed(model)
        }
        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)
        def subscriptions(model: String) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) => containsValue(diff, "failed")
          case _                  => false
      )
    },
    test("assignAsync updates a model field without a completion message") {
      val lv = new LiveView[Msg, AssignModel]:
        def mount =
          LiveContext.assignAsync(AssignModel())(_.value)(ZIO.succeed("assigned"))
        def handleMessage(model: AssignModel) = _ => ZIO.succeed(model)
        def render(model: AssignModel): HtmlElement[Msg] =
          div(idAttr := "root", asyncText(model.value))
        def subscriptions(model: AssignModel) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) => containsValue(diff, "ok:assigned")
          case _                  => false
      )
    },
    test("assignAsync stores failures on the selected model field") {
      val lv = new LiveView[Msg, AssignModel]:
        def mount =
          LiveContext.assignAsync(AssignModel())(_.value)(ZIO.fail(new RuntimeException("boom")))
        def handleMessage(model: AssignModel) = _ => ZIO.succeed(model)
        def render(model: AssignModel): HtmlElement[Msg] =
          div(idAttr := "root", asyncText(model.value))
        def subscriptions(model: AssignModel) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) => containsValue(diff, "failed")
          case _                  => false
      )
    },
    test("assignAsync reload preserves previous value while loading") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, AssignModel]:
               def mount = ZIO.succeed(AssignModel(AsyncValue.Ok("old")))
               def handleMessage(model: AssignModel) = {
                 case Msg.Start =>
                   LiveContext.assignAsync(model)(_.value)(release.await.as("new"))
                 case _ => ZIO.succeed(model)
               }
               def render(model: AssignModel): HtmlElement[Msg] =
                 div(idAttr := "root", button(phx.onClick(Msg.Start), "reload"), span(asyncText(model.value)))
               def subscriptions(model: AssignModel) = ZStream.empty
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _       <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      loading <- outbox.take
                      _       <- release.succeed(())
                      loaded  <- outbox.take
                    yield assertTrue(
                      diffFromPayload(loading._1).exists(containsValue(_, "loading:old")),
                      diffFromPayload(loaded._1).exists(containsValue(_, "ok:new"))
                    )
                  }
      yield result
    },
    test("assignAsync reset clears previous value while loading") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, AssignModel]:
               def mount = ZIO.succeed(AssignModel(AsyncValue.Ok("old")))
               def handleMessage(model: AssignModel) = {
                 case Msg.StartReset =>
                   LiveContext.assignAsync(model, reset = true)(_.value)(release.await.as("new"))
                 case _ => ZIO.succeed(model)
               }
               def render(model: AssignModel): HtmlElement[Msg] =
                 div(
                   idAttr := "root",
                   button(phx.onClick(Msg.StartReset), "reload"),
                   span(asyncText(model.value))
                 )
               def subscriptions(model: AssignModel) = ZStream.empty
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _       <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      loading <- outbox.take
                      _       <- release.succeed(())
                      loaded  <- outbox.take
                    yield assertTrue(
                      diffFromPayload(loading._1).exists(containsValue(_, "loading:none")),
                      diffFromPayload(loaded._1).exists(containsValue(_, "ok:new"))
                    )
                  }
      yield result
    },
    test("cancelAssignAsync updates the selected model field") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, AssignModel]:
               def mount =
                 LiveContext.assignAsync(AssignModel())(_.value)(release.await.as("assigned"))
               def handleMessage(model: AssignModel) = {
                 case Msg.Cancel =>
                   LiveContext.cancelAssignAsync(model)(_.value, Some("stop")).as(model)
                 case _ => ZIO.succeed(model)
               }
               def render(model: AssignModel): HtmlElement[Msg] =
                 div(idAttr := "root", button(phx.onClick(Msg.Cancel), "cancel"), span(asyncText(model.value)))
               def subscriptions(model: AssignModel) = ZStream.empty
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    val hasCancelled = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "cancelled:stop"))

                    for
                      _        <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      payloads <- takeUntil(outbox, 3)(hasCancelled)
                    yield assertTrue(payloads.exists(hasCancelled))
                  }
      yield result
    },
    test("async completion message can push patch") {
      val lv = new LiveView[Msg, String]:
        def mount =
          LiveContext
            .startAsync(Tasks.Patch)(ZIO.succeed("done"))(Msg.PatchLoaded(_))
            .as("/start")
        def handleMessage(model: String) = {
          case Msg.PatchLoaded(LiveAsyncResult.Ok(_)) =>
            LiveContext.pushPatch("/async-done").as(model)
          case _ => ZIO.succeed(model)
        }
        override def handleParams(model: String, query: queryCodec.Out, url: zio.http.URL) =
          ZIO.succeed(url.path.encode)
        def render(model: String): HtmlElement[Msg] =
          div(idAttr := "root", model)
        def subscriptions(model: String) = ZStream.empty

      for
        socket     <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        navigation <- socket.outbox.drop(1).runHead.some
        patchReply <- socket.livePatch("/async-done", meta)
      yield assertTrue(
        navigation._1 == Payload.LiveNavigation("/async-done", LivePatchKind.Push),
        patchReply.response match
          case scalive.WebSocketMessage.LiveResponse.Diff(diff) => containsValue(diff, "/async-done")
          case _                                                => false
      )
    },
    test("event handlers can start async tasks") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount = ZIO.succeed("idle")
               def handleMessage(model: String) = {
                 case Msg.Start =>
                   LiveContext
                     .startAsync(Tasks.Load)(release.await.as("event-loaded"))(Msg.Loaded(_))
                     .as("loading")
                 case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(value)
                 case _                                     => ZIO.succeed(model)
               }
               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", button(phx.onClick(Msg.Start), "start"), span(model))
               def subscriptions(model: String) = ZStream.empty
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
    test("cancelAsync sends a typed cancellation message") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount =
                 LiveContext
                   .startAsync(Tasks.Load)(release.await.as("loaded"))(Msg.Loaded(_))
                   .as("loading")
               def handleMessage(model: String) = {
                 case Msg.Cancel =>
                   LiveContext.cancelAsync(Tasks.Load, Some("stop")).as(model)
                 case Msg.Loaded(LiveAsyncResult.Cancelled(Some("stop"))) =>
                   ZIO.succeed("cancelled")
                 case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(value)
                 case _                                    => ZIO.succeed(model)
               }
               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", button(phx.onClick(Msg.Cancel), "cancel"), span(model))
               def subscriptions(model: String) = ZStream.empty
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    val hasCancelled = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "cancelled"))

                    for
                      _        <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      payloads <- takeUntil(outbox, 3)(hasCancelled)
                    yield assertTrue(payloads.exists(hasCancelled))
                  }
      yield result
    },
    test("restarting an async task suppresses the previous completion") {
      for
        oldRelease <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount =
                 for
                   _ <- LiveContext.startAsync(Tasks.Load)(oldRelease.await.as("old"))(Msg.Loaded(_))
                   _ <- LiveContext.startAsync(Tasks.Load)(ZIO.succeed("new"))(Msg.Loaded(_))
                 yield "loading"
               def handleMessage(model: String) = {
                 case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(value)
                 case _                                     => ZIO.succeed(model)
               }
               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", model)
               def subscriptions(model: String) = ZStream.empty
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
        _      <- oldRelease.succeed(())
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) =>
            containsValue(diff, "new") && !containsValue(diff, "old")
          case _ => false
      )
    },
    test("KeepExisting leaves the running async task in place") {
      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Msg, String]:
               def mount =
                 for
                   _ <- LiveContext.startAsync(Tasks.Load)(release.await.as("old"))(Msg.Loaded(_))
                   _ <- LiveContext.startAsync(Tasks.Load, AsyncStartMode.KeepExisting)(
                          ZIO.succeed("new")
                        )(Msg.Loaded(_))
                 yield "loading"
               def handleMessage(model: String) = {
                 case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(value)
                 case _                                     => ZIO.succeed(model)
               }
               def render(model: String): HtmlElement[Msg] =
                 div(idAttr := "root", model)
               def subscriptions(model: String) = ZStream.empty
        socket      <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        updateFiber <- socket.outbox.drop(1).runHead.fork
        _           <- release.succeed(())
        update      <- updateFiber.join.some
      yield assertTrue(
        update._1 match
          case Payload.Diff(diff) =>
            containsValue(diff, "old") && !containsValue(diff, "new")
          case _ => false
      )
    },
    test("live components can start scoped async tasks") {
      object AsyncComponent
          extends LiveComponent[Promise[Nothing, Unit], AsyncComponent.Msg, (Promise[Nothing, Unit], String)]:
        private val Load = LiveAsync[String]("component-load")

        enum Msg:
          case Start
          case Loaded(result: LiveAsyncResult[String])

        def mount(props: Promise[Nothing, Unit]) = ZIO.succeed(props -> "idle")
        def handleMessage(model: (Promise[Nothing, Unit], String)) = {
          case Msg.Start =>
            LiveContext
              .startAsync(Load)(model._1.await.as("component-loaded"))(Msg.Loaded(_))
              .as(model._1 -> "loading")
          case Msg.Loaded(LiveAsyncResult.Ok(value)) => ZIO.succeed(model._1 -> value)
          case _                                     => ZIO.succeed(model)
        }
        override def update(props: Promise[Nothing, Unit], model: (Promise[Nothing, Unit], String)) =
          ZIO.succeed(model)
        def render(model: (Promise[Nothing, Unit], String), self: ComponentRef[Msg]) =
          button(phx.onClick(Msg.Start), phx.target(self), model._2)

      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Unit, Unit]:
               def mount = ZIO.unit
               def handleMessage(model: Unit) = _ => ZIO.succeed(model)
               def render(model: Unit): HtmlElement[Unit] =
                 div(liveComponent(AsyncComponent, id = "async", props = release))
               def subscriptions(model: Unit) = ZStream.empty
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
    test("live components can assign async model fields") {
      object AssignComponent
          extends LiveComponent[Promise[Nothing, Unit], AssignComponent.Msg, AssignComponent.Model]:
        final case class Model(
          release: Promise[Nothing, Unit],
          value: AsyncValue[String] = AsyncValue.Empty)

        enum Msg:
          case Start

        def mount(props: Promise[Nothing, Unit]) = ZIO.succeed(Model(props))
        def handleMessage(model: Model) = {
          case Msg.Start =>
            LiveContext.assignAsync(model)(_.value)(model.release.await.as("component-assigned"))
        }
        override def update(props: Promise[Nothing, Unit], model: Model) =
          ZIO.succeed(model)
        def render(model: Model, self: ComponentRef[Msg]) =
          button(phx.onClick(Msg.Start), phx.target(self), asyncText(model.value))

      for
        release <- Promise.make[Nothing, Unit]
        lv = new LiveView[Unit, Unit]:
               def mount = ZIO.unit
               def handleMessage(model: Unit) = _ => ZIO.succeed(model)
               def render(model: Unit): HtmlElement[Unit] =
                 div(liveComponent(AssignComponent, id = "assign", props = release))
               def subscriptions(model: Unit) = ZStream.empty
        event = Payload.Event(
                  `type` = "click",
                  event = BindingId.attrBindingId(Vector("root:div", "component:0:1"), 1),
                  value = Json.Obj.empty,
                  cid = Some(1)
                )
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    for
                      _       <- socket.inbox.offer(event -> meta)
                      loading <- outbox.take
                      _       <- release.succeed(())
                      loaded  <- outbox.take
                    yield assertTrue(
                      diffFromPayload(loading._1).exists(containsValue(_, "loading:none")),
                      diffFromPayload(loaded._1).exists(containsValue(_, "ok:component-assigned"))
                    )
                  }
      yield result
    }
  )
end AsyncSpec
