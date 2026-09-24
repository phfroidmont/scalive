package scalive.docs

import zio.*
import zio.test.*

import scalive.{StaticAssetConfig, StaticAssets}
import scalive.docs.model.*

object DocumentationMarkdownSpec extends ZIOSpecDefault:
  private val assets = Seq(
    "runtime-connected-lifetime.svg",
    "runtime-connected-turn.svg",
    "runtime-disconnected-lifetime.svg"
  )

  private def fixture: Task[(DocumentationApplication, DocumentationMarkdown)] =
    for
      bundle <- ZIO
                  .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                  .mapError(new IllegalArgumentException(_))
      application <- ZIO
                       .fromEither(DocumentationApplication.from(bundle))
                       .mapError(new IllegalArgumentException(_))
      static <- StaticAssets.load(StaticAssetConfig.classpath("public", assets))
      origin <- ZIO
                  .fromEither(PublicOrigin.from("https://docs.example.test"))
                  .mapError(new IllegalArgumentException(_))
    yield (application, DocumentationMarkdown(application, static, origin))

  private def page(application: DocumentationApplication, route: String): Page =
    application.page(route).getOrElse(throw new IllegalArgumentException(route))

  override def spec = suite("DocumentationMarkdownSpec")(
    test("escapes syntax and emits robust code, list, table, quote, and callout Markdown") {
      for (application, markdown) <- fixture
      yield
        val original = page(application, "/learn")
        val custom   = original.copy(content =
          Vector(
            Block.Heading(2, "custom-heading", Vector(Inline.Text("A [heading]"))),
            Block.Paragraph(
              Vector(
                Inline.Text("*literal* [link]"),
                Inline.Code("a`b"),
                Inline.Link(
                  Vector(Inline.Text("Next")),
                  LinkTarget.Internal("/learn", Some("custom-heading")),
                  None
                )
              )
            ),
            Block.Code(Some("scala"), "val x = ```value```", Vector.empty, None),
            Block.BulletList(
              Vector(
                ListItem(
                  Vector(
                    Block.Paragraph(Vector(Inline.Text("outer"))),
                    Block.OrderedList(
                      3,
                      Vector(ListItem(Vector(Block.Paragraph(Vector(Inline.Text("inner"))))))
                    )
                  )
                )
              )
            ),
            Block.Quote(Vector(Block.Paragraph(Vector(Inline.Text("quoted"))), Block.Rule)),
            Block.Callout(
              CalloutKind.Warning,
              Some("Take care"),
              Vector(Block.Paragraph(Vector(Inline.Text("body"))))
            ),
            Block.Table(
              Vector(
                TableCell(Vector(Inline.Text("A|B"))),
                TableCell(Vector(Inline.Code("A | B")))
              ),
              Vector(
                TableRow(
                  Vector(
                    TableCell(Vector(Inline.Text("C|D"))),
                    TableCell(
                      Vector(
                        Inline.Link(
                          Vector(Inline.Text("label")),
                          LinkTarget.External("https://example.test/a|b"),
                          Some("x|y")
                        )
                      )
                    )
                  )
                )
              )
            ),
            Block.Paragraph(Vector(Inline.Text("1. ~strike~ &copy; & raw"), Inline.Code("   "))),
            Block.Paragraph(
              Vector(
                Inline.Emphasis(Vector(Inline.Text("em"))),
                Inline.Strong(Vector(Inline.Text("strong"))),
                Inline.Strike(Vector(Inline.Text("gone"))),
                Inline.LineBreak,
                Inline.Text("after")
              )
            ),
            Block.Image("./picture.svg", "Picture [one]", Some("a title")),
            Block.Image("/static/chart.svg", "Root image", None),
            Block.Image("//cdn.example.test/chart.svg", "CDN image", None),
            Block.Paragraph(
              Vector(
                Inline.Link(
                  Vector(Inline.Text("HTML")),
                  LinkTarget.External("/browser-only"),
                  Some("a title")
                ),
                Inline.Link(
                  Vector(Inline.Text("unsafe")),
                  LinkTarget.External("https://example.test/a<b>\\c"),
                  None
                )
              )
            ),
            Block.Paragraph(
              Vector(
                Inline.Link(
                  Vector(Inline.Text("CDN")),
                  LinkTarget.External("//cdn.example.test/demo"),
                  None
                )
              )
            )
          )
        )
        val text = markdown.render(custom)
        assertTrue(
          DocumentationMarkdown.path("/") == "/index.md",
          DocumentationMarkdown.path("/learn") == "/learn.md",
          text.contains("<a id=\"custom-heading\"></a>"),
          text.contains("A \\[heading\\]"),
          text.contains("\\*literal\\* \\[link\\]"),
          text.contains("``a`b``"),
          text.contains("https://docs.example.test/learn.md#custom-heading"),
          text.contains("````scala\nval x = ```value```\n````"),
          text.contains("- outer\n  \n  3. inner"),
          text.contains("> quoted\n>\n> ---"),
          text.contains("> **Warning: Take care**"),
          text.contains("A\\|B"),
          text.contains("C\\|D"),
          text.contains("`A \\| B`"),
          text.contains("https://example.test/a%7Cb \"x\\|y\""),
          text.contains("1\\. \\~strike\\~ &amp;copy; &amp; raw"),
          text.contains("`   `"),
          text.contains("*em***strong**~~gone~~  \nafter"),
          text.contains("![Picture \\[one\\]](https://docs.example.test/picture.svg \"a title\")"),
          text.contains("![Root image](https://docs.example.test/static/chart.svg)"),
          text.contains("![CDN image](https://cdn.example.test/chart.svg)"),
          text.contains("[HTML](https://docs.example.test/browser-only \"a title\")"),
          text.contains("[CDN](https://cdn.example.test/demo)"),
          text.contains("https://example.test/a%3Cb%3E%5Cc")
        )
    },
    test("expands examples, traces, diagrams and indexes outside authored content") {
      for (application, markdown) <- fixture
      yield
        val example = markdown.render(page(application, "/examples/counter"))
        val trace   = markdown.render(page(application, "/learn/lifecycle-and-connection-behavior"))
        val diagram = markdown.render(page(application, "/project/runtime-architecture"))
        val catalog = markdown.render(page(application, "/examples"))
        val apiIndex = markdown.render(page(application, "/api"))
        assertTrue(
          example.contains("<a id=\"example-counter\"></a>"),
          example.contains(
            "[Open the live example](https://docs.example.test/examples/counter#example-counter)"
          ),
          example.contains("class CounterExample"),
          example.contains("[View source]("),
          trace.contains("### Participants"),
          trace.contains("**Browser → Scalive runtime: HTTP GET**"),
          trace.contains("**Scalive runtime → Your LiveView: Disconnected mount**"),
          trace.contains("Model A"),
          trace.contains("Connected LiveSocket mount"),
          diagram.contains("runtime-connected-lifetime.svg"),
          diagram.matches(
            "(?s).*https://docs\\.example\\.test/static/[a-f0-9]{64}/runtime-connected-lifetime\\.svg.*"
          ),
          diagram.contains("HTTP request scope"),
          catalog.contains("https://docs.example.test/examples/counter.md"),
          catalog.contains("https://docs.example.test/examples/authentication/lab"),
          apiIndex.contains("## Generated API pages"),
          apiIndex.contains("https://docs.example.test/api/scalive/live-view.md"),
          markdown.render(page(application, "/")).contains("# Live interfaces. Typed end to end.")
        )
    },
    test("includes generated API owners, members, overloads, docs, tags, sources and anchors") {
      for (application, markdown) <- fixture
      yield
        val apiPage = page(application, "/api/scalive/live-view")
        val text    = markdown.render(apiPage)
        val symbols = apiPage.content.collect { case Block.ApiSymbolRef(id) =>
          application.apiSymbol(id).get
        }
        val sources = symbols
          .flatMap(_.signatures).map(signature =>
            application.bundle.apiReference.metadata.sourceLink(signature.source).url
          )
        val fragments      = symbols.flatMap(_.fragment)
        val authored       = markdown.render(page(application, "/learn/models-and-messages"))
        val overloaded     = application.bundle.apiReference.symbols.find(_.signatures.size > 1).get
        val overloadedText = markdown.render(page(application, overloaded.route))
        val method         = symbols.find(_.kind == ApiSymbolKind.Def).get
        val typeAlias      = symbols.find(_.kind == ApiSymbolKind.TypeAlias).get
        val compact        = markdown.render(
          page(application, "/learn").copy(content =
            Vector(
              Block.ApiSymbolRef(method.id),
              Block.ApiSymbolRef(typeAlias.id)
            )
          )
        )
        assertTrue(
          text.contains("trait LiveView[Msg, Model]"),
          text.contains("### scalive.LiveView (Trait)"),
          text.contains("## Methods"),
          compact.contains("Method [`scalive.LiveView."),
          compact.contains("Type alias [`scalive.LiveView."),
          fragments.nonEmpty,
          fragments.forall(id => text.contains(s"<a id=\"$id\"></a>")),
          symbols.flatMap(_.signatures).forall(signature => text.contains(signature.signature)),
          overloaded.signatures.forall(signature => overloadedText.contains(signature.signature)),
          sources.forall(text.contains),
          text.contains("A LiveView is mounted independently"),
          text.contains("**Parameters"),
          text.contains(
            "[Companion: object LiveView](https://docs.example.test/api/scalive/live-view/companion.md)"
          ),
          markdown
            .render(page(application, "/api/scalive/live-view/companion")).contains(
              "[Companion: trait LiveView](https://docs.example.test/api/scalive/live-view.md)"
            ),
          authored.contains("https://docs.example.test/api/scalive/live-view.md"),
          !authored.contains("### scalive.LiveView")
        )
    },
    test("every rewritten Markdown link targets a page and an exported anchor") {
      for (application, markdown) <- fixture
      yield
        val exported = application.bundle.pages
          .map(page => DocumentationMarkdown.path(page.route) -> markdown.render(page)).toMap
        val link =
          "https://docs\\.example\\.test(/[^)\\s]+?\\.md)(?:#([a-zA-Z0-9_-]+))?(?=[)\\s])".r
        val errors = exported.toVector.flatMap { (route, content) =>
          link.findAllMatchIn(content).flatMap { found =>
            val target   = found.group(1)
            val fragment = Option(found.group(2))
            exported.get(target) match
              case None => Some(s"$route: unknown $target")
              case Some(body) if fragment.exists(id => !body.contains(s"<a id=\"$id\"></a>")) =>
                Some(s"$route: missing $target#$fragment")
              case _ => None
          }
        }
        assertTrue(errors.isEmpty)
    },
    test("expands compiler diagnostics from the generated bundle") {
      for
        (application, _) <- fixture
        static           <- StaticAssets.load(StaticAssetConfig.classpath("public", assets))
        origin           <- ZIO
                    .fromEither(PublicOrigin.from("https://docs.example.test"))
                    .mapError(new IllegalArgumentException(_))
        sample = CompilationFailure(
                   "invalid-transition",
                   "val broken = 1",
                   Vector.empty,
                   "Found: Int; Required: String"
                 )
        bundle = application.bundle.copy(examples = application.bundle.examples.map { example =>
                   if example.descriptor.id == "counter" then
                     example.copy(compilationFailures = Vector(sample))
                   else example
                 })
        modified <- ZIO
                      .fromEither(DocumentationApplication.from(bundle))
                      .mapError(new IllegalArgumentException(_))
      yield
        val text = DocumentationMarkdown(modified, static, origin).render(
          page(modified, "/examples/counter")
        )
        assertTrue(
          text.contains(sample.diagnostic),
          text.contains(sample.source),
          text.contains("Compiler diagnostic:")
        )
    }
  )
end DocumentationMarkdownSpec
