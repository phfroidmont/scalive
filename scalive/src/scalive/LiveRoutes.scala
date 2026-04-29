package scalive

import java.util.Base64
import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.Random

import zio.*
import zio.http.*
import zio.http.ChannelEvent.Read
import zio.http.codec.Combiner
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.*
import zio.json.ast.Json
import zio.stream.SubscriptionRef
import zio.stream.ZStream

import scalive.*
import scalive.WebSocketMessage.JoinErrorReason
import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Meta
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.Protocol
import scalive.socket.ComponentRuntimeState
import scalive.socket.FlashRuntimeState
import scalive.socket.SocketComponentRuntime
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketNavigationRuntime
import scalive.socket.SocketStreamRuntime
import scalive.socket.StreamRuntimeState

final case class LiveLayoutContext[+A, +Ctx](
  params: A,
  request: Request,
  currentUrl: URL,
  context: Ctx)

trait LiveLayout[-A, -Ctx]:
  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]

object LiveLayout:
  val identity: LiveLayout[Any, Any] =
    new LiveLayout[Any, Any]:
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]) = content

  def apply[A, Ctx](
    f: (HtmlElement[?], LiveLayoutContext[A, Ctx]) => HtmlElement[?]
  ): LiveLayout[A, Ctx] =
    new LiveLayout[A, Ctx]:
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]) =
        f(content, ctx).asInstanceOf[HtmlElement[Msg]]

trait LiveRootLayout[-A, -Ctx]:
  def key(ctx: LiveLayoutContext[A, Ctx]): String
  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]

object LiveRootLayout:
  val identity: LiveRootLayout[Any, Any] =
    new LiveRootLayout[Any, Any]:
      def key(ctx: LiveLayoutContext[Any, Any]) = "scalive:identity-root"
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]) = content

  def apply[A, Ctx](
    rootKey: String
  )(
    f: (HtmlElement[?], LiveLayoutContext[A, Ctx]) => HtmlElement[?]
  ): LiveRootLayout[A, Ctx] =
    new LiveRootLayout[A, Ctx]:
      def key(ctx: LiveLayoutContext[A, Ctx])                                    = rootKey
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]) =
        f(content, ctx).asInstanceOf[HtmlElement[Msg]]

  def dynamic[A, Ctx](
    rootKey: LiveLayoutContext[A, Ctx] => String
  )(
    f: (HtmlElement[?], LiveLayoutContext[A, Ctx]) => HtmlElement[?]
  ): LiveRootLayout[A, Ctx] =
    new LiveRootLayout[A, Ctx]:
      def key(ctx: LiveLayoutContext[A, Ctx])                                    = rootKey(ctx)
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]) =
        f(content, ctx).asInstanceOf[HtmlElement[Msg]]

final private[scalive] case class LiveSessionPayload(
  sessionName: String,
  flash: Option[String],
  mountClaims: Option[Json],
  hasRouteMountClaims: Boolean,
  rootLayoutKey: String)
    derives JsonCodec

final private[scalive] case class LegacyLiveSessionPayload(
  sessionName: String,
  flash: Option[String],
  mountClaims: Option[Json])
    derives JsonCodec

private[scalive] object LiveSessionPayload:
  private val LegacyRootLayoutKey = "scalive:legacy-root"

  def sign(
    config: TokenConfig,
    liveViewId: String,
    sessionName: String,
    flash: Option[String],
    mountClaims: Option[Json],
    hasRouteMountClaims: Boolean,
    rootLayoutKey: String
  ): String =
    Token.sign(
      config.secret,
      liveViewId,
      LiveSessionPayload(sessionName, flash, mountClaims, hasRouteMountClaims, rootLayoutKey)
    )

  def verify(config: TokenConfig, token: String): Either[String, (String, LiveSessionPayload)] =
    Token
      .verify[LiveSessionPayload](config.secret, token, config.maxAge)
      .map { case (liveViewId, session) => liveViewId -> session }
      .orElse(
        Token
          .verify[LegacyLiveSessionPayload](config.secret, token, config.maxAge)
          .map { case (liveViewId, session) =>
            liveViewId -> LiveSessionPayload(
              session.sessionName,
              session.flash,
              session.mountClaims,
              hasRouteMountClaims = true,
              rootLayoutKey = LegacyRootLayoutKey
            )
          }
      ).orElse(
        Token
          .verify[String](config.secret, token, config.maxAge)
          .map { case (liveViewId, sessionName) =>
            liveViewId -> LiveSessionPayload(
              sessionName,
              None,
              None,
              hasRouteMountClaims = false,
              rootLayoutKey = LegacyRootLayoutKey
            )
          }
      )
end LiveSessionPayload

