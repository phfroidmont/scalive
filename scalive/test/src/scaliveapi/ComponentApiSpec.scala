package scaliveapi

import zio.test.*

object ComponentApiSpec extends ZIOSpecDefault:

  override def spec = suite("ComponentApiSpec")(
    test("low-level rendered component construction is not a package helper") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val content = component(1, div("content"))
      """)

      assertTrue(errors.nonEmpty)
    },
    test("component event routing is not a package helper") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Unit]:
          object Msg

          def mount(props: Unit, ctx: MountContext) = LiveIO.succeed(())

          def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
            (_: Msg.type) => LiveIO.succeed(())

          def render(props: Unit, model: Unit, self: ComponentRef[Msg.type]) = div()

        val binding = component[CounterComponent.type](CounterComponent.Msg)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("component event routing is available on event bindings") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Unit]:
          object Msg

          def mount(props: Unit, ctx: MountContext) = LiveIO.succeed(())

          def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
            (_: Msg.type) => LiveIO.succeed(())

          def render(props: Unit, model: Unit, self: ComponentRef[Msg.type]) = div()

        val binding = scalive.on.click.toComponent(CounterComponent)(CounterComponent.Msg)
      """)

      assertTrue(errors.isEmpty)
    },
    test("component event routing rejects another component's message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Unit]:
          object Msg

          def mount(props: Unit, ctx: MountContext) = LiveIO.succeed(())

          def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
            (_: Msg.type) => LiveIO.succeed(())

          def render(props: Unit, model: Unit, self: ComponentRef[Msg.type]) = div()

        object OtherMsg
        val binding = scalive.on.click.toComponent(CounterComponent)(OtherMsg)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("component instances unify rendering, event routing, and updates") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object CounterComponent extends LiveComponent[String, CounterComponent.Msg.type, Int]:
          object Msg

          def mount(props: String, ctx: MountContext) = LiveIO.succeed(0)

          def handleMessage(props: String, model: Int, ctx: MessageContext) =
            (_: Msg.type) => LiveIO.succeed(model + 1)

          def render(props: String, model: Int, self: ComponentRef[Msg.type]) = div()

        val counter = component(CounterComponent, "counter")
        val rendered = counter.render("Counter")
        val binding = scalive.on.click.to(counter)(CounterComponent.Msg)

        def update(ctx: MessageContext[Unit, Unit]) =
          ctx.components.sendUpdate(counter, "Updated counter")
      """)

      assertTrue(errors.isEmpty)
    },
    test("component instances reject another component's message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object CounterComponent extends LiveComponent[Unit, CounterComponent.Msg.type, Unit]:
          object Msg

          def mount(props: Unit, ctx: MountContext) = LiveIO.succeed(())

          def handleMessage(props: Unit, model: Unit, ctx: MessageContext) =
            (_: Msg.type) => LiveIO.succeed(())

          def render(props: Unit, model: Unit, self: ComponentRef[Msg.type]) = div()

        object OtherMsg
        val counter = component(CounterComponent, "counter")
        val binding = scalive.on.click.to(counter)(OtherMsg)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("component instances reject another props type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object LabelComponent extends LiveComponent[String, Unit, Unit]:
          def mount(props: String, ctx: MountContext) = LiveIO.succeed(())

          def handleMessage(props: String, model: Unit, ctx: MessageContext) =
            (_: Unit) => LiveIO.succeed(())

          def render(props: String, model: Unit, self: ComponentRef[Unit]) = div()

        val label = component(LabelComponent, "label")

        def update(ctx: MessageContext[Unit, Unit]) =
          ctx.components.sendUpdate(label, 42)
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end ComponentApiSpec
