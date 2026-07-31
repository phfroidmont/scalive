package scalive.examples.auth

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.examples.ExamplesRoutes
import scalive.testing.*

object AuthFlowSpec extends ZIOSpecDefault:
  private val security = LiveSecurity(
    TokenConfig("auth-flow-spec-secret", 1.hour),
    CookiePolicy(secure = true)
  )

  private val liveRoutes =
    scalive.Live.router
      .withSecurity(security)(
        ExamplesRoutes.login(LoginLiveView()),
        scalive.Live
          .session("authenticated").withMountAspect(AuthMountAspect.authenticated)(
            ExamplesRoutes.profile { (_, _, currentSession: CurrentSession) =>
              ProfileLiveView(currentSession)
            }
          )
      )

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def responseCookie(response: Response, name: String): Option[Cookie.Response] =
    response.headers(Header.SetCookie).map(_.value).find(_.name == name)

  private def requestCookie(cookie: Cookie.Response): Cookie.Request =
    Cookie.Request(cookie.name, cookie.content)

  private def postForm(action: FormAction, fields: (String, String)*): Request =
    Request.post(
      url(action.href),
      Body.fromURLEncodedForm(zio.http.Form.fromStrings(fields*))
    )

  private def runHttp(auth: AuthService, request: Request): UIO[Response] =
    ZIO
      .scoped(AuthHttpRoutes(security).routes.runZIO(request))
      .provideEnvironment(ZEnvironment(auth))

  private def render(auth: AuthService, request: Request) =
    DisconnectedRender
      .run(liveRoutes, request)
      .provideEnvironment(ZEnvironment(auth))

  def spec = suite("AuthFlowSpec")(
    test("logs in, mounts the protected view, and logs out through rendered forms") {
      val sessionAction = FormAction.from(AuthHttpRoutes.SessionRoute)
      val logoutAction  = FormAction.from(AuthHttpRoutes.LogoutRoute)

      for
        auth <- ZIO.service[AuthService].provide(AuthService.live)
        loginPage <- render(
                       auth,
                       Request.get(url(ExamplesRoutes.login.location.href))
                     )
        loginForm <- ZIO.fromEither(
                       loginPage.form(
                         FormQuery(Some(sessionAction.href), Some(Method.POST))
                       )
                     ).orDieWith(error => new AssertionError(error.toString))
        csrfCookie <- ZIO
                        .fromOption(
                          responseCookie(loginPage.response, CsrfProtection.CookieName)
                        ).orElseFail(new AssertionError("missing rendered CSRF cookie"))
        csrfToken <- ZIO
                       .fromOption(loginForm.values(CsrfProtection.ParamName).headOption)
                       .orElseFail(new AssertionError("missing rendered CSRF token"))
        loginResponse <- runHttp(
                           auth,
                           postForm(
                             sessionAction,
                             CsrfProtection.ParamName -> csrfToken,
                             LoginForm.Email.name      -> "alice@example.com",
                             LoginForm.Password.name   -> "scalive"
                           ).addCookie(requestCookie(csrfCookie))
                         )
        sessionCookie <- ZIO
                           .fromOption(
                             responseCookie(
                               loginResponse,
                               AuthHttpRoutes.SessionCookieName
                             )
                           ).orElseFail(new AssertionError("missing session cookie"))
        currentSession <- auth
                            .authenticate(SessionCookieToken(sessionCookie.content))
                            .someOrFail(new AssertionError("session was not created"))
        profilePage <- render(
                         auth,
                         Request
                           .get(url(ExamplesRoutes.profile.location.href))
                           .addCookie(requestCookie(csrfCookie))
                           .addCookie(requestCookie(sessionCookie))
                       )
        logoutForm <- ZIO.fromEither(
                        profilePage.form(
                          FormQuery(Some(logoutAction.href), Some(Method.POST))
                        )
                      ).orDieWith(error => new AssertionError(error.toString))
        logoutCsrf <- ZIO
                        .fromOption(logoutForm.values(CsrfProtection.ParamName).headOption)
                        .orElseFail(new AssertionError("missing logout CSRF token"))
        logoutResponse <- runHttp(
                            auth,
                            postForm(
                              logoutAction,
                              CsrfProtection.ParamName -> logoutCsrf
                            ).addCookie(requestCookie(csrfCookie))
                              .addCookie(requestCookie(sessionCookie))
                          )
        authenticated <- auth.authenticate(SessionCookieToken(sessionCookie.content))
        resumed       <- auth.resume(currentSession.publicSessionId)
        stalePage <- render(
                       auth,
                       Request
                         .get(url(ExamplesRoutes.profile.location.href))
                         .addCookie(requestCookie(sessionCookie))
                     )
        expiredCookie = responseCookie(logoutResponse, AuthHttpRoutes.SessionCookieName)
      yield assertTrue(
        loginResponse.status == Status.SeeOther,
        profilePage.response.status == Status.Ok,
        profilePage.text.contains("Welcome, Alice"),
        logoutResponse.status == Status.SeeOther,
        expiredCookie.exists(_.maxAge.contains(zio.Duration.Zero)),
        authenticated.isEmpty,
        resumed.isEmpty,
        stalePage.response.status == Status.SeeOther,
        stalePage.response.header(Header.Location).exists(
          _.url.encode == ExamplesRoutes.login.location.href
        )
      )
    }
  )
end AuthFlowSpec
