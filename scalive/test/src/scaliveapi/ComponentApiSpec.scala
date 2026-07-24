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

        val binding = phx.onClick.toComponent(CounterComponent)(CounterComponent.Msg)
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
        val binding = phx.onClick.toComponent(CounterComponent)(OtherMsg)
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end ComponentApiSpec
