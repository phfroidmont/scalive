package scalive

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import zio.*
import zio.json.*

import scalive.runtime.contracts.*
import scalive.upload.*

/** Authenticated upload join claims. Verification proves only that these claims were signed and are
  * fresh; [[ZioHttp]] must still admit them against the current live upload registry.
  */
final private[scalive] case class UploadCredentialClaims(
  lifecycleId: LifecycleId,
  epoch: Epoch,
  componentInstanceId: Option[ComponentInstanceId],
  uploadRef: UploadRef,
  entryRef: UploadEntryRef,
  registrationGeneration: Long,
  expectedTopic: String)

/** Validated security configuration for the ZIO HTTP transport.
  *
  * This is intentionally a top-level type until [[ZioHttp]] can expose it as `ZioHttp.Config`.
  *
  * @param sessionMaxAge
  *   The maximum accepted age for framework-issued session and CSRF values.
  * @param secureCookie
  *   Whether framework cookies carry the `Secure` attribute.
  * @param allowedWebSocketOrigins
  *   The exact canonical browser origins admitted at the WebSocket upgrade boundary.
  * @param reloadOnRestart
  *   Whether a page rendered by an earlier server process reloads when it reconnects.
  * @param pageCache
  *   How browsers and caches may keep the responses served for pages.
  */
final class ZioHttpConfig private (
  private[scalive] val signingSecretBytes: Array[Byte],
  val sessionMaxAge: Duration,
  val secureCookie: Boolean,
  val allowedWebSocketOrigins: Set[WebSocketOrigin],
  val reloadOnRestart: Boolean,
  val pageCache: PageCache):
  override def equals(other: Any): Boolean = other match
    case that: ZioHttpConfig =>
      sessionMaxAge == that.sessionMaxAge &&
      secureCookie == that.secureCookie &&
      allowedWebSocketOrigins == that.allowedWebSocketOrigins &&
      reloadOnRestart == that.reloadOnRestart &&
      pageCache == that.pageCache &&
      MessageDigest.isEqual(signingSecretBytes, that.signingSecretBytes)
    case _ => false

  override def hashCode(): Int =
    var result = sessionMaxAge.hashCode()
    result = 31 * result + java.util.Arrays.hashCode(signingSecretBytes)
    result = 31 * result + java.lang.Boolean.hashCode(secureCookie)
    result = 31 * result + java.lang.Boolean.hashCode(reloadOnRestart)
    result = 31 * result + pageCache.hashCode()
    31 * result + allowedWebSocketOrigins.hashCode()

  /** Describes the configuration with the signing secret redacted, so it is safe to log. */
  override def toString: String =
    s"ZioHttpConfig(signingSecret=<redacted>, sessionMaxAge=$sessionMaxAge, secureCookie=$secureCookie, allowedWebSocketOrigins=$allowedWebSocketOrigins, reloadOnRestart=$reloadOnRestart, pageCache=$pageCache)"

