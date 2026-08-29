package scalive

import scala.annotation.targetName

import zio.Tag
import zio.http.Request
import zio.http.codec.{Combiner, HttpCodec, PathCodec, QueryCodec}
import zio.schema.Schema

/** A typed mount pipeline whose individual claim types remain hidden until runtime interpretation.
  *
  * The tree is deliberately retained rather than collapsed: interpreters can run session trees
  * before route trees, and every aspect node still owns the codec for its claims.
  */
sealed private[scalive] trait LiveMountPipeline[R, A, In, Ctx]:
  def andThen[R1, Claims, Out, Result](
    next: LiveMountAspect[R1, A, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveMountPipeline[R & R1, A, In, Result] =
    LiveMountPipeline.Then(this, next, append)

  def admitThen[R1, Claims, Out, Result, Id](
    next: LiveMountAspect[R1, A, Ctx, Claims, Out],
    connectionId: Claims => Id,
    connections: Tag[LiveConnections[Id]]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveMountPipeline[R & R1 & LiveConnections[Id], A, In, Result] =
    LiveMountPipeline.Controlled(this, next, connectionId, connections, append)

object LiveMountPipeline:
  final case class Identity[A, Ctx]() extends LiveMountPipeline[Any, A, Ctx, Ctx]

  final case class Then[R, R1, A, In, Ctx, Claims, Out, Result](
    previous: LiveMountPipeline[R, A, In, Ctx],
    aspect: LiveMountAspect[R1, A, Ctx, Claims, Out],
    append: ContextAppend.Aux[Ctx, Out, Result])
      extends LiveMountPipeline[R & R1, A, In, Result]

  final case class Controlled[R, R1, A, In, Ctx, Claims, Out, Result, Id](
    previous: LiveMountPipeline[R, A, In, Ctx],
    aspect: LiveMountAspect[R1, A, Ctx, Claims, Out],
    connectionId: Claims => Id,
    connections: Tag[LiveConnections[Id]],
    append: ContextAppend.Aux[Ctx, Out, Result])
      extends LiveMountPipeline[R & R1 & LiveConnections[Id], A, In, Result]

/** Selects how a completed route obtains the context passed to its lifecycle factory and layouts.
  */
sealed private[scalive] trait LiveRouteContext[R, A, In, Ctx]

object LiveRouteContext:
  final case class Direct[A]()                    extends LiveRouteContext[Any, A, Any, Any]
  final case class Environment[R, A](tag: Tag[R]) extends LiveRouteContext[R, A, Any, R]
  final case class Required[A, Ctx]()             extends LiveRouteContext[Any, A, Ctx, Ctx]
  final case class Mounted[R, A, In, Ctx](pipeline: LiveMountPipeline[R, A, In, Ctx])
      extends LiveRouteContext[R, A, In, Ctx]

  /** Resolves an existing route context before adding one application-environment service. */
  final case class WithEnvironment[R0, R1, A, In, Ctx](
    previous: LiveRouteContext[R0, A, In, Ctx],
    service: Tag[R1])
      extends LiveRouteContext[R0 & R1, A, In, (Ctx, R1)]

  /** Retains the lifecycle order and the proof that session context supplies route input. */
  final case class SessionMounted[RS, RR, A, SessionCtx, RouteIn, RouteCtx](
    session: LiveMountPipeline[RS, Any, Any, SessionCtx],
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
    pipeline: LiveMountPipeline[R, Any, Any, SessionCtx],
    sessionGuards: LiveConnectedTurnGuard[SessionCtx],
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
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Environment = R
    type Input       = In
    type Context     = Ctx
    type Msg         = Message
    type Model       = State

    def withSession[R1, SessionCtx](
      pipeline: LiveMountPipeline[R1, Any, Any, SessionCtx],
      sessionGuards: LiveConnectedTurnGuard[SessionCtx],
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
        sessionLayouts.map(LiveLayout.contramapContext(_, (_: (SessionCtx, Ctx))._1)) ++
          layouts.map(LiveLayout.contramapContext(_, (_: (SessionCtx, Ctx))._2)),
        rootLayout
          .map(LiveRootLayout.contramapContext(_, (_: (SessionCtx, Ctx))._2))
          .orElse(
            sessionRootLayout.map(LiveRootLayout.contramapContext(_, (_: (SessionCtx, Ctx))._1))
          )
      )
  end Ordinary

  final case class Routed[R, A, In, Ctx, Message, State, Params](
    pathCodec: PathCodec[A],
    context: LiveRouteContext[R, A, In, Ctx],
    factory: (A, Request, Ctx) => LiveView.Routed[Message, State, Params],
    paramsCodec: LiveParamsDecoder[A, Params],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Environment = R
    type Input       = In
    type Context     = Ctx
    type Msg         = Message
    type Model       = State

    def withSession[R1, SessionCtx](
      pipeline: LiveMountPipeline[R1, Any, Any, SessionCtx],
      sessionGuards: LiveConnectedTurnGuard[SessionCtx],
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
        sessionLayouts.map(LiveLayout.contramapContext(_, (_: (SessionCtx, Ctx))._1)) ++
          layouts.map(LiveLayout.contramapContext(_, (_: (SessionCtx, Ctx))._2)),
        rootLayout
          .map(LiveRootLayout.contramapContext(_, (_: (SessionCtx, Ctx))._2))
          .orElse(
            sessionRootLayout.map(LiveRootLayout.contramapContext(_, (_: (SessionCtx, Ctx))._1))
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
    pipeline: LiveMountPipeline[RS, Any, Any, SessionCtx],
    guards: LiveConnectedTurnGuard[SessionCtx],
    layouts: Vector[LiveLayout[Any, SessionCtx]],
    rootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Input
  ): Vector[LiveRouteFragment[R & RS] { type Input = Any }] =
    Vector(LiveRoute(definition.withSession(pipeline, guards, layouts, rootLayout, supplies)))

object LiveRoute:
  private[scalive] def apply[R, A, Need](
    value: LiveRouteDefinition[A] { type Environment = R; type Input = Need }
  ): LiveRoute[R, A] { type Input = Need } =
    new LiveRoute[R, A]:
      type Input = Need
      val definition = value

/** A completed route or typed group of routes accepted by application assembly. */
sealed trait LiveRouteFragment[-R]:
  type Input

  private[scalive] def declarations: Vector[LiveRoute[?, ?]] = this match
    case route: LiveRoute[?, ?]  => Vector(route)
    case session: LiveSession[?] => session.routes.flatMap(_.declarations)

  private[scalive] def attachSession[RS, SessionCtx](
    pipeline: LiveMountPipeline[RS, Any, Any, SessionCtx],
    guards: LiveConnectedTurnGuard[SessionCtx],
    layouts: Vector[LiveLayout[Any, SessionCtx]],
    rootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Input
  ): Vector[LiveRouteFragment[R & RS] { type Input = Any }]

/** Common operations for an ordinary (non-routed) LiveView route. */
class LiveRouteBuilder[A] private[scalive] (
  private[scalive] val pathCodec: PathCodec[A],
  private val layouts: Vector[LiveLayout[A, Any]] = Vector.empty,
  private val rootLayout: Option[LiveRootLayout[A, Any]] = None,
  private val connectedTurnGuards: LiveConnectedTurnGuard[Any] = LiveConnectedTurnGuard.empty):

  def /[B](that: PathCodec[B])(using combiner: Combiner[A, B]): LiveRouteSeed[combiner.Out] =
    LiveRouteSeed(pathCodec / that)

  def location(value: A): LiveLocation =
    locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
    LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

  @targetName("unitLocation")
  def location(using ev: A =:= Unit): LiveLocation = location(ev.flip(()))

  @targetName("unitLocationEither")
  def locationEither(using ev: A =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(ev.flip(()))

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
      connectedTurnGuards
    )

  def query[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Query]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query](name))

  def queryOptional[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Option[Query]]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query](name).optional)

  def query[Query: Schema](
    using
    combiner: Combiner[A, Query]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query])

  def params: LiveEncodableRouteParamsBuilder[A, A] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      LiveParamsCodec.path[A],
      layouts,
      rootLayout,
      connectedTurnGuards
    )

  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    LiveEncodableRouteParamsBuilder(pathCodec, codec, layouts, rootLayout, connectedTurnGuards)

  def paramsDecodeOnly[Params](
    decoder: LiveParamsDecoder[A, Params]
  ): LiveRouteParamsBuilder[A, Params] =
    LiveRouteParamsBuilder(pathCodec, decoder, layouts, rootLayout, connectedTurnGuards)

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns(
    guard: Any => zio.IO[LiveConnectedTurnFailure, Unit]
  ): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard))
    )

  def withLayout(layout: LiveLayout[A, Any]): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts :+ layout,
      rootLayout,
      connectedTurnGuards
    )

  def withRootLayout(layout: LiveRootLayout[A, Any]): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts,
      Some(layout),
      connectedTurnGuards
    )

  /** Starts a typed route mount pipeline. The resulting context is supplied to route factories. */
  def withMountAspect[R, In, Claims, Out, Result](
    aspect: LiveMountAspect[R, A, In, Claims, Out]
  )(using append: ContextAppend.Aux[In, Out, Result]
  ): LiveRouteMountAspectBuilder[R, A, In, Result] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      LiveMountPipeline.Identity[A, In]().andThen(aspect),
      layouts.map(LiveLayout.contramapContext(_, (_: Result) => ())),
      rootLayout.map(LiveRootLayout.contramapContext(_, (_: Result) => ())),
      connectedTurnGuards.contramap((_: Result) => ())
    )

  def apply[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[Any, A] { type Input = Any } =
    ordinary((_, _) => view)

  def apply[Msg, Model](
    factory: Request => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Any } =
    ordinary((_, request) => factory(request))

  @targetName("contextFactory")
  def apply[Ctx, Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Required(),
        factory,
        connectedTurnGuards.contramap((_: Ctx) => ()),
        layouts.map(LiveLayout.contramapContext(_, (_: Ctx) => ())),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: Ctx) => ()))
      )
    )

  def context[Ctx, Msg, Model](
    factory: Ctx => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    context((_, _, context) => factory(context))

  def context[Ctx, R: Tag, Msg, Model](
    factory: (Ctx, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Ctx } =
    context((_, _, context, service) => factory(context, service))

  def context[Ctx, Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    apply(factory)

  def context[Ctx, R: Tag, Msg, Model](
    factory: (A, Request, Ctx, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Ctx } =
    val requiredLayouts    = layouts.map(LiveLayout.contramapContext(_, (_: Ctx) => ()))
    val requiredRootLayout = rootLayout.map(LiveRootLayout.contramapContext(_, (_: Ctx) => ()))
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Required(), summon[Tag[R]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        connectedTurnGuards.contramap((_: (Ctx, R)) => ()),
        requiredLayouts.map(LiveLayout.contramapContext(_, (_: (Ctx, R))._1)),
        requiredRootLayout.map(LiveRootLayout.contramapContext(_, (_: (Ctx, R))._1))
      )
    )

  infix def ->[Msg, Model](
    view: => LiveView[Msg, Model]
  ): LiveRoute[Any, A] { type Input = Any } = apply(view)

  def from[R: Tag, Msg, Model](
    factory: (A, Request, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Any } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Environment(summon[Tag[R]]),
        factory,
        connectedTurnGuards.contramap((_: R) => ()),
        layouts.map(LiveLayout.contramapContext(_, (_: R) => ())),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: R) => ()))
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
    connectedTurnGuards: LiveConnectedTurnGuard[Any] = LiveConnectedTurnGuard.empty
  ): LiveRouteBuilder[A] =
    new LiveRouteBuilder(pathCodec, layouts, rootLayout, connectedTurnGuards)

