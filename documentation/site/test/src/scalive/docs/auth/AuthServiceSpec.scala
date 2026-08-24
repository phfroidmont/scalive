package scalive.docs.auth

import zio.*
import zio.test.*

object AuthServiceSpec extends ZIOSpecDefault:
  private val visitor = VisitorToken("visitor-one")

  private def service(config: AuthServiceConfig = AuthServiceConfig.default): UIO[AuthService] =
    ZIO.service[AuthService].provide(AuthService.live(config))

  private def login(auth: AuthService, visitorToken: VisitorToken = visitor) =
    auth.login(visitorToken, LoginCredentials(AuthService.DemoEmail, AuthService.DemoPassword))

  def spec = suite("AuthServiceSpec")(
    test("creates an opaque session for the fixed lab credentials") {
      for
        auth   <- service()
        result <- login(auth)
        loggedIn <- ZIO
                      .fromOption(result.toOption)
                      .orDieWith(_ => new AssertionError("demo login failed"))
        authenticated <- auth.authenticate(loggedIn.cookieToken)
        resumed       <- auth.resume(loggedIn.currentSession.publicSessionId)
      yield assertTrue(
        authenticated.contains(loggedIn.currentSession),
        resumed.contains(loggedIn.currentSession),
        loggedIn.cookieToken.value != loggedIn.currentSession.publicSessionId.value,
        loggedIn.cookieToken.value.length >= 43
      )
    },
    test("expires and explicitly resets a visitor session") {
      val config = AuthServiceConfig.default.copy(sessionTtl = 1.minute)
      for
        auth     <- service(config)
        result   <- login(auth)
        loggedIn <- ZIO
                      .fromOption(result.toOption)
                      .orDieWith(_ => new AssertionError("demo login failed"))
        resetId  <- auth.reset(visitor, Some(loggedIn.cookieToken))
        reset    <- auth.authenticate(loggedIn.cookieToken)
        next     <- login(auth)
        active <- ZIO
                    .fromOption(next.toOption)
                    .orDieWith(_ => new AssertionError("login after reset failed"))
        _        <- TestClock.adjust(config.sessionTtl)
        expiredId <- auth.reset(visitor, Some(active.cookieToken))
        expired  <- auth.authenticate(active.cookieToken)
      yield assertTrue(
        resetId.contains(loggedIn.currentSession.publicSessionId),
        expiredId.contains(active.currentSession.publicSessionId),
        reset.isEmpty,
        expired.isEmpty
      )
    },
    test("bounds attempts independently by visitor") {
      val config = AuthServiceConfig.default.copy(maxAttempts = 2, attemptWindow = 1.minute)
      val invalid = LoginCredentials(AuthService.DemoEmail, "incorrect")
      for
        auth   <- service(config)
        first  <- auth.login(visitor, invalid)
        second <- auth.login(visitor, invalid)
        blocked <- login(auth)
        other   <- login(auth, VisitorToken("visitor-two"))
        _       <- TestClock.adjust(config.attemptWindow)
        retried <- login(auth)
      yield assertTrue(
        first == LoginDecision.Invalid,
        second == LoginDecision.Invalid,
        blocked == LoginDecision.RateLimited,
        other.toOption.nonEmpty,
        retried.toOption.nonEmpty
      )
    },
    test("bounds stored sessions and visitor attempt records") {
      val config = AuthServiceConfig.default.copy(maxSessions = 2, maxVisitors = 2)
      val invalid = LoginCredentials(AuthService.DemoEmail, "incorrect")
      for
        auth   <- service(config)
        first  <- login(auth, VisitorToken("session-one"))
        second <- login(auth, VisitorToken("session-two"))
        third  <- login(auth, VisitorToken("session-three"))
        one <- ZIO
                 .fromOption(first.toOption)
                 .orDieWith(_ => new AssertionError("first login failed"))
        two <- ZIO
                 .fromOption(second.toOption)
                 .orDieWith(_ => new AssertionError("second login failed"))
        three <- ZIO
                   .fromOption(third.toOption)
                   .orDieWith(_ => new AssertionError("third login failed"))
        firstSession  <- auth.authenticate(one.cookieToken)
        secondSession <- auth.authenticate(two.cookieToken)
        thirdSession  <- auth.authenticate(three.cookieToken)
        _ <- auth.login(VisitorToken("attempt-one"), invalid)
        _ <- auth.login(VisitorToken("attempt-two"), invalid)
        _ <- auth.login(VisitorToken("attempt-three"), invalid)
        counts <- auth.recordCounts
      yield assertTrue(
        firstSession.isEmpty,
        secondSession.nonEmpty,
        thirdSession.nonEmpty,
        counts == AuthRecordCounts(sessions = 2, visitors = 2)
      )
    }
  )
end AuthServiceSpec
