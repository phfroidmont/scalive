package scalive

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

object LiveRoutesValidationSpec extends ZIOSpecDefault:

  private def view: LiveView[Unit, Unit] = new LiveView[Unit, Unit]:
    def mount = ZIO.unit
    def handleMessage(model: Unit) = _ => ZIO.unit
    def render(model: Unit): HtmlElement[Unit] = div()
    def subscriptions(model: Unit) = ZStream.empty

  override def spec = suite("LiveRoutesValidationSpec")(
    test("duplicate live route paths fail fast") {
      val result = scala.util.Try(
        scalive.Live.router(
          (scalive.live / "duplicate")(view),
          (scalive.live / "duplicate")(view)
        )
      )

      assertTrue(
        result.failed.toOption.exists(_.getMessage == "Duplicate LiveRoutes paths: /duplicate")
      )
    },
    test("duplicate live route paths fail across sessions") {
      val result = scala.util.Try(
        scalive.Live.router(
          scalive.Live.session("left")(
            (scalive.live / "session-duplicate")(view)
          ),
          scalive.Live.session("right")(
            (scalive.live / "session-duplicate")(view)
          )
        )
      )

      assertTrue(
        result.failed.toOption.exists(_.getMessage == "Duplicate LiveRoutes paths: /session-duplicate")
      )
    },
    test("duplicate LiveSession names fail fast") {
      val result = scala.util.Try(
        scalive.Live.router(
          scalive.Live.session("duplicate-session")(
            (scalive.live / "first")(view)
          ),
          scalive.Live.session("duplicate-session")(
            (scalive.live / "second")(view)
          )
        )
      )

      assertTrue(
        result.failed.toOption.exists(
          _.getMessage ==
            "Duplicate LiveSession names: duplicate-session. LiveSession routes must be declared in a single named group."
        )
      )
    },
    test("one LiveSession group can contain multiple routes") {
      val result = scala.util.Try(
        scalive.Live.router(
          scalive.Live.session("multi-route-session")(
            (scalive.live / "first")(view),
            (scalive.live / "second")(view)
          )
        )
      )

      assertTrue(result.isSuccess)
    },
    test("non-GET route patterns do not compile as live routes") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.*
        import zio.stream.ZStream

        def view: LiveView[Unit, Unit] = new LiveView[Unit, Unit]:
          def mount = ZIO.unit
          def handleMessage(model: Unit) = _ => ZIO.unit
          def render(model: Unit): HtmlElement[Unit] = div()
          def subscriptions(model: Unit) = ZStream.empty

        val routes = scalive.Live.router(
          scalive.live(Method.POST / "submit")(view)
        )
      """)

      assertTrue(errors.nonEmpty)
    },
    test("non-GET route patterns do not compile inside sessions") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import zio.*
        import zio.http.*
        import zio.stream.ZStream

        def view: LiveView[Unit, Unit] = new LiveView[Unit, Unit]:
          def mount = ZIO.unit
          def handleMessage(model: Unit) = _ => ZIO.unit
          def render(model: Unit): HtmlElement[Unit] = div()
          def subscriptions(model: Unit) = ZStream.empty

        val routes = scalive.Live.router(
          scalive.Live.session("admin")(
            scalive.live(Method.POST / "admin")(view)
          )
        )
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end LiveRoutesValidationSpec
