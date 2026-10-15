package quickstart

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  private val port = 8080

  private val application = Live.router(
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

  val run =
    for
      config <- ZioHttpConfig.local(port)
      routes <- ZioHttp.routes(application, config)
      // docs:start learn-thermostat-provide
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port), Thermostat.simulated)
    // docs:end learn-thermostat-provide
    yield ()
