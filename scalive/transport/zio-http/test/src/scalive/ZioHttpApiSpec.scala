package scalive

import zio.test.*

object ZioHttpApiSpec extends ZIOSpecDefault:
  def spec = suite("ZIO HTTP public API")(
    test("requires an explicit validated config") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import java.time.Duration
        import scalive.*
        import zio.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        val application = Live.router(live(View))
        val config = ZioHttpConfig(
          "01234567890123456789012345678901",
          Duration.ofMinutes(30),
          secureCookie = true,
          allowedWebSocketOrigins = Set(WebSocketOrigin.https("example.com"))
        ).toOption.get
        val routes: Routes[Any, Nothing] = ZioHttp.routes(application, config)
      """)

      assertTrue(errors.isEmpty)
    },
    test("accepts one lifecycle observer for both transport phases") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import java.time.Duration
        import scalive.*
        import zio.*
        import zio.http.Routes

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.unit
          def view(model: Signal[Unit]) = div()

        val application = Live.router(live(View))
        val config = ZioHttpConfig(
          "01234567890123456789012345678901",
          Duration.ofMinutes(30),
          secureCookie = true,
          allowedWebSocketOrigins = Set(WebSocketOrigin.https("example.com"))
        ).toOption.get
        val observer = LifecycleObserver.fromFunction(_ => ZIO.unit)
        val direct: Routes[Any, Nothing] = ZioHttp.routes(application, config, observer)
        val shared: Routes[Any, Nothing] = ZioHttp.routes(application, LiveSecurity(config), observer)
      """)

      assertTrue(errors.isEmpty)
    },
    test("does not expose a one-argument overload") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*

        object View extends LiveView.Eventless[Unit]:
          def mount(ctx: MountContext) = ZIO.succeed(())
          def view(model: Signal[Unit]) = div()

        ZioHttp.routes(Live.router(live(View)))
      """)

      assertTrue(errors.nonEmpty)
    }
  )