/** The validating constructor for [[ZioHttpConfig]] and its errors. */
object ZioHttpConfig:
  /** Why [[ZioHttpConfig.apply]] rejected the settings.
    *
    * It is an exception with a readable message, so `ZIO.fromEither(ZioHttpConfig(...))` is a
    * `Task`.
    */
  enum Error(message: String) extends Exception(message):
    /** The signing secret contains fewer than 32 UTF-8 bytes.
      *
      * @param actualUtf8Bytes
      *   The length of the rejected secret in UTF-8 bytes.
      */
    case SecretTooShort(actualUtf8Bytes: Int)
        extends Error(
          s"The signing secret must contain at least 32 UTF-8 bytes, got $actualUtf8Bytes"
        )

    /** Session and CSRF values require a positive maximum age. */
    case NonPositiveSessionMaxAge extends Error("The session maximum age must be positive")

    /** The WebSocket policy contains no usable allowed origin. */
    case NoAllowedWebSocketOrigins extends Error("At least one WebSocket origin must be allowed")

  /** Validates the settings and creates a [[ZioHttpConfig]].
    *
    * `signingSecret` signs framework-issued values and must contain at least 32 UTF-8 bytes; every
    * replica must use the same secret, and changing it invalidates outstanding signed values.
    * `sessionMaxAge` must be positive, and `allowedWebSocketOrigins` must not be empty. A failed
    * check returns the matching [[Error]].
    *
    * `reloadOnRestart` is for development. With it, a page rendered before the server process
    * started reloads when it reconnects, so changed assets, root layouts, and views appear without
    * a manual refresh. Leave it off in deployments: every open page would reload after a deploy and
    * lose form input that LiveView would otherwise recover, and with several replicas a page
    * rendered by one would not join another.
    *
    * `pageCache` sets the `Cache-Control` of pages; the default lets only the browser keep them,
    * and it revalidates before reuse. See [[PageCache]].
    */
  def apply(
    signingSecret: String,
    sessionMaxAge: Duration,
    secureCookie: Boolean,
    allowedWebSocketOrigins: Set[WebSocketOrigin],
    reloadOnRestart: Boolean = false,
    pageCache: PageCache = PageCache.Revalidate
  ): Either[Error, ZioHttpConfig] =
    val secretBytes = signingSecret.getBytes(StandardCharsets.UTF_8)
    if secretBytes.length < 32 then Left(Error.SecretTooShort(secretBytes.length))
    else if sessionMaxAge.isZero || sessionMaxAge.isNegative then
      Left(Error.NonPositiveSessionMaxAge)
    else if allowedWebSocketOrigins.isEmpty || allowedWebSocketOrigins.exists(_ eq null) then
      Left(Error.NoAllowedWebSocketOrigins)
    else
      Right(
        new ZioHttpConfig(
          secretBytes.clone(),
          sessionMaxAge,
          secureCookie,
          allowedWebSocketOrigins,
          reloadOnRestart,
          pageCache
        )
      )

  /** Creates a configuration for running the application on this machine during development.
    *
    * It admits pages served at `http://localhost:port` and `http://127.0.0.1:port`, sends cookies
    * without `Secure`, signs with a random secret drawn on each call, and enables
    * `reloadOnRestart`, so restarting the server makes open pages reload. Never deploy it: build a
    * deployment's configuration with [[apply]] from its own settings. Fails when `port` is not a
    * TCP port.
    */
  def local(port: Int): IO[WebSocketOrigin.Error, ZioHttpConfig] =
    for
      localhost <- ZIO.fromEither(WebSocketOrigin.httpEither("localhost", port))
      loopback  <- ZIO.fromEither(WebSocketOrigin.httpEither("127.0.0.1", port))
      secret    <- ZIO.succeed {
                  val bytes = Array.ofDim[Byte](32)
                  SecureRandom().nextBytes(bytes)
                  bytes
                }
    yield new ZioHttpConfig(
      secret,
      Duration.ofDays(7),
      secureCookie = false,
      Set(localhost, loopback),
      reloadOnRestart = true,
      PageCache.Revalidate
    )
end ZioHttpConfig

