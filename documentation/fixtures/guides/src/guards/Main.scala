package guards

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  private val port = 8080

  private val notes = new Notes:
    def save(note: Note): Task[Unit] = ZIO.unit

  private val application = Live.router((live / "notes") -> NoteLiveView(notes))

  val run =
    for
      config <- ZioHttpConfig.local(port)
      routes <- ZioHttp.routes(application, config)
      _      <- Server.serve(routes).provide(Server.defaultWithPort(port))
    yield ()
