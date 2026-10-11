package scalive

import zio.*
import zio.http.*

/** Attributes shared by framework and application cookies.
  *
  * @param secure
  *   Whether cookies carry the `Secure` attribute, so browsers send them only over HTTPS.
  */
final case class CookiePolicy(secure: Boolean):
  /** Creates a response cookie with the shared attributes.
    *
    * The cookie is host-only, scoped to `/`, `HttpOnly`, and `SameSite=Lax`, and carries `Secure`
    * when `secure` is set. Without `maxAge`, the browser keeps it only for the current browsing
    * session.
    */
  def make(
    name: String,
    content: String,
    maxAge: Option[zio.Duration] = None
  ): Cookie.Response =
    Cookie.Response(
      name,
      content,
      path = Some(Path.root),
      isSecure = secure,
      isHttpOnly = true,
      maxAge = maxAge,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  /** Creates a response cookie that deletes the browser's cookie `name`.
    *
    * It uses the same path and attributes as [[make]], so it removes a cookie that `make` created.
    */
  def expire(name: String): Cookie.Response =
    make(name, "", maxAge = Some(zio.Duration.Zero))

/** CSRF, flash, and cookie helpers for ordinary HTTP handlers.
  *
  * Build it from the same [[ZioHttpConfig]] that is passed to `ZioHttp.routes`, so handlers and
  * Live routes sign and check the same values. See [Share security with HTTP
  * handlers](https://scalive.dev/guides/configuration#share-security-with-http-handlers).
  *
  * @param config
  *   The transport configuration whose secret, token lifetime, and cookie policy the helpers use.
  */
final class LiveSecurity private (val config: ZioHttpConfig):
  /** The cookie policy for application cookies, with `Secure` taken from `config.secureCookie`. */
  val cookies: CookiePolicy = CookiePolicy(config.secureCookie)

  /** The CSRF check for ordinary form submissions. */
  val csrf: CsrfProtection = CsrfProtection(config)

  /** The redirects that carry flash messages into the next rendered Live route. */
  val flash: HttpFlash = HttpFlash(config, cookies)

/** The constructor for [[LiveSecurity]]. */
object LiveSecurity:
  /** Creates the HTTP security helpers for `config`.
    *
    * Pass the same value that is passed to `ZioHttp.routes`.
    */
  def apply(config: ZioHttpConfig): LiveSecurity = new LiveSecurity(config)

/** Browser-bound double-submit CSRF validation for ordinary forms. */
final class CsrfProtection private (config: ZioHttpConfig):
  import CsrfProtection.*

  /** Checks that the form's CSRF token matches the request's CSRF cookie.
    *
    * The form must contain exactly one [[CsrfProtection.ParamName]] field, and the request must
    * carry the [[CsrfProtection.CookieName]] cookie. Both must be signed with this configuration's
    * secret, be no older than `sessionMaxAge`, and belong to the same browser. Live routes set the
    * cookie, and POST forms rendered with `Form.http` through those routes carry the token.
    */
  def validate(request: Request, data: FormData): IO[ValidationError, Unit] =
    data.values(ParamName) match
      case Vector()                                        => ZIO.fail(ValidationError.MissingToken)
      case Vector(token) if token.length <= MaxTokenLength => validateToken(request, token)
      case Vector(_)                                       => ZIO.fail(ValidationError.InvalidToken)
      case _ => ZIO.fail(ValidationError.DuplicateToken)

  private def validateToken(request: Request, token: String): IO[ValidationError, Unit] =
    request.cookie(CookieName) match
      case None         => ZIO.fail(ValidationError.MissingCookie)
      case Some(cookie) =>
        ZioHttpSecurity
          .refreshCsrf(config, cookie.content).mapError(_ => ValidationError.InvalidCookie) *>
          ZioHttpSecurity
            .verifyCsrf(config, token, cookie.content)
            .mapError(_ => ValidationError.InvalidToken).unit

/** The names and validation errors used by [[CsrfProtection]]. */
object CsrfProtection:
  /** The name of the cookie that binds CSRF tokens to one browser, `_scalive_csrf`. */
  val CookieName = "_scalive_csrf"

  /** The name of the form field that carries the CSRF token, `_csrf_token`. */
  val ParamName = "_csrf_token"

  /** The `name` of the `<meta>` element in which Scalive renders the CSRF token, `csrf-token`. */
  val MetaName = "csrf-token"

  private val MaxTokenLength = 4096

  /** Why [[CsrfProtection.validate]] rejected a request. */
  enum ValidationError:
    /** The request has no CSRF cookie. */
    case MissingCookie

    /** The CSRF cookie is malformed, signed with another secret, or expired. */
    case InvalidCookie

    /** The form has no CSRF token field. */
    case MissingToken

    /** The form has more than one CSRF token field. */
    case DuplicateToken

    /** The token is too long, malformed, signed with another secret, expired, or issued for another
      * browser's cookie.
      */
    case InvalidToken

  private[scalive] def apply(config: ZioHttpConfig): CsrfProtection =
    new CsrfProtection(config)
end CsrfProtection

/** Sends signed flash messages from an HTTP redirect into the next rendered Live route. */
final class HttpFlash private (config: ZioHttpConfig, cookies: CookiePolicy):
  /** Returns a 303 redirect to `to` that carries `values` as flash messages.
    *
    * The messages travel in a signed cookie that expires after 60 seconds. The next successful
    * disconnected render of a Live route moves them into that LiveView's flash and expires the
    * cookie. When a kind appears more than once, its last message wins; with no values, the
    * response sets no cookie. Flash values are signed, not encrypted, so never put secrets in them.
    */
  def seeOther(to: LiveLocation, values: (FlashKind, String)*): UIO[Response] =
    addCookie(Response.seeOther(to.url), values)

  /** Returns a 303 redirect to an unchecked URL that carries `values` as flash messages.
    *
    * Scalive does not validate `to`, so use this only for a URL validated independently. Prefer
    * [[seeOther]], which takes a typed `LiveLocation`. The messages behave as with `seeOther`.
    */
  def seeOtherUnsafe(to: URL, values: (FlashKind, String)*): UIO[Response] =
    addCookie(Response.seeOther(to), values)

  private def addCookie(
    response: Response,
    values: Seq[(FlashKind, String)]
  ): UIO[Response] =
    val encoded = values.iterator.map((kind, message) => kind.value -> message).toMap
    ZioHttpSecurity.issueFlash(config, encoded).map {
      case Some(token) =>
        response.addCookie(
          cookies.make(HttpFlash.CookieName, token, maxAge = Some(zio.Duration.fromSeconds(60)))
        )
      case None => response
    }
end HttpFlash

/** The cookie name used by [[HttpFlash]]. */
object HttpFlash:
  /** The name of the cookie that carries signed flash messages, `__phoenix_flash__`. */
  val CookieName = "__phoenix_flash__"

  private[scalive] def apply(config: ZioHttpConfig, cookies: CookiePolicy): HttpFlash =
    new HttpFlash(config, cookies)
