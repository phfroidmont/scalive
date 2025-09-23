package scalive

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

final case class LiveRoute[A, Msg, Model](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[Msg, Model]):

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

class LiveChannel(private val sockets: SubscriptionRef[Map[String, Socket[?, ?]]]):
  def diffsStream: ZStream[Any, Nothing, (Payload, Meta)] =
    sockets.changes
      .map(m =>
        ZStream
          .mergeAllUnbounded()(m.values.map(_.outbox).toList*)
      ).flatMapParSwitch(1)(identity)

  def join[Msg, Model](
    id: String,
    token: String,
    lv: LiveView[Msg, Model],
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Unit] =
    sockets
      .updateZIO { m =>
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
      }.flatMap(_ => ZIO.logDebug(s"LiveView joined $id"))

  def leave(id: String): UIO[Unit] =
    sockets.updateZIO { m =>
      m.get(id) match
        case Some(socket) =>
          for
            _ <- socket.shutdown
            _ <- ZIO.logDebug(s"Left LiveView $id")
          yield m.removed(id)
        case None =>
          ZIO.logWarning(s"Tried to leave LiveView $id which doesn't exist").as(m)
    }

  def event(id: String, event: Payload.Event, meta: WebSocketMessage.Meta): UIO[Unit] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.inbox.offer(event -> meta).unit
        case None => ZIO.unit
    }

end LiveChannel

object LiveChannel:
  def make(): UIO[LiveChannel] =
    SubscriptionRef.make(Map.empty).map(new LiveChannel(_))

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?, ?]]):

  private val socketApp: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      ZIO
        .scoped(for
          liveChannel <- LiveChannel.make()
          _           <- liveChannel.diffsStream
                 .runForeach((payload, meta) =>
                   channel
                     .send(
                       Read(
                         WebSocketFrame.text(
                           WebSocketMessage(
                             joinRef = meta.joinRef,
                             messageRef = payload match
                               case Payload.Close => meta.joinRef
                               case _             => meta.messageRef,
                             topic = meta.topic,
                             eventType = payload match
                               case Payload.Diff(_) => "diff"
                               case Payload.Close   => "phx_close"
                               case _               => "phx_reply",
                             payload = payload
                           ).toJson
                         )
                       )
                     )
                 )
                 .tapErrorCause(c => ZIO.logErrorCause("diffsStream pipeline failed", c))
                 .ensuring(ZIO.logWarning("WS out fiber terminated"))
                 .fork
          _ <- channel
                 .receiveAll {
                   case Read(WebSocketFrame.Close) => ZIO.logDebug("WS connection closed by client")
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
                 }
        yield ()).tapErrorCause(ZIO.logErrorCause(_))

    }

  private def handleMessage(message: WebSocketMessage, liveChannel: LiveChannel)
    : RIO[Scope, Option[WebSocketMessage]] =
    message.payload match
      case Payload.Heartbeat => ZIO.succeed(Some(message.okReply))
      case Payload.Join(url, redirect, session, static, sticky) =>
        ZIO
          .fromEither(URL.decode(url.orElse(redirect).getOrElse(???))).flatMap(url =>
            val req = Request(url = url)
            liveRoutes.iterator
              .map(route =>
                route.path
                  .decode(req.path)
                  .toOption
                  .map(route.liveviewBuilder(_, req))
                  .map(
                    ZIO.logDebug(s"Joining LiveView ${route.path.toString} ${message.topic}") *>
                      liveChannel.join(message.topic, session, _, message.meta)
                  )
              )
              .collectFirst { case Some(join) => join.map(_ => None) }
              .getOrElse(ZIO.succeed(None))
          )
      case Payload.Leave =>
        liveChannel
          .leave(message.topic)
          .as(Some(message.okReply))
      case event: Payload.Event =>
        liveChannel
          .event(message.topic, event, message.meta)
          .map(_ => None)
      case Payload.Reply(_, _) => ZIO.die(new IllegalArgumentException())
      case Payload.Diff(_)     => ZIO.die(new IllegalArgumentException())
      case Payload.Close       => ZIO.die(new IllegalArgumentException())
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
