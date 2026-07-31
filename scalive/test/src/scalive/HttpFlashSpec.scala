package scalive

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.test.*

object HttpFlashSpec extends ZIOSpecDefault:
  private val Info        = FlashKind("info")
  private val TargetRoute = scalive.live / "target"
  private val SourceRoute = scalive.live / "source"
  private val tokenConfig = TokenConfig("http-flash-spec-secret", 1.hour)
  private val security    = LiveSecurity(tokenConfig, CookiePolicy(secure = true))

  private val target = new LiveView.Eventless[Unit]:
    def mount(ctx: MountContext) = ZIO.unit

    def render(model: Unit): HtmlElement[Nothing] =
      div(flash(Info)(message => p(idAttr := "flash", message)))

  private val source = new LiveView.Eventless[Unit]:
    def mount(ctx: MountContext) =
      ctx.nav.redirect(TargetRoute.location)

    def render(model: Unit): HtmlElement[Nothing] = div("source")

  private val routes = scalive.Live.router.withSecurity(security)(
    SourceRoute(source),
    TargetRoute(target)
  )

  private def run(request: Request): Task[Response] =
    ZIO.scoped(routes.runZIO(request))

  private def cookies(response: Response): Chunk[Cookie.Response] =
    response
      .rawHeaders("set-cookie")
      .flatMap(Cookie.Response.decode(_).toOption)

  private def flashCookie(response: Response): Option[Cookie.Response] =
    cookies(response).find(_.name == FlashToken.CookieName)

  private def request(path: String, cookie: Cookie.Response): Request =
    Request
      .get(URL.decode(path).fold(throw _, identity))
      .addCookie(Cookie.Request(cookie.name, cookie.content))

  override def spec = suite("HttpFlashSpec")(
    test("creates a typed See Other response with a hardened signed flash cookie") {
      val response = security.flash.seeOther(
        TargetRoute.location,
        Info -> "Saved"
      )
      val cookie = flashCookie(response)

      assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url.encode == TargetRoute.location.href),
        cookie.flatMap(value => FlashToken.decode(tokenConfig, value.content)).contains(
          Map("info" -> "Saved")
        ),
        cookie.exists(_.path.contains(Path.root)),
        cookie.exists(_.isSecure),
        cookie.exists(_.isHttpOnly),
        cookie.exists(_.sameSite.contains(Cookie.SameSite.Lax)),
        cookie.exists(_.maxAge.exists(_.toSeconds <= 60))
      )
    },
    test("preserves HTTP flash across redirects and consumes it on the next render") {
      val initial = security.flash.seeOther(TargetRoute.location, Info -> "Saved")
      val cookie  = flashCookie(initial).get

      for
        redirected <- run(request("/source", cookie))
        rendered   <- run(request("/target", cookie))
        body       <- rendered.body.asString
        expired = flashCookie(rendered)
        withoutFlash <- run(Request.get(URL.decode("/target").fold(throw _, identity)))
        nextBody     <- withoutFlash.body.asString
      yield assertTrue(
        redirected.status.isRedirection,
        redirected.rawHeader("location").contains("/target"),
        flashCookie(redirected).isEmpty,
        body.contains("Saved"),
        expired.exists(_.content.isEmpty),
        expired.exists(_.maxAge.contains(zio.Duration.Zero)),
        expired.exists(_.isSecure),
        expired.exists(_.isHttpOnly),
        expired.exists(_.sameSite.contains(Cookie.SameSite.Lax)),
        !nextBody.contains("Saved")
      )
    },
    test("rejects wrong-purpose flash cookies and cleans them up") {
      val wrongPurpose = Token.sign(tokenConfig.secret, "session", Map("info" -> "Forged"))
      val cookie = Cookie.Response(
        FlashToken.CookieName,
        wrongPurpose,
        path = Some(Path.root)
      )

      for
        response <- run(request("/target", cookie))
        body     <- response.body.asString
        expired = flashCookie(response)
      yield assertTrue(
        !body.contains("Forged"),
        expired.exists(_.content.isEmpty),
        expired.exists(_.maxAge.contains(zio.Duration.Zero))
      )
    },
    test("supports an explicit URL escape hatch") {
      val url      = URL.decode("/dead?from=http").fold(throw _, identity)
      val response = security.flash.seeOtherUnsafe(url, Info -> "Saved")

      assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url == url),
        flashCookie(response).isDefined
      )
    }
  )
end HttpFlashSpec
