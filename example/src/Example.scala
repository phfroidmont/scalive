import zio.*
import zio.http.*
import zio.logging.ConsoleLoggerConfig
import zio.logging.LogColor
import zio.logging.LogFilter
import zio.logging.LogFormat.*
import zio.logging.consoleLogger

import scalive.{label as _, *}

object Example extends ZIOAppDefault:

  private val defaultPort = 8080

  private val serverPort =
    sys.env
      .get("SCALIVE_SERVER_PORT")
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)

  private val logFormat =
    label("timestamp", timestamp.fixed(32)).color(LogColor.BLUE) |-|
      label("level", level.fixed(5)).highlight |-|
      label("thread", fiberId).color(LogColor.WHITE) |-|
      label("message", quoted(line)).highlight |-|
      cause

  val logFilter = LogFilter.LogLevelByNameConfig(LogLevel.Debug)

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> consoleLogger(ConsoleLoggerConfig(logFormat, logFilter))

  val liveRoutes =
    (Live.router @@ RootLayout)(
      live             -> HomeLiveView(),
      live / "counter" -> CounterLiveView(),
      live / "list"    -> ListLiveView(),
      live / "todo"    -> TodoLiveView()
    )

  val routes =
    liveRoutes @@
      ServeHashedResourcesMiddleware(Path.empty / "static", "public")

  override val run = Server.serve(routes).provide(Server.defaultWithPort(serverPort))
end Example
