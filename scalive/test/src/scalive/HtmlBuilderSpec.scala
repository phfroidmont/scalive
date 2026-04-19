package scalive

import zio.test.*

object HtmlBuilderSpec extends ZIOSpecDefault:

  final case class TestModel(
    title: String = "title value",
    otherString: String = "other string value",
    bool: Boolean = false,
    nestedTitle: String = "nested title value",
    cls: String = "text-sm",
    items: List[NestedModel] = List.empty)
  final case class NestedModel(name: String, age: Int)

  override def spec = suite("HtmlBuilderSpec")(
    suite("Static HTML rendering")(
      test("Simple div") {
        val el     = div("Hello World")
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div>Hello World</div>")
      },
      test("Nested elements") {
        val el = div(
          h1("Title"),
          p("Content")
        )
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div><h1>Title</h1><p>Content</p></div>")
      },
      test("With attributes") {
        val el     = div(cls := "container", "Content")
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div class=\"container\">Content</div>")
      },
      test("Multiple attributes") {
        val el = div(
          cls    := "container",
          idAttr := "main",
          "Content"
        )
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div class=\"container\" id=\"main\">Content</div>")
      }
    ),
    suite("Dynamic HTML rendering")(
      test("Dynamic text") {
        val model = Var(TestModel(title = "dynamic title"))
        val el    = h1(model(_.title))
        el.syncAll()

        val result = HtmlBuilder.build(el)
        assertTrue(result == "<h1>dynamic title</h1>")
      },
      test("Dynamic attribute") {
        val model = Var(TestModel(cls = "dynamic-class"))
        val el    = div(cls := model(_.cls), "Content")
        el.syncAll()

        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div class=\"dynamic-class\">Content</div>")
      },
      test("Dynamic boolean attribute") {
        val model = Var(TestModel(bool = true))
        val el    = div(
          cls      := model(_.cls),
          disabled := model(_.bool),
          "Content"
        )
        el.syncAll()

        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div class=\"text-sm\" disabled>Content</div>")
      },
      test("Dynamic text with update") {
        val model = Var(TestModel(title = "initial"))
        val el    = h1(model(_.title))
        el.syncAll()

        val initialResult = HtmlBuilder.build(el)

        model.update(_.copy(title = "updated"))
        el.syncAll()

        val updatedResult = HtmlBuilder.build(el)
        assertTrue(
          initialResult == "<h1>initial</h1>",
          updatedResult == "<h1>updated</h1>"
        )
      }
    ),
    suite("Complex HTML rendering")(
      test("Form with dynamic fields") {
        val model = Var(
          TestModel(
            title = "Form Title",
            cls = "form-container"
          )
        )

        val el = form(
          cls := model(_.cls),
          div(
            label("Title:"),
            input(value := model(_.title))
          ),
          button("Submit")
        )
        el.syncAll()

        val result   = HtmlBuilder.build(el)
        val expected =
          "<form class=\"form-container\"><div><label>Title:</label><input value=\"Form Title\"/></div><button>Submit</button></form>"
        assertTrue(result == expected)
      },
      test("List with dynamic content") {
        val model = Var(
          TestModel(
            items = List(
              NestedModel("Item 1", 10),
              NestedModel("Item 2", 20)
            )
          )
        )

        val el = ul(
          model(_.items).splitByIndex((_, elem) =>
            li(
              elem(_.name),
              " (",
              elem(_.age.toString),
              ")"
            )
          )
        )
        el.syncAll()

        val result   = HtmlBuilder.build(el)
        val expected = "<ul><li>Item 1 (10)</li><li>Item 2 (20)</li></ul>"
        assertTrue(result == expected)
      }
    ),
    suite("Root HTML rendering")(
      test("With doctype") {
        val el       = div("Content")
        val result   = HtmlBuilder.build(el, isRoot = true)
        val expected = "<!doctype html><div>Content</div>"
        assertTrue(result == expected)
      },
      test("Without doctype") {
        val el       = div("Content")
        val result   = HtmlBuilder.build(el, isRoot = false)
        val expected = "<div>Content</div>"
        assertTrue(result == expected)
      }
    ),
    suite("Edge cases")(
      test("Empty content") {
        val el     = div("")
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div></div>")
      },
      test("Whitespace handling") {
        val el     = div("  Hello  World  ")
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div>  Hello  World  </div>")
      },
      test("Special characters") {
        val el     = div("Hello & World <script>")
        val result = HtmlBuilder.build(el)
        assertTrue(result == "<div>Hello &amp; World &lt;script&gt;</div>")
      },
      suite("XSS prevention")(
        test("Script tags in content are escaped") {
          val el     = div("<script>alert('xss')</script>")
          val result = HtmlBuilder.build(el)
          assertTrue(
            result == "<div>&lt;script&gt;alert('xss')&lt;/script&gt;</div>",
            !result.contains("<script>")
          )
        },
        test("Script tags in dynamic content are escaped") {
          val maliciousInput = "<script>alert('xss')</script>"
          val model          = Var(TestModel(title = maliciousInput))
          val el             = h1(model(_.title))
          el.syncAll()

          val result = HtmlBuilder.build(el)
          assertTrue(
            result.contains("&lt;script&gt;"),
            !result.contains("<script>")
          )
        },
        test("Angle brackets in attributes are escaped") {
          val maliciousInput = "<script>alert('xss')</script>"
          val el             = div(title := maliciousInput)
          val result         = HtmlBuilder.build(el)
          assertTrue(
            result.contains("&lt;script&gt;"),
            !result.contains("<script>")
          )
        },
        test("Mixed content with scripts") {
          val el = div(
            "Safe text",
            "<script>alert('xss')</script>",
            "More safe text"
          )
          val result = HtmlBuilder.build(el)
          assertTrue(
            result.contains("&lt;script&gt;"),
            !result.contains("<script>")
          )
        },
        test("Style tags are escaped") {
          val el     = div("<style>body { background: red; }</style>")
          val result = HtmlBuilder.build(el)
          assertTrue(
            result.contains("&lt;style&gt;"),
            !result.contains("<style>")
          )
        },
        test("Iframe tags are escaped") {
          val el     = div("<iframe src='javascript:alert(\"xss\")'></iframe>")
          val result = HtmlBuilder.build(el)
          assertTrue(
            result.contains("&lt;iframe"),
            !result.contains("<iframe>")
          )
        },
        test("JavaScript protocol in attributes") {
          val el     = div(href := "javascript:alert('xss')")
          val result = HtmlBuilder.build(el)
          assertTrue(result.contains("javascript:alert"))
        },
        test("Unicode-based attacks") {
          val el     = div("\u202E\u202D<script>alert('xss')</script>")
          val result = HtmlBuilder.build(el)
          assertTrue(
            result.contains("&lt;script&gt;"),
            !result.contains("<script>")
          )
        },
        test("HTML entity encoding bypass attempts") {
          val el     = div("&lt;script&gt;alert('xss')&lt;/script&gt;")
          val result = HtmlBuilder.build(el)
          assertTrue(result.contains("&amp;lt;script&amp;gt;"))
        },
        suite("Raw HTML rendering without escaping")(
          test("Basic raw HTML") {
            val el     = div(rawHtml("<span>Raw HTML</span>"))
            val result = HtmlBuilder.build(el)
            assertTrue(
              result == "<div><span>Raw HTML</span></div>",
              result.contains("<span>"),
              !result.contains("&lt;span&gt;")
            )
          },
          test("Raw HTML with scripts") {
            val el     = div(rawHtml("<script>alert('raw')</script>"))
            val result = HtmlBuilder.build(el)
            assertTrue(
              result == "<div><script>alert('raw')</script></div>",
              result.contains("<script>"),
              !result.contains("&lt;script&gt;")
            )
          },
          test("Raw HTML with nested elements") {
            val el = div(
              "Prefix: ",
              rawHtml("<strong>Bold</strong> <em>Italic</em>"),
              " Suffix"
            )
            val result = HtmlBuilder.build(el)
            assertTrue(result == "<div>Prefix: <strong>Bold</strong> <em>Italic</em> Suffix</div>")
          },
          test("Raw HTML security warning") {
            val maliciousInput = "<script>alert('XSS via rawHtml')</script>"
            val el             = div(rawHtml(maliciousInput))
            val result         = HtmlBuilder.build(el)
            assertTrue(
              result.contains("<script>alert('XSS via rawHtml')</script>"),
              !result.contains("&lt;script&gt;")
            )
          },
          test("Raw HTML vs escaped HTML comparison") {
            val dangerousContent = "<script>alert('test')</script>"

            val escapedEl     = div(dangerousContent)
            val escapedResult = HtmlBuilder.build(escapedEl)

            val rawEl     = div(rawHtml(dangerousContent))
            val rawResult = HtmlBuilder.build(rawEl)
            assertTrue(
              escapedResult.contains("&lt;script&gt;"),
              !escapedResult.contains("<script>"),
              rawResult.contains("<script>"),
              !rawResult.contains("&lt;script&gt;")
            )
          }
        )
      )
    )
  )
end HtmlBuilderSpec
