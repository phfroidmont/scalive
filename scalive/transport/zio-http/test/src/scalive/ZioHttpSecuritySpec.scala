package scalive

import java.time.Duration

import zio.*
import zio.test.*

import scalive.runtime.contracts.*
import scalive.upload.*

object ZioHttpSecuritySpec extends ZIOSpecDefault:
  private val secret         = "0123456789abcdef0123456789abcdef"
  private val allowedOrigins = Set(WebSocketOrigin.https("scalive.test"))

  private def config(maxAge: Duration = Duration.ofMinutes(30)) =
    ZioHttpConfig(
      secret,
      maxAge,
      secureCookie = true,
      allowedWebSocketOrigins = allowedOrigins
    ).toOption.get

  private def tamperSignature(token: String): String =
    val signatureStart = token.lastIndexOf('.') + 1
    val replacement    = if token.charAt(signatureStart) == 'A' then 'B' else 'A'
    token.updated(signatureStart, replacement)

  private def uploadClaims(
    lifecycle: Long = 7L,
    epoch: Long = 3L,
    component: Option[Long] = None,
    uploadRef: String = "upload-1",
    entryRef: String = "entry-1",
    generation: Long = 2L,
    topic: String = "lvu:entry-1"
  ): UploadCredentialClaims =
    UploadCredentialClaims(
      LifecycleId(lifecycle),
      Epoch(epoch),
      component.map(ComponentInstanceId(_)),
      UploadRef(uploadRef),
      UploadEntryRef(entryRef),
      generation,
      topic
    )

  def spec = suite("ZioHttpSecurity")(
    test("websocket origins normalize exact scheme, host, and effective port") {
      val expected = WebSocketOrigin.https("example.com")
      val explicit = WebSocketOrigin.parse("https://example.com:443")
      val http     = WebSocketOrigin.parse("http://example.com:80")
      val cased    = WebSocketOrigin.parse("HTTPS://EXAMPLE.COM")
      val custom   = WebSocketOrigin.parse("https://example.com:8443")
      val ipv6     = WebSocketOrigin.parse("http://[::1]:8080")
      val expanded = WebSocketOrigin.http("0:0:0:0:0:0:0:1", 8080)
      val ipv4     = WebSocketOrigin.parse("http://127.0.0.1")
      val mapped   = WebSocketOrigin.https("::ffff:192.0.2.128")

      assertTrue(
        explicit.contains(expected),
        http.contains(WebSocketOrigin.http("example.com")),
        cased.contains(expected),
        expected.toString == "https://example.com",
        custom.exists(origin => origin.port == 8443 && origin.toString == "https://example.com:8443"),
        ipv6.contains(expanded),
        expanded.host == "::1",
        expanded.toString == "http://[::1]:8080",
        ipv4.exists(_.host == "127.0.0.1"),
        mapped.toString == "https://[::ffff:c000:280]"
      )
    },
    test("websocket origins reject values that are not serialized browser origins") {
      val invalid = Vector(
        "",
        "null",
        "ws://example.com",
        "https://user@example.com",
        "https://example.com/",
        "https://example.com?query",
        "https://example.com#fragment",
        "https://example.com:",
        "https://example.com:99999",
        "https://%65xample.com",
        "https://[::1",
        "http://[0:0:0:0:0:0:0:1]",
        "http://127.1",
        "http://0177.0.0.1",
        "http://2130706433",
        " https://example.com",
        "https://bücher.example",
        "https://example.com, https://other.example"
      )
      val accepted = invalid.filter(WebSocketOrigin.parse(_).isRight)

      assertTrue(
        accepted.isEmpty,
        WebSocketOrigin.httpsEither("example.com", 0) == Left(WebSocketOrigin.Error.InvalidPort(0))
      )
    },
    test("config validates its secret, maximum age, and websocket origins") {
      val short = ZioHttpConfig(
        "short",
        Duration.ofMinutes(1),
        secureCookie = true,
        allowedWebSocketOrigins = allowedOrigins
      )
      val zero = ZioHttpConfig(
        secret,
        Duration.ZERO,
        secureCookie = true,
        allowedWebSocketOrigins = allowedOrigins
      )
      val empty = ZioHttpConfig(
        secret,
        Duration.ofMinutes(1),
        secureCookie = true,
        allowedWebSocketOrigins = Set.empty
      )
      val unusable = ZioHttpConfig(
        secret,
        Duration.ofMinutes(1),
        secureCookie = true,
        allowedWebSocketOrigins = Set(null)
      )
      val valid = config()

      assertTrue(
        short == Left(ZioHttpConfig.Error.SecretTooShort(5)),
        zero == Left(ZioHttpConfig.Error.NonPositiveSessionMaxAge),
        empty == Left(ZioHttpConfig.Error.NoAllowedWebSocketOrigins),
        unusable == Left(ZioHttpConfig.Error.NoAllowedWebSocketOrigins),
        !valid.toString.contains(secret),
        valid.toString.contains("<redacted>"),
        valid.toString.contains("https://scalive.test"),
        valid == config()
      )
    },
    test("rejects a tampered HMAC") {
      for
        token <- ZioHttpSecurity
                   .issueSession(config(), "root", LifecycleId(1L), 2, "https://example.test/a")
        result <- ZioHttpSecurity.verifySession(config(), tamperSignature(token)).either
      yield assertTrue(result == Left(ZioHttpSecurity.Error.InvalidSignature))
    },
    test("separates token purposes") {
      for
        token <- ZioHttpSecurity
                   .issueSession(config(), "root", LifecycleId(1L), 2, "https://example.test/a")
        result <- ZioHttpSecurity.verifyStatic(config(), token).either
      yield assertTrue(
        result == Left(
          ZioHttpSecurity.Error.PurposeMismatch(expected = "static", actual = "session")
        )
      )
    },
    test("roundtrips exact purpose-separated nested registration claims") {
      val expected = NestedCredentialClaims(
        NestedRegistrationId(11L),
        NestedRegistrationEpoch(3L),
        LifecycleId(7L),
        Epoch(2L),
        NestedTopic("lv:child"),
        childLifecycle = Some(LifecycleId(13L))
      )
      for
        issued      <- ZioHttpSecurity.issueNested(config(), expected)
        joinClaims  <- ZioHttpSecurity.verifyNestedJoin(config(), issued.join.value)
        staticToken  = issued.static.get
        staticClaims <- ZioHttpSecurity.verifyNestedStatic(config(), staticToken.value)
        wrongPurpose <- ZioHttpSecurity.verifyNestedJoin(config(), staticToken.value).either
      yield assertTrue(
        joinClaims == expected,
        staticClaims == expected,
        issued.join.value != staticToken.value,
        wrongPurpose == Left(
          ZioHttpSecurity.Error.PurposeMismatch(
            expected = "nested-join",
            actual = "nested-static"
          )
        )
      )
    },
    test("roundtrips exact root and component upload credential claims") {
      val root      = uploadClaims()
      val component = uploadClaims(component = Some(19L))
      for
        rootToken      <- ZioHttpSecurity.issueUploadCredential(config(), root)
        componentToken <- ZioHttpSecurity.issueUploadCredential(config(), component)
        verifiedRoot   <- ZioHttpSecurity.verifyUploadCredential(config(), rootToken)
        verifiedComponent <- ZioHttpSecurity.verifyUploadCredential(config(), componentToken)
      yield assertTrue(verifiedRoot == root, verifiedComponent == component)
    },
    test("rejects tampered and wrong-purpose upload credentials") {
      for
        uploadToken <- ZioHttpSecurity.issueUploadCredential(config(), uploadClaims())
        sessionToken <- ZioHttpSecurity
                          .issueSession(config(), "root", LifecycleId(1L), 0, "/")
        tampered <- ZioHttpSecurity
                      .verifyUploadCredential(config(), tamperSignature(uploadToken))
                      .either
        wrongPurpose <- ZioHttpSecurity.verifyUploadCredential(config(), sessionToken).either
      yield assertTrue(
        tampered == Left(ZioHttpSecurity.Error.InvalidSignature),
        wrongPurpose == Left(
          ZioHttpSecurity.Error.PurposeMismatch(expected = "upload-join", actual = "session")
        )
      )
    },
    test("expires upload credentials") {
      val shortConfig = config(Duration.ofSeconds(10))
      for
        token   <- ZioHttpSecurity.issueUploadCredential(shortConfig, uploadClaims())
        _       <- TestClock.adjust(11.seconds)
        expired <- ZioHttpSecurity.verifyUploadCredential(shortConfig, token).either
      yield assertTrue(expired == Left(ZioHttpSecurity.Error.Expired))
    },
    test("rejects invalid upload credential identities, generation, refs, and topic") {
      val invalidClaims = Vector(
        uploadClaims(lifecycle = 0L),
        uploadClaims(epoch = 0L),
        uploadClaims(component = Some(0L)),
        uploadClaims(generation = 0L),
        uploadClaims(uploadRef = ""),
        uploadClaims(entryRef = "", topic = "lvu:"),
        uploadClaims(topic = "")
      )
      for
        tokens  <- ZIO.foreach(invalidClaims)(ZioHttpSecurity.issueUploadCredential(config(), _))
        results <- ZIO.foreach(tokens)(ZioHttpSecurity.verifyUploadCredential(config(), _).either)
      yield assertTrue(results.forall(_.isLeft))
    },
    test("rejects an upload topic whose entry ref differs from the claim") {
      for
        token <- ZioHttpSecurity.issueUploadCredential(
                   config(),
                   uploadClaims(topic = "lvu:other-entry")
                 )
        result <- ZioHttpSecurity.verifyUploadCredential(config(), token).either
      yield assertTrue(
        result == Left(
          ZioHttpSecurity.Error.InvalidClaims("upload topic must exactly match the entry ref")
        )
      )
    },
    test("rejects expired and future-issued tokens") {
      for
        _            <- TestClock.setTime(java.time.Instant.ofEpochSecond(100))
        token <- ZioHttpSecurity
                   .issueSession(
                     config(Duration.ofSeconds(10)),
                     "root",
                     LifecycleId(1L),
                     0,
                     "/"
                   )
        _            <- TestClock.adjust(11.seconds)
        expired      <- ZioHttpSecurity.verifySession(config(Duration.ofSeconds(10)), token).either
        _            <- TestClock.setTime(java.time.Instant.EPOCH)
        futureResult <- ZioHttpSecurity.verifySession(config(Duration.ofSeconds(10)), token).either
      yield assertTrue(
        expired == Left(ZioHttpSecurity.Error.Expired),
        futureResult == Left(ZioHttpSecurity.Error.IssuedInFuture)
      )
    },
    test("roundtrips the same root claims in purpose-separated session and static tokens") {
      for
        now <- Clock.currentTime(java.util.concurrent.TimeUnit.SECONDS)
        sessionToken <- ZioHttpSecurity.issueSession(
                          config(),
                          rootId = "root-42",
                          lifecycle = LifecycleId(42L),
                          routeIndex = 7,
                          canonicalUrl = "https://example.test/a?x=1",
                          routeIdentity = "7:GET /a",
                          sessionIdentity = Some("admin"),
                          rootLayoutKey = "root:v2",
                          sessionMountClaims = Vector("session-claim"),
                          initialFlash = Map("notice" -> "saved"),
                          nestedLifecycles = Map("sticky-child" -> 43L)
                        )
        staticToken <- ZioHttpSecurity.issueStatic(
                         config(),
                         rootId = "root-42",
                         lifecycle = LifecycleId(42L),
                         routeIndex = 7,
                         canonicalUrl = "https://example.test/a?x=1",
                         routeIdentity = "7:GET /a",
                         sessionIdentity = Some("admin"),
                         rootLayoutKey = "root:v2",
                         sessionMountClaims = Vector("session-claim"),
                         initialFlash = Map("notice" -> "saved"),
                         nestedLifecycles = Map("sticky-child" -> 43L)
                       )
        sessionClaims <- ZioHttpSecurity.verifySession(config(), sessionToken)
        staticClaims  <- ZioHttpSecurity.verifyStatic(config(), staticToken)
        expected = ZioHttpSecurity.RootClaims(
                     rootId = "root-42",
                     lifecycle = 42L,
                     routeIndex = 7,
                     canonicalUrl = "https://example.test/a?x=1",
                     routeIdentity = "7:GET /a",
                     sessionIdentity = Some("admin"),
                     rootLayoutKey = "root:v2",
                     sessionMountClaims = Vector("session-claim"),
                     issuedAtEpochSecond = now,
                     initialFlash = Map("notice" -> "saved"),
                     nestedLifecycles = Map("sticky-child" -> 43L)
                   )
      yield assertTrue(
        sessionClaims == expected,
        staticClaims == expected,
        sessionClaims.sessionMountClaims == Vector("session-claim"),
        sessionToken != staticToken
      )
    },
    test("flash tokens are purpose-bound, omit empty values, and expire after sixty seconds") {
      for
        empty <- ZioHttpSecurity.issueFlash(config(), Map.empty)
        token <- ZioHttpSecurity.issueFlash(config(), Map("notice" -> "saved")).someOrFail(
                   AssertionError("non-empty flash did not produce a token")
                 )
        values <- ZioHttpSecurity.verifyFlash(config(), token)
        wrongPurpose <- ZioHttpSecurity.verifySession(config(), token).either
        _             <- TestClock.adjust(61.seconds)
        expired       <- ZioHttpSecurity.verifyFlash(config(), token).either
      yield assertTrue(
        empty.isEmpty,
        values == Map("notice" -> "saved"),
        wrongPurpose == Left(
          ZioHttpSecurity.Error.PurposeMismatch(expected = "session", actual = "flash")
        ),
        expired == Left(ZioHttpSecurity.Error.Expired)
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
