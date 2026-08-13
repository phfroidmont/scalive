package scalive

import scala.annotation.targetName

import zio.*
import zio.http.Request
import zio.http.Routes
import zio.http.codec.Combiner
import zio.http.codec.PathCodec
import zio.http.codec.QueryCodec
import zio.schema.Schema

/** Type-level evidence describing whether a parameterized route can construct locations.
  *
  * Applications normally let this type infer on [[LiveRouteParamsBuilder]]. It prevents a route
  * whose parameters can only be decoded from exposing an apparently safe inverse operation.
  */
sealed trait LiveRouteParamsCapability

/** Capability values used by the parameterized route builder. */
object LiveRouteParamsCapability:
  /** Marks parameters with both decoding and encoding, enabling typed location methods. */
  sealed trait Encodable extends LiveRouteParamsCapability

  /** Marks parameters that can be decoded for a routed view but cannot construct locations. */
  sealed trait DecodeOnly extends LiveRouteParamsCapability

/** Starts a GET Live route declaration from a typed path codec.
  *
  * A seed can be extended with path segments, parameter decoding, mount aspects, and layouts before
  * it is completed with a LiveView. Its location methods encode path values only. Calling a seed or
  * using `->` without a parameter builder creates an ordinary [[LiveView]] route; use `params`,
  * `query`, or `paramsDecodeOnly` to create a route for [[LiveView.Routed]].
  *
  * The LiveView and layer overloads take by-name arguments. They are evaluated independently for
  * the disconnected HTTP mount and connected socket mount, providing a fresh lifecycle instance.
  *
  * @tparam A
  *   the combined value decoded and encoded by the path
  */
