package scalive

import scala.annotation.targetName

import zio.Tag
import zio.http.Request
import zio.http.codec.{Combiner, HttpCodec, PathCodec, QueryCodec}
import zio.json.JsonCodec
import zio.schema.Schema

/** A typed mount pipeline whose claim type remains hidden until runtime interpretation. */
sealed private[scalive] trait LiveMountPipeline[R, A, Ctx]:
  def andThen[R1, Claims, Out, Result](
    next: LiveMountAspect[R1, A, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveMountPipeline[R & R1, A, Result]

object LiveMountPipeline:
  final case class Typed[R, A, CurrentClaims, Ctx](
    aspect: LiveMountAspect[R, A, Any, CurrentClaims, Ctx])
      extends LiveMountPipeline[R, A, Ctx]:

    def andThen[R1, Claims, Out, Result](
      next: LiveMountAspect[R1, A, Ctx, Claims, Out]
    )(using append: ContextAppend.Aux[Ctx, Out, Result]
    ): LiveMountPipeline[R & R1, A, Result] =
      given JsonCodec[CurrentClaims] = aspect.claimsCodec
      given JsonCodec[Claims]        = next.claimsCodec
      val claimsCodec                = summon[JsonCodec[(CurrentClaims, Claims)]]
      Typed(aspect.++(next)(using claimsCodec, append))

  def apply[R, A, Claims, Ctx](
    aspect: LiveMountAspect[R, A, Any, Claims, Ctx]
  ): LiveMountPipeline[R, A, Ctx] = Typed(aspect)

/** Selects how a completed route obtains the context passed to its lifecycle factory and layouts.
  */
sealed private[scalive] trait LiveRouteContext[R, A, Ctx]

object LiveRouteContext:
  final case class Direct[A]()                    extends LiveRouteContext[Any, A, Any]
  final case class Environment[R, A](tag: Tag[R]) extends LiveRouteContext[R, A, R]
  final case class Mounted[R, A, Ctx](pipeline: LiveMountPipeline[R, A, Ctx])
      extends LiveRouteContext[R, A, Ctx]

/** The sole existential boundary joining a complete typed route to the heterogeneous catalog. */
sealed private[scalive] trait LiveRouteDefinition[A]:
  type Environment
  type Context
  type Msg
  type Model

object LiveRouteDefinition:
  final case class Ordinary[R, A, Ctx, Message, State](
    pathCodec: PathCodec[A],
    context: LiveRouteContext[R, A, Ctx],
    factory: (A, Request, Ctx) => LiveView[Message, State],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Environment = R
    type Context     = Ctx
    type Msg         = Message
    type Model       = State

  final case class Routed[R, A, Ctx, Message, State, Params](
    pathCodec: PathCodec[A],
    context: LiveRouteContext[R, A, Ctx],
    factory: (A, Request, Ctx) => LiveView.Routed[Message, State, Params],
    paramsCodec: LiveParamsDecoder[A, Params],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]])
      extends LiveRouteDefinition[A]:
    type Environment = R
    type Context     = Ctx
    type Msg         = Message
    type Model       = State

/** A declarative route, hidden behind [[LiveRouteFragment]] during normal application assembly. */
final class LiveRoute[-R, A] private[scalive] (
  private[scalive] val definition: LiveRouteDefinition[A])
    extends LiveRouteFragment[R]

object LiveRoute:
  private[scalive] def apply[R, A](
    definition: LiveRouteDefinition[A] { type Environment = R }
  ): LiveRoute[R, A] =
    new LiveRoute(definition)

/** A completed route or typed group of routes accepted by application assembly. */
sealed trait LiveRouteFragment[-R]:
  private[scalive] def declarations: Vector[LiveRoute[?, ?]] = this match
    case route: LiveRoute[?, ?]  => Vector(route)
    case session: LiveSession[?] => session.routes.flatMap(_.declarations)

/** Common operations for an ordinary (non-routed) LiveView route. */
class LiveRouteBuilder[A] private[scalive] (
  private[scalive] val pathCodec: PathCodec[A],
  private val layouts: Vector[LiveLayout[A, Any]] = Vector.empty,
  private val rootLayout: Option[LiveRootLayout[A, Any]] = None):

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
      rootLayout
    )

  def query[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Query]
  ): LiveEncodableRouteParamsBuilder[A, combiner.Out] =
    query(HttpCodec.query[Query](name))

  def params: LiveEncodableRouteParamsBuilder[A, A] =
    LiveEncodableRouteParamsBuilder(
      pathCodec,
      LiveParamsCodec.path[A],
      layouts,
      rootLayout
    )

  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    LiveEncodableRouteParamsBuilder(pathCodec, codec, layouts, rootLayout)

  def paramsDecodeOnly[Params](
    decoder: LiveParamsDecoder[A, Params]
  ): LiveRouteParamsBuilder[A, Params] =
    LiveRouteParamsBuilder(pathCodec, decoder, layouts, rootLayout)

  def withLayout(layout: LiveLayout[A, Any]): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts :+ layout,
      rootLayout
    )

  def withRootLayout(layout: LiveRootLayout[A, Any]): LiveRouteBuilder[A] =
    LiveRouteBuilder(
      pathCodec,
      layouts,
      Some(layout)
    )

  /** Starts a typed route mount pipeline. The resulting context is supplied to route factories. */
  def withMountAspect[R, Claims, Ctx](
    aspect: LiveMountAspect[R, A, Any, Claims, Ctx]
  ): LiveRouteMountAspectBuilder[R, A, Ctx] =
    val project = (_: Ctx) => ()
    LiveRouteMountAspectBuilder(
      pathCodec,
      LiveMountPipeline(aspect),
      layouts.map(LiveLayout.contramapContext(_, project)),
      rootLayout.map(LiveRootLayout.contramapContext(_, project))
    )

  def apply[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[Any, A] =
    ordinary((_, _) => view)

  def apply[Msg, Model](factory: Request => LiveView[Msg, Model]): LiveRoute[Any, A] =
    ordinary((_, request) => factory(request))

  infix def ->[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[Any, A] = apply(view)

  def from[R: Tag, Msg, Model](
    factory: (A, Request, R) => LiveView[Msg, Model]
  ): LiveRoute[R, A] =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Environment(summon[Tag[R]]),
        factory,
        layouts.map(LiveLayout.contramapContext(_, (_: R) => ())),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: R) => ()))
      )
    )

  private def ordinary[Msg, Model](
    factory: (A, Request) => LiveView[Msg, Model]
  ): LiveRoute[Any, A] =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Direct(),
        (path, request, _) => factory(path, request),
        layouts,
        rootLayout
      )
    )
