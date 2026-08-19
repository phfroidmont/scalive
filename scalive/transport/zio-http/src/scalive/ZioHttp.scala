package scalive

import java.security.SecureRandom

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.json.ast.Json

import scalive.protocol.phoenix.*
import scalive.render.*
import scalive.runtime.connection.*
import scalive.runtime.contracts.*

/** ZIO HTTP assembly for the currently supported, root-only LiveView transport. */
object ZioHttp:
  final class AssemblyException(message: String) extends IllegalArgumentException(message)

  private val CsrfCookieName       = "_scalive_csrf"
  private val RootIngressCapacity  = 64
  private val OutboundCapacity     = 64
  private val KernelCapacity       = 64
  private val ContinuationCapacity = 64
  private val PhysicalWriterSize   = 64
  private val MaxFramePayloadBytes = 64 * 1024
  private val secureRandom         = SecureRandom()

  /** Stable browser-page identity used by both HTTP bootstrap tokens and websocket joins. */
  private[scalive] def canonicalUrl(url: URL): String =
    URL(path = url.path, queryParams = url.queryParams).encode

  /** Phoenix uses the push ref as the channel join ref on its initial `null, "1"` frame. */
  private[scalive] def effectiveJoinRef(
    joinRef: PhoenixRef,
    ref: PhoenixRef
  ): Option[PhoenixRef.Value] = (joinRef, ref) match
    case (value: PhoenixRef.Value, _)               => Some(value)
    case (PhoenixRef.Null, value: PhoenixRef.Value) => Some(value)
    case (PhoenixRef.Null, PhoenixRef.Null)         => None

  private[scalive] def connectedRequest(socketRequest: Request, admittedUrl: URL): Request =
    socketRequest.copy(
      method = Method.GET,
      url = URL(path = admittedUrl.path, queryParams = admittedUrl.queryParams),
      body = Body.empty
    )

  private[scalive] def correlatedEventRefs(
    expectedJoinRef: PhoenixRef.Value,
    joinRef: PhoenixRef,
    ref: PhoenixRef
  ): Option[(PhoenixRef.Value, PhoenixRef.Value)] = (joinRef, ref) match
    case (actualJoinRef: PhoenixRef.Value, actualRef: PhoenixRef.Value)
        if actualJoinRef == expectedJoinRef =>
      Some(actualJoinRef -> actualRef)
    case _ => None

  /** Assembles direct root routes and their Phoenix websocket endpoint.
    *
    * Unsupported application declarations are rejected immediately rather than being omitted.
    */
  def routes[R](application: LiveApplication[R], config: ZioHttpConfig): Routes[R, Nothing] =
    val directRoutes = validate(application)
    val getRoutes    = directRoutes.map(_.getRoute(config))
    val socketRoute  = websocketRoute(application.socketPath, directRoutes, config)
    Routes.fromIterable(getRoutes :+ socketRoute)

  private[scalive] def validate[R](application: LiveApplication[R]): Vector[DirectRoute] =
    val unsupported = Vector.newBuilder[String]
    if application.layout.nonEmpty then unsupported += "application layout"
    if !(application.rootLayout eq LiveRootLayout.identity) then
      unsupported += "custom application root layout"

    val declarations = application.routes.zipWithIndex.flatMap { case (fragment, fragmentIndex) =>
      fragment match
        case route: LiveRoute[?, ?] => Vector(fragmentIndex -> route)
        case _: LiveSession[?]      =>
          unsupported += "live session"
          Vector.empty
    }

    val supported = declarations.flatMap { case (index, route) =>
      route.definition match
        case definition: LiveRouteDefinition.Ordinary[?, ?, ?, ?, ?] =>
          definition.context match
            case _: LiveRouteContext.Direct[?]
                if definition.layouts.isEmpty &&
                  definition.rootLayout.isEmpty =>
              Some(DirectRoute.ordinary(index, definition))
            case _: LiveRouteContext.Direct[?] =>
              unsupported += s"custom layout on route $index"
              None
            case _: LiveRouteContext.Environment[?, ?] =>
              unsupported += s"environment route $index"
              None
            case _: LiveRouteContext.Mounted[?, ?, ?] =>
              unsupported += s"mounted route $index"
              None
        case _: LiveRouteDefinition.Routed[?, ?, ?, ?, ?, ?] =>
          unsupported += s"routed route $index"
          None
    }

    val problems = unsupported.result()
    if problems.nonEmpty then
      throw AssemblyException(
        s"ZIO HTTP root-only transport does not support: ${problems.distinct.mkString(", ")}"
      )
    supported
  end validate

  sealed private[scalive] trait DirectRoute:
    type PathParams
    type Msg
    type Model

    def index: Int
    def pathCodec: PathCodec[PathParams]
    def create(path: PathParams, request: Request): IO[Throwable, LiveView[Msg, Model]]

    final def matches(url: URL): Boolean = pathCodec.decode(url.path).isRight

    final def getRoute(config: ZioHttpConfig): Route[Any, Nothing] =
      RoutePattern(Method.GET, pathCodec) -> handler { (path: PathParams, request: Request) =>
        disconnected(path, request, config).catchAllCause { cause =>
          ZIO.logErrorCause(s"Disconnected LiveView GET failed for ${request.url.encode}", cause) *>
            ZIO.succeed(Response.internalServerError)
        }
      }

    final def startConnected(
      url: URL,
      request: Request,
      metadata: RootConnectionMetadata,
      sink: ConnectionOutput => Task[Unit]
    ): ZIO[Scope, Throwable, RootConnection[Msg, Model]] =
      for
        path       <- ZIO.fromEither(pathCodec.decode(url.path).left.map(Exception(_)))
        liveView   <- create(path, request)
        connection <- RootConnection.start(connectionConfig, metadata, liveView, sink)
      yield connection

    private def disconnected(
      path: PathParams,
      request: Request,
      config: ZioHttpConfig
    ): Task[Response] =
      for
        liveView <- create(path, request)
        model    <- liveView.mount(RootMountContext.disconnected[Msg, Model])
        program  <- ZIO.fromEither(RenderProgram.compile(liveView.view))
        inner    <- program
                   .evaluate(model).flatMap { candidate =>
                     ZIO.succeed(HtmlRenderer.render(candidate.tree)).ensuring(candidate.discard)
                   }.ensuring(program.close)
        rootId <- randomRootId
        canonical = canonicalUrl(request.url)
        tokens <- issueRootTokens(config, rootId, index, canonical)
        (session, static) = tokens
        csrf <- refreshOrIssueCsrf(config, request.cookie(CsrfCookieName).map(_.content))
        (issuedCsrf, setCsrfCookie) = csrf
        response                    = Response(
                     status = Status.Ok,
                     headers = Headers(Header.ContentType(MediaType.text.html)),
                     body =
                       Body.fromString(document(rootId, session, static, issuedCsrf.token, inner))
                   )
        withCookie =
          if setCsrfCookie then
            response.addCookie(csrfCookie(issuedCsrf.cookieToken, config.secureCookie))
          else response
      yield withCookie
  end DirectRoute

  private object DirectRoute:
    def ordinary[A, Message, State](
      routeIndex: Int,
      definition: LiveRouteDefinition.Ordinary[Any, A, Any, Message, State]
    ): DirectRoute = new DirectRoute:
      type PathParams = A
      type Msg        = Message
      type Model      = State

      val index                             = routeIndex
      val pathCodec                         = definition.pathCodec
      def create(path: A, request: Request) =
        ZIO.attempt(definition.factory(path, request, ()))

  private def websocketRoute(
    socketPath: PathCodec[Unit],
    routes: Vector[DirectRoute],
    config: ZioHttpConfig
  ): Route[Any, Nothing] =
    val pattern = RoutePattern(Method.GET, socketPath / "websocket")
    pattern -> handler { (request: Request) =>
      val csrfCookieValue = request.cookie(CsrfCookieName).map(_.content)
      val csrfToken       = request.queryParam("_csrf_token")
      val socketHandler   = Handler.webSocket { channel =>
        ZIO
          .scoped(runSocket(channel, routes, config, request, csrfCookieValue, csrfToken))
          .tapErrorCause(ZIO.logErrorCause("Root websocket failed", _))
      }
      val app = WebSocketApp(
        socketHandler.handler,
        Some(
          WebSocketConfig.default.decoderConfig(
            SocketDecoder.default.maxFramePayloadLength(MaxFramePayloadBytes)
          )
        )
      )
      app.toResponse
    }

  final private case class JoinedRoot(
    topic: String,
    joinRef: PhoenixRef.Value,
    connection: RootConnection[?, ?],
    correlations: Ref[Map[CommandId, (PhoenixRef, PhoenixRef)]])

  private def runSocket(
    channel: WebSocketChannel,
    routes: Vector[DirectRoute],
    config: ZioHttpConfig,
    socketRequest: Request,
    csrfCookie: Option[String],
    csrfToken: Option[String]
  ): ZIO[Scope, Throwable, Unit] =
    for
      writer <-
        SerialWriter.make[PhoenixEnvelope](PhysicalWriterSize) { envelope =>
          channel.send(ChannelEvent.read(WebSocketFrame.text(PhoenixEnvelope.encode(envelope))))
        }
      root <- Ref.make(Option.empty[JoinedRoot])
      receive = channel.receiveAll {
                  case ChannelEvent.Read(WebSocketFrame.Text(text))
                      if text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <=
                        MaxFramePayloadBytes =>
                    ZIO.fromEither(PhoenixEnvelope.decode(text).left.map(Exception(_))).flatMap {
                      envelope =>
                        PhoenixProtocol.decodeEnvelope(envelope) match
                          case Left(error) => ZIO.fail(Exception(error))
                          case Right(PhoenixInbound.Heartbeat(joinRef, ref)) =>
                            writer.offer(PhoenixOutput.heartbeat(joinRef, ref))
                          case Right(PhoenixInbound.Join(joinRef, ref, topic, payload)) =>
                            joinRoot(
                              routes,
                              config,
                              csrfCookie,
                              csrfToken,
                              channel,
                              socketRequest,
                              root,
                              writer,
                              joinRef,
                              ref,
                              topic,
                              payload
                            )
                          case Right(PhoenixInbound.Event(joinRef, ref, topic, payload)) =>
                            offerEvent(root, writer, joinRef, ref, topic, payload)
                    }
                  case ChannelEvent.Read(_: WebSocketFrame.Close) => ZIO.unit
                  case ChannelEvent.Registered | ChannelEvent.Unregistered |
                      ChannelEvent.UserEventTriggered(_) =>
                    ZIO.unit
                  case ChannelEvent.ExceptionCaught(cause) => ZIO.fail(cause)
                  case _ => ZIO.fail(Exception("unsupported websocket frame"))
                }
      _ <- receive
             .raceFirst(writer.awaitFailure.flatMap(ZIO.fail(_)))
             .ensuring(root.get.flatMap(ZIO.foreachDiscard(_)(_.connection.close)) *> writer.close)
    yield ()

  private def joinRoot(
    routes: Vector[DirectRoute],
    config: ZioHttpConfig,
    csrfCookie: Option[String],
    csrfToken: Option[String],
    channel: WebSocketChannel,
    socketRequest: Request,
    currentRoot: Ref[Option[JoinedRoot]],
    writer: SerialWriter[PhoenixEnvelope],
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    join: RootJoin
  ): ZIO[Scope, Throwable, Unit] =
    effectiveJoinRef(joinRef, ref) match
      case None               => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
      case Some(effectiveRef) =>
        currentRoot.get.flatMap { existing =>
          ZioHttpAdmission
            .admit(routes, config, csrfCookie, csrfToken, existing.nonEmpty, topic, join).foldZIO(
              error =>
                ZIO.logWarning(s"Rejecting root join topic=$topic: $error") *>
                  writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason)),
              admitted =>
                for
                  renderedState <- Ref.make(Option.empty[PhoenixRenderedState])
                  correlations  <- Ref.make(Map.empty[CommandId, (PhoenixRef, PhoenixRef)])
                  request  = connectedRequest(socketRequest, admitted.url)
                  metadata = RootConnectionMetadata(
                               staticChanged = false,
                               connectParams = join.params
                             )
                  sink = rootSink(
                           writer,
                           renderedState,
                           correlations,
                           effectiveRef,
                           ref,
                           topic
                         )
                  connection <- admitted.route.startConnected(admitted.url, request, metadata, sink)
                  installed  <- currentRoot.modify {
                                 case None =>
                                   true -> Some(
                                     JoinedRoot(topic, effectiveRef, connection, correlations)
                                   )
                                 case some => false -> some
                               }
                  _ <- ZIO.fail(Exception("root already joined")).unless(installed)
                  _ <- (connection.awaitFailure *> channel.shutdown).forkScoped
                yield ()
            )
        }

  private def rootSink(
    writer: SerialWriter[PhoenixEnvelope],
    state: Ref[Option[PhoenixRenderedState]],
    correlations: Ref[Map[CommandId, (PhoenixRef, PhoenixRef)]],
    joinRef: PhoenixRef,
    joinReplyRef: PhoenixRef,
    topic: String
  ): ConnectionOutput => Task[Unit] = output =>
    def update(delta: RenderDelta): IO[Throwable, Json.Obj] =
      state.modify { previous =>
        val encoded: Either[Throwable, (PhoenixRenderedState, Json.Obj)] = previous match
          case None =>
            delta match
              case RenderDelta.Replace(tree) =>
                PhoenixRenderedEncoder.initial(tree).left.map(error => Exception(error.toString))
              case _ => Left(Exception("initial root output was not a replacement"))
          case Some(current) =>
            PhoenixRenderedEncoder
              .update(current, delta).left.map(error => Exception(error.toString))
        encoded match
          case Right((next, json)) => Right(json) -> Some(next)
          case Left(error)         => Left(error) -> previous
      }.absolve

    output match
      case ConnectionOutput.Joined(delta) =>
        update(delta).flatMap(json =>
          writer.offer(PhoenixOutput.join(joinRef, joinReplyRef, topic, json))
        )
      case ConnectionOutput.Reply(command, delta) =>
        correlations.modify(current => current.get(command) -> (current - command)).flatMap {
          case Some((eventJoinRef, eventRef)) =>
            update(delta).flatMap(json =>
              writer.offer(PhoenixOutput.event(eventJoinRef, eventRef, topic, json))
            )
          case None => ZIO.fail(Exception(s"missing event correlation ${command.value}"))
        }
      case ConnectionOutput.Rejected(command, _) =>
        correlations.modify(current => current.get(command) -> (current - command)).flatMap {
          case Some((eventJoinRef, eventRef)) =>
            writer.offer(PhoenixOutput.error(eventJoinRef, eventRef, topic, staleReason))
          case None => ZIO.fail(Exception(s"missing event correlation ${command.value}"))
        }
      case ConnectionOutput.Diff(delta) =>
        update(delta).flatMap(json => writer.offer(PhoenixOutput.diff(joinRef, topic, json)))

  private def offerEvent(
    root: Ref[Option[JoinedRoot]],
    writer: SerialWriter[PhoenixEnvelope],
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    event: RootEvent
  ): Task[Unit] =
    root.get.flatMap {
      case Some(joined) if joined.topic == topic && event.cid.isEmpty =>
        correlatedEventRefs(joined.joinRef, joinRef, ref) match
          case Some(eventRefs) =>
            ZIO.fromEither(event.toBindingPayload.left.map(Exception(_))).flatMap { payload =>
              ZIO
                .fromEither(CommandId.fresh().left.map(error => Exception(error.toString))).flatMap {
                  command =>
                    joined.correlations
                      .modify { current =>
                        if current.size >= RootIngressCapacity then false -> current
                        else true -> current.updated(command, eventRefs)
                      }.flatMap {
                        case false =>
                          ZIO.fail(ConnectionError.IngressSaturated(RootIngressCapacity))
                        case true =>
                          joined.connection
                            .offerEvent(command, BindingId.fromEncoded(event.event), payload)
                            .onError(_ => joined.correlations.update(_ - command))
                      }
                }
            }
          case None => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
      case _ => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
    }

  private def connectionConfig: ConnectionConfig =
    ConnectionConfig
      .make(
        RootIngressCapacity,
        OutboundCapacity,
        KernelCapacity,
        ContinuationCapacity,
        OutboundCapacity
      ).fold(error => throw IllegalStateException(error.toString), identity)

  private def randomRootId: Task[String] = ZIO.attempt {
    val bytes = Array.ofDim[Byte](18)
    secureRandom.synchronized(secureRandom.nextBytes(bytes))
    java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  /** Both Phoenix root tokens must carry byte-for-byte equal claims. Retry only if issuance crosses
    * a wall-clock second boundary.
    */
  private def issueRootTokens(
    config: ZioHttpConfig,
    rootId: String,
    routeIndex: Int,
    canonicalUrl: String
  ): UIO[(String, String)] =
    ZioHttpSecurity
      .issueSession(config, rootId, routeIndex, canonicalUrl).zipPar(
        ZioHttpSecurity.issueStatic(config, rootId, routeIndex, canonicalUrl)
      ).flatMap { case tokens @ (session, static) =>
        ZioHttpSecurity
          .verifySession(config, session).zip(ZioHttpSecurity.verifyStatic(config, static)).foldZIO(
            _ => issueRootTokens(config, rootId, routeIndex, canonicalUrl),
            claims =>
              if claims._1 == claims._2 then ZIO.succeed(tokens)
              else issueRootTokens(config, rootId, routeIndex, canonicalUrl)
          )
      }

  private def refreshOrIssueCsrf(
    config: ZioHttpConfig,
    existingCookie: Option[String]
  ): UIO[(ZioHttpSecurity.IssuedCsrf, Boolean)] =
    existingCookie match
      case Some(cookieToken) =>
        ZioHttpSecurity
          .refreshCsrf(config, cookieToken).foldZIO(
            _ => ZioHttpSecurity.issueCsrf(config).map(_ -> true),
            issued => ZIO.succeed(issued -> false)
          )
      case None => ZioHttpSecurity.issueCsrf(config).map(_ -> true)

  private def document(
    rootId: String,
    session: String,
    static: String,
    csrfToken: String,
    inner: String
  ): String =
    def escaped(value: String) = Escaping.escape(value)
    s"<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"csrf-token\" content=\"${escaped(csrfToken)}\"></head><body><div id=\"${escaped(rootId)}\" data-phx-main data-phx-session=\"${escaped(session)}\" data-phx-static=\"${escaped(static)}\">$inner</div></body></html>"

  private def csrfCookie(cookieToken: String, secure: Boolean): Cookie.Response =
    Cookie.Response(
      CsrfCookieName,
      cookieToken,
      path = Some(Path.root),
      isSecure = secure,
      isHttpOnly = true,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  private def staleReason: Json.Obj = Json.Obj("reason" -> Json.Str("stale"))
end ZioHttp

private[scalive] object ZioHttpAdmission:
  final case class Admitted(route: ZioHttp.DirectRoute, url: URL)

  def admit(
    routes: Vector[ZioHttp.DirectRoute],
    config: ZioHttpConfig,
    csrfCookie: Option[String],
    csrfToken: Option[String],
    rootExists: Boolean,
    topic: String,
    join: RootJoin
  ): IO[String, Admitted] =
    val checked = for
      _      <- ZIO.fail("root already joined").when(rootExists)
      rootId <- ZIO
                  .fromOption(topic.stripPrefix("lv:") match
                    case value if topic.startsWith("lv:") && value.nonEmpty => Some(value)
                    case _ => None).orElseFail("invalid root topic")
      _           <- ZIO.fail("redirect joins are unsupported").when(join.redirect.nonEmpty)
      _           <- ZIO.fail("sticky roots are unsupported").when(join.sticky)
      urlText     <- ZIO.fromOption(join.url).orElseFail("missing root URL")
      url         <- ZIO.fromEither(URL.decode(urlText).left.map(_.getMessage))
      session     <- ZioHttpSecurity.verifySession(config, join.session).mapError(_.toString)
      staticToken <- ZIO.fromOption(join.static).orElseFail("missing static token")
      static      <- ZioHttpSecurity.verifyStatic(config, staticToken).mapError(_.toString)
      _           <- ZIO.fail("session/static claims differ").unless(session == static)
      _           <- ZIO.fail("root id differs").unless(session.rootId == rootId)
      _      <- ZIO.fail("URL differs").unless(session.canonicalUrl == ZioHttp.canonicalUrl(url))
      cookie <- ZIO.fromOption(csrfCookie).orElseFail("missing CSRF cookie")
      token  <- ZIO.fromOption(csrfToken).orElseFail("missing CSRF token")
      _      <- ZioHttpSecurity.verifyCsrf(config, token, cookie).mapError(_.toString)
      route  <-
        ZIO.fromOption(routes.find(_.index == session.routeIndex)).orElseFail("unknown route")
      _ <- ZIO.fail("route path differs").unless(route.matches(url))
    yield Admitted(route, url)
    checked
  end admit
end ZioHttpAdmission
