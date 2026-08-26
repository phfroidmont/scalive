package scalive.docs

import zio.test.*

import scalive.docs.model.{Block, Inline, LinkTarget, Section}

object DocumentationApplicationValidationSpec extends ZIOSpecDefault:
  private def bundle = GeneratedDocumentation.load(getClass.getClassLoader)

  override def spec = suite("DocumentationApplicationValidationSpec")(
    test("rejects drift between generated and executable example registries") {
      val result = bundle.flatMap(value => DocumentationApplication.from(value.copy(examples = Vector.empty)))
      assertTrue(result.left.exists(_.contains("missing example 'counter'")))
    },
    test("rejects unknown references and repeated examples") {
      val cases: Vector[(String, String, Block, String)] = Vector(
        ("unknown example", "/examples/counter", Block.ExampleRef("missing"), "unknown example 'missing'"),
        ("unknown trace", "/learn", Block.TraceRef("missing"), "unknown trace 'missing'"),
        (
          "unknown diagram",
          "/project/runtime-architecture",
          Block.DiagramRef("missing"),
          "unknown diagram 'missing'"
        ),
        (
          "repeated example",
          "/examples/counter",
          Block.ExampleRef("counter"),
          "example 'counter' appears more than once"
        )
      )

      val failures = cases.flatMap { case (name, route, block, expected) =>
        val result = bundle.flatMap { value =>
          val pages = value.pages.map { page =>
            if page.route == route then page.copy(content = page.content :+ block)
            else page
          }
          DocumentationApplication.from(value.copy(pages = pages))
        }
        Option.unless(result.left.exists(_.contains(expected)))(s"$name: $result")
      }

      assertTrue(failures.isEmpty)
    },
    test("rejects internal links to unknown fragments") {
      val result = bundle.flatMap { value =>
        val pages = value.pages.map { page =>
          if page.route == "/learn" then
            page.copy(content = page.content :+ Block.Paragraph(Vector(
              Inline.Link(
                Vector(Inline.Text("Broken fragment")),
                LinkTarget.Internal("/api/scalive/live-view", Some("missing-anchor")),
                None
              )
            )))
          else page
        }
        DocumentationApplication.from(value.copy(pages = pages))
      }
      assertTrue(
        result.left.exists(_.contains("/learn")),
        result.left.exists(_.contains("/api/scalive/live-view#missing-anchor"))
      )
    },
    test("requires one authored homepage at the root route") {
      val missing = bundle.flatMap(value =>
        DocumentationApplication.from(value.copy(pages = value.pages.filterNot(_.route == "/")))
      )
      val wrongSection = bundle.flatMap { value =>
        val pages = value.pages.map(page =>
          if page.route == "/" then
            page.copy(metadata = page.metadata.copy(section = Section.Learn))
          else page
        )
        DocumentationApplication.from(value.copy(pages = pages))
      }
      val duplicateSection = bundle.flatMap { value =>
        val pages = value.pages.map(page =>
          if page.route == "/learn" then
            page.copy(metadata = page.metadata.copy(section = Section.Home))
          else page
        )
        DocumentationApplication.from(value.copy(pages = pages))
      }
      assertTrue(
        missing.left.exists(_.contains("homepage route '/'")),
        wrongSection.left.exists(_.contains("section Home")),
        duplicateSection.left.exists(_.contains("exactly one page in section Home"))
      )
    },
    test("rejects malformed homepage blocks with a source-oriented error") {
      val result = bundle.flatMap { value =>
        val pages = value.pages.map(page =>
          if page.route == "/" then page.copy(content = page.content.reverse)
          else page
        )
        DocumentationApplication.from(value.copy(pages = pages))
      }
      assertTrue(
        result.left.exists(_.contains("documentation/content/index.md")),
        result.left.exists(_.contains("homepage block"))
      )
    }
  )
end DocumentationApplicationValidationSpec
