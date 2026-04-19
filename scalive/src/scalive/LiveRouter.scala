package scalive

import java.util.Base64
import scala.concurrent.duration.*
import scala.util.Random

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.*
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload

final case class LiveRoute[A, Msg, Model](
  path: PathCodec[A],
  liveviewBuilder: (A, Request) => LiveView[Msg, Model],
  sessionName: String = "default"):

  def toZioRoute(rootLayout: HtmlElement => HtmlElement): Route[Any, Throwable] =
    Method.GET / path -> handler { (params: A, req: Request) =>
      val lv         = liveviewBuilder(params, req)
      val id: String =
        s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12))}"
      val token = Token.sign("secret", id, sessionName)
      val ctx   = LiveContext(staticChanged = false)
      for
        initModel <- normalize(lv.init, ctx)
        el = lv.view(Var(initModel))
        _  = el.syncAll()
        _  = el.allocatePendingBindingIds()
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

class LiveChannel(
  private val sockets: SubscriptionRef[Map[String, Socket[?, ?]]],
  private val uploadOwners: Ref[Map[String, String]]):
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
    ctx: LiveContext,
    meta: WebSocketMessage.Meta
  ): RIO[Scope, Unit] =
    sockets
      .updateZIO { m =>
        m.get(id) match
          case Some(socket) =>
            socket.shutdown *>
              Socket
                .start(id, token, lv, ctx, meta)
                .map(m.updated(id, _))
          case None =>
            Socket
              .start(id, token, lv, ctx, meta)
              .map(m.updated(id, _))
      }.flatMap(_ => ZIO.logDebug(s"LiveView joined $id"))

  def leave(id: String): UIO[Unit] =
    for
      _ <- uploadOwners.update(_.filterNot { case (_, ownerId) => ownerId == id })
      _ <- sockets.updateZIO { m =>
             m.get(id) match
               case Some(socket) =>
                 for
                   _ <- socket.shutdown
                   _ <- ZIO.logDebug(s"Left LiveView $id")
                 yield m.removed(id)
               case None =>
                 ZIO.logDebug(s"Ignoring leave for unknown LiveView $id").as(m)
           }
    yield ()

  def event(id: String, event: Payload.Event, meta: WebSocketMessage.Meta): UIO[Unit] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.inbox.offer(event -> meta).unit
        case None => ZIO.unit
    }

  def livePatch(id: String, url: String, meta: WebSocketMessage.Meta): UIO[Unit] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) => socket.livePatch(url, meta).ignore
        case None         => ZIO.unit
    }

  def allowUpload(id: String, payload: Payload.AllowUpload): Task[Payload.Reply] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.allowUpload(payload).tap {
            case Payload.Reply(_, LiveResponse.UploadPreflightSuccess(_, _, entries, _)) =>
              uploadOwners
                .update(current => current ++ entries.keys.map(entryRef => s"lvu:$entryRef" -> id))
            case _ => ZIO.unit
          }
        case None =>
          ZIO.succeed(
            Payload.okReply(LiveResponse.UploadPreflightFailure(payload.ref, List.empty))
          )
    }

  def progressUpload(id: String, payload: Payload.Progress): Task[Payload.Reply] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) => socket.progressUpload(payload)
        case None         => ZIO.succeed(Payload.okReply(LiveResponse.Empty))
    }

  def uploadJoin(uploadTopic: String, token: String): Task[Payload.Reply] =
    uploadOwners.get.flatMap { owners =>
      owners.get(uploadTopic) match
        case Some(ownerId) =>
          sockets.get.flatMap { socketMap =>
            socketMap.get(ownerId) match
              case Some(socket) => socket.uploadJoin(uploadTopic, token)
              case None         =>
                ZIO.succeed(
                  Payload.errorReply(
                    LiveResponse.UploadJoinError(WebSocketMessage.UploadJoinErrorReason.Disallowed)
                  )
                )
          }
        case None =>
          ZIO.succeed(
            Payload.errorReply(
              LiveResponse.UploadJoinError(WebSocketMessage.UploadJoinErrorReason.InvalidToken)
            )
          )
    }

  def uploadChunk(uploadTopic: String, bytes: Chunk[Byte]): Task[Payload.Reply] =
    uploadOwners.get.flatMap { owners =>
      owners.get(uploadTopic) match
        case Some(ownerId) =>
          sockets.get.flatMap { socketMap =>
            socketMap.get(ownerId) match
              case Some(socket) => socket.uploadChunk(uploadTopic, bytes)
              case None         =>
                ZIO.succeed(
                  Payload.errorReply(
                    LiveResponse.UploadChunkError(
                      WebSocketMessage.UploadChunkErrorReason.Disallowed
                    )
                  )
                )
          }
        case None =>
          ZIO.succeed(
            Payload.errorReply(
              LiveResponse.UploadChunkError(WebSocketMessage.UploadChunkErrorReason.Disallowed)
            )
          )
    }

end LiveChannel

object LiveChannel:
  def make(): UIO[LiveChannel] =
    for
      sockets      <- SubscriptionRef.make(Map.empty[String, Socket[?, ?]])
      uploadOwners <- Ref.make(Map.empty[String, String])
    yield new LiveChannel(sockets, uploadOwners)

