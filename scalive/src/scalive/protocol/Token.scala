package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*
import scala.util.Random

import zio.json.*

final private[scalive] case class Token[T] private (
  version: Int,
  liveViewId: String,
  payload: T,
  issuedAt: Long,
  salt: String)
    derives JsonCodec

/** Signing configuration for framework-issued Live session, CSRF, and HTTP flash values.
  *
  * The secret authenticates values with a message authentication code; it does not encrypt them.
  * Clients can read signed payloads, so application secrets must not be stored in those payloads.
  * Changing the secret invalidates existing framework values. It does not configure or revoke
  * application-owned authentication sessions.
  *
  * Construction does not validate either field. Use a stable, high-entropy secret and a positive,
  * finite duration; non-positive or non-finite durations can expire values immediately or fail when
  * converted to milliseconds.
  *
  * @param secret
  *   secret key shared by every process that must verify the same framework values
  * @param maxAge
  *   maximum accepted age of signed framework values; individual browser cookies may be shorter
  *   lived
  */
final case class TokenConfig(secret: String, maxAge: Duration)

/** Default framework token configuration. */
object TokenConfig:
  private[scalive] val DefaultMaxAge = 7.days

  private def maxAgeFromEnvironment(environment: Map[String, String]): Option[Duration] =
    environment
      .get("SCALIVE_TOKEN_MAX_AGE_SECONDS")
      .flatMap(_.toLongOption)
      .filter(_ > 0)
      .map(_.seconds)

  /** Configuration resolved once from the process environment.
    *
    * `SCALIVE_TOKEN_SECRET` is used verbatim when non-empty, including whitespace. If it is missing
    * or exactly empty, a random process-local secret is generated. That fallback is convenient for
    * local development, but a restart invalidates existing Live sessions, CSRF values, and flash,
    * and independently started replicas cannot verify each other's values. Production deployments
    * must provide the same stable, high-entropy secret to every replica.
    *
    * `SCALIVE_TOKEN_MAX_AGE_SECONDS` is accepted only when it parses as a positive whole `Long`.
    * Missing, empty, whitespace-padded, non-numeric, overflowing, zero, and negative values use the
    * seven-day default. Environment values are read only when this singleton value is initialized.
    */
  val default: TokenConfig =
    fromEnvironment(sys.env, java.util.UUID.randomUUID().toString)

  private[scalive] def fromEnvironment(
    environment: Map[String, String],
    generatedSecret: => String
  ): TokenConfig =
    TokenConfig(
      secret = environment
        .get("SCALIVE_TOKEN_SECRET")
        .filter(_.nonEmpty)
        .getOrElse(generatedSecret),
      maxAge = maxAgeFromEnvironment(environment).getOrElse(DefaultMaxAge)
    )
end TokenConfig

private[scalive] object Token:
  private val version = 1

  def sign[T: JsonCodec](secret: String, liveViewId: String, payload: T)
    : String = // TODO use messagepack and add salt
    val salt  = Random.nextString(16)
    val token =
      Token(version, liveViewId, payload, Instant.now().toEpochMilli(), salt).toJson
        .getBytes(StandardCharsets.UTF_8)
    val tokenHash = hash(secret, token)

    s"${base64Encode(token)}.${base64Encode(tokenHash)}"

  private def hash(secret: String, value: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(value)

  private def base64Encode(value: Array[Byte]): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)

  private def base64Decode(value: String): Array[Byte] =
    Base64.getUrlDecoder().decode(value)

  private def base64DecodeSafe(value: String, error: String): Either[String, Array[Byte]] =
    try Right(base64Decode(value))
    catch case _: IllegalArgumentException => Left(error)

  def verify[T: JsonCodec](secret: String, token: String, maxAge: Duration)
    : Either[String, (liveViewId: String, payload: T)] =
    token.split("\\.", 2).toList match
      case tokenBase64 :: hashBase64 :: Nil if tokenBase64.nonEmpty && hashBase64.nonEmpty =>
        for
          tokenBytes   <- base64DecodeSafe(tokenBase64, "Invalid token payload encoding")
          providedHash <- base64DecodeSafe(hashBase64, "Invalid token signature encoding")
          _            <-
            if MessageDigest.isEqual(providedHash, hash(secret, tokenBytes)) then Right(())
            else Left("Invalid signature")
          tokenValue <- new String(tokenBytes, StandardCharsets.UTF_8)
                          .fromJson[Token[T]]
                          .left
                          .map(error => s"Invalid token payload: $error")
          _ <-
            if (tokenValue.issuedAt + maxAge.toMillis) < Instant.now().toEpochMilli() then
              Left("Token expired")
            else Right(())
        yield (tokenValue.liveViewId, tokenValue.payload)
      case _ =>
        Left("Malformed token")

end Token
