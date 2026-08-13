package scalive

import scala.concurrent.duration.*

import zio.http.*

/** Sends signed flash messages from an HTTP redirect into the next rendered Live route.
  *
  * Flash values are stored in a purpose-bound signed cookie. They are authenticated but not
  * encrypted, so clients can read them and they must not contain secrets. The browser cookie's max
  * age is the smaller of 60 seconds and [[TokenConfig.maxAge]], while signature verification uses
  * `TokenConfig.maxAge`. The 60-second browser policy is therefore not a cryptographic replay bound
  * for a copied cookie value.
  *
  * Redirect responses preserve an existing flash cookie through redirect chains. The next
  * successfully rendered disconnected Live route reads valid values, transfers them into its signed
  * Live session for connected mount, and expires the browser cookie. This is browser-level
  * consume-on-render behavior, not server-side replay prevention: replaying a copied, unexpired
  * cookie can present the values again. Invalid cookies are ignored and expired on a successful
  * render.
  */
final class HttpFlash private[scalive] (
  tokenConfig: TokenConfig,
  cookies: CookiePolicy):

  /** Returns an HTTP 303 redirect to a typed Live location with the supplied flash values.
    *
    * Values are keyed by [[FlashKind]]. If a kind occurs more than once, the last message wins. An
    * empty value list does not add a flash cookie. Browser cookie size limits still apply; this
    * method does not bound message size.
    *
    * @param to
    *   the typed Live route destination
    * @param values
    *   flash kind/message pairs to sign into the response cookie
    */
  def seeOther(to: LiveLocation, values: (FlashKind, String)*): Response =
    addCookie(Response.seeOther(to.url), toValues(values))

  /** Returns an HTTP 303 redirect to an unchecked URL with the supplied flash values.
    *
    * Flash handling and duplicate-kind behavior are the same as [[seeOther]]. This method performs
    * no same-origin or local-path validation; use it only for a trusted or independently validated
    * destination to avoid an open redirect.
    *
    * @param to
    *   the unchecked redirect destination
    * @param values
    *   flash kind/message pairs to sign into the response cookie
    */
  def seeOtherUnsafe(to: URL, values: (FlashKind, String)*): Response =
    addCookie(Response.seeOther(to), toValues(values))

  private[scalive] def fromRequest(request: Request): Map[String, String] =
    request
      .cookie(FlashToken.CookieName)
      .flatMap(cookie => FlashToken.decode(tokenConfig, cookie.content))
      .getOrElse(Map.empty)

  private[scalive] def addCookie(
    response: Response,
    values: Map[String, String]
  ): Response =
    FlashToken
      .encode(tokenConfig, values)
      .fold(response)(token => response.addCookie(responseCookie(token)))

  private[scalive] def clearCookie(response: Response, request: Request): Response =
    if request.cookie(FlashToken.CookieName).isDefined then
      response.addCookie(responseCookie("", zio.Duration.Zero))
    else response

  private def responseCookie(
    content: String,
    maxAge: zio.Duration = flashMaxAge
  ): Cookie.Response =
    cookies.make(
      FlashToken.CookieName,
      content,
      maxAge = Some(maxAge)
    )

  private def flashMaxAge: zio.Duration =
    zio.Duration.fromMillis(math.min(60.seconds.toMillis, tokenConfig.maxAge.toMillis))

  private def toValues(values: Seq[(FlashKind, String)]): Map[String, String] =
    values.iterator.map((kind, message) => kind.value -> message).toMap
end HttpFlash
