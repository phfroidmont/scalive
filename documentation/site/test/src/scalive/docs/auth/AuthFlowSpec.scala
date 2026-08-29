package scalive.docs.auth

import java.time.Duration

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.docs.*
import scalive.docs.examples.{Reports, reportsFixtureService}
import scalive.testing.*

object AuthFlowSpec extends ZIOSpecDefault:
  private val config = ZioHttpConfig(
    "documentation-auth-flow-secret-0000000000000000",
    Duration.ofHours(1),
    secureCookie = true,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("docs.example.test"))
  ).toOption.get
  private val security = LiveSecurity(config)
  private val liveConnections = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(LiveConnections.make[PublicSessionId](_ => ZIO.unit)).getOrThrow()
  }

  private def authEnvironment(
    auth: AuthService,
    connections: LiveConnections[PublicSessionId] = liveConnections
  ) =
    ZEnvironment[AuthService](auth)
      .add[LiveConnections[PublicSessionId]](connections)

  private val documentationEnvironment =
    authEnvironment(AuthService.inMemory()).add[Reports](reportsFixtureService)

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def responseCookie(response: Response, name: String): Option[Cookie.Response] =
    response.headers(Header.SetCookie).map(_.value).find(_.name == name)

  private def requestCookie(cookie: Cookie.Response): Cookie.Request =
    Cookie.Request(cookie.name, cookie.content)

  private final case class PreparedCsrf(cookie: Cookie.Response, token: String)

  private def prepareCsrf(auth: AuthService): Task[PreparedCsrf] =
    val sessionAction = FormAction.from(AuthLabRoutes.SessionRoute)
    for
      page <- render(auth, Request.get(url(AuthLabRoutes.LoginPath)))
      cookie <- ZIO
                  .fromOption(responseCookie(page.response, CsrfProtection.CookieName))
                  .orElseFail(new AssertionError("missing CSRF cookie"))
      form <- ZIO
                .fromEither(page.form(FormQuery(Some(sessionAction.href), Some(Method.POST))))
                .mapError(error => new AssertionError(error.toString))
      token <- ZIO
                 .fromOption(form.values(CsrfProtection.ParamName).headOption)
                 .orElseFail(new AssertionError("missing CSRF token"))
    yield PreparedCsrf(cookie, token)

  private def postForm(action: FormAction, fields: (String, String)*): Request =
    Request.post(
      url(action.href),
      Body.fromURLEncodedForm(zio.http.Form.fromStrings(fields*))
    )

  private def runHttp(auth: AuthService, request: Request): UIO[Response] =
    ZIO.scoped(
      AuthHttpRoutes(security).routes
        .provideEnvironment(authEnvironment(auth)).runZIO(request)
    )

  private def httpRoutes(auth: AuthService): Routes[Any, Nothing] =
    AuthHttpRoutes(security).routes.provideEnvironment(authEnvironment(auth))

  private def liveRoutes(auth: AuthService): Routes[Any, Nothing] =
    val application = scalive.Live.router(AuthLab.loginRoute, AuthLab.protectedSession)
    ZioHttp.routes(application, config).provideEnvironment(authEnvironment(auth))

  private def loginRequest(csrf: PreparedCsrf, password: String): Request =
    postForm(
      FormAction.from(AuthLabRoutes.SessionRoute),
      CsrfProtection.ParamName -> csrf.token,
      LoginForm.Email.name      -> AuthService.DemoEmail,
      LoginForm.Password.name   -> password
    ).addCookie(requestCookie(csrf.cookie))

  private def render(auth: AuthService, request: Request) =
    DisconnectedRender.run(liveRoutes(auth), request)

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
                         DocumentationConfig(8080, origin, "documentation-auth-flow-secret-0000000000000000")
                        ).provideEnvironment(documentationEnvironment),
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
        csrfToken <- ZIO
                       .fromOption(loginForm.values(CsrfProtection.ParamName).headOption)
                       .orDieWith(_ => new AssertionError("missing CSRF token"))
        loginResponse <- loginForm.submit(
                           httpRoutes(auth),
                           FormData(
                             Vector(
                               CsrfProtection.ParamName -> csrfToken,
                               LoginForm.Email.name      -> AuthService.DemoEmail,
                               LoginForm.Password.name   -> AuthService.DemoPassword
                             )
                           )
                         )
        sessionCookie <- ZIO
                           .fromOption(
                             responseCookie(loginResponse.response, AuthHttpRoutes.SessionCookieName)
                           ).orDieWith(_ => new AssertionError("missing session cookie"))
        profilePage <- loginResponse.followSeeOther(liveRoutes(auth))
        resetForm <- ZIO
                       .fromEither(
                          profilePage.form(FormQuery(Some(resetAction.href), Some(Method.POST)))
                       ).orDieWith(error => new AssertionError(error.toString))
        resetCsrf <- ZIO
                       .fromOption(resetForm.values(CsrfProtection.ParamName).headOption)
                       .orDieWith(_ => new AssertionError("missing reset CSRF token"))
        resetResponse <- resetForm.submit(
                           httpRoutes(auth),
                           FormData(Vector(CsrfProtection.ParamName -> resetCsrf))
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
        loginResponse.response.status == Status.SeeOther,
        loginResponse.response.header(Header.Location).exists(_.url.encode == AuthLabRoutes.ProfilePath),
        sessionCookie.isHttpOnly,
        sessionCookie.isSecure,
        sessionCookie.sameSite.contains(Cookie.SameSite.Lax),
        profilePage.response.status == Status.Ok,
        profilePage.text.contains("Welcome, Alice"),
        resetResponse.response.status == Status.SeeOther,
        responseCookie(resetResponse.response, AuthHttpRoutes.SessionCookieName)
          .exists(_.maxAge.contains(zio.Duration.Zero)),
        authenticated.isEmpty,
        stalePage.response.status == Status.SeeOther,
        stalePage.response.header(Header.Location).exists(_.url.encode == AuthLabRoutes.LoginPath)
      )
    },
    test("maps rejected login requests through the lab HTTP boundary") {
      val sessionAction = FormAction.from(AuthLabRoutes.SessionRoute)

      for
        auth <- ZIO.succeed(AuthService.inMemory())
        csrf <- prepareCsrf(auth)
        malformedBody = s"${CsrfProtection.ParamName}=${csrf.token}&${LoginForm.Email.name}=%ZZ"
        malformed = Request
                      .post(
                        url(sessionAction.href),
                        Body.fromString(malformedBody).contentType(
                          MediaType.application.`x-www-form-urlencoded`
                        )
                      ).addCookie(requestCookie(csrf.cookie))
        oversized = Request
                      .post(
                        url(sessionAction.href),
                        Body
                          .fromString("x" * 4097)
                          .contentType(MediaType.application.`x-www-form-urlencoded`)
                      ).addCookie(requestCookie(csrf.cookie))
        wrongType = Request
                      .post(
                        url(sessionAction.href),
                        Body.fromString("{}").contentType(MediaType.application.json)
                      ).addCookie(requestCookie(csrf.cookie))
        missingCsrf = postForm(
                        sessionAction,
                        LoginForm.Email.name    -> AuthService.DemoEmail,
                        LoginForm.Password.name -> AuthService.DemoPassword
                      ).addCookie(requestCookie(csrf.cookie))
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
      for
        auth <- ZIO.succeed(AuthService.inMemory())
        csrf <- prepareCsrf(auth)
        invalidResponse <- runHttp(auth, loginRequest(csrf, "incorrect"))
        _ <- ZIO.foreachDiscard(2 to AuthServiceConfig.default.maxAttempts)(_ =>
               runHttp(auth, loginRequest(csrf, "incorrect"))
             )
        rateLimitedResponse <- runHttp(auth, loginRequest(csrf, AuthService.DemoPassword))
        invalidCookie <- ZIO
                            .fromOption(responseCookie(invalidResponse, HttpFlash.CookieName))
                           .orDieWith(_ => new AssertionError("missing invalid-login flash cookie"))
        invalidPage <- render(
                         auth,
                         Request
                           .get(url(AuthLabRoutes.LoginPath))
                           .addCookie(requestCookie(invalidCookie))
                       )
        rateLimitedCookie <- ZIO
                               .fromOption(responseCookie(rateLimitedResponse, HttpFlash.CookieName))
                               .orDieWith(_ => new AssertionError("missing rate-limit flash cookie"))
        rateLimitedPage <- render(
                             auth,
                             Request
                               .get(url(AuthLabRoutes.LoginPath))
                               .addCookie(requestCookie(rateLimitedCookie))
                           )
        expiredFlash = responseCookie(invalidPage.response, HttpFlash.CookieName)
      yield assertTrue(
        invalidResponse.status == Status.SeeOther,
        invalidResponse.header(Header.Location).exists(_.url.encode == AuthLabRoutes.LoginPath),
        responseCookie(invalidResponse, AuthHttpRoutes.SessionCookieName).isEmpty,
        invalidCookie.content.nonEmpty,
        rateLimitedCookie.content.nonEmpty,
        invalidPage.text.contains(LoginLiveView.InvalidLoginMessage),
        rateLimitedPage.text.contains(LoginLiveView.RateLimitedMessage),
        expiredFlash.exists(_.content.isEmpty),
        expiredFlash.exists(_.maxAge.contains(zio.Duration.Zero))
      )
    },
    test("invalid reset CSRF preserves the session and revoked claims cannot reconnect") {
      val resetAction = FormAction.from(AuthLabRoutes.ResetRoute)

      for
        auth <- ZIO.succeed(AuthService.inMemory())
        csrf <- prepareCsrf(auth)
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
        connectedAuth <- AuthMountAspect
                           .authenticated
                           .connected(
                             AuthClaims(loggedIn.currentSession.publicSessionId),
                             LiveMountRequest((), Request.get(url(AuthLabRoutes.ProfilePath))),
                             ()
                           ).provideEnvironment(ZEnvironment(auth))
        _ <- auth.reset(
               VisitorToken(csrf.cookie.content),
               Some(loggedIn.cookieToken)
             )
        revalidation <- connectedAuth.revalidate.either
        reconnect <- AuthMountAspect
                       .authenticated
                       .connected(
                         AuthClaims(loggedIn.currentSession.publicSessionId),
                         LiveMountRequest((), Request.get(url(AuthLabRoutes.ProfilePath))),
                         ()
                       ).provideEnvironment(ZEnvironment(auth)).either
      yield assertTrue(
        invalidReset.status == Status.Forbidden,
        preserved.contains(loggedIn.currentSession),
        revalidation.left.exists {
          case LiveConnectedTurnFailure.Redirect(location) =>
            location.href == AuthLabRoutes.LoginPath
          case _ => false
        },
        reconnect.left.exists {
          case LiveMountFailure.Redirect(location) =>
            location.href == AuthLabRoutes.LoginPath
          case _ => false
        }
      )
    },
    test("reset revokes and expires the cookie when disconnect publication fails") {
      val resetAction = FormAction.from(AuthLabRoutes.ResetRoute)
      for
        auth <- ZIO.succeed(AuthService.inMemory())
        csrf <- prepareCsrf(auth)
        decision <- auth.login(
                      VisitorToken(csrf.cookie.content),
                      LoginCredentials(AuthService.DemoEmail, AuthService.DemoPassword)
                    )
        loggedIn <- ZIO
                      .fromOption(decision.toOption)
                      .orDieWith(_ => new AssertionError("demo login failed"))
        connections <- LiveConnections.make[PublicSessionId](_ =>
                         ZIO.fail(new Exception("fanout unavailable"))
                       )
        request = postForm(
                    resetAction,
                    CsrfProtection.ParamName -> csrf.token
                  ).addCookie(requestCookie(csrf.cookie)).addCookie(
                    Cookie.Request(
                      AuthHttpRoutes.SessionCookieName,
                      loggedIn.cookieToken.value
                    )
                  )
        response <- ZIO.scoped(
                      AuthHttpRoutes(security).routes
                        .provideEnvironment(authEnvironment(auth, connections)).runZIO(request)
                    )
        revoked <- auth.authenticate(loggedIn.cookieToken)
        expired = responseCookie(response, AuthHttpRoutes.SessionCookieName)
      yield assertTrue(
        response.status == Status.InternalServerError,
        expired.exists(_.maxAge.contains(zio.Duration.Zero)),
        revoked.isEmpty
      )
    }
  )
end AuthFlowSpec
