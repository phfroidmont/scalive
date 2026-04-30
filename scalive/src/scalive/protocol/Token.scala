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

final case class Token[T] private (
  version: Int,
  liveViewId: String,
  payload: T,
  issuedAt: Long,
  salt: String)
    derives JsonCodec

final case class TokenConfig(secret: String, maxAge: Duration)

object TokenConfig:
  private val defaultMaxAge = 7.days

  private def maxAgeFromEnv: Option[Duration] =
    sys.env
      .get("SCALIVE_TOKEN_MAX_AGE_SECONDS")
      .flatMap(_.toLongOption)
      .filter(_ > 0)
      .map(_.seconds)

  val default: TokenConfig =
    TokenConfig(
      secret = sys.env
        .get("SCALIVE_TOKEN_SECRET")
        .filter(_.nonEmpty)
        .getOrElse(java.util.UUID.randomUUID().toString),
      maxAge = maxAgeFromEnv.getOrElse(defaultMaxAge)
    )

object Token:
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
