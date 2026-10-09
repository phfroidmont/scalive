package quickstart

import scalive.*

object Routes:
  val home = live
  // docs:start learn-cart-route
  val cart = live / "cart"
  // docs:end learn-cart-route
  // docs:start learn-signup-route
  val signup = live / "signup"
  // docs:end learn-signup-route
  // docs:start learn-thermostat-route
  val thermostat = live / "thermostat"
  // docs:end learn-thermostat-route
  // docs:start learn-search-route
  val search = (live / "search").queryOptional[String]("q")
  // docs:end learn-search-route
