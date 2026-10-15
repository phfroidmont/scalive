// docs:start services-route-test
package reports

import java.time.Duration

import zio.*
import zio.http.{Request, URL}
import zio.test.{Live as _, *}

import scalive.*
import scalive.testing.*

object ReportsRouteSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(throw _, identity)

  private val testReports: ULayer[Reports] = ZLayer.succeed(
    new Reports:
      def recent: Task[Vector[Report]] = ZIO.succeed(Vector(Report(7L, "Route report")))
  )

  def spec = suite("Reports route")(
    test("builds the LiveView from the provided service") {
      val application = Live.router(Routes.reports)
      val request     = Request.get(URL.decode("/reports").toOption.get)
      ZIO
        .scoped {
          for
            client <- ConnectedRender.open(application, config, request)
            view   <- client.join
            report <- view.text("li")
          yield assertTrue(report == "Route report")
        }
        .provide(testReports)
    }
  )
// docs:end services-route-test
