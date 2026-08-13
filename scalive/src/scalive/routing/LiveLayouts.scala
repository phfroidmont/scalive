package scalive

import zio.http.Request
import zio.http.URL

/** Values available while rendering a Live layout or choosing a root layout key.
  *
  * @param params
  *   the path values for the currently matched route
  * @param request
  *   the current request metadata; its URL follows `currentUrl` across live patches
  * @param currentUrl
  *   the URL currently rendered, updated across live patches
  * @param context
  *   the typed mount-aspect context visible where the layout was attached
  */
final case class LiveLayoutContext[+A, +Ctx](
  params: A,
  request: Request,
  currentUrl: URL,
  context: Ctx)

/** Wraps a LiveView's rendered tree while preserving its message type.
  *
  * Live layouts participate in every LiveView render. Router layouts are outermost, followed by
  * session layouts and then route layouts. Within one level, layouts wrap in registration order,
  * with the first registered layout outermost. Each layout sees the mount context available at the
  * point where it was attached, even if later mount aspects extend that context.
  *
  * @tparam A
  *   the route path-value type accepted by this layout
  * @tparam Ctx
  *   the mount context accepted by this layout
  */
trait LiveLayout[-A, -Ctx]:
  /** Wraps rendered LiveView content.
    *
    * @param content
    *   the current typed LiveView tree
    * @param ctx
    *   route, request, URL, and mount context values
    * @return
    *   the wrapped tree with the same message type
    */
  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]

/** Constructors and the no-op implementation for [[LiveLayout]]. */
object LiveLayout:
  /** A layout that returns content unchanged. */
  val identity: LiveLayout[Any, Any] =
    new LiveLayout[Any, Any]:
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]) = content

  /** Creates a layout from a rendering function.
    *
    * The wildcard element signature is an ergonomic construction boundary. The function must only
    * wrap or transform the supplied tree without introducing event bindings for another message
    * type; the returned element is restored to the input `Msg` type internally.
    *
    * @param f
    *   wraps content using the current layout context
    * @return
    *   a typed Live layout
    */
  def apply[A, Ctx](
    f: (HtmlElement[?], LiveLayoutContext[A, Ctx]) => HtmlElement[?]
  ): LiveLayout[A, Ctx] =
    new LiveLayout[A, Ctx]:
      def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[A, Ctx]) =
        f(content, ctx).asInstanceOf[HtmlElement[Msg]]

/** Renders the outer document shell and identifies live-navigation-compatible shells.
  *
  * Exactly one root layout is selected for a route: a route root overrides its session root, which
  * overrides the router root. Root layouts do not compose. The root is used for disconnected HTML
  * rendering and static asset tracking; ordinary [[LiveLayout]] instances wrap the LiveView inside
  * it.
  *
  * @tparam A
  *   the route path-value type accepted by this root layout
  * @tparam Ctx
  *   the mount context accepted by this root layout
  */
trait LiveRootLayout[-A, -Ctx]:
  /** Returns the compatibility key for this root document.
    *
    * Routes that can navigate over the same connected LiveView session must return the same stable
    * key. If the key changes, Scalive rejects connected live navigation so the destination can be
    * loaded through a fresh HTTP render. Include distinctions that make document shells or their
    * required assets incompatible; this is not an HTML DOM key.
    *
    * @param ctx
    *   route, request, URL, and mount context values
    * @return
    *   a stable root-layout compatibility identifier
    */
  def key(ctx: LiveLayoutContext[A, Ctx]): String

  /** Renders the outer document around laid-out LiveView content.
    *
    * @param content
    *   the LiveView after ordinary layouts have been applied
    * @param pageTitle
    *   the normalized title supplied by the root LiveView, when present
    * @param ctx
    *   route, request, URL, and mount context values
    * @return
    *   the complete root tree
    */
  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveLayoutContext[A, Ctx]
  ): HtmlElement[Msg]
end LiveRootLayout

/** Constructors and the no-op implementation for [[LiveRootLayout]]. */
object LiveRootLayout:
  /** A transparent root layout with a framework-stable compatibility key. */
  val identity: LiveRootLayout[Any, Any] =
    new LiveRootLayout[Any, Any]:
      def key(ctx: LiveLayoutContext[Any, Any]) = "scalive:identity-root"
      def render[Msg](
        content: HtmlElement[Msg],
        pageTitle: Option[String],
        ctx: LiveLayoutContext[Any, Any]
      ) = content

  /** Creates a root layout with a constant compatibility key.
    *
    * The wildcard element signature is an ergonomic construction boundary. The rendering function
    * must preserve the supplied content's message type.
    *
    * @param rootKey
    *   the stable key shared by routes with compatible root documents
    * @param f
    *   renders the root tree around content and an optional page title
    * @return
    *   a root layout with a constant key
    */
  def apply[A, Ctx](
    rootKey: String
  )(
    f: (HtmlElement[?], Option[String], LiveLayoutContext[A, Ctx]) => HtmlElement[?]
  ): LiveRootLayout[A, Ctx] =
    new LiveRootLayout[A, Ctx]:
      def key(ctx: LiveLayoutContext[A, Ctx]) = rootKey
      def render[Msg](
        content: HtmlElement[Msg],
        pageTitle: Option[String],
        ctx: LiveLayoutContext[A, Ctx]
      ) = f(content, pageTitle, ctx).asInstanceOf[HtmlElement[Msg]]

  /** Creates a root layout whose compatibility key depends on the current layout context.
    *
    * Use this only when one route can select genuinely different document shells. The key function
    * must be stable for contexts whose roots are compatible. The rendering function must preserve
    * the supplied content's message type.
    *
    * @param rootKey
    *   computes the root compatibility key
    * @param f
    *   renders the root tree around content and an optional page title
    * @return
    *   a context-sensitive root layout
    */
  def dynamic[A, Ctx](
    rootKey: LiveLayoutContext[A, Ctx] => String
  )(
    f: (HtmlElement[?], Option[String], LiveLayoutContext[A, Ctx]) => HtmlElement[?]
  ): LiveRootLayout[A, Ctx] =
    new LiveRootLayout[A, Ctx]:
      def key(ctx: LiveLayoutContext[A, Ctx]) = rootKey(ctx)
      def render[Msg](
        content: HtmlElement[Msg],
        pageTitle: Option[String],
        ctx: LiveLayoutContext[A, Ctx]
      ) = f(content, pageTitle, ctx).asInstanceOf[HtmlElement[Msg]]
end LiveRootLayout

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
    pageTitle: Option[String],
    params: A,
    request: Request,
    currentUrl: URL,
    context: Ctx
  ): HtmlElement[Msg] =
    layout.render(
      content,
      pageTitle,
      LiveLayoutContext(params, request, currentUrl, project(context))
    )

  def mapContext[Ctx2](f: Ctx2 => Ctx): LiveRootLayoutLayer[A, Ctx2, LayerCtx] =
    copy(project = project.compose(f))