/** Route construction after a mount aspect has produced typed lifecycle context. */
final class LiveRouteMountAspectBuilder[R, A, Need, Ctx] private[scalive] (
  private val pathCodec: PathCodec[A],
  private val pipeline: LiveMountPipeline[R, A, Need, Ctx],
  private val layouts: Vector[LiveLayout[A, Ctx]],
  private val rootLayout: Option[LiveRootLayout[A, Ctx]],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx]):

  def withMountAspect[R1, Claims, Out, Result](
    aspect: LiveMountAspect[R1, A, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveRouteMountAspectBuilder[R & R1, A, Need, Result] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline.andThen(aspect),
      layouts.map(LiveLayout.contramapContext(_, append.left)),
      rootLayout.map(LiveRootLayout.contramapContext(_, append.left)),
      connectedTurnGuards.contramap(append.left)
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns(
    guard: Ctx => zio.IO[LiveConnectedTurnFailure, Unit]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard))
    )

  def withLayout(layout: LiveLayout[A, Ctx]): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts :+ layout,
      rootLayout,
      connectedTurnGuards
    )

  def withRootLayout(
    layout: LiveRootLayout[A, Ctx]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      Some(layout),
      connectedTurnGuards
    )

  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    LiveRouteMountAspectParamsBuilder(
      pathCodec,
      codec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards
    )

  def params: LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, A] =
    params(LiveParamsCodec.path[A])

  def query[Query](
    codec: QueryCodec[Query]
  )(using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    params(LiveParamsCodec.fromQuery(codec))

  def query[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    query(HttpCodec.query[Query](name))

  def queryOptional[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Option[Query]]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    query(HttpCodec.query[Query](name).optional)

  def query[Query: Schema](
    using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, combiner.Out] =
    query(HttpCodec.query[Query])

  def apply[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[R, A] { type Input = Need } =
    from((_, _, _) => view)

  def apply[Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Need } = from(factory)

  def context[Msg, Model](
    factory: Ctx => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Need } =
    context((_, _, context) => factory(context))

  def context[R1: Tag, Msg, Model](
    factory: (Ctx, R1) => LiveView[Msg, Model]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    context[R1, Msg, Model]((_: A, _: Request, context: Ctx, service: R1) =>
      factory(context, service)
    )

  def context[Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Need } =
    from(factory)

  def context[R1: Tag, Msg, Model](
    factory: (A, Request, Ctx, R1) => LiveView[Msg, Model]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Mounted(pipeline), summon[Tag[R1]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        connectedTurnGuards.contramap((_: (Ctx, R1))._1),
        layouts.map(LiveLayout.contramapContext(_, (_: (Ctx, R1))._1)),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: (Ctx, R1))._1))
      )
    )

  def from[Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Mounted(pipeline),
        factory,
        connectedTurnGuards,
        layouts,
        rootLayout
      )
    )
