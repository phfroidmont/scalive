package scalive

import java.net.URI
import java.security.SecureRandom

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.json.*
import zio.json.ast.Json

import scalive.protocol.phoenix.*
import scalive.render.*
import scalive.runtime.connection.*
import scalive.runtime.contracts.*

/** ZIO HTTP assembly for root LiveView routes. */
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

  private[scalive] def clientTrackedStatics(
    params: Map[String, Json]
  ): Option[Vector[String]] =
    params.get("_track_static").collect {
      case Json.Arr(values) if values.forall(_.isInstanceOf[Json.Str]) =>
        values.collect { case Json.Str(value) => value }.toVector
    }

  private[scalive] def staticChanged(
    client: Option[Vector[String]],
    server: Vector[String],
    pageUrl: URL
  ): Boolean =
    client.exists(values =>
      values.nonEmpty &&
        values.map(normalizeStaticUrl(_, pageUrl)) != server.map(normalizeStaticUrl(_, pageUrl))
    )

  private def normalizeStaticUrl(value: String, pageUrl: URL): String =
    val withoutQuery = value.takeWhile(character => character != '?' && character != '#')
    val encoded      = value.replace(" ", "%20")
    val base         = URI.create(s"http://scalive.invalid${canonicalUrl(pageUrl)}")
    try
      Option(base.resolve(encoded).normalize().getRawPath)
        .filter(_.nonEmpty)
        .map(path => "(?i)%[0-9a-f]{2}".r.replaceAllIn(path, _.matched.toUpperCase))
        .getOrElse(withoutQuery)
    catch case _: IllegalArgumentException => withoutQuery

  /** Phoenix uses the push ref as the channel join ref on its initial `null, "1"` frame. */
  private[scalive] def effectiveJoinRef(
    joinRef: PhoenixRef,
    ref: PhoenixRef
  ): Option[PhoenixRef.Value] = (joinRef, ref) match
    case (value: PhoenixRef.Value, _)               => Some(value)
    case (PhoenixRef.Null, value: PhoenixRef.Value) => Some(value)
    case (PhoenixRef.Null, PhoenixRef.Null)         => None

  private[scalive] def connectedRequest(socketRequest: Request, admittedUrl: URL): Request =
    Request.get(URL(path = admittedUrl.path, queryParams = admittedUrl.queryParams))

  private[scalive] def correlatedEventRefs(
    expectedJoinRef: PhoenixRef.Value,
    joinRef: PhoenixRef,
    ref: PhoenixRef
  ): Option[(PhoenixRef.Value, PhoenixRef.Value)] = (joinRef, ref) match
    case (actualJoinRef: PhoenixRef.Value, actualRef: PhoenixRef.Value)
        if actualJoinRef == expectedJoinRef =>
      Some(actualJoinRef -> actualRef)
    case _ => None

  /** Assembles the HTTP and websocket routes after synchronously validating the catalog. */
  def routes[R](application: LiveApplication[R], config: ZioHttpConfig): Routes[R, Nothing] =
    val catalog     = validate(application)
    val getRoutes   = catalog.map(_.getRoute(config))
    val socketRoute = websocketRoute(application.socketPath, catalog, config)
    Routes.fromIterable(getRoutes :+ socketRoute)

  private[scalive] def validate[R](application: LiveApplication[R]): Vector[CompiledRoute[R]] =
    val sessionNames = application.routes.collect { case session: LiveSession[?] => session.name }
    val duplicateSessions = sessionNames.groupBy(identity).collect {
      case (name, values) if values.size > 1 =>
        name
    }
    if duplicateSessions.nonEmpty then
      throw AssemblyException(
        s"duplicate live session: ${duplicateSessions.toVector.sorted.mkString(", ")}"
      )

    val declarations = application.routes.flatMap {
      case route: LiveRoute[?, ?]  => Vector(None -> route)
      case session: LiveSession[?] =>
        session.routes.flatMap(_.declarations).map(Some(session.name) -> _)
    }
    val catalog = declarations.zipWithIndex.map { case ((sessionName, route), index) =>
      CompiledRoute.erase(
        index,
        sessionName,
        route.definition,
        application.layout,
        application.rootLayout
      )
    }
    // PathCodec does not expose structural equality. RoutePattern.toString is ZIO HTTP's stable
    // rendered pattern evidence and is also what its diagnostics use.
    val duplicatePaths = catalog.groupBy(_.pathDescription).collect {
      case (description, values) if values.size > 1 => description
    }
    if duplicatePaths.nonEmpty then
      throw AssemblyException(
        s"duplicate rendered route path: ${duplicatePaths.toVector.sorted.mkString(", ")}"
      )
    catalog
  end validate

  final private[scalive] case class MountClaims(session: Vector[String], route: Vector[String]):
    def ++(that: MountClaims): MountClaims =
      MountClaims(session ++ that.session, route ++ that.route)

  private enum ClaimGroup:
    case Session, Route

  /** The one audited existential boundary. All route-specific types remain captured by the
    * implementation created in `erase`; transport/socket code only sees this sealed interface.
    */
  sealed private[scalive] trait CompiledRoute[-R]:
    type PathParams
    type Msg
    type Model

    def index: Int
    def sessionName: Option[String]
    def routeIdentity: String
    def pathDescription: String
    def pathCodec: PathCodec[PathParams]
    def rootLayoutKey(path: PathParams, request: Request, context: Any): String

    final def matches(url: URL): Boolean = pathCodec.decode(url.path).isRight

    final def getRoute(config: ZioHttpConfig): Route[R, Nothing] =
      RoutePattern(Method.GET, pathCodec) -> handler { (path: PathParams, request: Request) =>
        disconnected(path, request, config).catchAllCause { cause =>
          ZIO.logErrorCause(s"Disconnected LiveView GET failed for ${request.url.encode}", cause) *>
            ZIO.succeed(Response.internalServerError)
        }
      }

    final def startConnected(
      url: URL,
      request: Request,
      claims: ZioHttpSecurity.RootClaims,
      metadata: RootConnectionMetadata,
      sink: ConnectionOutput => Task[Unit]
    ): ZIO[R & Scope, Throwable, RootConnection[Msg, Model]] =
      for
        path       <- ZIO.fromEither(pathCodec.decode(url.path).left.map(Exception(_)))
        lifecycle  <- connectedLifecycle(path, request, url, claims)
        connection <- RootConnection.startLifecycle(connectionConfig, metadata, lifecycle, sink)
      yield connection

    protected def disconnectedLifecycle(
      path: PathParams,
      request: Request
    ): ZIO[
      R,
      Response,
      (RootLifecycle[Msg, Model], Any, MountClaims, LiveRootLayout[PathParams, Any])
    ]

    protected def connectedLifecycle(
      path: PathParams,
      request: Request,
      url: URL,
      claims: ZioHttpSecurity.RootClaims
    ): ZIO[R, Throwable, RootLifecycle[Msg, Model]]

    final private[scalive] def prepareConnected(
      url: URL,
      request: Request,
      claims: ZioHttpSecurity.RootClaims
    ): ZIO[R, Throwable, RootLifecycle[Msg, Model]] =
      ZIO
        .fromEither(pathCodec.decode(url.path).left.map(Exception(_))).flatMap(path =>
          connectedLifecycle(path, request, url, claims)
        )

    private def disconnected(path: PathParams, request: Request, config: ZioHttpConfig)
      : ZIO[R, Nothing, Response] =
      disconnectedLifecycle(path, request).foldZIO(
        ZIO.succeed(_),
        (lifecycle, erasedContext, mountClaims, selectedRootLayout) =>
          renderDisconnected(
            path,
            request,
            config,
            lifecycle,
            erasedContext,
            mountClaims,
            selectedRootLayout
          ).catchAllCause { cause =>
            ZIO.logErrorCause(
              s"Disconnected LiveView GET failed for ${request.url.encode}",
              cause
            ) *>
              ZIO.succeed(Response.internalServerError)
          }
      )

    private def renderDisconnected(
      path: PathParams,
      request: Request,
      config: ZioHttpConfig,
      lifecycle: RootLifecycle[Msg, Model],
      erasedContext: Any,
      mountClaims: MountClaims,
      selectedRootLayout: LiveRootLayout[PathParams, Any]
    ): Task[Response] =
      for
        mountContext = RootMountContext.disconnected[Msg, Model]
        mounted  <- lifecycle.mount(mountContext)
        prepared <- lifecycle.prepareParams(request.url)
        model    <- prepared.run(mounted, disconnectedParamsContext(mountContext))
        rootId   <- randomRootId
        canonical = canonicalUrl(request.url)
        csrf <- refreshOrIssueCsrf(config, request.cookie(CsrfCookieName).map(_.content))
        (issuedCsrf, setCsrfCookie) = csrf
        rootContext = LiveRootLayoutContext(path, request, request.url, erasedContext)
        rootKey <- ZIO.attempt(selectedRootLayout.key(rootContext))
        sessionPlaceholder = s"pending-session-$rootId"
        staticPlaceholder  = s"pending-static-$rootId"
        rootAttrs          = Vector(
                      Mod.Attr.Static("id", rootId),
                      Mod.Attr.StaticValueAsPresence("data-phx-main", true),
                      Mod.Attr.Static("data-phx-session", sessionPlaceholder),
                      Mod.Attr.Static("data-phx-static", staticPlaceholder)
                    )
        rendered <-
          if selectedRootLayout eq LiveRootLayout.identity then
            renderElement(
              (input: Signal[(Model, URL)]) => withRootAttrs(lifecycle.view(input), rootAttrs),
              model -> request.url
            )
              .map(inner => inner.copy(html = document(issuedCsrf.token, inner.html)))
          else
            val rootView = (input: Signal[(Model, URL)]) =>
              val content = withRootAttrs(lifecycle.view(input), rootAttrs)
              val rooted  =
                selectedRootLayout.render(content, lifecycle.pageTitle(model), rootContext)
              injectCsrf(rooted, issuedCsrf.token)
            renderElement(rootView, model -> request.url, includeDoctype = true)
        tokens <- issueRootTokens(
                    config,
                    rootId,
                    index,
                    canonical,
                    routeIdentity,
                    sessionName,
                    rootKey,
                    mountClaims,
                    rendered.trackedStatics
                  )
        (session, static) = tokens
        html              = rendered.html
                 .replace(
                   s"data-phx-session=\"$sessionPlaceholder\"",
                   s"data-phx-session=\"$session\""
                 ).replace(
                   s"data-phx-static=\"$staticPlaceholder\"",
                   s"data-phx-static=\"$static\""
                 )
        response = Response(
                     status = Status.Ok,
                     headers = Headers(Header.ContentType(MediaType.text.html)),
                     body = Body.fromString(html)
                   )
        withCookie =
          if setCsrfCookie then
            response.addCookie(csrfCookie(issuedCsrf.cookieToken, config.secureCookie))
          else response
      yield withCookie
  end CompiledRoute

  private object CompiledRoute:
    def erase[R, A](
      routeIndex: Int,
      declaredSession: Option[String],
      definition: LiveRouteDefinition[A],
      applicationLayout: Option[LiveLayout[Any, Any]],
      applicationRootLayout: LiveRootLayout[Any, Any]
    ): CompiledRoute[R] = definition match
      case ordinary: LiveRouteDefinition.Ordinary[r, A, in, ctx, message, state] =>
        val compiled = ordinaryRoute(
          routeIndex,
          declaredSession,
          ordinary,
          applicationLayout,
          applicationRootLayout
        )
        compiled.asInstanceOf[CompiledRoute[R]]
      case routed: LiveRouteDefinition.Routed[r, A, in, ctx, message, state, params] =>
        val compiled = routedRoute(
          routeIndex,
          declaredSession,
          routed,
          applicationLayout,
          applicationRootLayout
        )
        compiled.asInstanceOf[CompiledRoute[R]]

    private def base[R, A, In, Ctx, Message, State](
      routeIndex: Int,
      declaredSession: Option[String],
      codec: PathCodec[A],
      contextDefinition: LiveRouteContext[R, A, In, Ctx],
      layouts: Vector[LiveLayout[A, Ctx]],
      root: Option[LiveRootLayout[A, Ctx]],
      applicationLayout: Option[LiveLayout[Any, Any]],
      applicationRoot: LiveRootLayout[Any, Any],
      make: (A, Request, Ctx, URL) => Task[RootLifecycle[Message, State]]
    ): CompiledRoute[R] = new CompiledRoute[R]:
      type PathParams = A
      type Msg        = Message
      type Model      = State

      val index           = routeIndex
      val sessionName     = declaredSession
      val pathCodec       = codec
      val pathDescription = RoutePattern(Method.GET, codec).toString
      val routeIdentity   = s"$routeIndex:$pathDescription"

      private def selectedRoot: LiveRootLayout[A, Ctx] =
        root.getOrElse(applicationRoot.asInstanceOf[LiveRootLayout[A, Ctx]])

      def rootLayoutKey(path: A, request: Request, erasedContext: Any): String =
        selectedRoot.key(
          LiveRootLayoutContext(path, request, request.url, erasedContext.asInstanceOf[Ctx])
        )

      protected def disconnectedLifecycle(path: A, request: Request) =
        runDisconnectedContext(
          contextDefinition,
          path,
          request,
          ().asInstanceOf[In]
        ).flatMap { case (context, claims) =>
          make(path, request, context, request.url)
            .map { lifecycle =>
              val composed = composeLayouts(
                lifecycle,
                request,
                context,
                layouts,
                applicationLayout,
                url => codec.decode(url.path).fold(error => throw Exception(error), identity)
              )
              (composed, context: Any, claims, selectedRoot.asInstanceOf[LiveRootLayout[A, Any]])
            }.mapError(error => Response.internalServerError)
        }

      protected def connectedLifecycle(
        path: A,
        request: Request,
        url: URL,
        claims: ZioHttpSecurity.RootClaims
      ) =
        val supplied = MountClaims(claims.sessionMountClaims, claims.routeMountClaims)
        runConnectedContext(
          contextDefinition,
          path,
          request,
          ().asInstanceOf[In],
          supplied
        ).mapError(failure => Exception(s"connected mount rejected: $failure")).flatMap { context =>
          val rootContext = LiveRootLayoutContext(path, request, url, context)
          ZIO
            .fail(Exception("root layout key differs"))
            .unless(selectedRoot.key(rootContext) == claims.rootLayoutKey) *>
            make(path, request, context, url).map { lifecycle =>
              composeLayouts(
                lifecycle,
                request,
                context,
                layouts,
                applicationLayout,
                destination =>
                  codec.decode(destination.path).fold(error => throw Exception(error), identity)
              )
            }
        }
      end connectedLifecycle

    private def ordinaryRoute[R, A, In, Ctx, Message, State](
      index: Int,
      sessionName: Option[String],
      definition: LiveRouteDefinition.Ordinary[R, A, In, Ctx, Message, State],
      applicationLayout: Option[LiveLayout[Any, Any]],
      applicationRoot: LiveRootLayout[Any, Any]
    ): CompiledRoute[R] =
      base(
        index,
        sessionName,
        definition.pathCodec,
        definition.context,
        definition.layouts,
        definition.rootLayout,
        applicationLayout,
        applicationRoot,
        (path, request, context, url) =>
          ZIO.attempt(RootLifecycle.ordinary(definition.factory(path, request, context), url))
      )

    private def routedRoute[R, A, In, Ctx, Message, State, Params](
      index: Int,
      sessionName: Option[String],
      definition: LiveRouteDefinition.Routed[R, A, In, Ctx, Message, State, Params],
      applicationLayout: Option[LiveLayout[Any, Any]],
      applicationRoot: LiveRootLayout[Any, Any]
    ): CompiledRoute[R] =
      base(
        index,
        sessionName,
        definition.pathCodec,
        definition.context,
        definition.layouts,
        definition.rootLayout,
        applicationLayout,
        applicationRoot,
        (path, request, context, url) =>
          for
            initialParams <- definition.paramsCodec.decode(path, url)
            view          <- ZIO.attempt(definition.factory(path, request, context))
          yield RootLifecycle(
            initialUrl = url,
            hooks = view.hooks,
            pageTitle = view.pageTitle,
            mount = ctx => view.mount(initialParams, ctx),
            handleMessage = (model, ctx, message) => view.handleMessage(model, ctx)(message),
            prepareParams = destination =>
              definition.pathCodec.decode(destination.path) match
                case Left(error) =>
                  LiveIO.succeed(
                    RootParamsHandler(
                      runHooks = false,
                      (model, ctx) =>
                        view.handleParamsDecodeError(
                          model,
                          LiveParamsCodec.DecodeError(error),
                          destination,
                          ctx
                        )
                    )
                  )
                case Right(destinationPath) =>
                  definition.paramsCodec
                    .decode(destinationPath, destination).fold(
                      error =>
                        RootParamsHandler(
                          runHooks = false,
                          (model, ctx) =>
                            view.handleParamsDecodeError(model, error, destination, ctx)
                        ),
                      params =>
                        RootParamsHandler(
                          runHooks = true,
                          (model, ctx) => view.handleParams(model, params, destination, ctx)
                        )
                    ),
            view = input => view.view(input.map(_._1))
          )
      )
  end CompiledRoute

  private def composeLayouts[A, Ctx, Msg, Model](
    lifecycle: RootLifecycle[Msg, Model],
    request: Request,
    context: Ctx,
    layouts: Vector[LiveLayout[A, Ctx]],
    applicationLayout: Option[LiveLayout[Any, Any]],
    decodePath: URL => A
  ): RootLifecycle[Msg, Model] =
    lifecycle.copy(view = input =>
      val currentUrl    = input.map(_._2)
      val path          = currentUrl.map(decodePath)
      val requestSignal = currentUrl.map(url =>
        request.copy(url = URL(path = url.path, queryParams = url.queryParams))
      )
      val routeContext = LiveLayoutContext(path, requestSignal, currentUrl, context)
      val routed       = layouts.foldRight(lifecycle.view(input))((layout, content) =>
        layout.view(content, routeContext)
      )
      applicationLayout.fold(routed)(
        _.view(
          routed,
          LiveLayoutContext(path, requestSignal, currentUrl, ())
        )
      ))

  private def runDisconnectedPipeline[R, A, In, Ctx](
    pipeline: LiveMountPipeline[R, A, In, Ctx],
    group: ClaimGroup,
    mountRequest: LiveMountRequest[A],
    input: In
  ): ZIO[R, Response, (Ctx, MountClaims)] = pipeline match
    case _: LiveMountPipeline.Identity[A, In] =>
      ZIO.succeed(input -> MountClaims(Vector.empty, Vector.empty))
    case thenNode: LiveMountPipeline.Then[r, r1, A, In, previous, claims, out, Ctx] =>
      runDisconnectedPipeline(thenNode.previous, group, mountRequest, input).flatMap {
        case (previousContext, previousClaims) =>
          thenNode.aspect.disconnected(mountRequest, previousContext).map { case (claim, output) =>
            val encoded    = thenNode.aspect.claimsCodec.encodeJson(claim, None).toString
            val nextClaims = group match
              case ClaimGroup.Session => MountClaims(Vector(encoded), Vector.empty)
              case ClaimGroup.Route   => MountClaims(Vector.empty, Vector(encoded))
            thenNode.append.append(previousContext, output) -> (previousClaims ++ nextClaims)
          }
      }

  private def runConnectedPipeline[R, A, In, Ctx](
    pipeline: LiveMountPipeline[R, A, In, Ctx],
    group: ClaimGroup,
    mountRequest: LiveMountRequest[A],
    input: In,
    claims: Vector[String]
  ): ZIO[R, LiveMountFailure, (Ctx, Vector[String])] = pipeline match
    case _: LiveMountPipeline.Identity[A, In] => ZIO.succeed(input -> claims)
    case thenNode: LiveMountPipeline.Then[r, r1, A, In, previous, claim, out, Ctx] =>
      runConnectedPipeline(thenNode.previous, group, mountRequest, input, claims).flatMap {
        case (previousContext, remaining) =>
          remaining.headOption match
            case None          => ZIO.fail(LiveMountFailure.unauthorized("missing mount claims"))
            case Some(encoded) =>
              ZIO
                .fromEither(
                  thenNode.aspect.claimsCodec
                    .decodeJson(encoded).left.map(_ =>
                      LiveMountFailure.unauthorized("malformed mount claims")
                    )
                ).flatMap(claim =>
                  thenNode.aspect.connected(claim, mountRequest, previousContext).map { output =>
                    thenNode.append.append(previousContext, output) -> remaining.tail
                  }
                )
      }

  private def runDisconnectedContext[R, A, In, Ctx](
    context: LiveRouteContext[R, A, In, Ctx],
    path: A,
    request: Request,
    input: In
  ): ZIO[R, Response, (Ctx, MountClaims)] = context match
    case _: LiveRouteContext.Direct[A] => ZIO.succeed(((), MountClaims(Vector.empty, Vector.empty)))
    case environment: LiveRouteContext.Environment[r, A] =>
      ZIO
        .environmentWith[r](_.get(environment.tag)).map(
          _ -> MountClaims(Vector.empty, Vector.empty)
        )
    case _: LiveRouteContext.Required[A, Ctx] =>
      ZIO.succeed(input -> MountClaims(Vector.empty, Vector.empty))
    case mounted: LiveRouteContext.Mounted[r, A, In, Ctx] =>
      runDisconnectedPipeline(
        mounted.pipeline,
        ClaimGroup.Route,
        LiveMountRequest(path, request),
        input
      )
    case session: LiveRouteContext.SessionMounted[rs, rr, A, sessionCtx, routeIn, routeCtx] =>
      runDisconnectedPipeline(
        session.session,
        ClaimGroup.Session,
        LiveMountRequest(path, request),
        ()
      ).flatMap { case (sessionContext, sessionClaims) =>
        runDisconnectedContext(
          session.route,
          path,
          request,
          session.routeInput(sessionContext)
        ).map { case (routeContext, routeClaims) =>
          (sessionContext -> routeContext) -> (sessionClaims ++ routeClaims)
        }
      }

  private def runConnectedContext[R, A, In, Ctx](
    context: LiveRouteContext[R, A, In, Ctx],
    path: A,
    request: Request,
    input: In,
    claims: MountClaims
  ): ZIO[R, LiveMountFailure, Ctx] = context match
    case _: LiveRouteContext.Direct[A] =>
      rejectExtraClaims(claims).as(())
    case environment: LiveRouteContext.Environment[r, A] =>
      rejectExtraClaims(claims) *> ZIO.environmentWith[r](_.get(environment.tag))
    case _: LiveRouteContext.Required[A, Ctx] =>
      rejectExtraClaims(claims).as(input)
    case mounted: LiveRouteContext.Mounted[r, A, In, Ctx] =>
      for
        result <- runConnectedPipeline(
                    mounted.pipeline,
                    ClaimGroup.Route,
                    LiveMountRequest(path, request),
                    input,
                    claims.route
                  )
        (mountedContext, remaining) = result
        _ <- ZIO
               .fail(LiveMountFailure.unauthorized("unexpected route mount claims")).when(
                 remaining.nonEmpty || claims.session.nonEmpty
               )
      yield mountedContext
    case session: LiveRouteContext.SessionMounted[rs, rr, A, sessionCtx, routeIn, routeCtx] =>
      for
        sessionResult <- runConnectedPipeline(
                           session.session,
                           ClaimGroup.Session,
                           LiveMountRequest(path, request),
                           (),
                           claims.session
                         )
        (sessionContext, remainingSession) = sessionResult
        _ <- ZIO
               .fail(LiveMountFailure.unauthorized("unexpected session mount claims"))
               .when(remainingSession.nonEmpty)
        routeContext <- runConnectedContext(
                          session.route,
                          path,
                          request,
                          session.routeInput(sessionContext),
                          MountClaims(Vector.empty, claims.route)
                        )
      yield sessionContext -> routeContext

  private def rejectExtraClaims(claims: MountClaims): IO[LiveMountFailure, Unit] =
    ZIO
      .fail(LiveMountFailure.unauthorized("unexpected mount claims"))
      .when(claims.session.nonEmpty || claims.route.nonEmpty).unit

  private[scalive] def disconnectedParamsContext[Msg, Model](
    mount: MountContext[Msg, Model]
  ): ParamsContext[Msg, Model] = new ParamsContext[Msg, Model]:
    val connection = Connection.Disconnected
    val flash      = mount.flash
    val uploads    = mount.uploads
    val streams    = mount.streams
    val hooks      = mount.hooks
    val nav        = new Navigation:
      private def unsupported(name: String) =
        ZIO.fail(Exception(s"$name is unavailable disconnected"))
      def pushNavigateUnsafe(to: String)    = unsupported("push navigate")
      def replaceNavigateUnsafe(to: String) = unsupported("replace navigate")
      def redirectUnsafe(to: String)        = unsupported("redirect")
      def pushPatchUnsafe(to: String)       = unsupported("push patch")
      def replacePatchUnsafe(to: String)    = unsupported("replace patch")

  final private case class RenderedElement(html: String, trackedStatics: Vector[String])

  private def renderElement[A, Msg](
    view: Signal[A] => HtmlElement[Msg],
    value: A,
    includeDoctype: Boolean = false
  ): Task[RenderedElement] =
    ZIO.fromEither(RenderProgram.compile(view)).flatMap { program =>
      program
        .evaluate(value).flatMap { candidate =>
          ZIO
            .succeed(
              RenderedElement(
                HtmlRenderer.render(candidate.tree, includeDoctype),
                collectTrackedStatics(candidate.tree.root)
              )
            ).ensuring(
              candidate.discard
            )
        }.ensuring(program.close)
    }

  private def collectTrackedStatics(node: EvaluatedNode): Vector[String] = node match
    case element: EvaluatedNode.Element =>
      val tracked = element.attributes.exists(attribute =>
        attribute.name == "phx-track-static" && attribute.value.nonEmpty
      )
      val own =
        if tracked then
          element.attributes.collectFirst {
            case EvaluatedAttribute("href" | "src", Some(AttributeValue.Text(value)), _, _) => value
          }.toVector
        else Vector.empty
      own ++ element.children.flatMap(collectTrackedStatics)
    case flash: EvaluatedNode.Flash => flash.child.toVector.flatMap(collectTrackedStatics)
    case _: EvaluatedNode.Text      => Vector.empty

  private def injectCsrf[Msg](root: HtmlElement[Msg], token: String): HtmlElement[Msg] =
    val csrfMeta = metaTag(nameAttr := "csrf-token", contentAttr := token)
    if root.tag.name == "html" then
      val hasHead = root.contentMods.exists {
        case Mod.Content.Tag(element) => element.tag.name == "head"
        case _                        => false
      }
      val mods = root.mods.map {
        case Mod.Content.Tag(head) if head.tag.name == "head" =>
          Mod.Content.Tag(head.appended(csrfMeta))
        case other => other
      }
      HtmlElement(
        root.tag,
        if hasHead then mods else mods.prepended(Mod.Content.Tag(headTag(csrfMeta)))
      )
    else root.prepended(Mod.Content.Tag(csrfMeta))

  private def withRootAttrs[Msg](
    element: HtmlElement[Msg],
    rootAttrs: Vector[Mod.Attr[Nothing]]
  ): HtmlElement[Msg] =
    val reserved = Set("id", "data-phx-main", "data-phx-session", "data-phx-static")
    def retained(mod: Mod[Msg]): Boolean = mod match
      case Mod.Attr.Static(name, _)                => !reserved(name)
      case Mod.Attr.StaticValueAsPresence(name, _) => !reserved(name)
      case Mod.Attr.SignalValue(name, _)           => !reserved(name)
      case Mod.Attr.SignalOptionalValue(name, _)   => !reserved(name)
      case Mod.Attr.SignalValueAsPresence(name, _) => !reserved(name)
      case _                                       => true
    HtmlElement(element.tag, rootAttrs ++ element.mods.filter(retained))

  private def websocketRoute[R](
    socketPath: PathCodec[Unit],
    routes: Vector[CompiledRoute[R]],
    config: ZioHttpConfig
  ): Route[R, Nothing] =
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

  private def runSocket[R](
    channel: WebSocketChannel,
    routes: Vector[CompiledRoute[R]],
    config: ZioHttpConfig,
    socketRequest: Request,
    csrfCookie: Option[String],
    csrfToken: Option[String]
  ): ZIO[R & Scope, Throwable, Unit] =
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
                          case Right(PhoenixInbound.LivePatch(joinRef, ref, topic, url)) =>
                            offerPatch(root, writer, joinRef, ref, topic, url)
                          case Right(PhoenixInbound.Leave(joinRef, ref, topic)) =>
                            leaveRoot(root, writer, joinRef, ref, topic)
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

  private def joinRoot[R](
    routes: Vector[CompiledRoute[R]],
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
  ): ZIO[R & Scope, Throwable, Unit] =
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
                               staticChanged = staticChanged(
                                 clientTrackedStatics(join.params),
                                 admitted.claims.trackedStatics,
                                 admitted.url
                               ),
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
                  connection <- admitted.route.startConnected(
                                  admitted.url,
                                  request,
                                  admitted.claims,
                                  metadata,
                                  sink
                                )
                  installed <- currentRoot.modify {
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
      case ConnectionOutput.Joined(delta, effects) =>
        update(delta)
          .map(addEffects(_, effects)).flatMap(json =>
            writer.offer(PhoenixOutput.join(joinRef, joinReplyRef, topic, json))
          )
      case ConnectionOutput.JoinedNavigation(delta, navigation, effects) =>
        update(delta)
          .map(addEffects(_, effects)).flatMap(json =>
            writer.offer(PhoenixOutput.join(joinRef, joinReplyRef, topic, json)) *>
              writer.offer(navigationEnvelope(joinRef, topic, navigation))
          )
      case ConnectionOutput.Reply(command, delta, effects) =>
        correlations.modify(current => current.get(command) -> (current - command)).flatMap {
          case Some((eventJoinRef, eventRef)) =>
            update(delta)
              .map(addEffects(_, effects)).flatMap(json =>
                writer.offer(PhoenixOutput.event(eventJoinRef, eventRef, topic, json))
              )
          case None => ZIO.fail(Exception(s"missing event correlation ${command.value}"))
        }
      case ConnectionOutput.ReplyNavigation(command, delta, navigation, effects) =>
        correlations.modify(current => current.get(command) -> (current - command)).flatMap {
          case Some((eventJoinRef, eventRef)) =>
            update(delta)
              .map(addEffects(_, effects)).flatMap(json =>
                writer.offer(PhoenixOutput.event(eventJoinRef, eventRef, topic, json)) *>
                  writer.offer(navigationEnvelope(eventJoinRef, topic, navigation))
              )
          case None => ZIO.fail(Exception(s"missing event correlation ${command.value}"))
        }
      case ConnectionOutput.Rejected(command, _) =>
        correlations.modify(current => current.get(command) -> (current - command)).flatMap {
          case Some((eventJoinRef, eventRef)) =>
            writer.offer(PhoenixOutput.error(eventJoinRef, eventRef, topic, staleReason))
          case None => ZIO.fail(Exception(s"missing event correlation ${command.value}"))
        }
      case ConnectionOutput.Diff(delta, effects) =>
        update(delta)
          .map(addEffects(_, effects)).flatMap(json =>
            writer.offer(PhoenixOutput.diff(joinRef, topic, json))
          )
      case ConnectionOutput.DiffNavigation(delta, navigation, effects) =>
        update(delta)
          .map(addEffects(_, effects)).flatMap(json =>
            writer.offer(PhoenixOutput.diff(joinRef, topic, json)) *>
              writer.offer(navigationEnvelope(joinRef, topic, navigation))
          )
    end match

  private[scalive] def addEffects(
    json: Json.Obj,
    effects: scalive.runtime.kernel.SessionEffects
  ): Json.Obj =
    val titled = effects.pageTitle.fold(json)(value => json.add("t", Json.Str(value)))
    if effects.clientEvents.isEmpty then titled
    else
      titled.add(
        "e",
        Json.Arr(
          effects.clientEvents.map(event => Json.Arr(Json.Str(event.name), event.payload))*
        )
      )

  private def navigationEnvelope(
    joinRef: PhoenixRef,
    topic: String,
    navigation: scalive.runtime.kernel.NavigationOutput
  ): PhoenixEnvelope =
    val kind = navigation.kind match
      case scalive.runtime.kernel.NavigationKind.PushPatch    => "push"
      case scalive.runtime.kernel.NavigationKind.ReplacePatch => "replace"
      case other => throw IllegalStateException(s"unsupported navigation output $other")
    PhoenixOutput.livePatch(joinRef, topic, navigation.destination.encode, kind)

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
                            .offerNamedEvent(
                              command,
                              BindingId.fromEncoded(event.event),
                              payload,
                              event.event,
                              event.value.toJson
                            )
                            .onError(_ => joined.correlations.update(_ - command))
                      }
                }
            }
          case None => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
      case _ => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
    }

  private def offerPatch(
    root: Ref[Option[JoinedRoot]],
    writer: SerialWriter[PhoenixEnvelope],
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    value: String
  ): Task[Unit] =
    root.get.flatMap {
      case Some(joined) if joined.topic == topic =>
        correlatedEventRefs(joined.joinRef, joinRef, ref) match
          case Some(eventRefs) =>
            for
              decoded <-
                ZIO.fromEither(URL.decode(value).left.map(error => Exception(error.getMessage)))
              destination = URL(path = decoded.path, queryParams = decoded.queryParams)
              command <-
                ZIO
                  .fromEither(CommandId.fresh().left.map(error => Exception(error.toString)))
              accepted <- joined.correlations.modify { current =>
                            if current.size >= RootIngressCapacity + 1 then false -> current
                            else true -> current.updated(command, eventRefs)
                          }
              _ <-
                if accepted then
                  joined.connection
                    .offerPatch(command, destination)
                    .onError(_ => joined.correlations.update(_ - command))
                else ZIO.fail(ConnectionError.IngressSaturated(RootIngressCapacity))
            yield ()
          case None => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
      case _ => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
    }

  private def leaveRoot(
    root: Ref[Option[JoinedRoot]],
    writer: SerialWriter[PhoenixEnvelope],
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String
  ): Task[Unit] =
    root
      .modify {
        case Some(joined)
            if joined.topic == topic && correlatedEventRefs(
              joined.joinRef,
              joinRef,
              ref
            ).nonEmpty =>
          Some(joined) -> None
        case current => None -> current
      }.flatMap {
        case Some(joined) =>
          joined.connection.close *> writer.offer(PhoenixOutput.leave(joinRef, ref, topic))
        case None => writer.offer(PhoenixOutput.error(joinRef, ref, topic, staleReason))
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
    canonicalUrl: String,
    routeIdentity: String,
    sessionName: Option[String],
    rootLayoutKey: String,
    mountClaims: MountClaims,
    trackedStatics: Vector[String]
  ): UIO[(String, String)] =
    ZioHttpSecurity
      .issueSession(
        config,
        rootId,
        routeIndex,
        canonicalUrl,
        routeIdentity,
        sessionName,
        rootLayoutKey,
        mountClaims.session,
        mountClaims.route,
        mountClaims.route.nonEmpty,
        trackedStatics
      ).zipPar(
        ZioHttpSecurity.issueStatic(
          config,
          rootId,
          routeIndex,
          canonicalUrl,
          routeIdentity,
          sessionName,
          rootLayoutKey,
          mountClaims.session,
          mountClaims.route,
          mountClaims.route.nonEmpty,
          trackedStatics
        )
      ).flatMap { case tokens @ (session, static) =>
        ZioHttpSecurity
          .verifySession(config, session).zip(ZioHttpSecurity.verifyStatic(config, static)).foldZIO(
            _ =>
              issueRootTokens(
                config,
                rootId,
                routeIndex,
                canonicalUrl,
                routeIdentity,
                sessionName,
                rootLayoutKey,
                mountClaims,
                trackedStatics
              ),
            claims =>
              if claims._1 == claims._2 then ZIO.succeed(tokens)
              else
                issueRootTokens(
                  config,
                  rootId,
                  routeIndex,
                  canonicalUrl,
                  routeIdentity,
                  sessionName,
                  rootLayoutKey,
                  mountClaims,
                  trackedStatics
                )
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
    csrfToken: String,
    inner: String
  ): String =
    def escaped(value: String) = Escaping.escape(value)
    s"<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"csrf-token\" content=\"${escaped(csrfToken)}\"></head><body>$inner</body></html>"

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
  final case class Admitted[-R](
    route: ZioHttp.CompiledRoute[R],
    url: URL,
    claims: ZioHttpSecurity.RootClaims)

  def admit[R](
    routes: Vector[ZioHttp.CompiledRoute[R]],
    config: ZioHttpConfig,
    csrfCookie: Option[String],
    csrfToken: Option[String],
    rootExists: Boolean,
    topic: String,
    join: RootJoin
  ): IO[String, Admitted[R]] =
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
      _ <- ZIO.fail("route identity differs").unless(session.routeIdentity == route.routeIdentity)
      _ <- ZIO.fail("session identity differs").unless(session.sessionIdentity == route.sessionName)
      _ <- ZIO
             .fail("route claim marker differs").unless(
               session.hasRouteClaims == session.routeMountClaims.nonEmpty
             )
      _ <- ZIO.fail("missing root layout identity").when(session.rootLayoutKey.isEmpty)
      _ <- ZIO.fail("route path differs").unless(route.matches(url))
    yield Admitted(route, url, session)
    checked
  end admit
end ZioHttpAdmission
