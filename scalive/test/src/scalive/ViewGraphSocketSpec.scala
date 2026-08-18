package scalive

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import zio.*
import zio.json.ast.Json
import zio.test.*

import scalive.WebSocketMessage.Payload

object ViewGraphSocketSpec extends ZIOSpecDefault:

  private enum Msg:
    case Increment

  private object CounterComponent extends LiveComponent[Unit, Unit, Int]:
    val constructions = new AtomicInteger(0)
    def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
    def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model + 1)
    override def view(
      props: Signal[Unit],
      model: Signal[Int],
      self: ComponentRef[Unit]
    ) =
      val _ = constructions.incrementAndGet()
      button(on.click(()), phx.target(self), "Component ", model.map(_.toString))

  private object DisposalComponent
      extends LiveComponent.Eventless[Unit, Unit]:
    val latestScope = new AtomicReference[SignalScope]()
    def mount(props: Unit, ctx: MountContext) = ZIO.unit
    override def view(
      props: Signal[Unit],
      model: Signal[Unit],
      self: ComponentRef[Nothing]
    ) =
      latestScope.set(model.scope)
      div("component")

  private object NestedParentComponent
      extends LiveComponent.Eventless[Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) = ZIO.unit

    override def view(
      props: Signal[Unit],
      model: Signal[Unit],
      self: ComponentRef[Nothing]
    ) =
      div(liveComponent(NestedCounterComponent, "nested-counter", ()))

  private object NestedCounterComponent extends LiveComponent[Unit, Unit, Int]:
    def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
    def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
      (_: Unit) => ZIO.succeed(model + 1)
    override def view(
      props: Signal[Unit],
      model: Signal[Int],
      self: ComponentRef[Unit]
    ) =
      button(on.click(()), phx.target(self), "Nested component ", model.map(_.toString))

  private val meta = WebSocketMessage.Meta(None, None, topic = "t", eventType = "event")

  override def spec = suite("ViewGraphSocketSpec")(
    test("constructs the connected graph only after mount succeeds") {
      ZIO.scoped {
        for
          mounted     <- ZIO.succeed(new AtomicBoolean(false))
          constructions <- ZIO.succeed(new AtomicInteger(0))
          lv = new LiveView[Unit, Unit]:
                 def mount(ctx: MountContext) = ZIO.succeed(mounted.set(true))
                 def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
                 override def view(model: Signal[Unit]) =
                   require(mounted.get(), "view constructed before mount")
                   constructions.incrementAndGet()
                   div("mounted")
          _ <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
        yield assertTrue(mounted.get(), constructions.get() == 1)
      }
    },
    test("does not construct a connected graph when mount fails") {
      ZIO.scoped {
        for
          constructions <- ZIO.succeed(new AtomicInteger(0))
          lv = new LiveView[Unit, Unit]:
                 def mount(ctx: MountContext) = ZIO.fail(new RuntimeException("mount failed"))
                 def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
                 override def view(model: Signal[Unit]) =
                   constructions.incrementAndGet()
                   div("unreachable")
          result <- Socket
                      .start("id", "token", lv, LiveContext(staticChanged = false), meta).exit
        yield assertTrue(result.isFailure, constructions.get() == 0)
      }
    },
    test("disposes the connected graph when page title evaluation fails") {
      ZIO.scoped {
        for
          capturedScope <- ZIO.succeed(new AtomicReference[SignalScope]())
          lv = new LiveView[Unit, Unit]:
                 def mount(ctx: MountContext) = ZIO.unit
                 def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
                 override def pageTitle(model: Unit) = throw new RuntimeException("title failed")
                 override def view(model: Signal[Unit]) =
                   capturedScope.set(model.scope)
                   div("root")
          result <- Socket
                      .start("id", "token", lv, LiveContext(staticChanged = false), meta).exit
        yield assertTrue(result.isFailure, capturedScope.get().isDisposed)
      }
    },
    test("constructs once and atomically updates a connected view graph") {
      ZIO.scoped {
        for
          constructions <- ZIO.succeed(new AtomicInteger(0))
          lv = new LiveView[Msg, Int]:
                 def mount(ctx: MountContext) = ZIO.succeed(0)
                 def handleMessage(model: Int, ctx: MessageContext) =
                   case Msg.Increment => ZIO.succeed(model + 1)
                 override def view(model: Signal[Int]) =
                   val _ = constructions.incrementAndGet()
                   div(
                     idAttr := "root",
                     button(on.click(Msg.Increment), "Increment"),
                     span(model.map(_.toString))
                   )
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta
                    )
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          initialHtml <- socket.renderedHtml
          binding <- ZIO
                       .fromOption("phx-click=\"([^\"]+)\"".r.findFirstMatchIn(initialHtml).map(_.group(1)))
                        .orElseFail(new RuntimeException("missing click binding"))
          _ <- socket.inbox.offer(
                 Payload.Event(
                   `type` = "click",
                   event = binding,
                   value = Json.Obj.empty
                 ) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("view graph update timed out"))(1.second)
          updatedHtml <- socket.renderedHtml
          _           <- fiber.interrupt
        yield assertTrue(
          constructions.get() == 1,
          initialHtml.contains("<span>0</span>"),
          updatedHtml.contains("<span>1</span>")
        )
      }
    },
    test("resolves component lifecycle slots inside a view graph root") {
      ZIO.scoped {
        val lv = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
          override def view(model: Signal[Unit]) =
            div(liveComponent(CounterComponent, "counter", ()))

        for
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta
                    )
          initial <- socket.renderedHtml
          binding <- ZIO
                       .fromOption("phx-click=\"([^\"]+)\"".r.findFirstMatchIn(initial).map(_.group(1)))
                       .orElseFail(new RuntimeException("missing component binding"))
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          _ <- socket.inbox.offer(
                 Payload.Event(
                   `type` = "click",
                   event = binding,
                   value = Json.Obj.empty,
                   cid = Some(1)
                 ) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("component update timed out"))(1.second)
          updated <- socket.renderedHtml
          _       <- fiber.interrupt
        yield assertTrue(
          CounterComponent.constructions.get() == 1,
          initial.contains("Component 0"),
          updated.contains("Component 1"),
          updated.contains("data-phx-component=\"1\"")
        )
      }
    },
    test("reserves parent component CIDs before resolving nested components") {
      ZIO.scoped {
        val lv = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
          override def view(model: Signal[Unit]) =
            div(liveComponent(NestedParentComponent, "parent", ()))

        for
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta
                    )
          initial <- socket.renderedHtml
          binding <- ZIO
                       .fromOption("phx-click=\"([^\"]+)\"".r.findFirstMatchIn(initial).map(_.group(1)))
                       .orElseFail(new RuntimeException("missing nested component binding"))
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          _ <- socket.inbox.offer(
                 Payload.Event(
                   `type` = "click",
                   event = binding,
                   value = Json.Obj.empty,
                   cid = Some(2)
                 ) -> meta
               )
          _       <- queue.take.timeoutFail(new RuntimeException("nested component update timed out"))(1.second)
          updated <- socket.renderedHtml
          _       <- fiber.interrupt
        yield assertTrue(
          initial.contains("data-phx-component=\"1\""),
          initial.contains("data-phx-component=\"2\""),
          updated.contains("Nested component 1")
        )
      }
    },
    test("namespaces view graph bindings for separate component instances") {
      object Component extends LiveComponent[String, Unit, Int]:
        def mount(props: String, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: String, model: Int, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model + 1)
        override def view(
          props: Signal[String],
          model: Signal[Int],
          self: ComponentRef[Unit]
        ) = button(on.click(()), phx.target(self), props, ":", model.map(_.toString))

      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
        override def view(model: Signal[Unit]) =
          div(
            liveComponent(Component, "left", "left"),
            liveComponent(Component, "right", "right")
          )

      ZIO.scoped {
        for
          socket  <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
          initial <- socket.renderedHtml
          bindings = "phx-click=\"([^\"]+)\"".r.findAllMatchIn(initial).map(_.group(1)).toVector
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          _ <- socket.inbox.offer(
                 Payload.Event("click", bindings(0), Json.Obj.empty, cid = Some(1)) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("left update timed out"))(1.second)
          _ <- socket.inbox.offer(
                 Payload.Event("click", bindings(1), Json.Obj.empty, cid = Some(2)) -> meta
               )
          _       <- queue.take.timeoutFail(new RuntimeException("right update timed out"))(1.second)
          updated <- socket.renderedHtml
          _       <- fiber.interrupt
        yield assertTrue(
          bindings.size == 2,
          bindings.distinct.size == 2,
          updated.contains("left:1"),
          updated.contains("right:1")
        )
      }
    },
    test("preserves explicit component targets inside another component") {
      object Target extends LiveComponent[Unit, Unit, Int]:
        def mount(props: Unit, ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(props: Unit, model: Int, ctx: MessageContext) =
          (_: Unit) => ZIO.succeed(model + 1)
        override def view(
          props: Signal[Unit],
          model: Signal[Int],
          self: ComponentRef[Unit]
        ) = div(idAttr := "target", "target:", model.map(_.toString))

      val target = component(Target, "target")

      object Sender extends LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        override def view(
          props: Signal[Unit],
          model: Signal[Unit],
          self: ComponentRef[Nothing]
        ) = div(
          button(on.click.to(target)(()), "increment target instance"),
          button(
            on.click.toComponent(Target)(()),
            phx.target(DomSelector.css("#target")),
            "increment target selector"
          )
        )

      val lv = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
        override def view(model: Signal[Unit]) =
          div(liveComponent(Sender, "sender", ()), target.render(()))

      ZIO.scoped {
        for
          socket  <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
          initial <- socket.renderedHtml
          bindings = "phx-click=\"([^\"]+)\"".r
                       .findAllMatchIn(initial)
                       .map(_.group(1))
                       .toVector
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          _ <- socket.inbox.offer(
                 Payload.Event("click", bindings(0), Json.Obj.empty, cid = Some(1)) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("instance target timed out"))(1.second)
          _ <- socket.inbox.offer(
                 Payload.Event("click", bindings(1), Json.Obj.empty, cid = Some(2)) -> meta
               )
          _       <- queue.take.timeoutFail(new RuntimeException("selector target timed out"))(1.second)
          updated <- socket.renderedHtml
          _       <- fiber.interrupt
        yield assertTrue(
          bindings.size == 2,
          initial.contains("target:0"),
          updated.contains("target:2")
        )
      }
    },
    test("applies lifecycle turns to the pending push-patch model") {
      enum PendingMsg:
        case Patch
        case AddTen

      val lv = new LiveView[PendingMsg, Int]:
        def mount(ctx: MountContext) = ZIO.succeed(0)
        def handleMessage(model: Int, ctx: MessageContext) =
          case PendingMsg.Patch  => ctx.nav.pushPatchUnsafe("/next").as(model + 1)
          case PendingMsg.AddTen => ZIO.succeed(model + 10)
        override def view(model: Signal[Int]) =
          div(
            button(on.click(PendingMsg.Patch), "patch"),
            button(on.click(PendingMsg.AddTen), "add"),
            span(model.map(_.toString))
          )

      ZIO.scoped {
        for
          socket  <- Socket.start("id", "token", lv, LiveContext(staticChanged = false), meta)
          initial <- socket.renderedHtml
          bindings = "phx-click=\"([^\"]+)\"".r.findAllMatchIn(initial).map(_.group(1)).toVector
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          _ <- socket.inbox.offer(
                 Payload.Event("click", bindings(0), Json.Obj.empty) -> meta
               )
          firstNavigationPayload <- queue.take.timeoutFail(
                                      new RuntimeException("navigation reply timed out")
                                    )(1.second)
          secondNavigationPayload <- queue.take.timeoutFail(
                                       new RuntimeException("navigation command timed out")
                                     )(1.second)
          navigation = Vector(firstNavigationPayload, secondNavigationPayload).map(_._1)
          _ <- socket.inbox.offer(
                 Payload.Event("click", bindings(1), Json.Obj.empty) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("intervening update timed out"))(1.second)
          beforePatch <- socket.renderedHtml
          _           <- socket.livePatch("/next", meta.copy(eventType = "live_patch"))
          afterPatch  <- socket.renderedHtml
          _           <- fiber.interrupt
        yield assertTrue(
          navigation.contains(
            Payload.LiveNavigation("/next", WebSocketMessage.LivePatchKind.Push)
          ),
          beforePatch.contains("<span>11</span>"),
          afterPatch.contains("<span>11</span>")
        )
      }
    },
    test("sendUpdate preserves component state and view graph identity") {
      enum ParentMsg:
        case Update

      object StatefulComponent extends LiveComponent[String, Nothing, Int]:
        val constructions = new AtomicInteger(0)
        def mount(props: String, ctx: MountContext) = ZIO.succeed(7)
        override def update(props: String, model: Int, ctx: UpdateContext) = ZIO.succeed(model)
        def handleMessage(props: String, model: Int, ctx: MessageContext) =
          (_: Nothing) => ZIO.succeed(model)
        override def view(
          props: Signal[String],
          model: Signal[Int],
          self: ComponentRef[Nothing]
        ) =
          val _ = constructions.incrementAndGet()
          articleTag(
            dataAttr("cid") := self.cid.toString,
            props,
            ":",
            model.map(_.toString)
          )

      val component = scalive.component(StatefulComponent, "stateful")
      val lv = new LiveView[ParentMsg, Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case ParentMsg.Update => ctx.components.sendUpdate(component, "updated").as(model)
        override def view(model: Signal[Unit]) =
          div(
            button(on.click(ParentMsg.Update), "update"),
            component.render("initial")
          )

      ZIO.scoped {
        for
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta
                    )
          initial <- socket.renderedHtml
          binding <- ZIO
                       .fromOption("phx-click=\"([^\"]+)\"".r.findFirstMatchIn(initial).map(_.group(1)))
                       .orElseFail(new RuntimeException("missing sendUpdate binding"))
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          _ <- socket.inbox.offer(
                 Payload.Event(
                   `type` = "click",
                   event = binding,
                   value = Json.Obj.empty
                 ) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("sendUpdate timed out"))(1.second)
          updated <- socket.renderedHtml
          _       <- fiber.interrupt
        yield assertTrue(
          initial.contains("data-phx-component=\"1\""),
          initial.contains("initial:7"),
          updated.contains("data-phx-component=\"1\""),
          updated.contains("updated:7"),
          StatefulComponent.constructions.get() == 1
        )
      }
    },
    test("disposes the root view graph on socket shutdown") {
      ZIO.scoped {
        for
          capturedScope <- ZIO.succeed(new AtomicReference[SignalScope]())
          lv = new LiveView[Unit, Unit]:
                 def mount(ctx: MountContext) = ZIO.unit
                 def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
                 override def view(model: Signal[Unit]) =
                   capturedScope.set(model.scope)
                   div("root")
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta
                    )
          _ <- socket.shutdown
        yield assertTrue(capturedScope.get().isDisposed)
      }
    },
    test("does not report an interrupted event handler as a crash during shutdown") {
      ZIO.scoped {
        for
          started <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          crashes <- ZIO.succeed(new AtomicInteger(0))
          lv = new LiveView[Unit, Unit]:
                 def mount(ctx: MountContext) = ZIO.unit
                 def handleMessage(model: Unit, ctx: MessageContext) =
                   (_: Unit) => started.succeed(()) *> release.await
                 override def view(model: Signal[Unit]) = button(on.click(()), "block")
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta,
                      onCrash = ZIO.succeed(crashes.incrementAndGet()).unit
                    )
          initial <- socket.renderedHtml
          binding <- ZIO
                       .fromOption(
                         "phx-click=\"([^\"]+)\"".r.findFirstMatchIn(initial).map(_.group(1))
                       ).orElseFail(new RuntimeException("missing blocking event binding"))
          _ <- socket.inbox.offer(Payload.Event("click", binding, Json.Obj.empty) -> meta)
          _ <- started.await.timeoutFail(new RuntimeException("event did not start"))(1.second)
          _ <- socket.shutdown.timeoutFail(new RuntimeException("shutdown timed out"))(1.second)
        yield assertTrue(crashes.get() == 0)
      }
    },
    test("disposes component view graphs after browser confirmation") {
      ZIO.scoped {
        val lv = new LiveView[Unit, Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def handleMessage(model: Unit, ctx: MessageContext) = (_: Unit) => ZIO.unit
          override def view(model: Signal[Unit]) =
            div(liveComponent(DisposalComponent, "disposal", ()))

        for
          socket <- Socket.start(
                      "id",
                      "token",
                      lv,
                      LiveContext(staticChanged = false),
                      meta
                    )
          queue <- Queue.unbounded[(Payload, WebSocketMessage.Meta)]
          fiber <- socket.outbox.runForeach(queue.offer).fork
          _     <- queue.take
          scope  = DisposalComponent.latestScope.get()
          activeBefore = !scope.isDisposed
          _ <- socket.inbox.offer(
                 Payload.Event(
                   `type` = "click",
                   event = "cids_destroyed",
                   value = Json.Obj("cids" -> Json.Arr(Json.Num(1)))
                 ) -> meta
               )
          _ <- queue.take.timeoutFail(new RuntimeException("component destruction timed out"))(1.second)
          _ <- fiber.interrupt
        yield assertTrue(activeBefore, scope.isDisposed)
      }
    },
    test("disposes a newly created component graph when its parent view graph evaluation fails") {
      object Component extends LiveComponent.Eventless[Unit, Unit]:
        val latestScope = new AtomicReference[SignalScope]()
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        override def view(
          props: Signal[Unit],
          model: Signal[Unit],
          self: ComponentRef[Nothing]
        ) =
          latestScope.set(model.scope)
          div("component")

      val graph = ViewGraph.build[Boolean](visible =>
        div(
          visible.when(div(liveComponent(Component, "candidate", ()))),
          visible.map(value => if value then throw new IllegalStateException("later slot failed") else "ok")
        )
      )

      for
        components <- Ref.make(scalive.socket.ComponentRuntimeState.empty)
        initial <- scalive.socket.SocketComponentRuntime.evaluateViewGraph(
                     graph,
                     false,
                     SignalEvaluation.empty,
                     revision = 1L,
                     components,
                     LiveContext(staticChanged = false)
                   )
        failed <- scalive.socket.SocketComponentRuntime
                    .evaluateViewGraph(
                      graph,
                      true,
                      initial.evaluation,
                      revision = 2L,
                      components,
                      LiveContext(staticChanged = false)
                    ).exit
        state <- components.get
        scope  = Component.latestScope.get()
        _     <- ZIO.succeed(graph.dispose())
      yield assertTrue(
        failed.isFailure,
        state.instances.isEmpty,
        scope != null,
        scope.isDisposed
      )
    }
  )
end ViewGraphSocketSpec