final private[scalive] case class LiveLayoutLayer[A, Ctx, LayerCtx](
  layout: LiveLayout[A, LayerCtx],
  project: Ctx => LayerCtx):
  def render[Msg](
    content: HtmlElement[Msg],
    params: A,
    request: Request,
    currentUrl: URL,
    context: Ctx
  ): HtmlElement[Msg] =
    layout.render(content, LiveLayoutContext(params, request, currentUrl, project(context)))

  def mapContext[Ctx2](f: Ctx2 => Ctx): LiveLayoutLayer[A, Ctx2, LayerCtx] =
    copy(project = project.compose(f))

final private[scalive] case class LiveRootLayoutLayer[A, Ctx, LayerCtx](
  layout: LiveRootLayout[A, LayerCtx],
  project: Ctx => LayerCtx):
  def key(params: A, request: Request, currentUrl: URL, context: Ctx): String =
    layout.key(LiveLayoutContext(params, request, currentUrl, project(context)))

  def render[Msg](
    content: HtmlElement[Msg],
    params: A,
    request: Request,
    currentUrl: URL,
    context: Ctx
  ): HtmlElement[Msg] =
    layout.render(content, LiveLayoutContext(params, request, currentUrl, project(context)))

  def mapContext[Ctx2](f: Ctx2 => Ctx): LiveRootLayoutLayer[A, Ctx2, LayerCtx] =
    copy(project = project.compose(f))

trait LiveRouteFragment[-R, -Need]:
  private[scalive] def liveRoutes: List[LiveRoute[?, ?, ?, ?, ?, ?]]

sealed private[scalive] trait LiveSessionGroup

private[scalive] object LiveSessionGroup:
  case object Default extends LiveSessionGroup

  final class Named private[LiveSessionGroup] (val name: String) extends LiveSessionGroup

  def named(name: String): LiveSessionGroup =
    new Named(name)

