package scalive.docs

import zio.test.*

import scalive.docs.model.Block

object DocumentationApplicationValidationSpec extends ZIOSpecDefault:
  private def bundle = GeneratedDocumentation.load(getClass.getClassLoader)

  override def spec = suite("DocumentationApplicationValidationSpec")(
    test("rejects drift between generated and executable example registries") {
      val result = bundle.flatMap(value => DocumentationApplication.from(value.copy(examples = Vector.empty)))
      assertTrue(result.left.exists(_.contains("missing example 'counter'")))
    },
    test("rejects generated pages that reference an unknown example") {
      val result = bundle.flatMap { value =>
        val pages = value.pages.map { page =>
          if page.route == "/examples" then page.copy(content = page.content :+ Block.ExampleRef("missing"))
          else page
        }
        DocumentationApplication.from(value.copy(pages = pages))
      }
      assertTrue(result.left.exists(_.contains("unknown example 'missing'")))
    },
    test("rejects repeated instances of one example on a page") {
      val result = bundle.flatMap { value =>
        val pages = value.pages.map { page =>
          if page.route == "/examples" then page.copy(content = page.content :+ Block.ExampleRef("counter"))
          else page
        }
        DocumentationApplication.from(value.copy(pages = pages))
      }
      assertTrue(result.left.exists(_.contains("example 'counter' appears more than once")))
    }
  )
end DocumentationApplicationValidationSpec
