package scalive

import scala.annotation.targetName

import zio.Tag
import zio.http.Request
import zio.http.codec.{Combiner, HttpCodec, PathCodec, QueryCodec}
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

/** Selects how a completed route obtains the context passed to its lifecycle factory and layouts.
  */
sealed private[scalive] trait LiveRouteContext[R, A, In, Ctx]

object LiveRouteContext:
  final case class Direct[A]()                    extends LiveRouteContext[Any, A, Any, Any]
  final case class Environment[R, A](tag: Tag[R]) extends LiveRouteContext[R, A, Any, R]
  final case class Required[A, Ctx]()             extends LiveRouteContext[Any, A, Ctx, Ctx]
  final case class Mounted[R, A, In, Ctx](pipeline: LiveRouteMountPipeline[R, A, In, Ctx])
      extends LiveRouteContext[R, A, In, Ctx]

  /** Resolves an existing route context before adding one application-environment service. */
  final case class WithEnvironment[R0, R1, A, In, Ctx](
    previous: LiveRouteContext[R0, A, In, Ctx],
    service: Tag[R1])
      extends LiveRouteContext[R0 & R1, A, In, (Ctx, R1)]

  /** Retains the lifecycle order and the proof that session context supplies route input. */
  final case class SessionMounted[RS, RR, A, SessionCtx, RouteIn, RouteCtx](
    session: LiveSessionMountPipeline[RS, Any, SessionCtx],
    route: LiveRouteContext[RR, A, RouteIn, RouteCtx],
    routeInput: SessionCtx => RouteIn)
      extends LiveRouteContext[RS & RR, A, Any, (SessionCtx, RouteCtx)]

/** The sole existential boundary joining a complete typed route to the heterogeneous catalog. */
sealed private[scalive] trait LiveRouteDefinition[A]:
  type Environment
  type Input
  type Context
  type Msg
  type Model

  private[scalive] def withSession[R, SessionCtx](
    pipeline: LiveSessionMountPipeline[R, Any, SessionCtx],
    sessionGuards: LiveConnectedTurnGuard[SessionCtx],
    sessionInitializers: LiveConnectedResourceInitializers[SessionCtx],
    sessionLayouts: Vector[LiveLayout[Any, SessionCtx]],
    sessionRootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Input
  ): LiveRouteDefinition[A] {
    type Environment = LiveRouteDefinition.this.Environment & R; type Input = Any
  }

