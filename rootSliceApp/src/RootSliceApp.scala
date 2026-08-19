import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import zio.*
import zio.http.*

import scalive.*
import scalive.LiveIO.given
import scalive.codecs.StringAsIsEncoder

object RootSliceApp extends ZIOAppDefault:
  private val defaultPort   = 8081
  private val mountSequence = AtomicInteger(0)
  private val config        = ZioHttpConfig(
    signingSecret = "root-slice-test-only-signing-secret-32-bytes",
    sessionMaxAge = Duration.ofMinutes(30),
    secureCookie = false
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private val serverPort =
    sys.env
      .get("SCALIVE_SERVER_PORT")
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)

  private val application = Live.router(
    live -> RootSliceLiveView(mountSequence)
  )

  private val supportRoutes = Routes(
    Method.GET / "health"      -> handler(Response.text("OK")),
    Method.GET / "favicon.ico" -> handler(Response(status = Status.NoContent)),
    Method.GET / "app.js"      -> handler {
      ZIO
        .attemptBlocking {
          val stream = Option(getClass.getClassLoader.getResourceAsStream("public/app.js"))
            .getOrElse(throw IllegalStateException("Missing bundled public/app.js"))
          try stream.readAllBytes()
          finally stream.close()
        }.fold(
          _ => Response.internalServerError,
          bytes =>
            Response(
              headers = Headers(Header.ContentType(MediaType.application.javascript)),
              body = Body.fromArray(bytes)
            )
        )
    }
  )

  override val run =
    Server
      .serve(ZioHttp.routes(application, config) ++ supportRoutes)
      .provide(Server.defaultWithPort(serverPort))
end RootSliceApp

final class RootSliceLiveView(mountSequence: AtomicInteger)
    extends LiveView[RootSliceLiveView.Msg.type, Int]:
  import RootSliceLiveView.*

  def mount(ctx: MountContext) = mountSequence.incrementAndGet()

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg => model + 1

  override def view(model: Signal[Int]) =
    val ariaLabel = htmlAttr("aria-label", StringAsIsEncoder)

    mainTag(
      h1("Root slice counter"),
      span(
        idAttr    := "counter-value",
        ariaLabel := "Counter value",
        model.map(_.toString)
      ),
      button(on.click(Msg), "Increment"),
      scriptTag(src := "/app.js")
    )

object RootSliceLiveView:
  case object Msg
