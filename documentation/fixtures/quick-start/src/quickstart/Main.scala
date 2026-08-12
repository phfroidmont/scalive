package quickstart

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  override val run =
    for
      assets <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      security   = LiveSecurity(TokenConfig.default)
      liveRoutes = Live.router
                     .withSecurity(security)
                     .withRootLayout(RootLayout(assets))(
                       Routes.home -> CounterLiveView()
                     )
      routes = liveRoutes ++ assets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(8080))
    yield ()
