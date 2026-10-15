package reports

import zio.*
import zio.http.Server

import scalive.*

// docs:start services-route
object Routes:
  val reports = (live / "reports").from(ZIO.serviceWith[Reports](ReportsLiveView(_)))
// docs:end services-route

// docs:start services-main
object Main extends ZIOAppDefault:
  private val port        = 8080
  private val application = Live.router(Routes.reports)

  val run =
    for
      config <- ZioHttpConfig.local(port)
      routes <- ZioHttp.routes(application, config)
      _      <- Server.serve(routes).provide(Server.defaultWithPort(port), Reports.inMemory)
    yield ()
// docs:end services-main
