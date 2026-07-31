package scalive.examples.auth

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.examples.ExamplesRoutes
import scalive.testing.*

object LoginLiveViewSpec extends ZIOSpecDefault:

  private val InvalidLoginMessage = LoginLiveView.InvalidLoginMessage
  private val SessionAction       = FormAction.from(AuthHttpRoutes.SessionRoute)
  private val security = LiveSecurity(
    TokenConfig("login-live-view-spec-secret", 1.hour),
    secureCookies = true
  )
  private val csrfProtection = security.csrf

  private val routes =
    scalive.Live.router
      .withSecurity(security)(ExamplesRoutes.login(LoginLiveView()))

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def render(flashCookie: Option[Cookie.Response] = None) =
    val request = flashCookie.fold(Request.get(url(ExamplesRoutes.login.location.href)))(cookie =>
      Request
        .get(url(ExamplesRoutes.login.location.href))
        .addCookie(Cookie.Request(cookie.name, cookie.content))
    )
    DisconnectedRender.run(routes, request)

  def spec = suite("LoginLiveViewSpec")(
    test("renders a directly usable CSRF-protected login form") {
      for
        page <- render()
        renderedForm <- ZIO
                          .fromEither(
                            page.form(
                              FormQuery(
                                action = Some(SessionAction.href),
                                method = Some(Method.POST)
                              )
                            )
                          ).orDieWith(error => new AssertionError(error.toString))
        csrfCookie = page.response
                       .headers(Header.SetCookie).map(_.value)
                       .find(_.name == CsrfProtection.CookieName)
        fieldsByName = renderedForm.fields.map(field => field.name -> field).toMap
        csrfToken     = renderedForm.values(CsrfProtection.ParamName).headOption
        validation = (csrfCookie, csrfToken) match
                       case (Some(cookie), Some(token)) =>
                         val request = Request.get(URL.root).addCookie(
                           Cookie.Request(cookie.name, cookie.content)
                         )
                         csrfProtection
                           .validate(
                             request,
                             FormData(Vector(CsrfProtection.ParamName -> token))
                           ).left.map(_ => ())
                       case _ => Left(())
      yield assertTrue(
        page.response.status == Status.Ok,
        !page.text.contains(InvalidLoginMessage),
        renderedForm.id.contains(LoginForm.FormId),
        renderedForm.names == Vector(
          CsrfProtection.ParamName,
          LoginForm.EmailPath.name,
          LoginForm.PasswordPath.name
        ),
        validation.isRight,
        csrfCookie.exists(_.isSecure),
        renderedForm.values(LoginForm.EmailPath) == Vector("alice@example.com"),
        renderedForm.values(LoginForm.PasswordPath) == Vector(""),
        fieldsByName.get(LoginForm.EmailPath.name).exists(field =>
          field.id.contains(LoginForm.EmailId) &&
            field.inputType.contains("email") && field.required
        ),
        fieldsByName.get(LoginForm.PasswordPath.name).exists(field =>
          field.id.contains(LoginForm.PasswordId) &&
            field.inputType.contains("password") && field.required
        ),
        !renderedForm.hasChangeBinding,
        !renderedForm.hasSubmitBinding,
        !renderedForm.triggersAction
      )
    },
    test("renders and consumes an invalid-login flash from an HTTP redirect") {
      val redirect = security.flash.seeOther(
        ExamplesRoutes.login.location,
        LoginLiveView.InvalidLoginFlash -> InvalidLoginMessage
      )
      val cookie = redirect
        .headers(Header.SetCookie).map(_.value)
        .find(_.name == FlashToken.CookieName)

      for page <- render(cookie)
      yield assertTrue(
        page.response.status == Status.Ok,
        page.text.contains(InvalidLoginMessage),
        page.response
          .headers(Header.SetCookie).map(_.value)
          .find(_.name == FlashToken.CookieName)
          .exists(_.maxAge.contains(zio.Duration.Zero))
      )
    }
  )
end LoginLiveViewSpec
