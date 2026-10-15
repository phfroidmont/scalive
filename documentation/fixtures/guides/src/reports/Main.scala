package reports

import quickstart.RootLayout
import zio.*
import zio.http.Server

import scalive.*

// docs:start services-route
object Routes:
  val reports = (live / "reports").from(ZIO.serviceWith[Reports](ReportsLiveView(_)))
// docs:end services-route

// docs:start services-main
object Main extends ZIOAppDefault:
  private val port = 8080

  val run =
    for
      clientAssets <- LiveViewClientAssets.load()
      assets       <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      config       <- ZioHttpConfig.local(port)
      application = Live.router
                      .withRootLayout(RootLayout(clientAssets, assets))(
                        Routes.reports
                      )
      liveRoutes <- ZioHttp.routes(application, config)
      routes = liveRoutes ++ clientAssets.routes ++ assets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port), Reports.inMemory)
    yield ()
// docs:end services-main
