// docs:start quick-start-main
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
                        Routes.home -> CounterLiveView()
                      )
      routes = ZioHttp.routes(application, config) ++ clientAssets.routes ++ assets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port))
    yield ()
// docs:end quick-start-main
