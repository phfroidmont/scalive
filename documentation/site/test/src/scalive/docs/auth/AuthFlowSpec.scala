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

  private final case class PreparedCsrf(cookie: Cookie.Response, token: String)

  private def prepareCsrf: PreparedCsrf =
    val prepared = security.csrf.prepare(Request.get(URL.root))
    PreparedCsrf(prepared.cookie.get, prepared.value)

  private def postForm(action: FormAction, fields: (String, String)*): Request =
    Request.post(
      url(action.href),
      Body.fromURLEncodedForm(zio.http.Form.fromStrings(fields*))
    )

  private def runHttp(auth: AuthService, request: Request): UIO[Response] =
    ZIO.scoped(AuthHttpRoutes(auth, security).routes.runZIO(request))

  private def loginRequest(csrf: PreparedCsrf, password: String): Request =
    postForm(
      FormAction.from(AuthLabRoutes.SessionRoute),
      CsrfProtection.ParamName -> csrf.token,
      LoginForm.Email.name      -> AuthService.DemoEmail,
      LoginForm.Password.name   -> password
    ).addCookie(requestCookie(csrf.cookie))

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
    },
    test("maps rejected login requests through the lab HTTP boundary") {
      val csrf          = prepareCsrf
      val sessionAction = FormAction.from(AuthLabRoutes.SessionRoute)
      val malformedBody =
        s"${CsrfProtection.ParamName}=${csrf.token}&${LoginForm.Email.name}=%ZZ"
      val malformed = Request
        .post(
          url(sessionAction.href),
          Body.fromString(malformedBody).contentType(
            MediaType.application.`x-www-form-urlencoded`
          )
        ).addCookie(requestCookie(csrf.cookie))
      val oversized = Request
        .post(
          url(sessionAction.href),
          Body
            .fromString("x" * 4097)
            .contentType(MediaType.application.`x-www-form-urlencoded`)
        ).addCookie(requestCookie(csrf.cookie))
      val wrongType = Request
        .post(
          url(sessionAction.href),
          Body.fromString("{}").contentType(MediaType.application.json)
        ).addCookie(requestCookie(csrf.cookie))
      val missingCsrf = postForm(
        sessionAction,
        LoginForm.Email.name    -> AuthService.DemoEmail,
        LoginForm.Password.name -> AuthService.DemoPassword
      ).addCookie(requestCookie(csrf.cookie))

      for
        auth              <- ZIO.succeed(AuthService.inMemory())
        malformedResponse <- runHttp(auth, malformed)
        oversizedResponse <- runHttp(auth, oversized)
        wrongTypeResponse <- runHttp(auth, wrongType)
        missingResponse   <- runHttp(auth, missingCsrf)
      yield assertTrue(
        malformedResponse.status == Status.BadRequest,
        oversizedResponse.status == Status.RequestEntityTooLarge,
        wrongTypeResponse.status == Status.UnsupportedMediaType,
        missingResponse.status == Status.Forbidden
      )
    },
    test("uses generic flash responses for invalid and rate-limited logins") {
      val csrf = prepareCsrf

      for
        auth <- ZIO.succeed(AuthService.inMemory())
        invalidResponse <- runHttp(auth, loginRequest(csrf, "incorrect"))
        _ <- ZIO.foreachDiscard(2 to AuthServiceConfig.default.maxAttempts)(_ =>
               runHttp(auth, loginRequest(csrf, "incorrect"))
             )
        rateLimitedResponse <- runHttp(auth, loginRequest(csrf, AuthService.DemoPassword))
        invalidCookie <- ZIO
                           .fromOption(responseCookie(invalidResponse, FlashToken.CookieName))
                           .orDieWith(_ => new AssertionError("missing invalid-login flash cookie"))
        invalidPage <- render(
                         auth,
                         Request
                           .get(url(AuthLabRoutes.LoginPath))
                           .addCookie(requestCookie(invalidCookie))
                       )
        invalidFlash = FlashToken.decode(security.tokenConfig, invalidCookie.content)
        rateLimitedFlash = responseCookie(rateLimitedResponse, FlashToken.CookieName)
          .flatMap(cookie => FlashToken.decode(security.tokenConfig, cookie.content))
        expiredFlash = responseCookie(invalidPage.response, FlashToken.CookieName)
      yield assertTrue(
        invalidResponse.status == Status.SeeOther,
        invalidResponse.header(Header.Location).exists(_.url.encode == AuthLabRoutes.LoginPath),
        responseCookie(invalidResponse, AuthHttpRoutes.SessionCookieName).isEmpty,
        invalidFlash.contains(
          Map(LoginLiveView.LoginErrorFlash.value -> LoginLiveView.InvalidLoginMessage)
        ),
        rateLimitedFlash.contains(
          Map(LoginLiveView.LoginErrorFlash.value -> LoginLiveView.RateLimitedMessage)
        ),
        invalidPage.text.contains(LoginLiveView.InvalidLoginMessage),
        expiredFlash.exists(_.content.isEmpty),
        expiredFlash.exists(_.maxAge.contains(zio.Duration.Zero))
      )
    },
    test("invalid reset CSRF preserves the session and revoked claims cannot reconnect") {
      val csrf        = prepareCsrf
      val resetAction = FormAction.from(AuthLabRoutes.ResetRoute)

      for
        auth <- ZIO.succeed(AuthService.inMemory())
        decision <- auth.login(
                      VisitorToken(csrf.cookie.content),
                      LoginCredentials(AuthService.DemoEmail, AuthService.DemoPassword)
                    )
        loggedIn <- ZIO
                      .fromOption(decision.toOption)
                      .orDieWith(_ => new AssertionError("demo login failed"))
        invalidReset <- runHttp(
                          auth,
                          postForm(
                            resetAction,
                            CsrfProtection.ParamName -> s"${csrf.token}x"
                          ).addCookie(requestCookie(csrf.cookie)).addCookie(
                            Cookie.Request(
                              AuthHttpRoutes.SessionCookieName,
                              loggedIn.cookieToken.value
                            )
                          )
                        )
        preserved <- auth.authenticate(loggedIn.cookieToken)
        _ <- auth.reset(
               VisitorToken(csrf.cookie.content),
               Some(loggedIn.cookieToken)
             )
        reconnect <- AuthMountAspect
                       .authenticated(auth)
                       .connected(
                         AuthClaims(loggedIn.currentSession.publicSessionId),
                         LiveMountRequest((), Request.get(url(AuthLabRoutes.ProfilePath))),
                         ()
                       ).either
      yield assertTrue(
        invalidReset.status == Status.Forbidden,
        preserved.contains(loggedIn.currentSession),
        reconnect.left.exists {
          case LiveMountFailure.Redirect(location) =>
            location.href == AuthLabRoutes.LoginPath
          case _ => false
        }
      )
    }
  )
end AuthFlowSpec
