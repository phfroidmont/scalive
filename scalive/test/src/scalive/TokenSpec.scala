package scalive

import java.util.Base64
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

import zio.test.*

object TokenSpec extends ZIOSpecDefault:
  private val secret = "token-spec-secret"

  private def base64(value: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def mutateSignature(token: String): String =
    token.split("\\.", 2).toList match
      case payload :: signature :: Nil =>
        val bytes = Base64.getUrlDecoder.decode(signature)
        val flipped =
          if bytes.isEmpty then Array(1.toByte)
          else
            bytes(0) = (bytes(0) ^ 0x01).toByte
            bytes
        s"$payload.${Base64.getUrlEncoder.withoutPadding().encodeToString(flipped)}"
      case _ => token

  override def spec = suite("TokenSpec")(
    test("returns Left for invalid base64 payload") {
      val token  = s"%%%25.${base64("signature")}" 
      val result = Token.verify[String](secret, token, 7.days)

      assertTrue(result == Left("Invalid token payload encoding"))
    },
    test("returns Left for malformed payload") {
      val token  = Token.sign[String](secret, "lv:test", "value")
      val result = Token.verify[Int](secret, token, 7.days)

      assertTrue(result.left.exists(_.startsWith("Invalid token payload:")))
    },
    test("returns Left for bad signature") {
      val token  = mutateSignature(Token.sign[String](secret, "lv:test", "value"))
      val result = Token.verify[String](secret, token, 7.days)

      assertTrue(result == Left("Invalid signature"))
    },
    test("returns Left for expired token") {
      val token  = Token.sign[String](secret, "lv:test", "value")
      val result = Token.verify[String](secret, token, (-1).millis)

      assertTrue(result == Left("Token expired"))
    }
  )
end TokenSpec
