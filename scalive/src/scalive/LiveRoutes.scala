package scalive

import java.util.Base64
import scala.annotation.targetName
import scala.reflect.ClassTag
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
import scalive.WebSocketMessage.Protocol
import scalive.socket.SocketNavigationRuntime
import scalive.socket.SocketComponentRuntime
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketStreamRuntime
import scalive.socket.ComponentRuntimeState
import scalive.socket.FlashRuntimeState
import scalive.socket.StreamRuntimeState

final case class LiveRouteHandler[A, Msg, Model] private[scalive] (
  liveviewBuilder: (A, Request) => LiveView[Msg, Model]
)(using val msgClassTag: ClassTag[Msg])

object liveHandler:
  def apply[A, Msg: ClassTag, Model](
    f: (A, Request) => LiveView[Msg, Model]
  ): LiveRouteHandler[A, Msg, Model] =
    LiveRouteHandler(f)

  @targetName("applyRequest")
  def apply[Msg: ClassTag, Model](
    f: Request => LiveView[Msg, Model]
  ): LiveRouteHandler[Unit, Msg, Model] =
    LiveRouteHandler((_, req) => f(req))

  def apply[A, Msg: ClassTag, Model](
    f: A => LiveView[Msg, Model]
  ): LiveRouteHandler[A, Msg, Model] =
    LiveRouteHandler((params, _) => f(params))

  def apply[Msg: ClassTag, Model](
    view: => LiveView[Msg, Model]
  ): LiveRouteHandler[Unit, Msg, Model] =
    LiveRouteHandler((_, _) => view)

final case class LiveRoute[A, Msg, Model](
  pattern: RoutePattern[A],
  live: LiveRouteHandler[A, Msg, Model],
  sessionName: String = "default"):

  def session(name: String): LiveRoute[A, Msg, Model] =
    copy(sessionName = name)

  private[scalive] def toZioRoute(
    rootLayout: HtmlElement[?] => HtmlElement[?],
    tokenConfig: TokenConfig
  ): Route[Any, Throwable] =
    pattern -> handler { (params: A, req: Request) =>
      val lv         = live.liveviewBuilder(params, req)
      val id: String =
        s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12))}"
      val token    = Token.sign(tokenConfig.secret, id, sessionName)
      val response = for
        streamRef     <- Ref.make(StreamRuntimeState.empty)
        flashRef      <- Ref.make(FlashRuntimeState.empty)
        componentsRef <- Ref.make(ComponentRuntimeState.empty)
        navigationRef <- Ref.make(Option.empty[LiveNavigationCommand])
        ctx = LiveContext(
                staticChanged = false,
                streams = new SocketStreamRuntime(streamRef),
                navigation = new SocketNavigationRuntime(navigationRef),
                flash = new SocketFlashRuntime(flashRef),
                components = new scalive.socket.SocketComponentUpdateRuntime(componentsRef)
              )
        initModel <- LiveIO.toZIO(lv.mount).provide(ZLayer.succeed(ctx))
        lifecycle <- LiveRoute.runInitialHandleParams(
                       lv,
                       initModel,
                       req.url,
                       ctx,
                       navigationRef
                     )
        response <- lifecycle match
                      case LiveRoute.InitialLifecycleOutcome.Render(model) =>
                        val el = lv.render(model)
                        SocketComponentRuntime
                          .renderRoot(
                            rootLayout(
                              div(
                                idAttr      := id,
                                phx.main    := true,
                                phx.session := token,
                                el
                              )
                            ),
                            componentsRef,
                            ctx
                          ).map(rendered =>
                            Response.html(
                              Html.raw(
                                HtmlBuilder.build(
                                  rendered,
                                  isRoot = false
                                )
                              )
                            )
                          )
                      case LiveRoute.InitialLifecycleOutcome.Redirect(url) =>
                        ZIO.succeed(Response.redirect(url))
      yield response
      response.catchAllCause { cause =>
        ZIO.logErrorCause(cause) *>
          ZIO.succeed(Response.text("Internal Server Error").status(Status.InternalServerError))
      }
    }
end LiveRoute

