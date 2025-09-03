package scalive

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload
import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.*
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import java.util.Base64
import scala.util.Random

final case class LiveRoute[A, Event: JsonCodec](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[Event]):
  val eventCodec = JsonCodec[Event]

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

class LiveChannel(private val sockets: SubscriptionRef[Map[String, Socket[?]]]):
  def diffsStream: ZStream[Any, Nothing, (Diff, Meta)] =
    sockets.changes
      .map(m =>
        ZStream
          .mergeAllUnbounded()(
            m.values
              .map(_.outbox).map(ZStream.fromHub(_)).toList*
          )
      ).flatMapParSwitch(1, 1)(identity)

  def join[Event: JsonCodec](
    id: String,
    token: String,
    lv: LiveView[Event],
    meta: WebSocketMessage.Meta
  ): URIO[Scope, Unit] =
    sockets.updateZIO { m =>
      m.get(id) match
        case Some(socket) =>
          socket.shutdown *>
            Socket
              .start(id, token, lv, meta)
              .map(m.updated(id, _))
        case None =>
          Socket
            .start(id, token, lv, meta)
            .map(m.updated(id, _))

    }

  def event(id: String, value: String, meta: WebSocketMessage.Meta): UIO[Unit] =
    sockets.get.map { m =>
      m.get(id) match
        case Some(socket) =>
          socket.inbox
            .offer(
              value
                .fromJson(using socket.clientEventCodec.decoder)
                .getOrElse(throw new IllegalArgumentException())
                -> meta
            )
        case None => ZIO.unit
    }.unit

end LiveChannel

object LiveChannel:
  def make(): UIO[LiveChannel] =
    SubscriptionRef.make(Map.empty).map(new LiveChannel(_))

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?]]):

  private val socketApp: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      ZIO.scoped(for
        liveChannel <- LiveChannel.make()
        _           <- liveChannel.diffsStream
               .foreach((diff, meta) =>
                 channel.send(
                   Read(
                     WebSocketFrame.text(
                       WebSocketMessage(
                         meta.joinRef,
                         meta.messageRef,
                         meta.topic,
                         "phx_reply",
                         Payload.Reply("OK", LiveResponse.Diff(diff))
                       ).toJson
                     )
                   )
                 )
               ).fork
        _ <- channel
               .receiveAll {
                 case Read(WebSocketFrame.Text(content)) =>
                   for
                     message <- ZIO
                                  .fromEither(content.fromJson[WebSocketMessage])
                                  .mapError(new IllegalArgumentException(_))
                     reply <- handleMessage(message, liveChannel)
                     _     <- reply match
                            case Some(r) => channel.send(Read(WebSocketFrame.text(r.toJson)))
                            case None    => ZIO.unit
                   yield ()
                 case _ => ZIO.unit
               }.tapErrorCause(ZIO.logErrorCause(_))
      yield ())

    }

  private def handleMessage(message: WebSocketMessage, liveChannel: LiveChannel)
    : RIO[Scope, Option[WebSocketMessage]] =
    message.payload match
      case Payload.Heartbeat =>
        ZIO.succeed(
          Some(
            WebSocketMessage(
              message.joinRef,
              message.messageRef,
              message.topic,
              "phx_reply",
              Payload.Reply("ok", LiveResponse.Empty)
            )
          )
        )
      case Payload.Join(url, session, static, sticky) =>
        ZIO
          .fromEither(URL.decode(url)).flatMap(url =>
            val req = Request(url = url)
            liveRoutes
              .collectFirst { route =>
                val pathParams = route.path.decode(req.path).getOrElse(???)
                val lv         = route.liveviewBuilder(pathParams, req)
                liveChannel
                  .join(message.topic, session, lv, message.meta)(using route.eventCodec)
                  .map(_ => None)

              }.getOrElse(ZIO.succeed(None))
          )
      case Payload.Event(_, event, _) =>
        liveChannel
          .event(message.topic, event, message.meta)
          .map(_ => None)
      case Payload.Reply(_, _) => ZIO.die(new IllegalArgumentException())
    end match

  end handleMessage

  val routes: Routes[Any, Response] =
    Routes.fromIterable(
      liveRoutes
        .map(route => route.toZioRoute(rootLayout))
        .prepended(
          Method.GET / "live" / "websocket" -> handler(socketApp.toResponse)
        )
    )
end LiveRouter
