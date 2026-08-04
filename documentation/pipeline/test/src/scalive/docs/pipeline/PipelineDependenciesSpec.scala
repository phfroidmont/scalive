package scalive.docs.pipeline

import laika.api.MarkupParser
import tastyquery.Contexts.Context
import zio.test.*

object PipelineDependenciesSpec extends ZIOSpecDefault:
  override def spec = suite("PipelineDependenciesSpec")(
    test("exposes the pinned Laika and TASTy Query APIs") {
      assertTrue(
        classOf[MarkupParser].getName == "laika.api.MarkupParser",
        classOf[Context].getName == "tastyquery.Contexts$Context"
      )
    }
  )
end PipelineDependenciesSpec
