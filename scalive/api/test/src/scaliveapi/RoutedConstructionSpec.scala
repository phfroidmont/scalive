package scaliveapi

import zio.test.*

object RoutedConstructionSpec extends ZIOSpecDefault:
  def spec = suite("RoutedConstructionSpec")(
    test("routed definitions have no unrouted mount") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        def invalid[Msg, Model, Params](
          view: LiveView.Routed[Msg, Model, Params],
          ctx: MountContext[Msg, Model]
        ) = view.mount(ctx)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("parameter codecs and routed definitions are packaged together") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Routed.Eventless[String, Int]:
          def mount(params: Int, ctx: MountContext) = LiveIO.succeed(params.toString)
          def view(model: Signal[String]) = div(model)

        val route = (live / "items").query[Int]("id")(View)
        val location: LiveLocation = (live / "items").query[Int]("id").location(42)
      """)

      assertTrue(errors.isEmpty)
    },
    test("mount aspects supply typed context to route factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val aspect: LiveMountAspect[Any, Unit, Any, String, String] =
          LiveMountAspect.fromRequest[Any, Unit, String, String](
          _ => ZIO.succeed("claim" -> "disconnected"),
          (claim, _) => ZIO.succeed(s"connected:$claim")
          )

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = LiveIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        val route: LiveRoute[Any, Unit] =
          live
            .withMountAspect(aspect)
            .withLayout(LiveLayout[Unit, String]([Msg] => (content, _) => content))
            .apply((_, _, context: String) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("layouts cannot change the LiveView message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        val layout = LiveLayout[Unit, Unit]([Msg] =>
          (content, _) => div(on.click("wrong message"), content)
        )
      """)

      assertTrue(errors.nonEmpty)
    },
    test("custom context composition preserves earlier layout context") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        final case class Combined(first: String, second: Int)

        given ContextAppend[String, Int] with
          type Result = Combined
          def append(input: String, output: Int) = Combined(input, output)
          def left(result: Combined) = result.first

        val first = LiveMountAspect.fromRequest[Any, Unit, String, String](
          _ => ZIO.succeed("claim" -> "first"),
          (claim, _) => ZIO.succeed(claim)
        )
        val second = LiveMountAspect.make[Any, Unit, String, Int, Int](
          (_, input) => ZIO.succeed(1 -> input.length),
          (_, _, input) => ZIO.succeed(input.length)
        )
        val layout = LiveLayout[Unit, String]([Msg] => (content, _) => content)

        object View extends LiveView.Eventless[Combined]:
          def mount(ctx: MountContext) = LiveIO.succeed(Combined("", 0))
          def view(model: Signal[Combined]) = div()

        val route: LiveRoute[Any, Unit] = live
          .withMountAspect(first)
          .withLayout(layout)
          .withMountAspect(second)
          .apply((_, _, context: Combined) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("router assembly remains declarative") {
      val applicationErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("main")(live(View))
        val application: LiveApplication[Any] =
          Live.router.withRootLayout(LiveRootLayout.identity)(session)
      """)
      val executableErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application: LiveApplication[Any] = Live.router(live(View))
        val executable: Routes[Any, Nothing] = application
      """)

      assertTrue(applicationErrors.isEmpty, executableErrors.nonEmpty)
    }
  )
