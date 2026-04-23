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

  val liveRoutes =
    LiveRoutes(
      layout = E2ERootLayout(_)
    )(
      Method.GET / "select" ->
        liveHandler(SelectLiveView()),
      Method.GET / "keyed-comprehension" ->
        liveHandler { req =>
          val tab = req.queryParamOrElse("tab", "all_keyed")
          KeyedComprehensionLiveView(tab)
        },
      (Method.GET / "navigation" / "a" ->
        liveHandler { req =>
          val param = req.queryParam("param")
          NavigationALiveView(param)
        }).session("navigation"),
      (Method.GET / "navigation" / "b" ->
        liveHandler { req =>
          val container = req.queryParam("container").contains("1")
          NavigationBLiveView(container)
        }).session("navigation"),
      (Method.GET / "navigation" / "redirectloop" ->
        liveHandler { req =>
          val loop = req.queryParam("loop").contains("true")
          RedirectLoopLiveView(loop)
        }).session("navigation"),
      Method.GET / "stream" ->
        liveHandler { req =>
          val extraItemWithId = req.queryParam("empty_item").isDefined
          StreamLiveView(extraItemWithId)
        },
      Method.GET / "stream" / "reset" ->
        liveHandler { req =>
          val usePhxRemove = req.queryParam("phx-remove").isDefined
          StreamResetLiveView(usePhxRemove)
        },
      Method.GET / "stream" / "reset-lc" ->
        liveHandler(StreamResetLCLiveView()),
      Method.GET / "stream" / "limit" ->
        liveHandler(StreamLimitLiveView()),
      Method.GET / "stream" / "nested-component-reset" ->
        liveHandler(StreamNestedComponentResetLiveView()),
      Method.GET / "stream" / "inside-for" ->
        liveHandler(StreamInsideForLiveView()),
      Method.GET / "healthy" / "fruits" ->
        liveHandler(HealthyLiveView("fruits")),
      Method.GET / "healthy" / "veggies" ->
        liveHandler(HealthyLiveView("veggies")),
      Method.GET / "components" ->
        liveHandler { req =>
          val tab = req.queryParamOrElse("tab", "focus_wrap")
          ComponentsLiveView(tab)
        },
      Method.GET / "js" ->
        liveHandler(JsLiveView()),
      Method.GET / "colocated" ->
        liveHandler(ColocatedLiveView()),
      Method.GET / "upload" ->
        liveHandler { req =>
          val autoUpload = req.queryParam("auto_upload").contains("1")
          UploadLiveView(autoUpload)
        }
    )

  private val healthRoutes =
    Routes(
      Method.GET / "health"   -> handler(Response.text("OK")),
      Method.GET / "download" -> handler { (req: Request) =>
        val maybeFile = req.queryParam("file")
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
    (liveRoutes ++ healthRoutes) @@
      ServeHashedResourcesMiddleware(Path.empty / "static", "public")

  override val run = Server.serve(routes).provide(Server.defaultWithPort(serverPort))
end E2EApp