object LiveRoute:
  enum InitialLifecycleOutcome[+Model]:
    case Render(model: Model)
    case Redirect(url: URL)

  private[scalive] def runInitialHandleParams[Msg, Model](
    lv: LiveView[Msg, Model],
    initModel: Model,
    url: URL,
    ctx: LiveContext,
    navigationRef: Ref[Option[LiveNavigationCommand]]
  ): Task[InitialLifecycleOutcome[Model]] =
    for
      _     <- navigationRef.set(None)
      model <- LiveIO
                 .toZIO(LiveViewParamsRuntime.runHandleParams(lv, initModel, url))
                 .provide(ZLayer.succeed(ctx))
      navigation <- navigationRef.getAndSet(None)
      result     <- navigation match
                  case None =>
                    ZIO.succeed(InitialLifecycleOutcome.Render(model))
                  case Some(command) =>
                    val destination = command match
                      case LiveNavigationCommand.PushPatch(to)    => to
                      case LiveNavigationCommand.ReplacePatch(to) => to
                    LivePatchUrl.resolve(destination, url) match
                      case Right(redirectUrl) =>
                        ZIO.succeed(InitialLifecycleOutcome.Redirect(redirectUrl))
                      case Left(error) =>
                        ZIO
                          .logWarning(
                            s"Could not decode initial navigation URL '$destination': $error"
                          )
                          .as(InitialLifecycleOutcome.Render(model))
    yield result
  end runInitialHandleParams
end LiveRoute

extension [A](pattern: RoutePattern[A])
  infix def ->[Msg, Model](handler: LiveRouteHandler[A, Msg, Model]): LiveRoute[A, Msg, Model] =
    LiveRoute(pattern, handler)

object LiveRoutes:
  def apply(
    layout: HtmlElement[?] => HtmlElement[?],
    mount: String = "live",
    tokenConfig: TokenConfig = TokenConfig.default
  )(
    routes: LiveRoute[?, ?, ?]*
  ): Routes[Any, Nothing] =
    new LiveRoutesRuntime(layout, routes.toList, websocketMountCodec(mount), tokenConfig).routes

  private[scalive] def websocketMountCodec(mount: String): PathCodec[Unit] =
    val segments =
      mount
        .split("/")
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toList
    require(segments.nonEmpty, "mount must contain at least one path segment")
    segments.foldLeft(PathCodec.empty: PathCodec[Unit])((codec, segment) => codec / segment)

