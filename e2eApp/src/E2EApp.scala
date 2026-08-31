import zio.*
import zio.http.*
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

  def liveRoutes(rootLayout: E2ERootLayout, assets: StaticAssets) =
    Live.router.withRootLayout(rootLayout)(
      live / "select"              -> SelectLiveView(),
      E2ERoutes.keyedComprehension -> KeyedComprehensionLiveView(assets),
      Live.session("navigation")(
        E2ERoutes.navigationA            -> NavigationALiveView(),
        E2ERoutes.navigationB            -> NavigationBLiveView(),
        E2ERoutes.navigationBItemRoute   -> NavigationBLiveView(),
        E2ERoutes.navigationRedirectLoop -> RedirectLoopLiveView()
      ),
      E2ERoutes.stream                                                -> StreamLiveView(),
      (live / "stream" / "reset").queryOptional[String]("phx-remove") ->
        StreamResetLiveView(),
      live / "stream" / "reset-lc"                        -> StreamResetLCLiveView(),
      live / "stream" / "limit"                           -> StreamLimitLiveView(),
      live / "stream" / "nested-component-reset"          -> StreamNestedComponentResetLiveView(),
      live / "stream" / "inside-for"                      -> StreamInsideForLiveView(),
      E2ERoutes.healthy                                   -> HealthyLiveView(),
      E2ERoutes.components                                -> ComponentsLiveView(),
      live / "js"                                         -> JsLiveView(),
      live / "colocated"                                  -> ColocatedLiveView(),
      (live / "upload").query[UploadLiveView.QueryParams] -> UploadLiveView(),
      E2ERoutes.form                                      -> FormLiveView(),
      (live / "form" / "nested").paramsDecodeOnly(FormQueryParams.decoder) ->
        NestedFormLiveView(),
      live / "form" / "stream" -> FormStreamLiveView(),
      (live / "form" / "dynamic-inputs").paramsDecodeOnly(FormQueryParams.decoder) ->
        FormDynamicInputsLiveView(),
      live / "form" / "feedback"       -> FormFeedbackLiveView(),
      live / "form-unsaved"            -> FormUnsavedLiveView(),
      live / "form-unsaved" / "target" -> FormUnsavedTargetLiveView(),
      E2ERoutes.portal                 -> PortalLiveView(),
      (live / "errors").paramsDecodeOnly(ErrorLiveView.QueryParams.decoder) -> ErrorLiveView(),
      live / "issues" / "2965"                                              -> Issue2965LiveView(),
      live / "issues" / "2787"                                              -> Issue2787LiveView(),
      live / "issues" / "2835"                                              -> Issue2835LiveView(),
      live / "issues" / "3040"                                              -> Issue3040LiveView(),
      Live
        .session("issue-3047").withLayout(Issue3047LiveView.Layout)(
          E2ERoutes.issue3047A -> Issue3047LiveView(pageName = "A"),
          E2ERoutes.issue3047B -> Issue3047LiveView(pageName = "B")
        ),
      live / "issues" / "3026"          -> Issue3026LiveView(),
      live / "issues" / "3083"          -> Issue3083LiveView(),
      live / "issues" / "3117"          -> Issue3117LiveView(),
      live / "issues" / "3107"          -> Issue3107LiveView(),
      live / "issues" / "3169"          -> Issue3169LiveView(),
      live / "issues" / "3199"          -> Issue3199LiveView(away = false),
      live / "issues" / "3199" / "away" -> Issue3199LiveView(away = true),
      E2ERoutes.issue3194Other          -> Issue3194OtherLiveView(),
      live / "issues" / "3194"          -> Issue3194LiveView(),
      E2ERoutes.issue3200               -> Issue3200LiveView(),
      live / "issues" / "3319"          -> Issue3319LiveView(),
      live / "issues" / "3368"          -> Issue3368LiveView(),
      live / "issues" / "3391"          -> Issue3391LiveView(),
      live / "issues" / "3378"          -> Issue3378LiveView(),
      live / "issues" / "3448"          -> Issue3448LiveView(),
      live / "issues" / "3496" / "a"    -> Issue3496LiveView("A", includeStickyHook = true),
      E2ERoutes.issue3496B              -> Issue3496LiveView("B", includeStickyHook = false),
      E2ERoutes.issue3529               -> Issue3529LiveView(),
      E2ERoutes.issue3530               -> Issue3530LiveView(),
      E2ERoutes.issue3612A              -> Issue3612LiveView("A"),
      E2ERoutes.issue3612B              -> Issue3612LiveView("B"),
      live / "issues" / "3636"          -> Issue3636LiveView(),
      live / "issues" / "3647"          -> Issue3647LiveView(),
      live / "issues" / "3651"          -> Issue3651LiveView(),
      live / "issues" / "3656"          -> Issue3656LiveView(),
      live / "issues" / "3658"          -> Issue3658LiveView(),
      E2ERoutes.issue3681               -> Issue3681LiveView(onAway = false),
      E2ERoutes.issue3681Away           -> Issue3681LiveView(onAway = true),
      live / "issues" / "3684"          -> Issue3684LiveView(),
      E2ERoutes.issue3686A              -> Issue3686LiveView("A"),
      E2ERoutes.issue3686B              -> Issue3686LiveView("B"),
      E2ERoutes.issue3686C              -> Issue3686LiveView("C"),
      E2ERoutes.issue3709.params
        .mapParamsDecodeOnly(_ => Option.empty[String]) ->
        Issue3709LiveView(),
      E2ERoutes.issue3709Id.params
        .mapParamsDecodeOnly(id => Option(id.toString)) -> Issue3709LiveView(),
      live / "issues" / "3719"                          -> Issue3719LiveView(),
      live / "issues" / "3814"                          -> Issue3814LiveView(),
      live / "issues" / "3819"                          -> Issue3819LiveView(),
      live / "issues" / "3919"                          -> Issue3919LiveView(),
      live / "issues" / "3931"                          -> Issue3931LiveView(),
      live / "issues" / "3941"                          -> Issue3941LiveView(),
      live / "issues" / "3953"                          -> Issue3953LiveView(),
      live / "issues" / "3979"                          -> Issue3979LiveView(),
      (live / "issues" / "4027")
        .queryOptional[String]("case")
        .mapParams(caseName => Issue4027LiveView.QueryParams(caseName.getOrElse("first")))(params =>
          Some(params.caseName)
        ) ->
        Issue4027LiveView(),
      (live / "issues" / "4066")
        .queryOptional[Int]("delay")
        .mapParams(delay => Issue4066LiveView.QueryParams(delay.getOrElse(3000)))(params =>
          Some(params.delay)
        ) ->
        Issue4066LiveView(),
      live / "issues" / "4078"                                 -> Issue4078LiveView(),
      live / "issues" / "4088"                                 -> Issue4088LiveView(),
      E2ERoutes.issue4094                                      -> Issue4094LiveView(),
      live / "issues" / "4095"                                 -> Issue4095LiveView(),
      live / "issues" / "4102"                                 -> Issue4102LiveView(),
      live / "issues" / "4107"                                 -> Issue4107LiveView(),
      live / "issues" / "4121"                                 -> Issue4121LiveView(),
      live / "issues" / "4147"                                 -> Issue4147LiveView(),
      live / "issues" / "4209"                                 -> Issue4209LiveView(),
      live / "issues" / "4212"                                 -> Issue4212LiveView(),
      live / "issues" / "4290" / "a"                           -> Issue4290LiveView(page = "A"),
      live / "issues" / "4290" / "b"                           -> Issue4290LiveView(page = "B"),
      live / "issues" / "4323"                                 -> Issue4323LiveView(),
      live / "issues" / "4325"                                 -> Issue4325LiveView(),
      live / "issues" / "4334"                                 -> Issue4334LiveView(),
      live / "issues" / "4350"                                 -> Issue4350LiveView(),
      (live / "issues" / "4359").queryOptional[String]("done") -> Issue4359LiveView(),
      live / "issues" / "4368"                                 -> Issue4368LiveView()
    )

  private def healthRoutes(assets: StaticAssets) =
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
      Method.POST / "submit" -> handler { (req: Request) =>
        req.body.asString.orDie.map { body =>
          FormData
            .fromUrlEncoded(body).fold(
              _ => Response.badRequest,
              data =>
                val json = data.raw
                  .map { case (key, value) => s"\"$key\":\"$value\"" }
                  .mkString("{", ",", "}")

                Response.text(json)
            )
        }
      },
      Method.POST / "api" / "test" -> handler(Response.text("OK")),
      Method.GET / "download"      -> handler { (req: Request) =>
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
          s"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><script defer type="text/javascript" phx-track-static src="${assets
              .path("app.js")}"></script><link phx-track-static rel="stylesheet" href="${assets
              .path(
                "app.css"
              )}"><title>Scalive E2E</title></head><body><div><style>html, body { margin: 0; padding: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Ubuntu, "Helvetica Neue", sans-serif; font-size: 1rem; }</style><div style="display: flex; width: 100%; height: 100vh;"><div style="position: fixed; height: 100vh; background-color: #f8fafc; border-right: 1px solid; width: 20rem; display: flex; flex-direction: column; padding: 1rem; gap: 0.5rem;"><h1 style="margin-bottom: 1rem; font-size: 1.125rem; line-height: 1.75rem;">Navigation</h1><a href="${E2ERoutes.navigationA
              .location(
                NavigationLiveViews.AParams(None)
              ).href}" style="background-color: #f1f5f9; padding: 0.5rem;">LiveView A</a><a href="${E2ERoutes.navigationB
              .location(
                NavigationLiveViews.BParams(false)
              ).href}" style="background-color: #f1f5f9; padding: 0.5rem;">LiveView B</a><a href="${E2ERoutes.stream
              .location(
                None
              ).href}" style="background-color: #f1f5f9; padding: 0.5rem;">LiveView (other session)</a><a href="/navigation/dead" style="background-color: #f1f5f9; padding: 0.5rem;">Dead View</a></div><div style="margin-left: 22rem; flex: 1; padding: 2rem;"><h1>Dead view</h1></div></div></div><div id="root-portal"></div></body></html>"""
        )
      }
    )

  override val run =
    for
      assets <- StaticAssets.load(
                  StaticAssetConfig.classpath("public", Seq("app.css", "app.js", "daisy.css"))
                )
      navigationGuardAssets <- NavigationGuardAssets.load()
      config                <-
        ZIO
          .fromEither(
            ZioHttpConfig(
              signingSecret = sys.env.getOrElse(
                "SCALIVE_SIGNING_SECRET",
                "scalive-e2e-development-signing-secret"
              ),
              sessionMaxAge = java.time.Duration.ofHours(1),
              secureCookie = false,
              allowedWebSocketOrigins = Set(
                WebSocketOrigin.http("localhost", serverPort),
                WebSocketOrigin.http("127.0.0.1", serverPort)
              )
            )
          ).mapError(error => IllegalArgumentException(s"Invalid ZIO HTTP config: $error"))
      rootLayout  = new E2ERootLayout(assets, navigationGuardAssets)
      application = liveRoutes(rootLayout, assets)
      routes      =
        ZioHttp.routes(application, config) ++ healthRoutes(assets) ++ assets.routes ++
          navigationGuardAssets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(serverPort))
    yield ()
end E2EApp
