package scalive.docs

import zio.*
import zio.http.*

import scalive.*

object DocumentationSite extends ZIOAppDefault:
  private val defaultPort = 8080

  private val serverPort =
    sys.env
      .get("SCALIVE_SERVER_PORT")
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)

  override val run =
    for
      bundle <- ZIO
                  .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                  .mapError(new IllegalStateException(_))
      application <- ZIO
                       .fromEither(DocumentationApplication.from(bundle))
                       .mapError(new IllegalStateException(_))
      assets <- StaticAssets.load(
                  StaticAssetConfig.classpath("public", Seq("app.css", "app.js"))
                )
      security = LiveSecurity(TokenConfig.default, CookiePolicy(secure = false))
      routes   = application.routes(assets, security) ++ assets.routes
      _ <- Server
             .serve(routes)
             .provide(Server.defaultWithPort(serverPort))
    yield ()
