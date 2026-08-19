package scalive

import java.time.Duration

import zio.*
import zio.test.*

object ZioHttpSecuritySpec extends ZIOSpecDefault:
  private val secret = "0123456789abcdef0123456789abcdef"

  private def config(maxAge: Duration = Duration.ofMinutes(30)) =
    ZioHttpConfig(secret, maxAge, secureCookie = true).toOption.get

  private def tamperSignature(token: String): String =
    val signatureStart = token.lastIndexOf('.') + 1
    val replacement    = if token.charAt(signatureStart) == 'A' then 'B' else 'A'
    token.updated(signatureStart, replacement)

  def spec = suite("ZioHttpSecurity")(
    test("config validates its secret and maximum age and redacts the secret") {
      val short = ZioHttpConfig("short", Duration.ofMinutes(1), secureCookie = true)
      val zero  = ZioHttpConfig(secret, Duration.ZERO, secureCookie = true)
      val valid = config()

      assertTrue(
        short == Left(ZioHttpConfig.Error.SecretTooShort(5)),
        zero == Left(ZioHttpConfig.Error.NonPositiveSessionMaxAge),
        valid.toString == "ZioHttpConfig(signingSecret=<redacted>, sessionMaxAge=PT30M, secureCookie=true)",
        !valid.toString.contains(secret),
        valid == config()
      )
    },
    test("rejects a tampered HMAC") {
      for
        token  <- ZioHttpSecurity.issueSession(config(), "root", 2, "https://example.test/a")
        result <- ZioHttpSecurity.verifySession(config(), tamperSignature(token)).either
      yield assertTrue(result == Left(ZioHttpSecurity.Error.InvalidSignature))
    },
    test("separates token purposes") {
      for
        token  <- ZioHttpSecurity.issueSession(config(), "root", 2, "https://example.test/a")
        result <- ZioHttpSecurity.verifyStatic(config(), token).either
      yield assertTrue(
        result == Left(
          ZioHttpSecurity.Error.PurposeMismatch(expected = "static", actual = "session")
        )
      )
    },
    test("rejects expired and future-issued tokens") {
      for
        _            <- TestClock.setTime(java.time.Instant.ofEpochSecond(100))
        token        <- ZioHttpSecurity.issueSession(config(Duration.ofSeconds(10)), "root", 0, "/")
        _            <- TestClock.adjust(11.seconds)
        expired      <- ZioHttpSecurity.verifySession(config(Duration.ofSeconds(10)), token).either
        _            <- TestClock.setTime(java.time.Instant.EPOCH)
        futureResult <- ZioHttpSecurity.verifySession(config(Duration.ofSeconds(10)), token).either
      yield assertTrue(
        expired == Left(ZioHttpSecurity.Error.Expired),
        futureResult == Left(ZioHttpSecurity.Error.IssuedInFuture)
      )
    },
    test("roundtrips root claims exactly") {
      for
        now    <- Clock.currentTime(java.util.concurrent.TimeUnit.SECONDS)
        token  <- ZioHttpSecurity.issueStatic(
                    config(),
                    "root-42",
                    7,
                    "https://example.test/a?x=1",
                    "7:GET /a",
                    Some("admin"),
                    "root:v2",
                    Vector("session-claim"),
                    Vector("route-claim"),
                    hasRouteClaims = true
                  )
        claims <- ZioHttpSecurity.verifyStatic(config(), token)
      yield assertTrue(
        claims == ZioHttpSecurity.RootClaims(
          rootId = "root-42",
          routeIndex = 7,
          canonicalUrl = "https://example.test/a?x=1",
          routeIdentity = "7:GET /a",
          sessionIdentity = Some("admin"),
          rootLayoutKey = "root:v2",
          sessionMountClaims = Vector("session-claim"),
          routeMountClaims = Vector("route-claim"),
          hasRouteClaims = true,
          issuedAtEpochSecond = now
        )
      )
    },
    test("CSRF cookie and render tokens hide the shared secret and verify exactly") {
      for
        csrf   <- ZioHttpSecurity.issueCsrf(config())
        claims <- ZioHttpSecurity.verifyCsrf(config(), csrf.token, csrf.cookieToken)
      yield assertTrue(
        csrf.cookieToken != csrf.token,
        csrf.cookieToken.split("\\.")(2) == csrf.token.split("\\.")(2),
        !csrf.cookieToken.contains(claims.browserSecret),
        !csrf.token.contains(claims.browserSecret),
        claims.browserSecret.length == 43
      )
    },
    test("rejects a cookie issued for another browser secret") {
      for
        first    <- ZioHttpSecurity.issueCsrf(config())
        second   <- ZioHttpSecurity.issueCsrf(config())
        mismatch <- ZioHttpSecurity.verifyCsrf(config(), first.token, second.cookieToken).either
      yield assertTrue(mismatch == Left(ZioHttpSecurity.Error.CsrfSecretMismatch))
    },
    test("rejects a tampered CSRF cookie") {
      for
        csrf   <- ZioHttpSecurity.issueCsrf(config())
        result <- ZioHttpSecurity
                    .verifyCsrf(config(), csrf.token, tamperSignature(csrf.cookieToken))
                    .either
      yield assertTrue(result == Left(ZioHttpSecurity.Error.InvalidSignature))
    },
    test("separates CSRF cookie and render purposes") {
      for
        csrf   <- ZioHttpSecurity.issueCsrf(config())
        result <- ZioHttpSecurity.verifyCsrf(config(), csrf.token, csrf.token).either
      yield assertTrue(
        result == Left(
          ZioHttpSecurity.Error.PurposeMismatch(expected = "csrf-cookie", actual = "csrf")
        )
      )
    },
    test("refresh preserves the cookie and supports independent render tokens") {
      for
        issued   <- ZioHttpSecurity.issueCsrf(config())
        _        <- TestClock.adjust(1.second)
        first    <- ZioHttpSecurity.refreshCsrf(config(), issued.cookieToken)
        _        <- TestClock.adjust(1.second)
        second   <- ZioHttpSecurity.refreshCsrf(config(), issued.cookieToken)
        firstOk  <- ZioHttpSecurity.verifyCsrf(config(), first.token, first.cookieToken).either
        secondOk <- ZioHttpSecurity.verifyCsrf(config(), second.token, second.cookieToken).either
      yield assertTrue(
        first.cookieToken == issued.cookieToken,
        second.cookieToken == issued.cookieToken,
        first.token != second.token,
        firstOk.isRight,
        secondOk.isRight
      )
    },
    test("refresh rejects an invalid cookie with a typed error") {
      for
        csrf   <- ZioHttpSecurity.issueCsrf(config())
        result <- ZioHttpSecurity.refreshCsrf(config(), csrf.token).either
      yield assertTrue(
        result == Left(
          ZioHttpSecurity.Error.PurposeMismatch(expected = "csrf-cookie", actual = "csrf")
        )
      )
    },
    test("expires both CSRF token forms") {
      val shortConfig = config(Duration.ofSeconds(10))
      for
        csrf         <- ZioHttpSecurity.issueCsrf(shortConfig)
        _            <- TestClock.adjust(11.seconds)
        verification <- ZioHttpSecurity.verifyCsrf(shortConfig, csrf.token, csrf.cookieToken).either
        refresh      <- ZioHttpSecurity.refreshCsrf(shortConfig, csrf.cookieToken).either
      yield assertTrue(
        verification == Left(ZioHttpSecurity.Error.Expired),
        refresh == Left(ZioHttpSecurity.Error.Expired)
      )
    }
  )
end ZioHttpSecuritySpec
