package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

import zio.http.Cookie
import zio.http.Request
import zio.http.Response

import scalive.Mod.Attr
import scalive.Mod.Content

/** Browser-bound double-submit CSRF protection for ordinary forms and Live socket joins.
  *
  * A disconnected Live render reuses the random browser secret from a valid existing cookie or
  * creates one and stores it in a purpose-bound signed `HttpOnly` cookie. It renders the same
  * secret in a separately purpose-bound signed token, injecting a `csrf-token` meta element into
  * any rendered `head` and a hidden field into checked POST forms built with
  * `Form.http(FormAction.from(...))`. Connected renders retain the verified join token for newly
  * rendered checked forms. GET forms, manually authored forms, and `FormAction.unsafe` are not
  * automatically protected. Injection does not deduplicate a manually supplied `_csrf_token` field
  * inside a checked form; do not add that field yourself because validation rejects duplicates.
  *
  * Ordinary-form validation requires exactly one bounded submitted token, verifies both signatures
  * and purposes, and compares their browser secrets in constant time. Live socket joins validate
  * the bounded token selected from the connection query. Tokens are reusable until
  * [[TokenConfig.maxAge]]; they are not one-time tokens and there is no server-side replay store.
  * Values are signed, not encrypted, and their representation is not a stable public wire format.
  *
  * This mechanism binds a submission to its cookie, but it does not parse the request body, check
  * the HTTP method, validate `Origin` or `Referer`, bind a token to a route or authenticated user,
  * or defend against same-origin script injection. Use [[HttpFormDecoder]] for bounded form parsing
  * plus this validation, and perform application authorization separately.
  */
final class CsrfProtection private[scalive] (
  tokenConfig: TokenConfig,
  cookies: CookiePolicy):
  import CsrfProtection.*

  /** Validates an already-decoded ordinary form against the request's CSRF cookie.
    *
    * The form must contain exactly one [[CsrfProtection.ParamName]] value within the framework's
    * size bound. The request must contain a valid [[CsrfProtection.CookieName]] cookie signed by
    * the same `LiveSecurity`, and both values must carry the same browser secret. A successful
    * result is reusable and does not consume either value.
    *
    * This method does not decode or size-limit the request body as a whole; prefer
    * [[HttpFormDecoder]] for ordinary URL-encoded handlers.
    */
  def validate(request: Request, data: FormData): Either[ValidationError, Unit] =
    data.values(ParamName) match
      case Vector()                                        => Left(ValidationError.MissingToken)
      case Vector(token) if token.length <= MaxTokenLength => validateToken(request, token)
      case Vector(_)                                       => Left(ValidationError.InvalidToken)
      case _                                               => Left(ValidationError.DuplicateToken)

  private[scalive] def prepare(request: Request): RenderToken =
    val existingSecret = request.cookie(CookieName).flatMap(cookieSecret(tokenConfig, _))
    val secret         = existingSecret.getOrElse(newSecret())
    RenderToken(
      value = Token.sign[String](tokenConfig.secret, ParamTokenId, secret),
      cookie = Option.when(existingSecret.isEmpty)(responseCookie(secret))
    )

  private[scalive] def validateWebSocket(request: Request): Option[String] =
    request
      .queryParam(ParamName)
      .filter(_.length <= MaxTokenLength)
      .filter(token => validateToken(request, token).isRight)

  private def validateToken(
    request: Request,
    token: String
  ): Either[ValidationError, Unit] =
    val verified = for
      cookie       <- request.cookie(CookieName).toRight(ValidationError.MissingCookie)
      cookieSecret <- cookieSecret(tokenConfig, cookie).toRight(ValidationError.InvalidCookie)
      paramSecret  <- paramSecret(tokenConfig, token).toRight(ValidationError.InvalidToken)
      _            <- Either.cond(
             constantTimeEquals(cookieSecret, paramSecret),
             (),
             ValidationError.InvalidToken
           )
    yield ()

    verified

  private def responseCookie(secret: String): Cookie.Response =
    cookies.make(
      CookieName,
      Token.sign[String](tokenConfig.secret, CookieTokenId, secret),
      maxAge = Some(zio.Duration.fromMillis(tokenConfig.maxAge.toMillis))
    )
end CsrfProtection

