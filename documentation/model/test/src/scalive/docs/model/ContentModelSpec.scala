package scalive.docs.model

import zio.json.*
import zio.test.*

object ContentModelSpec extends ZIOSpecDefault:
  private val sourceRegion = SourceRegion(
    path = "documentation/content/learn/quick-start.md",
    startLine = 12,
    endLineInclusive = 24
  )

  private val allInlines = Vector[Inline](
    Inline.Text("Build "),
    Inline.Emphasis(Vector(Inline.Text("interactive"))),
    Inline.Strong(Vector(Inline.Text("Scala"))),
    Inline.Strike(Vector(Inline.Text("static"))),
    Inline.Code("LiveView"),
    Inline.Link(
      content = Vector(Inline.Text("quick start")),
      target = LinkTarget.Internal("/learn/quick-start", Some("run-it")),
      title = Some("Run Scalive")
    ),
    Inline.Link(
      content = Vector(Inline.Text("ZIO")),
      target = LinkTarget.External("https://zio.dev"),
      title = None
    ),
    Inline.ApiSymbolRef("trait:scalive.LiveView", "LiveView[Msg, Model]"),
    Inline.LineBreak
  )

  private val tokens = Vector(
    CodeToken("val", Vector("keyword")),
    CodeToken(" count = 0", Vector.empty)
  )

  private val allBlocks = Vector[Block](
    Block.Paragraph(allInlines),
    Block.Heading(level = 2, id = "run-it", content = Vector(Inline.Text("Run it"))),
    Block.Code(
      language = Some("scala"),
      text = "val count = 0",
      tokens = tokens,
      sourceRegion = Some(sourceRegion)
    ),
    Block.Code(language = None, text = "plain text", tokens = Vector.empty, sourceRegion = None),
    Block.BulletList(
      Vector(ListItem(Vector(Block.Paragraph(Vector(Inline.Text("First bullet"))))))
    ),
    Block.OrderedList(
      start = 3,
      items = Vector(ListItem(Vector(Block.Paragraph(Vector(Inline.Text("Third step"))))))
    ),
    Block.Quote(Vector(Block.Paragraph(Vector(Inline.Text("Typed and testable."))))),
    Block.Table(
      header =
        Vector(TableCell(Vector(Inline.Text("Feature"))), TableCell(Vector(Inline.Text("Status")))),
      rows = Vector(
        TableRow(
          Vector(
            TableCell(Vector(Inline.Text("Navigation"))),
            TableCell(Vector(Inline.Strong(Vector(Inline.Text("Ready")))))
          )
        )
      )
    ),
    Block.Rule,
    Block.Image(
      source = "/assets/docs/counter.png",
      alt = "Counter example",
      title = Some("Counter")
    ),
    Block.Callout(
      kind = CalloutKind.Info,
      title = Some("Connection behavior"),
      content = Vector(Block.Paragraph(Vector(Inline.Text("Content remains readable offline."))))
    ),
    Block.ExampleRef("counter"),
    Block.LabRef("authentication"),
    Block.SourceCode(
      region = sourceRegion,
      language = Some("scala"),
      text = "val count = 0",
      tokens = tokens
    ),
    Block.ApiSymbolRef("scalive.LiveView"),
    Block.CompatibilityRef("server-rendered-navigation")
  )

  private val sections = Vector(
    Section.Home,
    Section.Learn,
    Section.Guides,
    Section.Examples,
    Section.Api,
    Section.Project
  )

  private val navigationItems = sections.map { section =>
    NavigationItem(
      title = section.toString,
      route = if section == Section.Home then "/" else s"/${section.toString.toLowerCase}",
      section = section,
      children =
        if section == Section.Learn then
          Vector(
            NavigationItem(
              title = "Quick start",
              route = "/learn/quick-start",
              section = Section.Learn,
              children = Vector.empty
            )
          )
        else Vector.empty
    )
  }

  private val pages = sections.zipWithIndex.map { case (section, index) =>
    val route = if section == Section.Home then "/" else s"/${section.toString.toLowerCase}"
    Page(
      route = route,
      metadata = PageMetadata(
        title = section.toString,
        description = s"$section documentation",
        order = index,
        section = section
      ),
      source =
        if section == Section.Api then PageSource.GeneratedApi("trait:scalive.LiveView")
        else
          PageSource.Authored(
            SourceLocation(
              path = s"documentation/content/${section.toString.toLowerCase}.md",
              line = 1
            )
          ),
      outline = PageOutline(
        Vector(
          OutlineItem(
            id = "run-it",
            title = "Run it",
            level = 2,
            children = Vector(
              OutlineItem("details", "Details", level = 3, children = Vector.empty)
            )
          )
        )
      ),
      content = if section == Section.Home then allBlocks else Vector.empty
    )
  }

  private val exampleDescriptor = ExampleDescriptor(
    id = "counter",
    title = "Counter",
    description = "Change typed server state and reset it explicitly.",
    topics = Vector("state", "events"),
    aliases = Vector("increment", "reset"),
    resetDescription = "Set the count back to zero.",
    sources = Vector(
      ExampleSource(
        "LiveView",
        "documentation/site/src/Counter.scala",
        "counter",
        Some("scala")
      ),
      ExampleSource(
        "Browser hook",
        "documentation/site/assets/js/counter.js",
        "counter-hook",
        Some("javascript")
      )
    )
  )

  private val bundle = DocumentationBundle(
    formatVersion = DocumentationBundle.CurrentFormatVersion,
    navigation = Navigation(navigationItems),
    pages = pages,
    examples = Vector(
      ExampleDefinition(
        descriptor = exampleDescriptor,
        sources = Vector(
          ExampleSourceCode(
            label = "LiveView",
            region = sourceRegion,
            language = Some("scala"),
            text = "val count = 0",
            tokens = tokens
          ),
          ExampleSourceCode(
            label = "Browser hook",
            region = SourceRegion("documentation/site/assets/js/counter.js", 2, 4),
            language = Some("javascript"),
            text = "export const hook = {}",
            tokens = Vector.empty
          )
        ),
        compilationFailures = Vector(
          CompilationFailure(
            id = "wrong-model",
            source = "val count: Int = \"zero\"",
            sourceTokens = tokens,
            diagnostic = "Found: String, Required: Int"
          )
        )
      )
    ),
    apiReference = ApiReference(
      metadata = ApiReferenceMetadata(
        repositoryUrl = "https://github.com/phfroidmont/scalive",
        revision = "0123456789abcdef0123456789abcdef01234567",
        domTypesVersion = "18.1.0",
        domGeneratorPath = "DomDefsGenerator.mill"
      ),
      symbols = Vector(
        ApiSymbol(
          id = "trait:scalive.LiveView",
          ownerId = None,
          name = "LiveView",
          qualifiedName = "scalive.LiveView",
          kind = ApiSymbolKind.Trait,
          summary = "Defines a stateful server-rendered view.",
          signatures = Vector(
            ApiSignature(
              id = "trait:scalive.LiveView:8f57c1c6",
              signature = "trait LiveView[Msg, Model]",
              tokens = tokens,
              origin = ApiOrigin("scalive.LiveView", ApiExposure.Direct),
              source = ApiSource.Repository(
                SourceRegion("scalive/src/scalive/LiveView.scala", 8, 82)
              ),
              documentation = Some(
                ApiDocumentation(
                  body = Vector(
                    Block.Paragraph(
                      Vector(Inline.Text("Defines a stateful server-rendered view."))
                    ),
                    Block.Paragraph(Vector(Inline.Text("Mounted for HTTP and socket phases.")))
                  ),
                  tags = Vector(
                    ApiDocumentationTag(
                      "tparam",
                      Some("Msg"),
                      Vector(Block.Paragraph(Vector(Inline.Text("the accepted messages"))))
                    )
                  )
                )
              )
            ),
            ApiSignature(
              id = "trait:scalive.LiveView:generated",
              signature = "lazy val div: HtmlTag",
              tokens = tokens,
              origin = ApiOrigin("scalive.defs.tags.HtmlTags.div", ApiExposure.Inherited),
              source = ApiSource.GeneratedDom,
              documentation = None
            )
          ),
          route = "/api/scalive/live-view",
          fragment = None
        )
      )
    ),
    searchEntries = Vector(
      SearchEntry(
        id = "learn-quick-start-run-it",
        kind = SearchEntryKind.Heading,
        title = "Run it",
        description = "Run the quick-start application.",
        route = "/learn/quick-start",
        fragment = Some("run-it"),
        section = Section.Learn,
        text = "Run the Scalive quick-start application with Mill."
      )
    )
  )

  override def spec = suite("ContentModelSpec")(
    test("round trips every content model variant in one bundle") {
      val encoded = bundle.toJson
      assertTrue(
        encoded.fromJson[DocumentationBundle] == Right(bundle),
        encoded.contains("\"type\":\"paragraph\""),
        encoded.contains("\"type\":\"internal\""),
        encoded.contains("\"type\":\"apiSymbolRef\",\"id\":\"trait:scalive.LiveView\""),
        encoded.contains("\"section\":\"home\""),
        encoded.contains("\"kind\":\"info\""),
        encoded.contains("\"kind\":\"heading\""),
         encoded.contains("\"resetDescription\":\"Set the count back to zero.\""),
         encoded.contains("\"diagnostic\":\"Found: String, Required: Int\""),
         encoded.contains("\"name\":\"tparam\""),
         encoded.contains("\"type\":\"generatedApi\"")
      )
    },
    test("preserves repository-relative source paths and inclusive line ranges") {
      val decoded = bundle.toJson.fromJson[DocumentationBundle]
      assertTrue(
        decoded.map(_.pages.head.source) == Right(
          PageSource.Authored(SourceLocation("documentation/content/home.md", 1))
        ),
        decoded.map(_.apiReference.symbols.head.signatures.head.source) == Right(
          ApiSource.Repository(SourceRegion("scalive/src/scalive/LiveView.scala", 8, 82))
        ),
        decoded.map(_.pages.head.content.collectFirst { case Block.SourceCode(region, _, _, _) =>
          region
        }) == Right(Some(sourceRegion)),
        decoded.map(_.examples.head.sources.map(source => source.label -> source.region)) == Right(
          Vector(
            "LiveView" -> sourceRegion,
            "Browser hook" -> SourceRegion("documentation/site/assets/js/counter.js", 2, 4)
          )
        )
      )
    },
    test("builds pinned repository and generated DOM source links") {
      val metadata = bundle.apiReference.metadata
      assertTrue(
        metadata.sourceLink(
          ApiSource.Repository(SourceRegion("scalive/src/scalive/LiveView.scala", 8, 82))
        ).url ==
          "https://github.com/phfroidmont/scalive/blob/0123456789abcdef0123456789abcdef01234567/scalive/src/scalive/LiveView.scala#L8-L82",
        metadata.sourceLink(ApiSource.GeneratedDom) == ApiSourceLink(
          "https://github.com/phfroidmont/scalive/blob/0123456789abcdef0123456789abcdef01234567/DomDefsGenerator.mill",
          "Generated from Scala DOM Types 18.1.0"
        )
      )
    }
  )
end ContentModelSpec
