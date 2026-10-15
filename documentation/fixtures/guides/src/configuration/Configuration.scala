package configuration

import java.time.Duration

import quickstart.{CounterLiveView, Routes}
import zio.*
import zio.http.codec.PathCodec

import scalive.*

// docs:start configuration-load
object TransportConfig:
  val load: IO[String, ZioHttpConfig] =
    for
      secret     <- required("SCALIVE_TOKEN_SECRET")
      rawOrigins <- required("SCALIVE_ALLOWED_ORIGINS")
      origins    <- ZIO.fromEither(parseOrigins(rawOrigins))
      config     <- ZIO.fromEither(
                  ZioHttpConfig(
                    signingSecret = secret,
                    sessionMaxAge = Duration.ofDays(7),
                    secureCookie = true,
                    allowedWebSocketOrigins = origins
                  ).left.map(error => s"Invalid transport configuration: ${error.getMessage}")
                )
    yield config

  private def required(name: String): IO[String, String] =
    System.env(name).orDie.someOrFail(s"$name is required")

  // docs:start configuration-parse-origins
  def parseOrigins(value: String): Either[String, Set[WebSocketOrigin]] =
    val entries = value.split(",", -1).toVector.map(_.trim)
    entries.zipWithIndex.foldLeft[Either[String, Set[WebSocketOrigin]]](Right(Set.empty)) {
      case (parsed, (entry, index)) =>
        for
          origins <- parsed
          origin  <-
            WebSocketOrigin
              .parse(entry)
              .left.map(error => s"SCALIVE_ALLOWED_ORIGINS entry ${index + 1}: ${error.message}")
        yield origins + origin
    }
  // docs:end configuration-parse-origins
end TransportConfig
// docs:end configuration-load

object SocketPath:
  // docs:start configuration-socket-path
  val application = Live.router
    .withSocketPath(PathCodec.empty / "socket")(
      Routes.home -> CounterLiveView()
    )
  // docs:end configuration-socket-path

object Observers:
  // docs:start configuration-custom-observer
  val applicationObserver = LifecycleObserver.fromFunction { event =>
    ZIO.logAnnotate("lifecycle_event", event.name) {
      ZIO.logDebug("Scalive lifecycle event")
    }
  }
  // docs:end configuration-custom-observer

  def routes(application: LiveApplication[Any], config: ZioHttpConfig) =
    // docs:start configuration-metrics-observer
    ZioHttp.routes(application, config, LifecycleMetrics.observer)
    // docs:end configuration-metrics-observer

  def composedRoutes(application: LiveApplication[Any], config: ZioHttpConfig) =
    // docs:start configuration-composed-observer
    val observer = LifecycleMetrics.observer.andThen(applicationObserver)
    ZioHttp.routes(application, config, observer)
    // docs:end configuration-composed-observer
