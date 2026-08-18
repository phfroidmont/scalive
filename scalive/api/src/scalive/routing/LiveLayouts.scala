package scalive

import zio.http.{Request, URL}

/** Signal-backed values available to an ordinary Live layout. */
final case class LiveLayoutContext[+A, +Ctx](
  params: Signal[A],
  request: Signal[Request],
  currentUrl: Signal[URL],
  context: Ctx)

/** Value-backed inputs available while rendering a root document. */
final case class LiveRootLayoutContext[+A, +Ctx](
  params: A,
  request: Request,
  currentUrl: URL,
  context: Ctx)

/** Declaratively wraps a LiveView while preserving its message type. */
trait LiveLayout[-A, -Ctx]:
  def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[A, Ctx]): HtmlElement[Msg]

object LiveLayout:
  val identity: LiveLayout[Any, Any] = new LiveLayout[Any, Any]:
    def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = content

  def apply[A, Ctx](
    render: [Msg] => (HtmlElement[Msg], LiveLayoutContext[A, Ctx]) => HtmlElement[Msg]
  ): LiveLayout[A, Ctx] = new LiveLayout[A, Ctx]:
    def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[A, Ctx]) =
      render[Msg](content, context)

  private[scalive] def contramapContext[A, Ctx, Ctx2](
    layout: LiveLayout[A, Ctx],
    f: Ctx2 => Ctx
  ): LiveLayout[A, Ctx2] = new LiveLayout[A, Ctx2]:
    def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[A, Ctx2]) =
      layout.view(
        content,
        LiveLayoutContext(context.params, context.request, context.currentUrl, f(context.context))
      )

/** Declaratively renders and identifies the outer document shell. */
trait LiveRootLayout[-A, -Ctx]:
  def key(context: LiveRootLayoutContext[A, Ctx]): String
  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    context: LiveRootLayoutContext[A, Ctx]
  ): HtmlElement[Msg]

object LiveRootLayout:
  val identity: LiveRootLayout[Any, Any] = new LiveRootLayout[Any, Any]:
    def key(context: LiveRootLayoutContext[Any, Any]) = "scalive:identity-root"
    def render[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      context: LiveRootLayoutContext[Any, Any]
    ) = content

  def apply[A, Ctx](
    rootKey: String
  )(
    renderRoot: [Msg] => (
      HtmlElement[Msg],
      Option[String],
      LiveRootLayoutContext[A, Ctx]
    ) => HtmlElement[Msg]
  ): LiveRootLayout[A, Ctx] = dynamic[A, Ctx](_ => rootKey)(renderRoot)

  def dynamic[A, Ctx](
    rootKey: LiveRootLayoutContext[A, Ctx] => String
  )(
    renderRoot: [Msg] => (
      HtmlElement[Msg],
      Option[String],
      LiveRootLayoutContext[A, Ctx]
    ) => HtmlElement[Msg]
  ): LiveRootLayout[A, Ctx] = new LiveRootLayout[A, Ctx]:
    def key(context: LiveRootLayoutContext[A, Ctx]) = rootKey(context)
    def render[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      context: LiveRootLayoutContext[A, Ctx]
    ) = renderRoot[Msg](content, pageTitle, context)

  private[scalive] def contramapContext[A, Ctx, Ctx2](
    layout: LiveRootLayout[A, Ctx],
    f: Ctx2 => Ctx
  ): LiveRootLayout[A, Ctx2] = new LiveRootLayout[A, Ctx2]:
    def key(context: LiveRootLayoutContext[A, Ctx2]) =
      layout.key(
        LiveRootLayoutContext(
          context.params,
          context.request,
          context.currentUrl,
          f(context.context)
        )
      )

    def render[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      context: LiveRootLayoutContext[A, Ctx2]
    ) = layout.render(
      content,
      pageTitle,
      LiveRootLayoutContext(context.params, context.request, context.currentUrl, f(context.context))
    )
end LiveRootLayout
