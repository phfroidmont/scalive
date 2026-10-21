package scalive

import zio.*
import zio.http.*

import scalive.runtime.kernel.RuntimeLog

/** Why a page request did not render its LiveView. */
private[scalive] enum PageFailure:
  /** An error Scalive answers itself, with the router's error page when it has one. */
  case Error(status: Status)

  /** A response the application chose, such as a session aspect's redirect, sent unchanged. */
  case Given(response: Response)

  /** The route's LiveView factory or HTTP handler failed, as `message` logs it. */
  case Failed(message: String, cause: Cause[Throwable])

private[scalive] object PageFailure:
  def fromRouteMount(failure: LiveRouteMountFailure): PageFailure = failure match
    case _: (LiveRouteMountFailure.Unauthorized | LiveRouteMountFailure.Forbidden |
          LiveRouteMountFailure.NotFound) =>
      Error(failure.disconnectedResponse.status)
    case _ => Given(failure.disconnectedResponse)

/** Renders the router's [[LiveErrorPage]] inside its root layout, or a bare status without one. */
final private[scalive] class ErrorPages(
  rootLayout: LiveRootLayout[Any, Any],
  page: Option[LiveErrorPage]):

  // Without a page for 404, an unmatched request is left to the application's other routes.
  def rendersNotFound: Boolean = page.exists(_.renders(Status.NotFound))

  def respond(failure: PageFailure, request: Request): UIO[Response] = failure match
    case PageFailure.Error(status)          => response(status, request)
    case PageFailure.Given(response)        => ZIO.succeed(response)
    case PageFailure.Failed(message, cause) => failed(message, cause, request)

  def response(status: Status, request: Request): UIO[Response] = page match
    case Some(page) if page.renders(status) =>
      render(page, status, request).catchAllCause(cause =>
        ZIO.logErrorCause("Error page render failed", cause).as(ErrorPages.bare(status))
      )
    case _ => ZIO.succeed(ErrorPages.bare(status))

  /** Answers a page request whose LiveView failed: 404 for [[LiveNotFound]], 500 otherwise. */
  def failed(message: String, cause: Cause[Throwable], request: Request): UIO[Response] =
    LiveNotFound.in(cause) match
      case Some(notFound) =>
        ZIO.logAnnotate(RuntimeLog.Reason, notFound.reason)(ZIO.logDebug(s"$message: not found")) *>
          response(Status.NotFound, request)
      case None =>
        ZIO.logErrorCause(message, cause) *> response(Status.InternalServerError, request)

  private def render(page: LiveErrorPage, status: Status, request: Request): Task[Response] =
    for
      document <- ZIO.attempt {
                    val context = LiveRootLayoutContext[Any, Any]((), request, request.url, ())
                    rootLayout.view(
                      page.view(status, request),
                      page.pageTitle(status, request),
                      context
                    )
                  }
      html <- StaticHtml
                .render(document, includeDoctype = true)
                .mapError(error => Exception(s"error page render failed: $error"))
    yield Response(
      status = status,
      headers = Headers(Header.ContentType(MediaType.text.html)),
      body = Body.fromString(html)
    )
end ErrorPages

private[scalive] object ErrorPages:
  /** The cookie the Phoenix client sets before reloading after a join answered `reload`. */
  val ReloadStatusCookie = "__phoenix_reload_status__"

  // Browsers show their own error page for an error with no body, so name the status.
  def bare(status: Status): Response = Response.text(status.reasonPhrase).status(status)
