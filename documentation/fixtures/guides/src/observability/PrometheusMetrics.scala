package observability

import zio.*
import zio.http.*
import zio.metrics.connectors.MetricsConfig
import zio.metrics.connectors.prometheus
import zio.metrics.connectors.prometheus.PrometheusPublisher

import scalive.*

// docs:start observability-prometheus-route
object MetricsRoutes:
  val routes: Routes[PrometheusPublisher, Nothing] =
    Routes(
      Method.GET / "metrics" -> handler(
        ZIO.serviceWithZIO[PrometheusPublisher](_.get).map(Response.text)
      )
    )
// docs:end observability-prometheus-route

object PrometheusMetrics:
  // docs:start observability-prometheus-layers
  val layers: ULayer[PrometheusPublisher] =
    ZLayer.make[PrometheusPublisher](
      ZLayer.succeed(MetricsConfig(interval = 5.seconds)),
      prometheus.publisherLayer,
      prometheus.prometheusLayer
    )
  // docs:end observability-prometheus-layers

  def serve(application: LiveApplication[Any], config: ZioHttpConfig) =
    // docs:start observability-prometheus-serve
    for
      liveRoutes <- ZioHttp.routes(application, config, LifecycleMetrics.observer)
      _          <- Server
             .serve(liveRoutes ++ MetricsRoutes.routes)
             .provide(Server.defaultWithPort(8080), PrometheusMetrics.layers)
    yield ()
    // docs:end observability-prometheus-serve
