package scaliveapi

import zio.ZIO
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
          def mount(params: Int, ctx: MountContext) = ZIO.succeed(params.toString)
          def view(model: Signal[String]) = div(model)

        val route = (live / "items").query[Int]("id")(View)
        val location: LiveLocation = (live / "items").query[Int]("id").location(42)
      """)

      assertTrue(errors.isEmpty)
    },
    test("schema queries and parameter mappings retain their intended capabilities") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.codec.PathCodec
        import zio.schema.Schema
        import zio.schema.derived

        final case class Search(q: Option[String]) derives Schema
        final case class Page(value: Option[Int])

        object SearchView extends LiveView.Routed.Eventless[String, Search]:
          def mount(params: Search, ctx: MountContext) = ZIO.succeed("")
          def view(model: Signal[String]) = div(model)

        object PageView extends LiveView.Routed.Eventless[String, Page]:
          def mount(params: Page, ctx: MountContext) = ZIO.succeed("")
          def view(model: Signal[String]) = div(model)

        val search = (live / "search").query[Search]
        val searchRoute = search(SearchView)
        val searchLocation: LiveLocation = search.location(Search(Some("typed")))

        val page = (live / "page")
          .queryOptional[Int]("page")
          .mapParams(Page.apply)(_.value)
        val pageRoute = page(PageView)
        val pageLocation: LiveLocation = page.location(Page(Some(2)))

        val decoded = (live / "items" / PathCodec.int("id"))
          .params
          .mapParamsDecodeOnly(id => id.toString)

        val explicit = Live.route(PathCodec.empty / "explicit")
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
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
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
          def mount(ctx: MountContext) = ZIO.succeed(Combined("", 0))
          def view(model: Signal[Combined]) = div()

        val route: LiveRoute[Any, Unit] = live
          .withMountAspect(first)
          .withLayout(layout)
          .withMountAspect(second)
          .apply((_, _, context: Combined) => View)
      """)

      assertTrue(errors.isEmpty)
    },
    test("session aspect context reaches route factories") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val sessionAspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )

        object View extends LiveView.Eventless[String]:
          def mount(ctx: MountContext) = ZIO.succeed("mounted")
          def view(model: Signal[String]) = div(model)

        val session: LiveSession[Any] = Live.session("main")
          .withMountAspect(sessionAspect)(
            live.apply((_, _, context: String) => View)
          )
      """)

      assertTrue(errors.isEmpty)
    },
    test("session aspects run before route aspects in the typed pipeline") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        trait SessionEnvironment
        trait RouteEnvironment

        val sessionAspect = LiveMountAspect.fromRequest[SessionEnvironment, Any, String, String](
          _ => ZIO.succeed("session-claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )
        val routeAspect = LiveMountAspect.make[RouteEnvironment, Unit, String, Int, Int](
          (_, session) => ZIO.succeed(1 -> session.length),
          (_, _, session) => ZIO.succeed(session.length)
        )

        object View extends LiveView.Eventless[(String, Int)]:
          def mount(ctx: MountContext) = ZIO.succeed("" -> 0)
          def view(model: Signal[(String, Int)]) = div()

        val route = live
          .withMountAspect(routeAspect)
          .apply((_, _, context: (String, Int)) => View)

        val session: LiveSession[SessionEnvironment & RouteEnvironment] = Live.session("main")
          .withMountAspect(sessionAspect)(route)
      """)

      assertTrue(errors.isEmpty)
    },
    test("session layouts retain their earlier context projection") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val first = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("first-claim" -> "first"),
          (claim, _) => ZIO.succeed(claim)
        )
        val second = LiveMountAspect.make[Any, Any, String, Int, Int](
          (_, first) => ZIO.succeed(1 -> first.length),
          (_, _, first) => ZIO.succeed(first.length)
        )
        val layout = LiveLayout[Any, String]([Msg] => (content, context) =>
          if context.context.nonEmpty then content else content
        )

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("main")
          .withMountAspect(first)
          .withLayout(layout)
          .withMountAspect(second)(live(View))
      """)

      assertTrue(errors.isEmpty)
    },
    test("incompatible session and route contexts are rejected") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.json.*

        val sessionAspect = LiveMountAspect.fromRequest[Any, Any, String, String](
          _ => ZIO.succeed("claim" -> "session"),
          (claim, _) => ZIO.succeed(claim)
        )

        object View extends LiveView.Eventless[Int]:
          def mount(ctx: MountContext) = ZIO.succeed(0)
          def view(model: Signal[Int]) = div()

        val route = live.apply((_, _, context: Int) => View)
        val session = Live.session("main").withMountAspect(sessionAspect)(route)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("router assembly remains declarative") {
      val applicationErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val session: LiveSession[Any] = Live.session("main")(live(View))
        val application: LiveApplication[Any] =
          Live.router.withRootLayout(LiveRootLayout.identity)(session)
      """)
      val executableErrors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application: LiveApplication[Any] = Live.router(live(View))
        val executable: Routes[Any, Nothing] = application
      """)

      assertTrue(applicationErrors.isEmpty, executableErrors.nonEmpty)
    }
  )
