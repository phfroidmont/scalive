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
      Method.GET / "select"              -> liveHandler(SelectLiveView()),
      Method.GET / "keyed-comprehension" -> liveHandler(KeyedComprehensionLiveView()),
      (Method.GET / "navigation" / "a" -> liveHandler(NavigationALiveView())).session("navigation"),
      (Method.GET / "navigation" / "b" -> liveHandler(NavigationBLiveView())).session("navigation"),
      (Method.GET / "navigation" / "redirectloop" -> liveHandler(RedirectLoopLiveView()))
        .session("navigation"),
      Method.GET / "stream"                            -> liveHandler(StreamLiveView()),
      Method.GET / "stream" / "reset"                  -> liveHandler(StreamResetLiveView()),
      Method.GET / "stream" / "reset-lc"               -> liveHandler(StreamResetLCLiveView()),
      Method.GET / "stream" / "limit"                  -> liveHandler(StreamLimitLiveView()),
      Method.GET / "stream" / "nested-component-reset" -> liveHandler(
        StreamNestedComponentResetLiveView()
      ),
      Method.GET / "stream" / "inside-for"   -> liveHandler(StreamInsideForLiveView()),
      Method.GET / "healthy" / "fruits"      -> liveHandler(HealthyLiveView("fruits")),
      Method.GET / "healthy" / "veggies"     -> liveHandler(HealthyLiveView("veggies")),
      Method.GET / "components"              -> liveHandler(ComponentsLiveView()),
      Method.GET / "js"                      -> liveHandler(JsLiveView()),
      Method.GET / "colocated"               -> liveHandler(ColocatedLiveView()),
      Method.GET / "upload"                  -> liveHandler(UploadLiveView()),
      Method.GET / "form"                    -> liveHandler(FormLiveView()),
      Method.GET / "form" / "nested"         -> liveHandler(FormLiveView(nested = true)),
      Method.GET / "form" / "stream"         -> liveHandler(FormStreamLiveView()),
      Method.GET / "form" / "dynamic-inputs" -> liveHandler(FormDynamicInputsLiveView()),
      Method.GET / "form" / "feedback"       -> liveHandler(FormFeedbackLiveView()),
      Method.GET / "portal"                  -> liveHandler(PortalLiveView()),
      Method.GET / "errors" -> liveHandler(req => ErrorLiveView(req.headers.isEmpty))
    )

  private val healthRoutes =
    Routes(
      Method.GET / "health" -> handler(Response.text("OK")),
      Method.POST / "eval"  -> handler { (req: Request) =>
        req.body.asString.orDie
          .flatMap(E2ELatencyGate.releaseFromCode).as(
            Response(
              headers = Headers(Header.ContentType(MediaType.application.json)),
              body = Body.fromString("{\"result\":null}")
            )
          )
      },
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
              ),
              isRoot = false
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
