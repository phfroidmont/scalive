package scalive.examples.auth

import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.examples.ExamplesRoutes

object AuthServiceSpec extends ZIOSpecDefault:

  private val DemoEmail        = "alice@example.com"
  private val DemoPassword     = "scalive"
  private val sessionAction    = FormAction.from(AuthHttpRoutes.SessionRoute)
  private val logoutAction     = FormAction.from(AuthHttpRoutes.LogoutRoute)
  private val security = LiveSecurity(
    TokenConfig("auth-service-spec-secret", 1.hour),
    CookiePolicy(secure = true)
  )
  private val csrfProtection = security.csrf

  private final case class PreparedCsrf(cookie: Cookie.Response, token: String)

  private def authService: UIO[AuthService] =
    authService(AuthServiceConfig.default)

  private def authService(config: AuthServiceConfig): UIO[AuthService] =
    ZIO.service[AuthService].provide(AuthService.live(config))

  private def login(auth: AuthService): UIO[LoginResult] =
    auth
      .login(LoginCredentials(DemoEmail, DemoPassword))
      .someOrFail(new IllegalStateException("demo login failed"))
      .orDie

  private def prepareCsrf: PreparedCsrf =
    val prepared = csrfProtection.prepare(Request.get(URL.root))
    PreparedCsrf(prepared.cookie.get, prepared.value)

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def postForm(value: String, fields: (String, String)*): Request =
    Request.post(
      url(value),
      Body.fromURLEncodedForm(zio.http.Form.fromStrings(fields*))
    )

  private def protectedPost(
    action: FormAction,
    csrf: PreparedCsrf,
    fields: (String, String)*
  ): Request =
    postForm(action.href, (CsrfProtection.ParamName -> csrf.token) +: fields*).addCookie(
      Cookie.Request(csrf.cookie.name, csrf.cookie.content)
    )

  private def run(auth: AuthService, request: Request): UIO[Response] =
    ZIO
      .scoped(AuthHttpRoutes(security).routes.runZIO(request))
      .provideEnvironment(ZEnvironment(auth))

  private def responseCookies(response: Response): Chunk[Cookie.Response] =
    response.headers(Header.SetCookie).map(_.value)

  private def loginRequest(
    csrf: PreparedCsrf,
    password: String = DemoPassword
  ): Request =
    protectedPost(
      sessionAction,
      csrf,
      LoginForm.Email.name    -> DemoEmail,
      LoginForm.Password.name -> password
    )

  private def rawLoginRequest(
    csrf: PreparedCsrf,
    body: String,
    mediaType: MediaType = MediaType.application.`x-www-form-urlencoded`
  ): Request =
    Request
      .post(
        url(sessionAction.href),
        Body.fromString(body).contentType(mediaType)
      ).addCookie(Cookie.Request(csrf.cookie.name, csrf.cookie.content))

  def spec = suite("AuthServiceSpec")(
    test("decodes rooted credentials without application-owned CSRF") {
      val data = FormData(
        Vector(
          LoginForm.Email.name    -> DemoEmail,
          LoginForm.Password.name -> DemoPassword
        )
      )

      assertTrue(
        LoginForm.Definition.codec.decode(data) == Right(
          LoginCredentials(DemoEmail, DemoPassword)
        )
      )
    },
    test("rejects incomplete, oversized, and duplicated credentials") {
      val incomplete = FormData(Vector(LoginForm.Email.name -> DemoEmail))
      val oversized = FormData(
        Vector(
          LoginForm.Email.name    -> ("a" * (LoginForm.EmailMaxLength + 1)),
          LoginForm.Password.name -> DemoPassword
        )
      )
      val duplicated = FormData(
        Vector(
          LoginForm.Email.name    -> "first@example.com",
          LoginForm.Email.name    -> DemoEmail,
          LoginForm.Password.name -> DemoPassword
        )
      )

      assertTrue(
        LoginForm.Definition.codec.decode(incomplete).left.exists(
          _.forPath(LoginForm.Password.path).nonEmpty
        ),
        LoginForm.Definition.codec.decode(oversized).left.exists(
          _.forPath(LoginForm.Email.path).nonEmpty
        ),
        LoginForm.Definition.codec.decode(duplicated).left.exists(
          _.forPath(LoginForm.Email.path).nonEmpty
        )
      )
    },
    test("rejects invalid credentials") {
      for
        auth   <- authService
        result <- auth.login(LoginCredentials(DemoEmail, "incorrect"))
      yield assertTrue(result.isEmpty)
    },
    test("authenticates and resumes an opaque session") {
      for
        auth          <- authService
        loggedIn      <- login(auth)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
        resumed       <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(
        authenticated.contains(loggedIn.currentSession),
        resumed.contains(loggedIn.currentSession),
        loggedIn.cookieToken.value.length >= 43,
        loggedIn.cookieToken.value != loggedIn.currentSession.publicSessionId.value
      )
    },
    test("logout invalidates cookie authentication and claims resumption") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        _ <- auth.logout(loggedIn.cookieToken)
        _ <- auth.logout(loggedIn.cookieToken)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
        resumed       <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(authenticated.isEmpty, resumed.isEmpty)
    },
    test("expires sessions") {
      val config = AuthServiceConfig.default.copy(sessionTtl = 1.minute)
      for
        auth     <- authService(config)
        loggedIn <- login(auth)
        _        <- TestClock.adjust(config.sessionTtl)
        byCookie <- auth.authenticate(loggedIn.cookieToken)
        byPublic <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(byCookie.isEmpty, byPublic.isEmpty)
    },
    test("evicts the oldest session at the configured bound") {
      val config = AuthServiceConfig.default.copy(maxSessions = 2)
      for
        auth   <- authService(config)
        first  <- login(auth)
        second <- login(auth)
        third  <- login(auth)
        firstByCookie  <- auth.authenticate(first.cookieToken)
        firstByPublic  <- auth.resume(first.currentSession.publicSessionId)
        secondByCookie <- auth.authenticate(second.cookieToken)
        thirdByCookie  <- auth.authenticate(third.cookieToken)
      yield assertTrue(
        firstByCookie.isEmpty,
        firstByPublic.isEmpty,
        secondByCookie.isDefined,
        thirdByCookie.isDefined
      )
    },
    test("secure-cookie config parses strict boolean values") {
      val expectedMessage =
        s"${AuthHttpConfig.SecureCookiesEnvironmentVariable} must be either true or false when set"
      for
        absent <- AuthHttpConfig.fromEnvironment(Map.empty)
        enabled <- AuthHttpConfig.fromEnvironment(
                     Map(AuthHttpConfig.SecureCookiesEnvironmentVariable -> "TrUe")
                   )
        disabled <- AuthHttpConfig.fromEnvironment(
                      Map(AuthHttpConfig.SecureCookiesEnvironmentVariable -> "FALSE")
                    )
        invalid <- AuthHttpConfig
                     .fromEnvironment(
                       Map(AuthHttpConfig.SecureCookiesEnvironmentVariable -> "1")
                     ).either
      yield assertTrue(
        !absent.secureCookies,
        enabled.secureCookies,
        !disabled.secureCookies,
        invalid.left.exists(_.getMessage == expectedMessage)
      )
    },
    test("HTTP login validates framework CSRF and sets a hardened session cookie") {
      val csrf = prepareCsrf
      for
        auth     <- authService
        response <- run(auth, loginRequest(csrf))
        sessionCookie = responseCookies(response).find(
                          _.name == AuthHttpRoutes.SessionCookieName
                        )
        authenticated <- ZIO.foreach(sessionCookie)(cookie =>
                           auth.authenticate(SessionCookieToken(cookie.content))
                         )
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(
          _.url.encode == ExamplesRoutes.profile.location.href
        ),
        sessionCookie.exists(_.path.contains(Path.root)),
        sessionCookie.exists(_.isHttpOnly),
        sessionCookie.exists(_.isSecure),
        sessionCookie.exists(_.sameSite.contains(Cookie.SameSite.Lax)),
        authenticated.flatten.isDefined
      )
    },
    test("HTTP login rejects missing, tampered, and transferred CSRF") {
      val first  = prepareCsrf
      val second = prepareCsrf
      val fields = Vector(
        LoginForm.Email.name    -> DemoEmail,
        LoginForm.Password.name -> DemoPassword
      )
      val missing = postForm(sessionAction.href, fields*).addCookie(
        Cookie.Request(first.cookie.name, first.cookie.content)
      )
      val tampered = protectedPost(
        sessionAction,
        first.copy(token = s"${first.token}x"),
        fields*
      )
      val transferred = protectedPost(
        sessionAction,
        first.copy(cookie = second.cookie),
        fields*
      )

      for
        auth                <- authService
        missingResponse     <- run(auth, missing)
        tamperedResponse    <- run(auth, tampered)
        transferredResponse <- run(auth, transferred)
      yield assertTrue(
        missingResponse.status == Status.Forbidden,
        tamperedResponse.status == Status.Forbidden,
        transferredResponse.status == Status.Forbidden,
        List(missingResponse, tamperedResponse, transferredResponse).forall(response =>
          responseCookies(response).forall(_.name != AuthHttpRoutes.SessionCookieName)
        )
      )
    },
    test("HTTP invalid credentials use one generic flash redirect") {
      val csrf = prepareCsrf
      for
        auth     <- authService
        response <- run(auth, loginRequest(csrf, password = "incorrect"))
        redirect = response.header(Header.Location).map(_.url.encode)
        flashCookie = responseCookies(response).find(_.name == FlashToken.CookieName)
        flashValues = flashCookie.flatMap(cookie =>
                        FlashToken.decode(security.tokenConfig, cookie.content)
                      )
      yield assertTrue(
        response.status == Status.SeeOther,
        redirect.contains(ExamplesRoutes.login.location.href),
        redirect.forall(value => !value.contains(DemoEmail) && !value.contains("incorrect")),
        responseCookies(response).forall(_.name != AuthHttpRoutes.SessionCookieName),
        flashValues.contains(
          Map(LoginLiveView.InvalidLoginFlash.value -> LoginLiveView.InvalidLoginMessage)
        ),
        flashCookie.exists(_.isSecure),
        flashCookie.exists(_.isHttpOnly),
        flashCookie.exists(_.sameSite.contains(Cookie.SameSite.Lax))
      )
    },
    test("HTTP malformed, oversized, and wrong-content-type login bodies stay distinct") {
      val csrf = prepareCsrf
      for
        auth <- authService
        malformed <- run(
                       auth,
                        rawLoginRequest(csrf, s"${LoginForm.Email.name}=%ZZ")
                     )
        oversized <- run(
                       auth,
                       rawLoginRequest(csrf, "x" * (AuthHttpRoutes.FormMaxBytes.toInt + 1))
                     )
        wrongType <- run(
                       auth,
                       rawLoginRequest(csrf, "{}", MediaType.application.json)
                     )
      yield assertTrue(
        malformed.status == Status.BadRequest,
        oversized.status == Status.RequestEntityTooLarge,
        wrongType.status == Status.UnsupportedMediaType
      )
    },
    test("HTTP logout rejects invalid CSRF without revoking the session") {
      val csrf = prepareCsrf
      for
        auth     <- authService
        loggedIn <- login(auth)
        request = protectedPost(
                    logoutAction,
                    csrf.copy(token = s"${csrf.token}x")
                  ).addCookie(
                    Cookie.Request(AuthHttpRoutes.SessionCookieName, loggedIn.cookieToken.value)
                  )
        response      <- run(auth, request)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(
        response.status == Status.Forbidden,
        responseCookies(response).isEmpty,
        authenticated.contains(loggedIn.currentSession)
      )
    },
    test("HTTP logout revokes the session and expires its cookie") {
      val csrf = prepareCsrf
      for
        auth     <- authService
        loggedIn <- login(auth)
        request = protectedPost(logoutAction, csrf).addCookie(
                    Cookie.Request(AuthHttpRoutes.SessionCookieName, loggedIn.cookieToken.value)
                  )
        response <- run(auth, request)
        cookie = responseCookies(response).find(_.name == AuthHttpRoutes.SessionCookieName)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url.encode == ExamplesRoutes.home.location.href),
        cookie.exists(_.content.isEmpty),
        cookie.exists(_.path.contains(Path.root)),
        cookie.exists(_.isHttpOnly),
        cookie.exists(_.isSecure),
        cookie.exists(_.maxAge.contains(zio.Duration.Zero)),
        cookie.exists(_.sameSite.contains(Cookie.SameSite.Lax)),
        authenticated.isEmpty
      )
    },
    test("HTTP logout clears missing and stale sessions idempotently") {
      val csrf = prepareCsrf
      for
        auth     <- authService
        loggedIn <- login(auth)
        _        <- auth.logout(loggedIn.cookieToken)
        staleRequest = protectedPost(logoutAction, csrf).addCookie(
                         Cookie.Request(
                           AuthHttpRoutes.SessionCookieName,
                           loggedIn.cookieToken.value
                         )
                       )
        staleResponse  <- run(auth, staleRequest)
        missingResponse <- run(auth, protectedPost(logoutAction, csrf))
        repeatedResponse <- run(auth, staleRequest)
        responses = List(staleResponse, missingResponse, repeatedResponse)
      yield assertTrue(
        responses.forall(_.status == Status.SeeOther),
        responses.forall(
          _.header(Header.Location).exists(_.url.encode == ExamplesRoutes.home.location.href)
        ),
        responses.forall(response =>
          responseCookies(response)
            .find(_.name == AuthHttpRoutes.SessionCookieName)
            .exists(_.maxAge.contains(zio.Duration.Zero))
        )
      )
    },
    test("mount authentication uses the cookie disconnected and public claims connected") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        aspect = AuthMountAspect.authenticated
        disconnectedRequest = LiveMountRequest(
                                (),
                                Request
                                  .get(url("/auth/profile"))
                                  .addCookie(
                                    Cookie.Request(
                                      AuthHttpRoutes.SessionCookieName,
                                      loggedIn.cookieToken.value
                                    )
                                  )
                              )
        disconnected <- aspect
                          .disconnected(disconnectedRequest, ())
                          .provideEnvironment(ZEnvironment(auth))
        connectedRequest = LiveMountRequest((), Request.get(url("/auth/profile")))
        connected <- aspect
                       .connected(disconnected._1, connectedRequest, ())
                       .provideEnvironment(ZEnvironment(auth))
      yield assertTrue(
        disconnected._1 == AuthClaims(loggedIn.currentSession.publicSessionId),
        disconnected._2 == loggedIn.currentSession,
        connected == loggedIn.currentSession,
        connectedRequest.request.cookie(AuthHttpRoutes.SessionCookieName).isEmpty
      )
    },
    test("disconnected mount redirects an invalid session cookie") {
      for
        auth <- authService
        request = LiveMountRequest(
                    (),
                    Request
                      .get(url("/auth/profile"))
                      .addCookie(Cookie.Request(AuthHttpRoutes.SessionCookieName, "invalid"))
                  )
        result <- AuthMountAspect.authenticated
                    .disconnected(request, ())
                    .provideEnvironment(ZEnvironment(auth))
                    .either
        response = result.swap.toOption
      yield assertTrue(
        response.exists(_.status == Status.SeeOther),
        response.flatMap(_.header(Header.Location)).exists(
          _.url.encode == ExamplesRoutes.login.location.href
        )
      )
    },
    test("connected mount redirects a revoked public session") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        claims    = AuthClaims(loggedIn.currentSession.publicSessionId)
        _ <- auth.logout(loggedIn.cookieToken)
        result <- AuthMountAspect.authenticated
                    .connected(
                      claims,
                      LiveMountRequest((), Request.get(url("/auth/profile"))),
                      ()
                    ).provideEnvironment(ZEnvironment(auth))
                    .either
      yield assertTrue(
        result.left.exists {
          case LiveMountFailure.Redirect(location) =>
            location.href == ExamplesRoutes.login.location.href
          case _ => false
        }
      )
    }
  )
end AuthServiceSpec