end LiveRouteMountAspectBuilder

object LiveRouteMountAspectBuilder:
  private[scalive] def apply[R, A, Need, Ctx](
    pathCodec: PathCodec[A],
    pipeline: LiveMountPipeline[R, A, Need, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx]
  ): LiveRouteMountAspectBuilder[R, A, Need, Ctx] =
    new LiveRouteMountAspectBuilder(pathCodec, pipeline, layouts, rootLayout, connectedTurnGuards)

/** Parameterized route construction after mount aspects have produced context. */
final class LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] private[scalive] (
  private val pathCodec: PathCodec[A],
  private val paramsCodec: LiveParamsCodec[A, Params],
  private val pipeline: LiveMountPipeline[R, A, Need, Ctx],
  private val layouts: Vector[LiveLayout[A, Ctx]],
  private val rootLayout: Option[LiveRootLayout[A, Ctx]],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx]):

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns(
    guard: Ctx => zio.IO[LiveConnectedTurnFailure, Unit]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    LiveRouteMountAspectParamsBuilder(
      pathCodec,
      paramsCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard))
    )

  def location(params: Params): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(params: Params): Either[LiveLocation.EncodeError, LiveLocation] =
    paramsCodec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))

  def apply[Msg, Model](
    view: => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    from((_, _, _) => view)

  def apply[Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } = from(factory)

  def context[Msg, Model](
    factory: Ctx => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    context((_, _, context) => factory(context))

  def context[R1: Tag, Msg, Model](
    factory: (Ctx, R1) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R & R1, A] { type Input = Need } =
    context[R1, Msg, Model]((_: A, _: Request, context: Ctx, service: R1) =>
      factory(context, service)
    )

  def context[Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    from(factory)

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
        layouts.map(LiveLayout.contramapContext(_, (_: (Ctx, R1))._1)),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: (Ctx, R1))._1))
      )
    )

  def from[Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Need } =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Mounted(pipeline),
        factory,
        paramsCodec,
        connectedTurnGuards,
        layouts,
        rootLayout
      )
    )