end LiveRouteBuilder

object LiveRouteBuilder:
  private[scalive] def apply[A](
    pathCodec: PathCodec[A],
    layouts: Vector[LiveLayout[A, Any]] = Vector.empty,
    rootLayout: Option[LiveRootLayout[A, Any]] = None
  ): LiveRouteBuilder[A] = new LiveRouteBuilder(pathCodec, layouts, rootLayout)

/** Route construction after a mount aspect has produced typed lifecycle context. */
final class LiveRouteMountAspectBuilder[R, A, Ctx] private[scalive] (
  private val pathCodec: PathCodec[A],
  private val pipeline: LiveMountPipeline[R, A, Ctx],
  private val layouts: Vector[LiveLayout[A, Ctx]],
  private val rootLayout: Option[LiveRootLayout[A, Ctx]]):

  def withMountAspect[R1, Claims, Out, Result](
    aspect: LiveMountAspect[R1, A, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveRouteMountAspectBuilder[R & R1, A, Result] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline.andThen(aspect),
      layouts.map(LiveLayout.contramapContext(_, append.left)),
      rootLayout.map(LiveRootLayout.contramapContext(_, append.left))
    )

  def withLayout(layout: LiveLayout[A, Ctx]): LiveRouteMountAspectBuilder[R, A, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts :+ layout,
      rootLayout
    )

  def withRootLayout(
    layout: LiveRootLayout[A, Ctx]
  ): LiveRouteMountAspectBuilder[R, A, Ctx] =
    LiveRouteMountAspectBuilder(
      pathCodec,
      pipeline,
      layouts,
      Some(layout)
    )

  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveRouteMountAspectParamsBuilder[R, A, Ctx, Params] =
    LiveRouteMountAspectParamsBuilder(pathCodec, codec, pipeline, layouts, rootLayout)

  def params: LiveRouteMountAspectParamsBuilder[R, A, Ctx, A] =
    params(LiveParamsCodec.path[A])

  def query[Query](
    codec: QueryCodec[Query]
  )(using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Ctx, combiner.Out] =
    params(LiveParamsCodec.fromQuery(codec))

  def query[Query: Schema](
    name: String
  )(using
    combiner: Combiner[A, Query]
  ): LiveRouteMountAspectParamsBuilder[R, A, Ctx, combiner.Out] =
    query(HttpCodec.query[Query](name))

  def apply[Msg, Model](view: => LiveView[Msg, Model]): LiveRoute[R, A] =
    from((_, _, _) => view)

  def apply[Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A] = from(factory)

  def from[Msg, Model](
    factory: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A] =
    LiveRoute(
      LiveRouteDefinition.Ordinary(
        pathCodec,
        LiveRouteContext.Mounted(pipeline),
        factory,
        layouts,
        rootLayout
      )
    )
end LiveRouteMountAspectBuilder

object LiveRouteMountAspectBuilder:
  private[scalive] def apply[R, A, Ctx](
    pathCodec: PathCodec[A],
    pipeline: LiveMountPipeline[R, A, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]]
  ): LiveRouteMountAspectBuilder[R, A, Ctx] =
    new LiveRouteMountAspectBuilder(pathCodec, pipeline, layouts, rootLayout)