class LiveRouteSeed[A] private[scalive] (pathCodec: PathCodec[A]):
  /** Appends a typed path codec.
    *
    * @param that
    *   the path segment or codec to append
    * @param combiner
    *   combines this path's value with the appended value
    * @return
    *   a seed for the combined path value
    */
  def /[B](that: PathCodec[B])(using combiner: Combiner[A, B]): LiveRouteSeed[combiner.Out] =
    LiveRouteSeed(pathCodec / that)

  /** Constructs a path-only location or throws for an invalid path value.
    *
    * @param value
    *   the value encoded by this path
    * @return
    *   the typed location
    * @throws LiveLocation.EncodingException
    *   if the path codec rejects `value`
    */
  def location(value: A): LiveLocation =
    locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

  /** Constructs a checked path-only location.
    *
    * @param value
    *   the value encoded by this path
    * @return
    *   the typed location or a path encoding error
    */
  def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
    LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

  /** Constructs a path-only location without an argument when the path value is `Unit`.
    *
    * @param ev
    *   evidence that this path's value type is `Unit`
    * @return
    *   the typed location
    * @throws LiveLocation.EncodingException
    *   if the path cannot be encoded
    */
  @targetName("unitLocation")
  def location(using ev: A =:= Unit): LiveLocation =
    location(ev.flip(()))

  /** Constructs a checked path-only location when the path value is `Unit`.
    *
    * @param ev
    *   evidence that this path's value type is `Unit`
    * @return
    *   the typed location or a path encoding error
    */
  @targetName("unitLocationEither")
  def locationEither(using ev: A =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(ev.flip(()))

  private def base[Ctx]: LiveRouteBuilder[Any, A, Ctx, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      LiveMountPipeline.identity[A, Ctx],
      Nil,
      None,
      hasRouteMountAspect = false
    )

  /** Uses the path value itself as encodable routed parameters.
    *
    * @return
    *   a parameter builder that requires a [[LiveView.Routed]] with parameter type `A`
    */
  def params: LiveRouteParamsBuilder[Any, A, Any, Any, A, LiveRouteParamsCapability.Encodable] =
    base[Any].params

  /** Uses a bidirectional codec for application-facing routed parameters.
    *
    * @param codec
    *   decodes the matched URL and encodes typed locations
    * @return
    *   an encodable parameter builder requiring a compatible [[LiveView.Routed]]
    */
  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveRouteParamsBuilder[
    Any,
    A,
    Any,
    Any,
    Params,
    LiveRouteParamsCapability.Encodable
  ] =
    base[Any].params(codec)

  /** Uses one-way routed parameters.
    *
    * The returned builder can mount a compatible [[LiveView.Routed]], but intentionally has no
    * usable location methods because no inverse mapping to path/query values exists.
    *
    * @param decoder
    *   decodes the matched URL into routed parameters
    * @return
    *   a decode-only parameter builder
    */
  def paramsDecodeOnly[Params](
    decoder: LiveParamsDecoder[A, Params]
  ): LiveRouteParamsBuilder[
    Any,
    A,
    Any,
    Any,
    Params,
    LiveRouteParamsCapability.DecodeOnly
  ] =
    base[Any].paramsDecodeOnly(decoder)

  /** Combines the path value with a ZIO HTTP query codec.
    *
    * @param codec
    *   the query codec to decode and encode
    * @param combiner
    *   combines path and query values and separates them for location construction
    * @return
    *   an encodable parameter builder for the combined value
    */
  def query[QueryParams](
    codec: QueryCodec[QueryParams]
  )(using combiner: Combiner[A, QueryParams]
  ): LiveRouteParamsBuilder[
    Any,
    A,
    Any,
    Any,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    base[Any].query(codec)

  /** Adds one required named query parameter derived from its schema.
    *
    * @param name
    *   the query parameter name
    * @param schema
    *   the parameter schema used by ZIO HTTP
    * @param combiner
    *   combines path and query values and separates them for location construction
    * @return
    *   an encodable parameter builder for the combined value
    */
  def query[QueryParam](
    name: String
  )(using
    schema: Schema[QueryParam],
    combiner: Combiner[A, QueryParam]
  ): LiveRouteParamsBuilder[
    Any,
    A,
    Any,
    Any,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    base[Any].query[QueryParam](name)

  /** Adds one optional named query parameter derived from its schema.
    *
    * @param name
    *   the query parameter name
    * @param schema
    *   the parameter schema used by ZIO HTTP
    * @param combiner
    *   combines the path value with `Option[QueryParam]`
    * @return
    *   an encodable parameter builder for the combined value
    */
  def queryOptional[QueryParam](
    name: String
  )(using
    schema: Schema[QueryParam],
    combiner: Combiner[A, Option[QueryParam]]
  ): LiveRouteParamsBuilder[
    Any,
    A,
    Any,
    Any,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    base[Any].queryOptional[QueryParam](name)

  /** Derives structured query parameters from a schema and combines them with the path value.
    *
    * @param schema
    *   the structured query parameter schema
    * @param combiner
    *   combines path and query values and separates them for location construction
    * @return
    *   an encodable parameter builder for the combined value
    */
  def query[QueryParams](
    using
    schema: Schema[QueryParams],
    combiner: Combiner[A, QueryParams]
  ): LiveRouteParamsBuilder[
    Any,
    A,
    Any,
    Any,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    base[Any].query[QueryParams]

  /** Starts the route mount pipeline with an aspect.
    *
    * The aspect runs before LiveView construction in both disconnected and connected lifecycles.
    * Its output becomes route context; a non-`Any` input records context that a surrounding session
    * must provide before the router can assemble the route.
    *
    * @param aspect
    *   the disconnected/connected mount step to append
    * @param append
    *   determines the resulting context shape
    * @return
    *   a builder carrying the aspect environment, context requirement, and output
    */
  def withMountAspect[R, In, Claims, Out, Result](
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

  /** Attaches a route Live layout that requires context from a surrounding session.
    *
    * @param layout
    *   the layout to apply inside router and session layouts
    * @return
    *   a builder recording `Ctx` as its required and visible context
    */
  def withLayout[Ctx](layout: LiveLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
    base[Ctx].withLayout(layout)

  /** Selects a route root layout that requires context from a surrounding session.
    *
    * A route root layout replaces, rather than wraps, session and router root layouts.
    *
    * @param layout
    *   the route root layout
    * @return
    *   a builder recording `Ctx` as its required and visible context
    */
  def withRootLayout[Ctx](layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
    base[Ctx].withRootLayout(layout)

  /** Completes the route with a by-name ordinary LiveView.
    *
    * @param view
    *   a fresh LiveView expression evaluated for each disconnected or connected mount
    * @return
    *   the completed route
    */
  def apply[Msg: LiveMessageTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[Any, A, Any, Any, Msg, Model] =
    base[Any].apply(view)

  /** Infix alias for [[apply]] with a by-name ordinary LiveView.
    *
    * @param view
    *   a fresh LiveView expression evaluated for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowView")
  infix def ->[Msg: LiveMessageTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[Any, A, Any, Any, Msg, Model] =
    apply(view)

  /** Completes the route with a by-name LiveView layer.
    *
    * The layer is built in the mount scope for each disconnected or connected lifecycle, and its
    * environment is added to the completed route's environment.
    *
    * @param layer
    *   the fresh layer expression that constructs the LiveView
    * @return
    *   the completed route
    */
  @targetName("applyLayer")
  def apply[R, Msg: LiveMessageTag, Model, View <: LiveView[Msg, Model]: Tag](
    layer: => ZLayer[R, Nothing, View]
  ): LiveRoute[R, A, Any, Any, Msg, Model] =
    base[Any].apply(layer)

  /** Infix alias for [[apply]] with a by-name LiveView layer.
    *
    * @param layer
    *   the fresh layer expression that constructs the LiveView
    * @return
    *   the completed route
    */
  @targetName("arrowLayer")
  infix def ->[R, Msg: LiveMessageTag, Model, View <: LiveView[Msg, Model]: Tag](
    layer: => ZLayer[R, Nothing, View]
  ): LiveRoute[R, A, Any, Any, Msg, Model] =
    apply(layer)

  /** Completes the route with a function of path values, request, and session-provided context.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route requiring `Ctx` from a surrounding session
    */
  @targetName("applyFull")
  def apply[Ctx, Msg: LiveMessageTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Ctx, Ctx, Msg, Model] =
    base[Ctx].apply(builder)

  /** Infix alias for the path/request/context route factory.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route requiring `Ctx` from a surrounding session
    */
  @targetName("arrowFull")
  infix def ->[Ctx, Msg: LiveMessageTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Ctx, Ctx, Msg, Model] =
    apply(builder)

  /** Completes the route with a function of session-provided context.
    *
    * @param builder
    *   constructs a LiveView from context for each lifecycle
    * @return
    *   the completed route requiring `Ctx` from a surrounding session
    */
  def context[Ctx, Msg: LiveMessageTag, Model](
    builder: Ctx => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Ctx, Ctx, Msg, Model] =
    base[Ctx].context(builder)

  /** Completes the route with a function of path values and request.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("applyRequestParams")
  def apply[Msg: LiveMessageTag, Model](
    builder: (A, Request) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    base[Any].apply((params, request, _) => builder(params, request))

  /** Infix alias for the path/request route factory.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowRequestParams")
  infix def ->[Msg: LiveMessageTag, Model](
    builder: (A, Request) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    apply(builder)

  /** Completes a `Unit` path route with a function of its request.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("applyRequest")
  def apply[Msg: LiveMessageTag, Model](
    builder: Request => LiveView[Msg, Model]
  )(using A =:= Unit
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    base[Any].apply((_, request, _) => builder(request))

  /** Infix alias for the request-only route factory.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowRequest")
  infix def ->[Msg: LiveMessageTag, Model](
    builder: Request => LiveView[Msg, Model]
  )(using A =:= Unit
  ): LiveRoute[Any, A, Any, Any, Msg, Model] =
    apply(builder)

  /** Completes the route with two session context components as separate arguments.
    *
    * @param builder
    *   constructs a LiveView from path values, request, and both context components
    * @return
    *   the completed route requiring `(C1, C2)` from a surrounding session
    */
  @targetName("applyTuple2")
  def apply[C1, C2, Msg: LiveMessageTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, (C1, C2), (C1, C2), Msg, Model] =
    base[(C1, C2)].apply(builder)

  /** Infix alias for the two-context-component route factory.
    *
    * @param builder
    *   constructs a LiveView from path values, request, and both context components
    * @return
    *   the completed route requiring `(C1, C2)` from a surrounding session
    */
  @targetName("arrowTuple2")
  infix def ->[C1, C2, Msg: LiveMessageTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  ): LiveRoute[Any, A, (C1, C2), (C1, C2), Msg, Model] =
    apply(builder)

end LiveRouteSeed

/** Configures an ordinary Live route after mount aspects or layouts have been attached.
  *
  * `Need` is context that a surrounding [[LiveSessionBuilder]] must provide; `Ctx` is the context
  * visible to subsequent route aspects, layouts, and LiveView factories. Adding an aspect appends
  * its output to `Ctx` while retaining the context projection used by previously attached layouts.
  *
  * The `apply` and `->` overload families complete the route with an ordinary [[LiveView]]. By-name
  * LiveView and layer arguments are evaluated once per disconnected or connected mount. Function
  * overloads likewise construct a view for each lifecycle and expose selected route inputs.
  *
  * @tparam R
  *   the environment required by route aspects and the LiveView layer
  * @tparam A
  *   the route path-value type
  * @tparam Need
  *   context required from a surrounding session
  * @tparam Ctx
  *   the route's current mount context
  */
final class LiveRouteBuilder[R, A, -Need, Ctx] private[scalive] (
  pathCodec: PathCodec[A],
  mountPipeline: LiveMountPipeline[R, A, Need, Ctx],
  liveLayouts: List[LiveLayoutLayer[A, Ctx, ?]],
  rootLayout: Option[LiveRootLayoutLayer[A, Ctx, ?]],
  hasRouteMountAspect: Boolean):

  /** Constructs a path-only location or throws for an invalid path value.
    *
    * @param value
    *   the value encoded by this route's path
    * @return
    *   the typed location
    * @throws LiveLocation.EncodingException
    *   if the path codec rejects `value`
    */
  def location(value: A): LiveLocation =
    locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

  /** Constructs a checked path-only location.
    *
    * @param value
    *   the value encoded by this route's path
    * @return
    *   the typed location or a path encoding error
    */
  def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
    LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

  /** Constructs a path-only location without an argument when the path value is `Unit`.
    *
    * @param ev
    *   evidence that this route's path value type is `Unit`
    * @return
    *   the typed location
    * @throws LiveLocation.EncodingException
    *   if the path cannot be encoded
    */
  @targetName("unitLocation")
  def location(using ev: A =:= Unit): LiveLocation =
    location(ev.flip(()))

  /** Constructs a checked path-only location when the path value is `Unit`.
    *
    * @param ev
    *   evidence that this route's path value type is `Unit`
    * @return
    *   the typed location or a path encoding error
    */
  @targetName("unitLocationEither")
  def locationEither(using ev: A =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(ev.flip(()))

  /** Appends a mount aspect to this route.
    *
    * Aspects run in declaration order before LiveView construction during both mount phases. The
    * new output is appended to `Ctx`; layouts already attached retain a projection to the context
    * that existed when they were declared.
    *
    * @param aspect
    *   the mount step whose input is the current context
    * @param append
    *   determines the resulting context shape
    * @return
    *   a builder with combined environment and extended context
    */
  def withMountAspect[R1, Claims, Out, Result](
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

  /** Appends a route Live layout using the current mount context.
    *
    * Route layouts are inside router and session layouts. Multiple route layouts wrap in
    * declaration order, with the first outermost.
    *
    * @param layout
    *   the layout to append
    * @return
    *   this route configuration with the additional layout
    */
  def withLayout(layout: LiveLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[A, Ctx, Ctx](layout, identity),
      rootLayout,
      hasRouteMountAspect
    )

  /** Selects a route root layout using the current mount context.
    *
    * The most recently selected route root replaces any previous route root and overrides session
    * and router roots.
    *
    * @param layout
    *   the root layout to select
    * @return
    *   this route configuration with the selected root layout
    */
  def withRootLayout(layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[A, Ctx, Ctx](layout, identity)),
      hasRouteMountAspect
    )

  /** Uses the path value itself as encodable routed parameters.
    *
    * @return
    *   a parameter builder requiring a [[LiveView.Routed]] with parameter type `A`
    */
  def params: LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    A,
    LiveRouteParamsCapability.Encodable
  ] =
    params(LiveParamsCodec.path[A])

  /** Uses a bidirectional codec for application-facing routed parameters.
    *
    * @param codec
    *   decodes the current URL and encodes typed locations
    * @return
    *   an encodable parameter builder preserving this route configuration
    */
  def params[Params](
    codec: LiveParamsCodec[A, Params]
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    Params,
    LiveRouteParamsCapability.Encodable
  ] =
    LiveRouteParamsBuilder(
      pathCodec,
      codec,
      Some(codec.encode),
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect
    )

  /** Uses one-way routed parameters.
    *
    * The returned builder requires a compatible [[LiveView.Routed]] but intentionally cannot
    * construct locations.
    *
    * @param decoder
    *   decodes the current URL into routed parameters
    * @return
    *   a decode-only parameter builder preserving this route configuration
    */
  def paramsDecodeOnly[Params](
    decoder: LiveParamsDecoder[A, Params]
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    Params,
    LiveRouteParamsCapability.DecodeOnly
  ] =
    LiveRouteParamsBuilder(
      pathCodec,
      decoder,
      None,
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect
    )

  /** Combines the path value with a ZIO HTTP query codec.
    *
    * @param codec
    *   the query codec to decode and encode
    * @param combiner
    *   combines path/query values and separates them for location construction
    * @return
    *   an encodable parameter builder for the combined value
    */
  def query[QueryParams](
    codec: QueryCodec[QueryParams]
  )(using combiner: Combiner[A, QueryParams]
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    params(LiveParamsCodec.fromQuery[A, QueryParams](codec))

  /** Adds one required named query parameter derived from its schema.
    *
    * @param name
    *   the query parameter name
    * @param schema
    *   the parameter schema used by ZIO HTTP
    * @param combiner
    *   combines path/query values and separates them for location construction
    * @return
    *   an encodable parameter builder for the combined value
    */
  def query[QueryParam](
    name: String
  )(using
    schema: Schema[QueryParam],
    combiner: Combiner[A, QueryParam]
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    query(zio.http.codec.HttpCodec.query[QueryParam](name))

  /** Adds one optional named query parameter derived from its schema.
    *
    * @param name
    *   the query parameter name
    * @param schema
    *   the parameter schema used by ZIO HTTP
    * @param combiner
    *   combines the path value with `Option[QueryParam]`
    * @return
    *   an encodable parameter builder for the combined value
    */
  def queryOptional[QueryParam](
    name: String
  )(using
    schema: Schema[QueryParam],
    combiner: Combiner[A, Option[QueryParam]]
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    query(zio.http.codec.HttpCodec.query[QueryParam](name).optional)

  /** Derives structured query parameters from a schema and combines them with the path value.
    *
    * @param schema
    *   the structured query parameter schema
    * @param combiner
    *   combines path/query values and separates them for location construction
    * @return
    *   an encodable parameter builder for the combined value
    */
  def query[QueryParams](
    using
    schema: Schema[QueryParams],
    combiner: Combiner[A, QueryParams]
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    combiner.Out,
    LiveRouteParamsCapability.Encodable
  ] =
    query(zio.http.codec.HttpCodec.query[QueryParams])

  /** Completes the route with a by-name ordinary LiveView.
    *
    * @param view
    *   a fresh LiveView expression evaluated for each lifecycle
    * @return
    *   the completed route
    */
  def apply[Msg: LiveMessageTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, _, _) => view)

  /** Infix alias for [[apply]] with a by-name ordinary LiveView.
    *
    * @param view
    *   a fresh LiveView expression evaluated for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowView")
  infix def ->[Msg: LiveMessageTag, Model](view: => LiveView[Msg, Model])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(view)

  /** Completes the route with a function of path values, request, and current route context.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("applyFull")
  def apply[Msg: LiveMessageTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    new LiveRoute(
      pathCodec,
      (params, request, context) => ZIO.succeed(builder(params, request, context)),
      LiveRouteParamsRuntime.none[A, Msg, Model],
      summon[LiveMessageTag[Msg]].classTag,
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect = hasRouteMountAspect
    )

  /** Completes the route with a by-name LiveView layer.
    *
    * The layer is built in the mount scope for each lifecycle and adds its dependencies to the
    * route environment.
    *
    * @param layer
    *   the fresh layer expression that constructs the LiveView
    * @return
    *   the completed route
    */
  @targetName("applyLayer")
  def apply[R1, Msg: LiveMessageTag, Model, View <: LiveView[Msg, Model]: Tag](
    layer: => ZLayer[R1, Nothing, View]
  ): LiveRoute[R & R1, A, Need, Ctx, Msg, Model] =
    new LiveRoute[R & R1, A, Need, Ctx, Msg, Model](
      pathCodec,
      (_, _, _) => layer.build.map(_.get[View]),
      LiveRouteParamsRuntime.none[A, Msg, Model],
      summon[LiveMessageTag[Msg]].classTag,
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect = hasRouteMountAspect
    )

  /** Infix alias for [[apply]] with a by-name LiveView layer.
    *
    * @param layer
    *   the fresh layer expression that constructs the LiveView
    * @return
    *   the completed route
    */
  @targetName("arrowLayer")
  infix def ->[R1, Msg: LiveMessageTag, Model, View <: LiveView[Msg, Model]: Tag](
    layer: => ZLayer[R1, Nothing, View]
  ): LiveRoute[R & R1, A, Need, Ctx, Msg, Model] =
    apply(layer)

  /** Infix alias for the path/request/context route factory.
    *
    * @param builder
    *   constructs a LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowFull")
  infix def ->[Msg: LiveMessageTag, Model](
    builder: (A, Request, Ctx) => LiveView[Msg, Model]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  /** Completes the route with a function of its current mount context.
    *
    * @param builder
    *   constructs a LiveView from context for each lifecycle
    * @return
    *   the completed route
    */
  def context[Msg: LiveMessageTag, Model](
    builder: Ctx => LiveView[Msg, Model]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, _, context) => builder(context))

  /** Completes a tuple-context route with each component as a separate argument.
    *
    * @param builder
    *   constructs a LiveView from path values, request, and both context components
    * @param ev
    *   evidence that the current context can be viewed as `(C1, C2)`
    * @return
    *   the completed route
    */
  @targetName("applyTuple2")
  def apply[C1, C2, Msg: LiveMessageTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((params, request, context) =>
      val tuple = ev(context)
      builder(params, request, tuple._1, tuple._2)
    )

  /** Infix alias for the two-context-component route factory.
    *
    * @param builder
    *   constructs a LiveView from path values, request, and both context components
    * @return
    *   the completed route
    */
  @targetName("arrowTuple2")
  infix def ->[C1, C2, Msg: LiveMessageTag, Model](
    builder: (A, Request, C1, C2) => LiveView[Msg, Model]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

end LiveRouteBuilder

/** Configures a route whose URL is decoded into typed [[LiveView.Routed]] parameters.
  *
  * Unlike path value `A`, `Params` is delivered to the routed lifecycle's `mount` and
  * `handleParams`; LiveView factory overloads still receive `A` so they can choose an instance from
  * the matched path before parameter lifecycle handling. Attaching an ordinary [[LiveView]] is not
  * supported at this stage.
  *
  * `Capability` makes location construction available only when the parameter mapping has an
  * encoder. [[mapParamsDecodeOnly]] deliberately drops that capability, because a one-way mapping
  * cannot safely reconstruct a URL.
  *
  * The `apply` and `->` overload families construct a routed view for each disconnected or
  * connected lifecycle. By-name view and layer arguments therefore produce fresh instances.
  *
  * @tparam R
  *   the environment required by route aspects and the LiveView layer
  * @tparam A
  *   the route path-value type
  * @tparam Need
  *   context required from a surrounding session
  * @tparam Ctx
  *   the route's current mount context
  * @tparam Params
  *   parameters decoded for the routed lifecycle
  * @tparam Capability
  *   whether `Params` can also be encoded into a location
  */
final class LiveRouteParamsBuilder[
  R,
  A,
  -Need,
  Ctx,
  Params,
  Capability <: LiveRouteParamsCapability
] private[scalive] (
  pathCodec: PathCodec[A],
  paramsDecoder: LiveParamsDecoder[A, Params],
  paramsEncoder: Option[
    Params => Either[LiveLocation.EncodeError, LiveParamsCodec.Encoded[A]]
  ],
  mountPipeline: LiveMountPipeline[R, A, Need, Ctx],
  liveLayouts: List[LiveLayoutLayer[A, Ctx, ?]],
  rootLayout: Option[LiveRootLayoutLayer[A, Ctx, ?]],
  hasRouteMountAspect: Boolean):

  /** Bidirectionally maps routed parameters while retaining location construction.
    *
    * This operation is available only for an encodable builder. The two functions should form an
    * isomorphism for values used by the application.
    *
    * @param decodeParams
    *   maps decoded parameters to the application-facing type; thrown exceptions are defects
    * @param encodeParams
    *   maps the application-facing type back before encoding; thrown exceptions escape the checked
    *   location API
    * @return
    *   an encodable builder for `Params2`
    */
  def mapParams[Params2](
    decodeParams: Params => Params2
  )(
    encodeParams: Params2 => Params
  )(using Capability =:= LiveRouteParamsCapability.Encodable
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    Params2,
    LiveRouteParamsCapability.Encodable
  ] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsDecoder.mapDecodeOnly(decodeParams),
      paramsEncoder.map(baseEncode => params => baseEncode(encodeParams(params))),
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect
    )

  /** Maps decoded parameters without an inverse.
    *
    * This can be called from either capability, but the result is always decode-only and therefore
    * cannot construct a [[LiveLocation]]. Use it for irreversible validation or normalization.
    *
    * @param decodeParams
    *   maps decoded parameters to the final type; thrown exceptions are defects
    * @return
    *   a decode-only builder for `Params2`
    */
  def mapParamsDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    Params2,
    LiveRouteParamsCapability.DecodeOnly
  ] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsDecoder.mapDecodeOnly(decodeParams),
      None,
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect
    )

  /** Constructs a location from final routed parameters or throws on encoding failure.
    *
    * Compile-time evidence makes this method callable only while the builder remains encodable.
    *
    * @param params
    *   the final routed parameters to encode
    * @return
    *   the typed location
    * @throws LiveLocation.EncodingException
    *   if parameter, path, or query encoding fails
    */
  def location(params: Params)(using Capability =:= LiveRouteParamsCapability.Encodable)
    : LiveLocation =
    locationEither(params).fold(
      error => throw new LiveLocation.EncodingException(error),
      identity
    )

  /** Constructs a checked location from final routed parameters.
    *
    * @param params
    *   the final routed parameters to encode
    * @return
    *   the typed location or a path/query encoding error
    */
  def locationEither(
    params: Params
  )(using Capability =:= LiveRouteParamsCapability.Encodable
  ): Either[LiveLocation.EncodeError, LiveLocation] =
    paramsEncoder.get.apply(params).flatMap(LiveLocation.encode(pathCodec, _))

  /** Constructs a location without an argument when final parameters are `Unit`.
    *
    * @return
    *   the typed location
    * @throws LiveLocation.EncodingException
    *   if parameter, path, or query encoding fails
    */
  @targetName("unitParamsLocation")
  def location(using Params =:= Unit, Capability =:= LiveRouteParamsCapability.Encodable)
    : LiveLocation =
    location(summon[Params =:= Unit].flip(()))

  /** Constructs a checked location when final parameters are `Unit`.
    *
    * @return
    *   the typed location or a path/query encoding error
    */
  @targetName("unitParamsLocationEither")
  def locationEither(using Params =:= Unit, Capability =:= LiveRouteParamsCapability.Encodable)
    : Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(summon[Params =:= Unit].flip(()))

  /** Appends a mount aspect while preserving parameter capability.
    *
    * Aspects run in declaration order before routed parameter mount. The new output is appended to
    * `Ctx`; layouts already attached retain their earlier context projection.
    *
    * @param aspect
    *   the mount step whose input is the current context
    * @param append
    *   determines the resulting context shape
    * @return
    *   a builder with combined environment and extended context
    */
  def withMountAspect[R1, Claims, Out, Result](
    aspect: LiveMountAspect[R1, A, Ctx, Claims, Out]
  )(using append: ContextAppend.Aux[Ctx, Out, Result]
  ): LiveRouteParamsBuilder[R & R1, A, Need, Result, Params, Capability] =
    val projectPrevious = (result: Result) => append.left(result)
    LiveRouteParamsBuilder(
      pathCodec,
      paramsDecoder,
      paramsEncoder,
      mountPipeline ++ aspect.runtime,
      liveLayouts.map(_.mapContext(projectPrevious)),
      rootLayout.map(_.mapContext(projectPrevious)),
      hasRouteMountAspect = true
    )

  /** Appends a route Live layout using the current mount context.
    *
    * @param layout
    *   the layout to append inside router and session layouts
    * @return
    *   this parameterized route configuration with the additional layout
    */
  def withLayout(
    layout: LiveLayout[A, Ctx]
  ): LiveRouteParamsBuilder[R, A, Need, Ctx, Params, Capability] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsDecoder,
      paramsEncoder,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[A, Ctx, Ctx](layout, identity),
      rootLayout,
      hasRouteMountAspect
    )

  /** Selects a route root layout using the current mount context.
    *
    * It replaces a previous route root and overrides session and router roots.
    *
    * @param layout
    *   the root layout to select
    * @return
    *   this parameterized route configuration with the selected root
    */
  def withRootLayout(
    layout: LiveRootLayout[A, Ctx]
  ): LiveRouteParamsBuilder[R, A, Need, Ctx, Params, Capability] =
    LiveRouteParamsBuilder(
      pathCodec,
      paramsDecoder,
      paramsEncoder,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[A, Ctx, Ctx](layout, identity)),
      hasRouteMountAspect
    )

  /** Completes the route with a by-name routed LiveView.
    *
    * @param view
    *   a fresh routed LiveView expression evaluated for each lifecycle
    * @return
    *   the completed route
    */
  def apply[Msg: LiveMessageTag, Model](view: => LiveView.Routed[Msg, Model, Params])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, _, _) => view)

  /** Infix alias for [[apply]] with a by-name routed LiveView.
    *
    * @param view
    *   a fresh routed LiveView expression evaluated for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowRoutedView")
  infix def ->[Msg: LiveMessageTag, Model](view: => LiveView.Routed[Msg, Model, Params])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(view)

  /** Completes the route with a factory receiving path values, request, and current context.
    *
    * `Params` are decoded later for the returned routed LiveView's lifecycle; the first argument is
    * the path value `A`.
    *
    * @param builder
    *   constructs a routed LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("applyRoutedFull")
  def apply[Msg: LiveMessageTag, Model](
    builder: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    new LiveRoute(
      pathCodec,
      (pathParams, request, context) => ZIO.succeed(builder(pathParams, request, context)),
      LiveRouteParamsRuntime.routed[A, Msg, Model, Params](pathCodec, paramsDecoder),
      summon[LiveMessageTag[Msg]].classTag,
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect = hasRouteMountAspect
    )

  /** Completes the route with a by-name routed LiveView layer.
    *
    * The layer is built in the mount scope for each lifecycle and adds its dependencies to the
    * route environment.
    *
    * @param layer
    *   the fresh layer expression that constructs the routed LiveView
    * @return
    *   the completed route
    */
  @targetName("applyRoutedLayer")
  def apply[R1, Msg: LiveMessageTag, Model, View <: LiveView.Routed[Msg, Model, Params]: Tag](
    layer: => ZLayer[R1, Nothing, View]
  ): LiveRoute[R & R1, A, Need, Ctx, Msg, Model] =
    new LiveRoute[R & R1, A, Need, Ctx, Msg, Model](
      pathCodec,
      (_, _, _) => layer.build.map(_.get[View]),
      LiveRouteParamsRuntime.routed[A, Msg, Model, Params](pathCodec, paramsDecoder),
      summon[LiveMessageTag[Msg]].classTag,
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect = hasRouteMountAspect
    )

  /** Infix alias for [[apply]] with a by-name routed LiveView layer.
    *
    * @param layer
    *   the fresh layer expression that constructs the routed LiveView
    * @return
    *   the completed route
    */
  @targetName("arrowRoutedLayer")
  infix def ->[R1, Msg: LiveMessageTag, Model, View <: LiveView.Routed[Msg, Model, Params]: Tag](
    layer: => ZLayer[R1, Nothing, View]
  ): LiveRoute[R & R1, A, Need, Ctx, Msg, Model] =
    apply(layer)

  /** Infix alias for the path/request/context routed-view factory.
    *
    * @param builder
    *   constructs a routed LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowRoutedFull")
  infix def ->[Msg: LiveMessageTag, Model](
    builder: (A, Request, Ctx) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  /** Completes the route with a routed-view factory receiving current mount context.
    *
    * @param builder
    *   constructs a routed LiveView from context for each lifecycle
    * @return
    *   the completed route
    */
  def context[Msg: LiveMessageTag, Model](
    builder: Ctx => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, _, context) => builder(context))

  /** Completes the route with a routed-view factory receiving path values and request.
    *
    * @param builder
    *   constructs a routed LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("applyRoutedRequestParams")
  def apply[Msg: LiveMessageTag, Model](
    builder: (A, Request) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((params, request, _) => builder(params, request))

  /** Infix alias for the path/request routed-view factory.
    *
    * @param builder
    *   constructs a routed LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowRoutedRequestParams")
  infix def ->[Msg: LiveMessageTag, Model](
    builder: (A, Request) => LiveView.Routed[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  /** Completes a `Unit` path route with a routed-view factory receiving the request.
    *
    * @param builder
    *   constructs a routed LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("applyRoutedRequest")
  def apply[Msg: LiveMessageTag, Model](
    builder: Request => LiveView.Routed[Msg, Model, Params]
  )(using A =:= Unit
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, request, _) => builder(request))

  /** Infix alias for the request-only routed-view factory.
    *
    * @param builder
    *   constructs a routed LiveView for each lifecycle
    * @return
    *   the completed route
    */
  @targetName("arrowRoutedRequest")
  infix def ->[Msg: LiveMessageTag, Model](
    builder: Request => LiveView.Routed[Msg, Model, Params]
  )(using A =:= Unit
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  /** Completes a tuple-context route with each context component as a separate argument.
    *
    * @param builder
    *   constructs a routed LiveView from path values, request, and both context components
    * @param ev
    *   evidence that the current context can be viewed as `(C1, C2)`
    * @return
    *   the completed route
    */
  @targetName("applyRoutedTuple2")
  def apply[C1, C2, Msg: LiveMessageTag, Model](
    builder: (A, Request, C1, C2) => LiveView.Routed[Msg, Model, Params]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((params, request, context) =>
      val tuple = ev(context)
      builder(params, request, tuple._1, tuple._2)
    )

  /** Infix alias for the two-context-component routed-view factory.
    *
    * @param builder
    *   constructs a routed LiveView from path values, request, and both context components
    * @return
    *   the completed route
    */
  @targetName("arrowRoutedTuple2")
  infix def ->[C1, C2, Msg: LiveMessageTag, Model](
    builder: (A, Request, C1, C2) => LiveView.Routed[Msg, Model, Params]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

end LiveRouteParamsBuilder

/** Starts one named Live session group.
  *
  * Reuse the same seed for every route fragment belonging to this named group. Its [[apply]] method
  * may be called more than once, and builders derived from the seed may select different mount or
  * layout configuration. Calling `Live.session` again with the same name creates an independent
  * group, which the router rejects as a duplicate even when its paths differ.
  */
final class LiveSessionSeed private[scalive] (private[scalive] val name: String):
  private val group = LiveSessionGroup.named(name)

  /** Groups routes that require no session-provided context.
    *
    * @param route
    *   the first route or route fragment in the session
    * @param routes
    *   additional routes or fragments in the same named group
    * @return
    *   one fragment for router assembly
    */
  def apply[R](route: LiveRouteFragment[R, Any], routes: LiveRouteFragment[R, Any]*)
    : LiveRouteFragment[R, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      Nil,
      None,
      group
    )(route, routes*)

  /** Starts the session mount pipeline with an aspect.
    *
    * Session aspects run before every contained route's aspects in disconnected and connected
    * phases. Their output can satisfy a route's `Need` and is also available to session layouts.
    *
    * @param aspect
    *   the session mount step
    * @return
    *   a session builder with the aspect's environment and output context
    */
  def withMountAspect[R, Claims, Out, Result](
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

  /** Attaches a context-free Live layout to the session.
    *
    * @param layout
    *   the layout applied outside route layouts and inside router layouts
    * @return
    *   a configurable session builder
    */
  def withLayout(layout: LiveLayout[Any, Any]): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      List(LiveLayoutLayer[Any, Any, Any](layout, identity)),
      None,
      group
    )

  /** Selects a context-free root layout for session routes without route roots.
    *
    * @param layout
    *   the session root layout
    * @return
    *   a configurable session builder
    */
  def withRootLayout(layout: LiveRootLayout[Any, Any]): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      Nil,
      Some(LiveRootLayoutLayer[Any, Any, Any](layout, identity)),
      group
    )

end LiveSessionSeed

/** Configures a named Live session before grouping routes.
  *
  * Session mount aspects compose in declaration order and run before route aspects. Their final
  * `Ctx` is checked against each route's required context and becomes the input to that route's
  * mount pipeline. Route LiveView factories and route layouts receive the resulting route context;
  * session layouts receive only the session-context projection visible when they were attached.
  * Session Live layouts wrap route layouts; a route root overrides the session root, while the
  * session root overrides the router root.
  *
  * @tparam R
  *   the environment required by session mount aspects
  * @tparam Ctx
  *   the session's current mount context
  */
final class LiveSessionBuilder[R, Ctx] private[scalive] (
  private[scalive] val name: String,
  private[scalive] val mountPipeline: LiveMountPipeline[R, Any, Any, Ctx],
  private[scalive] val liveLayouts: List[LiveLayoutLayer[Any, Ctx, ?]],
  private[scalive] val rootLayout: Option[LiveRootLayoutLayer[Any, Ctx, ?]],
  private[scalive] val group: LiveSessionGroup):

  /** Applies this session configuration to one or more route fragments.
    *
    * The compile-time context evidence ensures the session can satisfy each route's `Need`. All
    * routes in one call form one named session group for duplicate-name validation.
    *
    * @param route
    *   the first route or route fragment in the session
    * @param routes
    *   additional routes or fragments in the same named group
    * @return
    *   one fragment with combined session and route environments
    */
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

  /** Appends a mount aspect to the session pipeline.
    *
    * The aspect runs after earlier session aspects and before route aspects in both mount phases.
    * Previously attached session layouts retain their projection to the earlier context.
    *
    * @param aspect
    *   the mount step whose input is current session context
    * @param append
    *   determines the resulting context shape
    * @return
    *   a builder with combined environment and extended session context
    */
  def withMountAspect[R1, Claims, Out, Result](
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

  /** Appends a session Live layout using current session context.
    *
    * Multiple session layouts wrap in declaration order, with the first outermost.
    *
    * @param layout
    *   the layout to append outside route layouts
    * @return
    *   this session configuration with the additional layout
    */
  def withLayout(layout: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[Any, Ctx, Ctx](layout, identity),
      rootLayout,
      group
    )

  /** Selects the session root layout using current session context.
    *
    * The most recently selected session root replaces a previous one. It applies only where a route
    * has no root layout of its own.
    *
    * @param layout
    *   the session root layout to select
    * @return
    *   this session configuration with the selected root
    */
  def withRootLayout(layout: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[Any, Ctx, Ctx](layout, identity)),
      group
    )

end LiveSessionBuilder

/** Assembles configured Live route fragments into ZIO HTTP routes.
  *
  * Router Live layouts are global and wrap session and route layouts. Its single root layout is the
  * fallback when neither a route nor its session selects one. Router configuration is immutable;
  * each modifier returns a new router.
  *
  * Calling the router validates the complete assembly synchronously. Duplicate rendered paths are
  * rejected across all fragments and sessions. Reusing a session name through separate
  * [[Live.session]] groups is also rejected; put all routes for that name in one session call.
  *
  * @tparam R
  *   the environment already required by router internals
  */
final class LiveRouter[R] private[scalive] (
  globalLayouts: List[LiveLayout[Any, Any]],
  globalRootLayout: LiveRootLayout[Any, Any],
  liveSocketMount: PathCodec[Unit],
  security: LiveSecurity,
  runtimeTraceFactory: RuntimeTraceFactory):

  /** Appends a context-free global Live layout.
    *
    * Multiple router layouts wrap in declaration order, with the first outermost.
    *
    * @param layout
    *   the global layout to append
    * @return
    *   a router with the additional layout
    */
  def withLayout(layout: LiveLayout[Any, Any]): LiveRouter[R] =
    LiveRouter(
      globalLayouts :+ layout,
      globalRootLayout,
      liveSocketMount,
      security,
      runtimeTraceFactory
    )

  /** Replaces the global fallback root layout.
    *
    * @param layout
    *   the root used by routes without session- or route-specific roots
    * @return
    *   a router with the selected fallback root
    */
  def withRootLayout(layout: LiveRootLayout[Any, Any]): LiveRouter[R] =
    LiveRouter(globalLayouts, layout, liveSocketMount, security, runtimeTraceFactory)

  /** Replaces the HTTP path where the Live websocket transport is mounted.
    *
    * @param path
    *   a unit-valued path codec for the socket endpoint
    * @return
    *   a router with the selected socket path
    */
  def withSocketPath(path: PathCodec[Unit]): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, path, security, runtimeTraceFactory)

  /** Replaces the token signing configuration while preserving other security policy.
    *
    * @param config
    *   the signing secret and token lifetime configuration
    * @return
    *   a router using the updated security token configuration
    */
  def withTokenConfig(config: TokenConfig): LiveRouter[R] =
    LiveRouter(
      globalLayouts,
      globalRootLayout,
      liveSocketMount,
      security.withTokenConfig(config),
      runtimeTraceFactory
    )

  /** Replaces the complete Live security configuration.
    *
    * @param value
    *   token, CSRF, cookie, and flash security policy
    * @return
    *   a router using `value`
    */
  def withSecurity(value: LiveSecurity): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, liveSocketMount, value, runtimeTraceFactory)

  private[scalive] def withRuntimeTrace(factory: RuntimeTraceFactory): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, liveSocketMount, security, factory)

  /** Validates and assembles Live fragments as executable ZIO HTTP routes.
    *
    * Only fragments whose context requirements have been satisfied can reach this boundary. Route
    * environments are intersected with the router environment in the returned `Routes` type.
    *
    * @param route
    *   the first completed route or session fragment
    * @param routes
    *   additional completed route or session fragments
    * @return
    *   the GET page routes and Live socket route
    * @throws IllegalArgumentException
    *   if rendered route paths are duplicated, or one session name was declared as multiple groups
    */
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
      security,
      runtimeTraceFactory
    ).routes
end LiveRouter

/** Entry points for declaring and assembling Live routes. */
object Live:
  /** The default immutable router.
    *
    * It has no ordinary layouts, uses [[LiveRootLayout.identity]], mounts the Live socket at
    * `/live`, uses default token security, and disables runtime tracing.
    */
  val router: LiveRouter[Any] =
    LiveRouter(
      Nil,
      LiveRootLayout.identity,
      PathCodec.empty / "live",
      LiveSecurity(TokenConfig.default),
      RuntimeTraceFactory.Disabled
    )

  /** Starts a typed GET Live route from a complete ZIO HTTP path codec.
    *
    * @param path
    *   the path codec to match and use for typed location construction
    * @return
    *   a route seed for the path value `A`
    */
  def route[A](path: PathCodec[A]): LiveRouteSeed[A] =
    LiveRouteSeed(path)

  /** Starts a named Live session group.
    *
    * A name identifies lifecycle compatibility on the client. Declare all routes for the same name
    * in one resulting session group; router assembly rejects duplicate groups.
    *
    * @param name
    *   the stable session name shared by the grouped routes
    * @return
    *   a session seed
    */
  def session(name: String): LiveSessionSeed =
    LiveSessionSeed(name)
end Live

/** Starts a GET Live route at the root path.
  *
  * Append path codecs with `/`, configure the route, then complete it with `apply` or `->`. This is
  * the concise counterpart to `Live.route(PathCodec.empty)`.
  */
val live: LiveRouteSeed[Unit] = LiveRouteSeed(PathCodec.empty)
