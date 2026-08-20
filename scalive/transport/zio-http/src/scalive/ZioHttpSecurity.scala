package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import zio.*
import zio.json.*

import scalive.runtime.contracts.*

/** Validated security configuration for the ZIO HTTP transport.
  *
  * This is intentionally a top-level type until [[ZioHttp]] can expose it as `ZioHttp.Config`.
  */
final class ZioHttpConfig private (
  private[scalive] val signingSecretBytes: Array[Byte],
  val sessionMaxAge: Duration,
  val secureCookie: Boolean):
  override def equals(other: Any): Boolean = other match
    case that: ZioHttpConfig =>
      sessionMaxAge == that.sessionMaxAge &&
      secureCookie == that.secureCookie &&
      MessageDigest.isEqual(signingSecretBytes, that.signingSecretBytes)
    case _ => false

  override def hashCode(): Int =
    var result = sessionMaxAge.hashCode()
    result = 31 * result + java.util.Arrays.hashCode(signingSecretBytes)
    31 * result + java.lang.Boolean.hashCode(secureCookie)

  override def toString: String =
    s"ZioHttpConfig(signingSecret=<redacted>, sessionMaxAge=$sessionMaxAge, secureCookie=$secureCookie)"

object ZioHttpConfig:
  enum Error:
    case SecretTooShort(actualUtf8Bytes: Int)
    case NonPositiveSessionMaxAge

  def apply(
    signingSecret: String,
    sessionMaxAge: Duration,
    secureCookie: Boolean
  ): Either[Error, ZioHttpConfig] =
    val secretBytes = signingSecret.getBytes(StandardCharsets.UTF_8)
    if secretBytes.length < 32 then Left(Error.SecretTooShort(secretBytes.length))
    else if sessionMaxAge.isZero || sessionMaxAge.isNegative then
      Left(Error.NonPositiveSessionMaxAge)
    else Right(new ZioHttpConfig(secretBytes.clone(), sessionMaxAge, secureCookie))

private[scalive] object ZioHttpSecurity:
  final case class RootClaims(
    rootId: String,
    lifecycle: Long,
    routeIndex: Int,
    canonicalUrl: String,
    routeIdentity: String = "",
    sessionIdentity: Option[String] = None,
    rootLayoutKey: String = "scalive:identity-root",
    sessionMountClaims: Vector[String] = Vector.empty,
    routeMountClaims: Vector[String] = Vector.empty,
    hasRouteClaims: Boolean = false,
    issuedAtEpochSecond: Long,
    trackedStatics: Vector[String] = Vector.empty,
    initialFlash: Map[String, String] = Map.empty,
    nestedLifecycles: Map[String, Long] = Map.empty)
      derives JsonCodec

  final case class CsrfClaims(browserSecret: String, issuedAtEpochSecond: Long) derives JsonCodec
  final case class FlashClaims(values: Map[String, String], issuedAtEpochSecond: Long)
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
    rootLayoutKey: String = "scalive:identity-root",
    sessionMountClaims: Vector[String] = Vector.empty,
    routeMountClaims: Vector[String] = Vector.empty,
    hasRouteClaims: Boolean = false,
    trackedStatics: Vector[String] = Vector.empty,
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
      routeMountClaims,
      hasRouteClaims,
      trackedStatics,
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
    rootLayoutKey: String = "scalive:identity-root",
    sessionMountClaims: Vector[String] = Vector.empty,
    routeMountClaims: Vector[String] = Vector.empty,
    hasRouteClaims: Boolean = false,
    trackedStatics: Vector[String] = Vector.empty,
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
      routeMountClaims,
      hasRouteClaims,
      trackedStatics,
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
    routeMountClaims: Vector[String],
    hasRouteClaims: Boolean,
    trackedStatics: Vector[String],
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
          routeMountClaims,
          hasRouteClaims,
          issuedAt,
          trackedStatics,
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