/** Parameterized route construction after mount aspects have produced context. */
final class LiveRouteMountAspectParamsBuilder[R, A, Ctx, Params] private[scalive] (
  private val pathCodec: PathCodec[A],
  private val paramsCodec: LiveParamsCodec[A, Params],
  private val pipeline: LiveMountPipeline[R, A, Ctx],
  private val layouts: Vector[LiveLayout[A, Ctx]],
  private val rootLayout: Option[LiveRootLayout[A, Ctx]]):

  def location(params: Params): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(params: Params): Either[LiveLocation.EncodeError, LiveLocation] =
    paramsCodec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))

  def apply[Msg, Model](view: => LiveView.Routed[Msg, Model, Params]): LiveRoute[R, A] =
    from((_, _, _) => view)

  def apply[Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] = from(factory)

  def from[Msg, Model](
    factory: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Mounted(pipeline),
        factory,
        paramsCodec,
        layouts,
        rootLayout
      )
    )
end LiveRouteMountAspectParamsBuilder

object LiveRouteMountAspectParamsBuilder:
  private[scalive] def apply[R, A, Ctx, Params](
    pathCodec: PathCodec[A],
    paramsCodec: LiveParamsCodec[A, Params],
    pipeline: LiveMountPipeline[R, A, Ctx],
    layouts: Vector[LiveLayout[A, Ctx]],
    rootLayout: Option[LiveRootLayout[A, Ctx]]
  ): LiveRouteMountAspectParamsBuilder[R, A, Ctx, Params] =
    new LiveRouteMountAspectParamsBuilder(
      pathCodec,
      paramsCodec,
      pipeline,
      layouts,
      rootLayout
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
  private val layouts: Vector[LiveLayout[A, Any]],
  private val rootLayout: Option[LiveRootLayout[A, Any]]):

  def apply[Msg, Model](view: => LiveView.Routed[Msg, Model, Params]): LiveRoute[Any, A] =
    routed((_, _) => view)

  def apply[Msg, Model](
    factory: Request => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] = routed((_, request) => factory(request))

  infix def ->[Msg, Model](view: => LiveView.Routed[Msg, Model, Params]): LiveRoute[Any, A] =
    apply(view)

  def from[R: Tag, Msg, Model](
    factory: (A, Request, R) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A] =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Environment(summon[Tag[R]]),
        factory,
        paramsCodec,
        layouts.map(LiveLayout.contramapContext(_, (_: R) => ())),
        rootLayout.map(LiveRootLayout.contramapContext(_, (_: R) => ()))
      )
    )

  private def routed[Msg, Model](
    factory: (A, Request) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[Any, A] =
    LiveRoute(
      LiveRouteDefinition.Routed(
        pathCodec,
        LiveRouteContext.Direct(),
        (path, request, _) => factory(path, request),
        paramsCodec,
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
    rootLayout: Option[LiveRootLayout[A, Any]]
  ): LiveRouteParamsBuilder[A, Params] =
    new LiveRouteParamsBuilder(pathCodec, paramsCodec, layouts, rootLayout)

final class LiveEncodableRouteParamsBuilder[A, Params] private[scalive] (
  pathCodec: PathCodec[A],
  val codec: LiveParamsCodec[A, Params],
  layouts: Vector[LiveLayout[A, Any]],
  rootLayout: Option[LiveRootLayout[A, Any]])
    extends LiveRouteParamsBuilder[A, Params](
      pathCodec,
      codec,
      layouts,
      rootLayout
    ):
  def location(params: Params): LiveLocation =
    locationEither(params).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(params: Params): Either[LiveLocation.EncodeError, LiveLocation] =
    codec.encode(params).flatMap(LiveLocation.encode(pathCodec, _))

object LiveEncodableRouteParamsBuilder:
  private[scalive] def apply[A, Params](
    pathCodec: PathCodec[A],
    codec: LiveParamsCodec[A, Params],
    layouts: Vector[LiveLayout[A, Any]],
    rootLayout: Option[LiveRootLayout[A, Any]]
  ): LiveEncodableRouteParamsBuilder[A, Params] =
    new LiveEncodableRouteParamsBuilder(pathCodec, codec, layouts, rootLayout)

/** A named, environment-typed group of declarative routes. */
final class LiveSession[-R] private[scalive] (
  val name: String,
  private[scalive] val routes: Vector[LiveRouteFragment[R]],
  val layout: Option[LiveLayout[Any, Any]],
  val rootLayout: Option[LiveRootLayout[Any, Any]])
    extends LiveRouteFragment[R]

object LiveSession:
  private[scalive] def apply[R](
    name: String,
    routes: Vector[LiveRouteFragment[R]],
    layout: Option[LiveLayout[Any, Any]],
    rootLayout: Option[LiveRootLayout[Any, Any]]
  ): LiveSession[R] = new LiveSession(name, routes, layout, rootLayout)

final class LiveSessionBuilder private[scalive] (
  val name: String,
  private val layout: Option[LiveLayout[Any, Any]] = None,
  private val rootLayout: Option[LiveRootLayout[Any, Any]] = None):
  def withLayout(value: LiveLayout[Any, Any]): LiveSessionBuilder =
    LiveSessionBuilder(name, Some(value), rootLayout)
  def withRootLayout(value: LiveRootLayout[Any, Any]): LiveSessionBuilder =
    LiveSessionBuilder(name, layout, Some(value))
  def apply[R](routes: LiveRouteFragment[R]*): LiveSession[R] =
    LiveSession(name, routes.toVector, layout, rootLayout)

object LiveSessionBuilder:
  private[scalive] def apply(
    name: String,
    layout: Option[LiveLayout[Any, Any]] = None,
    rootLayout: Option[LiveRootLayout[Any, Any]] = None
  ): LiveSessionBuilder = new LiveSessionBuilder(name, layout, rootLayout)

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
  def apply[R](routes: LiveRouteFragment[R]*): LiveApplication[R] =
    LiveApplication(routes.toVector, socketPath, layout, rootLayout)

object LiveRouter:
  private[scalive] def apply(
    socketPath: PathCodec[Unit],
    layout: Option[LiveLayout[Any, Any]],
    rootLayout: LiveRootLayout[Any, Any]
  ): LiveRouter = new LiveRouter(socketPath, layout, rootLayout)

object Live:
  val router: LiveRouter = LiveRouter(PathCodec.empty / "live", None, LiveRootLayout.identity)
  def session(name: String): LiveSessionBuilder = LiveSessionBuilder(name)

val live: LiveRouteSeed[Unit] = LiveRouteSeed(PathCodec.empty)
