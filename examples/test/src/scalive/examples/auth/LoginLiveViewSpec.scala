package scalive.examples.auth

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.examples.ExamplesRoutes
import scalive.testing.*

object LoginLiveViewSpec extends ZIOSpecDefault:

  private val InvalidLoginMessage = "The sign-in request was invalid. Please try again."
  private val SessionAction       = FormAction.from(AuthHttpRoutes.SessionRoute)

  private val routes =
    scalive.Live.router(
      scalive.Live.session("login")(
        ExamplesRoutes.login.withMountAspect(LoginMountAspect.prepared) { (_, _, loginContext) =>
          LoginLiveView(loginContext)
        }
      )
    )

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def render(
    authService: AuthService,
    cookieToken: LoginContextCookieToken,
    invalid: Boolean
  ) =
    val location = ExamplesRoutes.login.location(Option.when(invalid)(true))
    val request = Request
      .get(url(location.href)).addCookie(
        Cookie.Request(AuthHttpRoutes.LoginContextCookieName, cookieToken.value)
      )

    DisconnectedRender.run(routes, request).provideEnvironment(ZEnvironment(authService))

  def spec = suite("LoginLiveViewSpec")(
    test("renders the ordinary login form with stable typed fields") {
      for
        authService <- ZIO.service[AuthService]
        bootstrap   <- authService.beginLogin
        loginContext <- authService
                          .prepareLogin(bootstrap.cookieToken)
                          .someOrFail(new IllegalStateException("login context was not prepared"))
        page         <- render(authService, bootstrap.cookieToken, invalid = false)
        renderedForm <- ZIO
                          .fromEither(
                            page.form(
                              FormQuery(
                                action = Some(SessionAction.href),
                                method = Some(Method.POST)
                              )
                            )
                          ).orDieWith(error => new AssertionError(error.toString))
        fieldsByName = renderedForm.fields.map(field => field.name -> field).toMap
      yield assertTrue(
        page.response.status == Status.Ok,
        !page.text.contains(InvalidLoginMessage),
        renderedForm.id.contains(LoginForm.FormId),
        renderedForm.names == Vector(
          LoginForm.CsrfPath.name,
          LoginForm.EmailPath.name,
          LoginForm.PasswordPath.name
        ),
        renderedForm.values(LoginForm.CsrfPath) == Vector(loginContext.csrfToken.value),
        renderedForm.values(LoginForm.EmailPath) == Vector("alice@example.com"),
        renderedForm.values(LoginForm.PasswordPath) == Vector(""),
        fieldsByName.get(LoginForm.CsrfPath.name).exists(_.id.contains(LoginForm.CsrfId)),
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
    test("renders the invalid-login notice from typed route params") {
      for
        authService <- ZIO.service[AuthService]
        bootstrap   <- authService.beginLogin
        page         <- render(authService, bootstrap.cookieToken, invalid = true)
      yield assertTrue(
        page.response.status == Status.Ok,
        page.text.contains(InvalidLoginMessage)
      )
    }
  ).provide(AuthService.live(AuthServiceConfig.default))
end LoginLiveViewSpec
