package scalive

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

import scalive.WebSocketMessage.Payload

object AssignAsyncParitySpec extends ZIOSpecDefault:
  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  private enum RootMsg:
    case Cancel
    case Renew

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
      case AsyncValue.Empty                => "empty"
      case AsyncValue.Loading(previous)    => s"loading:${previous.getOrElse("none") }"
      case AsyncValue.Ok(current)          => s"ok:$current"
      case AsyncValue.Failed(_, _)         => "failed"
      case AsyncValue.Cancelled(_, reason) => s"cancelled:${reason.getOrElse("none") }"

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

    loop(max, Nil).timeoutFail(new RuntimeException("timed out waiting for async assign payload"))(2.seconds)

  private def awaitWithinTestClock[A](effect: UIO[A], duration: Duration): UIO[Option[A]] =
    for
      fiber <- effect.timeout(duration).fork
      _     <- TestClock.adjust(duration)
      value <- fiber.join
    yield value

  private object UpdateAssignComponent
      extends LiveComponent[UpdateAssignComponent.Action, Unit, UpdateAssignComponent.Model]:
    enum Action:
      case Ok
      case Fail

    final case class Model(
      action: Action,
      started: Boolean = false,
      value: AsyncValue[String] = AsyncValue.Empty)

    def mount(props: Action) = ZIO.succeed(Model(props))

    override def update(props: Action, model: Model) =
      val next = model.copy(action = props)
      if next.started then ZIO.succeed(next)
      else
        LiveContext
          .assignAsync(next)(_.value)(
            props match
              case Action.Ok   => ZIO.succeed("component-assigned")
              case Action.Fail => ZIO.fail(new RuntimeException("boom"))
          )
          .map(_.copy(started = true))

    def handleMessage(model: Model) = _ => ZIO.succeed(model)

    def render(model: Model, self: ComponentRef[Unit]) =
      val _ = self
      div(asyncText(model.value))

  private object ReloadAssignComponent
      extends LiveComponent[ReloadAssignComponent.Props, Unit, ReloadAssignComponent.Model]:
    enum Props:
      case Idle
      case Reload(release: Promise[Nothing, Unit], reset: Boolean)

    final case class Model(value: AsyncValue[String] = AsyncValue.Ok("old"))

    def mount(props: Props) =
      val _ = props
      ZIO.succeed(Model())

    override def update(props: Props, model: Model) =
      props match
        case Props.Idle => ZIO.succeed(model)
        case Props.Reload(release, reset) =>
          LiveContext.assignAsync(model, reset = reset)(_.value)(release.await.as("new"))

    def handleMessage(model: Model) = _ => ZIO.succeed(model)

    def render(model: Model, self: ComponentRef[Unit]) =
      val _ = self
      div(asyncText(model.value))

  private object CancelAssignComponent
      extends LiveComponent[CancelAssignComponent.Props, Unit, AssignModel]:
    enum Props:
      case Idle
      case Cancel
      case Renew

    def mount(props: Props) =
      val _ = props
      LiveContext.assignAsync(AssignModel())(_.value)(ZIO.never.as("initial"))

    override def update(props: Props, model: AssignModel) =
      props match
        case Props.Idle => ZIO.succeed(model)
        case Props.Cancel =>
          LiveContext.cancelAssignAsync(model)(_.value, Some("cancel")).as(model)
        case Props.Renew =>
          LiveContext.assignAsync(model)(_.value)(ZIO.succeed("renewed"))

    def handleMessage(model: AssignModel) = _ => ZIO.succeed(model)

    def render(model: AssignModel, self: ComponentRef[Unit]) =
      val _ = self
      div(asyncText(model.value))

  private object InterruptAssignComponent
      extends LiveComponent[Promise[Nothing, Unit], Unit, AssignModel]:
    def mount(stopped: Promise[Nothing, Unit]) =
      LiveContext.assignAsync(AssignModel())(_.value)(
        ZIO.never.as("never").ensuring(stopped.succeed(()).unit)
      )

    override def update(stopped: Promise[Nothing, Unit], model: AssignModel) =
      val _ = stopped
      ZIO.succeed(model)

    def handleMessage(model: AssignModel) = _ => ZIO.succeed(model)

    def render(model: AssignModel, self: ComponentRef[Unit]) =
      val _ = (model, self)
      div("component")

  override def spec = suite("AssignAsyncParitySpec")(
    test("root assignAsync can renew after cancellation") {
      val lv = new LiveView[RootMsg, AssignModel]:
        def mount =
          LiveContext.assignAsync(AssignModel())(_.value)(ZIO.never.as("initial"))
        def handleMessage(model: AssignModel) = {
          case RootMsg.Cancel =>
            LiveContext.cancelAssignAsync(model)(_.value, Some("cancel")).as(model)
          case RootMsg.Renew =>
            LiveContext.assignAsync(model)(_.value)(ZIO.succeed("renewed"))
        }
        def render(model: AssignModel): HtmlElement[RootMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(RootMsg.Cancel), "cancel"),
            button(phx.onClick(RootMsg.Renew), "renew"),
            span(asyncText(model.value))
          )
        def subscriptions(model: AssignModel) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    val hasCancelled = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "cancelled:cancel"))
                    val hasLoading = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "loading:none"))
                    val hasRenewed = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "ok:renewed"))

                    for
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      cancelled <- takeUntil(outbox, 3)(hasCancelled)
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      renewed   <- takeUntil(outbox, 4)(hasRenewed)
                    yield assertTrue(
                      cancelled.exists(hasCancelled),
                      renewed.exists(hasLoading),
                      renewed.exists(hasRenewed)
                    )
                  }
      yield result
    },
    test("root assignAsync task is interrupted on socket shutdown") {
      for
        stopped <- Promise.make[Nothing, Unit]
        lv = new LiveView[Unit, AssignModel]:
               def mount =
                 LiveContext.assignAsync(AssignModel())(_.value)(
                   ZIO.never.as("never").ensuring(stopped.succeed(()).unit)
                 )
               def handleMessage(model: AssignModel) = _ => ZIO.succeed(model)
               def render(model: AssignModel): HtmlElement[Unit] =
                 div(asyncText(model.value))
               def subscriptions(model: AssignModel) = ZStream.empty
        result <- ZIO.scoped {
                    for
                      socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
                      _      <- socket.shutdown
                      done   <- awaitWithinTestClock(stopped.await, 1.second)
                    yield assertTrue(done.contains(()))
                  }
      yield result
    },
    test("live components can assignAsync during update") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(UpdateAssignComponent, id = "assign", props = UpdateAssignComponent.Action.Ok))
        def subscriptions(model: Unit) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "ok:component-assigned")))
    },
    test("live component update assignAsync stores failures") {
      val lv = new LiveView[Unit, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = _ => ZIO.succeed(model)
        def render(model: Unit): HtmlElement[Unit] =
          div(liveComponent(UpdateAssignComponent, id = "assign", props = UpdateAssignComponent.Action.Fail))
        def subscriptions(model: Unit) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        update <- socket.outbox.drop(1).runHead.some
      yield assertTrue(diffFromPayload(update._1).exists(containsValue(_, "failed")))
    },
    test("component assignAsync reload preserves previous value while loading") {
      componentReloadTest(reset = false, expectedLoading = "loading:old")
    },
    test("component assignAsync reload can reset previous value while loading") {
      componentReloadTest(reset = true, expectedLoading = "loading:none")
    },
    test("component assignAsync can renew after cancellation") {
      enum ParentMsg:
        case Cancel
        case Renew

      val lv = new LiveView[ParentMsg, Unit]:
        def mount = ZIO.unit
        def handleMessage(model: Unit) = {
          case ParentMsg.Cancel =>
            LiveContext
              .sendUpdate[CancelAssignComponent.type]("cancel", CancelAssignComponent.Props.Cancel)
              .as(model)
          case ParentMsg.Renew =>
            LiveContext
              .sendUpdate[CancelAssignComponent.type]("cancel", CancelAssignComponent.Props.Renew)
              .as(model)
        }
        def render(model: Unit): HtmlElement[ParentMsg] =
          div(
            idAttr := "root",
            button(phx.onClick(ParentMsg.Cancel), "cancel"),
            button(phx.onClick(ParentMsg.Renew), "renew"),
            liveComponent(CancelAssignComponent, id = "cancel", props = CancelAssignComponent.Props.Idle)
          )
        def subscriptions(model: Unit) = ZStream.empty

      for
        socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        result <- withOutbox(socket) { outbox =>
                    val hasCancelled = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "cancelled:cancel"))
                    val hasLoading = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "loading:none"))
                    val hasRenewed = (payload: Payload) =>
                      diffFromPayload(payload).exists(containsValue(_, "ok:renewed"))

                    for
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                      cancelled <- takeUntil(outbox, 4)(hasCancelled)
                      _         <- socket.inbox.offer(click(Vector("root:div", "tag:1:button")) -> meta)
                      renewed   <- takeUntil(outbox, 4)(hasRenewed)
                    yield assertTrue(
                      cancelled.exists(hasCancelled),
                      renewed.exists(hasLoading),
                      renewed.exists(hasRenewed)
                    )
                  }
      yield result
    },
    test("confirmed component removal interrupts component assignAsync tasks") {
      enum ParentMsg:
        case Toggle

      for
        stopped <- Promise.make[Nothing, Unit]
        lv = new LiveView[ParentMsg, Boolean]:
               def mount = ZIO.succeed(true)
               def handleMessage(model: Boolean) = {
                 case ParentMsg.Toggle => ZIO.succeed(!model)
               }
               def render(model: Boolean): HtmlElement[ParentMsg] =
                 div(
                   idAttr := "root",
                   button(phx.onClick(ParentMsg.Toggle), "toggle"),
                   if model then liveComponent(InterruptAssignComponent, id = "interrupt", props = stopped)
                   else "gone"
                 )
               def subscriptions(model: Boolean) = ZStream.empty
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
                      done <- awaitWithinTestClock(stopped.await, 1.second)
                    yield assertTrue(done.contains(()))
                  }
      yield result
    }
  )

  private def componentReloadTest(reset: Boolean, expectedLoading: String) =
    enum ParentMsg:
      case Reload

    for
      release <- Promise.make[Nothing, Unit]
      lv = new LiveView[ParentMsg, Unit]:
             def mount = ZIO.unit
             def handleMessage(model: Unit) = {
               case ParentMsg.Reload =>
                 LiveContext
                   .sendUpdate[ReloadAssignComponent.type](
                     "reload",
                     ReloadAssignComponent.Props.Reload(release, reset)
                   )
                   .as(model)
             }
             def render(model: Unit): HtmlElement[ParentMsg] =
               div(
                 idAttr := "root",
                 button(phx.onClick(ParentMsg.Reload), "reload"),
                 liveComponent(ReloadAssignComponent, id = "reload", props = ReloadAssignComponent.Props.Idle)
               )
             def subscriptions(model: Unit) = ZStream.empty
      socket <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
      result <- withOutbox(socket) { outbox =>
                  for
                    _       <- socket.inbox.offer(click(Vector("root:div", "tag:0:button")) -> meta)
                    loading <- outbox.take
                    _       <- release.succeed(())
                    loaded  <- outbox.take
                  yield assertTrue(
                    diffFromPayload(loading._1).exists(containsValue(_, expectedLoading)),
                    diffFromPayload(loaded._1).exists(containsValue(_, "ok:new"))
                  )
                }
    yield result
end AssignAsyncParitySpec