final private[scalive] class LiveChannel(
  sockets: SubscriptionRef[Map[String, Socket[?, ?]]],
  uploadOwners: Ref[Map[String, String]],
  nestedEntries: Ref[Map[String, NestedLiveViewEntry]],
  tokenConfig: TokenConfig):
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
    meta: WebSocketMessage.Meta,
    initialUrl: URL
  )(using ClassTag[Msg]
  ): RIO[Scope, Unit] =
    sockets
      .updateZIO { m =>
        m.get(id) match
          case Some(socket) =>
            socket.shutdown *>
              Socket
                .start(id, token, lv, ctx, meta, tokenConfig, initialUrl)
                .map(m.updated(id, _))
          case None =>
            Socket
              .start(id, token, lv, ctx, meta, tokenConfig, initialUrl)
              .map(m.updated(id, _))
      }.flatMap(_ => ZIO.logDebug(s"LiveView joined $id"))

  def nestedRuntime(parentTopic: String): NestedLiveViewRuntime =
    new SocketNestedLiveViewRuntime(parentTopic, tokenConfig, nestedEntries)

  private[scalive] def nestedEntry(topic: String): UIO[Option[NestedLiveViewEntry]] =
    nestedEntries.get.map(_.get(topic))

  private[scalive] def socket(id: String): UIO[Option[Socket[?, ?]]] =
    sockets.get.map(_.get(id))

  def joinNested(
    topic: String,
    token: String,
    staticChanged: Boolean,
    meta: WebSocketMessage.Meta,
    initialUrl: URL
  ): RIO[Scope, Option[JoinErrorReason]] =
    nestedEntries.get.flatMap { entries =>
      entries.get(topic) match
        case Some(entry) if isAuthorizedNestedJoin(topic, token) =>
          val ctx = LiveContext(
            staticChanged = staticChanged,
            nestedLiveViews = nestedRuntime(topic)
          )
          sockets
            .updateZIO { m =>
              m.get(topic) match
                case Some(socket) =>
                  socket.shutdown *>
                    entry.start(ctx, meta, initialUrl).map(socket => m.updated(topic, socket))
                case None =>
                  entry.start(ctx, meta, initialUrl).map(socket => m.updated(topic, socket))
            }
            .as(None)
        case Some(_) =>
          ZIO.succeed(Some(JoinErrorReason.Unauthorized))
        case None =>
          ZIO.succeed(None)
    }

  private def isAuthorizedNestedJoin(topic: String, token: String): Boolean =
    Token
      .verify[String](tokenConfig.secret, token, tokenConfig.maxAge)
      .toOption
      .exists { case (tokenTopic, payload) => tokenTopic == topic && payload == "nested" }

  def leave(id: String): UIO[Unit] =
    for
      childIds <- nestedEntries.modify { entries =>
                    val children = entries.collect {
                      case (topic, entry) if entry.parentTopic == id => topic
                    }.toSet
                    (children, entries -- children - id)
                  }
      leavingIds = childIds + id
      _ <- uploadOwners.update(_.filterNot { case (_, ownerId) => leavingIds.contains(ownerId) })
      _ <- sockets.updateZIO { m =>
             val children     = childIds.flatMap(m.get)
             val stopChildren = ZIO.foreachDiscard(children)(_.shutdown)
             m.get(id) match
               case Some(socket) =>
                 for
                   _ <- stopChildren
                   _ <- socket.shutdown
                   _ <- ZIO.logDebug(s"Left LiveView $id")
                 yield m -- childIds - id
               case None =>
                 stopChildren *>
                   ZIO.logDebug(s"Ignoring leave for unknown LiveView $id").as(m -- childIds)
           }
    yield ()

  def event(id: String, event: Payload.Event, meta: WebSocketMessage.Meta): UIO[Unit] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) =>
          socket.inbox.offer(event -> meta).unit
        case None => ZIO.unit
    }

  def livePatch(id: String, url: String, meta: WebSocketMessage.Meta): Task[Payload.Reply] =
    sockets.get.flatMap { m =>
      m.get(id) match
        case Some(socket) => socket.livePatch(url, meta)
        case None         => ZIO.succeed(Payload.okReply(LiveResponse.Empty))
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
  def make(tokenConfig: TokenConfig): UIO[LiveChannel] =
    for
      sockets      <- SubscriptionRef.make(Map.empty[String, Socket[?, ?]])
      uploadOwners <- Ref.make(Map.empty[String, String])
      nested       <- Ref.make(Map.empty[String, NestedLiveViewEntry])
    yield new LiveChannel(sockets, uploadOwners, nested, tokenConfig)

final private class LiveRoutesRuntime(
  rootLayout: HtmlElement[?] => HtmlElement[?],
  liveRoutes: List[LiveRoute[?, ?, ?]],
  websocketMount: PathCodec[Unit],
  tokenConfig: TokenConfig):

  private val trackedStatic = StaticTracking.collect(rootLayout(div()))

  private val socketApp: WebSocketApp[Any] =
    Handler.webSocket { channel =>
      ZIO
        .scoped(for
          liveChannel <- LiveChannel.make(tokenConfig)
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
                             eventType = outgoingEventType(payload),
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
      case Payload.Heartbeat            => handleHeartbeat(message)
      case join: Payload.Join           => handleJoin(message, join, liveChannel)
      case Payload.Leave                => handleLeave(message, liveChannel)
      case event: Payload.Event         => handleEvent(message, event, liveChannel)
      case allow: Payload.AllowUpload   => handleAllowUpload(message, allow, liveChannel)
      case progress: Payload.Progress   => handleProgress(message, progress, liveChannel)
      case patch: Payload.LivePatch     => handleLivePatch(message, patch, liveChannel)
      case join: Payload.UploadJoin     => handleUploadJoin(message, join, liveChannel)
      case chunk: Payload.UploadChunk   => handleUploadChunk(message, chunk, liveChannel)
      case Payload.LiveNavigation(_, _) =>
        handleUnexpectedPayload(message, Protocol.EventLivePatch)
      case Payload.Error       => handleUnexpectedPayload(message, Protocol.EventError)
      case Payload.Reply(_, _) => handleUnexpectedPayload(message, Protocol.EventReply)
      case Payload.Diff(_)     => handleUnexpectedPayload(message, Protocol.EventDiff)
      case Payload.Close       => handleClose(message, liveChannel)
    end match

  private def handleHeartbeat(message: WebSocketMessage): UIO[Option[WebSocketMessage]] =
    ZIO.succeed(Some(message.okReply))

  private def handleJoin(
    message: WebSocketMessage,
    join: Payload.Join,
    liveChannel: LiveChannel
  ): RIO[Scope, Option[WebSocketMessage]] =
    val clientStatics = join.static.orElse(StaticTracking.clientListFromParams(join.params))
    val staticChanged = StaticTracking.staticChanged(clientStatics, trackedStatic)
    val ctx           = LiveContext(
      staticChanged = staticChanged,
      nestedLiveViews = liveChannel.nestedRuntime(message.topic)
    )

    decodeJoinUrl(join) match
      case Left(error) =>
        ZIO.logWarning(error).as(Some(joinErrorReply(message, JoinErrorReason.Stale)))
      case Right(decodedUrl) =>
        liveChannel
          .joinNested(message.topic, join.session, staticChanged, message.meta, decodedUrl)
          .flatMap {
            case Some(reason) => ZIO.succeed(Some(joinErrorReply(message, reason)))
            case None         =>
              val req = Request(url = decodedUrl)
              liveRoutes.iterator
                .map(route =>
                  route.pattern.pathCodec
                    .decode(req.path)
                    .toOption
                    .map(pathParams =>
                      if isAuthorizedJoin(route.sessionName, message.topic, join.session) then
                        val lv = route.live.liveviewBuilder(pathParams, req)
                        ZIO.logDebug(
                          s"Joining LiveView ${route.pattern.pathCodec} ${message.topic}"
                        ) *>
                          liveChannel
                            .join(message.topic, join.session, lv, ctx, message.meta, decodedUrl)(
                              using route.live.msgClassTag
                            )
                            .as(None)
                            .catchAllCause(cause =>
                              ZIO.logErrorCause(cause) *>
                                ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Stale)))
                            )
                      else ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Unauthorized)))
                    )
                )
                .collectFirst { case Some(joinAction) => joinAction }
                .getOrElse(ZIO.succeed(None))
          }
    end match
  end handleJoin

  private def handleLeave(
    message: WebSocketMessage,
    liveChannel: LiveChannel
  ): UIO[Option[WebSocketMessage]] =
    if isLiveViewTopic(message.topic) then
      liveChannel.leave(message.topic).as(Some(message.okReply))
    else
      ZIO.logDebug(s"Ignoring leave for non-liveview topic ${message.topic}") *>
        ZIO.succeed(Some(message.okReply))

  private def handleClose(
    message: WebSocketMessage,
    liveChannel: LiveChannel
  ): UIO[Option[WebSocketMessage]] =
    if isLiveViewTopic(message.topic) then
      liveChannel.leave(message.topic).as(Some(message.okReply))
    else
      ZIO.logDebug(s"Ignoring close for non-liveview topic ${message.topic}") *>
        ZIO.succeed(Some(message.okReply))

  private def handleEvent(
    message: WebSocketMessage,
    event: Payload.Event,
    liveChannel: LiveChannel
  ): UIO[Option[WebSocketMessage]] =
    liveChannel.event(message.topic, event, message.meta).as(None)

  private def handleAllowUpload(
    message: WebSocketMessage,
    payload: Payload.AllowUpload,
    liveChannel: LiveChannel
  ): Task[Option[WebSocketMessage]] =
    wrapReply(message)(liveChannel.allowUpload(message.topic, payload))

  private def handleProgress(
    message: WebSocketMessage,
    payload: Payload.Progress,
    liveChannel: LiveChannel
  ): Task[Option[WebSocketMessage]] =
    wrapReply(message)(liveChannel.progressUpload(message.topic, payload))

  private def handleLivePatch(
    message: WebSocketMessage,
    payload: Payload.LivePatch,
    liveChannel: LiveChannel
  ): Task[Option[WebSocketMessage]] =
    wrapReply(message)(liveChannel.livePatch(message.topic, payload.url, message.meta))

  private def handleUploadJoin(
    message: WebSocketMessage,
    payload: Payload.UploadJoin,
    liveChannel: LiveChannel
  ): Task[Option[WebSocketMessage]] =
    if isUploadTopic(message.topic) then
      wrapReply(message)(liveChannel.uploadJoin(message.topic, payload.token))
    else
      ZIO.succeed(
        Some(
          errorReply(
            message,
            LiveResponse.UploadJoinError(WebSocketMessage.UploadJoinErrorReason.Disallowed)
          )
        )
      )

  private def handleUploadChunk(
    message: WebSocketMessage,
    payload: Payload.UploadChunk,
    liveChannel: LiveChannel
  ): Task[Option[WebSocketMessage]] =
    if isUploadTopic(message.topic) then
      wrapReply(message)(liveChannel.uploadChunk(message.topic, payload.bytes))
    else
      ZIO.succeed(
        Some(
          errorReply(
            message,
            LiveResponse.UploadChunkError(WebSocketMessage.UploadChunkErrorReason.Disallowed)
          )
        )
      )

  private def handleUnexpectedPayload(
    message: WebSocketMessage,
    payloadType: String
  ): UIO[Option[WebSocketMessage]] =
    ZIO
      .logWarning(s"Ignoring unexpected client payload type $payloadType on topic ${message.topic}")
      .as(Some(errorReply(message, LiveResponse.Empty)))

  private def decodeJoinUrl(join: Payload.Join): Either[String, URL] =
    join.url
      .orElse(join.redirect)
      .toRight("Join payload must contain url or redirect")
      .flatMap(rawUrl =>
        URL.decode(rawUrl).left.map(error => s"Could not decode join URL '$rawUrl': $error")
      )

  private def wrapReply(
    message: WebSocketMessage
  )(
    effect: Task[Payload.Reply]
  ): Task[Option[WebSocketMessage]] =
    effect.map(reply => Some(replyEnvelope(message, reply)))

  private def replyEnvelope(
    message: WebSocketMessage,
    reply: Payload.Reply
  ): WebSocketMessage =
    WebSocketMessage(
      message.joinRef,
      message.messageRef,
      message.topic,
      Protocol.EventReply,
      reply
    )

  private def errorReply(
    message: WebSocketMessage,
    response: LiveResponse
  ): WebSocketMessage =
    replyEnvelope(message, Payload.errorReply(response))

  private def joinErrorReply(message: WebSocketMessage, reason: JoinErrorReason): WebSocketMessage =
    errorReply(message, LiveResponse.JoinError(reason))

  private def outgoingEventType(payload: Payload): String =
    payload match
      case Payload.Diff(_)              => Protocol.EventDiff
      case Payload.Close                => Protocol.EventClose
      case Payload.LiveNavigation(_, _) => Protocol.EventLivePatch
      case Payload.Error                => Protocol.EventError
      case _                            => Protocol.EventReply

  private def isLiveViewTopic(topic: String): Boolean =
    topic.startsWith("lv:") && topic.length > 3

  private def isUploadTopic(topic: String): Boolean =
    topic.startsWith("lvu:")

  private def isAuthorizedJoin(expectedSession: String, topic: String, sessionToken: String)
    : Boolean =
    val topicId = topic.stripPrefix("lv:")
    Token
      .verify[String](tokenConfig.secret, sessionToken, tokenConfig.maxAge)
      .toOption
      .exists { case (tokenTopic, tokenSession) =>
        (tokenTopic == topic || tokenTopic == topicId) && tokenSession == expectedSession
      }

  val routes: Routes[Any, Nothing] =
    Routes
      .fromIterable(
        liveRoutes
          .map(route => route.toZioRoute(rootLayout, tokenConfig))
          .prepended(
            Method.GET / websocketMount / "websocket" -> handler(socketApp.toResponse)
          )
      ).handleErrorZIO(e =>
        ZIO.logErrorCause(Cause.fail(e)).as(Response(status = Status.InternalServerError))
      )
end LiveRoutesRuntime