object LiveRouteDefinition:
  final case class Ordinary[R, A, In, Ctx, Message, State](
    pathCodec: PathCodec[A],
    context: LiveRouteContext[R, A, In, Ctx],
    factory: (A, Request, Ctx) => LiveView[Message, State],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Environment = R
    type Input       = In
    type Context     = Ctx
    type Msg         = Message
    type Model       = State

    def withSession[R1, SessionCtx](
      pipeline: LiveSessionMountPipeline[R1, Any, SessionCtx],
      sessionGuards: LiveConnectedTurnGuard[SessionCtx],
      sessionInitializers: LiveConnectedResourceInitializers[SessionCtx],
      sessionLayouts: Vector[LiveLayout[Any, SessionCtx]],
      sessionRootLayout: Option[LiveRootLayout[Any, SessionCtx]],
      supplies: SessionCtx <:< In
    ) =
      Ordinary(
        pathCodec,
        LiveRouteContext.SessionMounted(pipeline, context, supplies),
        (path, request, contexts) => factory(path, request, contexts._2),
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
          .orElse(
            sessionRootLayout.map(_.forContext((_: (SessionCtx, Ctx))._1))
          )
      )
  end Ordinary

  final case class Routed[R, A, In, Ctx, Message, State, Params](
    pathCodec: PathCodec[A],
    context: LiveRouteContext[R, A, In, Ctx],
    factory: (A, Request, Ctx) => LiveView.Routed[Message, State, Params],
    paramsCodec: LiveParamsDecoder[A, Params],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Environment = R
    type Input       = In
    type Context     = Ctx
    type Msg         = Message
    type Model       = State

    def withSession[R1, SessionCtx](
      pipeline: LiveSessionMountPipeline[R1, Any, SessionCtx],
      sessionGuards: LiveConnectedTurnGuard[SessionCtx],
      sessionInitializers: LiveConnectedResourceInitializers[SessionCtx],
      sessionLayouts: Vector[LiveLayout[Any, SessionCtx]],
      sessionRootLayout: Option[LiveRootLayout[Any, SessionCtx]],
      supplies: SessionCtx <:< In
    ) =
      Routed(
        pathCodec,
        LiveRouteContext.SessionMounted(pipeline, context, supplies),
        (path, request, contexts) => factory(path, request, contexts._2),
        paramsCodec,
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
          .orElse(
            sessionRootLayout.map(_.forContext((_: (SessionCtx, Ctx))._1))
          )
      )
  end Routed
end LiveRouteDefinition

/** A declarative route, hidden behind [[LiveRouteFragment]] during normal application assembly. */
sealed abstract class LiveRoute[R, A] private[scalive] extends LiveRouteFragment[R]:
  private[scalive] val definition: LiveRouteDefinition[A] {
    type Environment = R
    type Input       = LiveRoute.this.Input
  }
  private[scalive] def attachSession[RS, SessionCtx](
    pipeline: LiveSessionMountPipeline[RS, Any, SessionCtx],
    guards: LiveConnectedTurnGuard[SessionCtx],
    initializers: LiveConnectedResourceInitializers[SessionCtx],
    layouts: Vector[LiveLayout[Any, SessionCtx]],
    rootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Input
  ): Vector[LiveRouteFragment[R & RS] { type Input = Any }] =
    Vector(
      LiveRoute(
        definition.withSession(pipeline, guards, initializers, layouts, rootLayout, supplies)
      )
    )

object LiveRoute:
  private[scalive] def apply[R, A, Need](
    value: LiveRouteDefinition[A] { type Environment = R; type Input = Need }
  ): LiveRoute[R, A] { type Input = Need } =
    new LiveRoute[R, A]:
      type Input = Need
      val definition = value

/** A completed route or typed group of routes accepted by application assembly. */
sealed trait LiveRouteFragment[-R]:
  /** The context this fragment needs from an enclosing named live session, `Any` when it needs
    * none.
    *
    * The router accepts only fragments whose input is `Any`. A route that reads session context,
    * through `context` or a route mount aspect, must be passed to a session that produces it.
    */
  type Input

  private[scalive] def declarations: Vector[LiveRoute[?, ?]] = this match
    case route: LiveRoute[?, ?]  => Vector(route)
    case session: LiveSession[?] => session.routes.flatMap(_.declarations)

/** Declares a LiveView route: its path, how its LiveView is built, and the layouts around it.
  *
  * Start from `live`, add path segments with `/`, and finish with `->` for a LiveView that needs
  * nothing, [[from]] for one built from a service, or [[context]] for one built from the context of
  * a named live session. Call [[query]] or [[params]] first when the LiveView reads URL parameters;
  * it then extends [[LiveView.Routed LiveView.Routed]]. Keep the declaration in a named value to
  * build links to it with [[location]].
  *
  * See the [routes guide](https://scalive.dev/guides/routes-and-navigation).
  *
  * @tparam A
  *   the values decoded from the path, `Unit` when it has none
  */
class LiveRouteBuilder[A] private[scalive] (
  private[scalive] val pathCodec: PathCodec[A],
  private val layouts: Vector[LiveLayout[A, Any]] = Vector.empty,
  private val rootLayout: Option[LiveRootLayout[A, Any]] = None,
  private val connectedTurnGuards: LiveConnectedTurnGuard[Any] = LiveConnectedTurnGuard.empty,
  private val connectedResourceInitializers: LiveConnectedResourceInitializers[Any] =
    LiveConnectedResourceInitializers.empty):

  /** Appends path segments, as in `live / "projects" / PathCodec.string("project")`.
    *
    * A literal string matches itself; a codec such as `PathCodec.int("id")` decodes one segment and
    * adds its value to the route's path values. Layouts, guards, and connected resources installed
    * before `/` keep applying to the longer path.
    */
  def /[B](that: PathCodec[B])(using combiner: Combiner[A, B]): LiveRouteSeed[combiner.Out] =
    val previousParams: combiner.Out => A = out => combiner.separate(out)._1
    LiveRouteSeed(
      pathCodec / that,
      layouts.map(_.forPathParams(previousParams)),
      rootLayout.map(_.forPathParams(previousParams)),
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Appends setup for this route's connected resources, before connected mount. Callbacks run in
    * declaration order, after session setup, and receive the context available here. They do not
    * run during disconnected rendering and are not inherited by nested LiveViews.
    */
  def withConnectedResources[Result <: Unit](
    initialize: (Any, ConnectedResources) => zio.Task[Result]
  ): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Builds a link or navigation destination for this route.
    *
    * Pass the path values, as in `Routes.project.location("scalive")`. Throws
    * [[LiveLocation.EncodingException]] when a value cannot be encoded; use [[locationEither]] to
    * handle that case.
    */
  def location(value: A): LiveLocation =
    locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

  /** Builds a link or navigation destination for this route, or returns why encoding failed. */
  def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
    LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

  /** Builds a link or navigation destination for this route.
    *
    * This form applies to a path without values, as in `Routes.home.location`.
    */
  @targetName("unitLocation")
  def location(using ev: A =:= Unit): LiveLocation = location(ev.flip(()))

  /** Builds a link or navigation destination for this route, or returns why encoding failed. */
  @targetName("unitLocationEither")
  def locationEither(using ev: A =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(ev.flip(()))

  /** Adds query parameters, after which the route registers a [[LiveView.Routed LiveView.Routed]].
    *
    * The LiveView receives the path values and the decoded query together as its parameters. This
    * form takes a ZIO HTTP query codec; `query[Int]("page")` names one parameter, and
    * `query[Filters]` derives several from a schema.
    */
  def query[Query](
    codec: QueryCodec[Query]
  )(using
    combiner: Combiner[A, Query]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      LiveParamsCodec.fromQuery(codec),
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Adds query parameters, after which the route registers a [[LiveView.Routed LiveView.Routed]].
    *
    * The LiveView receives the path values and the decoded query together as its parameters. This
    * form adds one required parameter, as in `query[Int]("page")`.
    */
  def query[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Query]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query](name))

  /** Adds an optional query parameter, after which the route registers a
    * [[LiveView.Routed LiveView.Routed]].
    *
    * The parameter decodes to `None` when it is absent, as in `queryOptional[String]("q")`.
    */
  def queryOptional[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Option[Query]]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query](name).optional)

  /** Adds query parameters, after which the route registers a [[LiveView.Routed LiveView.Routed]].
    *
    * The LiveView receives the path values and the decoded query together as its parameters. This
    * form derives the parameters from the fields of `Query`'s schema.
    */
  def query[Query: Schema](
    using
    combiner: Combiner[A, Query]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query])

  /** Passes the route's values to a [[LiveView.Routed LiveView.Routed]] as its parameters.
    *
    * Without an argument, the parameters are the path values. Pass a [[LiveParamsCodec]] to decode
    * them into another type that can still build links.
    */
  def params: LiveEncodableRouteParamsBuilder[A, A] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      LiveParamsCodec.path[A],
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Passes the route's values to a [[LiveView.Routed LiveView.Routed]] as its parameters.
    *
    * Without an argument, the parameters are the path values. Pass a [[LiveParamsCodec]] to decode
    * them into another type that can still build links.
    */
  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Decodes the route's parameters with a decoder that cannot build links.
    *
    * Prefer [[params]] with a codec. The resulting route has no `location`.
    */
  def paramsDecodeOnly[Params](
    decoder: LiveParamsDecoder[A, Params]
  ): LiveRouteParamsBuilder[A, Params] =
    LiveRouteParamsBuilder(
      pathCodec,
      decoder,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Any => zio.IO[LiveConnectedTurnFailure, Result]
  ): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      connectedResourceInitializers
    )

  /** Wraps this route's LiveView in `layout`.
    *
    * Route layouts sit inside router and session layouts and nest in installation order. Install
    * them before [[params]] or [[query]]. See the [layouts
    * guide](https://scalive.dev/guides/layouts#give-one-route-its-own-layout).
    */
  def withLayout(layout: LiveLayout[A, Any]): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts :+ layout,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Renders this route's document with `layout`, replacing the session's or router's root layout.
    */
  def withRootLayout(layout: LiveRootLayout[A, Any]): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts,
      Some(layout),
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Starts a typed route mount pipeline. The resulting context is supplied to route factories. */
  def withMountAspect[R, In, Out, Result](
    aspect: LiveRouteMountAspect[R, A, In, Out]
  )(using append: ContextAppend.Aux[In, Out, Result]
  ): LiveRouteMountAspectBuilder[R, A, In, Result] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      LiveRouteMountPipeline.Identity[A, In]().andThen(aspect),
      layouts.map(_.forContext((_: Result) => ())),
      rootLayout.map(_.forContext((_: Result) => ())),
      connectedTurnGuards.contramap((_: Result) => ()),
      connectedResourceInitializers.contramap((_: Result) => ())
    )

  /** Registers the LiveView this route mounts.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Prefer
    * `->`, which reads better in a router.
    */
  def apply[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[Any, A] { type Input = Any } =
    ordinary((_, _) => view)

  /** Registers the LiveView this route mounts.
    *
    * `factory` receives the HTTP request of the disconnected render. For a connected mount it
    * receives a GET request for the page's path and query, without the browser's headers or
    * cookies; read those in a session mount aspect instead.
    */
  def apply[Msg, Model](
    factory: Request => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Any } =
    ordinary((_, request) => factory(request))

  /** Registers a LiveView built from the context of the named live session around this route.
    *
    * `factory` receives the context, as in
    * `.context((user: CurrentUser) => AccountLiveView(user))`. The route compiles only inside a
    * session whose mount aspects produce that context. See [live
    * sessions](https://scalive.dev/guides/live-sessions-and-mount-aspects).
    */
  def context[Ctx, Msg, Model](
    factory: Ctx => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    context((_, _, context) => factory(context))

  /** Registers a LiveView built from the context of the named live session around this route.
    *
    * `factory` also receives one service from the application environment, which the route adds to
    * the application's requirements.
    */
  def context[Ctx, R: Tag, Msg, Model](
    factory: (Ctx, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Ctx } =
    context((_, _, context, service) => factory(context, service))

  /** Registers a LiveView built from the context of the named live session around this route.
    *
    * `factory` also receives the path values and the request.
    */
  def context[Ctx, Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Required(),
        factory,
        connectedTurnGuards.contramap((_: Ctx) => ()),
        connectedResourceInitializers.contramap((_: Ctx) => ()),
        layouts.map(_.forContext((_: Ctx) => ())),
        rootLayout.map(_.forContext((_: Ctx) => ()))
      )
    )

  /** Registers a LiveView built from the context of the named live session around this route.
    *
    * `factory` also receives the path values, the request, and one service from the application
    * environment.
    */
  def context[Ctx, R: Tag, Msg, Model](
    factory: (A, Request, Ctx, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Ctx } =
    val requiredLayouts    = layouts.map(_.forContext((_: Ctx) => ()))
    val requiredRootLayout = rootLayout.map(_.forContext((_: Ctx) => ()))
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Required(), summon[Tag[R]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        connectedTurnGuards.contramap((_: (Ctx, R)) => ()),
        connectedResourceInitializers.contramap((_: (Ctx, R)) => ()),
        requiredLayouts.map(_.forContext((_: (Ctx, R))._1)),
        requiredRootLayout.map(_.forContext((_: (Ctx, R))._1))
      )
    )

  /** Registers the LiveView this route mounts, as in `live / "counter" -> CounterLiveView()`.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Use
    * [[from]] when the LiveView needs a service.
    */
  infix def ->[Msg, Model](
    view: => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Any } = apply(view)

  /** Registers a LiveView built from one service in the application environment.
    *
    * The route adds `R` to the application's environment requirement, so the server must provide
    * it. Scalive calls the factory for the disconnected render and for each connected mount. To use
    * several services, group them in one. See the [services
    * guide](https://scalive.dev/guides/services-and-zlayer-injection).
    */
  def from[R: Tag, Msg, Model](
    factory: R => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Any } =
    from((_: A, _: Request, service: R) => factory(service))

  /** Registers a LiveView built from one service in the application environment.
    *
    * `factory` also receives the path values and the request.
    */
  def from[R: Tag, Msg, Model](
    factory: (A, Request, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Any } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Environment(summon[Tag[R]]),
        factory,
        connectedTurnGuards.contramap((_: R) => ()),
        connectedResourceInitializers.contramap((_: R) => ()),
        layouts.map(_.forContext((_: R) => ())),
        rootLayout.map(_.forContext((_: R) => ()))
      )
    )

  private def ordinary[Msg, Model](
    factory: (A, Request) => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Any } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Direct(),
        (path, request, _) => factory(path, request),
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout
      )
    )