private[scalive] object ZioHttpSecurity:
  final case class RootClaims(
    rootId: String,
    lifecycle: Long,
    routeIndex: Int,
    canonicalUrl: String,
    routeIdentity: String = "",
    sessionIdentity: Option[String] = None,
    rootLayoutKey: String = "scalive:default-root",
    sessionMountClaims: Vector[String] = Vector.empty,
    issuedAtEpochSecond: Long,
    serverInstance: String = "",
    initialFlash: Map[String, String] = Map.empty,
    nestedLifecycles: Map[String, Long] = Map.empty)
      derives JsonCodec

  final case class CsrfClaims(browserSecret: String, issuedAtEpochSecond: Long) derives JsonCodec
  final case class FlashClaims(values: Map[String, String], issuedAtEpochSecond: Long)
      derives JsonCodec
  final private case class ReloadStatusClaims(status: Int, issuedAtEpochSecond: Long)
      derives JsonCodec
  final case class NestedClaims(
    registration: Long,
    registrationEpoch: Long,
    parentLifecycle: Long,
    parentEpoch: Long,
    topic: String,
    childLifecycle: Option[Long],
    issuedAtEpochSecond: Long)
      derives JsonCodec
  final private case class UploadClaims(
    lifecycle: Long,
    epoch: Long,
    component: Option[Long],
    uploadRef: String,
    entryRef: String,
    registrationGeneration: Long,
    topic: String,
    issuedAtEpochSecond: Long)
      derives JsonCodec

  final case class IssuedCsrf(cookieToken: String, token: String)

  enum Error:
    case MalformedToken
    case UnsupportedVersion(version: String)
    case InvalidSignature
    case PurposeMismatch(expected: String, actual: String)
    case InvalidClaims(details: String)
    case IssuedInFuture
    case Expired
    case CsrfSecretMismatch

  private enum Purpose(val value: String):
    case Session      extends Purpose("session")
    case Static       extends Purpose("static")
    case CsrfCookie   extends Purpose("csrf-cookie")
    case Csrf         extends Purpose("csrf")
    case Flash        extends Purpose("flash")
    case NestedJoin   extends Purpose("nested-join")
    case NestedStatic extends Purpose("nested-static")
    case UploadJoin   extends Purpose("upload-join")
    case ReloadStatus extends Purpose("reload-status")

  private val Version       = "v1"
  private val HmacAlgorithm = "HmacSHA256"
  private val encoder       = Base64.getUrlEncoder.withoutPadding()
  private val decoder       = Base64.getUrlDecoder

  def issueSession(
    config: ZioHttpConfig,
    rootId: String,
    lifecycle: LifecycleId,
    routeIndex: Int,
    canonicalUrl: String,
    routeIdentity: String = "",
    sessionIdentity: Option[String] = None,
    rootLayoutKey: String = "scalive:default-root",
    sessionMountClaims: Vector[String] = Vector.empty,
    serverInstance: String = "",
    initialFlash: Map[String, String] = Map.empty,
    nestedLifecycles: Map[String, Long] = Map.empty
  ): UIO[String] =
    issueRoot(
      config,
      Purpose.Session,
      rootId,
      lifecycle,
      routeIndex,
      canonicalUrl,
      routeIdentity,
      sessionIdentity,
      rootLayoutKey,
      sessionMountClaims,
      serverInstance,
      initialFlash,
      nestedLifecycles
    )

  def verifySession(
    config: ZioHttpConfig,
    token: String
  ): IO[Error, RootClaims] =
    verify[RootClaims](config, Purpose.Session, token)
      .flatMap(claims => validateAge(config, claims, _.issuedAtEpochSecond))
      .flatMap(validateRoot)

  def issueStatic(
    config: ZioHttpConfig,
    rootId: String,
    lifecycle: LifecycleId,
    routeIndex: Int,
    canonicalUrl: String,
    routeIdentity: String = "",
    sessionIdentity: Option[String] = None,
    rootLayoutKey: String = "scalive:default-root",
    sessionMountClaims: Vector[String] = Vector.empty,
    serverInstance: String = "",
    initialFlash: Map[String, String] = Map.empty,
    nestedLifecycles: Map[String, Long] = Map.empty
  ): UIO[String] =
    issueRoot(
      config,
      Purpose.Static,
      rootId,
      lifecycle,
      routeIndex,
      canonicalUrl,
      routeIdentity,
      sessionIdentity,
      rootLayoutKey,
      sessionMountClaims,
      serverInstance,
      initialFlash,
      nestedLifecycles
    )

  def verifyStatic(
    config: ZioHttpConfig,
    token: String
  ): IO[Error, RootClaims] =
    verify[RootClaims](config, Purpose.Static, token)
      .flatMap(claims => validateAge(config, claims, _.issuedAtEpochSecond))
      .flatMap(validateRoot)

  def issueFlash(config: ZioHttpConfig, values: Map[String, String]): UIO[Option[String]] =
    if values.isEmpty then ZIO.none
    else
      currentEpochSecond.map(issuedAt =>
        Some(encode(config, Purpose.Flash, FlashClaims(values, issuedAt)))
      )

  def verifyFlash(config: ZioHttpConfig, token: String): IO[Error, Map[String, String]] =
    verify[FlashClaims](config, Purpose.Flash, token)
      .flatMap(claims => validateAge(Duration.ofSeconds(60), claims, _.issuedAtEpochSecond))
      .map(_.values)

  /** Signs the status a failed join asks the reloaded page to answer, as Phoenix's reload token. */
  def issueReloadStatus(config: ZioHttpConfig, status: zio.http.Status): UIO[String] =
    currentEpochSecond.map(issuedAt =>
      encode(config, Purpose.ReloadStatus, ReloadStatusClaims(status.code, issuedAt))
    )

  def verifyReloadStatus(config: ZioHttpConfig, token: String): IO[Error, zio.http.Status] =
    verify[ReloadStatusClaims](config, Purpose.ReloadStatus, token)
      .flatMap(claims => validateAge(Duration.ofSeconds(60), claims, _.issuedAtEpochSecond))
      .map(claims => zio.http.Status.fromInt(claims.status))

  def issueNested(
    config: ZioHttpConfig,
    claims: NestedCredentialClaims
  ): UIO[IssuedNestedCredentials] =
    currentEpochSecond.map { issuedAt =>
      val encoded = NestedClaims(
        claims.registration.value,
        claims.registrationEpoch.value,
        claims.parentLifecycle.value,
        claims.parentEpoch.value,
        claims.topic.value,
        claims.childLifecycle.map(_.value),
        issuedAt
      )
      IssuedNestedCredentials(
        NestedJoinCredential(encode(config, Purpose.NestedJoin, encoded)),
        Some(NestedStaticCredential(encode(config, Purpose.NestedStatic, encoded)))
      )
    }

  def verifyNestedJoin(
    config: ZioHttpConfig,
    token: String
  ): IO[Error, NestedCredentialClaims] =
    verifyNested(config, Purpose.NestedJoin, token)

  def verifyNestedStatic(
    config: ZioHttpConfig,
    token: String
  ): IO[Error, NestedCredentialClaims] =
    verifyNested(config, Purpose.NestedStatic, token)

  def issueUploadCredential(
    config: ZioHttpConfig,
    claims: UploadCredentialClaims
  ): UIO[String] =
    currentEpochSecond.map { issuedAt =>
      val encoded = UploadClaims(
        claims.lifecycleId.value,
        claims.epoch.value,
        claims.componentInstanceId.map(_.value),
        claims.uploadRef.value,
        claims.entryRef.value,
        claims.registrationGeneration,
        claims.expectedTopic,
        issuedAt
      )
      encode(config, Purpose.UploadJoin, encoded)
    }

  /** Verifies credential authenticity and freshness, not active upload admission. The caller must
    * still match the returned claims against the live upload registry.
    */
  def verifyUploadCredential(
    config: ZioHttpConfig,
    token: String
  ): IO[Error, UploadCredentialClaims] =
    verify[UploadClaims](config, Purpose.UploadJoin, token)
      .flatMap(claims => validateAge(config, claims, _.issuedAtEpochSecond))
      .flatMap(validateUploadCredential)

  private def generateCsrfBrowserSecret: UIO[String] =
    Random.nextBytes(32).map(bytes => encoder.encodeToString(bytes.toArray))

  def issueCsrf(config: ZioHttpConfig): UIO[IssuedCsrf] =
    for
      browserSecret <- generateCsrfBrowserSecret
      issuedAt      <- currentEpochSecond
      claims      = CsrfClaims(browserSecret, issuedAt)
      cookieToken = encode(config, Purpose.CsrfCookie, claims)
      token       = encode(config, Purpose.Csrf, claims)
    yield IssuedCsrf(cookieToken, token)

  def refreshCsrf(config: ZioHttpConfig, existingCookieToken: String): IO[Error, IssuedCsrf] =
    verifyCsrfCookie(config, existingCookieToken).flatMap { cookieClaims =>
      currentEpochSecond.map { issuedAt =>
        val token = encode(
          config,
          Purpose.Csrf,
          CsrfClaims(cookieClaims.browserSecret, issuedAt)
        )
        IssuedCsrf(existingCookieToken, token)
      }
    }

  def verifyCsrf(
    config: ZioHttpConfig,
    token: String,
    cookieToken: String
  ): IO[Error, CsrfClaims] =
    for
      tokenClaims <- verify[CsrfClaims](config, Purpose.Csrf, token)
                       .flatMap(claims => validateAge(config, claims, _.issuedAtEpochSecond))
      cookieClaims <- verifyCsrfCookie(config, cookieToken)
      claims       <-
        val expected = tokenClaims.browserSecret.getBytes(StandardCharsets.UTF_8)
        val actual   = cookieClaims.browserSecret.getBytes(StandardCharsets.UTF_8)
        if MessageDigest.isEqual(expected, actual) then ZIO.succeed(tokenClaims)
        else ZIO.fail(Error.CsrfSecretMismatch)
    yield claims

  private def verifyCsrfCookie(
    config: ZioHttpConfig,
    cookieToken: String
  ): IO[Error, CsrfClaims] =
    verify[CsrfClaims](config, Purpose.CsrfCookie, cookieToken)
      .flatMap(claims => validateAge(config, claims, _.issuedAtEpochSecond))

  private def verifyNested(
    config: ZioHttpConfig,
    purpose: Purpose,
    token: String
  ): IO[Error, NestedCredentialClaims] =
    verify[NestedClaims](config, purpose, token)
      .flatMap(claims => validateAge(config, claims, _.issuedAtEpochSecond))
      .flatMap { claims =>
        if claims.registration <= 0L then
          ZIO.fail(Error.InvalidClaims("registration must be positive"))
        else if claims.registrationEpoch <= 0L then
          ZIO.fail(Error.InvalidClaims("registration epoch must be positive"))
        else if claims.parentLifecycle <= 0L then
          ZIO.fail(Error.InvalidClaims("parent lifecycle must be positive"))
        else if claims.parentEpoch <= 0L then
          ZIO.fail(Error.InvalidClaims("parent epoch must be positive"))
        else if claims.childLifecycle.exists(_ <= 0L) then
          ZIO.fail(Error.InvalidClaims("child lifecycle must be positive"))
        else if !claims.topic.startsWith("lv:") || claims.topic.length <= 3 then
          ZIO.fail(Error.InvalidClaims("nested topic is invalid"))
        else
          ZIO.succeed(
            NestedCredentialClaims(
              NestedRegistrationId(claims.registration),
              NestedRegistrationEpoch(claims.registrationEpoch),
              LifecycleId(claims.parentLifecycle),
              Epoch(claims.parentEpoch),
              NestedTopic(claims.topic),
              claims.childLifecycle.map(LifecycleId(_))
            )
          )
      }

  private def validateUploadCredential(
    claims: UploadClaims
  ): IO[Error, UploadCredentialClaims] =
    val expectedTopic = s"lvu:${claims.entryRef}"
    if claims.lifecycle <= 0L then
      ZIO.fail(Error.InvalidClaims("upload lifecycle must be positive"))
    else if claims.epoch <= 0L then ZIO.fail(Error.InvalidClaims("upload epoch must be positive"))
    else if claims.component.exists(_ <= 0L) then
      ZIO.fail(Error.InvalidClaims("upload component must be positive"))
    else if claims.registrationGeneration <= 0L then
      ZIO.fail(Error.InvalidClaims("upload registration generation must be positive"))
    else if claims.uploadRef.isEmpty then
      ZIO.fail(Error.InvalidClaims("upload ref must be non-empty"))
    else if claims.entryRef.isEmpty then
      ZIO.fail(Error.InvalidClaims("upload entry ref must be non-empty"))
    else if claims.topic.isEmpty then
      ZIO.fail(Error.InvalidClaims("upload topic must be non-empty"))
    else if claims.topic != expectedTopic then
      ZIO.fail(Error.InvalidClaims("upload topic must exactly match the entry ref"))
    else
      ZIO.succeed(
        UploadCredentialClaims(
          LifecycleId(claims.lifecycle),
          Epoch(claims.epoch),
          claims.component.map(ComponentInstanceId(_)),
          UploadRef(claims.uploadRef),
          UploadEntryRef(claims.entryRef),
          claims.registrationGeneration,
          claims.topic
        )
      )
  end validateUploadCredential

  private def issueRoot(
    config: ZioHttpConfig,
    purpose: Purpose,
    rootId: String,
    lifecycle: LifecycleId,
    routeIndex: Int,
    canonicalUrl: String,
    routeIdentity: String,
    sessionIdentity: Option[String],
    rootLayoutKey: String,
    sessionMountClaims: Vector[String],
    serverInstance: String,
    initialFlash: Map[String, String],
    nestedLifecycles: Map[String, Long]
  ): UIO[String] =
    currentEpochSecond.map { issuedAt =>
      encode(
        config,
        purpose,
        RootClaims(
          rootId,
          lifecycle.value,
          routeIndex,
          canonicalUrl,
          routeIdentity,
          sessionIdentity,
          rootLayoutKey,
          sessionMountClaims,
          issuedAt,
          serverInstance,
          initialFlash,
          nestedLifecycles
        )
      )
    }

  private def validateRoot(claims: RootClaims): IO[Error, RootClaims] =
    if claims.lifecycle <= 0L then ZIO.fail(Error.InvalidClaims("root lifecycle must be positive"))
    else if claims.nestedLifecycles.exists { case (applicationId, lifecycle) =>
        applicationId.trim.isEmpty || lifecycle <= 0L
      }
    then ZIO.fail(Error.InvalidClaims("nested lifecycle bootstrap is invalid"))
    else ZIO.succeed(claims)

  private def currentEpochSecond: UIO[Long] =
    Clock.currentTime(TimeUnit.SECONDS)

  private def encode[A: JsonEncoder](
    config: ZioHttpConfig,
    purpose: Purpose,
    claims: A
  ): String =
    val payload        = encoder.encodeToString(claims.toJson.getBytes(StandardCharsets.UTF_8))
    val authenticated  = s"$Version.${purpose.value}.$payload"
    val signatureBytes = sign(config, authenticated)
    s"$authenticated.${encoder.encodeToString(signatureBytes)}"

  private def verify[A: JsonDecoder](
    config: ZioHttpConfig,
    expectedPurpose: Purpose,
    token: String
  ): IO[Error, A] =
    token.split("\\.", -1).toList match
      case version :: purpose :: payload :: encodedSignature :: Nil =>
        if purpose.isEmpty || !isBase64Url(payload) || !isBase64Url(encodedSignature) then
          ZIO.fail(Error.MalformedToken)
        else if version != Version then ZIO.fail(Error.UnsupportedVersion(version))
        else
          for
            actualSignature <- decodeBase64(encodedSignature)
            authenticated     = s"$version.$purpose.$payload"
            expectedSignature = sign(config, authenticated)
            _ <- ZIO
                   .fail(Error.InvalidSignature).unless(
                     MessageDigest.isEqual(expectedSignature, actualSignature)
                   )
            _ <- ZIO
                   .fail(Error.PurposeMismatch(expectedPurpose.value, purpose)).unless(
                     purpose == expectedPurpose.value
                   )
            payloadBytes <- decodeBase64(payload)
            claims       <- ZIO
                        .fromEither(new String(payloadBytes, StandardCharsets.UTF_8).fromJson[A])
                        .mapError(Error.InvalidClaims.apply)
          yield claims
      case _ => ZIO.fail(Error.MalformedToken)

  private def decodeBase64(value: String): IO[Error, Array[Byte]] =
    ZIO
      .attempt(decoder.decode(value))
      .mapError(_ => Error.MalformedToken)
      .flatMap { decoded =>
        if encoder.encodeToString(decoded) == value then ZIO.succeed(decoded)
        else ZIO.fail(Error.MalformedToken)
      }

  private def isBase64Url(value: String): Boolean =
    value.nonEmpty && value.forall { character =>
      character >= 'A' && character <= 'Z' ||
      character >= 'a' && character <= 'z' ||
      character >= '0' && character <= '9' ||
      character == '-' || character == '_'
    }

  private def sign(config: ZioHttpConfig, authenticated: String): Array[Byte] =
    val mac = Mac.getInstance(HmacAlgorithm)
    mac.init(new SecretKeySpec(config.signingSecretBytes, HmacAlgorithm))
    mac.doFinal(authenticated.getBytes(StandardCharsets.UTF_8))

  private def validateAge[A](
    config: ZioHttpConfig,
    claims: A,
    issuedAtEpochSecond: A => Long
  ): IO[Error, A] = validateAge(config.sessionMaxAge, claims, issuedAtEpochSecond)

  private def validateAge[A](
    maxAge: Duration,
    claims: A,
    issuedAtEpochSecond: A => Long
  ): IO[Error, A] =
    currentEpochSecond.flatMap { now =>
      val issuedAt = issuedAtEpochSecond(claims)
      if issuedAt > now then ZIO.fail(Error.IssuedInFuture)
      else
        ZIO
          .attempt(Duration.between(Instant.ofEpochSecond(issuedAt), Instant.ofEpochSecond(now)))
          .mapError(error => Error.InvalidClaims(error.getMessage))
          .flatMap(age =>
            if age.compareTo(maxAge) > 0 then ZIO.fail(Error.Expired)
            else ZIO.succeed(claims)
          )
    }
end ZioHttpSecurity
