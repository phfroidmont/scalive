package scalive.docs.auth

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.docs.*
import scalive.testing.*

object AuthFlowSpec extends ZIOSpecDefault:
  private val security = LiveSecurity(
    TokenConfig("documentation-auth-flow-spec-secret", 1.hour),
    CookiePolicy(secure = true)
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
    ZIO.scoped(AuthHttpRoutes(auth, security).routes.runZIO(request))

  private def render(auth: AuthService, request: Request) =
    val routes = scalive.Live.router
      .withSecurity(security)(AuthLab.loginRoute, AuthLab.protectedSession(auth))
    DisconnectedRender.run(routes, request)

  def spec = suite("AuthFlowSpec")(
    test("keeps standalone lab routes out of the public content index") {
      for
        bundle <- ZIO
                    .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                    .orDieWith(error => new AssertionError(error))
        application <- ZIO
                         .fromEither(DocumentationApplication.from(bundle))
                         .orDieWith(error => new AssertionError(error))
      yield assertTrue(
        application.metadata(AuthLabRoutes.LoginPath).exists(!_.indexable),
        application.metadata(AuthLabRoutes.ProfilePath).exists(!_.indexable),
        application.metadata(AuthLabRoutes.LoginPath).exists(
          _.canonicalPath == "/guides/authentication"
        ),
        application.page(AuthLabRoutes.LoginPath).isEmpty,
        application.page(AuthLabRoutes.ProfilePath).isEmpty
      )
    },
    test("renders a clickable lab call to action in the authentication guide") {
      for
        bundle <- ZIO
                    .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                    .orDieWith(error => new AssertionError(error))
        application <- ZIO
                         .fromEither(DocumentationApplication.from(bundle))
                         .orDieWith(error => new AssertionError(error))
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath(
                      "public",
                      Seq(
                        "app.css",
                        "app.js",
                        "favicon.svg",
                        "fonts.css",
                        "instrument-sans-OFL.txt",
                        "jetbrains-mono-OFL.txt",
                        "search-index.json"
                      )
                    )
                  )
        origin <- ZIO
                    .fromEither(PublicOrigin.from("https://docs.example.test"))
                    .orDieWith(error => new IllegalArgumentException(error))
        rendered <- DisconnectedRender.run(
                      application.routes(
                        assets,
                        security,
                        DocumentationConfig(8080, origin)
                      ).provide(scalive.docs.examples.reportsFixtureService),
                      Request.get(url("/guides/authentication"))
                    )
      yield assertTrue(
        rendered.html.contains("data-lab-cta=\"authentication\""),
        rendered.html.contains(s"href=\"${AuthLabRoutes.LoginPath}\""),
        rendered.html.contains("Open authentication lab")
      )
    },
    test("logs in, mounts the protected route, and explicitly resets the lab") {
      val sessionAction = FormAction.from(AuthLabRoutes.SessionRoute)
      val resetAction   = FormAction.from(AuthLabRoutes.ResetRoute)

      for
        auth <- ZIO.succeed(AuthService.inMemory())
        loginPage <- render(auth, Request.get(url(AuthLabRoutes.LoginPath)))
        loginForm <- ZIO
                       .fromEither(
                         loginPage.form(FormQuery(Some(sessionAction.href), Some(Method.POST)))
                       ).orDieWith(error => new AssertionError(error.toString))
        csrfCookie <- ZIO
                        .fromOption(responseCookie(loginPage.response, CsrfProtection.CookieName))
                        .orDieWith(_ => new AssertionError("missing CSRF cookie"))
        csrfToken <- ZIO
                       .fromOption(loginForm.values(CsrfProtection.ParamName).headOption)
                       .orDieWith(_ => new AssertionError("missing CSRF token"))
        loginResponse <- runHttp(
                           auth,
                           postForm(
                             sessionAction,
                             CsrfProtection.ParamName -> csrfToken,
                             LoginForm.Email.name      -> AuthService.DemoEmail,
                             LoginForm.Password.name   -> AuthService.DemoPassword
                           ).addCookie(requestCookie(csrfCookie))
                         )
        sessionCookie <- ZIO
                           .fromOption(
                             responseCookie(loginResponse, AuthHttpRoutes.SessionCookieName)
                           ).orDieWith(_ => new AssertionError("missing session cookie"))
        profilePage <- render(
                         auth,
                         Request
                           .get(url(AuthLabRoutes.ProfilePath))
                           .addCookie(requestCookie(csrfCookie))
                           .addCookie(requestCookie(sessionCookie))
                       )
        resetForm <- ZIO
                       .fromEither(
                         profilePage.form(FormQuery(Some(resetAction.href), Some(Method.POST)))
                       ).orDieWith(error => new AssertionError(error.toString))
        resetCsrf <- ZIO
                       .fromOption(resetForm.values(CsrfProtection.ParamName).headOption)
                       .orDieWith(_ => new AssertionError("missing reset CSRF token"))
        resetResponse <- runHttp(
                           auth,
                           postForm(resetAction, CsrfProtection.ParamName -> resetCsrf)
                             .addCookie(requestCookie(csrfCookie))
                             .addCookie(requestCookie(sessionCookie))
                         )
        authenticated <- auth.authenticate(SessionCookieToken(sessionCookie.content))
        stalePage <- render(
                       auth,
                       Request
                         .get(url(AuthLabRoutes.ProfilePath))
                         .addCookie(requestCookie(sessionCookie))
                     )
      yield assertTrue(
        loginPage.response.status == Status.Ok,
        loginForm.values(LoginForm.Email.path) == Vector(AuthService.DemoEmail),
        loginResponse.status == Status.SeeOther,
        loginResponse.header(Header.Location).exists(_.url.encode == AuthLabRoutes.ProfilePath),
        sessionCookie.isHttpOnly,
        sessionCookie.isSecure,
        sessionCookie.sameSite.contains(Cookie.SameSite.Lax),
        profilePage.response.status == Status.Ok,
        profilePage.text.contains("Welcome, Alice"),
        resetResponse.status == Status.SeeOther,
        responseCookie(resetResponse, AuthHttpRoutes.SessionCookieName)
          .exists(_.maxAge.contains(zio.Duration.Zero)),
        authenticated.isEmpty,
        stalePage.response.status == Status.SeeOther,
        stalePage.response.header(Header.Location).exists(_.url.encode == AuthLabRoutes.LoginPath)
      )
    }
  )
end AuthFlowSpec
