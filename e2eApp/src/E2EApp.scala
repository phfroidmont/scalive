import zio.*
import zio.http.*
import zio.http.template.Html
import zio.logging.ConsoleLoggerConfig
import zio.logging.LogColor
import zio.logging.LogFilter
import zio.logging.LogFormat.*
import zio.logging.consoleLogger

import scalive.{label as _, *}

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
          ,
          "navigation"
        ),
        LiveRoute(
          Root / "navigation" / "b",
          (_, req) =>
            val container = req.url.queryParams.getAll("container").headOption.contains("1")
            NavigationBLiveView(container)
          ,
          "navigation"
        ),
        LiveRoute(
          Root / "navigation" / "redirectloop",
          (_, req) =>
            val loop = req.url.queryParams.getAll("loop").headOption.contains("true")
            RedirectLoopLiveView(loop)
          ,
          "navigation"
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
        ),
        LiveRoute(
          Root / "js",
          (_, _) => JsLiveView()
        ),
        LiveRoute(
          Root / "upload",
          (_, req) =>
            val autoUpload = req.url.queryParams.getAll("auto_upload").headOption.contains("1")
            new UploadLiveView(autoUpload)
        )
      )
    )

  private val healthRoutes =
    Routes(
      Method.GET / "health"   -> handler(Response.text("OK")),
      Method.GET / "download" -> handler { (req: Request) =>
        val maybeFile = req.url.queryParams.getAll("file").headOption
        maybeFile.flatMap(UploadLiveView.resolveUploadPath) match
          case Some(path) if java.nio.file.Files.exists(path) =>
            ZIO
              .attemptBlocking(java.nio.file.Files.readAllBytes(path))
              .map(bytes =>
                Response(
                  status = Status.Ok,
                  headers = Headers(
                    Header.ContentDisposition.attachment(path.getFileName.toString)
                  ),
                  body = Body.fromArray(bytes)
                )
              )
              .catchAll(_ => ZIO.succeed(Response.notFound))
          case _ => ZIO.succeed(Response.notFound)
      },
      Method.GET / "favicon.ico"         -> handler(Response(status = Status.NoContent)),
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
