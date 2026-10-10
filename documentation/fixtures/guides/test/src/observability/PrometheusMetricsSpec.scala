package observability

import java.time.Duration

import quickstart.{CounterLiveView, Routes as AppRoutes}
import zio.*
import zio.http.{Request, URL}
import zio.test.{Live as _, *}

import scalive.*
import scalive.testing.*

object PrometheusMetricsSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private val application = Live.router(AppRoutes.home -> CounterLiveView())

  private val routes =
    ZioHttp.routes(application, config, LifecycleMetrics.observer) ++ MetricsRoutes.routes

  private def scrape =
    ZIO.scoped {
      for
        response <- routes.runZIO(Request.get(URL.decode("/metrics").toOption.get))
        body     <- response.body.asString
      yield body
    }

  def spec = suite("Prometheus metrics")(
    test("exports the disconnected render of a Live route") {
      for
        _      <- DisconnectedRender.run(routes, Request.get(URL.root))
        report <- scrape
                    .delay(200.millis)
                    .repeatUntil(_.contains("scalive_disconnected_render_total"))
                    .timeoutFail(AssertionError("No Scalive metric was exported."))(10.seconds)
      yield assertTrue(report.contains("scalive_disconnected_render_total"))
    }
  ).provide(PrometheusMetrics.layers) @@ TestAspect.withLiveClock