end LiveRouteMountAspectParamsBuilder

object LiveRouteMountAspectParamsBuilder:
  private[scalive] def apply[R, A, Need, Ctx, Params](
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsCodec[A, Params],
    pipeline: LiveMountPipeline[R, A, Need, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx]
  ): LiveRouteMountAspectParamsBuilder[R, A, Need, Ctx, Params] =
    new LiveRouteMountAspectParamsBuilder(
      pathCodec,
      paramsCodec,
      pipeline,
      layouts,
      rootLayout,
      connectedTurnGuards
    )

/** The root route seed exposed as `live`. */
final class LiveRouteSeed[A] private[scalive] (pathCodec: PathCodec[A])
    extends LiveRouteBuilder[A](
      pathCodec,
      Vector.empty[LiveLayout[A, Any]],
      None
    )

object LiveRouteSeed:
  private[scalive] def apply[A](pathCodec: PathCodec[A]): LiveRouteSeed[A] =
    new LiveRouteSeed(pathCodec)

/** A route whose path and query values are decoded for a sibling [[LiveView.Routed]]. */
class LiveRouteParamsBuilder[A, Params] private[scalive] (
  protected val pathCodec: PathCodec[A],
  protected val paramsCodec: LiveParamsDecoder[A, Params],
  protected val layouts: Vector[LiveLayout[A, Any]],
  protected val rootLayout: Option[LiveRootLayout[A, Any]],
  protected val connectedTurnGuards: LiveConnectedTurnGuard[Any]):

  def mapParamsDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveRouteParamsBuilder[A, Params2] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsCodec.mapDecodeOnly(decodeParams),
      layouts,
      rootLayout,
      connectedTurnGuards
    )

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns(
    guard: Any => zio.IO[LiveConnectedTurnFailure, Unit]
  ): LiveRouteParamsBuilder[A, Params] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsCodec,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard))
    )

  def apply[Msg, Model](
    view: => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } =
    routed((_, _) => view)

  def apply[Msg, Model](
    factory: Request => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } = routed((_, request) => factory(request))

  def context[Ctx, Msg, Model](
    factory: Ctx => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Ctx } =
    context((_, _, context) => factory(context))

  def context[Ctx, R: Tag, Msg, Model](
    factory: (Ctx, R) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Ctx } =
    context((_, _, context, service) => factory(context, service))

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
        layouts.map(LiveLayout.contramapContext(_, (_: Ctx) => ())),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: Ctx) => ()))
      )
    )

  def context[Ctx, R: Tag, Msg, Model](
    factory: (A, Request, Ctx, R) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] { type Input = Ctx } =
    val requiredLayouts    = layouts.map(LiveLayout.contramapContext(_, (_: Ctx) => ()))
    val requiredRootLayout = rootLayout.map(LiveRootLayout.contramapContext(_, (_: Ctx) => ()))
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.WithEnvironment(LiveRouteContext.Required(), summon[Tag[R]]),
        (path, request, contexts) => factory(path, request, contexts._1, contexts._2),
        paramsCodec,
        connectedTurnGuards.contramap((_: (Ctx, R)) => ()),
        requiredLayouts.map(LiveLayout.contramapContext(_, (_: (Ctx, R))._1)),
        requiredRootLayout.map(LiveRootLayout.contramapContext(_, (_: (Ctx, R))._1))
      )
    )

  infix def ->[Msg, Model](
    view: => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] { type Input = Any } =
    apply(view)

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
        layouts.map(LiveLayout.contramapContext(_, (_: R) => ())),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: R) => ()))
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
    connectedTurnGuards: LiveConnectedTurnGuard[Any]
  ): LiveRouteParamsBuilder[A, Params] =
    new LiveRouteParamsBuilder(pathCodec, paramsCodec, layouts, rootLayout, connectedTurnGuards)

