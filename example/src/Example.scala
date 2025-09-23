import scalive.{label as _, *}
import zio.*
import zio.http.*
import zio.logging.ConsoleLoggerConfig
import zio.logging.LogColor
import zio.logging.LogFilter
import zio.logging.LogFormat.*
import zio.logging.consoleLogger

object Example extends ZIOAppDefault:

  private val logFormat =
    label("timestamp", timestamp.fixed(32)).color(LogColor.BLUE) |-|
      label("level", level.fixed(5)).highlight |-|
      label("thread", fiberId).color(LogColor.WHITE) |-|
      label("message", quoted(line)).highlight |-|
      cause

  val logFilter = LogFilter.LogLevelByNameConfig(LogLevel.Debug)

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> consoleLogger(ConsoleLoggerConfig(logFormat, logFilter))

  val liveRouter =
    LiveRouter(
      RootLayout(_),
      List(
        LiveRoute(
          Root,
          (_, _) => HomeLiveView()
        ),
        LiveRoute(
          Root / "counter",
          (_, _) => CounterLiveView()
        ),
        LiveRoute(
          Root / "list",
          (_, req) =>
            val q = req.queryParam("q").map("Param : " ++ _).getOrElse("No param")
            ListLiveView(q)
        ),
        LiveRoute(
          Root / "todo",
          (_, _) => TodoLiveView()
        )
      )
    )

  val routes = liveRouter.routes @@ Middleware.serveResources(Path.empty / "static", "public")

  override val run = Server.serve(routes).provide(Server.default)
end Example
