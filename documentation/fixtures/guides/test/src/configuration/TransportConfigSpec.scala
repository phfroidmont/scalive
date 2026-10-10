package configuration

import zio.test.*

import scalive.*

object TransportConfigSpec extends ZIOSpecDefault:
  def spec = suite("TransportConfig")(
    test("parses a comma-separated origin list") {
      val origins = TransportConfig.parseOrigins("https://example.com, https://example.com:8443")

      assertTrue(
        origins == Right(
          Set(WebSocketOrigin.https("example.com"), WebSocketOrigin.https("example.com", 8443))
        )
      )
    },
    test("names the invalid entry without repeating its value") {
      val origins = TransportConfig.parseOrigins("https://example.com,,wss://example.com")

      assertTrue(
        origins.left.exists(_.startsWith("SCALIVE_ALLOWED_ORIGINS entry 2:")),
        origins.left.forall(!_.contains("wss://"))
      )
    }
  )
