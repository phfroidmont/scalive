package scaliveapi

import zio.test.*

object EventlessApiSpec extends ZIOSpecDefault:

  override def spec = suite("EventlessApiSpec")(
    test("eventless LiveViews only require mount and render") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = LiveIO.succeed("mounted")
          def render(model: String) = div(model)

        val view: LiveView[Nothing, String] = View
        val route = (live / "eventless") -> View
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless routed LiveViews only require mount and render") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = LiveIO.succeed(s"mounted:$params")
          def render(model: String) = div(model)

        val view: LiveView.Routed[Nothing, String, Int] = View
        val route = (live / "eventless").query[Int]("id") -> View
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews can be routed from factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.Request

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = LiveIO.succeed("mounted")
          def render(model: String) = div(model)

        val requestRoute = live((_: Request) => View)
        val contextRoute = live.withLayout(LiveLayout[Unit, String]((content, _) => content)) {
          (_: Unit, _: Request, _: String) => View
        }
      """)

      assertTrue(errors.isEmpty)
    },
    test("routed eventless LiveViews can be routed from factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.Request

        object View extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = LiveIO.succeed(s"mounted:$params")
          def render(model: String) = div(model)

        val requestRoute = live.query[Int]("id")((_: Request) => View)
        val contextRoute = live
          .withLayout(LiveLayout[Unit, String]((content, _) => content))
          .query[Int]("id") { (_: Unit, _: Request, _: String) => View }
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews remain routable after widening") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Plain extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = LiveIO.succeed("mounted")
          def render(model: String) = div(model)

        object Routed extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = LiveIO.succeed(s"mounted:$params")
          def render(model: String) = div(model)

        val plain: LiveView[Nothing, String] = Plain
        val routed: LiveView.Routed[Nothing, String, Int] = Routed
        val plainRoute = live(plain)
        val routedRoute = live.query[Int]("id")(routed)
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews can be nested") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Child extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = LiveIO.succeed("mounted")
          def render(model: String) = div(model)

        object Parent extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def render(model: Unit) = div(liveView("child", Child))
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveComponents only require mount and render") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object Component extends LiveComponent.Eventless[String, Int]:
          def mount(props: String, ctx: MountContext) = LiveIO.succeed(0)
          def render(props: String, model: Int, self: ComponentRef[Nothing]) = div(props)

        val component: LiveComponent[String, Nothing, Int] = Component
      """)

      assertTrue(errors.isEmpty)
    },
    test("eventless LiveViews reject server event bindings") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def render(model: Unit) = button(scalive.on.click(()), "click")
      """)

      assertTrue(errors.nonEmpty)
    },
    test("ordinary LiveViews still require handleMessage") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView[String, Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def render(model: Unit) = div()
      """)

      assertTrue(errors.nonEmpty)
    },
    test("ordinary LiveViews still require a ClassTag when routing") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        def route[Msg, Model](view: LiveView[Msg, Model]) = live(view)
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end EventlessApiSpec
