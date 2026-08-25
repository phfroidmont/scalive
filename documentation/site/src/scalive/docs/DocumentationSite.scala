package scalive.docs

import java.time.Duration

import zio.*
import zio.http.*

import scalive.*
import scalive.docs.auth.{AuthService, PublicSessionId}
import scalive.docs.examples.Reports
import scalive.docs.xray.DocumentationTraceStore

object DocumentationSite extends ZIOAppDefault:
  private[docs] def healthRoutes(revision: String): Routes[Any, Nothing] =
    Routes(
      Method.GET / "health" -> handler(
        Response.text(revision).addHeader(Header.CacheControl.NoStore)
      )
    )

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
      traceStore <- DocumentationTraceStore.make()
      assets     <- StaticAssets.load(
                  StaticAssetConfig.classpath(
                    "public",
                    Seq(
                      "app.css",
                      "app.js",
                      "favicon.svg",
                      "fonts.css",
                      "instrument-sans-OFL.txt",
                      "jetbrains-mono-OFL.txt",
                      "runtime-connected-lifetime.svg",
                      "runtime-connected-turn.svg",
                      "runtime-disconnected-lifetime.svg",
                      "search-index.json"
                    )
                  )
                )
      transportConfig <- ZIO
                           .fromEither(
                             ZioHttpConfig(
                               signingSecret = config.signingSecret,
                               sessionMaxAge = Duration.ofDays(7),
                               secureCookie = config.secureCookie
                             )
                           ).mapError(error => new IllegalArgumentException(error.toString))
      security = LiveSecurity(transportConfig)
      revision = bundle.apiReference.metadata.revision
      routes   =
        application.routes(assets, security, config, traceStore) ++ healthRoutes(
          revision
        ) ++ assets.routes
      _ <- Server
             .serve(routes)
             .provide(
               Server.defaultWith(_.binding("127.0.0.1", config.serverPort)),
               Reports.inMemory,
               AuthService.live,
               LiveConnections.local[PublicSessionId]
             )
    yield ()
end DocumentationSite
