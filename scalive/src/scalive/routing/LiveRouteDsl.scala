package scalive

import scala.annotation.targetName
import scala.reflect.ClassTag

import zio.http.Request
import zio.http.Routes
import zio.http.codec.Combiner
import zio.http.codec.PathCodec
import zio.http.codec.QueryCodec
import zio.schema.Schema

sealed trait LiveRouteParamsCapability

object LiveRouteParamsCapability:
  sealed trait Encodable  extends LiveRouteParamsCapability
  sealed trait DecodeOnly extends LiveRouteParamsCapability

class LiveRouteSeed[A] private[scalive] (pathCodec: PathCodec[A]):
  def /[B](that: PathCodec[B])(using combiner: Combiner[A, B]): LiveRouteSeed[combiner.Out] =
    LiveRouteSeed(pathCodec / that)

  def location(value: A): LiveLocation =
    locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
    LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

  @targetName("unitLocation")
  def location(using ev: A =:= Unit): LiveLocation =
    location(ev.flip(()))

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

  def params: LiveRouteParamsBuilder[Any, A, Any, Any, A, LiveRouteParamsCapability.Encodable] =
    base[Any].params

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

  def withLayout[Ctx](layout: LiveLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
    base[Ctx].withLayout(layout)

  def withRootLayout[Ctx](layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
    base[Ctx].withRootLayout(layout)

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

  def location(value: A): LiveLocation =
    locationEither(value).fold(error => throw new LiveLocation.EncodingException(error), identity)

  def locationEither(value: A): Either[LiveLocation.EncodeError, LiveLocation] =
    LiveLocation.encode(pathCodec, LiveParamsCodec.Encoded(value, zio.http.QueryParams.empty))

  @targetName("unitLocation")
  def location(using ev: A =:= Unit): LiveLocation =
    location(ev.flip(()))

  @targetName("unitLocationEither")
  def locationEither(using ev: A =:= Unit): Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(ev.flip(()))

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

  def withLayout(layout: LiveLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[A, Ctx, Ctx](layout, identity),
      rootLayout,
      hasRouteMountAspect
    )

  def withRootLayout(layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx] =
    LiveRouteBuilder(
      pathCodec,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[A, Ctx, Ctx](layout, identity)),
      hasRouteMountAspect
    )

  def params: LiveRouteParamsBuilder[
    R,
    A,
    Need,
    Ctx,
    A,
    LiveRouteParamsCapability.Encodable
  ] =
    params(LiveParamsCodec.path[A])

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
      LiveRouteParamsRuntime.none[A, Msg, Model],
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

  def location(params: Params)(using Capability =:= LiveRouteParamsCapability.Encodable)
    : LiveLocation =
    locationEither(params).fold(
      error => throw new LiveLocation.EncodingException(error),
      identity
    )

  def locationEither(
    params: Params
  )(using Capability =:= LiveRouteParamsCapability.Encodable
  ): Either[LiveLocation.EncodeError, LiveLocation] =
    paramsEncoder.get.apply(params).flatMap(LiveLocation.encode(pathCodec, _))

  @targetName("unitParamsLocation")
  def location(using Params =:= Unit, Capability =:= LiveRouteParamsCapability.Encodable)
    : LiveLocation =
    location(summon[Params =:= Unit].flip(()))

  @targetName("unitParamsLocationEither")
  def locationEither(using Params =:= Unit, Capability =:= LiveRouteParamsCapability.Encodable)
    : Either[LiveLocation.EncodeError, LiveLocation] =
    locationEither(summon[Params =:= Unit].flip(()))

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

  def apply[Msg: ClassTag, Model](view: => RoutedLiveView[Msg, Model, Params])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, _, _) => view)

  @targetName("arrowRoutedView")
  infix def ->[Msg: ClassTag, Model](view: => RoutedLiveView[Msg, Model, Params])
    : LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(view)

  @targetName("applyRoutedFull")
  def apply[Msg: ClassTag, Model](
    builder: (A, Request, Ctx) => RoutedLiveView[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    new LiveRoute(
      pathCodec,
      builder,
      LiveRouteParamsRuntime.routed[A, Msg, Model, Params](pathCodec, paramsDecoder),
      summon[ClassTag[Msg]],
      mountPipeline,
      liveLayouts,
      rootLayout,
      hasRouteMountAspect = hasRouteMountAspect
    )

  @targetName("arrowRoutedFull")
  infix def ->[Msg: ClassTag, Model](
    builder: (A, Request, Ctx) => RoutedLiveView[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  @targetName("applyRoutedRequestParams")
  def apply[Msg: ClassTag, Model](
    builder: (A, Request) => RoutedLiveView[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((params, request, _) => builder(params, request))

  @targetName("arrowRoutedRequestParams")
  infix def ->[Msg: ClassTag, Model](
    builder: (A, Request) => RoutedLiveView[Msg, Model, Params]
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  @targetName("applyRoutedRequest")
  def apply[Msg: ClassTag, Model](
    builder: Request => RoutedLiveView[Msg, Model, Params]
  )(using A =:= Unit
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((_, request, _) => builder(request))

  @targetName("arrowRoutedRequest")
  infix def ->[Msg: ClassTag, Model](
    builder: Request => RoutedLiveView[Msg, Model, Params]
  )(using A =:= Unit
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)

  @targetName("applyRoutedTuple2")
  def apply[C1, C2, Msg: ClassTag, Model](
    builder: (A, Request, C1, C2) => RoutedLiveView[Msg, Model, Params]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply((params, request, context) =>
      val tuple = ev(context)
      builder(params, request, tuple._1, tuple._2)
    )

  @targetName("arrowRoutedTuple2")
  infix def ->[C1, C2, Msg: ClassTag, Model](
    builder: (A, Request, C1, C2) => RoutedLiveView[Msg, Model, Params]
  )(using ev: Ctx <:< (C1, C2)
  ): LiveRoute[R, A, Need, Ctx, Msg, Model] =
    apply(builder)
end LiveRouteParamsBuilder

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

  def withLayout(layout: LiveLayout[Any, Any]): LiveSessionBuilder[Any, Any] =
    LiveSessionBuilder[Any, Any](
      name,
      LiveMountPipeline.identity[Any, Any],
      List(LiveLayoutLayer[Any, Any, Any](layout, identity)),
      None,
      group
    )

  def withRootLayout(layout: LiveRootLayout[Any, Any]): LiveSessionBuilder[Any, Any] =
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

  def withLayout(layout: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      mountPipeline,
      liveLayouts :+ LiveLayoutLayer[Any, Ctx, Ctx](layout, identity),
      rootLayout,
      group
    )

  def withRootLayout(layout: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx] =
    LiveSessionBuilder(
      name,
      mountPipeline,
      liveLayouts,
      Some(LiveRootLayoutLayer[Any, Ctx, Ctx](layout, identity)),
      group
    )

end LiveSessionBuilder

final class LiveRouter[R] private[scalive] (
  globalLayouts: List[LiveLayout[Any, Any]],
  globalRootLayout: LiveRootLayout[Any, Any],
  liveSocketMount: PathCodec[Unit],
  tokenConfig: TokenConfig):

  def withLayout(layout: LiveLayout[Any, Any]): LiveRouter[R] =
    LiveRouter(globalLayouts :+ layout, globalRootLayout, liveSocketMount, tokenConfig)

  def withRootLayout(layout: LiveRootLayout[Any, Any]): LiveRouter[R] =
    LiveRouter(globalLayouts, layout, liveSocketMount, tokenConfig)

  def withSocketPath(path: PathCodec[Unit]): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, path, tokenConfig)

  def withTokenConfig(config: TokenConfig): LiveRouter[R] =
    LiveRouter(globalLayouts, globalRootLayout, liveSocketMount, config)

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

val live: LiveRouteSeed[Unit] = LiveRouteSeed(PathCodec.empty)
