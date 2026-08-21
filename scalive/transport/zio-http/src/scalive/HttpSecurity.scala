package scalive

import zio.*
import zio.http.*

/** Attributes shared by framework and application cookies. */
final case class CookiePolicy(secure: Boolean):
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

  def expire(name: String): Cookie.Response =
    make(name, "", maxAge = Some(zio.Duration.Zero))

/** Shared signing and cookie policy for Live transport and ordinary HTTP handlers. */
final class LiveSecurity private (val config: ZioHttpConfig):
  val cookies: CookiePolicy = CookiePolicy(config.secureCookie)
  val csrf: CsrfProtection  = CsrfProtection(config)
  val flash: HttpFlash      = HttpFlash(config, cookies)

object LiveSecurity:
  def apply(config: ZioHttpConfig): LiveSecurity = new LiveSecurity(config)

/** Browser-bound double-submit CSRF validation for ordinary forms. */
final class CsrfProtection private (config: ZioHttpConfig):
  import CsrfProtection.*

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

object CsrfProtection:
  val CookieName = "_scalive_csrf"
  val ParamName  = "_csrf_token"
  val MetaName   = "csrf-token"

  private val MaxTokenLength = 4096

  enum ValidationError:
    case MissingCookie
    case InvalidCookie
    case MissingToken
    case DuplicateToken
    case InvalidToken

  private[scalive] def apply(config: ZioHttpConfig): CsrfProtection =
    new CsrfProtection(config)

/** Sends signed flash messages from an HTTP redirect into the next rendered Live route. */
final class HttpFlash private (config: ZioHttpConfig, cookies: CookiePolicy):
  def seeOther(to: LiveLocation, values: (FlashKind, String)*): UIO[Response] =
    addCookie(Response.seeOther(to.url), values)

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

object HttpFlash:
  val CookieName = "__phoenix_flash__"

  private[scalive] def apply(config: ZioHttpConfig, cookies: CookiePolicy): HttpFlash =
    new HttpFlash(config, cookies)
