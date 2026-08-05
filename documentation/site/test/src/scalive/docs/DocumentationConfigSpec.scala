package scalive.docs

import zio.test.*

object DocumentationConfigSpec extends ZIOSpecDefault:
  override def spec = suite("DocumentationConfigSpec")(
    test("derives a local public origin from the configured development port") {
      assertTrue(
        DocumentationConfig.fromEnvironment(Map.empty).exists(config =>
          config.serverPort == 8080 && config.publicOrigin.value == "http://localhost:8080"
        ),
        DocumentationConfig
          .fromEnvironment(Map(DocumentationConfig.ServerPortVariable -> "9090"))
          .exists(_.publicOrigin.value == "http://localhost:9090")
      )
    },
    test("accepts and normalizes an explicit HTTP public origin") {
      assertTrue(
        DocumentationConfig
          .fromEnvironment(
            Map(DocumentationConfig.PublicOriginVariable -> "https://docs.example.test/")
          ).exists(_.publicOrigin.absolute("/learn") == "https://docs.example.test/learn")
      )
    },
    test("rejects invalid ports and non-origin public URLs") {
      val invalidOrigins = Vector(
        "docs.example.test",
        "ftp://docs.example.test",
        "https://not_a_host",
        "https://user@docs.example.test",
        "https://docs.example.test/path",
        "https://docs.example.test?query=yes",
        "https://docs.example.test/#fragment"
      )
      assertTrue(
        DocumentationConfig
          .fromEnvironment(Map(DocumentationConfig.ServerPortVariable -> "0"))
          .isLeft,
        invalidOrigins.forall(value =>
          DocumentationConfig
            .fromEnvironment(Map(DocumentationConfig.PublicOriginVariable -> value))
            .isLeft
        )
      )
    }
  )
end DocumentationConfigSpec