class LiveRouter(rootLayout: HtmlElement => HtmlElement, liveRoutes: List[LiveRoute[?, ?, ?]]):

  private val trackedStatic = StaticTracking.collect(rootLayout(div()))

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
                               case Payload.Diff(_)              => "diff"
                               case Payload.Close                => "phx_close"
                               case Payload.LiveNavigation(_, _) => "live_patch"
                               case Payload.Error                => "phx_error"
                               case _                            => "phx_reply",
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
                   case Read(WebSocketFrame.Binary(bytes)) =>
                     for
                       message <- ZIO
                                    .fromEither(WebSocketMessage.decodeBinaryPush(bytes))
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
      case Payload.Join(url, redirect, session, static, params, sticky) =>
        val clientStatics = static.orElse(StaticTracking.clientListFromParams(params))
        val ctx           = LiveContext(StaticTracking.staticChanged(clientStatics, trackedStatic))
        ZIO
          .fromEither(URL.decode(url.orElse(redirect).getOrElse(???))).flatMap(url =>
            val req = Request(url = url)
            liveRoutes.iterator
              .map(route =>
                route.path
                  .decode(req.path)
                  .toOption
                  .map(pathParams =>
                    if isAuthorizedJoin(route.sessionName, message.topic, session) then
                      val lv = route.liveviewBuilder(pathParams, req)
                      ZIO.logDebug(s"Joining LiveView ${route.path.toString} ${message.topic}") *>
                        liveChannel.join(message.topic, session, lv, ctx, message.meta).as(None)
                    else
                      ZIO.succeed(
                        Some(
                          WebSocketMessage(
                            message.joinRef,
                            message.messageRef,
                            message.topic,
                            "phx_reply",
                            Payload.errorReply(LiveResponse.JoinError(JoinErrorReason.Unauthorized))
                          )
                        )
                      )
                  )
              )
              .collectFirst { case Some(join) => join }
              .getOrElse(ZIO.succeed(None))
          )
      case Payload.Leave =>
        if message.topic.startsWith("lv:") && message.topic.length > 3 then
          liveChannel
            .leave(message.topic)
            .as(Some(message.okReply))
        else
          ZIO
            .logDebug(s"Ignoring leave for non-liveview topic ${message.topic}")
            .as(Some(message.okReply))
      case event: Payload.Event =>
        liveChannel
          .event(message.topic, event, message.meta)
          .map(_ => None)
      case allowUpload: Payload.AllowUpload =>
        liveChannel
          .allowUpload(message.topic, allowUpload).map(reply =>
            Some(
              WebSocketMessage(
                message.joinRef,
                message.messageRef,
                message.topic,
                "phx_reply",
                reply
              )
            )
          )
      case progress: Payload.Progress =>
        liveChannel
          .progressUpload(message.topic, progress).map(reply =>
            Some(
              WebSocketMessage(
                message.joinRef,
                message.messageRef,
                message.topic,
                "phx_reply",
                reply
              )
            )
          )
      case Payload.LivePatch(url) =>
        liveChannel.livePatch(message.topic, url, message.meta).as(Some(message.okReply))
      case Payload.UploadJoin(token) =>
        if message.topic.startsWith("lvu:") then
          liveChannel
            .uploadJoin(message.topic, token).map(reply =>
              Some(
                WebSocketMessage(
                  message.joinRef,
                  message.messageRef,
                  message.topic,
                  "phx_reply",
                  reply
                )
              )
            )
        else
          ZIO.succeed(
            Some(
              WebSocketMessage(
                message.joinRef,
                message.messageRef,
                message.topic,
                "phx_reply",
                Payload.errorReply(
                  LiveResponse.UploadJoinError(WebSocketMessage.UploadJoinErrorReason.Disallowed)
                )
              )
            )
          )
      case Payload.UploadChunk(bytes) =>
        if message.topic.startsWith("lvu:") then
          liveChannel
            .uploadChunk(message.topic, bytes).map(reply =>
              Some(
                WebSocketMessage(
                  message.joinRef,
                  message.messageRef,
                  message.topic,
                  "phx_reply",
                  reply
                )
              )
            )
        else
          ZIO.succeed(
            Some(
              WebSocketMessage(
                message.joinRef,
                message.messageRef,
                message.topic,
                "phx_reply",
                Payload.errorReply(
                  LiveResponse.UploadChunkError(WebSocketMessage.UploadChunkErrorReason.Disallowed)
                )
              )
            )
          )
      case Payload.LiveNavigation(_, _) => ZIO.die(new IllegalArgumentException())
      case Payload.Error                => ZIO.die(new IllegalArgumentException())
      case Payload.Reply(_, _)          => ZIO.die(new IllegalArgumentException())
      case Payload.Diff(_)              => ZIO.die(new IllegalArgumentException())
      case Payload.Close                => ZIO.die(new IllegalArgumentException())
    end match
  end handleMessage

  private def isAuthorizedJoin(expectedSession: String, topic: String, sessionToken: String)
    : Boolean =
    val topicId = topic.stripPrefix("lv:")
    Token
      .verify[String]("secret", sessionToken, 7.days)
      .toOption
      .exists { case (tokenTopic, tokenSession) =>
        (tokenTopic == topic || tokenTopic == topicId) && tokenSession == expectedSession
      }

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
