package scalive

import zio.test.*

object ZioHttpApiSpec extends ZIOSpecDefault:
  def spec = test("ZIO HTTP owns executable route assembly") {
    val errors = scala.compiletime.testing.typeCheckErrors("""
      import scalive.*
      import zio.http.Routes

      object View extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = LiveIO.succeed(())
        def view(model: Signal[Unit]) = div()

      val application = Live.router(live(View))
      val routes: Routes[Any, Nothing] = ZioHttp.routes(application)
    """)

    assertTrue(errors.isEmpty)
  }
