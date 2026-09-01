package scalive

import zio.ZIO
import zio.http.{Response, URL}

/** Stable input available to a [[LiveRouteMountAspect]] on every route mount.
  *
  * The same value shape is available during disconnected HTTP rendering, initial connected mount,
  * and same-session live navigation. Original HTTP cookies and headers are intentionally absent;
  * extract HTTP-only identity in a [[LiveSessionMountAspect]] and pass its typed context into the
  * route aspect instead.
  */
final case class LiveRouteMountRequest[+A](params: A, url: URL)

/** A route-mount rejection with safe HTTP and connected-admission semantics. */
enum LiveRouteMountFailure:
  /** Redirects to a location produced by a typed Live route. */
  case Redirect(to: LiveLocation)

  /** Redirects to an unchecked URL without same-origin or local-path validation. */
  case RedirectUnsafe(to: URL)

  /** Returns HTTP 401 or rejects connected admission as unauthorized. */
  case Unauthorized(reason: Option[String])

  /** Returns HTTP 403 or rejects connected admission as unauthorized. */
  case Forbidden(reason: Option[String])

  /** Returns HTTP 404 or rejects connected admission as unauthorized. */
  case NotFound(reason: Option[String])

  /** Supplies explicit HTTP and connected outcomes for uncommon policies. */
  case Custom(disconnected: Response, connected: LiveMountFailure)

  private[scalive] def disconnectedResponse: Response = this match
    case Redirect(to)        => to.seeOther
    case RedirectUnsafe(to)  => Response.seeOther(to)
    case Unauthorized(_)     => Response.unauthorized
    case Forbidden(_)        => Response.forbidden
    case NotFound(_)         => Response.notFound
    case Custom(response, _) => response

  private[scalive] def connectedFailure: LiveMountFailure = this match
    case Redirect(to)         => LiveMountFailure.redirect(to)
    case RedirectUnsafe(to)   => LiveMountFailure.redirectUnsafe(to)
    case Unauthorized(reason) => LiveMountFailure.Unauthorized(reason)
    case Forbidden(reason)    =>
      LiveMountFailure.Unauthorized(reason.orElse(Some("route mount forbidden")))
    case NotFound(reason) =>
      LiveMountFailure.Unauthorized(reason.orElse(Some("route mount not found")))
    case Custom(_, failure) => failure
end LiveRouteMountFailure

/** Constructors for route mount failures. */
object LiveRouteMountFailure:
  def redirect(to: LiveLocation): LiveRouteMountFailure = Redirect(to)
  def redirectUnsafe(to: URL): LiveRouteMountFailure    = RedirectUnsafe(to)

  def unauthorized: LiveRouteMountFailure                 = Unauthorized(None)
  def unauthorized(reason: String): LiveRouteMountFailure = Unauthorized(Some(reason))

  def forbidden: LiveRouteMountFailure                 = Forbidden(None)
  def forbidden(reason: String): LiveRouteMountFailure = Forbidden(Some(reason))

  def notFound: LiveRouteMountFailure                 = NotFound(None)
  def notFound(reason: String): LiveRouteMountFailure = NotFound(Some(reason))

  def custom(
    disconnected: Response,
    connected: LiveMountFailure
  ): LiveRouteMountFailure = Custom(disconnected, connected)

/** Derives fresh typed route context before every disconnected or connected route mount.
  *
  * Route aspects do not issue claims. Their output is never serialized or trusted across routes;
  * the aspect runs again with the destination's typed parameters during same-session live
  * navigation.
  */
final case class LiveRouteMountAspect[R, A, -In, Ctx] private[scalive] (
  private[scalive] val run: (LiveRouteMountRequest[A], In) => ZIO[R, LiveRouteMountFailure, Ctx]):
  /** Transforms the freshly derived context without changing this aspect's input. */
  def map[Ctx2](f: Ctx => Ctx2): LiveRouteMountAspect[R, A, In, Ctx2] =
    LiveRouteMountAspect((request, input) => run(request, input).map(f))

  /** Composes two route aspects from left to right on every mount. */
  def ++[R1, Ctx2, Result](
    that: LiveRouteMountAspect[R1, A, Ctx, Ctx2]
  )(using append: ContextAppend.Aux[Ctx, Ctx2, Result]
  ): LiveRouteMountAspect[R & R1, A, In, Result] =
    LiveRouteMountAspect((request, input) =>
      for
        left  <- run(request, input)
        right <- that.run(request, left)
      yield append.append(left, right)
    )

/** Constructors for route mount aspects. */
object LiveRouteMountAspect:
  /** Creates a route aspect that consumes context from a session or preceding route aspect. */
  def make[R, A, In, Ctx](
    run: (LiveRouteMountRequest[A], In) => ZIO[R, LiveRouteMountFailure, Ctx]
  ): LiveRouteMountAspect[R, A, In, Ctx] =
    LiveRouteMountAspect(run)

  /** Creates a route aspect that does not consume context from a preceding aspect. */
  def fromRequest[R, A, Ctx](
    run: LiveRouteMountRequest[A] => ZIO[R, LiveRouteMountFailure, Ctx]
  ): LiveRouteMountAspect[R, A, Any, Ctx] =
    LiveRouteMountAspect((request, _) => run(request))
