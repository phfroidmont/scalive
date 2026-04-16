import zio.*
import zio.http.*
import zio.logging.ConsoleLoggerConfig
import zio.logging.LogColor
import zio.logging.LogFilter
import zio.logging.LogFormat.*
import zio.logging.consoleLogger

import scalive.{label as _, *}
import zio.http.template.Html

object E2EApp extends ZIOAppDefault:

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

  val liveRouter =
    LiveRouter(
      E2ERootLayout(_),
      List(
        LiveRoute(
          Root / "select",
          (_, _) => SelectLiveView()
        ),
        LiveRoute(
          Root / "keyed-comprehension",
          (_, req) =>
            val tab =
              req.url.queryParams.getAll("tab").headOption.getOrElse("all_keyed")
            KeyedComprehensionLiveView(tab)
        ),
        LiveRoute(
          Root / "navigation" / "a",
          (_, req) =>
            val param = req.url.queryParams.getAll("param").headOption
            NavigationALiveView(param)
        ),
        LiveRoute(
          Root / "navigation" / "b",
          (_, req) =>
            val container = req.url.queryParams.getAll("container").headOption.contains("1")
            NavigationBLiveView(container)
        ),
        LiveRoute(
          Root / "navigation" / "redirectloop",
          (_, req) =>
            val loop = req.url.queryParams.getAll("loop").headOption.contains("true")
            RedirectLoopLiveView(loop)
        ),
        LiveRoute(
          Root / "stream",
          (_, _) => StreamLiveView()
        ),
        LiveRoute(
          Root / "components",
          (_, req) =>
            val tab = req.url.queryParams.getAll("tab").headOption.getOrElse("focus_wrap")
            ComponentsLiveView(tab)
        )
      )
    )

  private val healthRoutes =
    Routes(
      Method.GET / "health" -> handler(Response.text("OK")),
      Method.GET / "favicon.ico" -> handler(Response(status = Status.NoContent)),
      Method.GET / "navigation" / "dead" -> handler {
        Response.html(
          Html.raw(
            HtmlBuilder.build(
              E2ERootLayout(
                NavigationLayout(
                  h1("Dead view")
                )
              )
            )
          )
        )
      }
    )

  val routes =
    (liveRouter.routes ++ healthRoutes) @@
      ServeHashedResourcesMiddleware(Path.empty / "static", "public")

  override val run = Server.serve(routes).provide(Server.defaultWithPort(serverPort))
end E2EApp
