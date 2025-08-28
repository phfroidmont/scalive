package scalive

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.*

import java.util.Base64
import scala.collection.mutable
import scala.util.Random

final case class LiveRoute[A, ClientEvt: JsonCodec, ServerEvt](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[ClientEvt, ServerEvt]):
  val clientEventCodec = JsonCodec[ClientEvt]

  def toZioRoute(rootLayout: HtmlElement => HtmlElement): Route[Any, Nothing] =
    Method.GET / path -> handler { (params: A, req: Request) =>
      val lv         = liveviewBuilder(params, req)
      val id: String =
        s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12))}"
      val token = Token.sign("secret", id, "")
      lv.el.syncAll()
      Response.html(
        Html.raw(
          HtmlBuilder.build(
            rootLayout(
              div(
                idAttr      := id,
                phx.main    := true,
                phx.session := token,
                lv.el
              )
            )
          )
        )
      )
    }

class LiveChannel(semaphore: Semaphore):
  private val sockets: mutable.Map[String, Socket[?, ?]] = mutable.Map.empty

  // TODO should check id isn't already present
  def join[ClientEvt: JsonCodec](id: String, token: String, lv: LiveView[ClientEvt, ?]): UIO[Diff] =
    semaphore.withPermit {
      ZIO.succeed {
        val socket = Socket(id, token, lv)
        sockets.addOne(id, socket)
        socket.diff
      }
    }

  // TODO handle missing id
  def event(id: String, value: String): UIO[Diff] =
    semaphore.withPermit {
      ZIO.succeed {
        val s = sockets(id)
        s.lv.handleClientEvent(
          value
            .fromJson(using s.clientEventCodec.decoder).getOrElse(
              throw new IllegalArgumentException()
            )
        )
        s.diff
      }
    }

object LiveChannel:
  def make(): UIO[LiveChannel] =
    Semaphore.make(permits = 1).map(new LiveChannel(_))

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?, ?]]):

  private val socketApp: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      LiveChannel
        .make().flatMap(liveChannel =>
          channel
            .receiveAll {
              case Read(WebSocketFrame.Text(content)) =>
                for
                  message <- ZIO
                               .fromEither(content.fromJson[WebSocketMessage])
                               .mapError(new IllegalArgumentException(_))
                  reply <- handleMessage(message, liveChannel)
                  _     <- channel.send(Read(WebSocketFrame.text(reply.toJson)))
                yield ()
              case _ => ZIO.unit
            }.tapErrorCause(ZIO.logErrorCause(_))
        )
    }

  private def handleMessage(message: WebSocketMessage, liveChannel: LiveChannel)
    : Task[WebSocketMessage] =
    val reply = message.payload match
      case Payload.Heartbeat => ZIO.succeed(Payload.Reply("ok", LiveResponse.Empty))
      case Payload.Join(url, session, static, sticky) =>
        ZIO
          .fromEither(URL.decode(url)).flatMap(url =>
            val req = Request(url = url)
            liveRoutes
              .collectFirst { route =>
                val pathParams = route.path.decode(req.path).getOrElse(???)
                val lv         = route.liveviewBuilder(pathParams, req)
                liveChannel.join(message.topic, session, lv)(using route.clientEventCodec)

              }.getOrElse(???)
          ).map(diff => Payload.Reply("ok", LiveResponse.InitDiff(diff)))
      case Payload.Event(_, event, _) =>
        liveChannel
          .event(message.topic, event)
          .map(diff => Payload.Reply("ok", LiveResponse.Diff(diff)))
      case Payload.Reply(_, _) => ZIO.die(new IllegalArgumentException())

    reply.map(WebSocketMessage(message.joinRef, message.messageRef, message.topic, "phx_reply", _))

  val routes: Routes[Any, Response] =
    Routes.fromIterable(
      liveRoutes
        .map(route => route.toZioRoute(rootLayout))
        .prepended(
          Method.GET / "live" / "websocket" -> handler(socketApp.toResponse)
        )
    )
end LiveRouter
