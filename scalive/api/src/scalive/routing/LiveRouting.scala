package scalive

import scala.annotation.implicitNotFound
import scala.annotation.targetName

import zio.IO
import zio.RIO
import zio.Tag
import zio.Task
import zio.URIO
import zio.ZIO
import zio.http.codec.{Combiner, PathCodec, QueryCodec}
import zio.http.{Request, Response}
import zio.schema.Schema

/** A typed session pipeline whose individual claim types remain hidden until interpretation. */
sealed private[scalive] trait LiveSessionMountPipeline[R, In, Ctx]:
  def andThen[R1, Claims, Out, Result](
    next: LiveSessionMountAspect[R1, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveSessionMountPipeline[R & R1, In, Result] =
    LiveSessionMountPipeline.Then(this, next, append)

  def admitThen[R1, Claims, Out, Result, Id](
    next: LiveSessionMountAspect[R1, Ctx, Claims, Out],
    connectionId: Claims => Id,
    connections: Tag[LiveConnections[Id]]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveSessionMountPipeline[R & R1 & LiveConnections[Id], In, Result] =
    LiveSessionMountPipeline.Controlled(this, next, connectionId, connections, append)

object LiveSessionMountPipeline:
  final case class Identity[Ctx]() extends LiveSessionMountPipeline[Any, Ctx, Ctx]

  final case class Then[R, R1, In, Ctx, Claims, Out, Result](
    previous: LiveSessionMountPipeline[R, In, Ctx],
    aspect: LiveSessionMountAspect[R1, Ctx, Claims, Out],
    append: ContextAppend.Aux[Ctx, Out, Result])
      extends LiveSessionMountPipeline[R & R1, In, Result]

  final case class Controlled[R, R1, In, Ctx, Claims, Out, Result, Id](
    previous: LiveSessionMountPipeline[R, In, Ctx],
    aspect: LiveSessionMountAspect[R1, Ctx, Claims, Out],
    connectionId: Claims => Id,
    connections: Tag[LiveConnections[Id]],
    append: ContextAppend.Aux[Ctx, Out, Result])
      extends LiveSessionMountPipeline[R & R1 & LiveConnections[Id], In, Result]

/** A typed route pipeline that reruns without serialized claims before every route mount. */
sealed private[scalive] trait LiveRouteMountPipeline[R, A, In, Ctx]:
  def andThen[R1, Out, Result](
    next: LiveRouteMountAspect[R1, A, Ctx, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveRouteMountPipeline[R & R1, A, In, Result] =
    LiveRouteMountPipeline.Then(this, next, append)

object LiveRouteMountPipeline:
  final case class Identity[A, Ctx]() extends LiveRouteMountPipeline[Any, A, Ctx, Ctx]

  final case class Then[R, R1, A, In, Ctx, Out, Result](
    previous: LiveRouteMountPipeline[R, A, In, Ctx],
    aspect: LiveRouteMountAspect[R1, A, Ctx, Out],
    append: ContextAppend.Aux[Ctx, Out, Result])
      extends LiveRouteMountPipeline[R & R1, A, In, Result]

/** Selects how a registered route obtains the context passed to its factory and layouts. */
sealed private[scalive] trait LiveRouteContext[R, A, In, Ctx]

object LiveRouteContext:
  final case class Direct[A]()        extends LiveRouteContext[Any, A, Any, Any]
  final case class Required[A, Ctx]() extends LiveRouteContext[Any, A, Ctx, Ctx]
  final case class Mounted[R, A, In, Ctx](pipeline: LiveRouteMountPipeline[R, A, In, Ctx])
      extends LiveRouteContext[R, A, In, Ctx]

  /** Retains the lifecycle order and the proof that session context supplies route input. */
  final case class SessionMounted[RS, RR, A, SessionCtx, RouteIn, RouteCtx](
    session: LiveSessionMountPipeline[RS, Any, SessionCtx],
    route: LiveRouteContext[RR, A, RouteIn, RouteCtx],
    routeInput: SessionCtx => RouteIn)
      extends LiveRouteContext[RS & RR, A, Any, (SessionCtx, RouteCtx)]

/** The sole existential boundary joining a registered route to the heterogeneous catalog. */
sealed private[scalive] trait LiveRouteDefinition[A]:
  type Input

  private[scalive] def withSession[R, SessionCtx](
    pipeline: LiveSessionMountPipeline[R, Any, SessionCtx],
    sessionGuards: LiveConnectedTurnGuard[SessionCtx],
    sessionInitializers: LiveConnectedResourceInitializers[SessionCtx],
    sessionLayouts: Vector[LiveLayout[Any, SessionCtx]],
    sessionRootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Input
  ): LiveRouteDefinition[A] { type Input = Any }

object LiveRouteDefinition:
  final case class Route[RC, RF, A, P, In, Ctx](
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsCodec[A, P],
    context: LiveRouteContext[RC, A, In, Ctx],
    factory: Ctx => URIO[RF, LiveRouteView.Mounted[P]],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Input = In

    def withSession[R1, SessionCtx](
      pipeline: LiveSessionMountPipeline[R1, Any, SessionCtx],
      sessionGuards: LiveConnectedTurnGuard[SessionCtx],
      sessionInitializers: LiveConnectedResourceInitializers[SessionCtx],
      sessionLayouts: Vector[LiveLayout[Any, SessionCtx]],
      sessionRootLayout: Option[LiveRootLayout[Any, SessionCtx]],
      supplies: SessionCtx <:< In
    ): LiveRouteDefinition[A] { type Input = Any } =
      Route[R1 & RC, RF, A, P, Any, (SessionCtx, Ctx)](
        pathCodec,
        paramsCodec,
        LiveRouteContext.SessionMounted(pipeline, context, supplies),
        contexts => factory(contexts._2),
        sessionGuards
          .contramap((_: (SessionCtx, Ctx))._1)
          .andThen(connectedTurnGuards.contramap((_: (SessionCtx, Ctx))._2)),
        sessionInitializers
          .contramap((_: (SessionCtx, Ctx))._1)
          .andThen(connectedResourceInitializers.contramap((_: (SessionCtx, Ctx))._2)),
        sessionLayouts.map(_.forContext((_: (SessionCtx, Ctx))._1)) ++
          layouts.map(_.forContext((_: (SessionCtx, Ctx))._2)),
        rootLayout
          .map(_.forContext((_: (SessionCtx, Ctx))._2))
          .orElse(sessionRootLayout.map(_.forContext((_: (SessionCtx, Ctx))._1)))
      )
  end Route

  /** A route that answers ordinary HTTP `GET` requests with `handler` instead of a LiveView. */
  final case class Http[RC, RH, A, P, In, Ctx](
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsCodec[A, P],
    context: LiveRouteContext[RC, A, In, Ctx],
    handler: (P, Ctx, Request) => RIO[RH, Response])
      extends LiveRouteDefinition[A]:
    type Input = In

    // Guards, connected resources and layouts concern LiveViews; an HTTP route keeps only the
    // session's admission.
    def withSession[R1, SessionCtx](
      pipeline: LiveSessionMountPipeline[R1, Any, SessionCtx],
      sessionGuards: LiveConnectedTurnGuard[SessionCtx],
      sessionInitializers: LiveConnectedResourceInitializers[SessionCtx],
      sessionLayouts: Vector[LiveLayout[Any, SessionCtx]],
      sessionRootLayout: Option[LiveRootLayout[Any, SessionCtx]],
      supplies: SessionCtx <:< In
    ): LiveRouteDefinition[A] { type Input = Any } =
      Http[R1 & RC, RH, A, P, Any, (SessionCtx, Ctx)](
        pathCodec,
        paramsCodec,
        LiveRouteContext.SessionMounted(pipeline, context, supplies),
        (params, contexts, request) => handler(params, contexts._2, request)
      )
end LiveRouteDefinition

/** Evidence that a route whose parameters are `Params` can mount `View`.
  *
  * Any [[LiveView]] fits any route. A [[LiveView.Routed LiveView.Routed]] fits a route whose
  * parameters are the ones it takes.
  */
@implicitNotFound(
  "${View} cannot be mounted on a route whose parameters are ${Params}: a LiveView.Routed must take the route's parameters, and a LiveView fits any route"
)
sealed trait LiveRouteView[View, Params]:
  private[scalive] def erase(view: View): LiveRouteView.Mounted[Params]

/** The two ways a LiveView fits a route. */
object LiveRouteView:
  private[scalive] type Mounted[Params] = LiveView[?, ?] | LiveView.Routed[?, ?, Params]

  /** Any [[LiveView]] fits any route. */
  given ordinary[View <: LiveView[?, ?], Params]: LiveRouteView[View, Params] with
    private[scalive] def erase(view: View): Mounted[Params] = view

  /** A [[LiveView.Routed LiveView.Routed]] fits a route with the parameters it takes. */
  given routed[View <: LiveView.Routed[?, ?, Params], Params]: LiveRouteView[View, Params] with
    private[scalive] def erase(view: View): Mounted[Params] = view

/** A route registered with the LiveView it mounts, as returned by `->`, `from`, or `context`.
  *
  * Pass it to `Live.router`, or to a named live session when it reads the session's context.
  *
  * @tparam R
  *   the services its factory and mount aspects need from the server's environment
  */
sealed abstract class LiveRegistration[-R] private[scalive]:
  /** The context this route reads from an enclosing named live session, `Any` when it reads none.
    *
    * The router accepts only registrations whose input is `Any`. A route that reads session
    * context, through `context` or a route mount aspect, must be passed to a session that produces
    * it.
    */
  type Input

  private[scalive] val definition: LiveRouteDefinition[?] {
    type Input = LiveRegistration.this.Input
  }

  private[scalive] def attachSession[RS, SessionCtx](
    pipeline: LiveSessionMountPipeline[RS, Any, SessionCtx],
    guards: LiveConnectedTurnGuard[SessionCtx],
    initializers: LiveConnectedResourceInitializers[SessionCtx],
    layouts: Vector[LiveLayout[Any, SessionCtx]],
    rootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Input
  ): LiveRegistration[R & RS] { type Input = Any } =
    LiveRegistration(
      definition.withSession(pipeline, guards, initializers, layouts, rootLayout, supplies)
    )

private[scalive] object LiveRegistration:
  def apply[R, Need](
    value: LiveRouteDefinition[?] { type Input = Need }
  ): LiveRegistration[R] { type Input = Need } =
    new LiveRegistration[R]:
      type Input = Need
      val definition = value

/** A route being registered: layouts, connected-turn guards, connected resources, and mount
  * aspects, in any order, then the LiveView it mounts.
  *
  * A [[LiveRoute]] is itself a registration with no setup, so `Routes.search -> SearchLiveView()`
  * registers it directly. Each setup call returns a new registration and leaves the route value
  * unchanged. See the [routes guide](https://scalive.dev/guides/routes-and-navigation).
  *
  * @tparam A
  *   the values decoded from the path, which layouts receive
  * @tparam P
  *   the parameters a [[LiveView.Routed LiveView.Routed]] receives
  */
sealed class LiveRouteSetup[A, P] private[scalive] (
  private[scalive] val pathCodec: PathCodec[A],
  private[scalive] val paramsCodec: LiveParamsCodec[A, P],
  private val layouts: Vector[LiveLayout[A, Any]],
  private val rootLayout: Option[LiveRootLayout[A, Any]],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Any],
  private val connectedResourceInitializers: LiveConnectedResourceInitializers[Any]):

  private def setup(
    layouts: Vector[LiveLayout[A, Any]] = layouts,
    rootLayout: Option[LiveRootLayout[A, Any]] = rootLayout,
    connectedTurnGuards: LiveConnectedTurnGuard[Any] = connectedTurnGuards,
    connectedResourceInitializers: LiveConnectedResourceInitializers[Any] =
      connectedResourceInitializers
  ): LiveRouteSetup[A, P] =
    new LiveRouteSetup(
      pathCodec,
      paramsCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Wraps this route's LiveView in `layout`.
    *
    * Route layouts sit inside router and session layouts and nest in installation order. See the
    * [layouts guide](https://scalive.dev/guides/layouts#give-one-route-its-own-layout).
    */
  def withLayout(layout: LiveLayout[A, Any]): LiveRouteSetup[A, P] =
    setup(layouts = layouts :+ layout)

  /** Renders this route's document with `layout`, replacing the session's or router's root layout.
    */
  def withRootLayout(layout: LiveRootLayout[A, Any]): LiveRouteSetup[A, P] =
    setup(rootLayout = Some(layout))

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards.
    * Succeed with `Unit` to continue, or fail with [[LiveConnectedTurnFailure]] for a controlled
    * halt, redirect, reload, or disconnect. Guards are inherited by nested LiveViews and do not run
    * during mount, disconnected rendering, or framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Any => IO[LiveConnectedTurnFailure, Result]
  ): LiveRouteSetup[A, P] =
    setup(connectedTurnGuards = connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)))

  /** Appends setup for this route's connected resources, before connected mount.
    *
    * Callbacks run in declaration order, after session setup. They do not run during disconnected
    * rendering and are not inherited by nested LiveViews.
    */
  def withConnectedResources[Result <: Unit](
    initialize: (Any, ConnectedResources) => Task[Result]
  ): LiveRouteSetup[A, P] =
    setup(connectedResourceInitializers =
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Runs `aspect` before this route mounts and passes its output to the LiveView's factory.
    *
    * The aspect receives the values decoded from the route's path, not its query or mapped
    * parameters, with the URL and `In`, the context of the enclosing named live session. See [route
    * mount
    * aspects](https://scalive.dev/guides/live-sessions-and-mount-aspects#derive-route-context).
    */
  def withMountAspect[R, In, Out, Result](
    aspect: LiveRouteMountAspect[R, A, In, Out]
  )(using append: ContextAppend.Aux[In, Out, Result]
  ): LiveRouteSetup.WithContext[R, A, P, In, Result] =
    val before = (_: Result) => ()
    LiveRouteSetup.WithContext(
      pathCodec,
      paramsCodec,
      LiveRouteMountPipeline.Identity[A, In]().andThen(aspect),
      layouts.map(_.forContext(before)),
      rootLayout.map(_.forContext(before)),
      connectedTurnGuards.contramap(before),
      connectedResourceInitializers.contramap(before)
    )

  /** Registers the LiveView this route mounts, as in `Routes.counter -> CounterLiveView()`.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Use
    * [[from]] when the LiveView needs services, and [[context]] when it reads session context.
    */
  infix def ->[View](
    view: => View
  )(using
    mount: LiveRouteView[View, P]
  ): LiveRegistration[Any] { type Input = Any } =
    register(LiveRouteContext.Direct[A](), _ => ZIO.succeed(mount.erase(view)))

  /** Registers a LiveView built from services in the application environment.
    *
    * The route adds `R` to the application's environment requirement, so the server must provide
    * it, as in `from(ZIO.serviceWith[Reports](ReportsLiveView(_)))`. Scalive runs `factory` for the
    * disconnected render and for each connected mount. See the [services
    * guide](https://scalive.dev/guides/services-and-zlayer-injection).
    */
  def from[R, View](
    factory: URIO[R, View]
  )(using
    mount: LiveRouteView[View, P]
  ): LiveRegistration[R] { type Input = Any } =
    register(LiveRouteContext.Direct[A](), _ => factory.map(mount.erase))

  /** Registers a LiveView built from the context of the named live session around this route.
    *
    * The route compiles only inside a session that produces `Ctx`, as in
    * `.context((user: CurrentUser) => AccountLiveView(user))`. Inside the session, a tuple context
    * arrives as separate parameters, as in `.context((user, locale) => ...)`. See [live
    * sessions](https://scalive.dev/guides/live-sessions-and-mount-aspects).
    */
  def context[Ctx, View](
    factory: Ctx => View
  )(using
    mount: LiveRouteView[View, P]
  ): LiveRegistration[Any] { type Input = Ctx } =
    register(
      LiveRouteContext.Required[A, Ctx](),
      context => ZIO.succeed(mount.erase(factory(context)))
    )

  /** Registers a LiveView built from services in the application environment.
    *
    * This form also reads the context of the enclosing named live session, as in
    * `from((user: CurrentUser) => ZIO.serviceWith[Reports](ReportsLiveView(user, _)))`.
    */
  def from[Ctx, R, View](
    factory: Ctx => URIO[R, View]
  )(using
    mount: LiveRouteView[View, P]
  ): LiveRegistration[R] { type Input = Ctx } =
    register(LiveRouteContext.Required[A, Ctx](), context => factory(context).map(mount.erase))

  /** Registers an ordinary HTTP `GET` handler for this route instead of a LiveView.
    *
    * Use it for responses that are not pages, such as a file download or an image, while keeping
    * the route's typed parameters and [[LiveRoute.location]]. The handler receives the decoded
    * parameters and the request, and its `Response` is sent as is. Parameters that do not decode
    * answer `400`, [[LiveNotFound]] answers `404`, and any other failure answers `500`, with the
    * router's [[LiveErrorPage]] when it has one. Inside a named live session, the session's mount
    * aspects admit the request first, as for a page request. See the [routes
    * guide](https://scalive.dev/guides/routes-and-navigation#serve-http-responses-from-routes).
    */
  def http[R](handler: (P, Request) => RIO[R, Response]): LiveRegistration[R] {
    type Input = Any
  } =
    registerHttp(LiveRouteContext.Direct[A](), (params, _, request) => handler(params, request))

  /** Registers an ordinary HTTP `GET` handler for this route instead of a LiveView.
    *
    * This form also reads the context of the enclosing named live session, as in
    * `http((params, user: CurrentUser, request) => ...)`; it compiles only inside a session that
    * produces `Ctx`.
    */
  def http[Ctx, R](handler: (P, Ctx, Request) => RIO[R, Response]): LiveRegistration[R] {
    type Input = Ctx
  } =
    registerHttp(LiveRouteContext.Required[A, Ctx](), handler)

  private def registerHttp[R, In, Ctx](
    context: LiveRouteContext[Any, A, In, Ctx],
    handler: (P, Ctx, Request) => RIO[R, Response]
  ): LiveRegistration[R] { type Input = In } =
    LiveRegistration(
      LiveRouteDefinition.Http[Any, R, A, P, In, Ctx](pathCodec, paramsCodec, context, handler)
    )

  private def register[R, In, Ctx](
    context: LiveRouteContext[Any, A, In, Ctx],
    factory: Ctx => URIO[R, LiveRouteView.Mounted[P]]
  ): LiveRegistration[R] { type Input = In } =
    LiveRegistration(
      LiveRouteDefinition.Route[Any, R, A, P, In, Ctx](
        pathCodec,
        paramsCodec,
        context,
        factory,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout
      )
    )
end LiveRouteSetup

/** Registration forms once a route mount aspect has produced context. */
object LiveRouteSetup:
  /** A route being registered after its mount aspects, whose setup and LiveView read their context.
    *
    * Accepts the same setup as [[LiveRouteSetup]], in any order. Layouts, guards, and connected
    * resources receive the context produced by the aspects before them.
    *
    * @tparam R
    *   the services the mount aspects need
    * @tparam A
    *   the values decoded from the path
    * @tparam P
    *   the parameters a [[LiveView.Routed LiveView.Routed]] receives
    * @tparam In
    *   the context the route reads from an enclosing named live session
    * @tparam Ctx
    *   the context produced so far, which the LiveView's factory receives
    */
  final class WithContext[R, A, P, In, Ctx] private[scalive] (
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsCodec[A, P],
    pipeline: LiveRouteMountPipeline[R, A, In, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx]):

    private def setup(
      layouts: Vector[LiveLayout[A, Ctx]] = layouts,
      rootLayout: Option[LiveRootLayout[A, Ctx]] = rootLayout,
      connectedTurnGuards: LiveConnectedTurnGuard[Ctx] = connectedTurnGuards,
      connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx] =
        connectedResourceInitializers
    ): WithContext[R, A, P, In, Ctx] =
      WithContext(
        pathCodec,
        paramsCodec,
        pipeline,
        layouts,
        rootLayout,
        connectedTurnGuards,
        connectedResourceInitializers
      )

    /** Runs another mount aspect after the previous ones and adds its output to the context. */
    def withMountAspect[R1, Out, Result](
      aspect: LiveRouteMountAspect[R1, A, Ctx, Out]
    )(using append: ContextAppend.Aux[Ctx, Out, Result]
    ): WithContext[R & R1, A, P, In, Result] =
      WithContext(
        pathCodec,
        paramsCodec,
        pipeline.andThen(aspect),
        layouts.map(_.forContext(append.left)),
        rootLayout.map(_.forContext(append.left)),
        connectedTurnGuards.contramap(append.left),
        connectedResourceInitializers.contramap(append.left)
      )

    /** Wraps this route's LiveView in a layout that can read the aspects' context. */
    def withLayout(layout: LiveLayout[A, Ctx]): WithContext[R, A, P, In, Ctx] =
      setup(layouts = layouts :+ layout)

    /** Wraps this route's LiveView in a layout that can read the aspects' context.
      *
      * This form selects the layout's input from the context available here. The pure selector
      * adapts only the layout; later aspects preserve this selection.
      */
    def withLayout[LayoutContext](
      layout: LiveLayout[A, LayoutContext],
      select: Ctx => LayoutContext
    ): WithContext[R, A, P, In, Ctx] = withLayout(layout.forContext(select))

    /** Renders this route's document with a root layout that can read the aspects' context. */
    def withRootLayout(layout: LiveRootLayout[A, Ctx]): WithContext[R, A, P, In, Ctx] =
      setup(rootLayout = Some(layout))

    /** Renders this route's document with a root layout that can read the aspects' context.
      *
      * This form selects the layout's input from the context available here. Key and view select
      * independently, so the selector must be pure and deterministic.
      */
    def withRootLayout[LayoutContext](
      layout: LiveRootLayout[A, LayoutContext],
      select: Ctx => LayoutContext
    ): WithContext[R, A, P, In, Ctx] = withRootLayout(layout.forContext(select))

    /** Appends a policy check before each connected application turn, with the aspects' context.
      *
      * Guards at one boundary run in declaration order; session guards run before route guards.
      * Succeed with `Unit` to continue, or fail with [[LiveConnectedTurnFailure]] for a controlled
      * halt, redirect, reload, or disconnect.
      */
    def guardConnectedTurns[Result <: Unit](
      guard: Ctx => IO[LiveConnectedTurnFailure, Result]
    ): WithContext[R, A, P, In, Ctx] =
      setup(connectedTurnGuards = connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)))

    /** Appends connected setup that receives the aspects' context. */
    def withConnectedResources[Result <: Unit](
      initialize: (Ctx, ConnectedResources) => Task[Result]
    ): WithContext[R, A, P, In, Ctx] =
      setup(connectedResourceInitializers =
        connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
      )

    /** Registers the LiveView this route mounts after its mount aspects succeed. */
    infix def ->[View](
      view: => View
    )(using
      mount: LiveRouteView[View, P]
    ): LiveRegistration[R] { type Input = In } =
      register(_ => ZIO.succeed(mount.erase(view)))

    /** Registers a LiveView built from services in the application environment.
      *
      * The services are provided after the mount aspects succeed.
      */
    def from[R1, View](
      factory: URIO[R1, View]
    )(using
      mount: LiveRouteView[View, P]
    ): LiveRegistration[R & R1] { type Input = In } =
      register(_ => factory.map(mount.erase))

    /** Registers a LiveView built from the aspects' context.
      *
      * A tuple context arrives as separate parameters, as in
      * `.context((user, access) => WorkspaceLiveView(user, access))`.
      */
    def context[View](
      factory: Ctx => View
    )(using
      mount: LiveRouteView[View, P]
    ): LiveRegistration[R] { type Input = In } =
      register(context => ZIO.succeed(mount.erase(factory(context))))

    /** Registers a LiveView built from services in the application environment.
      *
      * This form also reads the aspects' context.
      */
    def from[R1, View](
      factory: Ctx => URIO[R1, View]
    )(using
      mount: LiveRouteView[View, P]
    ): LiveRegistration[R & R1] { type Input = In } =
      register(context => factory(context).map(mount.erase))

    /** Registers an ordinary HTTP `GET` handler that reads the aspects' context.
      *
      * The mount aspects admit each request first, as for a page request; see
      * [[LiveRouteSetup.http]].
      */
    def http[R1](handler: (P, Ctx, Request) => RIO[R1, Response]): LiveRegistration[R & R1] {
      type Input = In
    } =
      LiveRegistration(
        LiveRouteDefinition.Http[R, R1, A, P, In, Ctx](
          pathCodec,
          paramsCodec,
          LiveRouteContext.Mounted(pipeline),
          handler
        )
      )

    private def register[R1](
      factory: Ctx => URIO[R1, LiveRouteView.Mounted[P]]
    ): LiveRegistration[R & R1] { type Input = In } =
      LiveRegistration(
        LiveRouteDefinition.Route[R, R1, A, P, In, Ctx](
          pathCodec,
          paramsCodec,
          LiveRouteContext.Mounted(pipeline),
          factory,
          connectedTurnGuards,
          connectedResourceInitializers,
          layouts,
          rootLayout
        )
      )
  end WithContext
end LiveRouteSetup

/** A route value: a path and its parameter codec, which builds links and registers a LiveView.
  *
  * Declare routes once, usually in a `Routes` object, then build links with [[location]] and
  * register them in `Live.router`. Every route can encode its parameters, so every route builds
  * links.
  *
  * @tparam A
  *   the values decoded from the path
  * @tparam P
  *   the parameters a [[LiveView.Routed LiveView.Routed]] on this route receives
  */
sealed class LiveRoute[A, P] private[scalive] (
  path: PathCodec[A],
  codec: LiveParamsCodec[A, P])
    extends LiveRouteSetup[A, P](
      path,
      codec,
      Vector.empty,
      None,
      LiveConnectedTurnGuard.empty,
      LiveConnectedResourceInitializers.empty
    ):

  /** Builds a link or navigation destination for this route.
    *
    * Pass the parameters a routed LiveView receives, as in
    * `Routes.search.location(Some("LiveView"))`. Throws [[LiveLocation.EncodingException]] when a
    * value cannot be encoded; use [[locationEither]] to handle that case.
    */
  def location(params: P): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  /** Builds a link or navigation destination for this route, or returns why encoding failed. */
  def locationEither(params: P): Either[LiveLocation.EncodeError, LiveLocation] =
    paramsCodec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))

  /** Builds a link or navigation destination for this route.
    *
    * This form applies to a route without parameters, as in `Routes.home.location`.
    */
  @targetName("unitLocation")
  def location(using ev: P =:= Unit): LiveLocation = location(ev.flip(()))

  /** Builds a link or navigation destination for this route, or returns why encoding failed. */
  @targetName("unitLocationEither")
  def locationEither(using ev: P =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(ev.flip(()))

  /** Converts the parameters into a domain type, keeping the route able to build links.
    *
    * `decodeParams` runs on each decoded URL and `encodeParams` on each [[location]] call, so they
    * must be inverses.
    */
  def mapParams[P2](decodeParams: P => P2)(encodeParams: P2 => P): LiveRoute[A, P2] =
    LiveRoute(pathCodec, paramsCodec.imap(decodeParams)(encodeParams))
end LiveRoute

private[scalive] object LiveRoute:
  def apply[A, P](path: PathCodec[A], codec: LiveParamsCodec[A, P]): LiveRoute[A, P] =
    new LiveRoute(path, codec)

/** A route whose parameters are its path values, extended with `/`.
  *
  * Start from `live`, add segments with `/`, and add query parameters with [[query]] or
  * [[queryOptional]]. A [[LiveView.Routed LiveView.Routed]] on a path receives the path values as
  * its parameters, as in `(live / "items" / PathCodec.int("id")) -> ItemLiveView()`.
  *
  * @tparam A
  *   the values decoded from the path, `Unit` when it has none
  */
final class LivePath[A] private[scalive] (path: PathCodec[A])
    extends LiveRoute[A, A](path, LiveParamsCodec.path[A]):

  /** Appends path segments, as in `live / "projects" / PathCodec.string("project")`.
    *
    * A literal string matches itself; a codec such as `PathCodec.int("id")` decodes one segment and
    * adds its value to the path values.
    */
  def /[B, Out](that: PathCodec[B])(using combiner: Combiner.WithOut[A, B, Out]): LivePath[Out] =
    LivePath(pathCodec / that)

  /** Adds query parameters to the parameters a routed LiveView receives.
    *
    * The LiveView receives the path values and the decoded query together. This form takes a ZIO
    * HTTP query codec.
    */
  def query[Query](
    codec: QueryCodec[Query]
  )[Out](
    using combiner: Combiner.WithOut[A, Query, Out]
  ): LiveRoute[A, Out] =
    LiveRoute(pathCodec, LiveParamsCodec.fromQuery(codec))

  /** Adds query parameters to the parameters a routed LiveView receives.
    *
    * This form adds one required parameter, as in `query[Int]("page")`. A missing or malformed
    * value fails to decode.
    */
  def query[Query](
    name: String
  )(using schema: Schema[Query]
  )[Out](
    using combiner: Combiner.WithOut[A, Query, Out]
  ): LiveRoute[A, Out] =
    LiveRoute(pathCodec, LiveParamsCodec.fromSchemaQuery(SchemaQuery.param(name, schema)))

  /** Adds an optional query parameter, as in `queryOptional[String]("q")`.
    *
    * It decodes to `None` when the value is absent or malformed, and `None` encodes as no
    * parameter.
    */
  def queryOptional[Query](
    name: String
  )(using schema: Schema[Query]
  )[Out](
    using combiner: Combiner.WithOut[A, Option[Query], Out]
  ): LiveRoute[A, Out] =
    LiveRoute(
      pathCodec,
      LiveParamsCodec.fromSchemaQuery(SchemaQuery.param(name, Schema.Optional(schema)))
    )

  /** Adds query parameters to the parameters a routed LiveView receives.
    *
    * This form derives the parameters from the fields of `Query`'s schema, as
    * [[LiveParamsCodec.query]] describes: a field with a default value, an `Option` or a collection
    * takes that fallback when its value is missing or malformed.
    */
  def query[Query](
    using schema: Schema[Query]
  )[Out](
    using combiner: Combiner.WithOut[A, Query, Out]
  ): LiveRoute[A, Out] =
    LiveRoute(pathCodec, LiveParamsCodec.fromSchemaQuery(SchemaQuery.fields(schema)))

  /** Decodes the route's values into another parameter type that can still build links. */
  def params[Params](codec: LiveParamsCodec[A, Params]): LiveRoute[A, Params] =
    LiveRoute(pathCodec, codec)
end LivePath

private[scalive] object LivePath:
  def apply[A](path: PathCodec[A]): LivePath[A] = new LivePath(path)

/** A named, environment-typed group of registered routes.
  *
  * @param name
  *   The name passed to `Live.session`, unique within an application.
  */
final class LiveSession[-R] private[scalive] (
  val name: String,
  private[scalive] val routes: Vector[LiveRegistration[R] { type Input = Any }])

private[scalive] object LiveSession:
  def apply[R](
    name: String,
    routes: Vector[LiveRegistration[R] { type Input = Any }]
  ): LiveSession[R] = new LiveSession(name, routes)

/** Declares a named live session: a group of routes that share navigation, layouts, and mount
  * policy.
  *
  * Create one with `Live.session(name)`, add mount aspects, layouts, and guards, then pass the
  * routes, as in `Live.session("account").withMountAspect(currentUser)(...)`. Live navigation stays
  * inside a session; navigating to a route outside it loads a new document. See [live
  * sessions](https://scalive.dev/guides/live-sessions-and-mount-aspects).
  *
  * @tparam R
  *   the services the session's mount aspects need
  * @tparam Ctx
  *   the context the session's mount aspects produce for its routes
  * @tparam Admitted
  *   whether the session has declared its admission, which it can do once
  * @param name
  *   The name passed to `Live.session`.
  */
final class LiveSessionBuilder[R, Ctx, Admitted <: Boolean] private[scalive] (
  val name: String,
  private val pipeline: LiveSessionMountPipeline[R, Any, Ctx],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
  private val connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
  private val layouts: Vector[LiveLayout[Any, Ctx]],
  private val rootLayout: Option[LiveRootLayout[Any, Ctx]]):

  private def setup(
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx] = connectedTurnGuards,
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx] =
      connectedResourceInitializers,
    layouts: Vector[LiveLayout[Any, Ctx]] = layouts,
    rootLayout: Option[LiveRootLayout[Any, Ctx]] = rootLayout
  ): LiveSessionBuilder[R, Ctx, Admitted] =
    new LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards,
      connectedResourceInitializers,
      layouts,
      rootLayout
    )

  /** Appends session setup before route setup and connected mount, in declaration order. */
  def withConnectedResources[Result <: Unit](
    initialize: (Ctx, ConnectedResources) => Task[Result]
  ): LiveSessionBuilder[R, Ctx, Admitted] =
    setup(connectedResourceInitializers =
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Runs `aspect` before every route in this session mounts and adds its output to the context.
    *
    * The aspect reads the HTTP request during the disconnected render and signs its claims into the
    * page, so the connected mount can rebuild the context without the browser's cookies. See [mount
    * aspects](https://scalive.dev/guides/live-sessions-and-mount-aspects).
    */
  def withMountAspect[R1, Claims, Out, Result](
    aspect: LiveSessionMountAspect[R1, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveSessionBuilder[R & R1, Result, Admitted] =
    new LiveSessionBuilder(
      name,
      pipeline.andThen(aspect),
      connectedTurnGuards.contramap(append.left),
      connectedResourceInitializers.contramap(append.left),
      layouts.map(_.forContext(append.left)),
      rootLayout.map(_.forContext(append.left))
    )

  /** Adds the session's single active-connection admission boundary.
    *
    * The signed aspect claim supplies the application-owned connection identifier. Connected mount
    * registers the physical transport before invoking the aspect's authoritative callback.
    */
  def withAdmission[R1, Claims, Out, Result, Id](
    aspect: LiveSessionMountAspect[R1, Ctx, Claims, Out]
  )(
    connectionId: Claims => Id
  )(using
    append: ContextAppend.Aux[Ctx, Out, Result],
    connections: Tag[LiveConnections[Id]],
    once: LiveSessionBuilder.FirstAdmission[Admitted]
  ): LiveSessionBuilder[R & R1 & LiveConnections[Id], Result, true] =
    new LiveSessionBuilder(
      name,
      pipeline.admitThen(aspect, connectionId, connections),
      connectedTurnGuards.contramap(append.left),
      connectedResourceInitializers.contramap(append.left),
      layouts.map(_.forContext(append.left)),
      rootLayout.map(_.forContext(append.left))
    )

  /** Wraps every route in this session in a layout.
    *
    * Session layouts sit inside the router's layouts and around route layouts, and nest in
    * installation order.
    */
  def withLayout(value: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx, Admitted] =
    setup(layouts = layouts :+ value)

  /** Wraps every route in this session in a layout.
    *
    * This form selects the layout's input from the context available here. The pure selector adapts
    * only the layout; later aspects preserve this selection.
    */
  def withLayout[LayoutContext](
    layout: LiveLayout[Any, LayoutContext],
    select: Ctx => LayoutContext
  ): LiveSessionBuilder[R, Ctx, Admitted] = withLayout(layout.forContext(select))

  /** Renders the document of every route in this session, replacing the router's root layout. */
  def withRootLayout(value: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx, Admitted] =
    setup(rootLayout = Some(value))

  /** Renders the document of every route in this session, replacing the router's root layout.
    *
    * This form selects the layout's input from the context available here. Key and view select
    * independently, so the selector must be pure and deterministic.
    */
  def withRootLayout[LayoutContext](
    layout: LiveRootLayout[Any, LayoutContext],
    select: Ctx => LayoutContext
  ): LiveSessionBuilder[R, Ctx, Admitted] = withRootLayout(layout.forContext(select))

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available here. Succeed with `Unit` to continue, or fail with
    * [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect. Guards
    * are inherited by nested LiveViews and do not run during mount, disconnected rendering, or
    * framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Ctx => IO[LiveConnectedTurnFailure, Result]
  ): LiveSessionBuilder[R, Ctx, Admitted] =
    setup(connectedTurnGuards = connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)))

  /** Puts `routes` in this session and returns it for the router.
    *
    * A route that reads context compiles only when this session produces the context it reads.
    * Sessions do not nest, so `routes` cannot include another session.
    */
  def apply[R1](routes: (LiveRegistration[R1] { type Input >: Ctx })*): LiveSession[R & R1] =
    val attached = routes.toVector.map { route =>
      route.attachSession(
        pipeline,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout,
        summon[Ctx <:< route.Input]
      )
    }
    LiveSession(name, attached)
end LiveSessionBuilder

/** Evidence types of [[LiveSessionBuilder]]. */
object LiveSessionBuilder:
  /** Evidence that a session has not declared its admission yet. */
  @implicitNotFound("A live session has at most one admission.")
  sealed trait FirstAdmission[Admitted <: Boolean]

  /** The evidence for a session without an admission. */
  object FirstAdmission:
    /** Proves that the session has no admission yet. */
    given notAdmitted: FirstAdmission[false] = new FirstAdmission[false] {}

/** All routes and layouts of a Scalive application, built by `Live.router(...)`.
  *
  * Serve it with `ZioHttp.routes(application, config)`, whose effect fails when the routes are
  * invalid.
  *
  * @tparam R
  *   the services the routes need from the server's environment
  * @param routes
  *   The registered routes and named live sessions passed to the router, in declaration order.
  * @param socketPath
  *   The path of the LiveView WebSocket, `/live` unless set with [[LiveRouter.withSocketPath]].
  * @param clientPath
  *   The path below which Scalive serves its own browser files, `/_scalive` unless set with
  *   [[LiveRouter.withClientPath]].
  * @param layouts
  *   The router's layouts, which wrap every route outside session and route layouts, in
  *   installation order.
  * @param rootLayout
  *   The root layout used by routes whose session or route installs none; without
  *   [[LiveRouter.withRootLayout]], it is [[LiveRootLayout.default]].
  * @param errorPage
  *   The page rendered for Scalive's own error responses, set with [[LiveRouter.withErrorPage]].
  */
final class LiveApplication[-R] private[scalive] (
  val routes: Vector[LiveRegistration[R] | LiveSession[R]],
  val socketPath: PathCodec[Unit],
  val clientPath: PathCodec[Unit],
  val layouts: Vector[LiveLayout[Any, Any]],
  val rootLayout: LiveRootLayout[Any, Any],
  val errorPage: Option[LiveErrorPage])

private[scalive] object LiveApplication:
  def apply[R](
    routes: Vector[LiveRegistration[R] | LiveSession[R]],
    socketPath: PathCodec[Unit],
    clientPath: PathCodec[Unit],
    layouts: Vector[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any],
    errorPage: Option[LiveErrorPage]
  ): LiveApplication[R] =
    new LiveApplication(routes, socketPath, clientPath, layouts, rootLayout, errorPage)

/** Collects an application's routes into a [[LiveApplication]]. Start from `Live.router`. */
final class LiveRouter private[scalive] (
  private val socketPath: PathCodec[Unit],
  private val clientPath: PathCodec[Unit],
  private val layouts: Vector[LiveLayout[Any, Any]],
  private val rootLayout: LiveRootLayout[Any, Any],
  private val errorPage: Option[LiveErrorPage]):

  /** Serves the LiveView WebSocket at `value` instead of `/live`.
    *
    * The browser's `LiveSocket` must connect to the same path.
    */
  def withSocketPath(value: PathCodec[Unit]): LiveRouter =
    LiveRouter(value, clientPath, layouts, rootLayout, errorPage)

  /** Serves Scalive's own browser files below `value` instead of `/_scalive`.
    *
    * Scalive adds these scripts to every page, with either client, so an application served below a
    * prefix moves them there too, as in `withClientPath(PathCodec.empty / "app" / "_scalive")`.
    */
  def withClientPath(value: PathCodec[Unit]): LiveRouter =
    LiveRouter(socketPath, value, layouts, rootLayout, errorPage)

  /** Wraps every route in `value`.
    *
    * Router layouts sit outside session and route layouts, and nest in installation order.
    */
  def withLayout(value: LiveLayout[Any, Any]): LiveRouter =
    LiveRouter(socketPath, clientPath, layouts :+ value, rootLayout, errorPage)

  /** Renders every route's document with `value`, unless a session or route installs its own.
    *
    * Without one, the response contains only the LiveView, with no document or client scripts
    * around it.
    */
  def withRootLayout(value: LiveRootLayout[Any, Any]): LiveRouter =
    LiveRouter(socketPath, clientPath, layouts, value, errorPage)

  /** Renders `value` for the error responses Scalive answers itself, inside the root layout.
    *
    * With an error page, a `GET` request that no route of the application matches also answers
    * `404` with it, so add it to the routes that serve everything else. More specific routes still
    * win, whatever their order. See [[LiveErrorPage]].
    */
  def withErrorPage(value: LiveErrorPage): LiveRouter =
    LiveRouter(socketPath, clientPath, layouts, rootLayout, Some(value))

  /** Builds the application from registered routes and named live sessions.
    *
    * Routes that read session context must be inside the [[LiveSessionBuilder]] that produces it;
    * the compiler reports each one passed here directly.
    */
  inline def apply[R](
    inline routes: (LiveRegistration[R] | LiveSession[R])*
  ): LiveApplication[R] =
    LiveRouterChecks.requireRouterRoutes(routes)
    application(routes)

  private[scalive] def application[R](
    routes: Seq[LiveRegistration[R] | LiveSession[R]]
  ): LiveApplication[R] =
    LiveApplication(routes.toVector, socketPath, clientPath, layouts, rootLayout, errorPage)
end LiveRouter

private[scalive] object LiveRouter:
  def apply(
    socketPath: PathCodec[Unit],
    clientPath: PathCodec[Unit],
    layouts: Vector[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any],
    errorPage: Option[LiveErrorPage]
  ): LiveRouter = new LiveRouter(socketPath, clientPath, layouts, rootLayout, errorPage)

/** Entry points for declaring a Scalive application. */
object Live:
  /** Starts an application, as in
    * `Live.router.withRootLayout(RootLayout())(Routes.home -> HomeLiveView())`.
    *
    * Pass the result to `ZioHttp.routes` to serve it. See the [quick
    * start](https://scalive.dev/learn/quick-start).
    */
  val router: LiveRouter =
    LiveRouter(
      PathCodec.empty / "live",
      PathCodec.empty / "_scalive",
      Vector.empty,
      LiveRootLayout.default,
      None
    )

  /** Starts a route from a ZIO HTTP path codec. Most routes start from `live` instead. */
  def route[A](pathCodec: PathCodec[A]): LivePath[A] = LivePath(pathCodec)

  /** Starts a named live session, a group of routes that share navigation and mount policy.
    *
    * Names must be unique within the application.
    */
  def session(name: String): LiveSessionBuilder[Any, Any, false] =
    new LiveSessionBuilder(
      name,
      LiveSessionMountPipeline.Identity[Any](),
      LiveConnectedTurnGuard.empty,
      LiveConnectedResourceInitializers.empty,
      Vector.empty,
      None
    )
end Live

/** The root path `/`, from which routes are declared, as in `live / "counter"`.
  *
  * See the [routes guide](https://scalive.dev/guides/routes-and-navigation).
  */
val live: LivePath[Unit] = LivePath(PathCodec.empty)