end LiveRouteBuilder

object LiveRouteBuilder:
  private[scalive] def apply[A](
    pathCodec: PathCodec[A],
    layouts: Vector[LiveLayout[A, Any]] = Vector.empty,
    rootLayout: Option[LiveRootLayout[A, Any]] = None,
    connectedTurnGuards: LiveConnectedTurnGuard[Any] = LiveConnectedTurnGuard.empty,
    connectedResourceInitializers: LiveConnectedResourceInitializers[Any] =
      LiveConnectedResourceInitializers.empty
  ): LiveRouteBuilder[A] =
    new LiveRouteBuilder(
      pathCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

/** Route construction after a mount aspect has produced typed lifecycle context. */
final class LiveRouteMountAspectBuilder[R, A, Need, Ctx] private[scalive] (
  private val pathCodec: PathCodec[A],
  private val pipeline: LiveRouteMountPipeline[R, A, Need, Ctx],
  private val layouts: Vector[LiveLayout[A, Ctx]],
  private val rootLayout: Option[LiveRootLayout[A, Ctx]],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
  private val connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx]):

  /** Appends connected setup using the context available at this declaration. */
  def withConnectedResources[Result <: Unit](
    initialize: (Ctx, ConnectedResources) => zio.Task[Result]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Runs another mount aspect after the previous ones and adds its output to the context. */
  def withMountAspect[R1, Out, Result](
    aspect: LiveRouteMountAspect[R1, A, Ctx, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveRouteMountAspectBuilder[R & R1, A, Need, Result] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline.andThen(aspect),
      layouts.map(_.forContext(append.left)),
      rootLayout.map(_.forContext(append.left)),
      connectedTurnGuards.contramap(append.left),
      connectedResourceInitializers.contramap(append.left)
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Ctx => zio.IO[LiveConnectedTurnFailure, Result]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      connectedResourceInitializers
    )

  /** Wraps this route's LiveView in a layout that can read the aspects' context. */
  def withLayout(layout: LiveLayout[A, Ctx]): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts :+ layout,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Wraps this route's LiveView in a layout that can read the aspects' context.
    *
    * This form selects the layout's input from the context available at this declaration. The pure
    * selector adapts only the layout; later aspects preserve this context selection.
    */
  def withLayout[LayoutContext](
    layout: LiveLayout[A, LayoutContext],
    select: Ctx => LayoutContext
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] = withLayout(layout.forContext(select))

  /** Renders this route's document with a root layout that can read the aspects' context. */
  def withRootLayout(
    layout: LiveRootLayout[A, Ctx]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      Some(layout),
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Renders this route's document with a root layout that can read the aspects' context.
    *
    * This form selects the layout's input from this declaration's context. Key and view select
    * independently, so the selector must be pure and deterministic.
    */
  def withRootLayout[LayoutContext](
    layout: LiveRootLayout[A, LayoutContext],
    select: Ctx => LayoutContext
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] = withRootLayout(layout.forContext(select))

  /** Passes the route's values to a [[LiveView.Routed LiveView.Routed]] as its parameters, like
    * [[LiveRouteBuilder.params]].
    */
  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    LiveRouteMountAspectParamsBuilder(
      pathCodec,
      codec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Passes the route's values to a [[LiveView.Routed LiveView.Routed]] as its parameters, like
    * [[LiveRouteBuilder.params]].
    */
  def params: LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, A] =
    params(LiveParamsCodec.path[A])

  /** Adds query parameters, after which the route registers a [[LiveView.Routed LiveView.Routed]],
    * like [[LiveRouteBuilder.query]].
    */
  def query[Query](
    codec: QueryCodec[Query]
  )(using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    params(LiveParamsCodec.fromQuery(codec))

  /** Adds query parameters, after which the route registers a [[LiveView.Routed LiveView.Routed]],
    * like [[LiveRouteBuilder.query]].
    */
  def query[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    query(HttpCodec.query[Query](name))

  /** Adds an optional query parameter, after which the route registers a
    * [[LiveView.Routed LiveView.Routed]].
    */
  def queryOptional[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Option[Query]]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    query(HttpCodec.query[Query](name).optional)

  /** Adds query parameters, after which the route registers a [[LiveView.Routed LiveView.Routed]],
    * like [[LiveRouteBuilder.query]].
    */
  def query[Query: Schema](
    using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    query(HttpCodec.query[Query])

  /** Registers the LiveView this route mounts after its mount aspects succeed.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Use
    * [[context]] when the LiveView reads the aspects' context.
    */
  def apply[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[R, A] { type Input = Need } =
    context((_, _, _) => view)

  /** Registers a LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` receives the context, as in `.context((access: ProjectAccess) => ...)`.
    */
  def context[Msg, Model](
    factory: Ctx => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Need } =
    context((_, _, context) => factory(context))

  /** Registers a LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` also receives one service from the application environment, which the route adds to
    * the application's requirements.
    */
  def context[R1: Tag, Msg, Model](
    factory: (Ctx, R1) => LiveView[Msg, Model]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    context[R1, Msg, Model]((_: A, _: Request, context: Ctx, service: R1) =>
      factory(context, service)
    )

  /** Registers a LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` also receives the path values and the request.
    */
  def context[Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Mounted(pipeline),
        factory,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout
      )
    )

  /** Registers a LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` also receives the path values, the request, and one service from the application
    * environment.
    */
  def context[R1: Tag, Msg, Model](
    factory: (A, Request, Ctx, R1) => LiveView[Msg, Model]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Mounted(pipeline), summon[Tag[R1]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        connectedTurnGuards.contramap((_: (Ctx, R1))._1),
        connectedResourceInitializers.contramap((_: (Ctx, R1))._1),
        layouts.map(_.forContext((_: (Ctx, R1))._1)),
        rootLayout.map(_.forContext((_: (Ctx, R1))._1))
      )
    )
end LiveRouteMountAspectBuilder

object LiveRouteMountAspectBuilder:
  private[scalive] def apply[R, A, Need, Ctx](
    pathCodec: PathCodec[A],
    pipeline: LiveRouteMountPipeline[R, A, Need, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    new LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

/** Parameterized route construction after mount aspects have produced context. */
final class LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] private[scalive] (
  private val pathCodec: PathCodec[A],
  private val paramsCodec: LiveParamsCodec[A, Params],
  private val pipeline: LiveRouteMountPipeline[R, A, Need, Ctx],
  private val layouts: Vector[LiveLayout[A, Ctx]],
  private val rootLayout: Option[LiveRootLayout[A, Ctx]],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
  private val connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx]):

  /** Appends connected setup using the context available at this declaration. */
  def withConnectedResources[Result <: Unit](
    initialize: (Ctx, ConnectedResources) => zio.Task[Result]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    LiveRouteMountAspectParamsBuilder(
      pathCodec,
      paramsCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Ctx => zio.IO[LiveConnectedTurnFailure, Result]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    LiveRouteMountAspectParamsBuilder(
      pathCodec,
      paramsCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      connectedResourceInitializers
    )

  /** Builds a link or navigation destination for this route from its parameters.
    *
    * Throws [[LiveLocation.EncodingException]] when a value cannot be encoded; use
    * [[locationEither]] to handle that case.
    */
  def location(params: Params): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  /** Builds a link or navigation destination for this route, or returns why encoding failed. */
  def locationEither(params: Params): Either[LiveLocation.EncodeError, LiveLocation] =
    paramsCodec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))

  /** Registers the routed LiveView this route mounts after its mount aspects succeed.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Use
    * [[context]] when the LiveView reads the aspects' context.
    */
  def apply[Msg, Model](
    view: => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    context((_, _, _) => view)

  /** Registers a routed LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` receives the context.
    */
  def context[Msg, Model](
    factory: Ctx => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    context((_, _, context) => factory(context))

  /** Registers a routed LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` also receives one service from the application environment, which the route adds to
    * the application's requirements.
    */
  def context[R1: Tag, Msg, Model](
    factory: (Ctx, R1) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    context[R1, Msg, Model]((_: A, _: Request, context: Ctx, service: R1) =>
      factory(context, service)
    )

  /** Registers a routed LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` also receives the path values and the request.
    */
  def context[Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Mounted(pipeline),
        factory,
        paramsCodec,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout
      )
    )

  /** Registers a routed LiveView built from the context produced by this route's mount aspects.
    *
    * `factory` also receives the path values, the request, and one service from the application
    * environment.
    */
  def context[R1: Tag, Msg, Model](
    factory: (A, Request, Ctx, R1) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Mounted(pipeline), summon[Tag[R1]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        paramsCodec,
        connectedTurnGuards.contramap((_: (Ctx, R1))._1),
        connectedResourceInitializers.contramap((_: (Ctx, R1))._1),
        layouts.map(_.forContext((_: (Ctx, R1))._1)),
        rootLayout.map(_.forContext((_: (Ctx, R1))._1))
      )
    )
end LiveRouteMountAspectParamsBuilder

object LiveRouteMountAspectParamsBuilder:
  private[scalive] def apply[R, A, Need, Ctx, Params](
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsCodec[A, Params],
    pipeline: LiveRouteMountPipeline[R, A, Need, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    new LiveRouteMountAspectParamsBuilder(
      pathCodec,
      paramsCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

/** The root route seed exposed as `live`. */
final class LiveRouteSeed[A] private[scalive] (
  pathCodec: PathCodec[A],
  layouts: Vector[LiveLayout[A, Any]],
  rootLayout: Option[LiveRootLayout[A, Any]],
  connectedTurnGuards: LiveConnectedTurnGuard[Any],
  connectedResourceInitializers: LiveConnectedResourceInitializers[Any])
    extends LiveRouteBuilder[A](
      pathCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

object LiveRouteSeed:
  private[scalive] def apply[A](
    pathCodec: PathCodec[A],
    layouts: Vector[LiveLayout[A, Any]] = Vector.empty,
    rootLayout: Option[LiveRootLayout[A, Any]] = None,
    connectedTurnGuards: LiveConnectedTurnGuard[Any] = LiveConnectedTurnGuard.empty,
    connectedResourceInitializers: LiveConnectedResourceInitializers[Any] =
      LiveConnectedResourceInitializers.empty
  ): LiveRouteSeed[A] =
    new LiveRouteSeed(
      pathCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

/** Declares a route whose decoded path and query values are the parameters of a
  * [[LiveView.Routed LiveView.Routed]].
  *
  * `query`, `queryOptional`, and `params` on [[LiveRouteBuilder]] return this builder. Finish it
  * like an ordinary route, with `->`, [[from]], or [[context]]. See the [routes
  * guide](https://scalive.dev/guides/routes-and-navigation#mount-and-react-to-parameters).
  */
class LiveRouteParamsBuilder[A, Params] private[scalive] (
  protected val pathCodec: PathCodec[A],
  protected val paramsCodec: LiveParamsDecoder[A, Params],
  protected val layouts: Vector[LiveLayout[A, Any]],
  protected val rootLayout: Option[LiveRootLayout[A, Any]],
  protected val connectedTurnGuards: LiveConnectedTurnGuard[Any],
  protected val connectedResourceInitializers: LiveConnectedResourceInitializers[Any]):

  /** Appends connected setup before this route's connected mount. */
  def withConnectedResources[Result <: Unit](
    initialize: (Any, ConnectedResources) => zio.Task[Result]
  ): LiveRouteParamsBuilder[A, Params] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Converts the decoded parameters with a function that cannot be reversed.
    *
    * The resulting route can no longer build links with `location`. Prefer
    * [[LiveEncodableRouteParamsBuilder.mapParams]] when the conversion can be reversed.
    */
  def mapParamsDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveRouteParamsBuilder[A, Params2] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsCodec.mapDecodeOnly(decodeParams),
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Any => zio.IO[LiveConnectedTurnFailure, Result]
  ): LiveRouteParamsBuilder[A, Params] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsCodec,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      connectedResourceInitializers
    )

  /** Registers the routed LiveView this route mounts.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Prefer
    * `->`, which reads better in a router.
    */
  def apply[Msg, Model](
    view: => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } =
    routed((_, _) => view)

  /** Registers the routed LiveView this route mounts.
    *
    * `factory` receives the HTTP request of the disconnected render. For a connected mount it
    * receives a GET request for the page's path and query, without the browser's headers or
    * cookies.
    */
  def apply[Msg, Model](
    factory: Request => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } = routed((_, request) => factory(request))

  /** Registers a routed LiveView built from the context of the named live session around this
    * route.
    *
    * `factory` receives the context. The route compiles only inside a session whose mount aspects
    * produce that context.
    */
  def context[Ctx, Msg, Model](
    factory: Ctx => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    context((_, _, context) => factory(context))

  /** Registers a routed LiveView built from the context of the named live session around this
    * route.
    *
    * `factory` also receives one service from the application environment, which the route adds to
    * the application's requirements.
    */
  def context[Ctx, R: Tag, Msg, Model](
    factory: (Ctx, R) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Ctx } =
    context((_, _, context, service) => factory(context, service))

  /** Registers a routed LiveView built from the context of the named live session around this
    * route.
    *
    * `factory` also receives the path values and the request.
    */
  def context[Ctx, Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Required(),
        factory,
        paramsCodec,
        connectedTurnGuards.contramap((_: Ctx) => ()),
        connectedResourceInitializers.contramap((_: Ctx) => ()),
        layouts.map(_.forContext((_: Ctx) => ())),
        rootLayout.map(_.forContext((_: Ctx) => ()))
      )
    )

  /** Registers a routed LiveView built from the context of the named live session around this
    * route.
    *
    * `factory` also receives the path values, the request, and one service from the application
    * environment.
    */
  def context[Ctx, R: Tag, Msg, Model](
    factory: (A, Request, Ctx, R) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Ctx } =
    val requiredLayouts    = layouts.map(_.forContext((_: Ctx) => ()))
    val requiredRootLayout = rootLayout.map(_.forContext((_: Ctx) => ()))
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Required(), summon[Tag[R]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        paramsCodec,
        connectedTurnGuards.contramap((_: (Ctx, R)) => ()),
        connectedResourceInitializers.contramap((_: (Ctx, R)) => ()),
        requiredLayouts.map(_.forContext((_: (Ctx, R))._1)),
        requiredRootLayout.map(_.forContext((_: (Ctx, R))._1))
      )
    )

  /** Registers the routed LiveView this route mounts, as in `Routes.search -> SearchLiveView()`.
    *
    * `view` is evaluated for the disconnected render and again for each connected mount. Use
    * [[from]] when the LiveView needs a service.
    */
  infix def ->[Msg, Model](
    view: => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } =
    apply(view)

  /** Registers a routed LiveView built from one service in the application environment.
    *
    * The route adds `R` to the application's environment requirement, so the server must provide
    * it. Scalive calls the factory for the disconnected render and for each connected mount. See
    * the [services guide](https://scalive.dev/guides/services-and-zlayer-injection).
    */
  def from[R: Tag, Msg, Model](
    factory: R => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Any } =
    from((_: A, _: Request, service: R) => factory(service))

  /** Registers a routed LiveView built from one service in the application environment.
    *
    * `factory` also receives the path values and the request.
    */
  def from[R: Tag, Msg, Model](
    factory: (A, Request, R) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Any } =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Environment(summon[Tag[R]]),
        factory,
        paramsCodec,
        connectedTurnGuards.contramap((_: R) => ()),
        connectedResourceInitializers.contramap((_: R) => ()),
        layouts.map(_.forContext((_: R) => ())),
        rootLayout.map(_.forContext((_: R) => ()))
      )
    )

  private def routed[Msg, Model](
    factory: (A, Request) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Direct(),
        (path, request, _) => factory(path, request),
        paramsCodec,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout
      )
    )
end LiveRouteParamsBuilder

object LiveRouteParamsBuilder:
  private[scalive] def apply[A, Params](
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsDecoder[A, Params],
    layouts: Vector[LiveLayout[A, Any]],
    rootLayout: Option[LiveRootLayout[A, Any]],
    connectedTurnGuards: LiveConnectedTurnGuard[Any],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Any]
  ): LiveRouteParamsBuilder[A, Params] =
    new LiveRouteParamsBuilder(
      pathCodec,
      paramsCodec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

/** A [[LiveRouteParamsBuilder]] that can also encode its parameters into links with [[location]].
  *
  * @param codec
  *   The codec that decodes the route's parameters from a URL and encodes them back into links.
  */
final class LiveEncodableRouteParamsBuilder[A, Params] private[scalive] (
  pathCodec: PathCodec[A],
  val codec: LiveParamsCodec[A, Params],
  layouts: Vector[LiveLayout[A, Any]],
  rootLayout: Option[LiveRootLayout[A, Any]],
  connectedTurnGuards: LiveConnectedTurnGuard[Any],
  connectedResourceInitializers: LiveConnectedResourceInitializers[Any])
    extends LiveRouteParamsBuilder[A, Params](
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    ):
  /** Appends connected setup while retaining parameter encoding and location construction. */
  override def withConnectedResources[Result <: Unit](
    initialize: (Any, ConnectedResources) => zio.Task[Result]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize))
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  override def guardConnectedTurns[Result <: Unit](
    guard: Any => zio.IO[LiveConnectedTurnFailure, Result]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      connectedResourceInitializers
    )

  /** Converts the decoded parameters into a domain type, keeping the route able to build links.
    *
    * `decodeParams` runs on each decoded URL and `encodeParams` on each [[location]] call, so they
    * must be inverses.
    */
  def mapParams[Params2](
    decodeParams: Params => Params2
  )(
    encodeParams: Params2 => Params
  ): LiveEncodableRouteParamsBuilder[A, Params2] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec.imap(decodeParams)(encodeParams),
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

  /** Builds a link or navigation destination for this route from its parameters.
    *
    * Pass the parameters the LiveView receives, as in `Routes.search.location(Some("LiveView"))`.
    * Throws [[LiveLocation.EncodingException]] when a value cannot be encoded; use
    * [[locationEither]] to handle that case.
    */
  def location(params: Params): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  /** Builds a link or navigation destination for this route, or returns why encoding failed. */
  def locationEither(params: Params): Either[LiveLocation.EncodeError, LiveLocation] =
    codec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))
end LiveEncodableRouteParamsBuilder

object LiveEncodableRouteParamsBuilder:
  private[scalive] def apply[A, Params](
    pathCodec: PathCodec[A],
    codec: LiveParamsCodec[A, Params],
    layouts: Vector[LiveLayout[A, Any]],
    rootLayout: Option[LiveRootLayout[A, Any]],
    connectedTurnGuards: LiveConnectedTurnGuard[Any],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Any]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    new LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards,
      connectedResourceInitializers
    )

/** A named, environment-typed group of declarative routes.
  *
  * @param name
  *   The name passed to `Live.session`, unique within an application.
  */
final class LiveSession[-R] private[scalive] (
  val name: String,
  private[scalive] val routes: Vector[LiveRouteFragment[R] { type Input = Any }])
    extends LiveRouteFragment[R]:
  /** The context a session needs from outside, always `Any`, so the router accepts it directly. */
  type Input = Any

object LiveSession:
  private[scalive] def apply[R](
    name: String,
    routes: Vector[LiveRouteFragment[R] { type Input = Any }]
  ): LiveSession[R] = new LiveSession(name, routes)

/** Declares a named live session: a group of routes that share navigation, layouts, and mount
  * policy.
  *
  * Create one with `Live.session(name)`, add mount aspects, layouts, and guards, then pass the
  * routes, as in `Live.session("account").withMountAspect(currentUser)(Routes.account...)`. Live
  * navigation stays inside a session; navigating to a route outside it loads a new document. See
  * [live sessions](https://scalive.dev/guides/live-sessions-and-mount-aspects).
  *
  * @tparam R
  *   the services the session's mount aspects need
  * @tparam Ctx
  *   the context the session's mount aspects produce for its routes
  * @param name
  *   The name passed to `Live.session`.
  */
final class LiveSessionBuilder[R, Ctx] private[scalive] (
  val name: String,
  private val pipeline: LiveSessionMountPipeline[R, Any, Ctx],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
  private val connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
  private val layouts: Vector[LiveLayout[Any, Ctx]],
  private val rootLayout: Option[LiveRootLayout[Any, Ctx]]):
  /** Appends session setup before route setup and connected mount, in declaration order. */
  def withConnectedResources[Result <: Unit](
    initialize: (Ctx, ConnectedResources) => zio.Task[Result]
  ): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards,
      connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize)),
      layouts,
      rootLayout
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
  ): LiveSessionBuilder[R & R1, Result] =
    LiveSessionBuilder(
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
    connections: Tag[LiveConnections[Id]]
  ): LiveSessionBuilder.Admitted[R & R1 & LiveConnections[Id], Result] =
    LiveSessionBuilder.Admitted(
      name,
      pipeline.admitThen(aspect, connectionId, connections),
      connectedTurnGuards.contramap(append.left),
      connectedResourceInitializers.contramap(append.left),
      layouts.map(_.forContext(append.left)),
      rootLayout.map(_.forContext(append.left))
    )

  /** Wraps every route in this session in a layout.
    *
    * Session layouts sit inside the router's layout and around route layouts, and nest in
    * installation order.
    */
  def withLayout(value: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards,
      connectedResourceInitializers,
      layouts :+ value,
      rootLayout
    )

  /** Wraps every route in this session in a layout.
    *
    * This form selects the layout's input from the context available at this declaration. The pure
    * selector adapts only the layout; later aspects preserve this context selection.
    */
  def withLayout[LayoutContext](
    layout: LiveLayout[Any, LayoutContext],
    select: Ctx => LayoutContext
  ): LiveSessionBuilder[R, Ctx] = withLayout(layout.forContext(select))

  /** Renders the document of every route in this session, replacing the router's root layout. */
  def withRootLayout(value: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards,
      connectedResourceInitializers,
      layouts,
      Some(value)
    )

  /** Renders the document of every route in this session, replacing the router's root layout.
    *
    * This form selects the layout's input from this declaration's context. Key and view select
    * independently, so the selector must be pure and deterministic.
    */
  def withRootLayout[LayoutContext](
    layout: LiveRootLayout[Any, LayoutContext],
    select: Ctx => LayoutContext
  ): LiveSessionBuilder[R, Ctx] = withRootLayout(layout.forContext(select))

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns[Result <: Unit](
    guard: Ctx => zio.IO[LiveConnectedTurnFailure, Result]
  ): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      connectedResourceInitializers,
      layouts,
      rootLayout
    )

  /** Puts `routes` in this session and returns it for the router.
    *
    * A route registered with `context` compiles only when this session produces the context it
    * reads. Sessions do not nest, so `routes` cannot include another session.
    */
  def apply[R1](routes: (LiveRoute[? >: R1, ?] { type Input >: Ctx })*): LiveSession[R & R1] =
    val attached = routes.toVector.flatMap { route =>
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

/** The companion of [[LiveSessionBuilder]], which holds the builder returned by `withAdmission`. */
object LiveSessionBuilder:
  private[scalive] def apply[R, Ctx](
    name: String,
    pipeline: LiveSessionMountPipeline[R, Any, Ctx],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
    layouts: Vector[LiveLayout[Any, Ctx]],
    rootLayout: Option[LiveRootLayout[Any, Ctx]]
  ): LiveSessionBuilder[R, Ctx] =
    new LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards,
      connectedResourceInitializers,
      layouts,
      rootLayout
    )

  /** Session construction after its one admission boundary has been declared.
    *
    * @param name
    *   The name passed to `Live.session`.
    */
  final class Admitted[R, Ctx] private[scalive] (
    val name: String,
    private val pipeline: LiveSessionMountPipeline[R, Any, Ctx],
    private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    private val connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
    private val layouts: Vector[LiveLayout[Any, Ctx]],
    private val rootLayout: Option[LiveRootLayout[Any, Ctx]]):
    /** Appends session setup using the context available after admission. */
    def withConnectedResources[Result <: Unit](
      initialize: (Ctx, ConnectedResources) => zio.Task[Result]
    ): Admitted[R, Ctx] =
      Admitted(
        name,
        pipeline,
        connectedTurnGuards,
        connectedResourceInitializers.andThen(LiveConnectedResourceInitializers(initialize)),
        layouts,
        rootLayout
      )

    /** Runs `aspect` before every route in this session mounts and adds its output to the context.
      *
      * The aspect runs after the admission aspect and receives its context.
      */
    def withMountAspect[R1, Claims, Out, Result](
      aspect: LiveSessionMountAspect[R1, Ctx, Claims, Out]
    )(using append: ContextAppend.Aux[Ctx, Out, Result]
    ): Admitted[R & R1, Result] =
      Admitted(
        name,
        pipeline.andThen(aspect),
        connectedTurnGuards.contramap(append.left),
        connectedResourceInitializers.contramap(append.left),
        layouts.map(_.forContext(append.left)),
        rootLayout.map(_.forContext(append.left))
      )

    /** Wraps every route in this session in a layout.
      *
      * Session layouts sit inside the router's layout and around route layouts, and nest in
      * installation order.
      */
    def withLayout(value: LiveLayout[Any, Ctx]): Admitted[R, Ctx] =
      Admitted(
        name,
        pipeline,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts :+ value,
        rootLayout
      )

    /** Wraps every route in this session in a layout.
      *
      * This form selects the layout's input from the context available at this declaration. The
      * pure selector adapts only the layout; later aspects preserve this context selection.
      */
    def withLayout[LayoutContext](
      layout: LiveLayout[Any, LayoutContext],
      select: Ctx => LayoutContext
    ): Admitted[R, Ctx] = withLayout(layout.forContext(select))

    /** Renders the document of every route in this session, replacing the router's root layout. */
    def withRootLayout(value: LiveRootLayout[Any, Ctx]): Admitted[R, Ctx] =
      Admitted(
        name,
        pipeline,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        Some(value)
      )

    /** Renders the document of every route in this session, replacing the router's root layout.
      *
      * This form selects the layout's input from this declaration's context. Key and view select
      * independently, so the selector must be pure and deterministic.
      */
    def withRootLayout[LayoutContext](
      layout: LiveRootLayout[Any, LayoutContext],
      select: Ctx => LayoutContext
    ): Admitted[R, Ctx] = withRootLayout(layout.forContext(select))

    /** Appends a policy check before each connected application turn.
      *
      * Guards at one boundary run in declaration order; session guards run before route guards. The
      * callback receives the context available at this builder. Succeed with `Unit` to continue, or
      * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or
      * disconnect. Guards are inherited by nested LiveViews and do not run during mount,
      * disconnected rendering, or framework cleanup.
      */
    def guardConnectedTurns[Result <: Unit](
      guard: Ctx => zio.IO[LiveConnectedTurnFailure, Result]
    ): Admitted[R, Ctx] =
      Admitted(
        name,
        pipeline,
        connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
        connectedResourceInitializers,
        layouts,
        rootLayout
      )

    /** Puts `routes` in this session and returns it for the router.
      *
      * A route registered with `context` compiles only when this session produces the context it
      * reads. Sessions do not nest, so `routes` cannot include another session.
      */
    def apply[R1](routes: (LiveRoute[? >: R1, ?] { type Input >: Ctx })*): LiveSession[R & R1] =
      val attached = routes.toVector.flatMap { route =>
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
  end Admitted

  private[scalive] object Admitted:
    def apply[R, Ctx](
      name: String,
      pipeline: LiveSessionMountPipeline[R, Any, Ctx],
      connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
      connectedResourceInitializers: LiveConnectedResourceInitializers[Ctx],
      layouts: Vector[LiveLayout[Any, Ctx]],
      rootLayout: Option[LiveRootLayout[Any, Ctx]]
    ): Admitted[R, Ctx] =
      new Admitted(
        name,
        pipeline,
        connectedTurnGuards,
        connectedResourceInitializers,
        layouts,
        rootLayout
      )
end LiveSessionBuilder

/** All routes and layouts of a Scalive application, built by `Live.router(...)`.
  *
  * Serve it with `ZioHttp.routes(application, config)`, which validates the routes when called.
  *
  * @tparam R
  *   the services the routes need from the server's environment
  * @param routes
  *   The routes and named live sessions passed to the router, in declaration order.
  * @param socketPath
  *   The path of the LiveView WebSocket, `/live` unless set with [[LiveRouter.withSocketPath]].
  * @param layouts
  *   The router's layouts, which wrap every route outside session and route layouts, in
  *   installation order.
  * @param rootLayout
  *   The root layout used by routes whose session or route installs none; without
  *   [[LiveRouter.withRootLayout]], it is [[LiveRootLayout.identity]].
  */
final class LiveApplication[-R] private[scalive] (
  val routes: Vector[LiveRouteFragment[R]],
  val socketPath: PathCodec[Unit],
  val layouts: Vector[LiveLayout[Any, Any]],
  val rootLayout: LiveRootLayout[Any, Any])

object LiveApplication:
  private[scalive] def apply[R](
    routes: Vector[LiveRouteFragment[R]],
    socketPath: PathCodec[Unit],
    layouts: Vector[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any]
  ): LiveApplication[R] = new LiveApplication(routes, socketPath, layouts, rootLayout)

/** Collects an application's routes into a [[LiveApplication]]. Start from `Live.router`. */
final class LiveRouter private[scalive] (
  private val socketPath: PathCodec[Unit],
  private val layouts: Vector[LiveLayout[Any, Any]],
  private val rootLayout: LiveRootLayout[Any, Any]):

  /** Serves the LiveView WebSocket at `value` instead of `/live`.
    *
    * The browser's `LiveSocket` must connect to the same path.
    */
  def withSocketPath(value: PathCodec[Unit]): LiveRouter = LiveRouter(value, layouts, rootLayout)

  /** Wraps every route in `value`.
    *
    * Router layouts sit outside session and route layouts, and nest in installation order.
    */
  def withLayout(value: LiveLayout[Any, Any]): LiveRouter =
    LiveRouter(socketPath, layouts :+ value, rootLayout)

  /** Renders every route's document with `value`, unless a session or route installs its own.
    *
    * Without one, the response contains only the LiveView, with no document or client scripts
    * around it.
    */
  def withRootLayout(value: LiveRootLayout[Any, Any]): LiveRouter =
    LiveRouter(socketPath, layouts, value)

  /** Builds the application from routes and named live sessions.
    *
    * Routes that read session context must be inside the [[LiveSessionBuilder]] that produces it.
    */
  def apply[R](routes: (LiveRouteFragment[R] { type Input = Any })*): LiveApplication[R] =
    LiveApplication(routes.toVector, socketPath, layouts, rootLayout)
end LiveRouter

object LiveRouter:
  private[scalive] def apply(
    socketPath: PathCodec[Unit],
    layouts: Vector[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any]
  ): LiveRouter = new LiveRouter(socketPath, layouts, rootLayout)

/** Entry points for declaring a Scalive application. */
object Live:
  /** Starts an application, as in
    * `Live.router.withRootLayout(RootLayout())(live -> HomeLiveView())`.
    *
    * Pass the result to `ZioHttp.routes` to serve it. See the [quick
    * start](https://scalive.dev/learn/quick-start).
    */
  val router: LiveRouter =
    LiveRouter(PathCodec.empty / "live", Vector.empty, LiveRootLayout.identity)

  /** Starts a route from a ZIO HTTP path codec. Most routes start from `live` instead. */
  def route[A](pathCodec: PathCodec[A]): LiveRouteSeed[A] = LiveRouteSeed(pathCodec)

  /** Starts a named live session, a group of routes that share navigation and mount policy.
    *
    * Names must be unique within the application.
    */
  def session(name: String): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder(
      name,
      LiveSessionMountPipeline.Identity[Any](),
      LiveConnectedTurnGuard.empty,
      LiveConnectedResourceInitializers.empty,
      Vector.empty,
      None
    )

/** The root path `/`, from which routes are declared, as in
  * `live / "counter" -> CounterLiveView()`.
  *
  * See the [routes guide](https://scalive.dev/guides/routes-and-navigation).
  */
val live: LiveRouteSeed[Unit] = LiveRouteSeed(PathCodec.empty)
