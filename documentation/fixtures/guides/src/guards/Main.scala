package guards

import java.time.Duration

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  private val port = 8080

  private val notes = new Notes:
    def save(note: Note): Task[Unit] = ZIO.unit

  // docs:start guards-load
  private def serve(config: ZioHttpConfig): Task[Unit] =
    for
      clientAssets <- LiveViewClientAssets.load()
      appAssets    <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      guardAssets  <- NavigationGuardAssets.load()
      rootLayout  = RootLayout(clientAssets, appAssets, guardAssets)
      application = Live.router.withRootLayout(rootLayout)(
                      (live / "notes") -> NoteLiveView(notes)
                    )
      routes =
        ZioHttp.routes(application, config) ++
          clientAssets.routes ++
          appAssets.routes ++
          guardAssets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(port))
    yield ()
  // docs:end guards-load

  val run =
    ZIO
      .fromEither(
        ZioHttpConfig(
          signingSecret = "local-development-secret-change-me",
          sessionMaxAge = Duration.ofDays(7),
          secureCookie = false,
          allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost", port))
        )
      )
      .mapError(error => new IllegalArgumentException(error.toString))
      .flatMap(serve)
end Main
