package scalive.examples

import zio.*
import zio.http.*
import zio.logging.ConsoleLoggerConfig
import zio.logging.LogColor
import zio.logging.LogFilter
import zio.logging.LogFormat.*
import zio.logging.consoleLogger

import scalive.examples.auth.*
import scalive.examples.collections.ActivityStreamLiveView
import scalive.examples.components.ComponentsLiveView
import scalive.examples.forms.ProfileFormLiveView
import scalive.examples.interop.BrowserInteropLiveView
import scalive.examples.lifecycle.NotificationsLiveView
import scalive.examples.navigation.SearchLiveView
import scalive.examples.processing.*
import scalive.examples.services.*
import scalive.examples.state.ShoppingCartLiveView
import scalive.examples.uploads.*
import scalive.{label as _, *}

object ExamplesApp extends ZIOAppDefault:

  private val defaultPort = 8080

  private val serverPort =
    sys.env
      .get("SCALIVE_SERVER_PORT")
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)

  private val authServiceConfig = AuthServiceConfig.default

  private val logFormat =
    label("timestamp", timestamp.fixed(32)).color(LogColor.BLUE) |-|
      label("level", level.fixed(5)).highlight |-|
      label("thread", fiberId).color(LogColor.WHITE) |-|
      label("message", quoted(line)).highlight |-|
      cause

  val logFilter = LogFilter.LogLevelByNameConfig(LogLevel.Debug)

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> consoleLogger(ConsoleLoggerConfig(logFormat, logFilter))

  def liveRoutes(assets: StaticAssets) =
    Live.router
      .withRootLayout(ExamplesRootLayout(assets))
      .withLayout(ExamplesLayout)(
        ExamplesRoutes.home           -> HomeLiveView(),
        ExamplesRoutes.shoppingCart   -> ShoppingCartLiveView(),
        ExamplesRoutes.guestbook      -> GuestbookLiveView.layer,
        ExamplesRoutes.subscriptions  -> ClockLiveView(),
        ExamplesRoutes.async          -> AsyncReportLiveView(),
        ExamplesRoutes.profileForm    -> ProfileFormLiveView(),
        ExamplesRoutes.documents      -> DocumentUploadLiveView.layer,
        ExamplesRoutes.search         -> SearchLiveView(),
        ExamplesRoutes.activity       -> ActivityStreamLiveView(),
        ExamplesRoutes.voting         -> ComponentsLiveView(),
        ExamplesRoutes.browserInterop -> BrowserInteropLiveView(),
        ExamplesRoutes.notifications  -> NotificationsLiveView(),
        Live
          .session("login").withMountAspect(LoginMountAspect.prepared)(
            ExamplesRoutes.login { (_, request, loginContext: LoginContext) =>
              LoginLiveView(
                loginContext,
                request.queryParam("invalid").contains("true")
              )
            }
          ),
        Live
          .session("authenticated").withMountAspect(AuthMountAspect.authenticated)(
            ExamplesRoutes.profile { (_, _, currentSession: CurrentSession) =>
              ProfileLiveView(currentSession)
            }
          )
      )

  override val run =
    for
      assets <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.css", "app.js")))
      authHttpConfig <- AuthHttpConfig.fromEnvironment(sys.env)
      authService    <- ZIO.service[AuthService].provide(AuthService.live(authServiceConfig))
      routes = liveRoutes(assets) ++
                 AuthHttpRoutes(authService, authHttpConfig).routes ++ assets.routes
      _ <- Server
             .serve(routes)
             .provide(
               Server.defaultWithPort(serverPort),
               ZLayer.succeed(authService),
               Guestbook.live,
               UploadStore.live
             )
    yield ()
end ExamplesApp
