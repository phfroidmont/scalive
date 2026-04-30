package scalive

import zio.http.Request
import zio.http.URL

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
