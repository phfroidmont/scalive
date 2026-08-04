package scalive.docs.model

import zio.json.*
import zio.test.*

object SerializationDependencySpec extends ZIOSpecDefault:
  private final case class Probe(value: String) derives JsonCodec

  override def spec = suite("SerializationDependencySpec")(
    test("round trips model data with ZIO JSON") {
      val expected = Probe("phase-0")
      assertTrue(expected.toJson.fromJson[Probe] == Right(expected))
    }
  )
end SerializationDependencySpec