final class LiveEncodableRouteParamsBuilder[A, Params] private[scalive] (
  pathCodec: PathCodec[A],
  val codec: LiveParamsCodec[A, Params],
  layouts: Vector[LiveLayout[A, Any]],
  rootLayout: Option[LiveRootLayout[A, Any]],
  connectedTurnGuards: LiveConnectedTurnGuard[Any])
    extends LiveRouteParamsBuilder[A, Params](
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards
    ):
  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  override def guardConnectedTurns(
    guard: Any => zio.IO[LiveConnectedTurnFailure, Unit]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard))
    )

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
      connectedTurnGuards
    )

  def location(params: Params): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(params: Params): Either[LiveLocation.EncodeError, LiveLocation] =
    codec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))
end LiveEncodableRouteParamsBuilder

object LiveEncodableRouteParamsBuilder:
  private[scalive] def apply[A, Params](
    pathCodec: PathCodec[A],
    codec: LiveParamsCodec[A, Params],
    layouts: Vector[LiveLayout[A, Any]],
    rootLayout: Option[LiveRootLayout[A, Any]],
    connectedTurnGuards: LiveConnectedTurnGuard[Any]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    new LiveEncodableRouteParamsBuilder(
      pathCodec,
      codec,
      layouts,
      rootLayout,
      connectedTurnGuards
    )

/** A named, environment-typed group of declarative routes. */
final class LiveSession[-R] private[scalive] (
  val name: String,
  private[scalive] val routes: Vector[LiveRouteFragment[R] { type Input = Any }])
    extends LiveRouteFragment[R]:
  type Input = Any
  private[scalive] def attachSession[RS, SessionCtx](
    pipeline: LiveMountPipeline[RS, Any, Any, SessionCtx],
    guards: LiveConnectedTurnGuard[SessionCtx],
    layouts: Vector[LiveLayout[Any, SessionCtx]],
    rootLayout: Option[LiveRootLayout[Any, SessionCtx]],
    supplies: SessionCtx <:< Any
  ): Vector[LiveRouteFragment[R & RS] { type Input = Any }] =
    routes.flatMap(_.attachSession(pipeline, guards, layouts, rootLayout, supplies))

object LiveSession:
  private[scalive] def apply[R](
    name: String,
    routes: Vector[LiveRouteFragment[R] { type Input = Any }]
  ): LiveSession[R] = new LiveSession(name, routes)

final class LiveSessionBuilder[R, Ctx] private[scalive] (
  val name: String,
  private val pipeline: LiveMountPipeline[R, Any, Any, Ctx],
  private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
  private val layouts: Vector[LiveLayout[Any, Ctx]],
  private val rootLayout: Option[LiveRootLayout[Any, Ctx]]):
  def withMountAspect[R1, Claims, Out, Result](
    aspect: LiveMountAspect[R1, Any, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveSessionBuilder[R & R1, Result] =
    LiveSessionBuilder(
      name,
      pipeline.andThen(aspect),
      connectedTurnGuards.contramap(append.left),
      layouts.map(LiveLayout.contramapContext(_, append.left)),
      rootLayout.map(LiveRootLayout.contramapContext(_, append.left))
    )

  /** Adds the session's single active-connection admission boundary.
    *
    * The signed aspect claim supplies the application-owned connection identifier. Connected mount
    * registers the physical transport before invoking the aspect's authoritative callback.
    */
  def withAdmission[R1, Claims, Out, Result, Id](
    aspect: LiveMountAspect[R1, Any, Ctx, Claims, Out]
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
      layouts.map(LiveLayout.contramapContext(_, append.left)),
      rootLayout.map(LiveRootLayout.contramapContext(_, append.left))
    )

  def withLayout(value: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(name, pipeline, connectedTurnGuards, layouts :+ value, rootLayout)

  def withRootLayout(value: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(name, pipeline, connectedTurnGuards, layouts, Some(value))

  /** Appends a policy check before each connected application turn.
    *
    * Guards at one boundary run in declaration order; session guards run before route guards. The
    * callback receives the context available at this builder. Succeed with `Unit` to continue, or
    * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or disconnect.
    * Guards are inherited by nested LiveViews and do not run during mount, disconnected rendering,
    * or framework cleanup.
    */
  def guardConnectedTurns(
    guard: Ctx => zio.IO[LiveConnectedTurnFailure, Unit]
  ): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      pipeline,
      connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
      layouts,
      rootLayout
    )

  def apply[R1](routes: (LiveRouteFragment[R1] { type Input >: Ctx })*): LiveSession[R & R1] =
    val attached = routes.toVector.flatMap { route =>
      route.attachSession(
        pipeline,
        connectedTurnGuards,
        layouts,
        rootLayout,
        summon[Ctx <:< route.Input]
      )
    }
    LiveSession(name, attached)
end LiveSessionBuilder

object LiveSessionBuilder:
  private[scalive] def apply[R, Ctx](
    name: String,
    pipeline: LiveMountPipeline[R, Any, Any, Ctx],
    connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    layouts: Vector[LiveLayout[Any, Ctx]],
    rootLayout: Option[LiveRootLayout[Any, Ctx]]
  ): LiveSessionBuilder[R, Ctx] =
    new LiveSessionBuilder(name, pipeline, connectedTurnGuards, layouts, rootLayout)

  /** Session construction after its one admission boundary has been declared. */
  final class Admitted[R, Ctx] private[scalive] (
    val name: String,
    private val pipeline: LiveMountPipeline[R, Any, Any, Ctx],
    private val connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
    private val layouts: Vector[LiveLayout[Any, Ctx]],
    private val rootLayout: Option[LiveRootLayout[Any, Ctx]]):
    def withMountAspect[R1, Claims, Out, Result](
      aspect: LiveMountAspect[R1, Any, Ctx, Claims, Out]
    )(using append: ContextAppend.Aux[Ctx, Out, Result]
    ): Admitted[R & R1, Result] =
      Admitted(
        name,
        pipeline.andThen(aspect),
        connectedTurnGuards.contramap(append.left),
        layouts.map(LiveLayout.contramapContext(_, append.left)),
        rootLayout.map(LiveRootLayout.contramapContext(_, append.left))
      )

    def withLayout(value: LiveLayout[Any, Ctx]): Admitted[R, Ctx] =
      Admitted(name, pipeline, connectedTurnGuards, layouts :+ value, rootLayout)

    def withRootLayout(value: LiveRootLayout[Any, Ctx]): Admitted[R, Ctx] =
      Admitted(name, pipeline, connectedTurnGuards, layouts, Some(value))

    /** Appends a policy check before each connected application turn.
      *
      * Guards at one boundary run in declaration order; session guards run before route guards. The
      * callback receives the context available at this builder. Succeed with `Unit` to continue, or
      * fail with [[LiveConnectedTurnFailure]] for a controlled halt, redirect, reload, or
      * disconnect. Guards are inherited by nested LiveViews and do not run during mount,
      * disconnected rendering, or framework cleanup.
      */
    def guardConnectedTurns(
      guard: Ctx => zio.IO[LiveConnectedTurnFailure, Unit]
    ): Admitted[R, Ctx] =
      Admitted(
        name,
        pipeline,
        connectedTurnGuards.andThen(LiveConnectedTurnGuard(guard)),
        layouts,
        rootLayout
      )

    def apply[R1](routes: (LiveRouteFragment[R1] { type Input >: Ctx })*): LiveSession[R & R1] =
      val attached = routes.toVector.flatMap { route =>
        route.attachSession(
          pipeline,
          connectedTurnGuards,
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
      pipeline: LiveMountPipeline[R, Any, Any, Ctx],
      connectedTurnGuards: LiveConnectedTurnGuard[Ctx],
      layouts: Vector[LiveLayout[Any, Ctx]],
      rootLayout: Option[LiveRootLayout[Any, Ctx]]
    ): Admitted[R, Ctx] =
      new Admitted(name, pipeline, connectedTurnGuards, layouts, rootLayout)
end LiveSessionBuilder

/** A complete declarative application; transport and runtime interpretation live elsewhere. */
final class LiveApplication[-R] private[scalive] (
  val routes: Vector[LiveRouteFragment[R]],
  val socketPath: PathCodec[Unit],
  val layout: Option[LiveLayout[Any, Any]],
  val rootLayout: LiveRootLayout[Any, Any])

object LiveApplication:
  private[scalive] def apply[R](
    routes: Vector[LiveRouteFragment[R]],
    socketPath: PathCodec[Unit],
    layout: Option[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any]
  ): LiveApplication[R] = new LiveApplication(routes, socketPath, layout, rootLayout)

final class LiveRouter private[scalive] (
  private val socketPath: PathCodec[Unit],
  private val layout: Option[LiveLayout[Any, Any]],
  private val rootLayout: LiveRootLayout[Any, Any]):
  def withSocketPath(value: PathCodec[Unit]): LiveRouter  = LiveRouter(value, layout, rootLayout)
  def withLayout(value: LiveLayout[Any, Any]): LiveRouter =
    LiveRouter(socketPath, Some(value), rootLayout)
  def withRootLayout(value: LiveRootLayout[Any, Any]): LiveRouter =
    LiveRouter(socketPath, layout, value)
  def apply[R](routes: (LiveRouteFragment[R] { type Input = Any })*): LiveApplication[R] =
    LiveApplication(routes.toVector, socketPath, layout, rootLayout)

object LiveRouter:
  private[scalive] def apply(
    socketPath: PathCodec[Unit],
    layout: Option[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any]
  ): LiveRouter = new LiveRouter(socketPath, layout, rootLayout)

object Live:
  val router: LiveRouter = LiveRouter(PathCodec.empty / "live", None, LiveRootLayout.identity)
  def route[A](pathCodec: PathCodec[A]): LiveRouteSeed[A] = LiveRouteSeed(pathCodec)
  def session(name: String): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder(
      name,
      LiveMountPipeline.Identity[Any, Any](),
      LiveConnectedTurnGuard.empty,
      Vector.empty,
      None
    )

val live: LiveRouteSeed[Unit] = LiveRouteSeed(PathCodec.empty)
