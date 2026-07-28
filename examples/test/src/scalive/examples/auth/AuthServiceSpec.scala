package scalive.examples.auth

import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.examples.ExamplesRoutes

object AuthServiceSpec extends ZIOSpecDefault:

  private val DemoEmail    = "alice@example.com"
  private val DemoPassword = "scalive"
  private val secureHttpConfig = AuthHttpConfig(secureCookies = true)

  private final case class PreparedLogin(
    bootstrap: LoginBootstrap,
    context: LoginContext)

  private def authService: UIO[AuthService] =
    authService(AuthServiceConfig.default)

  private def authService(config: AuthServiceConfig): UIO[AuthService] =
    ZIO.service[AuthService].provide(AuthService.live(config))

  private def prepareLogin(auth: AuthService): UIO[PreparedLogin] =
    for
      bootstrap <- auth.beginLogin
      context <- auth
                   .prepareLogin(bootstrap.cookieToken)
                   .someOrFail(new IllegalStateException("login context was not prepared"))
                   .orDie
    yield PreparedLogin(bootstrap, context)

  private def login(auth: AuthService): UIO[LoginResult] =
    for
      prepared <- prepareLogin(auth)
      result <- auth
                  .login(
                    prepared.bootstrap.cookieToken,
                    prepared.context.csrfToken,
                    DemoEmail,
                    DemoPassword
                  )
                  .someOrFail(new IllegalStateException("demo login failed"))
                  .orDie
    yield result

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def postForm(value: String, fields: (String, String)*): Request =
    Request.post(
      url(value),
      Body.fromURLEncodedForm(zio.http.Form.fromStrings(fields*))
    )

  private def run(routes: Routes[Any, Nothing], request: Request): UIO[Response] =
    ZIO.scoped(routes.runZIO(request))

  private def httpRoutes(auth: AuthService): Routes[Any, Nothing] =
    AuthHttpRoutes(auth, secureHttpConfig).routes

  private def responseCookies(response: Response): Chunk[Cookie.Response] =
    response.headers(Header.SetCookie).map(_.value)

  private def beginHttpLogin(auth: AuthService): UIO[PreparedLogin] =
    for
      response <- run(
                    httpRoutes(auth),
                    Request.get(url("/auth/login/bootstrap"))
                  )
      cookie <- ZIO
                  .fromOption(
                    responseCookies(response).find(_.name == AuthHttpRoutes.LoginContextCookieName)
                  ).orElseFail(new IllegalStateException("login context cookie was not set"))
                  .orDie
      context <- auth
                   .prepareLogin(LoginContextCookieToken(cookie.content))
                   .someOrFail(new IllegalStateException("HTTP login context was not prepared"))
                   .orDie
    yield PreparedLogin(LoginBootstrap(LoginContextCookieToken(cookie.content)), context)

  private def loginRequest(prepared: PreparedLogin, fields: (String, String)*): Request =
    postForm(
      "/auth/session",
      fields*
    ).addCookie(
      Cookie.Request(
        AuthHttpRoutes.LoginContextCookieName,
        prepared.bootstrap.cookieToken.value
      )
    )

  private def loginMountRequest(cookieToken: Option[LoginContextCookieToken]) =
    val request = cookieToken.fold(Request.get(url("/auth/login")))(token =>
      Request
        .get(url("/auth/login"))
        .addCookie(Cookie.Request(AuthHttpRoutes.LoginContextCookieName, token.value))
    )
    LiveMountRequest((), request)

  private def redirectsToLoginBootstrap(response: Response): Boolean =
    response.status == Status.SeeOther && response.header(Header.Location).exists(
      _.url.encode == AuthHttpRoutes.loginBootstrapUrl(invalid = false).encode
    )

  private def redirectsToLoginBootstrap(failure: LiveMountFailure): Boolean =
    failure match
      case LiveMountFailure.RedirectUnsafe(location) =>
        location.encode == AuthHttpRoutes.loginBootstrapUrl(invalid = false).encode
      case _ => false

  def spec = suite("AuthServiceSpec")(
    test("rejects invalid credentials") {
      for
        auth     <- authService
        prepared <- prepareLogin(auth)
        result <- auth.login(
                    prepared.bootstrap.cookieToken,
                    prepared.context.csrfToken,
                    DemoEmail,
                    "incorrect"
                  )
      yield assertTrue(result.isEmpty)
    },
    test("consumes a login context and CSRF token after one attempt") {
      for
        auth     <- authService
        prepared <- prepareLogin(auth)
        _ <- auth.login(
               prepared.bootstrap.cookieToken,
               prepared.context.csrfToken,
               DemoEmail,
               "incorrect"
             )
        reuse <- auth.login(
                   prepared.bootstrap.cookieToken,
                   prepared.context.csrfToken,
                   DemoEmail,
                   DemoPassword
                 )
      yield assertTrue(reuse.isEmpty)
    },
    test("rejects a login CSRF token transferred to another browser context") {
      for
        auth   <- authService
        first  <- prepareLogin(auth)
        second <- prepareLogin(auth)
        transferred <- auth.login(
                         second.bootstrap.cookieToken,
                         first.context.csrfToken,
                         DemoEmail,
                         DemoPassword
                       )
        consumed <- auth.prepareLogin(second.bootstrap.cookieToken)
        original <- auth.login(
                      first.bootstrap.cookieToken,
                      first.context.csrfToken,
                      DemoEmail,
                      DemoPassword
                    )
      yield assertTrue(transferred.isEmpty, consumed.isEmpty, original.isDefined)
    },
    test("authenticates an opaque session cookie token") {
      for
        auth          <- authService
        loggedIn      <- login(auth)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(
        authenticated.contains(loggedIn.currentSession),
        loggedIn.cookieToken.value.length >= 43,
        loggedIn.cookieToken.value != loggedIn.currentSession.publicSessionId.value
      )
    },
    test("resumes a session from its public session ID") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        resumed  <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(resumed.contains(loggedIn.currentSession))
    },
    test("rejects an invalid logout CSRF token without revoking the session") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        loggedOut <- auth.logout(
                       loggedIn.cookieToken,
                       LogoutCsrfToken("invalid")
                     )
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(!loggedOut, authenticated.contains(loggedIn.currentSession))
    },
    test("revokes claims-based session resumption on logout") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        loggedOut <- auth.logout(
                       loggedIn.cookieToken,
                       loggedIn.currentSession.logoutCsrfToken
                     )
        resumed <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(loggedOut, resumed.isEmpty)
    },
    test("invalidates session cookie authentication on logout") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        loggedOut <- auth.logout(
                       loggedIn.cookieToken,
                       loggedIn.currentSession.logoutCsrfToken
                     )
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(loggedOut, authenticated.isEmpty)
    },
    test("expires pending login contexts") {
      val config = AuthServiceConfig.default.copy(loginContextTtl = 1.minute)
      for
        auth     <- authService(config)
        prepared <- prepareLogin(auth)
        _        <- TestClock.adjust(config.loginContextTtl)
        byCookie <- auth.prepareLogin(prepared.bootstrap.cookieToken)
        byPublic <- auth.resumeLogin(prepared.context.publicId)
      yield assertTrue(byCookie.isEmpty, byPublic.isEmpty)
    },
    test("expires session authentication and claims resumption") {
      val config = AuthServiceConfig.default.copy(sessionTtl = 1.minute)
      for
        auth     <- authService(config)
        loggedIn <- login(auth)
        _        <- TestClock.adjust(config.sessionTtl)
        byCookie <- auth.authenticate(loggedIn.cookieToken)
        byPublic <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(byCookie.isEmpty, byPublic.isEmpty)
    },
    test("evicts the oldest pending login context at the configured bound") {
      val config = AuthServiceConfig.default.copy(maxLoginContexts = 2)
      for
        auth   <- authService(config)
        first  <- prepareLogin(auth)
        second <- prepareLogin(auth)
        third  <- prepareLogin(auth)
        firstByCookie <- auth.prepareLogin(first.bootstrap.cookieToken)
        firstByPublic <- auth.resumeLogin(first.context.publicId)
        secondByCookie <- auth.prepareLogin(second.bootstrap.cookieToken)
        thirdByCookie  <- auth.prepareLogin(third.bootstrap.cookieToken)
      yield assertTrue(
        firstByCookie.isEmpty,
        firstByPublic.isEmpty,
        secondByCookie.isDefined,
        thirdByCookie.isDefined
      )
    },
    test("evicts the oldest session at the configured bound") {
      val config = AuthServiceConfig.default.copy(maxSessions = 2)
      for
        auth   <- authService(config)
        first  <- login(auth)
        second <- login(auth)
        third  <- login(auth)
        firstByCookie <- auth.authenticate(first.cookieToken)
        firstByPublic <- auth.resume(first.currentSession.publicSessionId)
        secondByCookie <- auth.authenticate(second.cookieToken)
        thirdByCookie  <- auth.authenticate(third.cookieToken)
      yield assertTrue(
        firstByCookie.isEmpty,
        firstByPublic.isEmpty,
        secondByCookie.isDefined,
        thirdByCookie.isDefined
      )
    },
    test("secure-cookie config defaults to false when the environment variable is absent") {
      for
        config <- AuthHttpConfig.fromEnvironment(Map.empty)
      yield assertTrue(!config.secureCookies)
    },
    test("secure-cookie config accepts case-insensitive true") {
      for
        configs <- ZIO.foreach(List("true", "TRUE", "TrUe"))(value =>
                     AuthHttpConfig.fromEnvironment(
                       Map(AuthHttpConfig.SecureCookiesEnvironmentVariable -> value)
                     )
                   )
      yield assertTrue(configs.forall(_.secureCookies))
    },
    test("secure-cookie config accepts case-insensitive false") {
      for
        configs <- ZIO.foreach(List("false", "FALSE", "FaLsE"))(value =>
                     AuthHttpConfig.fromEnvironment(
                       Map(AuthHttpConfig.SecureCookiesEnvironmentVariable -> value)
                     )
                   )
      yield assertTrue(configs.forall(config => !config.secureCookies))
    },
    test("secure-cookie config rejects supplied invalid values without echoing them") {
      val invalidValues = List("not-a-boolean-secret", " true ", "1", "")
      val expectedMessage =
        s"${AuthHttpConfig.SecureCookiesEnvironmentVariable} must be either true or false when set"
      for
        results <- ZIO.foreach(invalidValues)(invalidValue =>
                     AuthHttpConfig
                       .fromEnvironment(
                         Map(AuthHttpConfig.SecureCookiesEnvironmentVariable -> invalidValue)
                       )
                       .either
                   )
      yield assertTrue(
        results.forall(_.left.exists(_.getMessage == expectedMessage)),
        !expectedMessage.contains(invalidValues.head)
      )
    },
    test("HTTP bootstrap creates a hardened pre-authentication cookie") {
      for
        auth <- authService
        response <- run(
                      httpRoutes(auth),
                      Request.get(url("/auth/login/bootstrap"))
                    )
        cookie = responseCookies(response).find(
                   _.name == AuthHttpRoutes.LoginContextCookieName
                 )
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(
          _.url.encode == ExamplesRoutes.login.location.href
        ),
        cookie.exists(_.path.contains(Path.root)),
        cookie.exists(_.isHttpOnly),
        cookie.exists(_.isSecure),
        cookie.exists(_.sameSite.contains(Cookie.SameSite.Lax)),
        cookie.exists(_.maxAge.isDefined)
      )
    },
    test("HTTP login binds CSRF to pre-auth cookie and rotates into a session cookie") {
      for
        auth     <- authService
        prepared <- beginHttpLogin(auth)
        request = loginRequest(
                    prepared,
                    AuthHttpRoutes.LoginCsrfField -> prepared.context.csrfToken.value,
                    AuthHttpRoutes.EmailField     -> DemoEmail,
                    AuthHttpRoutes.PasswordField  -> DemoPassword
                  )
        response <- run(httpRoutes(auth), request)
        cookies   = responseCookies(response)
        sessionCookie = cookies.find(_.name == AuthHttpRoutes.SessionCookieName)
        loginCookie   = cookies.find(_.name == AuthHttpRoutes.LoginContextCookieName)
        authenticated <- ZIO.foreach(sessionCookie)(value =>
                           auth.authenticate(SessionCookieToken(value.content))
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
        loginCookie.exists(_.content.isEmpty),
        loginCookie.exists(_.maxAge.contains(Duration.Zero)),
        authenticated.flatten.isDefined
      )
    },
    test("HTTP login failures use one generic redirect without setting a session cookie") {
      for
        auth     <- authService
        prepared <- beginHttpLogin(auth)
        request = loginRequest(
                    prepared,
                    AuthHttpRoutes.LoginCsrfField -> prepared.context.csrfToken.value,
                    AuthHttpRoutes.EmailField     -> DemoEmail,
                    AuthHttpRoutes.PasswordField  -> "incorrect"
                  )
        response <- run(httpRoutes(auth), request)
        redirect = response.header(Header.Location).map(_.url.encode)
      yield assertTrue(
        response.status == Status.SeeOther,
        redirect.contains("/auth/login/bootstrap?invalid=true"),
        redirect.forall(value => !value.contains(DemoEmail) && !value.contains("incorrect")),
        responseCookies(response).forall(_.name != AuthHttpRoutes.SessionCookieName)
      )
    },
    test("HTTP login consumes its browser context when credentials are incomplete") {
      for
        auth     <- authService
        prepared <- beginHttpLogin(auth)
        request = loginRequest(
                    prepared,
                    AuthHttpRoutes.LoginCsrfField -> prepared.context.csrfToken.value,
                    AuthHttpRoutes.EmailField     -> DemoEmail
                  )
        response <- run(httpRoutes(auth), request)
        reuse    <- auth.prepareLogin(prepared.bootstrap.cookieToken)
      yield assertTrue(response.status == Status.SeeOther, reuse.isEmpty)
    },
    test("HTTP missing login CSRF fails generically and consumes the browser context") {
      for
        auth     <- authService
        prepared <- beginHttpLogin(auth)
        request = loginRequest(
                    prepared,
                    AuthHttpRoutes.EmailField    -> DemoEmail,
                    AuthHttpRoutes.PasswordField -> DemoPassword
                  )
        response <- run(httpRoutes(auth), request)
        reuse    <- auth.prepareLogin(prepared.bootstrap.cookieToken)
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(
          _.url.encode == "/auth/login/bootstrap?invalid=true"
        ),
        responseCookies(response).forall(_.name != AuthHttpRoutes.SessionCookieName),
        reuse.isEmpty
      )
    },
    test("HTTP invalid login CSRF fails generically and consumes the browser context") {
      for
        auth     <- authService
        prepared <- beginHttpLogin(auth)
        request = loginRequest(
                    prepared,
                    AuthHttpRoutes.LoginCsrfField -> "invalid",
                    AuthHttpRoutes.EmailField     -> DemoEmail,
                    AuthHttpRoutes.PasswordField  -> DemoPassword
                  )
        response <- run(httpRoutes(auth), request)
        reuse    <- auth.prepareLogin(prepared.bootstrap.cookieToken)
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(
          _.url.encode == "/auth/login/bootstrap?invalid=true"
        ),
        responseCookies(response).forall(_.name != AuthHttpRoutes.SessionCookieName),
        reuse.isEmpty
      )
    },
    test("HTTP invalid logout preserves the session and emits no cookie") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        request = postForm(
                    "/auth/logout",
                    AuthHttpRoutes.LogoutCsrfField -> "invalid"
                  ).addCookie(
                    Cookie.Request(AuthHttpRoutes.SessionCookieName, loggedIn.cookieToken.value)
                  )
        response      <- run(httpRoutes(auth), request)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(
        response.status == Status.Forbidden,
        responseCookies(response).isEmpty,
        authenticated.contains(loggedIn.currentSession)
      )
    },
    test("HTTP logout revokes the session and expires its cookie") {
      for
        auth     <- authService
        loggedIn <- login(auth)
        request = postForm(
                    "/auth/logout",
                    AuthHttpRoutes.LogoutCsrfField -> loggedIn.currentSession.logoutCsrfToken.value
                  ).addCookie(
                    Cookie.Request(AuthHttpRoutes.SessionCookieName, loggedIn.cookieToken.value)
                  )
        response <- run(httpRoutes(auth), request)
        cookie = responseCookies(response).find(_.name == AuthHttpRoutes.SessionCookieName)
        authenticated <- auth.authenticate(loggedIn.cookieToken)
      yield assertTrue(
        response.status == Status.SeeOther,
        response.header(Header.Location).exists(_.url.encode == ExamplesRoutes.home.location.href),
        cookie.exists(_.content.isEmpty),
        cookie.exists(_.path.contains(Path.root)),
        cookie.exists(_.isHttpOnly),
        cookie.exists(_.isSecure),
        cookie.exists(_.maxAge.contains(Duration.Zero)),
        cookie.exists(_.sameSite.contains(Cookie.SameSite.Lax)),
        authenticated.isEmpty
      )
    },
    test("login disconnected mount redirects a missing pre-auth cookie") {
      for
        auth <- authService
        result <- LoginMountAspect.prepared
                    .disconnected(loginMountRequest(None), ())
                    .provideEnvironment(ZEnvironment(auth))
                    .either
      yield assertTrue(result.swap.toOption.exists(redirectsToLoginBootstrap))
    },
    test("login disconnected mount redirects an invalid pre-auth cookie") {
      for
        auth <- authService
        result <- LoginMountAspect.prepared
                    .disconnected(
                      loginMountRequest(Some(LoginContextCookieToken("invalid"))),
                      ()
                    )
                    .provideEnvironment(ZEnvironment(auth))
                    .either
      yield assertTrue(result.swap.toOption.exists(redirectsToLoginBootstrap))
    },
    test("login disconnected mount produces claims and the typed login context") {
      for
        auth     <- authService
        prepared <- prepareLogin(auth)
        result <- LoginMountAspect.prepared
                    .disconnected(
                      loginMountRequest(Some(prepared.bootstrap.cookieToken)),
                      ()
                    )
                    .provideEnvironment(ZEnvironment(auth))
      yield assertTrue(
        result._1 == LoginClaims(prepared.context.publicId),
        result._2 == prepared.context
      )
    },
    test("login connected mount resumes the typed context without the original cookie") {
      for
        auth     <- authService
        prepared <- prepareLogin(auth)
        connectedRequest = loginMountRequest(None)
        result <- LoginMountAspect.prepared
                    .connected(
                      LoginClaims(prepared.context.publicId),
                      connectedRequest,
                      ()
                    )
                    .provideEnvironment(ZEnvironment(auth))
      yield assertTrue(
        result == prepared.context,
        connectedRequest.request.cookie(AuthHttpRoutes.LoginContextCookieName).isEmpty
      )
    },
    test("login disconnected mount redirects an expired context") {
      val config = AuthServiceConfig.default.copy(loginContextTtl = 1.minute)
      for
        auth     <- authService(config)
        prepared <- prepareLogin(auth)
        _        <- TestClock.adjust(config.loginContextTtl)
        result <- LoginMountAspect.prepared
                    .disconnected(
                      loginMountRequest(Some(prepared.bootstrap.cookieToken)),
                      ()
                    )
                    .provideEnvironment(ZEnvironment(auth))
                    .either
      yield assertTrue(result.swap.toOption.exists(redirectsToLoginBootstrap))
    },
    test("login connected mount redirects an evicted context") {
      val config = AuthServiceConfig.default.copy(maxLoginContexts = 1)
      for
        auth     <- authService(config)
        evicted  <- prepareLogin(auth)
        _        <- prepareLogin(auth)
        result <- LoginMountAspect.prepared
                    .connected(
                      LoginClaims(evicted.context.publicId),
                      loginMountRequest(None),
                      ()
                    )
                    .provideEnvironment(ZEnvironment(auth))
                    .either
      yield assertTrue(result.swap.toOption.exists(redirectsToLoginBootstrap))
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
        _ <- auth.logout(loggedIn.cookieToken, loggedIn.currentSession.logoutCsrfToken)
        result <- AuthMountAspect.authenticated
                    .connected(
                      claims,
                      LiveMountRequest((), Request.get(url("/auth/profile"))),
                      ()
                    )
                    .provideEnvironment(ZEnvironment(auth))
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
