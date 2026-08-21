package scalive.docs.pipeline

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

import scalive.docs.model.*
import zio.*
import zio.test.*

object ContentPipelineSpec extends ZIOSpecDefault:
  private val fixtures =
    val resource = Option(getClass.getClassLoader.getResource("content-pipeline"))
      .getOrElse(throw new IllegalStateException("ContentPipeline fixtures are missing"))
    Path.of(resource.toURI)

  private val apiMetadata = ApiReferenceMetadata(
    "https://github.com/phfroidmont/scalive",
    "0123456789abcdef0123456789abcdef01234567",
    "18.1.0",
    "DomDefsGenerator.mill"
  )
  private val emptyApiReference = ApiReference(apiMetadata, Vector.empty)
  private val snapshotVersion   = "0.0.1-0123456789ab-SNAPSHOT"
  private val liveViewSymbol = ApiSymbol(
    id = "trait:scalive.LiveView",
    ownerId = Some("package:scalive"),
    name = "LiveView",
    qualifiedName = "scalive.LiveView",
    kind = ApiSymbolKind.Trait,
    summary = "Defines a stateful server-rendered view.",
    signatures = Vector(
      ApiSignature(
        "trait:scalive.LiveView:signature",
        "trait LiveView[Msg, Model]",
        CodeHighlighter.highlight(Some("scala"), "trait LiveView[Msg, Model]"),
        ApiOrigin("scalive.LiveView", ApiExposure.Direct),
        ApiSource.Repository(SourceRegion("scalive/src/scalive/LiveView.scala", 1, 2)),
        None
      )
    ),
    route = "/api/scalive/live-view",
    fragment = None
  )
  private val mountSymbol = liveViewSymbol.copy(
    id = "def:scalive.LiveView.mount",
    ownerId = Some(liveViewSymbol.id),
    name = "mount",
    qualifiedName = "scalive.LiveView.mount",
    kind = ApiSymbolKind.Def,
    summary = "Creates the initial model.",
    fragment = Some("mount-12345678")
  )
  private val validApiReference = ApiReference(apiMetadata, Vector(liveViewSymbol, mountSymbol))
  private val counterDescriptor = ExampleDescriptor(
    id = "counter",
    title = "Typed counter",
    description = "Update and reset isolated server state.",
    category = ExampleCategory.StartHere,
    topics = Vector("state", "events"),
    aliases = Vector("increment", "reset"),
    resetDescription = "Set the count back to zero.",
    sources = Vector(ExampleSource("LiveView", "examples/Sample.scala", "greeting", Some("scala")))
  )

  override def spec = suite("ContentPipelineSpec")(
    test("generates a deterministic bundle from a resolved Laika document tree") {
      val first  = generate("valid")
      val second = generate("valid")

      val assertions = first match
        case Left(error) => assertTrue(error.messages.isEmpty)
        case Right(bundle) =>
          val home = bundle.pages.find(_.route == "/").get
          val learn = bundle.pages.find(_.route == "/learn").get
          val heading = home.content.collectFirst { case value: Block.Heading => value }.get
          val source = home.content.collectFirst { case value: Block.SourceCode => value }.get
          val code = home.content.collectFirst { case value: Block.Code => value }.get
          val links = home.content.collect { case Block.Paragraph(content) => content }.flatten
            .collect { case value: Inline.Link => value }
          val inlines = home.content.collect { case Block.Paragraph(content) => content }.flatten
          val renderedText = inlines.collect {
            case Inline.Text(value) => value
            case Inline.Code(value) => value
          }.mkString(" ")

          assertTrue(
            bundle.formatVersion == DocumentationBundle.CurrentFormatVersion,
            bundle.pages.map(_.route) ==
              Vector("/", "/learn", "/guides/first-guide", "/examples", "/examples/counter"),
            bundle.examples.map(_.descriptor) == Vector(counterDescriptor),
            bundle.examples.head.sources.head.label == "LiveView",
            bundle.examples.head.sources.head.region == SourceRegion("examples/Sample.scala", 3, 4),
            bundle.examples.head.sources.head.text == "val greeting = \"hello\"\nprintln(greeting)",
            bundle.examples.head.sources.head.tokens.exists(_.styles.nonEmpty),
            bundle.examples.head.compilationFailures.isEmpty,
            bundle.apiReference.symbols == Vector(liveViewSymbol, mountSymbol),
            renderedText.contains(snapshotVersion),
            !renderedText.contains("{{scaliveSnapshotVersion}}"),
            bundle.searchEntries.map(_.kind).toSet == Set(
              SearchEntryKind.Page,
              SearchEntryKind.Heading,
              SearchEntryKind.Example,
              SearchEntryKind.Compatibility
            ),
            bundle.searchEntries.exists(entry =>
              entry.id == "page:/" && entry.text.contains("installation guide")
            ),
            bundle.searchEntries.exists(entry =>
              entry.id == "heading:/#overview" && entry.fragment.contains("overview")
            ),
            bundle.searchEntries.exists(entry =>
              entry.id == "example:/examples/counter" &&
                entry.title == "Typed counter" &&
                entry.description == "Update and reset isolated server state." &&
                entry.text.contains("increment") &&
                entry.route == "/examples/counter" &&
                entry.fragment.isEmpty
            ),
            bundle.searchEntries.exists(entry =>
              entry.id == "compatibility:/#compatibility-server-navigation" &&
                entry.title == "Server navigation" &&
                entry.fragment.contains("compatibility-server-navigation")
            ),
            bundle.navigation.items.map(_.section) ==
              Vector(Section.Home, Section.Learn, Section.Guides, Section.Examples),
            bundle.navigation.items.find(_.section == Section.Learn).exists(_.route == "/learn"),
            bundle.navigation.items
              .find(_.section == Section.Guides)
              .exists(_.group.contains("Foundations")),
            bundle.navigation.items.find(_.section == Section.Examples).exists(
              item => item.route == "/examples" && item.children.isEmpty
            ),
            home.source == PageSource.Authored(
              SourceLocation("documentation/content/index.md", 1)
            ),
            home.outline.items.head.id == "overview",
            home.outline.items.head.title == "Overview with emphasis",
            home.outline.items.head.children.head.id == "details",
            heading.id == "overview",
            heading.content.exists(_.isInstanceOf[Inline.Emphasis]),
            learn.outline.items.head.id == "install",
            links.exists(_.target == LinkTarget.Internal("/learn", Some("install"))),
            links.exists(_.target == LinkTarget.External("https://example.com/docs")),
            inlines.exists(_.isInstanceOf[Inline.Strong]),
            inlines.exists(_.isInstanceOf[Inline.Emphasis]),
            inlines.exists(_.isInstanceOf[Inline.Strike]),
            inlines.exists(_.isInstanceOf[Inline.Code]),
            home.content.exists(_.isInstanceOf[Block.Table]),
            home.content.exists(_.isInstanceOf[Block.BulletList]),
            home.content.exists {
              case Block.OrderedList(1, _) => true
              case _                       => false
            },
            home.content.exists(_.isInstanceOf[Block.Quote]),
            home.content.contains(Block.Rule),
            code.language.contains("scala"),
            code.tokens.exists(_.styles.nonEmpty),
            code.tokens.exists(token => token.text == "enum" && token.styles.contains("keyword")),
            source.region == SourceRegion("examples/Sample.scala", 3, 4),
            source.language.contains("scala"),
            source.text == "val greeting = \"hello\"\nprintln(greeting)",
            source.tokens.exists(_.styles.nonEmpty),
            home.content.contains(Block.ExampleRef("counter")),
            home.content.contains(Block.LabRef("authentication")),
            home.content.contains(Block.CompatibilityRef("server-navigation")),
            home.content.collect { case callout: Block.Callout => callout.kind }.toSet ==
              CalloutKind.values.toSet
          )

      assertions && assertTrue(first == second)
    },
    test("preserves owner and member API symbol identity in inline content and search") {
      generate("valid") match
        case Left(error) => assertTrue(error.messages.isEmpty)
        case Right(bundle) =>
          val inlines = bundle.pages.find(_.route == "/").toVector
            .flatMap(_.content.collect { case Block.Paragraph(content) => content }.flatten)
          val searchText = bundle.searchEntries.find(_.id == "page:/").map(_.text)
          assertTrue(
            inlines.contains(Inline.ApiSymbolRef(liveViewSymbol.id, "LiveView")),
            inlines.contains(Inline.ApiSymbolRef(mountSymbol.id, "mount")),
            searchText.exists(_.contains("LiveView")),
            searchText.exists(_.contains("mount"))
          )
    },
    test("generates typed API pages and search entries") {
      generate("api-reference", ApiReference(apiMetadata, Vector(liveViewSymbol))) match
        case Left(error) => assertTrue(error.messages.isEmpty)
        case Right(bundle) =>
          val generated = bundle.pages.find(_.route == "/api/scalive/live-view")
          assertTrue(
            generated.exists(_.source == PageSource.GeneratedApi("trait:scalive.LiveView")),
            generated.exists(_.content == Vector(Block.ApiSymbolRef("trait:scalive.LiveView"))),
            bundle.searchEntries.exists(entry =>
              entry.kind == SearchEntryKind.ApiSymbol &&
                entry.route == "/api/scalive/live-view" &&
                entry.title == "scalive.LiveView"
            ),
            bundle.searchEntries.forall(entry =>
              entry.fragment.forall(fragment =>
                bundle.pages.find(_.route == entry.route)
                  .exists(_.outline.items.exists(_.id == fragment))
              )
            ),
            bundle.navigation.items.exists(item =>
              item.section == Section.Api &&
                item.children.exists(_.route == "/api/scalive/live-view")
            )
          )
    },
    test("groups companions and builds API navigation from symbol owners") {
      val packageSymbol = liveViewSymbol.copy(
        id = "package:scalive",
        ownerId = None,
        name = "scalive",
        qualifiedName = "scalive",
        kind = ApiSymbolKind.Package,
        summary = "Public APIs in the `scalive` package.",
        signatures = liveViewSymbol.signatures.map(_.copy(
          id = "package:scalive:signature",
          signature = "package scalive",
          origin = ApiOrigin("scalive", ApiExposure.Direct)
        )),
        route = "/api/scalive"
      )
      val companion = liveViewSymbol.copy(
        id = "object:scalive.LiveView",
        kind = ApiSymbolKind.Object,
        summary = "Variants of LiveView.",
        signatures = liveViewSymbol.signatures.map(_.copy(
          id = "object:scalive.LiveView:signature",
          signature = "object LiveView"
        )),
        route = "/api/scalive/live-view/companion"
      )
      val instanceMember = liveViewSymbol.copy(
        id = "def:scalive.LiveView.mount",
        ownerId = Some(liveViewSymbol.id),
        name = "mount",
        qualifiedName = "scalive.LiveView.mount",
        kind = ApiSymbolKind.Def,
        summary = "Creates the initial model.",
        signatures = liveViewSymbol.signatures.map(_.copy(
          id = "def:scalive.LiveView.mount:signature",
          signature = "def mount: Model"
        )),
        fragment = Some("mount-12345678")
      )
      val companionMember = instanceMember.copy(
        id = "def:scalive.LiveView.apply",
        ownerId = Some(companion.id),
        name = "apply",
        qualifiedName = "scalive.LiveView.apply",
        summary = "Creates a LiveView.",
        signatures = instanceMember.signatures.map(_.copy(
          id = "def:scalive.LiveView.apply:signature",
          signature = "def apply: LiveView"
        )),
        route = companion.route,
        fragment = Some("apply-12345678")
      )
      val alphaObject = companion.copy(
        id = "object:scalive.Alpha",
        name = "Alpha",
        qualifiedName = "scalive.Alpha",
        signatures = companion.signatures.map(_.copy(
          id = "object:scalive.Alpha:signature",
          signature = "object Alpha"
        )),
        route = "/api/scalive/alpha"
      )
      val zetaTrait = liveViewSymbol.copy(
        id = "trait:scalive.Zeta",
        name = "Zeta",
        qualifiedName = "scalive.Zeta",
        signatures = liveViewSymbol.signatures.map(_.copy(
          id = "trait:scalive.Zeta:signature",
          signature = "trait Zeta"
        )),
        route = "/api/scalive/zeta"
      )
      val codecsPackage = packageSymbol.copy(
        id = "package:scalive.codecs",
        ownerId = Some(packageSymbol.id),
        name = "codecs",
        qualifiedName = "scalive.codecs",
        signatures = packageSymbol.signatures.map(_.copy(
          id = "package:scalive.codecs:signature",
          signature = "package scalive.codecs",
          origin = ApiOrigin("scalive.codecs", ApiExposure.Direct)
        )),
        route = "/api/scalive/codecs"
      )
      val testingPackage = packageSymbol.copy(
        id = "package:scalive.testing",
        ownerId = Some(packageSymbol.id),
        name = "testing",
        qualifiedName = "scalive.testing",
        signatures = packageSymbol.signatures.map(_.copy(
          id = "package:scalive.testing:signature",
          signature = "package scalive.testing",
          origin = ApiOrigin("scalive.testing", ApiExposure.Direct)
        )),
        route = "/api/scalive/testing"
      )
      val reference = ApiReference(
        apiMetadata,
        Vector(
          packageSymbol,
          companion,
          companionMember,
          liveViewSymbol,
          instanceMember,
          alphaObject,
          zetaTrait,
          codecsPackage,
          testingPackage
        )
      )

      generate("api-reference", reference) match
        case Left(error) => assertTrue(error.messages.isEmpty)
        case Right(bundle) =>
          val page          = bundle.pages.find(_.route == liveViewSymbol.route).get
          val companionPage = bundle.pages.find(_.route == companion.route).get
          val apiRoot       = bundle.navigation.items.find(_.section == Section.Api).get
          val scalive       = apiRoot.children.find(_.route == packageSymbol.route).get
          assertTrue(
            page.source == PageSource.GeneratedApi(liveViewSymbol.id),
            page.content == Vector(
              Block.ApiSymbolRef(liveViewSymbol.id),
              Block.ApiSymbolRef(instanceMember.id)
            ),
            companionPage.source == PageSource.GeneratedApi(companion.id),
            companionPage.metadata.title == "scalive.LiveView companion object",
            companionPage.content == Vector(
              Block.ApiSymbolRef(companion.id),
              Block.ApiSymbolRef(companionMember.id)
            ),
            page.outline.items.map(_.id) == Vector("methods"),
            companionPage.outline.items.map(_.id) == Vector("methods"),
            page.outline.items.flatMap(_.children).forall(_.level == 3),
            scalive.title == "scalive",
            scalive.children.map(_.title) ==
              Vector("Alpha", "codecs", "LiveView", "LiveView", "testing", "Zeta"),
            scalive.children.count(_.title == "LiveView") == 2,
            scalive.children.exists(_.route == page.route),
            scalive.children.exists(_.route == companionPage.route),
            bundle.searchEntries.count(entry =>
              entry.kind == SearchEntryKind.ApiSymbol &&
                entry.title == liveViewSymbol.qualifiedName &&
                entry.fragment.isEmpty
            ) == 1,
            bundle.searchEntries.exists(entry =>
              entry.title == "scalive.LiveView companion object" &&
                entry.route == companion.route
            )
          )
    },
    test("rejects unknown API symbol directives") {
      generate("valid", emptyApiReference) match
        case Right(_)    => assertTrue(false)
        case Left(error) => assertTrue(error.message.contains("unknown API symbol 'trait:scalive.LiveView'"))
    },
    failureTest(
      "rejects standalone authored API symbol directives",
      "standalone-api-symbol",
      "apiSymbol must be embedded in inline content"
    ),
    test("requires API symbol labels to be non-empty code spans") {
      val results = Vector("api-symbol-text-label", "api-symbol-empty-label").map { fixture =>
        generate(fixture, validApiReference)
      }
      assertTrue(results.forall(
        _.left.exists(_.message.contains("apiSymbol label must be one non-empty code span"))
      ))
    },
    test("rejects unknown example directives") {
      generate("valid", validApiReference, Vector.empty) match
        case Right(_)    => assertTrue(false)
        case Left(error) => assertTrue(error.message.contains("unknown example 'counter'"))
    },
    failureTest("rejects unknown lab directives", "unknown-lab", "unknown lab 'missing'"),
    test("converts trace directives and indexes catalog prose") {
      withTraceDocument("@:trace(http-get)\n\n@:trace(live-socket-join)").map {
        case Left(error) => assertTrue(error.messages.isEmpty)
        case Right(bundle) =>
          assertTrue(
            bundle.pages.head.content == Vector(
              Block.TraceRef("http-get"),
              Block.TraceRef("live-socket-join")
            ),
            bundle.searchEntries.exists(entry =>
              entry.id == "page:/" &&
                entry.text.contains("End request lifecycle") &&
                entry.text.contains("Connected LiveSocket mount")
            )
          )
      }
    },
    test("rejects invalid and unknown trace directives") {
      withTraceDocument("@:trace(Bad_ID)").zipWith(withTraceDocument("@:trace(missing)")) {
        case (invalid, unknown) =>
          assertTrue(
            invalid.left.exists(_.message.contains("invalid trace id 'Bad_ID'")),
            unknown.left.exists(_.message.contains("unknown trace 'missing'"))
          )
      }
    },
    test("rejects duplicate example registry ids") {
      generate("valid", validApiReference, Vector(counterDescriptor, counterDescriptor)) match
        case Right(_)    => assertTrue(false)
        case Left(error) => assertTrue(error.message.contains("duplicate example id 'counter'"))
    },
    test("requires one canonical detail page for every example") {
      val missing = counterDescriptor.copy(id = "missing-counter")
      generate("valid", validApiReference, Vector(missing)) match
        case Right(_)    => assertTrue(false)
        case Left(error) =>
          assertTrue(
            error.message.contains(
              "example 'missing-counter' requires canonical page '/examples/missing-counter'"
            )
          )
    },
    test("rejects topics with colliding URL keys") {
      val invalid = counterDescriptor.copy(topics = Vector("server state", "server-state"))
      generate("valid", validApiReference, Vector(invalid)) match
        case Right(_)    => assertTrue(false)
        case Left(error) =>
          assertTrue(error.message.contains("duplicate topic key 'server-state'"))
    },
    suite("authoring failures")(
      failureTest(
        "rejects missing, blank, wrong-type, unknown, and invalid section metadata",
        "metadata",
        "missing metadata field 'description'",
        "metadata field 'title' must not be blank",
        "metadata field 'order' must be an integer",
        "unknown metadata field 'extra'",
        "unknown documentation section 'other'",
        "inherited document metadata is not allowed"
      ),
      failureTest("rejects invalid route segments", "route", "lowercase kebab-case"),
      failureTest(
        "rejects implicit, level-1, malformed, and skipped headings",
        "heading",
        "level-1 headings are not allowed",
        "requires a trailing explicit id",
        "invalid heading id",
        "skips a heading level"
      ),
      failureTest("rejects raw HTML", "raw-html", "raw HTML is not allowed"),
      failureTest(
        "rejects unsafe external link schemes",
        "unsafe-link",
        "external link scheme 'javascript' is not allowed"
      ),
      failureTest(
        "rejects images until documentation assets are packaged",
        "image",
        "Markdown images are not supported until documentation assets are packaged"
      ),
      failureTest(
        "rejects unsupported Laika AST nodes",
        "unsupported-node",
        "unsupported Markdown node: LiteralBlock"
      ),
      failureTest(
        "rejects unknown and standard directives",
        "unknown-directive",
        "directive 'include' is not supported",
        "directive 'mystery' is not supported"
      ),
      failureTest(
        "rejects directive attributes and extra positional arguments",
        "directive-attributes",
        "invalid @:example directive",
        "invalid @:apiSymbol directive"
      ),
      failureTest(
        "rejects unstable example and compatibility IDs",
        "directive-id",
        "invalid example id 'Bad_ID'",
        "invalid compatibility id 'server/navigation'"
      ),
      failureTest(
        "rejects directive anchors that collide with headings",
        "directive-anchor",
        "duplicate rendered anchor 'example-counter'"
      ),
      failureTest("rejects a broken internal document link", "broken-link", "missing.md"),
      failureTest("rejects a broken internal fragment", "broken-fragment", "missing-anchor"),
      failureTest("rejects route collisions", "duplicate-route", "route collision '/guide'"),
      failureTest("rejects duplicate page titles", "duplicate-title", "duplicate page title 'Same'"),
      failureTest(
        "rejects duplicate navigation positions",
        "duplicate-nav-position",
        "duplicate navigation position (learn, 1)"
      ),
      failureTest("rejects duplicate page anchors", "duplicate-anchor", "duplicate anchor 'same'"),
      failureTest(
        "surfaces source extraction failures through the pipeline",
        "source-directive",
        "Missing start marker for region 'missing' in 'examples/Sample.scala'."
      ),
      failureTest(
        "rejects malformed and unterminated metadata headers",
        "metadata-syntax",
        "invalid HOCON metadata",
        "metadata header is missing closing"
      ),
      failureTest(
        "rejects invalid source directive paths",
        "source-path",
        "sourceRegion path must be repository-relative"
      ),
      failureTest(
        "rejects unsupported and unclosed callouts",
        "callout",
        "unsupported callout kind 'success'",
        "unclosed @:callout directive"
      ),
      failureTest(
        "rejects unclosed fenced code",
        "unclosed-fence",
        "unclosed fenced code block"
      )
    ),
    test("rejects content roots outside documentation/content") {
      val repository = fixtures.resolve("valid").resolve("repository")
      ContentPipeline.generate(
         repository,
         repository,
         Seq(Path.of("examples")),
         emptyApiReference,
         Vector.empty,
         snapshotVersion
      ) match
        case Right(_)    => assertTrue(false)
        case Left(error) =>
          assertTrue(error.messages.contains("Content root must be under documentation/content."))
    },
    test("rejects content files resolving outside the content root") {
      withTempDirectory("content-pipeline-symlink-") { temporaryRoot =>
        ZIO.attemptBlocking {
          val repository = Files.createDirectory(temporaryRoot.resolve("repository"))
          val docs       = Files.createDirectories(repository.resolve("documentation/content"))
          val outside    = temporaryRoot.resolve("outside.md")
          val _          = Files.writeString(outside, "outside")
          val _          = Files.createSymbolicLink(docs.resolve("escaped.md"), outside)

          ContentPipeline.generate(
            repository,
            docs,
            Seq(Path.of("documentation/site/src")),
            emptyApiReference,
            Vector.empty,
            snapshotVersion
          ) match
            case Right(_)    => assertTrue(false)
            case Left(error) =>
              assertTrue(
                error.messages.contains("Content file resolves outside the content root: 'escaped.md'.")
              )
        }
      }
    }
  )

  private def failureTest(name: String, fixture: String, expected: String*) = test(name) {
    generate(fixture) match
      case Right(_)    => assertTrue(false)
      case Left(error) =>
        assertTrue(
          expected.forall(fragment => error.messages.exists(_.contains(fragment))),
          !error.message.contains(fixtures.toString)
        )
  }

  private def generate(fixture: String): Either[PipelineError, DocumentationBundle] =
    generate(fixture, if fixture == "valid" then validApiReference else emptyApiReference)

  private def generate(
    fixture: String,
    apiReference: ApiReference
  ): Either[PipelineError, DocumentationBundle] =
    val examples =
      if Set("valid", "directive-anchor")(fixture) then Vector(counterDescriptor) else Vector.empty
    generate(fixture, apiReference, examples)

  private def generate(
    fixture: String,
    apiReference: ApiReference,
    examples: Vector[ExampleDescriptor]
  ): Either[PipelineError, DocumentationBundle] =
    val repository = fixtures.resolve(fixture).resolve("repository")
    ContentPipeline.generate(
      repositoryRoot = repository,
      contentRoot = repository.resolve("documentation/content"),
      allowedSourceRoots = Seq(Path.of("examples")),
      apiReference = apiReference,
      examples = examples,
      snapshotVersion = snapshotVersion
    )

  private def withTempDirectory[A](prefix: String)(use: Path => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(directory => deleteRecursively(directory).orDie)(use)

  private def deleteRecursively(directory: Path): Task[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(directory) then
        val paths = Files.walk(directory)
        try
          paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            val _ = Files.deleteIfExists(path)
          }
        finally paths.close()
    }

  private def withTraceDocument(directive: String): Task[Either[PipelineError, DocumentationBundle]] =
    withTempDirectory("content-pipeline-trace-") { repository =>
      ZIO.attemptBlocking {
        val content = Files.createDirectories(repository.resolve("documentation/content"))
        val _ = Files.writeString(
          content.resolve("index.md"),
          s"""{%
             |title = "Trace"
             |description = "Trace directive coverage."
             |order = 0
             |section = home
             |%}
             |
             |$directive
             |""".stripMargin
        )
        ContentPipeline.generate(
          repository,
          content,
          Seq(Path.of("examples")),
          emptyApiReference,
          Vector.empty,
          snapshotVersion
        )
      }
    }
end ContentPipelineSpec
