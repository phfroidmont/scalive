package reports

import java.time.Duration

import quickstart.RootLayout
import zio.*
import zio.http.Server

import scalive.*

// docs:start services-route
object Routes:
  val reports = (live / "reports").from((reports: Reports) => ReportsLiveView(reports))
// docs:end services-route

// docs:start services-main
object Main extends ZIOAppDefault:
  private val port = 8080

  val run =
    for
      clientAssets <- LiveViewClientAssets.load()
      assets       <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      config       <- ZIO
                  .fromEither(
                    ZioHttpConfig(
                      signingSecret = sys.env.getOrElse(
                        "SCALIVE_TOKEN_SECRET",
                        "local-development-secret-change-me"
                      ),
                      sessionMaxAge = Duration.ofDays(7),
                      secureCookie = false,
                      allowedWebSocketOrigins = Set(
                        WebSocketOrigin.http("localhost", port),
                        WebSocketOrigin.http("127.0.0.1", port)
                      )
                    )
                  ).mapError(error => new IllegalArgumentException(error.toString))
      security    = LiveSecurity(config)
      application = Live.router
                      .withRootLayout(RootLayout(clientAssets, assets))(
                        Routes.reports
                      )
      liveRoutes = ZioHttp.routes(application, security)
      routes     = liveRoutes ++ clientAssets.routes ++ assets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port), Reports.inMemory)
    yield ()
end Main
// docs:end services-main
