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

  final private[scalive] def forPathParams[B](select: B => A): LiveLayout[B, Ctx] =
    new LiveLayout[B, Ctx]:
      def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[B, Ctx]) =
        LiveLayout.this.view(
          content,
          LiveLayoutContext(
            context.params.map(select),
            context.request,
            context.currentUrl,
            context.context
          )
        )

  /** Adapts this layout to another context type by selecting the context it needs.
    *
    * The selector must be pure and deterministic. It changes only this layout's input, not the
    * route or session context; parameters, request, URL, and content are forwarded unchanged.
    */
  final def forContext[NewContext](select: NewContext => Ctx): LiveLayout[A, NewContext] =
    new LiveLayout[A, NewContext]:
      def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[A, NewContext]) =
        LiveLayout.this.view(
          content,
          LiveLayoutContext(
            context.params,
            context.request,
            context.currentUrl,
            select(context.context)
          )
        )
end LiveLayout

object LiveLayout:
  val identity: LiveLayout[Any, Any] = new LiveLayout[Any, Any]:
    def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = content

  def apply[A, Ctx](
    render: [Msg] => (HtmlElement[Msg], LiveLayoutContext[A, Ctx]) => HtmlElement[Msg]
  ): LiveLayout[A, Ctx] = new LiveLayout[A, Ctx]:
    def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[A, Ctx]) =
      render[Msg](content, context)

/** Declaratively renders and identifies the outer document shell. */
trait LiveRootLayout[-A, -Ctx]:
  def key(context: LiveRootLayoutContext[A, Ctx]): String
  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    context: LiveRootLayoutContext[A, Ctx]
  ): HtmlElement[Msg]

  final private[scalive] def forPathParams[B](select: B => A): LiveRootLayout[B, Ctx] =
    if this eq LiveRootLayout.identity then LiveRootLayout.identity
    else
      new LiveRootLayout[B, Ctx]:
        private def project(context: LiveRootLayoutContext[B, Ctx]) =
          LiveRootLayoutContext(
            select(context.params),
            context.request,
            context.currentUrl,
            context.context
          )

        def key(context: LiveRootLayoutContext[B, Ctx]) =
          LiveRootLayout.this.key(project(context))

        def render[Msg](
          content: HtmlElement[Msg],
          pageTitle: Option[String],
          context: LiveRootLayoutContext[B, Ctx]
        ) = LiveRootLayout.this.render(content, pageTitle, project(context))

  /** Adapts this document shell to another context type by selecting the context it needs.
    *
    * Key and render apply the selector independently, so it must be pure and deterministic.
    * Parameters, request, URL, content, and page title are forwarded unchanged. This does not
    * change the route or session context or make document-root attributes reactive. The identity
    * root stays unchanged and does not evaluate the selector because it consumes no context.
    */
  final def forContext[NewContext](select: NewContext => Ctx): LiveRootLayout[A, NewContext] =
    if this eq LiveRootLayout.identity then LiveRootLayout.identity
    else
      new LiveRootLayout[A, NewContext]:
        def key(context: LiveRootLayoutContext[A, NewContext]) =
          LiveRootLayout.this.key(
            LiveRootLayoutContext(
              context.params,
              context.request,
              context.currentUrl,
              select(context.context)
            )
          )

        def render[Msg](
          content: HtmlElement[Msg],
          pageTitle: Option[String],
          context: LiveRootLayoutContext[A, NewContext]
        ) = LiveRootLayout.this.render(
          content,
          pageTitle,
          LiveRootLayoutContext(
            context.params,
            context.request,
            context.currentUrl,
            select(context.context)
          )
        )
end LiveRootLayout

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

end LiveRootLayout
