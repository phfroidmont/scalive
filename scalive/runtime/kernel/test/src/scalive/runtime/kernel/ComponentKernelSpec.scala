package scalive.runtime.kernel

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.OwnerId

object ComponentKernelSpec extends ZIOSpecDefault:
  private val config = SessionConfig.make(4, 8).toOption.get

  private final class Reservation extends OutboundReservation[SessionOutput]:
    def publish(batch: OutboundBatch[SessionOutput]) = ZIO.unit
    def release                                  = ZIO.unit

  private final class Outbound extends OutboundReservations[SessionOutput]:
    def reserve  = ZIO.succeed(Reservation())
    def awaitCapacity = ZIO.unit
    def take     = ZIO.fail(OutboundReservationError.Shutdown)
    def shutdown = ZIO.unit

  private class FakeEnvironment(events: ConcurrentLinkedQueue[String])
      extends ComponentEnvironment[Int, Int]:
    type RenderInputs = Int
    def renderInputs(model: Int): Int = 0

    def mount[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, draft: TurnDraft[Int, Int]) =
      ZIO.succeed {
        events.add("mount")
        ComponentCallbackResult(props.asInstanceOf[A], draft, ComponentEnvironmentState(new Object()))
      }

    def update[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed {
        events.add("update")
        ComponentCallbackResult(model, draft, state)
      }

    def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      events.add(s"message:$value")
      val next = model.asInstanceOf[Int] + value.asInstanceOf[Int]
      val emissions =
        if component.isInstanceOf[LiveComponent.WithOutput[?, ?, ?, ?]] then
          emit(value.asInstanceOf[O]) *> emit(value.asInstanceOf[Int].+(1).asInstanceOf[O])
        else ZIO.unit
      emissions.as(ComponentCallbackResult(next.asInstanceOf[A], draft, state))

    def async[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, event: LiveAsyncEvent[M], emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))

    def browserEvent[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, command: SessionCommand.ComponentClientEvent, resolved: Task[M], emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      resolved.flatMap(value => message(id, component, props, model, value, emit, state, draft))

    def afterRender[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed {
        events.add("after-render")
        ComponentAfterRenderResult(draft, state)
      }

    def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) = ZIO.unit
    def close(id: ComponentInstanceId, state: ComponentEnvironmentState)   = ZIO.unit

  private def componentDefinition(
    events: ConcurrentLinkedQueue[String]
  ): LiveComponent[Int, Int, Int] = new LiveComponent[Int, Int, Int]:
    def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
    def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
      ZIO.succeed(model + message)
    def view(props: Signal[Int], model: Signal[Int]) =
      button(on.click(_ => 2), model.map { value =>
        events.add("render")
        value.toString
      })

  private val outputDefinition = new LiveComponent.WithOutput[Int, Int, Int, Int]:
    def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
    def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
      ZIO.succeed(model + message)
    def view(props: Signal[Int], model: Signal[Int]) =
      div(model.map(_.toString))

  private def logic(seen: Ref[Vector[Int]]): SessionLogic[Int, Int] =
    SessionLogic(
      bootstrap = ZIO.succeed(TurnDraft(0)),
      handle = (model, message) => seen.update(_ :+ message).as(TurnDraft(model + message))
    )

  private def textValues(node: EvaluatedNode): Vector[String] = node match
    case value: EvaluatedNode.Text    => Vector(value.value)
    case value: EvaluatedNode.Element => value.children.flatMap(textValues)
    case value: EvaluatedNode.Choice  => value.children.flatMap(textValues)
    case value: EvaluatedNode.Flash   => value.child.toVector.flatMap(textValues)
    case value: EvaluatedNode.Keyed   => value.rows.flatMap(row => textValues(row.child))
    case value: EvaluatedNode.Component =>
      value.resolution.toVector.flatMap(resolution => textValues(resolution.child.root))
    case _: EvaluatedNode.Nested | _: EvaluatedNode.Stream => Vector.empty

  override def spec = suite("ComponentKernelSpec")(
    test("component identities are opaque and monotonic") {
      val first  = ComponentInstanceId.fresh().toOption.get
      val second = ComponentInstanceId.fresh().toOption.get
      val nominalErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.runtime.kernel.*
        import scalive.runtime.contracts.*
        val id: ComponentInstanceId = CommandId.fresh().toOption.get
      """)
      assertTrue(second.value > first.value, nominalErrors.nonEmpty)
    },
    test("a component render state reads the root model only through the render inputs") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        new FakeEnvironment(ConcurrentLinkedQueue[String]()):
          override def renderState(
            id: ComponentInstanceId,
            draft: TurnDraft[Int, Int],
            state: ComponentEnvironmentState
          ) = RenderState.empty
      """)
      assertTrue(errors.exists(_.message.contains("different signature")))
    },
    test("new components mount, update, render and retained props do not update") {
      ZIO.scoped {
        for
          seen    <- Ref.make(Vector.empty[Int])
          events   = ConcurrentLinkedQueue[String]()
          definition = componentDefinition(events)
          instance   = component(definition, "counter")
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(3))))
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          initial = Vector.from(events.iterator().asScala)
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          later    = Vector.from(events.iterator().asScala)
        yield assertTrue(
          initial == Vector("mount", "update", "render", "after-render"),
          later.count(_ == "update") == 1,
          later.count(_ == "render") == 1,
          later.count(_ == "after-render") == 1
        )
      }
    },
    test("after-render runs for a component only when it is new or its props or model changed") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val instance   = component(componentDefinition(events), "counter")
        def afterRenders = events.iterator().asScala.count(_ == "after-render")
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_.toString), instance.render(3))
                       )
                     )
          kernel    <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          mounted    = afterRenders
          _         <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          afterRoot  = afterRenders
          inspected <- kernel.inspect
          id         = inspected.components.values.head.id
          _         <- kernel.submitComponent(id, 2)
          afterChange = afterRenders
          _         <- kernel.submitComponent(id, 0)
          afterSame  = afterRenders
        yield assertTrue(mounted == 1, afterRoot == 1, afterChange == 2, afterSame == 2)
      }
    },
    test("a component holding a child keeps its render while its input is unchanged") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val child  = component(componentDefinition(events), "child")
        val parent = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
            ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(model.map(value => s"parent $value"), child.render(props))
        val instance = component(parent, "parent")
        def parentOf(state: Committed[Int, Int]) =
          state.components.values.find(_.key.applicationId == "parent").get
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_.toString), instance.render(1))
                       )
                     )
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before  <- kernel.inspect
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          root    <- kernel.inspect
          childId  = root.components.values.find(_.key.applicationId == "child").get.id
          _       <- kernel.submitComponent(childId, 2)
          clicked <- kernel.inspect
        yield assertTrue(
          parentOf(root).render.signalEvaluation eq parentOf(before).render.signalEvaluation,
          parentOf(clicked).render.signalEvaluation eq parentOf(before).render.signalEvaluation,
          textValues(parentOf(root).render.tree.root) == Vector("parent 1", "1"),
          textValues(parentOf(clicked).render.tree.root) == Vector("parent 1", "3"),
          textValues(clicked.render.tree.root) == Vector("1", "parent 1", "3")
        )
      }
    },
    test("a turn keeps the components it leaves unchanged as they were committed") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val first  = component(componentDefinition(events), "first")
        val second = component(componentDefinition(events), "second")
        val parent = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
            ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(model.map(value => s"parent $value"), first.render(props), second.render(props))
        val instance = component(parent, "parent")
        def named(state: Committed[Int, Int], name: String) =
          state.components.values.find(_.key.applicationId == name).get
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_.toString), instance.render(1))
                       )
                     )
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before  <- kernel.inspect
          _       <- ZIO.succeed(events.clear())
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          root    <- kernel.inspect
          rootEvents = events.asScala.toVector
          _       <- kernel.submitComponent(named(root, "first").id, 2)
          clicked <- kernel.inspect
        yield assertTrue(
          rootEvents.isEmpty,
          Vector("parent", "first", "second").forall(name =>
            (named(root, name).render eq named(before, name).render) &&
              (named(root, name).environmentState.value eq named(before, name).environmentState.value)
          ),
          !(named(clicked, "first").render eq named(root, "first").render),
          !(named(clicked, "parent").render eq named(root, "parent").render),
          named(clicked, "second").render eq named(root, "second").render,
          textValues(clicked.render.tree.root) == Vector("1", "parent 1", "3", "1")
        )
      }
    },
    test("a turn that changes no component visits none of them") {
      ZIO.scoped {
        val events  = ConcurrentLinkedQueue[String]()
        val visited = ConcurrentLinkedQueue[ComponentInstanceId]()
        val environment = new FakeEnvironment(events):
          override def renderState(
            id: ComponentInstanceId,
            inputs: Int,
            state: ComponentEnvironmentState
          ) =
            visited.add(id)
            RenderState.empty
        val definition = componentDefinition(events)
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           model.map(_.toString),
                           component(definition, "static").render(1),
                           model.map(_ => Vector(1, 2)).splitBy(identity) { (id, item) =>
                             li(component(definition, s"row-$id").render(item))
                           }
                         )
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _      <- ZIO.succeed(visited.clear())
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          root   <- kernel.inspect
        yield assertTrue(
          visited.isEmpty,
          root.components.values.size == 3,
          textValues(root.render.tree.root) == Vector("2", "1", "1", "2")
        )
      }
    },
    test("a component kept next to one whose props change keeps its committed render") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        def named(state: Committed[Int, Int], name: String) =
          state.components.values.find(_.key.applicationId == name).get
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           component(definition, "kept").render(1),
                           component(definition, "changed").render(model)
                         )
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          _      <- ZIO.succeed(events.clear())
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 5))
          after  <- kernel.inspect
          turnEvents = events.asScala.toVector
        yield assertTrue(
          named(after, "kept").render eq named(before, "kept").render,
          named(after, "changed").props == 5,
          turnEvents == Vector("update", "after-render"),
          textValues(after.render.tree.root) == Vector("1", "0")
        )
      }
    },
    test("a turn that declares a kept component's identity again fails and keeps the committed state") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           component(definition, "same").render(1),
                           model.map(_ > 0).when(span(component(definition, "same").render(2)))
                         )
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          failed <- kernel.submit(SessionCommand.Message(kernel.epoch, 1)).either
        yield assertTrue(
          failed.left.exists {
            case SessionRejection.SessionFailed(SessionFailure.StageFailed(_, details)) =>
              details.prettyPrint.contains("duplicate component identity 'same'")
            case _ => false
          },
          before.components.values.map(_.props) == Vector(1)
        )
      }
    },
    test("a component the root stops declaring moves into a component declaring it") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val moving     = component(componentDefinition(events), "moving")
        val holder = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
            ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) = div(moving.render(1))
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           model.map(_ == 0).when(moving.render(1)),
                           model.map(_ > 0).when(component(holder, "holder").render(1))
                         )
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          movingId = before.components.values.head.id
          _     <- kernel.submitComponent(movingId, 4)
          _     <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          after <- kernel.inspect
          holderId = after.components.values.find(_.key.applicationId == "holder").map(_.id)
          moved    = after.components.get(movingId)
        yield assertTrue(
          moved.exists(_.parent == holderId),
          moved.exists(_.model == 5),
          after.components.values.map(_.key.applicationId) == Vector("holder", "moving"),
          textValues(after.render.tree.root) == Vector("5")
        )
      }
    },
    test("components render again when the render state they read changes, and only then") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val notice     = FlashKind("notice")
        val environment = new FakeEnvironment(events):
          override def renderInputs(model: Int) = model / 10
          override def renderState(
            id: ComponentInstanceId,
            inputs: Int,
            state: ComponentEnvironmentState
          ) = RenderState(Map(notice.key -> FlashValue(s"flash $inputs", 0)))
        val definition = componentDefinition(events)
        def afterRenders = events.iterator().asScala.count(_ == "after-render")
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_.toString), component(definition, "reader").render(1))
                       )
                     )
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          mounted  = afterRenders
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          same     = afterRenders
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 10))
          changed  = afterRenders
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          again    = afterRenders
        yield assertTrue(mounted == 1, same == 1, changed == 2, again == 2)
      }
    },
    test("a root turn keeps the rows of a collection that hold components") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        def cards(state: Committed[Int, Int]) =
          state.render.tree.root.children(1).asInstanceOf[EvaluatedNode.Keyed].rows.map(_.child)
        def card(state: Committed[Int, Int], id: Int) =
          state.components.values.find(_.key.applicationId == s"card-$id").get
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           model.map(_.toString),
                           model.map(_ => Vector(1, 2, 3)).splitBy(identity) { (id, item) =>
                             li(item.map(_.toString), component(definition, s"card-$id").render(item))
                           }
                         )
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          _      <- ZIO.succeed(events.clear())
          _    <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          root <- kernel.inspect
          rootEvents    = events.asScala.toVector
          _       <- kernel.submitComponent(card(root, 2).id, 2)
          clicked <- kernel.inspect
        yield assertTrue(
          rootEvents.isEmpty,
          cards(root).zip(cards(before)).forall(_ eq _),
          Vector(1, 2, 3).forall(id => card(root, id).render eq card(before, id).render),
          cards(clicked)(0) eq cards(root)(0),
          !(cards(clicked)(1) eq cards(root)(1)),
          cards(clicked)(2) eq cards(root)(2),
          !(card(clicked, 2).render eq card(root, 2).render),
          card(clicked, 1).render eq card(root, 1).render,
          card(clicked, 3).render eq card(root, 3).render,
          textValues(clicked.render.tree.root) == Vector("1", "1", "1", "2", "4", "3", "3")
        )
      }
    },
    test("send-update props survive unrelated parent renders") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val instance   = component(definition, "send-update-retained")
        val environment = new FakeEnvironment(events):
          override def update[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed {
              events.add(s"update:$props")
              ComponentCallbackResult(props.asInstanceOf[A], draft, state)
            }
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          componentLogic = logic(seen).copy(handle = (model, message) =>
                             ZIO.succeed(
                               TurnDraft(
                                 model + 1,
                                 componentUpdates =
                                   if message == 1 then
                                     Vector(ComponentUpdateRequest(instance, 10))
                                   else Vector.empty
                               )
                             )
                           )
          kernel <- SessionKernel.start(config, componentLogic, program, Outbound(), environment)
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          updated <- kernel.inspect
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 2))
          retained <- kernel.inspect
          updatedComponent  = updated.components.values.head
          retainedComponent = retained.components.values.head
          updateEvents       = events.iterator().asScala.filter(_.startsWith("update:")).toVector
        yield assertTrue(
          updatedComponent.props == 10,
          updatedComponent.model == 10,
          retainedComponent.props == 10,
          retainedComponent.model == 10,
          updateEvents == Vector("update:1", "update:10")
        )
      }
    },
    test("a component that changes the turn but not the root model does not render the root again") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val environment = new FakeEnvironment(events):
          override def mount[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, draft: TurnDraft[Int, Int]) =
            super.mount(id, component, props, draft.copy(effects = SessionEffects(pageTitle = Some("Mounted"))))
        val instance = component(componentDefinition(events), "titled")
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           model.map { value =>
                             events.add("root-render")
                             value.toString
                           },
                           instance.render(1)
                         )
                       )
                     )
          _ <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
        yield assertTrue(events.iterator().asScala.count(_ == "root-render") == 1)
      }
    },
    test("component browser events use the component committed binding") {
      ZIO.scoped {
        for
          seen    <- Ref.make(Vector.empty[Int])
          events   = ConcurrentLinkedQueue[String]()
          definition = componentDefinition(events)
          instance   = component(definition, "counter")
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          mounted = before.components.values.head
          binding = mounted.render.bindings.ids.head
          _ <- kernel.submit(
                 SessionCommand.ComponentClientEvent(
                   kernel.epoch,
                   mounted.id,
                   binding,
                   BindingPayload.Params(Map.empty)
                 )
               )
          after <- kernel.inspect
          stale <- kernel.submitComponent(ComponentInstanceId(Long.MaxValue), 1).either
        yield assertTrue(
          after.components.get(mounted.id).exists(_.model == 3),
          stale == Left(SessionRejection.StaleComponent(ComponentInstanceId(Long.MaxValue)))
        )
      }
    },
    test("component candidate root-state changes are rendered and committed atomically") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed(
              ComponentCallbackResult(
                (model.asInstanceOf[Int] + value.asInstanceOf[Int]).asInstanceOf[A],
                draft.copy(model = draft.model + 100),
                ComponentEnvironmentState(new Object())
              )
            )
        for
          seen       <- Ref.make(Vector.empty[Int])
          definition  = componentDefinition(events)
          instance    = component(definition, "counter")
          program    <- ZIO.fromEither(RenderProgram.compile[Int, Int](root => div(root.map(_.toString), instance.render(1))))
          kernel     <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          mounted    <- kernel.inspect.map(_.components.values.head)
          _          <- kernel.submitComponent(mounted.id, 2)
          committed  <- kernel.inspect
        yield assertTrue(committed.model == 100, committed.components.get(mounted.id).exists(_.model == 3))
      }
    },
    test("raw component interception precedes binding dispatch and preserves event metadata") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val environment = new FakeEnvironment(events):
          override def browserEvent[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, command: SessionCommand.ComponentClientEvent, resolved: Task[M], emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed {
              events.add(s"raw:${command.event.map(_.bindingId)}:${command.event.map(_.value)}")
              ComponentCallbackResult(
                (model.asInstanceOf[Int] + 10).asInstanceOf[A],
                draft,
                state
              )
            }
        for
          seen       <- Ref.make(Vector.empty[Int])
          definition  = componentDefinition(events)
          instance    = component(definition, "counter")
          program    <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          kernel     <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before     <- kernel.inspect
          mounted     = before.components.values.head
          binding     = mounted.render.bindings.ids.head
          _ <- kernel.submit(
                 SessionCommand.ComponentClientEvent(
                   kernel.epoch,
                   mounted.id,
                   binding,
                   BindingPayload.Params(Map.empty),
                   Some(
                     LiveEvent(
                       "click",
                       "click",
                       Json.Obj("value" -> Json.Num(1)),
                       Map.empty,
                       Some(1L),
                       None
                     )
                   )
                 )
               )
          after <- kernel.inspect
        yield assertTrue(
          after.components.get(mounted.id).exists(_.model == 11),
          events.contains("raw:Some(click):Some({\"value\":1})")
        )
      }
    },
    test("component flash uses candidate root state and is committed transactionally") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val notice = FlashKind("notice")
        val definition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message => ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(flash(notice)(message => span(message)))
        val instance = component(definition, "flash-component")
        val environment = new FakeEnvironment(events):
          override def renderInputs(model: Int) = if model == 1 then 1 else 0
          override def renderState(
            id: ComponentInstanceId,
            inputs: Int,
            state: ComponentEnvironmentState
          ) =
            RenderState(if inputs == 1 then Map(notice.key -> FlashValue("candidate flash", 0)) else Map.empty)
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed(ComponentCallbackResult(model, draft.copy(model = 1), state))
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          mounted <- kernel.inspect.map(_.components.values.head)
          _       <- kernel.submitComponent(mounted.id, 1)
          after   <- kernel.inspect
          texts = textValues(after.components.get(mounted.id).get.render.tree.root)
        yield assertTrue(texts.contains("candidate flash"))
      }
    },
    test("a flash a component puts shows in the components declared before it in the same turn") {
      ZIO.scoped {
        val events = ConcurrentLinkedQueue[String]()
        val notice = FlashKind("notice")
        val definition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message => ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(flash(notice)(message => span(message)))
        val before = component(definition, "before")
        val putter = component(definition, "putter")
        val environment = new FakeEnvironment(events):
          override def renderInputs(model: Int) = model
          override def renderState(
            id: ComponentInstanceId,
            inputs: Int,
            state: ComponentEnvironmentState
          ) =
            RenderState(if inputs == 1 then Map(notice.key -> FlashValue("put flash", 0)) else Map.empty)
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed(ComponentCallbackResult(model, draft.copy(model = 1), state))
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](_ => div(before.render(1), putter.render(1)))
                     )
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          initial <- kernel.inspect
          target   = initial.components.values.find(_.key.applicationId == "putter").get
          _       <- kernel.submitComponent(target.id, 1)
          after   <- kernel.inspect
        yield assertTrue(
          textValues(initial.render.tree.root).isEmpty,
          textValues(after.render.tree.root) == Vector("put flash", "put flash")
        )
      }
    },
    test("root flash topology stabilizes to final component requirements without rerunning source") {
      ZIO.scoped {
        val notice      = FlashKind("root-topology")
        val events      = ConcurrentLinkedQueue[String]()
        val sourceDef   = componentDefinition(events)
        val firstDef    = componentDefinition(events)
        val retainedDef = componentDefinition(events)
        val addedDef    = componentDefinition(events)
        val source      = component(sourceDef, "flash-source")
        val first       = component(firstDef, "flash-first")
        val retained    = component(retainedDef, "flash-retained")
        val added       = component(addedDef, "flash-added")
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            events.add("flash-source-callback")
            ZIO.succeed(ComponentCallbackResult(model, draft.copy(model = 1), state))
        for
          seen <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](
                         _ =>
                           div(
                             source.render(0),
                             flash(notice) { value =>
                               if value == "initial" then
                                 div(first.render(1), retained.render(2))
                               else div(retained.render(3), added.render(4))
                             }
                           ),
                         model => RenderState(Map(notice.key -> FlashValue((if model == 0 then "initial" else "final"), 0)))
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before <- kernel.inspect
          sourceId = before.components.values.find(_.key.applicationId == "flash-source").get.id
          _     <- kernel.submitComponent(sourceId, 1)
          after <- kernel.inspect
        yield assertTrue(
          after.model == 1,
          after.components.roots.flatMap(after.components.get).map(_.key.applicationId) ==
            Vector("flash-source", "flash-retained", "flash-added"),
          after.components.values.find(_.key.applicationId == "flash-retained").exists(_.props == 3),
          after.components.values.forall(_.key.applicationId != "flash-first"),
          events.iterator().asScala.count(_ == "flash-source-callback") == 1
        )
      }
    },
    test("send-update journal is exact, last-wins, and does not retain absent targets") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val instance   = component(definition, "counter")
        val absent     = component(definition, "absent")
        for
          seen <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          componentLogic = logic(seen).copy(handle = (model, _) =>
                             ZIO.succeed(
                               TurnDraft(
                                 model,
                                 componentUpdates = Vector(
                                   ComponentUpdateRequest(definition, "counter", 5),
                                   ComponentUpdateRequest(absent.component, absent.id, 99),
                                   ComponentUpdateRequest(definition, "counter", 7)
                                 )
                               )
                             )
                           )
          kernel <- SessionKernel.start(config, componentLogic, program, Outbound(), FakeEnvironment(events))
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          after  <- kernel.inspect
        yield assertTrue(
          after.components.values.map(_.props) == Vector(7),
          after.components.values.size == 1
        )
      }
    },
    test("callback updates stabilize for self and earlier/later siblings without rerunning source") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            events.add("source-callback")
            ZIO.succeed(
              ComponentCallbackResult(
                model,
                draft.copy(componentUpdates = Vector(
                  ComponentUpdateRequest(definition, "earlier", 7),
                  ComponentUpdateRequest(definition, "source", 9),
                  ComponentUpdateRequest(definition, "later", 8),
                  ComponentUpdateRequest(definition, "source", 10)
                )),
                state
              )
            )
        val earlier = component(definition, "earlier")
        val source  = component(definition, "source")
        val later   = component(definition, "later")
        for
          seen <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](_ =>
                         div(earlier.render(1), source.render(2), later.render(3))
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before <- kernel.inspect
          sourceId = before.components.values.find(_.key.applicationId == "source").get.id
          _     <- kernel.submitComponent(sourceId, 1)
          after <- kernel.inspect
          props = after.components.values.map(value => value.key.applicationId -> value.props).toMap
        yield assertTrue(
          props == Map("earlier" -> 7, "source" -> 10, "later" -> 8),
          events.iterator().asScala.count(_ == "source-callback") == 1
        )
      }
    },
    test("parent callback update is applied to its nested child in the same candidate") {
      ZIO.scoped {
        val events          = ConcurrentLinkedQueue[String]()
        val childDefinition = componentDefinition(events)
        val child           = component(childDefinition, "child")
        val parentDefinition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
            ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(child.render(props))
        val parent = component(parentDefinition, "parent")
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            val updates =
              if component.asInstanceOf[AnyRef] eq parentDefinition.asInstanceOf[AnyRef] then
                Vector(ComponentUpdateRequest(child, 42))
              else Vector.empty
            ZIO.succeed(
              ComponentCallbackResult(model, draft.copy(componentUpdates = updates), state)
            )
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(parent.render(1))))
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before  <- kernel.inspect
          parentId = before.components.values.find(_.key.applicationId == "parent").get.id
          _      <- kernel.submitComponent(parentId, 1)
          after  <- kernel.inspect
          childProps = after.components.values.find(_.key.applicationId == "child").map(_.props)
        yield assertTrue(childProps.contains(42))
      }
    },
    test("nested component resource operations survive parent staging") {
      ZIO.scoped {
        val lifecycle       = LifecycleId(91L)
        val events          = ConcurrentLinkedQueue[String]()
        val childDefinition = componentDefinition(events)
        val child           = component(childDefinition, "resource-child")
        val parentDefinition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
            ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(child.render(1))
        val parent = component(parentDefinition, "resource-parent")
        val environment = new FakeEnvironment(events):
          override def update[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            if component.asInstanceOf[AnyRef] eq childDefinition.asInstanceOf[AnyRef] then
              ZIO.succeed(
                ComponentCallbackResult(
                  model,
                  draft.copy(resourceOperations = draft.resourceOperations :+ ResourceOperation.StartAsync(
                    OwnerId.Component(lifecycle, id),
                    AsyncKey[Int]("nested-resource"),
                    ZIO.never,
                    _ => 0
                  )),
                  state
                )
              )
            else super.update(id, component, props, model, state, draft)
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(parent.render(1))))
          kernel <- SessionKernel.start(
                      config,
                      logic(seen),
                      program,
                      Outbound(),
                      environment,
                      providedLifecycle = Some(lifecycle)
                    )
          committed <- kernel.inspect
        yield assertTrue(committed.managedResources.values.size == 1)
      }
    },
    test("nested child callback update is applied back to its already-staged parent") {
      ZIO.scoped {
        val events          = ConcurrentLinkedQueue[String]()
        val childDefinition = componentDefinition(events)
        val child           = component(childDefinition, "child-to-parent")
        val parentDefinition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message => ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) = div(child.render(1))
        val parent = component(parentDefinition, "parent-from-child")
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            val updates =
              if component.asInstanceOf[AnyRef] eq childDefinition.asInstanceOf[AnyRef] then
                Vector(ComponentUpdateRequest(parent, 5))
              else Vector.empty
            ZIO.succeed(ComponentCallbackResult(model, draft.copy(componentUpdates = updates), state))
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(parent.render(1))))
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before  <- kernel.inspect
          childId = before.components.values.find(_.key.applicationId == "child-to-parent").get.id
          _      <- kernel.submitComponent(childId, 1)
          after  <- kernel.inspect
          parentProps = after.components.values.find(_.key.applicationId == "parent-from-child").map(_.props)
        yield assertTrue(parentProps.contains(5))
      }
    },
    test("child update reconciles changed parent child topology, order, and props") {
      ZIO.scoped {
        val events             = ConcurrentLinkedQueue[String]()
        val sourceDefinition   = componentDefinition(events)
        val retainedDefinition = componentDefinition(events)
        val addedDefinition    = componentDefinition(events)
        val source             = component(sourceDefinition, "topology-source")
        val retained           = component(retainedDefinition, "topology-retained")
        val added              = component(addedDefinition, "topology-added")
        val parentDefinition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message => ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(
              props.map(_ == 1).when(div(source.render(props))),
              props.map(_ > 1).when(div(added.render(props))),
              retained.render(props)
            )
        val parent = component(parentDefinition, "topology-parent")
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            val updates =
              if component.asInstanceOf[AnyRef] eq sourceDefinition.asInstanceOf[AnyRef] then
                Vector(ComponentUpdateRequest(parent, 2))
              else Vector.empty
            ZIO.succeed(ComponentCallbackResult(model, draft.copy(componentUpdates = updates), state))
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(parent.render(1))))
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before  <- kernel.inspect
          sourceId = before.components.values.find(_.key.applicationId == "topology-source").get.id
          _      <- kernel.submitComponent(sourceId, 1)
          after  <- kernel.inspect
          parentComponent = after.components.values.find(_.key.applicationId == "topology-parent").get
          children = parentComponent.children.flatMap(after.components.get)
        yield assertTrue(
          children.map(_.key.applicationId) == Vector("topology-added", "topology-retained"),
          children.map(_.props) == Vector(2, 2),
          after.components.values.forall(_.key.applicationId != "topology-source")
        )
      }
    },
    test("an absent update is consumed and cannot affect a later mount") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val absent     = component(definition, "later-mount")
        for
          seen <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_ > 0).when(div(absent.render(1))))
                       )
                     )
          componentLogic = logic(seen).copy(handle = (model, message) =>
                             if message == 1 then
                               ZIO.succeed(
                                 TurnDraft(
                                   model,
                                   componentUpdates = Vector(ComponentUpdateRequest(absent, 99))
                                 )
                               )
                             else ZIO.succeed(TurnDraft(1))
                           )
          kernel <- SessionKernel.start(config, componentLogic, program, Outbound(), FakeEnvironment(events))
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          absentAfterUpdate <- kernel.inspect
          _                 <- kernel.submit(SessionCommand.Message(kernel.epoch, 2))
          mounted           <- kernel.inspect
        yield assertTrue(
          absentAfterUpdate.components.values.isEmpty,
          mounted.components.values.map(_.props) == Vector(1)
        )
      }
    },
    test("components remain dormant until destruction and revive with their state") {
      ZIO.scoped {
        for
          events <- ZIO.succeed(ConcurrentLinkedQueue[String]())
          seen   <- Ref.make(Vector.empty[Int])
          definition = componentDefinition(events)
          instance   = component(definition, "revived")
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_ % 2 == 0).when(div(instance.render(1))))
                       )
                     )
          kernel  <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before  <- kernel.inspect
          mounted  = before.components.values.head
          token    = mounted.ref.asInstanceOf[Object]
          _       <- kernel.submitComponent(mounted.id, 1)
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          dormant <- kernel.inspect
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          revived <- kernel.inspect
          _       <- kernel.destroyComponents(Vector(token))
          retained <- kernel.inspect
          _        <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _        <- kernel.destroyComponents(Vector(token))
          destroyed <- kernel.inspect
        yield assertTrue(
          dormant.components.values.isEmpty,
          dormant.components.allValues.map(_.id) == Vector(mounted.id),
          revived.components.values.head.id == mounted.id,
          revived.components.values.head.model == 2,
          retained.components.values.head.id == mounted.id,
          destroyed.components.allValues.isEmpty
        )
      }
    },
    test("a removed component handles browser events until the browser destroys it") {
      ZIO.scoped {
        for
          events <- ZIO.succeed(ConcurrentLinkedQueue[String]())
          seen   <- Ref.make(Vector.empty[Int])
          definition = componentDefinition(events)
          instance   = component(definition, "removed")
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_ % 2 == 0).when(div(instance.render(1))))
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          mounted = before.components.values.head
          binding = mounted.render.bindings.ids.head
          _ <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _ <- kernel.submit(
                 SessionCommand.ComponentClientEvent(
                   kernel.epoch,
                   mounted.id,
                   binding,
                   BindingPayload.Params(Map.empty)
                 )
               )
          dormant <- kernel.inspect
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          revived <- kernel.inspect
          shown    = revived.components.values.head
        yield assertTrue(
          dormant.components.values.isEmpty,
          dormant.components.allValues.map(_.model) == Vector(3),
          shown.id == mounted.id,
          shown.model == 3,
          textValues(shown.render.tree.root) == Vector("3")
        )
      }
    },
    test("a removed component's outputs reach its owner, even a removed parent") {
      ZIO.scoped {
        val events   = ConcurrentLinkedQueue[String]()
        val producer = component(outputDefinition, "removed-producer")
        val parentDefinition = new LiveComponent[Int, Int, Int]:
          def mount(props: Int, ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(props: Int, model: Int, ctx: MessageContext) = message =>
            ZIO.succeed(model + message)
          def view(props: Signal[Int], model: Signal[Int]) =
            div(producer.render(1, _ + 100))
        val parent = component(parentDefinition, "removed-parent")
        for
          seen <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_ % 2 == 0).when(div(parent.render(0))))
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          parentId = before.components.values.find(_.parent.isEmpty).get.id
          childId  = before.components.values.find(_.parent.nonEmpty).get.id
          _       <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _       <- kernel.submitComponent(childId, 5)
          dormant <- kernel.inspect
        yield assertTrue(
          dormant.components.values.isEmpty,
          dormant.components.get(childId).map(_.model) == Some(6),
          dormant.components.get(parentId).map(_.model) == Some(211)
        )
      }
    },
    test("a removed component's outputs to the page are delivered") {
      ZIO.scoped {
        for
          seen     <- Ref.make(Vector.empty[Int])
          events    = ConcurrentLinkedQueue[String]()
          instance  = component(outputDefinition, "removed-root-producer")
          program  <- ZIO.fromEither(
                        RenderProgram.compile[Int, Int](model =>
                          div(model.map(_ % 2 == 0).when(div(instance.render(1, _ + 100))))
                        )
                      )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          id      = before.components.values.head.id
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _      <- kernel.submitComponent(id, 5)
          _      <- kernel.inspect
          order  <- seen.get
        yield assertTrue(order == Vector(1, 105, 106))
      }
    },
    test("a browser event for a destroyed component gets an empty reply and changes nothing") {
      ZIO.scoped {
        for
          events <- ZIO.succeed(ConcurrentLinkedQueue[String]())
          seen   <- Ref.make(Vector.empty[Int])
          definition = componentDefinition(events)
          instance   = component(definition, "removed")
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(model.map(_ % 2 == 0).when(div(instance.render(1))))
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          mounted = before.components.values.head
          binding = mounted.render.bindings.ids.head
          click   = SessionCommand.ComponentClientEvent(
                    kernel.epoch,
                    mounted.id,
                    binding,
                    BindingPayload.Params(Map.empty)
                  )
          _         <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          dormant   <- kernel.inspect
          _         <- kernel.destroyComponents(Vector(mounted.ref.asInstanceOf[Object]))
          ignored   <- kernel.submit(click).either
          destroyed <- kernel.inspect
        yield assertTrue(
          ignored.exists(_.delta == RenderDelta.Empty),
          destroyed.components.allValues.isEmpty,
          destroyed.model == dormant.model,
          destroyed.revision == dormant.revision
        )
      }
    },
    test("non-stabilizing component update journals fail validation") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val instance   = component(definition, "cycle")
        val environment = new FakeEnvironment(events):
          override def update[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed(
              ComponentCallbackResult(
                model,
                draft.copy(componentUpdates = Vector(
                  ComponentUpdateRequest(definition, "cycle", props.asInstanceOf[Int] + 1)
                )),
                state
              )
            )
        for
          seen    <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          result  <- SessionKernel.start(config, logic(seen), program, Outbound(), environment).either
        yield assertTrue(result.left.exists {
          case SessionFailure.StageFailed(SessionStage.Validation, details) =>
            details.prettyPrint.contains("did not stabilize")
          case _ => false
        })
      }
    },
    test("component pushPatch preserves its candidate across mismatched acknowledgement") {
      ZIO.scoped {
        val destination = URL.decode("/component-patch").toOption.get
        val events      = ConcurrentLinkedQueue[String]()
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed(
              ComponentCallbackResult(
                (model.asInstanceOf[Int] + value.asInstanceOf[Int]).asInstanceOf[A],
                draft.copy(
                  model = draft.model + value.asInstanceOf[Int],
                  navigation = Some(NavigationRequest(destination, NavigationKind.PushPatch))
                ),
                state
              )
            )
        for
          seen       <- Ref.make(Vector.empty[Int])
          definition  = componentDefinition(events)
          instance    = component(definition, "counter")
          program    <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          patchLogic  = logic(seen).copy(handleParams = (model, url) =>
                          ZIO.succeed(TurnDraft(model * 10, url = Some(url)))
                        )
          kernel     <- SessionKernel.start(config, patchLogic, program, Outbound(), environment)
          mounted    <- kernel.inspect.map(_.components.values.head)
          _          <- kernel.submitComponent(mounted.id, 2)
          wrong       = URL.decode("/wrong").toOption.get
          mismatch   <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, wrong)).either
          pending    <- kernel.inspect
          _          <- kernel.submit(SessionCommand.ParamsPatch(kernel.epoch, destination))
          committed  <- kernel.inspect
        yield assertTrue(
          mismatch == Left(SessionRejection.MismatchedPatch(destination, wrong)),
          pending.model == 0,
          pending.components.get(mounted.id).exists(_.model == 1),
          committed.model == 20,
          committed.url == destination,
          committed.components.get(mounted.id).exists(_.model == 3)
        )
      }
    },
    test("component terminal redirect publishes and closes") {
      ZIO.scoped {
        val destination = URL.decode("/component-redirect").toOption.get
        val events      = ConcurrentLinkedQueue[String]()
        val environment = new FakeEnvironment(events):
          override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
            ZIO.succeed(
              ComponentCallbackResult(
                model,
                draft.copy(navigation = Some(NavigationRequest(destination, NavigationKind.Redirect))),
                state
              )
            )
        for
          seen       <- Ref.make(Vector.empty[Int])
          definition  = componentDefinition(events)
          instance    = component(definition, "counter")
          program    <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ => div(instance.render(1))))
          kernel     <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          mounted    <- kernel.inspect.map(_.components.values.head)
          result     <- kernel.submitComponent(mounted.id, 2)
          terminal   <- kernel.awaitTermination
        yield assertTrue(
          result.delta == RenderDelta.Empty,
          terminal match
            case SessionState.Redirected(_, navigation) =>
              navigation.kind == NavigationKind.Redirect && navigation.destination == destination
            case _ => false
        )
      }
    },
    test("commit-defect cleanup remains masked across shutdown and runs environment cleanup once") {
      ZIO.scoped {
        for
          events       <- ZIO.succeed(ConcurrentLinkedQueue[String]())
          closeEntered <- Promise.make[Nothing, Unit]
          releaseClose <- Promise.make[Nothing, Unit]
          closes       <- Ref.make(0)
          discards     <- Ref.make(0)
          reserves     <- Ref.make(0)
          seen         <- Ref.make(Vector.empty[Int])
          definition    = componentDefinition(events)
          first         = component(definition, "cleanup-first")
          second        = component(definition, "cleanup-second")
          environment = new FakeEnvironment(events):
            override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
              ZIO.succeed(
                ComponentCallbackResult(model, draft, ComponentEnvironmentState(new Object()))
              )
            override def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) =
              discards.update(_ + 1)
            override def close(id: ComponentInstanceId, state: ComponentEnvironmentState) =
              closeEntered.succeed(()).unit *> releaseClose.await *> closes.update(_ + 1)
          outbound = new OutboundReservations[SessionOutput]:
            def reserve = reserves.getAndUpdate(_ + 1).map { index =>
              new OutboundReservation[SessionOutput]:
                def publish(batch: OutboundBatch[SessionOutput]) =
                  if index == 1 then ZIO.dieMessage("component publication defect") else ZIO.unit
                def release = ZIO.unit
            }
            def awaitCapacity = ZIO.unit
            def take     = ZIO.fail(OutboundReservationError.Shutdown)
            def shutdown = ZIO.unit
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](_ => div(first.render(1), second.render(2)))
                     )
          kernel  <- SessionKernel.start(config, logic(seen), program, outbound, environment)
          target  <- kernel.inspect.map(_.components.values.head.id)
          turn    <- kernel.submitComponent(target, 1).either.fork
          _       <- closeEntered.await
          closing <- kernel.close.fork
          _       <- ZIO.yieldNow
          pending <- closing.poll
          _       <- releaseClose.succeed(())
          result  <- turn.join
          _       <- closing.join
          state   <- kernel.awaitTermination
          closeCount   <- closes.get
          discardCount <- discards.get
        yield assertTrue(
          pending.isEmpty,
          result.left.exists {
            case SessionRejection.SessionFailed(_: SessionFailure.CommitDefect) => true
            case _                                                              => false
          },
          state.isInstanceOf[SessionState.Crashed[?, ?]],
          closeCount == 2,
          discardCount == 1
        )
      }
    },
    test("active ownership is atomically taken and closed when shutdown races terminal paths") {
      enum ClosePath:
        case ActiveFailure, TerminalNavigation, NavigationCrash

      ZIO.foreach(ClosePath.values.toVector) { path =>
        ZIO.scoped {
          val destination = URL.decode("/ownership-race").toOption.get
          for
            events       <- ZIO.succeed(ConcurrentLinkedQueue[String]())
            closeEntered <- Promise.make[Nothing, Unit]
            releaseClose <- Promise.make[Nothing, Unit]
            closes       <- Ref.make(0)
            seen         <- Ref.make(Vector.empty[Int])
            definition    = componentDefinition(events)
            instance      = component(definition, s"ownership-$path")
            environment = new FakeEnvironment(events):
              override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
                val nextDraft = path match
                  case ClosePath.ActiveFailure => draft.copy(model = 1)
                  case ClosePath.TerminalNavigation =>
                    draft.copy(
                      navigation = Some(NavigationRequest(destination, NavigationKind.Redirect))
                    )
                  case ClosePath.NavigationCrash =>
                    draft.copy(
                      navigation = Some(NavigationRequest(destination, NavigationKind.PushPatch))
                    )
                // The component's model changes on the failure path, so that it renders and its
                // after-render hook, which fails, runs.
                val nextModel =
                  if path == ClosePath.ActiveFailure then (model.asInstanceOf[Int] + 1).asInstanceOf[A]
                  else model
                ZIO.succeed(ComponentCallbackResult(nextModel, nextDraft, state))
              override def afterRender[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
                if path == ClosePath.ActiveFailure && draft.model == 1 then
                  ZIO.fail(IllegalStateException("active ownership failure"))
                else ZIO.succeed(ComponentAfterRenderResult(draft, state))
              override def close(id: ComponentInstanceId, state: ComponentEnvironmentState) =
                closeEntered.succeed(()).unit *> releaseClose.await *> closes.update(_ + 1)
            program <- ZIO.fromEither(
                         RenderProgram.compile[Int, Int](_ => div(instance.render(1)))
                       )
            pathConfig =
              if path == ClosePath.NavigationCrash then
                SessionConfig.make(
                  4,
                  8,
                  8,
                  java.time.Duration.ofSeconds(1),
                  4
                ).toOption.get
              else config
            kernel  <- SessionKernel.start(pathConfig, logic(seen), program, Outbound(), environment)
            target  <- kernel.inspect.map(_.components.values.head.id)
            turn    <- kernel.submitComponent(target, 1).either.fork
            _ <- {
              if path == ClosePath.NavigationCrash then
                turn.join *> TestClock.adjust(2.seconds)
              else ZIO.unit
            }
            _       <- closeEntered.await
            closing <- kernel.close.fork
            _       <- ZIO.yieldNow
            pending <- closing.poll
            _       <- releaseClose.succeed(())
            _       <- turn.await
            _       <- closing.join
            terminal <- kernel.awaitTermination
            closeCount <- closes.get
          yield assertTrue(
            pending.isEmpty,
            closeCount == 1,
            terminal match
              case _: SessionState.Crashed[?, ?] => path != ClosePath.TerminalNavigation
              case _: SessionState.Redirected[?, ?] => path == ClosePath.TerminalNavigation
              case _ => false
          )
        }
      }.map(results => assertTrue(results.forall(_.isSuccess)))
    },
    test("acknowledged retirement attempts every cleanup after first and middle defects") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val first      = component(definition, "retire-first")
        val middle     = component(definition, "retire-middle")
        val last       = component(definition, "retire-last")
        for
          attempts <- Ref.make(Vector.empty[ComponentInstanceId])
          failing  <- Ref.make(Set.empty[ComponentInstanceId])
          seen     <- Ref.make(Vector.empty[Int])
          environment = new FakeEnvironment(events):
            override def close(id: ComponentInstanceId, state: ComponentEnvironmentState) =
              attempts.update(_ :+ id) *>
                failing.get.flatMap { ids =>
                  if ids.contains(id) then ZIO.dieMessage(s"retirement defect ${id.value}")
                  else ZIO.unit
                }
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](model =>
                         div(
                           model.map(_ == 0).when(
                             div(first.render(1), middle.render(2), last.render(3))
                           )
                         )
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), environment)
          before <- kernel.inspect
          ids = before.components.values.map(component =>
                   component.key.applicationId -> component.id
                 ).toMap
          tokens = before.components.values.map(_.ref.asInstanceOf[Object])
          _      <- failing.set(Set(ids("retire-first"), ids("retire-middle")))
          _      <- kernel.submit(SessionCommand.Message(kernel.epoch, 1))
          _      <- kernel.destroyComponents(tokens)
          state  <- kernel.awaitTermination
          closed <- attempts.get
          logs   <- ZTestLogger.logOutput
          errors = logs.filter(_.logLevel == LogLevel.Error)
        yield assertTrue(
          state match
            case SessionState.Crashed(
                  _,
                  SessionFailure.StageFailed(SessionStage.Retirement, details)
                ) =>
              details.prettyPrint.contains(ids("retire-first").value.toString) &&
                details.prettyPrint.contains(ids("retire-middle").value.toString)
            case _ => false,
          closed == Vector(ids("retire-first"), ids("retire-middle"), ids("retire-last")),
          errors.size == 1,
          errors.head.message() == "LiveView crashed",
          errors.head.annotations.get("stage").contains("retirement"),
          errors.head.cause.prettyPrint.contains("retirement defect")
        )
      }
    },
    test("full-forest sibling failure rolls back outputs and cleans every state exactly once") {
      ZIO.scoped {
        for
          events       <- ZIO.succeed(ConcurrentLinkedQueue[String]())
          closes       <- Ref.make(0)
          discards     <- Ref.make(0)
          publications <- Ref.make(0)
          seen         <- Ref.make(Vector.empty[Int])
          siblingDefinition = componentDefinition(events)
          producer           = component(outputDefinition, "rollback-producer")
          sibling            = component(siblingDefinition, "rollback-sibling")
          environment = new FakeEnvironment(events):
            override def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
              emit(value.asInstanceOf[O]) *>
                emit(value.asInstanceOf[Int].+(1).asInstanceOf[O]) *>
                ZIO.succeed(
                  ComponentCallbackResult(
                    (model.asInstanceOf[Int] + value.asInstanceOf[Int]).asInstanceOf[A],
                    draft.copy(model = 1),
                    ComponentEnvironmentState(new Object())
                  )
                )
            override def afterRender[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
              if (component.asInstanceOf[AnyRef] eq siblingDefinition.asInstanceOf[AnyRef]) && draft.model == 1 then
                ZIO.fail(IllegalStateException("sibling after-render failure"))
              else ZIO.succeed(ComponentAfterRenderResult(draft, state))
            override def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) =
              discards.update(_ + 1)
            override def close(id: ComponentInstanceId, state: ComponentEnvironmentState) =
              closes.update(_ + 1)
          outbound = new OutboundReservations[SessionOutput]:
            def reserve = ZIO.succeed(
              new OutboundReservation[SessionOutput]:
                def publish(batch: OutboundBatch[SessionOutput]) = publications.update(_ + 1)
                def release = ZIO.unit
            )
            def awaitCapacity = ZIO.unit
            def take     = ZIO.fail(OutboundReservationError.Shutdown)
            def shutdown = ZIO.unit
          program <- ZIO.fromEither(
                       // The sibling's props follow the root model, which the producer's message
                       // changes, so the sibling renders in that turn and its after-render fails.
                       RenderProgram.compile[Int, Int](model =>
                         div(producer.render(1, identity), sibling.render(model.map(_ + 2)))
                       )
                     )
          kernel <- SessionKernel.start(config, logic(seen), program, outbound, environment)
          before <- kernel.inspect
          producerId = before.components.values.find(_.key.applicationId == "rollback-producer").get.id
          failed      <- kernel.submitComponent(producerId, 5).either
          terminal    <- kernel.awaitTermination
          handled     <- seen.get
          published   <- publications.get
          closeCount  <- closes.get
          discardCount <- discards.get
        yield assertTrue(
          failed.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
          terminal.isInstanceOf[SessionState.Crashed[?, ?]],
          handled.isEmpty,
          published == 1,
          closeCount == 2,
          discardCount == 1,
          before.model == 0,
          before.components.values.map(_.model) == Vector(1, 2)
        )
      }
    },
    test("component outputs append FIFO continuations to the immediate root owner") {
      ZIO.scoped {
        for
          seen     <- Ref.make(Vector.empty[Int])
          events    = ConcurrentLinkedQueue[String]()
          instance  = component(outputDefinition, "producer")
          program  <- ZIO.fromEither(
                        RenderProgram.compile[Int, Int](_ => div(instance.render(1, _ + 100)))
                      )
          kernel <- SessionKernel.start(config, logic(seen), program, Outbound(), FakeEnvironment(events))
          before <- kernel.inspect
          id      = before.components.values.head.id
          _      <- kernel.submitComponent(id, 5)
          after  <- kernel.inspect
          order  <- seen.get
        yield assertTrue(order == Vector(105, 106), after.model == 211)
      }
    },
    test("component outputs participate in continuation capacity before commit") {
      ZIO.scoped {
        for
          seen     <- Ref.make(Vector.empty[Int])
          events    = ConcurrentLinkedQueue[String]()
          instance  = component(outputDefinition, "producer")
          program  <- ZIO.fromEither(
                        RenderProgram.compile[Int, Int](_ => div(instance.render(1, identity)))
                      )
          tiny      = SessionConfig.make(4, 1).toOption.get
          kernel   <- SessionKernel.start(tiny, logic(seen), program, Outbound(), FakeEnvironment(events))
          before   <- kernel.inspect
          failed   <- kernel.submitComponent(before.components.values.head.id, 5).either
          terminal <- kernel.awaitTermination
        yield assertTrue(
          failed.left.exists(_.isInstanceOf[SessionRejection.SessionFailed]),
          terminal.isInstanceOf[SessionState.Crashed[?, ?]],
          before.components.values.head.model == 1
        )
      }
    },
    test("duplicate exact component identities fail before publication") {
      ZIO.scoped {
        val events     = ConcurrentLinkedQueue[String]()
        val definition = componentDefinition(events)
        val instance   = component(definition, "same")
        for
          seen <- Ref.make(Vector.empty[Int])
          program <- ZIO.fromEither(
                       RenderProgram.compile[Int, Int](_ => div(instance.render(1), instance.render(2)))
                     )
          result <- SessionKernel
                      .start(config, logic(seen), program, Outbound(), FakeEnvironment(events)).either
        yield assertTrue(result.left.exists {
          case SessionFailure.StageFailed(SessionStage.Validation, details) =>
            details.prettyPrint.contains("duplicate component identity")
          case _ => false
        })
      }
    }
  ) @@ TestAspect.sequential
end ComponentKernelSpec
