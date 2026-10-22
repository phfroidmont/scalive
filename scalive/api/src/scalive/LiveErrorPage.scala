package scalive

import zio.http.codec.PathCodec
import zio.http.{Request, Status}

/** The page Scalive renders when it answers a page request with an error status.
  *
  * Install it with `Live.router.withErrorPage`. Scalive renders it inside the router's root layout
  * as a static document, with no LiveView in it, for each error it answers itself: `400` when the
  * route's parameters do not decode, `401`, `403` and `404` from a route mount aspect, `404` from
  * [[LiveNotFound]] and for a `GET` request no route matches, and `500` when a LiveView fails to
  * mount or render. A response given with [[LiveRouteMountFailure.custom]] or by a session mount
  * aspect is sent as given.
  *
  * The page is rendered like `StaticHtml.render`, so it cannot bind server events or show flash. It
  * must not reveal why the request failed; Scalive logs the reason instead.
  */
trait LiveErrorPage:
  /** Whether Scalive renders this page for `status`. The default is every status; for the others
    * Scalive answers the bare status, for example to keep a `500` independent of the root layout.
    */
  def renders(status: Status): Boolean = true

  /** The paths below which a `GET` request that no route matches answers this page's `404`.
    *
    * The default is every path, as a Phoenix router does. Narrow it when the server also serves
    * other routes, such as another application whose own catch-all must answer the rest, as in
    * `PathCodec.empty / "app"`. Only applies when the page renders `404`.
    */
  def unmatchedPaths: PathCodec[Unit] = PathCodec.empty

  /** The document title for `status`, rendered by the root layout's `liveTitle`. */
  def pageTitle(status: Status, request: Request): Option[String] = None

  /** Renders the page body for `status`, inside the router's root layout. */
  def view(status: Status, request: Request): HtmlElement[Nothing]
