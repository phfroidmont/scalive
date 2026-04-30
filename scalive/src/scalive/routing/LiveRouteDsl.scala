package scalive

import scala.annotation.targetName
import scala.reflect.ClassTag

import zio.http.Request
import zio.http.Routes
import zio.http.codec.Combiner
import zio.http.codec.PathCodec

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
