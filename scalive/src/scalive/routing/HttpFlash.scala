package scalive

import scala.concurrent.duration.*

import zio.http.*

final class HttpFlash private[scalive] (
  tokenConfig: TokenConfig,
  cookies: CookiePolicy):

  def seeOther(to: LiveLocation, values: (FlashKind, String)*): Response =
    addCookie(Response.seeOther(to.url), toValues(values))

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
