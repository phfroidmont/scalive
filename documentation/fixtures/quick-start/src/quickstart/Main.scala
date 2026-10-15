// docs:start quick-start-main
package quickstart

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  private val port        = 8080
  private val application = Live.router(Routes.home -> CounterLiveView())

  val run =
    for
      config <- ZioHttpConfig.local(port)
      routes <- ZioHttp.routes(application, config)
      _      <- Server.serve(routes).provide(Server.defaultWithPort(port))
    yield ()
// docs:end quick-start-main
