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
    LiveRoutes(
      layout = RootLayout(_)
    )(
      Method.GET / Root      -> liveHandler(HomeLiveView()),
      Method.GET / "counter" -> liveHandler(CounterLiveView()),
      Method.GET / "list"    ->
        liveHandler { req =>
          val param = req.queryParam("q").map(q => s"Param : $q").getOrElse("No param")
          ListLiveView(param)
        },
      Method.GET / "todo" -> liveHandler(TodoLiveView())
    )

  val routes =
    liveRoutes @@
      ServeHashedResourcesMiddleware(Path.empty / "static", "public")

  override val run = Server.serve(routes).provide(Server.defaultWithPort(serverPort))
end Example
