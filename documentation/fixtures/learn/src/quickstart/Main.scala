package quickstart

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  private val port = 8080

  val run =
    for
      clientAssets <- LiveViewClientAssets.load()
      assets       <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      config       <- ZioHttpConfig.local(port)
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
                        Routes.thermostat.from(
                          ZIO.serviceWith[Thermostat](ThermostatLiveView(_))
                        ),
                        // docs:end learn-thermostat-registration
                        // docs:start learn-search-registration
                        Routes.search -> SearchLiveView()
                        // docs:end learn-search-registration
                      )
      liveRoutes <- ZioHttp.routes(application, config)
      routes = liveRoutes ++ clientAssets.routes ++ assets.routes
      // docs:start learn-thermostat-provide
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port), Thermostat.simulated)
    // docs:end learn-thermostat-provide
    yield ()
end Main