final class LiveRoute[R, A, -Need, Ctx, Msg, Model] private[scalive] (
  private[scalive] val pathCodec: PathCodec[A],
  private val liveViewBuilder: (A, Request, Ctx) => LiveView[Msg, Model],
  private[scalive] val msgClassTag: ClassTag[Msg],
  private[scalive] val mountPipeline: LiveMountPipeline[R, A, Need, Ctx],
  private[scalive] val liveLayouts: List[LiveLayoutLayer[A, Ctx, ?]] = Nil,
  private[scalive] val rootLayout: Option[LiveRootLayoutLayer[A, Ctx, ?]] = None,
  private[scalive] val sessionName: String = "default",
  private[scalive] val sessionGroup: LiveSessionGroup = LiveSessionGroup.Default,
  private[scalive] val hasRouteMountAspect: Boolean = false)
    extends LiveRouteFragment[R, Need]:

  private[scalive] def liveRoutes: List[LiveRoute[?, ?, ?, ?, ?, ?]] =
    List(this)

  private[scalive] def withSessionName(
    name: String,
    group: LiveSessionGroup
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    new LiveRoute(
      pathCodec,
      liveViewBuilder,
      msgClassTag,
      mountPipeline,
      liveLayouts,
      rootLayout,
      name,
      group,
      hasRouteMountAspect
    )

  private[scalive] def withSession[R1, SessionCtx](
    session: LiveSessionBuilder[R1, SessionCtx]
  )(using ev: SessionCtx <:< Need
  ): LiveRoute[R & R1, A, Any, (SessionCtx, Ctx), Msg, Model] =
    val route          = this
    val sessionLayouts = session.liveLayouts.map { layer =>
      LiveLayoutLayer[A, (SessionCtx, Ctx), Any](
        layer.layout.asInstanceOf[LiveLayout[A, Any]],
        context => layer.project(context._1).asInstanceOf[Any]
      )
    }
    val routeLayouts = liveLayouts.map(_.mapContext[(SessionCtx, Ctx)](_._2))
    val selectedRoot = rootLayout
      .map(_.mapContext[(SessionCtx, Ctx)](_._2))
      .orElse(
        session.rootLayout.map(layer =>
          LiveRootLayoutLayer[A, (SessionCtx, Ctx), Any](
            layer.layout.asInstanceOf[LiveRootLayout[A, Any]],
            context => layer.project(context._1).asInstanceOf[Any]
          )
        )
      )
    new LiveRoute(
      pathCodec,
      (params, request, context) => liveViewBuilder(params, request, context._2),
      msgClassTag,
      new LiveMountPipeline[R & R1, A, Any, (SessionCtx, Ctx)]:
        def runDisconnected(request: LiveMountRequest[A], input: Any) =
          for
            sessionResult <- session.mountPipeline.runDisconnected(
                               LiveMountRequest(request.params, request.request),
                               input
                             )
            routeResult <- route.mountPipeline.runDisconnected(
                             request,
                             ev(sessionResult._2)
                           )
          yield Json.Arr(
            Chunk(sessionResult._1, routeResult._1)
          ) -> (sessionResult._2 -> routeResult._2)

        def runConnected(claims: Option[Json], request: LiveMountRequest[A], input: Any) =
          claims match
            case Some(Json.Arr(values)) if values.length == 2 =>
              for
                sessionContext <- session.mountPipeline.runConnected(
                                    Some(values(0)),
                                    LiveMountRequest(request.params, request.request),
                                    input
                                  )
                routeContext <- route.mountPipeline.runConnected(
                                  Some(values(1)),
                                  request,
                                  ev(sessionContext)
                                )
              yield sessionContext -> routeContext
            case _ =>
              ZIO.fail(LiveMountFailure.unauthorized("Invalid session LiveMountAspect claims"))
      ,
      sessionLayouts ++ routeLayouts,
      selectedRoot,
      session.name,
      session.group,
      hasRouteMountAspect
    )
  end withSession

  private[scalive] def buildLiveView(
    params: A,
    request: Request,
    mountContext: Ctx
  ): LiveView[Msg, Model] =
    liveViewBuilder(params, request, mountContext)

  private[scalive] def renderLiveRoot(
    lv: LiveView[Msg, Model],
    model: Model,
    params: A,
    request: Request,
    currentUrl: URL,
    mountContext: Ctx,
    globalLayouts: List[LiveLayout[Any, Any]]
  ): HtmlElement[Msg] =
    applyLiveLayouts(lv.render(model), params, request, currentUrl, mountContext, globalLayouts)

  private[scalive] def socketRenderRoot(
    lv: LiveView[Msg, Model],
    params: A,
    request: Request,
    mountContext: Ctx,
    globalLayouts: List[LiveLayout[Any, Any]]
  ): (Model, URL) => HtmlElement[Msg] =
    (model, currentUrl) =>
      renderLiveRoot(lv, model, params, request, currentUrl, mountContext, globalLayouts)

  private def applyLiveLayouts[Msg](
    content: HtmlElement[Msg],
    params: A,
    request: Request,
    currentUrl: URL,
    mountContext: Ctx,
    globalLayouts: List[LiveLayout[Any, Any]]
  ): HtmlElement[Msg] =
    val globalLayers = globalLayouts.map(layout =>
      LiveLayoutLayer[A, Ctx, Any](layout.asInstanceOf[LiveLayout[A, Any]], identity)
    )
    (globalLayers ++ liveLayouts).foldRight(content) { (layer, current) =>
      layer.render(current, params, request, currentUrl, mountContext)
    }

  private[scalive] def renderRootHtml[Msg](
    content: HtmlElement[Msg],
    params: A,
    request: Request,
    currentUrl: URL,
    mountContext: Ctx,
    globalRootLayout: LiveRootLayout[Any, Any]
  ): HtmlElement[Msg] =
    rootLayer(globalRootLayout).render(content, params, request, currentUrl, mountContext)

  private[scalive] def rootLayoutKey(
    params: A,
    request: Request,
    currentUrl: URL,
    mountContext: Ctx,
    globalRootLayout: LiveRootLayout[Any, Any]
  ): String =
    rootLayer(globalRootLayout).key(params, request, currentUrl, mountContext)

  private[scalive] def trackedStatic(
    params: A,
    request: Request,
    currentUrl: URL,
    mountContext: Ctx,
    globalLayouts: List[LiveLayout[Any, Any]],
    globalRootLayout: LiveRootLayout[Any, Any]
  ): List[String] =
    val live = applyLiveLayouts(div(), params, request, currentUrl, mountContext, globalLayouts)
    StaticTracking.collect(
      renderRootHtml(live, params, request, currentUrl, mountContext, globalRootLayout)
    )

  private def rootLayer(
    globalRootLayout: LiveRootLayout[Any, Any]
  ): LiveRootLayoutLayer[A, Ctx, ?] =
    rootLayout.getOrElse(
      LiveRootLayoutLayer[A, Ctx, Any](
        globalRootLayout.asInstanceOf[LiveRootLayout[A, Any]],
        identity
      )
    )

  private[scalive] def toZioRoute(
    globalLayouts: List[LiveLayout[Any, Any]],
    globalRootLayout: LiveRootLayout[Any, Any],
    tokenConfig: TokenConfig
  )(using Any <:< Need
  ): Route[R, Throwable] =
    Method.GET / pathCodec -> handler { (params: A, req: Request) =>
      val initialInput = summon[Any <:< Need](())
      val id: String   =
        s"phx-${Base64.getUrlEncoder().withoutPadding().encodeToString(Random().nextBytes(12))}"
      val initialFlash = LiveRoute.flashFromRequest(req, tokenConfig)
      val response     = mountPipeline
        .runDisconnected(LiveMountRequest(params, req), initialInput).foldZIO(
          ZIO.succeed,
          { case (mountClaims, mountContext) =>
            val lv = buildLiveView(params, req, mountContext)
            for
              streamRef     <- Ref.make(StreamRuntimeState.empty)
              flashRef      <- Ref.make(FlashRuntimeState(initialFlash))
              componentsRef <- Ref.make(ComponentRuntimeState.empty)
              navigationRef <- Ref.make(Option.empty[LiveNavigationCommand])
              hooksRef      <- Ref.make(LiveHookRuntimeState.empty)
              ctx = LiveContext(
                      staticChanged = false,
                      streams = new SocketStreamRuntime(streamRef),
                      navigation = new SocketNavigationRuntime(navigationRef),
                      flash = new SocketFlashRuntime(flashRef),
                      components = new scalive.socket.SocketComponentUpdateRuntime(componentsRef),
                      hooks = new SocketLiveHookRuntime(hooksRef),
                      nestedLiveViews = new DisconnectedNestedLiveViewRuntime(
                        s"lv:$id",
                        tokenConfig,
                        req.url
                      )
                    )
              _               <- SocketFlashRuntime.resetNavigation(flashRef)
              _               <- navigationRef.set(None)
              initModel       <- LiveIO.toZIO(lv.mount).provide(ZLayer.succeed(ctx))
              mountNavigation <- navigationRef.getAndSet(None)
              lifecycle       <- LiveRoute.runInitialHandleParams(
                             lv,
                             initModel,
                             req.url,
                             ctx,
                             navigationRef,
                             flashRef,
                             mountNavigation
                           )
              response <- lifecycle match
                            case LiveRoute.InitialLifecycleOutcome.Render(model) =>
                              for
                                flash <- flashRef.get
                                rootKey = rootLayoutKey(
                                            params,
                                            req,
                                            req.url,
                                            mountContext,
                                            globalRootLayout
                                          )
                                token = LiveSessionPayload.sign(
                                          tokenConfig,
                                          id,
                                          sessionName,
                                          FlashToken.encode(tokenConfig, flash.values),
                                          Some(mountClaims),
                                          hasRouteMountAspect,
                                          rootKey
                                        )
                                el = applyLiveLayouts(
                                       lv.render(model),
                                       params,
                                       req,
                                       req.url,
                                       mountContext,
                                       globalLayouts
                                     )
                                rendered <- SocketComponentRuntime.renderRoot(
                                              div(
                                                idAttr      := id,
                                                phx.main    := true,
                                                phx.session := token,
                                                el
                                              ),
                                              componentsRef,
                                              ctx
                                            )
                                document = renderRootHtml(
                                             rendered,
                                             params,
                                             req,
                                             req.url,
                                             mountContext,
                                             globalRootLayout
                                           )
                                _ <- ctx.hooks.runAfterRender(model, ctx)
                              yield LiveRoute.clearFlashCookie(
                                Response.html(
                                  Html.raw(
                                    HtmlBuilder.build(
                                      document,
                                      isRoot = false
                                    )
                                  )
                                ),
                                req
                              )
                            case LiveRoute.InitialLifecycleOutcome.Redirect(url) =>
                              SocketFlashRuntime
                                .navigationValues(flashRef).map(flash =>
                                  LiveRoute.addFlashCookie(
                                    Response.redirect(url),
                                    tokenConfig,
                                    flash
                                  )
                                )
            yield response
            end for
          }
        )
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

  private[scalive] def flashFromRequest(
    request: Request,
    tokenConfig: TokenConfig
  ): Map[String, String] =
    request
      .cookie(FlashToken.CookieName)
      .flatMap(cookie => FlashToken.decode(tokenConfig, cookie.content))
      .getOrElse(Map.empty)

  private[scalive] def addFlashCookie(
    response: Response,
    tokenConfig: TokenConfig,
    values: Map[String, String]
  ): Response =
    FlashToken.encode(tokenConfig, values) match
      case Some(token) =>
        response.addCookie(
          Cookie.Response(
            FlashToken.CookieName,
            token,
            path = Some(Path.root),
            maxAge = Some(60.seconds)
          )
        )
      case None => response

  private[scalive] def clearFlashCookie(response: Response, request: Request): Response =
    if request.cookie(FlashToken.CookieName).isDefined then
      response.addCookie(
        Cookie.Response(
          FlashToken.CookieName,
          "",
          path = Some(Path.root),
          maxAge = Some(Duration.Zero)
        )
      )
    else response

  private[scalive] def runInitialHandleParams[Msg, Model](
    lv: LiveView[Msg, Model],
    initModel: Model,
    url: URL,
    ctx: LiveContext,
    navigationRef: Ref[Option[LiveNavigationCommand]],
    flashRef: Ref[FlashRuntimeState],
    initialNavigation: Option[LiveNavigationCommand]
  ): Task[InitialLifecycleOutcome[Model]] =
    def applyNavigation(model: Model, command: LiveNavigationCommand)
      : Task[InitialLifecycleOutcome[Model]] =
      val destination = command match
        case LiveNavigationCommand.PushPatch(to)       => to
        case LiveNavigationCommand.ReplacePatch(to)    => to
        case LiveNavigationCommand.PushNavigate(to)    => to
        case LiveNavigationCommand.ReplaceNavigate(to) => to
        case LiveNavigationCommand.Redirect(to)        => to
      LivePatchUrl.resolve(destination, url) match
        case Right(redirectUrl) =>
          ZIO.succeed(InitialLifecycleOutcome.Redirect(redirectUrl))
        case Left(error) =>
          ZIO
            .logWarning(
              s"Could not decode initial navigation URL '$destination': $error"
            )
            .as(InitialLifecycleOutcome.Render(model))

    initialNavigation match
      case Some(command) => applyNavigation(initModel, command)
      case None          =>
        for
          _          <- SocketFlashRuntime.resetNavigation(flashRef)
          _          <- navigationRef.set(None)
          model      <- LiveViewParamsRuntime.runHandleParams(lv, initModel, url, ctx)
          navigation <- navigationRef.getAndSet(None)
          result     <- navigation match
                      case None          => ZIO.succeed(InitialLifecycleOutcome.Render(model))
                      case Some(command) => applyNavigation(model, command)
        yield result
  end runInitialHandleParams
end LiveRoute

final private[scalive] class LiveRouteGroup[-R, -Need](
  private[scalive] val liveRoutes: List[LiveRoute[?, ?, ?, ?, ?, ?]])
    extends LiveRouteFragment[R, Need]

class LiveRouteSeed[A] private[scalive] (pathCodec: PathCodec[A]):
  def /[B](that: PathCodec[B])(using combiner: Combiner[A, B]): LiveRouteSeed[combiner.Out] =
    LiveRouteSeed(pathCodec / that)

  private def base[Ctx]: LiveRouteBuilder[Any, A, Ctx, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      LiveMountPipeline.identity[A, Ctx],
      Nil,
      None,
      hasRouteMountAspect = false
    )

  infix def @@[R, In, Claims, Out, Result](
    aspect: LiveMountAspect[R, A, In, Claims, Out]
  )(using append: ContextAppend.Aux[In, Out, Result]
  ): LiveRouteBuilder[R, A, In, Result] =
    LiveRouteBuilder(
      pathCodec,
      LiveMountPipeline.identity[A, In] ++ aspect.runtime,
      Nil,
      None,
      hasRouteMountAspect = true
    )

  infix def @@[Ctx](layout: LiveLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
    base[Ctx] @@ layout

  @targetName("rootLayoutModifier")
  infix def @@[Ctx](layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
    base[Ctx] @@ layout

  def apply[Msg: ClassTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[Any, A, Any, Any, Msg, Model] =
    base[Any].apply(view)

  @targetName("arrowView")
  infix def ->[Msg: ClassTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[Any, A, Any, Any, Msg, Model] =
    apply(view)

  @targetName("applyFull")
  def apply[Ctx, Msg: ClassTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Ctx, Ctx, Msg, Model] =
    base[Ctx].apply(builder)

  @targetName("arrowFull")
  infix def ->[Ctx, Msg: ClassTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Ctx, Ctx, Msg, Model] =
    apply(builder)

  @targetName("applyRequestParams")
  def apply[Msg: ClassTag, Model](
    builder: (A, Request) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    base[Any].apply((params, request, _) => builder(params, request))

  @targetName("arrowRequestParams")
  infix def ->[Msg: ClassTag, Model](
    builder: (A, Request) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    apply(builder)

  @targetName("applyRequest")
  def apply[Msg: ClassTag, Model](
    builder: Request => LiveView[Msg, Model]
  )(using A =:= Unit
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    base[Any].apply((_, request, _) => builder(request))

  @targetName("arrowRequest")
  infix def ->[Msg: ClassTag, Model](
    builder: Request => LiveView[Msg, Model]
  )(using A =:= Unit
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    apply(builder)

  @targetName("applyTuple2")
  def apply[C1, C2, Msg: ClassTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, (C1, C2), (C1, C2), Msg, Model] =
    base[(C1, C2)].apply(builder)

  @targetName("arrowTuple2")
  infix def ->[C1, C2, Msg: ClassTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, (C1, C2), (C1, C2), Msg, Model] =
    apply(builder)
end LiveRouteSeed

final class LiveRouteBuilder[R, A, -Need, Ctx] private[scalive] (
  pathCodec: PathCodec[A],
  mountPipeline: LiveMountPipeline[R, A, Need, Ctx],
  liveLayouts: List[LiveLayoutLayer[A, Ctx, ?]],
  rootLayout: Option[LiveRootLayoutLayer[A, Ctx, ?]],
  hasRouteMountAspect: Boolean):

  infix def @@[R1, Claims, Out, Result](
    aspect: LiveMountAspect[R1, A, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveRouteBuilder[R & R1, A, Need, Result] =
    val projectPrevious = (result: Result) => append.left(result)
    LiveRouteBuilder(
      pathCodec,
      mountPipeline ++ aspect.runtime,
      liveLayouts.map(_.mapContext(projectPrevious)),
      rootLayout.map(_.mapContext(projectPrevious)),
      hasRouteMountAspect = true
    )

  infix def @@(layout: LiveLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[A, Ctx, Ctx](layout, identity),
      rootLayout,
      hasRouteMountAspect
    )

  @targetName("rootLayoutModifier")
  infix def @@(layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[A, Ctx, Ctx](layout, identity)),
      hasRouteMountAspect
    )

  def apply[Msg: ClassTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, _, _) => view)

  @targetName("arrowView")
  infix def ->[Msg: ClassTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(view)

  @targetName("applyFull")
  def apply[Msg: ClassTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    new LiveRoute(
      pathCodec,
      builder,
      summon[ClassTag[Msg]],
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect = hasRouteMountAspect
    )

  @targetName("arrowFull")
  infix def ->[Msg: ClassTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  @targetName("applyTuple2")
  def apply[C1, C2, Msg: ClassTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((params, request, context) =>
      val tuple = ev(context)
      builder(params, request, tuple._1, tuple._2)
    )

  @targetName("arrowTuple2")
  infix def ->[C1, C2, Msg: ClassTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)
end LiveRouteBuilder

final class LiveSessionSeed private[scalive] (val name: String):
  private val group = LiveSessionGroup.named(name)

  def apply[R](route: LiveRouteFragment[R, Any], routes: LiveRouteFragment[R, Any]*)
    : LiveRouteFragment[R, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      Nil,
      None,
      group
    )(route, routes*)

  infix def @@[R, Claims, Out, Result](
    aspect: LiveMountAspect[R, Any, Any, Claims, Out]
  )(using ContextAppend.Aux[Any, Out, Result]
  ): LiveSessionBuilder[R, Result] =
    LiveSessionBuilder(
      name,
      LiveMountPipeline.identity[Any, Any] ++ aspect.runtime,
      Nil,
      None,
      group
    )

  infix def @@(layout: LiveLayout[Any, Any]): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      List(LiveLayoutLayer[Any, Any, Any](layout, identity)),
      None,
      group
    )

  @targetName("rootLayoutModifier")
  infix def @@(layout: LiveRootLayout[Any, Any]): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      Nil,
      Some(LiveRootLayoutLayer[Any, Any, Any](layout, identity)),
      group
    )
end LiveSessionSeed

final class LiveSessionBuilder[R, Ctx] private[scalive] (
  private[scalive] val name: String,
  private[scalive] val mountPipeline: LiveMountPipeline[R, Any, Any, Ctx],
  private[scalive] val liveLayouts: List[LiveLayoutLayer[Any, Ctx, ?]],
  private[scalive] val rootLayout: Option[LiveRootLayoutLayer[Any, Ctx, ?]],
  private[scalive] val group: LiveSessionGroup):

  def apply[R1, Need](
    route: LiveRouteFragment[R1, Need],
    routes: LiveRouteFragment[R1, Need]*
  )(using Ctx <:< Need
  ): LiveRouteFragment[R & R1, Any] =
    val liveRoutes = (route +: routes.toList)
      .flatMap(_.liveRoutes)
      .asInstanceOf[List[LiveRoute[R1, ?, Need, ?, ?, ?]]]
      .map(_.withSession(this))
    new LiveRouteGroup[R & R1, Any](liveRoutes)

  infix def @@[R1, Claims, Out, Result](
    aspect: LiveMountAspect[R1, Any, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveSessionBuilder[R & R1, Result] =
    val projectPrevious = (result: Result) => append.left(result)
    LiveSessionBuilder(
      name,
      mountPipeline ++ aspect.runtime,
      liveLayouts.map(_.mapContext(projectPrevious)),
      rootLayout.map(_.mapContext(projectPrevious)),
      group
    )

  infix def @@(layout: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[Any, Ctx, Ctx](layout, identity),
      rootLayout,
      group
    )

  @targetName("rootLayoutModifier")
  infix def @@(layout: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[Any, Ctx, Ctx](layout, identity)),
      group
    )
end LiveSessionBuilder

final case class LiveSocketMount(pathCodec: PathCodec[Unit])
final case class LiveTokenConfig(config: TokenConfig)

final class LiveRouter[R] private[scalive] (
  globalLayouts: List[LiveLayout[Any, Any]],
  globalRootLayout: LiveRootLayout[Any, Any],
  liveSocketMount: PathCodec[Unit],
  tokenConfig: TokenConfig):

  infix def @@(layout: LiveLayout[Any, Any]): LiveRouter[R] =
    LiveRouter(globalLayouts :+ layout, globalRootLayout, liveSocketMount, tokenConfig)

  @targetName("rootLayoutModifier")
  infix def @@(layout: LiveRootLayout[Any, Any]): LiveRouter[R] =
    LiveRouter(globalLayouts, layout, liveSocketMount, tokenConfig)

  @targetName("socketMountModifier")
  infix def @@(mount: LiveSocketMount): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, mount.pathCodec, tokenConfig)

  @targetName("tokenConfigModifier")
  infix def @@(config: LiveTokenConfig): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, liveSocketMount, config.config)

  def apply[R1](route: LiveRouteFragment[R1, Any], routes: LiveRouteFragment[R1, Any]*)
    : Routes[R & R1, Nothing] =
    buildRoutes[R1](route +: routes.toList)

  private def buildRoutes[R1](routes: List[LiveRouteFragment[?, Any]]): Routes[R & R1, Nothing] =
    val liveRoutes = routes
      .flatMap(_.liveRoutes)
      .asInstanceOf[List[LiveRoute[R & R1, ?, Any, ?, ?, ?]]]
    LiveRoutes.validateLiveRoutes(liveRoutes)
    new LiveRoutesRuntime(
      globalLayouts,
      globalRootLayout,
      liveRoutes,
      liveSocketMount,
      tokenConfig
    ).routes
end LiveRouter

object Live:
  val router: LiveRouter[Any] =
    LiveRouter(Nil, LiveRootLayout.identity, PathCodec.empty / "live", TokenConfig.default)

  def route[A](path: PathCodec[A]): LiveRouteSeed[A] =
    LiveRouteSeed(path)

  def session(name: String): LiveSessionSeed =
    LiveSessionSeed(name)

  def socketAt(path: PathCodec[Unit]): LiveSocketMount =
    LiveSocketMount(path)

  def tokenConfig(config: TokenConfig): LiveTokenConfig =
    LiveTokenConfig(config)

val live: LiveRouteSeed[Unit] = LiveRouteSeed(PathCodec.empty)

object LiveRoutes:

  private[scalive] def validateLiveRoutes(
    routes: List[LiveRoute[?, ?, Any, ?, ?, ?]]
  ): Unit =
    val duplicatePaths = routes
      .map(_.pathCodec.render)
      .groupBy(identity)
      .collect { case (path, occurrences) if occurrences.size > 1 => path }
      .toList
      .sorted

    if duplicatePaths.nonEmpty then
      throw new IllegalArgumentException(
        s"Duplicate LiveRoutes paths: ${duplicatePaths.mkString(", ")}"
      )

    val duplicateSessions = routes
      .groupBy(_.sessionName)
      .collect {
        case (name, sessionRoutes) if sessionRoutes.map(_.sessionGroup).distinct.size > 1 => name
      }
      .toList
      .sorted

    if duplicateSessions.nonEmpty then
      throw new IllegalArgumentException(
        s"Duplicate LiveSession names: ${duplicateSessions.mkString(", ")}. " +
          "LiveSession routes must be declared in a single named group."
      )
end LiveRoutes

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
    initialUrl: URL,
    initialFlash: Map[String, String] = Map.empty,
    renderRoot: Option[(Model, URL) => HtmlElement[Msg]] = None
  )(using ClassTag[Msg]
  ): RIO[Scope, Unit] =
    val rootRenderer = renderRoot.getOrElse((model: Model, _: URL) => lv.render(model))
    sockets
      .updateZIO { m =>
        m.get(id) match
          case Some(socket) =>
            socket.shutdown *>
              Socket
                .start(
                  id,
                  token,
                  lv,
                  ctx,
                  meta,
                  tokenConfig,
                  initialUrl,
                  initialFlash,
                  Some(rootRenderer)
                )
                .map(m.updated(id, _))
          case None =>
            Socket
              .start(
                id,
                token,
                lv,
                ctx,
                meta,
                tokenConfig,
                initialUrl,
                initialFlash,
                Some(rootRenderer)
              )
              .map(m.updated(id, _))
      }.flatMap(_ => ZIO.logDebug(s"LiveView joined $id"))
  end join

  def nestedRuntime(parentTopic: String): NestedLiveViewRuntime =
    new SocketNestedLiveViewRuntime(parentTopic, tokenConfig, nestedEntries)

  private[scalive] def nestedEntry(topic: String): UIO[Option[NestedLiveViewEntry]] =
    nestedEntries.get.map(_.get(topic))

  private[scalive] def socket(id: String): UIO[Option[Socket[?, ?]]] =
    sockets.get.map(_.get(id))

  private def rootTopic(entries: Map[String, NestedLiveViewEntry], topic: String): String =
    entries.get(topic) match
      case Some(entry) => rootTopic(entries, entry.parentTopic)
      case None        => topic

  private def takeNestedNavigationFlash(id: String): UIO[Map[String, String]] =
    for
      entries        <- nestedEntries.get
      currentSockets <- sockets.get
      descendantTopics = entries.keysIterator
                           .filter(topic => rootTopic(entries, topic) == id)
                           .toList
      flashes <- ZIO.foreach(descendantTopics)(topic =>
                   currentSockets
                     .get(topic)
                     .fold(ZIO.succeed(Map.empty[String, String]))(_.takeNavigationFlash)
                 )
    yield flashes.foldLeft(Map.empty[String, String])(_ ++ _)

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
                      case (topic, entry) if entry.parentTopic == id && !entry.sticky => topic
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
        case Some(socket) =>
          for
            flash <- takeNestedNavigationFlash(id)
            _     <- ZIO.when(flash.nonEmpty)(socket.replaceNavigationFlash(flash))
            reply <- socket.livePatch(url, meta)
          yield reply
        case None => ZIO.succeed(Payload.okReply(LiveResponse.Empty))
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

final private[scalive] class LiveRoutesRuntime[R](
  globalLayouts: List[LiveLayout[Any, Any]],
  globalRootLayout: LiveRootLayout[Any, Any],
  liveRoutes: List[LiveRoute[R, ?, Any, ?, ?, ?]],
  liveSocketMount: PathCodec[Unit],
  tokenConfig: TokenConfig):

  private val socketApp: WebSocketApp[R] =
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
    val clientStatics = join.static.orElse(StaticTracking.clientListFromParams(join.params))
    val rootSession   = verifyRootSession(message.topic, join.session)
    val initialFlash  = join.flash
      .orElse(rootSession.flatMap(_.flash))
      .flatMap(FlashToken.decode(tokenConfig, _))
      .getOrElse(Map.empty)

    decodeJoinUrl(join) match
      case Left(error) =>
        ZIO.logWarning(error).as(Some(joinErrorReply(message, JoinErrorReason.Stale)))
      case Right(decodedUrl) =>
        liveChannel
          .joinNested(message.topic, join.session, staticChanged = false, message.meta, decodedUrl)
          .flatMap {
            case Some(reason) => ZIO.succeed(Some(joinErrorReply(message, reason)))
            case None         =>
              val req = Request(url = decodedUrl)
              liveRoutes.iterator
                .map(route =>
                  route.pathCodec
                    .decode(req.path)
                    .toOption
                    .map(pathParams =>
                      rootSession match
                        case Some(session) if session.sessionName == route.sessionName =>
                          if isAuthorizedRootJoin(session, route, join) then
                            route.mountPipeline
                              .runConnected(
                                session.mountClaims,
                                LiveMountRequest(pathParams, req),
                                ()
                              ).foldZIO(
                                failure => mountFailureReply(message, failure).map(Some(_)),
                                mountContext =>
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
                                  val staticChanged =
                                    StaticTracking.staticChanged(clientStatics, serverStatics)
                                  val ctx = LiveContext(
                                    staticChanged = staticChanged,
                                    nestedLiveViews = liveChannel.nestedRuntime(message.topic)
                                  )
                                  val lv = route.buildLiveView(pathParams, req, mountContext)
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
                                      ZIO.succeed(
                                        Some(joinErrorReply(message, JoinErrorReason.Unauthorized))
                                      )
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
                                            ZIO.succeed(
                                              Some(joinErrorReply(message, JoinErrorReason.Stale))
                                            )
                                        )
                              )
                          else
                            ZIO.logWarning(
                              s"Rejecting live redirect to ${decodedUrl.path.encode}: route-specific mount claims require a fresh HTTP render"
                            ) *>
                              ZIO.succeed(
                                Some(joinErrorReply(message, JoinErrorReason.Unauthorized))
                              )
                        case _ =>
                          ZIO.succeed(Some(joinErrorReply(message, JoinErrorReason.Unauthorized)))
                    )
                )
                .collectFirst { case Some(joinAction) => joinAction }
                .getOrElse(ZIO.succeed(None))
          }
    end match
  end handleJoin

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
            Method.GET / liveSocketMount / "websocket" -> handler(socketApp.toResponse)
          )
      ).handleErrorZIO(e =>
        ZIO.logErrorCause(Cause.fail(e)).as(Response(status = Status.InternalServerError))
      )
end LiveRoutesRuntime
