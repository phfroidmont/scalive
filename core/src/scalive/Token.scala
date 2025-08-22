package scalive

import zio.json.*

import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.Duration
import scala.util.Random

final case class Token[T] private (
  version: Int,
  liveViewId: String,
  payload: T,
  issuedAt: Long,
  salt: String)
    derives JsonCodec

object Token:
  private val version = 1

  def sign[T: JsonCodec](secret: String, liveViewId: String, payload: T)
    : String = // TODO use messagepack and add salt
    val salt  = Random.nextString(16)
    val token =
      Token(version, liveViewId, payload, Instant.now().toEpochMilli(), salt).toJson.getBytes()
    val tokenHash = hash(secret, token)

    s"${base64Encode(token)}.${base64Encode(tokenHash)}"

  private def hash(secret: String, value: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"))
    mac.doFinal(value)

  private def base64Encode(value: Array[Byte]): String =
    Base64.getEncoder().encodeToString(value)

  private def base64Decode(value: String): Array[Byte] =
    Base64.getDecoder().decode(value)

  def verify[T: JsonCodec](secret: String, token: String, maxAge: Duration)
    : Either[String, (liveViewId: String, payload: T)] =
    val tokenBase64 = token.takeWhile(_ != '.')
    val hashBase64  = token.drop(tokenBase64.length)
    val tokenBytes  = base64Decode(tokenBase64)
    val tokenValue  = String.valueOf(tokenBytes).fromJson[Token[T]]

    val currentHash = hash(secret, tokenBytes)

    if base64Decode(hashBase64) == currentHash then
      tokenValue.flatMap(t =>
        if (t.issuedAt + maxAge.toMillis) > Instant.now().toEpochMilli() then Left("Token expired")
        else Right(t.liveViewId, t.payload)
      )
    else Left("Invalid signature")

end Token
