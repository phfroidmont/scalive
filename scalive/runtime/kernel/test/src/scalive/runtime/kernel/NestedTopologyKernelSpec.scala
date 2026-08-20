package scalive.runtime.kernel

import zio.*
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*

object NestedTopologyKernelSpec extends ZIOSpecDefault:
  private val config = SessionConfig.make(4, 8).toOption.get

  object Child extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  object NestedComponent extends LiveComponent[Unit, Unit, Unit]:
    def mount(props: Unit, ctx: MountContext) = ZIO.unit
    def handleMessage(props: Unit, model: Unit, ctx: MessageContext) = _ => ZIO.unit
    def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Unit]) =
      div(liveView("component-child", Child))

  private object Environment extends ComponentEnvironment[Int, Int]:
    def mount[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, draft: TurnDraft[Int, Int]) =
      ZIO.succeed(ComponentCallbackResult(props.asInstanceOf[A], draft, ComponentEnvironmentState(Object())))
    def update[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))
    def message[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, value: M, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))
    def async[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, event: LiveAsyncEvent[M], emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed(ComponentCallbackResult(model, draft, state))
    def browserEvent[P, M, A, O](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, command: SessionCommand.ComponentClientEvent, emit: O => Task[Unit], state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) = ZIO.none
    def afterRender[P, M, A](id: ComponentInstanceId, component: LiveComponent[P, M, A], props: P, model: A, state: ComponentEnvironmentState, draft: TurnDraft[Int, Int]) =
      ZIO.succeed(ComponentAfterRenderResult(draft, state))
    def discard(id: ComponentInstanceId, state: ComponentEnvironmentState) = ZIO.unit
    def close(id: ComponentInstanceId, state: ComponentEnvironmentState) = ZIO.unit

  private def program(factory: => LiveView[Nothing, Unit]) =
    ZIO.fromEither(
      RenderProgram.compile[Int, Int](_ => mainTag("before", liveView("child", factory), "after"))
    )

  private val logic = SessionLogic[Int, Int](
    bootstrap = ZIO.succeed(TurnDraft(0)),
    handle = (model, message) => ZIO.succeed(TurnDraft(model + message))
  )

  private final class Outbound(
    published: Ref[Vector[OutboundBatch[SessionOutput]]],
    events: Ref[Vector[String]])
      extends OutboundReservations[SessionOutput]:
    def reserve = ZIO.succeed(new OutboundReservation[SessionOutput]:
      def publish(batch: OutboundBatch[SessionOutput]) =
        events.update(_ :+ "publish") *> published.update(_ :+ batch)
      def release = ZIO.unit
    )
    def take = ZIO.fail(OutboundReservationError.Shutdown)
    def shutdown = ZIO.unit

  private def resolution(requirement: NestedLifecycleRequirement) =
    NestedRegistrationResolution(
      NestedRegistrationId.fresh().toOption.get,
      Object(),
      requirement.applicationId,
      "parent",
      NestedTopic(s"topic-${requirement.applicationId}"),
      NestedJoinCredential("join"),
      Some(NestedStaticCredential("static")),
      requirement.sticky,
      loading = false
    )

  private def topology(
    events: Ref[Vector[String]],
    activationEffect: UIO[Unit] = ZIO.unit,
    releaseEffect: UIO[Unit] = ZIO.unit
  ) = new NestedTopologyPreparer:
    def prepare(
      parentLifecycle: LifecycleId,
      parentEpoch: Epoch,
      parentRevision: TurnRevision,
      requirements: Vector[NestedLifecycleRequirement]
    ) =
      ZIO.succeed(new PreparedNestedTopology:
        val resolutions = requirements.map(resolution)
        def activate = events.update(_ :+ "activate") *> activationEffect
        def release  = releaseEffect
        def retire   = events.update(_ :+ "retire")
      )

  override def spec = suite("NestedTopologyKernelSpec")(
    test("prepares component declarations globally and commits their finalized nested output") {
      ZIO.scoped {
        for
          events    <- Ref.make(Vector.empty[String])
          published <- Ref.make(Vector.empty[OutboundBatch[SessionOutput]])
          seen      <- Ref.make(Vector.empty[String])
          render <- ZIO.fromEither(RenderProgram.compile[Int, Int](_ =>
                      mainTag(
                        liveView("root-child", Child),
                        component(NestedComponent, "component").render(())
                      )
                    ))
          preparer = new NestedTopologyPreparer:
                       def prepare(parentLifecycle: LifecycleId, parentEpoch: Epoch, parentRevision: TurnRevision, requirements: Vector[NestedLifecycleRequirement]) =
                         seen.set(requirements.map(_.applicationId)) *>
                           topology(events).prepare(parentLifecycle, parentEpoch, parentRevision, requirements)
          kernel <- SessionKernel.start(
                      config,
                      logic,
                      render,
                      Outbound(published, events),
                      Environment,
                      topologyPreparer = preparer
                    )
          committed <- kernel.inspect
          ids       <- seen.get
        yield assertTrue(
          ids == Vector("root-child", "component-child"),
          HtmlRenderer.render(committed.render.tree) ==
            "<main><div id=\"root-child\"></div><div><div id=\"component-child\"></div></div></main>"
        )
      }
    },
    test("defers factories, resolves exact output, activates before publication and retires after") {
      ZIO.scoped {
        var constructions = 0
        for
          events    <- Ref.make(Vector.empty[String])
          published <- Ref.make(Vector.empty[OutboundBatch[SessionOutput]])
          render    <- program({ constructions += 1; Child })
          kernel <- SessionKernel.start(
                      config,
                      logic,
                      render,
                      Outbound(published, events),
                      topologyPreparer = topology(events)
                    )
          committed <- kernel.inspect
          observed  <- events.get
          batches   <- published.get
        yield assertTrue(
          constructions == 0,
          HtmlRenderer.render(committed.render.tree) ==
            "<main>before<div id=\"child\"></div>after</main>",
          observed == Vector("activate", "publish", "retire"),
          batches.size == 1
        )
      }
    },
    test("typed preparation failure preserves state and publishes nothing") {
      ZIO.scoped {
        for
          events    <- Ref.make(Vector.empty[String])
          published <- Ref.make(Vector.empty[OutboundBatch[SessionOutput]])
          render    <- program(Child)
          preparer = new NestedTopologyPreparer:
                       def prepare(
                         parentLifecycle: LifecycleId,
                         parentEpoch: Epoch,
                         parentRevision: TurnRevision,
                         requirements: Vector[NestedLifecycleRequirement]
                       ) = ZIO.fail(NestedTopologyError.PreparationRejected("no lease"))
          started <- SessionKernel
                       .start(config, logic, render, Outbound(published, events),
                         topologyPreparer = preparer)
                       .either
          output <- published.get
        yield assertTrue(
          started.left.exists {
            case SessionFailure.StageFailed(SessionStage.TopologyPreparation, _) => true
            case _                                                               => false
          },
          output.isEmpty
        )
      }
    },
    test("failure after preparation releases the lease") {
      ZIO.scoped {
        for
          events    <- Ref.make(Vector.empty[String])
          releases  <- Ref.make(0)
          published <- Ref.make(Vector.empty[OutboundBatch[SessionOutput]])
          render    <- program(Child)
          failing = logic.copy(afterRender = _ => ZIO.fail(IllegalStateException("after prepare")))
          _ <- SessionKernel
                 .start(
                   config,
                   failing,
                   render,
                   Outbound(published, events),
                   topologyPreparer = topology(events, releaseEffect = releases.update(_ + 1))
                 ).either
          count  <- releases.get
          output <- published.get
        yield assertTrue(count == 1, output.isEmpty)
      }
    },
    test("activation defect is CommitDefect, releases the lease, and publishes nothing") {
      ZIO.scoped {
        for
          events    <- Ref.make(Vector.empty[String])
          releases  <- Ref.make(0)
          published <- Ref.make(Vector.empty[OutboundBatch[SessionOutput]])
          render    <- program(Child)
          started <- SessionKernel
                       .start(
                         config,
                         logic,
                         render,
                         Outbound(published, events),
                         topologyPreparer = topology(
                           events,
                           activationEffect = ZIO.die(RuntimeException("activation")),
                           releaseEffect = releases.update(_ + 1)
                         )
                       ).either
          count  <- releases.get
          output <- published.get
        yield assertTrue(
          started.left.exists(_.isInstanceOf[SessionFailure.CommitDefect]),
          count == 1,
          output.isEmpty
        )
      }
    }
  )
