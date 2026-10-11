package scalive

import zio.http.{Request, URL}

/** Signal-backed values available to an ordinary Live layout.
  *
  * @param params
  *   The route's decoded path values, as a signal that follows the current URL.
  * @param request
  *   The mount request, as a signal whose URL follows the current URL. During a connected mount it
  *   is a GET request without the browser's headers or cookies.
  * @param currentUrl
  *   The page's current URL, as a signal that follows live patches.
  * @param context
  *   The context produced by the session's or route's mount aspects, or the part of it selected
  *   with [[LiveLayout.forContext]].
  */
final case class LiveLayoutContext[+A, +Ctx](
  params: Signal[A],
  request: Signal[Request],
  currentUrl: Signal[URL],
  context: Ctx)

/** Value-backed inputs available while rendering a root document.
  *
  * @param params
  *   The route's decoded path values.
  * @param request
  *   The mount request. [[LiveRootLayout.view]] receives the browser's HTTP request, but
  *   [[LiveRootLayout.key]] also runs during connected mount with a GET request that has no browser
  *   headers or cookies, so a key must not depend on them.
  * @param currentUrl
  *   The URL being mounted.
  * @param context
  *   The context produced by the session's or route's mount aspects, or the part of it selected
  *   with [[LiveRootLayout.forContext]].
  */
final case class LiveRootLayoutContext[+A, +Ctx](
  params: A,
  request: Request,
  currentUrl: URL,
  context: Ctx)

/** Wraps a LiveView's content inside the document, for example in shared navigation.
  *
  * Implement it with a class and install it with `withLayout` on the router, a named live session,
  * or a route. `A` is the route parameter type and `Ctx` the mount context type the layout reads;
  * use `Any` for either when the layout does not read it.
  *
  * Scalive calls `view` once per LiveView lifecycle, like [[LiveView.view]]. Route parameters,
  * request, and URL are signals, so the layout follows live patches and navigation without being
  * rebuilt. Because `view` accepts content of any message type, the layout cannot send messages of
  * its own; use links, nested LiveViews, or browser hooks for interactive chrome.
  *
  * See the [layouts guide](https://scalive.dev/guides/layouts).
  */
trait LiveLayout[-A, -Ctx]:
  /** Renders this layout around `content`.
    *
    * `content` is the LiveView, already wrapped in the layouts installed inside this one. Read the
    * URL, path values, and request from the signals in `context`.
    */
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

/** Predefined [[LiveLayout]] values. */
object LiveLayout:
  /** A layout that returns its content unchanged. */
  val identity: LiveLayout[Any, Any] = new LiveLayout[Any, Any]:
    def view[Msg](content: HtmlElement[Msg], context: LiveLayoutContext[Any, Any]) = content

/** Renders the outer HTML document: `<html>`, `<head>`, scripts, stylesheets, and `<body>`.
  *
  * Implement it with a class and install it with `withRootLayout` on the router, a named live
  * session, or a route. `A` is the route parameter type and `Ctx` the mount context type the layout
  * reads; use `Any` for either when the layout does not read it.
  *
  * Scalive calls `view` only for the first HTTP response. Live updates patch the LiveView inside
  * the document and never re-render the document itself, so its inputs are plain values.
  *
  * See the [layouts guide](https://scalive.dev/guides/layouts).
  */
trait LiveRootLayout[-A, -Ctx]:

  /** Identifies the document this layout renders.
    *
    * Live navigation keeps the current document only when the destination's root layout returns the
    * same key; otherwise the browser loads the destination with a full page request. The default is
    * this class's name, so routes that share a root layout class share the document. Override it
    * when one class renders documents that cannot replace each other, for example a different
    * `lang` per context.
    */
  def key(context: LiveRootLayoutContext[A, Ctx]): String = getClass.getName

  /** Renders the document around `content`.
    *
    * `pageTitle` is the LiveView's [[LiveView.pageTitle]] at the first render; pass it to
    * `liveTitle` so live updates can change the title.
    */
  def view[Msg](
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

        override def key(context: LiveRootLayoutContext[B, Ctx]) =
          LiveRootLayout.this.key(project(context))

        def view[Msg](
          content: HtmlElement[Msg],
          pageTitle: Option[String],
          context: LiveRootLayoutContext[B, Ctx]
        ) = LiveRootLayout.this.view(content, pageTitle, project(context))

  /** Adapts this document shell to another context type by selecting the context it needs.
    *
    * Key and view apply the selector independently, so it must be pure and deterministic.
    * Parameters, request, URL, content, and page title are forwarded unchanged. This does not
    * change the route or session context or make document-root attributes reactive. The identity
    * root stays unchanged and does not evaluate the selector because it consumes no context.
    */
  final def forContext[NewContext](select: NewContext => Ctx): LiveRootLayout[A, NewContext] =
    if this eq LiveRootLayout.identity then LiveRootLayout.identity
    else
      new LiveRootLayout[A, NewContext]:
        private def project(context: LiveRootLayoutContext[A, NewContext]) =
          LiveRootLayoutContext(
            context.params,
            context.request,
            context.currentUrl,
            select(context.context)
          )

        override def key(context: LiveRootLayoutContext[A, NewContext]) =
          LiveRootLayout.this.key(project(context))

        def view[Msg](
          content: HtmlElement[Msg],
          pageTitle: Option[String],
          context: LiveRootLayoutContext[A, NewContext]
        ) = LiveRootLayout.this.view(content, pageTitle, project(context))
end LiveRootLayout

/** Predefined [[LiveRootLayout]] values. */
object LiveRootLayout:
  /** A root layout that renders the LiveView with no document around it.
    *
    * It is the router's default, so a page has no `<html>`, `<head>`, or client scripts until a
    * root layout is installed. All routes that use it share one document key.
    */
  val identity: LiveRootLayout[Any, Any] = new LiveRootLayout[Any, Any]:
    override def key(context: LiveRootLayoutContext[Any, Any]) = "scalive:identity-root"
    def view[Msg](
      content: HtmlElement[Msg],
      pageTitle: Option[String],
      context: LiveRootLayoutContext[Any, Any]
    ) = content
