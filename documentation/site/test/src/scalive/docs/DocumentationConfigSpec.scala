package scalive.docs

import zio.test.*

object DocumentationConfigSpec extends ZIOSpecDefault:
  private val signingSecret = "documentation-config-spec-secret-32-bytes"

  override def spec = suite("DocumentationConfigSpec")(
    test("derives a local public origin from the configured development port") {
      assertTrue(
        DocumentationConfig.fromEnvironment(Map.empty).exists(config =>
          config.serverPort == 8080 &&
            config.publicOrigin.value == "http://localhost:8080" &&
            !config.secureCookie
        ),
        DocumentationConfig
          .fromEnvironment(Map(DocumentationConfig.ServerPortVariable -> "9090"))
          .exists(_.publicOrigin.value == "http://localhost:9090"),
        DocumentationConfig
          .fromEnvironment(
            Map(DocumentationConfig.PublicOriginVariable -> "http://[::1]:8080")
          ).isRight
      )
    },
    test("accepts and normalizes an explicit HTTPS public origin") {
      assertTrue(
        DocumentationConfig
          .fromEnvironment(
            Map(
              DocumentationConfig.PublicOriginVariable -> "https://docs.example.test/",
              DocumentationConfig.SigningSecretVariable -> signingSecret
            )
          ).exists(config =>
            config.publicOrigin.absolute("/learn") == "https://docs.example.test/learn" &&
              config.secureCookie &&
              config.signingSecret == signingSecret
          )
      )
    },
    test("requires a strong signing secret for a public origin") {
      val publicOrigin = DocumentationConfig.PublicOriginVariable -> "https://docs.example.test"
      assertTrue(
        DocumentationConfig.fromEnvironment(Map(publicOrigin)).isLeft,
        DocumentationConfig
          .fromEnvironment(
            Map(
              publicOrigin,
              DocumentationConfig.SigningSecretVariable -> "too-short"
            )
          ).isLeft
      )
    },
    test("rejects invalid ports and non-origin public URLs") {
      val invalidOrigins = Vector(
        "docs.example.test",
        "ftp://docs.example.test",
        "https://not_a_host",
        "https://user@docs.example.test",
        "https://docs.example.test:",
        "https://docs.example.test:0",
        "https://docs.example.test:65536",
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
