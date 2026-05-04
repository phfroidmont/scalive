package scalive

import java.util.Base64
import scala.reflect.ClassTag
import scala.util.Random

import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.http.template.Html
import zio.json.ast.Json

import scalive.*
import scalive.socket.ComponentRuntimeState
import scalive.socket.FlashRuntimeState
import scalive.socket.SocketComponentRuntime
import scalive.socket.SocketFlashRuntime
import scalive.socket.SocketNavigationRuntime
import scalive.socket.SocketStreamRuntime
import scalive.socket.StreamRuntimeState

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
              hooksRef      <- Ref.make(LiveHookRuntimeState.root(lv.hooks))
              ctx = LiveContext(
                      staticChanged = false,
                      streams = new SocketStreamRuntime(streamRef),
                      navigation = new SocketNavigationRuntime(navigationRef),
                      flash = new SocketFlashRuntime(flashRef),
                      components = new scalive.socket.SocketComponentUpdateRuntime(componentsRef),
                      hooks = new SocketLiveHookRuntime(hooksRef),
                      nestedLiveViews = new DisconnectedNestedLiveViewRuntime(
                        s"lv:$id",
                        id,
                        tokenConfig,
                        req.url
                      )
                    )
              _               <- SocketFlashRuntime.resetNavigation(flashRef)
              _               <- navigationRef.set(None)
              initModel       <- lv.mount(ctx.mountContext[Msg, Model])
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
                                csrf             = CsrfProtection.prepare(tokenConfig, req)
                                documentWithCsrf = CsrfProtection.injectMeta(document, csrf.value)
                                _ <- ctx.hooks.runAfterRender[Msg, Model](model, ctx)
                              yield LiveRoute.clearFlashCookie(
                                CsrfProtection.addCookie(
                                  Response.html(
                                    Html.raw(
                                      HtmlBuilder.build(
                                        documentWithCsrf,
                                        isRoot = false
                                      )
                                    )
                                  ),
                                  csrf.cookie
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
