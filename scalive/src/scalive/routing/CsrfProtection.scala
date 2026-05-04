package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

import zio.http.Cookie
import zio.http.Path
import zio.http.Request
import zio.http.Response

import scalive.Mod.Attr
import scalive.Mod.Content

private[scalive] object CsrfProtection:
  val CookieName = "_scalive_csrf"
  val ParamName  = "_csrf_token"
  val MetaName   = "csrf-token"

  private val CookieTokenId = "csrf:cookie"
  private val ParamTokenId  = "csrf:param"
  private val RandomBytes   = 32
  private val secureRandom  = new SecureRandom()

  final case class RenderToken(value: String, cookie: Option[Cookie.Response])

  def prepare(config: TokenConfig, request: Request): RenderToken =
    val existingSecret = request.cookie(CookieName).flatMap(cookieSecret(config, _))
    val secret         = existingSecret.getOrElse(newSecret())
    RenderToken(
      value = Token.sign[String](config.secret, ParamTokenId, secret),
      cookie = Option.when(existingSecret.isEmpty)(responseCookie(config, secret))
    )

  def validate(config: TokenConfig, request: Request): Boolean =
    val verified = for
      cookie       <- request.cookie(CookieName)
      cookieSecret <- cookieSecret(config, cookie)
      param        <- request.queryParam(ParamName)
      paramSecret  <- paramSecret(config, param)
    yield constantTimeEquals(cookieSecret, paramSecret)

    verified.contains(true)

  def addCookie(response: Response, cookie: Option[Cookie.Response]): Response =
    cookie.fold(response)(response.addCookie)

  def injectMeta[Msg](document: HtmlElement[Msg], token: String): HtmlElement[Msg] =
    transform(document, token)

  private def transform[Msg](element: HtmlElement[Msg], token: String): HtmlElement[Msg] =
    if element.tag.name == "head" then withCsrfMeta(element, token)
    else HtmlElement(element.tag, element.mods.map(transformMod(_, token)))

  private def transformMod[Msg](mod: Mod[Msg], token: String): Mod[Msg] =
    mod match
      case Content.Tag(element)            => Content.Tag(transform(element, token))
      case Content.Component(cid, element) => Content.Component(cid, transform(element, token))
      case Content.Keyed(entries, stream, allEntries) =>
        val transformedEntries =
          entries.map(entry => entry.copy(element = transform(entry.element, token)))
        val transformedAllEntries = allEntries.map(
          _.map(entry => entry.copy(element = transform(entry.element, token)))
        )
        Content.Keyed(transformedEntries, stream, transformedAllEntries)
      case other => other

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

  private def responseCookie(config: TokenConfig, secret: String): Cookie.Response =
    Cookie.Response(
      CookieName,
      Token.sign[String](config.secret, CookieTokenId, secret),
      path = Some(Path.root),
      isHttpOnly = true,
      maxAge = Some(zio.Duration.fromMillis(config.maxAge.toMillis)),
      sameSite = Some(Cookie.SameSite.Lax)
    )

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
