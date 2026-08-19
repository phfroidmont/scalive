package scalive

import zio.test.*

object ZioHttpApiSpec extends ZIOSpecDefault:
  def spec = suite("ZIO HTTP public API")(
    test("requires an explicit validated config") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import java.time.Duration
        import scalive.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application = Live.router(live(View))
        val config = ZioHttpConfig(
          "01234567890123456789012345678901",
          Duration.ofMinutes(30),
          secureCookie = true
        ).toOption.get
        val routes: Routes[Any, Nothing] = ZioHttp.routes(application, config)
      """)

      assertTrue(errors.isEmpty)
    },
    test("does not expose a one-argument overload") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = LiveIO.succeed(())
          def view(model: Signal[Unit]) = div()

        ZioHttp.routes(Live.router(live(View)))
      """)

      assertTrue(errors.nonEmpty)
    }
  )
