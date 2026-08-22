package scalive.docs

import java.time.Duration

import zio.*
import zio.http.*

import scalive.*
import scalive.docs.examples.Reports
import scalive.docs.xray.DocumentationTraceStore

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
                      "runtime-connected-turn.svg",
                      "runtime-ownership.svg",
                      "search-index.json"
                    )
                  )
                )
      transportConfig <- ZIO
                           .fromEither(
                             ZioHttpConfig(
                               signingSecret = sys.env.getOrElse(
                                 "SCALIVE_TOKEN_SECRET",
                                 "local-development-secret-change-me"
                               ),
                               sessionMaxAge = Duration.ofDays(7),
                               secureCookie = false
                             )
                           ).mapError(error => new IllegalArgumentException(error.toString))
      security = LiveSecurity(transportConfig)
      routes   = application.routes(assets, security, config, traceStore) ++ assets.routes
      _ <- Server
             .serve(routes)
             .provide(Server.defaultWithPort(config.serverPort), Reports.inMemory)
    yield ()
end DocumentationSite
