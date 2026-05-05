package scalive

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.PathCodec
import zio.json.*

import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol

final private[scalive] class LiveRoutesRuntime[R](
  globalLayouts: List[LiveLayout[Any, Any]],
  globalRootLayout: LiveRootLayout[Any, Any],
  liveRoutes: List[LiveRoute[R, ?, Any, ?, ?, ?]],
  liveSocketMount: PathCodec[Unit],
  tokenConfig: TokenConfig):

  private val websocketConfig =
    WebSocketConfig.default.decoderConfig(
      SocketDecoder.default.maxFramePayloadLength(16 * 1024 * 1024)
    )

  private def socketApp(connectAuthorized: Boolean): WebSocketApp[R] =
    val app = Handler.webSocket { channel =>
      ZIO
        .scoped(for
          liveChannel <- LiveChannel.make(tokenConfig, connectAuthorized)
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
    WebSocketApp(app.handler, Some(websocketConfig))
  end socketApp

  private[scalive] def handleMessage(message: WebSocketMessage, liveChannel: LiveChannel)
    : RIO[R & Scope, Option[WebSocketMessage]] =
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
      case Payload.LiveRedirect(_, _, _) =>
        handleUnexpectedPayload(message, Protocol.EventLiveRedirect)
      case Payload.Redirect(_, _) =>
        handleUnexpectedPayload(message, Protocol.EventRedirect)
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
  ): RIO[R & Scope, Option[WebSocketMessage]] =
    if !liveChannel.connectAuthorized then
      ZIO
        .logWarning(s"Rejecting LiveView join for ${message.topic}: invalid CSRF token")
        .as(Some(joinErrorReply(message, JoinErrorReason.Stale)))
    else
      val clientStatics = join.static.orElse(StaticTracking.clientListFromParams(join.params))
      val rootSession   = verifyRootSession(message.topic, join.session)
      val initialFlash  = join.flash
        .orElse(rootSession.flatMap(_.flash))
        .flatMap(FlashToken.decode(tokenConfig, _))
        .getOrElse(Map.empty)

      decodeOptionalJoinUrl(join) match
        case Left(error) =>
          ZIO.logWarning(error).as(Some(joinErrorReply(message, JoinErrorReason.Stale)))
        case Right(decodedUrl) =>
          handleDecodedJoin(
            message,
            join,
            liveChannel,
            clientStatics,
            rootSession,
            initialFlash,
            decodedUrl
          )
  end handleJoin

  private def handleDecodedJoin(
    message: WebSocketMessage,
    join: Payload.Join,
    liveChannel: LiveChannel,
    clientStatics: Option[List[String]],
    rootSession: Option[LiveSessionPayload],
    initialFlash: Map[String, String],
    decodedUrl: Option[URL]
  ): RIO[R & Scope, Option[WebSocketMessage]] =
    liveChannel
      .tryJoinNested(
        message.topic,
        join.session,
        staticChanged = false,
        message.meta,
        decodedUrl,
        enqueueInitReply = false
      ).flatMap {
        case NestedJoinResult.Joined =>
          ZIO.none
        case NestedJoinResult.JoinedWithReply(reply) =>
          ZIO.succeed(
            Some(
              message.copy(
                eventType = Protocol.EventReply,
                payload = reply
              )
            )
          )
        case NestedJoinResult.Rejected(reason) =>
          ZIO.succeed(Some(joinErrorReply(message, reason)))
        case NestedJoinResult.NotNested =>
          decodedUrl match
            case Some(url) =>
              handleRootJoin(
                message,
                join,
                liveChannel,
                clientStatics,
                rootSession,
                initialFlash,
                url
              )
            case None =>
              ZIO
                .logWarning("Join payload must contain url or redirect")
                .as(Some(joinErrorReply(message, JoinErrorReason.Stale)))
      }

  private def handleRootJoin(
    message: WebSocketMessage,
    join: Payload.Join,
    liveChannel: LiveChannel,
    clientStatics: Option[List[String]],
    rootSession: Option[LiveSessionPayload],
    initialFlash: Map[String, String],
    decodedUrl: URL
  ): RIO[R & Scope, Option[WebSocketMessage]] =
    val req = Request(url = decodedUrl)
    liveRoutes.iterator
      .map(route =>
        rootJoinActionForRoute(
          message,
          join,
          liveChannel,
          clientStatics,
          rootSession,
          initialFlash,
          decodedUrl,
          req,
          route
        )
      ).collectFirst { case Some(joinAction) => joinAction }
      .getOrElse(ZIO.succeed(None))

  private def rootJoinActionForRoute[A, Ctx, Msg, Model](
    message: WebSocketMessage,
    join: Payload.Join,
    liveChannel: LiveChannel,
    clientStatics: Option[List[String]],
    rootSession: Option[LiveSessionPayload],
    initialFlash: Map[String, String],
    decodedUrl: URL,
    req: Request,
    route: LiveRoute[R, A, Any, Ctx, Msg, Model]
  ): Option[RIO[R & Scope, Option[WebSocketMessage]]] =
    route.pathCodec.decode(req.path).toOption.map { pathParams =>
      rootSession match
        case Some(session) if session.sessionName == route.sessionName =>
          handleMatchedRootRoute(
            message,
            join,
            liveChannel,
            clientStatics,
            initialFlash,
            decodedUrl,
            req,
            route,
            pathParams,
            session
          )
        case _ =>
          ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Unauthorized)))
    }

  private def handleMatchedRootRoute[A, Ctx, Msg, Model](
    message: WebSocketMessage,
    join: Payload.Join,
    liveChannel: LiveChannel,
    clientStatics: Option[List[String]],
    initialFlash: Map[String, String],
    decodedUrl: URL,
    req: Request,
    route: LiveRoute[R, A, Any, Ctx, Msg, Model],
    pathParams: A,
    session: LiveSessionPayload
  ): RIO[R & Scope, Option[WebSocketMessage]] =
    if isAuthorizedRootJoin(session, route, join) then
      route.mountPipeline
        .runConnected(
          session.mountClaims,
          LiveMountRequest(pathParams, req),
          ()
        ).foldZIO(
          failure => mountFailureReply(message, failure).map(Some(_)),
          mountContext =>
            joinMountedRootRoute(
              message,
              join,
              liveChannel,
              clientStatics,
              initialFlash,
              decodedUrl,
              req,
              route,
              pathParams,
              session,
              mountContext
            )
        )
    else
      ZIO.logWarning(
        s"Rejecting live redirect to ${decodedUrl.path.encode}: route-specific mount claims require a fresh HTTP render"
      ) *>
        ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Unauthorized)))

  private def joinMountedRootRoute[A, Ctx, Msg, Model](
    message: WebSocketMessage,
    join: Payload.Join,
    liveChannel: LiveChannel,
    clientStatics: Option[List[String]],
    initialFlash: Map[String, String],
    decodedUrl: URL,
    req: Request,
    route: LiveRoute[R, A, Any, Ctx, Msg, Model],
    pathParams: A,
    session: LiveSessionPayload,
    mountContext: Ctx
  ): RIO[Scope, Option[WebSocketMessage]] =
    val rootKey = route.rootLayoutKey(
      pathParams,
      req,
      decodedUrl,
      mountContext,
      globalRootLayout
    )
    val serverStatics = route.trackedStatic(
      pathParams,
      req,
      decodedUrl,
      mountContext,
      globalLayouts,
      globalRootLayout
    )
    val staticChanged = StaticTracking.staticChanged(clientStatics, serverStatics)
    val ctx           = LiveContext(
      staticChanged = staticChanged,
      nestedLiveViews = liveChannel.nestedRuntime(message.topic)
    )
    val lv         = route.buildLiveView(pathParams, req, mountContext)
    val renderRoot = route.socketRenderRoot(
      lv,
      pathParams,
      req,
      mountContext,
      globalLayouts
    )

    if rootKey != session.rootLayoutKey then
      ZIO.logWarning(
        s"Rejecting live redirect to ${decodedUrl.path.encode}: root layout changed from ${session.rootLayoutKey} to $rootKey"
      ) *>
        ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Unauthorized)))
    else
      ZIO.logDebug(
        s"Joining LiveView ${route.pathCodec} ${message.topic}"
      ) *>
        liveChannel
          .join(
            message.topic,
            join.session,
            lv,
            ctx,
            message.meta,
            decodedUrl,
            initialFlash,
            Some(renderRoot)
          )(using route.msgClassTag)
          .as(None)
          .catchAllCause(cause =>
            ZIO.logErrorCause(cause) *>
              ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Stale)))
          )
  end joinMountedRootRoute

  private def isAuthorizedRootJoin(
    session: LiveSessionPayload,
    route: LiveRoute[R, ?, Any, ?, ?, ?],
    join: Payload.Join
  ): Boolean =
    join.redirect.isEmpty || (!session.hasRouteMountClaims && !route.hasRouteMountAspect)

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

  private def decodeOptionalJoinUrl(join: Payload.Join): Either[String, Option[URL]] =
    join.url.orElse(join.redirect) match
      case Some(rawUrl) =>
        URL
          .decode(rawUrl)
          .left.map(error => s"Could not decode join URL '$rawUrl': $error")
          .map(Some(_))
      case None => Right(None)

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

  private def mountFailureReply(message: WebSocketMessage, failure: LiveMountFailure)
    : UIO[WebSocketMessage] =
    failure match
      case LiveMountFailure.Redirect(to) =>
        ZIO.succeed(
          WebSocketMessage(
            message.joinRef,
            message.messageRef,
            message.topic,
            Protocol.EventRedirect,
            Payload.Redirect(to.encode, None)
          )
        )
      // Phoenix LiveView clients know unauthorized and stale join failures; keep details server-side.
      case LiveMountFailure.Unauthorized(reason) =>
        logConnectedMountFailure("unauthorized", message, reason).as(
          joinErrorReply(message, JoinErrorReason.Unauthorized)
        )
      case LiveMountFailure.Stale(reason) =>
        logConnectedMountFailure("stale", message, reason).as(
          joinErrorReply(message, JoinErrorReason.Stale)
        )

  private def logConnectedMountFailure(
    kind: String,
    message: WebSocketMessage,
    reason: Option[String]
  ): UIO[Unit] =
    reason match
      case Some(value) =>
        ZIO.logWarning(s"Connected LiveView mount failed for ${message.topic} as $kind: $value")
      case None =>
        ZIO.unit

  private def outgoingEventType(payload: Payload): String =
    payload match
      case Payload.Diff(_)               => Protocol.EventDiff
      case Payload.Close                 => Protocol.EventClose
      case Payload.LiveNavigation(_, _)  => Protocol.EventLivePatch
      case Payload.LiveRedirect(_, _, _) => Protocol.EventLiveRedirect
      case Payload.Redirect(_, _)        => Protocol.EventRedirect
      case Payload.Error                 => Protocol.EventError
      case _                             => Protocol.EventReply

  private def isLiveViewTopic(topic: String): Boolean =
    topic.startsWith("lv:") && topic.length > 3

  private def isUploadTopic(topic: String): Boolean =
    topic.startsWith("lvu:")

  private def verifyRootSession(topic: String, sessionToken: String): Option[LiveSessionPayload] =
    val topicId = topic.stripPrefix("lv:")
    LiveSessionPayload
      .verify(tokenConfig, sessionToken)
      .toOption
      .collect {
        case (tokenTopic, session) if tokenTopic == topic || tokenTopic == topicId =>
          session
      }

  val routes: Routes[R, Nothing] =
    Routes
      .fromIterable(
        liveRoutes
          .map(route => route.toZioRoute(globalLayouts, globalRootLayout, tokenConfig))
          .prepended(
            Method.GET / liveSocketMount / "websocket" -> handler { (request: Request) =>
              socketApp(CsrfProtection.validate(tokenConfig, request)).toResponse
            }
          )
      ).handleErrorZIO(e =>
        ZIO.logErrorCause(Cause.fail(e)).as(Response(status = Status.InternalServerError))
      )
end LiveRoutesRuntime
