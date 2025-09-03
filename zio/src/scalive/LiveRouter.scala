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

final case class LiveRoute[A, Msg: JsonCodec, Model](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[Msg, Model]):
  val messageCodec = JsonCodec[Msg]

  def toZioRoute(rootLayout: HtmlElement => HtmlElement): Route[Any, Throwable] =
    Method.GET / path -> handler { (params: A, req: Request) =>
      val lv         = liveviewBuilder(params, req)
      val id: String =
        s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12))}"
      val token = Token.sign("secret", id, "")
      for
        initModel <- lv.init
        el = lv.view(Var(initModel))
        _  = el.syncAll()
      yield Response.html(
        Html.raw(
          HtmlBuilder.build(
            rootLayout(
              div(
                idAttr      := id,
                phx.main    := true,
                phx.session := token,
                el
              )
            )
          )
        )
      )
    }
end LiveRoute

class LiveChannel(private val sockets: SubscriptionRef[Map[String, Socket[?, ?]]]):
  def diffsStream: ZStream[Any, Nothing, (LiveResponse, Meta)] =
    sockets.changes
      .map(m =>
        ZStream
          .mergeAllUnbounded()(m.values.map(_.outbox).toList*)
      ).flatMapParSwitch(1, 1)(identity)

  def join[Msg: JsonCodec, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Unit] =
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
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.inbox
            .offer(
              value
                .fromJson(using socket.messageCodec.decoder)
                .getOrElse(throw new IllegalArgumentException())
                -> meta
            ).unit
        case None => ZIO.unit
    }

end LiveChannel

object LiveChannel:
  def make(): UIO[LiveChannel] =
    SubscriptionRef.make(Map.empty).map(new LiveChannel(_))

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?, ?]]):

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
                         Payload.Reply("ok", diff)
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
                  .join(message.topic, session, lv, message.meta)(using route.messageCodec)
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

  val routes: Routes[Any, Nothing] =
    Routes
      .fromIterable(
        liveRoutes
          .map(route => route.toZioRoute(rootLayout))
          .prepended(
            Method.GET / "live" / "websocket" -> handler(socketApp.toResponse)
          )
      ).handleErrorZIO(e =>
        ZIO.logErrorCause(Cause.die(e)).as(Response(status = Status.InternalServerError))
      )
end LiveRouter
