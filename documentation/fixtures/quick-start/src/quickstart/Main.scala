// docs:start quick-start-main
package quickstart

import java.time.Duration

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  val run =
    for
      assets <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      config <- ZIO
                  .fromEither(
                    ZioHttpConfig(
                      signingSecret = sys.env.getOrElse(
                        "SCALIVE_TOKEN_SECRET",
                        "local-development-secret-change-me"
                      ),
                      sessionMaxAge = Duration.ofDays(7),
                      secureCookie = false
                    )
                  ).mapError(error => new IllegalArgumentException(error.toString))
      security    = LiveSecurity(config)
      application = Live.router
                      .withRootLayout(RootLayout(assets))(
                        Routes.home -> CounterLiveView()
                      )
      liveRoutes = ZioHttp.routes(application, security)
      routes     = liveRoutes ++ assets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(8080))
    yield ()
// docs:end quick-start-main
