package scalive.docs

import zio.*
import zio.http.*

import scalive.*

object DocumentationSite extends ZIOAppDefault:
  override val run =
    for
      config <- ZIO
                  .fromEither(DocumentationConfig.fromEnvironment(sys.env))
                  .mapError(new IllegalArgumentException(_))
      bundle <- ZIO
                  .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                  .mapError(new IllegalStateException(_))
      application <- ZIO
                       .fromEither(DocumentationApplication.from(bundle))
                       .mapError(new IllegalStateException(_))
      assets <- StaticAssets.load(
                  StaticAssetConfig.classpath(
                    "public",
                    Seq("app.css", "app.js", "search-index.json")
                  )
                )
      security = LiveSecurity(TokenConfig.default, CookiePolicy(secure = false))
      routes   = application.routes(assets, security, config) ++ assets.routes
      _ <- Server
             .serve(routes)
             .provide(Server.defaultWithPort(config.serverPort))
    yield ()
