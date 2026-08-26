package scalive.docs.model

import zio.json.*
import zio.test.*

object ContentModelSpec extends ZIOSpecDefault:
  private val sourceRegion = SourceRegion(
    path = "documentation/content/learn/quick-start.md",
    startLine = 12,
    endLineInclusive = 24
  )

  private val text    = Vector[Inline](Inline.Text("content"))
  private val tokens  = Vector(CodeToken("val", Vector("keyword")))
  private val item    = ListItem(Vector(Block.Paragraph(text)))
  private val cell    = TableCell(text)

  private val inlineVariants = Vector[(String, Inline)](
    "text"         -> Inline.Text("content"),
    "emphasis"     -> Inline.Emphasis(text),
    "strong"       -> Inline.Strong(text),
    "strike"       -> Inline.Strike(text),
    "code"         -> Inline.Code("value"),
    "link"         -> Inline.Link(text, LinkTarget.External("https://example.com"), None),
    "apiSymbolRef" -> Inline.ApiSymbolRef("trait:scalive.LiveView", "LiveView"),
    "lineBreak"    -> Inline.LineBreak
  )

  private val blockVariants = Vector[(String, Block)](
    "paragraph"        -> Block.Paragraph(text),
    "heading"          -> Block.Heading(2, "heading", text),
    "code"             -> Block.Code(Some("scala"), "val value = 1", tokens, Some(sourceRegion)),
    "bulletList"       -> Block.BulletList(Vector(item)),
    "orderedList"      -> Block.OrderedList(1, Vector(item)),
    "quote"            -> Block.Quote(Vector(Block.Paragraph(text))),
    "table"            -> Block.Table(Vector(cell), Vector(TableRow(Vector(cell)))),
    "rule"             -> Block.Rule,
    "image"            -> Block.Image("/image.svg", "Diagram", None),
    "callout"          -> Block.Callout(CalloutKind.Info, None, Vector(Block.Paragraph(text))),
    "exampleRef"       -> Block.ExampleRef("counter"),
    "labRef"           -> Block.LabRef("authentication"),
    "traceRef"         -> Block.TraceRef("http-get"),
    "diagramRef"       -> Block.DiagramRef("runtime-ownership"),
    "sourceCode"       -> Block.SourceCode(sourceRegion, Some("scala"), "val value = 1", tokens),
    "apiSymbolRef"     -> Block.ApiSymbolRef("trait:scalive.LiveView"),
    "compatibilityRef" -> Block.CompatibilityRef("server-rendered-navigation")
  )

  private val pageSourceVariants = Vector[(String, PageSource)](
    "authored"     -> PageSource.Authored(SourceLocation("documentation/content/index.md", 1)),
    "generatedApi" -> PageSource.GeneratedApi("trait:scalive.LiveView")
  )

  private val apiSourceVariants = Vector[(String, ApiSource)](
    "repository"   -> ApiSource.Repository(sourceRegion),
    "generatedDom" -> ApiSource.GeneratedDom
  )

  private def roundTripsWithDiscriminator[A: JsonCodec](variants: Vector[(String, A)]): Boolean =
    variants.forall { case (discriminator, value) =>
      val encoded = value.toJson
      encoded.fromJson[A] == Right(value) && encoded.contains(s"\"type\":\"$discriminator\"")
    }

  private val bundle = DocumentationBundle(
    formatVersion = DocumentationBundle.CurrentFormatVersion,
    navigation = Navigation(
      Vector(NavigationItem("Home", "/", Section.Home, Vector.empty))
    ),
    pages = Vector(
      Page(
        route = "/",
        metadata = PageMetadata("Home", "Documentation", 0, Section.Home),
        source = pageSourceVariants.head._2,
        outline = PageOutline(Vector.empty),
        content = Vector(Block.Paragraph(text))
      )
    ),
    examples = Vector.empty,
    apiReference = ApiReference(
      ApiReferenceMetadata(
        repositoryUrl = "https://github.com/phfroidmont/scalive",
        revision = "0123456789abcdef0123456789abcdef01234567",
        domTypesVersion = "18.1.0",
        domGeneratorPath = "DomDefsGenerator.mill"
      ),
      symbols = Vector.empty
    ),
    searchEntries = Vector.empty
  )

  override def spec = suite("ContentModelSpec")(
    test("round trips each block discriminator") {
      assertTrue(roundTripsWithDiscriminator(blockVariants))
    },
    test("round trips each inline and link-target discriminator") {
      val linkTargets = Vector[(String, LinkTarget)](
        "internal" -> LinkTarget.Internal("/learn", Some("start")),
        "external" -> LinkTarget.External("https://example.com")
      )
      assertTrue(
        roundTripsWithDiscriminator(inlineVariants),
        roundTripsWithDiscriminator(linkTargets)
      )
    },
    test("round trips each page and API source discriminator") {
      assertTrue(
        roundTripsWithDiscriminator(pageSourceVariants),
        roundTripsWithDiscriminator(apiSourceVariants)
      )
    },
    test("round trips a minimal documentation bundle") {
      assertTrue(bundle.toJson.fromJson[DocumentationBundle] == Right(bundle))
    },
    test("preserves repository-relative paths and inclusive source ranges") {
      assertTrue(
        sourceRegion.toJson.fromJson[SourceRegion] == Right(sourceRegion),
        pageSourceVariants.head._2.toJson.fromJson[PageSource] == Right(pageSourceVariants.head._2),
        apiSourceVariants.head._2.toJson.fromJson[ApiSource] == Right(apiSourceVariants.head._2)
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
