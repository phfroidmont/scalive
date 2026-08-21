package scalive

import java.time.Duration

import zio.*
import zio.http.*
import zio.test.*

object HttpSecuritySpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false
  ).toOption.get

  private val security = LiveSecurity(config)

  override def spec = suite("HTTP security facade")(
    test("validates the transport-issued browser and render CSRF pair") {
      for
        csrf <- ZioHttpSecurity.issueCsrf(config)
        request = Request
                    .post(URL.root, Body.empty)
                    .addCookie(Cookie.Request(CsrfProtection.CookieName, csrf.cookieToken))
        valid <- security.csrf
                   .validate(
                     request,
                     FormData(Vector(CsrfProtection.ParamName -> csrf.token))
                   ).either
        missing <- security.csrf.validate(request, FormData.empty).either
      yield assertTrue(
        valid == Right(()),
        missing == Left(CsrfProtection.ValidationError.MissingToken),
        !security.cookies.secure
      )
    },
    test("rejects malformed, duplicate, and cookie-less CSRF submissions") {
      for
        csrf <- ZioHttpSecurity.issueCsrf(config)
        request = Request
                    .post(URL.root, Body.empty)
                    .addCookie(Cookie.Request(CsrfProtection.CookieName, csrf.cookieToken))
        malformed <- security.csrf
                       .validate(request, FormData(Vector(CsrfProtection.ParamName -> "bad"))).either
        duplicate <- security.csrf
                       .validate(
                         request,
                         FormData(
                           Vector(
                             CsrfProtection.ParamName -> csrf.token,
                             CsrfProtection.ParamName -> csrf.token
                           )
                         )
                       ).either
        noCookie <- security.csrf
                      .validate(
                        Request.post(URL.root, Body.empty),
                        FormData(Vector(CsrfProtection.ParamName -> csrf.token))
                      ).either
      yield assertTrue(
        malformed == Left(CsrfProtection.ValidationError.InvalidToken),
        duplicate == Left(CsrfProtection.ValidationError.DuplicateToken),
        noCookie == Left(CsrfProtection.ValidationError.MissingCookie)
      )
    },
    test("issues transport-compatible signed flash redirects") {
      val kind = FlashKind("notice")
      for
        response <- security.flash.seeOther(scalive.live.location, kind -> "saved")
        cookie <- ZIO
                    .fromOption(response.headers(Header.SetCookie).map(_.value)
                      .find(_.name == HttpFlash.CookieName))
                    .orElseFail(new NoSuchElementException("flash cookie"))
        values <- ZioHttpSecurity.verifyFlash(config, cookie.content)
      yield assertTrue(
        response.status == Status.SeeOther,
        values == Map("notice" -> "saved"),
        cookie.isHttpOnly,
        cookie.sameSite.contains(Cookie.SameSite.Lax)
      )
    },
    test("decodes bounded URL-encoded forms only after CSRF validation") {
      val decoder = HttpFormDecoder.urlEncoded(FormCodec.formData, 1024, security.csrf)
      for
        csrf <- ZioHttpSecurity.issueCsrf(config)
        body = Body
                 .fromString(s"${CsrfProtection.ParamName}=${csrf.token}&name=Ada")
                 .contentType(MediaType.application.`x-www-form-urlencoded`)
        request = Request
                    .post(URL.root, body)
                    .addCookie(Cookie.Request(CsrfProtection.CookieName, csrf.cookieToken))
        decoded <- decoder.decode(request)
        rejected <- decoder
                      .decode(Request.post(URL.root, body)).either
      yield assertTrue(
        decoded.get("name").contains("Ada"),
        rejected == Left(
          HttpFormDecoder.Error.Csrf(CsrfProtection.ValidationError.MissingCookie)
        )
      )
    }
  )
end HttpSecuritySpec
