package scaliveapi

import zio.ZIO
import zio.test.*

object EventlessApiSpec extends ZIOSpecDefault:

  override def spec = suite("EventlessApiSpec")(
    test("eventless LiveViews only require mount and view") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          override def view(model: Signal[String]) = div(model)

        val view: LiveView[Nothing, String] = View
        val route = (live / "eventless") -> View
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless routed LiveViews only require mount and view") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View
            extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(s"mounted:$params")
          override def view(model: Signal[String]) = div(model)

        val view: LiveView.Routed[Nothing, String, Int] = View
        val route = (live / "eventless").query[Int]("id") -> View
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews can be routed from service and context factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        trait Service

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          override def view(model: Signal[String]) = div(model)

        val serviceRoute = live.from(ZIO.service[Service].as(View))
        val contextRoute = live.context((_: String) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("routed eventless LiveViews can be routed from service and context factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        trait Service

        object View
            extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(s"mounted:$params")
          override def view(model: Signal[String]) = div(model)

        val serviceRoute = live.query[Int]("id").from(ZIO.service[Service].as(View))
        val contextRoute = live.query[Int]("id").context((_: String) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews remain routable after widening") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Plain extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          override def view(model: Signal[String]) = div(model)

        object Routed
            extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(s"mounted:$params")
          override def view(model: Signal[String]) = div(model)

        val plain: LiveView[Nothing, String] = Plain
        val routed: LiveView.Routed[Nothing, String, Int] = Routed
        val plainRoute = live -> plain
        val routedRoute = live.query[Int]("id") -> routed
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews can be nested") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Child extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          override def view(model: Signal[String]) = div(model)

        object Parent extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          override def view(model: Signal[Unit]) = div(liveView("child", Child))
      """)

      assertTrue(errors.isEmpty)
    },
    test("portal content preserves its owner message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        enum Msg:
          case Clicked

        val content: HtmlElement[Msg] = portal(
          DomRef("portal-source"),
          target = DomSelector.css("#portal-target"),
          wrapperClass = Some("contents")
        )(button(on.click(Msg.Clicked), "click"))
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveComponents only require mount and view") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Component
            extends LiveComponent.Eventless[String, Int]:
          def mount(props: String, ctx: MountContext) = ZIO.succeed(0)
          override def view(
            props: Signal[String],
            model: Signal[Int],
          ) = div(props)

        val component: LiveComponent[String, Nothing, Int] = Component
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews reject server event bindings") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          override def view(model: Signal[Unit]) = button(scalive.on.click(()), "click")
      """)

      assertTrue(errors.nonEmpty)
    },
    test("ordinary LiveViews still require handleMessage") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView[String, Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          override def view(model: Signal[Unit]) = div()
      """)

      assertTrue(errors.nonEmpty)
    },
    test("routed LiveViews need the route's parameters; ordinary LiveViews fit any route") {
      val routedMismatchErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Routed.Eventless[Unit, Int]:
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application = Live.router(live -> View)
      """)
      val ordinaryOnQueryErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application = Live.router(live.query[Int]("id") -> View)
      """)

      assertTrue(routedMismatchErrors.nonEmpty, ordinaryOnQueryErrors.isEmpty)
    }
  )
end EventlessApiSpec
