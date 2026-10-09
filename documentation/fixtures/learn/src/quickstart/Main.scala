package quickstart

import java.time.Duration

import zio.*
import zio.http.Server

import scalive.*

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
                        Routes.home -> CounterLiveView(),
                        // docs:start learn-cart-registration
                        Routes.cart -> CartLiveView(),
                        // docs:end learn-cart-registration
                        // docs:start learn-signup-registration
                        Routes.signup -> SignupLiveView(),
                        // docs:end learn-signup-registration
                        // docs:start learn-thermostat-registration
                        Routes.thermostat.from((thermostat: Thermostat) =>
                          ThermostatLiveView(thermostat)
                        ),
                        // docs:end learn-thermostat-registration
                        // docs:start learn-search-registration
                        Routes.search -> SearchLiveView()
                        // docs:end learn-search-registration
                      )
      liveRoutes = ZioHttp.routes(application, security)
      routes     = liveRoutes ++ clientAssets.routes ++ assets.routes
      // docs:start learn-thermostat-provide
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port), Thermostat.simulated)
    // docs:end learn-thermostat-provide
    yield ()
end Main