/** Public names and validation failures for [[CsrfProtection]]. */
object CsrfProtection:
  /** Name of the signed, `HttpOnly` browser-secret cookie. */
  val CookieName = "_scalive_csrf"

  /** Name of the hidden form field and Live socket query parameter carrying the render token. */
  val ParamName = "_csrf_token"

  /** Value of the rendered CSRF meta element's `name` attribute. */
  val MetaName = "csrf-token"

  private[scalive] val MarkerName = "data-scalive-csrf"

  private val CookieTokenId  = "csrf:cookie"
  private val ParamTokenId   = "csrf:param"
  private val RandomBytes    = 32
  private val MaxTokenLength = 1024
  private val secureRandom   = new SecureRandom()

  /** Why ordinary form CSRF validation was rejected.
    *
    * These categories are suitable for server-side handling. Avoid returning signature details or
    * supplied token values to an untrusted client.
    */
  enum ValidationError:
    /** The request has no [[CookieName]] cookie. */
    case MissingCookie

    /** The CSRF cookie is malformed, expired, signed by another secret, or has the wrong purpose.
      */
    case InvalidCookie

    /** The decoded form has no [[ParamName]] value. */
    case MissingToken

    /** The decoded form has more than one [[ParamName]] value. */
    case DuplicateToken

    /** The single submitted token is oversized, invalid, expired, or does not match the cookie. */
    case InvalidToken

  final private[scalive] case class RenderToken(
    value: String,
    cookie: Option[Cookie.Response])

  private[scalive] def addCookie(
    response: Response,
    cookie: Option[Cookie.Response]
  ): Response =
    cookie.fold(response)(response.addCookie)

  private[scalive] def inject[Msg](
    document: HtmlElement[Msg],
    token: String
  ): HtmlElement[Msg] =
    transform(document, token, injectMeta = true)

  private[scalive] def injectForms[Msg](
    root: HtmlElement[Msg],
    token: String
  ): HtmlElement[Msg] =
    transform(root, token, injectMeta = false)

  private def transform[Msg](
    element: HtmlElement[Msg],
    token: String,
    injectMeta: Boolean
  ): HtmlElement[Msg] =
    val transformed = HtmlElement(
      element.tag,
      element.mods.map(transformMod(_, token, injectMeta))
    )
    val withFormToken =
      if isProtectedForm(transformed) then protectForm(transformed, token)
      else transformed

    if injectMeta && withFormToken.tag.name == "head" then withCsrfMeta(withFormToken, token)
    else withFormToken

  private def transformMod[Msg](
    mod: Mod[Msg],
    token: String,
    injectMeta: Boolean
  ): Mod[Msg] =
    mod match
      case Content.Tag(element) =>
        Content.Tag(transform(element, token, injectMeta))
      case Content.Component(cid, element) =>
        Content.Component(cid, transform(element, token, injectMeta))
      case Content.Keyed(entries, stream, allEntries) =>
        val transformedEntries =
          entries.map(entry => entry.copy(element = transform(entry.element, token, injectMeta)))
        val transformedAllEntries = allEntries.map(
          _.map(entry => entry.copy(element = transform(entry.element, token, injectMeta)))
        )
        Content.Keyed(transformedEntries, stream, transformedAllEntries)
      case Content.SignalChoice(value, branches) =>
        Content.SignalChoice(
          value,
          branches.map((key, element) => key -> transform(element, token, injectMeta))
        )
      case Content.SignalOption(value, project) =>
        Content.SignalOption(value, signal => transform(project(signal), token, injectMeta))
      case Content.SignalKeyed(values, key, project) =>
        Content.SignalKeyed(
          values,
          key,
          (entryKey, signal) => transform(project(entryKey, signal), token, injectMeta)
        )
      case Content.SignalKeyedByIndex(values, project) =>
        Content.SignalKeyedByIndex(
          values,
          (index, signal) => transform(project(index, signal), token, injectMeta)
        )
      case Content.SignalStream(value, project) =>
        Content.SignalStream(
          value,
          (domId, signal) => transform(project(domId, signal), token, injectMeta)
        )
      case other => other

  private def isProtectedForm(element: HtmlElement[?]): Boolean =
    element.tag.name == "form" && element.attrMods.exists {
      case Attr.Static(MarkerName, "true") => true
      case _                               => false
    }

  private def protectForm[Msg](form: HtmlElement[Msg], token: String): HtmlElement[Msg] =
    val hidden = input(
      typ      := "hidden",
      nameAttr := ParamName,
      value    := token
    )
    HtmlElement(
      form.tag,
      form.mods
        .filterNot {
          case Attr.Static(MarkerName, _) => true
          case _                          => false
        }.prepended(Content.Tag(hidden))
    )

  private def withCsrfMeta[Msg](head: HtmlElement[Msg], token: String): HtmlElement[Msg] =
    val meta = metaTag(nameAttr := MetaName, contentAttr := token)
    HtmlElement(
      head.tag,
      head.mods
        .filterNot {
          case Content.Tag(element) => isCsrfMeta(element)
          case _                    => false
        }.prepended(Content.Tag(meta))
    )

  private def isCsrfMeta(element: HtmlElement[?]): Boolean =
    element.tag.name == "meta" && element.attrMods.exists {
      case Attr.Static("name", MetaName) => true
      case _                             => false
    }

  private def cookieSecret(config: TokenConfig, cookie: Cookie): Option[String] =
    Token
      .verify[String](config.secret, cookie.content, config.maxAge)
      .toOption
      .collect { case (`CookieTokenId`, secret) if secret.nonEmpty => secret }

  private def paramSecret(config: TokenConfig, token: String): Option[String] =
    Token
      .verify[String](config.secret, token, config.maxAge)
      .toOption
      .collect { case (`ParamTokenId`, secret) if secret.nonEmpty => secret }

  private def newSecret(): String =
    val bytes = Array.ofDim[Byte](RandomBytes)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.UTF_8),
      right.getBytes(StandardCharsets.UTF_8)
    )
end CsrfProtection
