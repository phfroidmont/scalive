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
        ApiOrigin("scalive.LiveView", ApiExposure.Direct),
        ApiSource.Repository(SourceRegion("scalive/src/scalive/LiveView.scala", 1, 2))
      )
    ),
    route = "/api/scalive/live-view",
    fragment = None
  )
  private val validApiReference = ApiReference(apiMetadata, Vector(liveViewSymbol))

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

          assertTrue(
            bundle.formatVersion == 2,
            bundle.pages.map(_.route) == Vector("/", "/learn", "/guides/first-guide"),
            bundle.apiReference.symbols == Vector(liveViewSymbol),
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
              entry.id == "example:/#example-counter" &&
                entry.title == "Counter" &&
                entry.fragment.contains("example-counter")
            ),
            bundle.searchEntries.exists(entry =>
              entry.id == "compatibility:/#compatibility-server-navigation" &&
                entry.title == "Server navigation" &&
                entry.fragment.contains("compatibility-server-navigation")
            ),
            bundle.navigation.items.map(_.section) ==
              Vector(Section.Home, Section.Learn, Section.Guides),
            bundle.navigation.items.find(_.section == Section.Learn).exists(_.route == "/learn"),
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
            source.region == SourceRegion("examples/Sample.scala", 3, 4),
            source.language.contains("scala"),
            source.text == "val greeting = \"hello\"\nprintln(greeting)",
            home.content.contains(Block.ExampleRef("counter")),
            home.content.contains(Block.ApiSymbolRef("trait:scalive.LiveView")),
            home.content.contains(Block.CompatibilityRef("server-navigation")),
            home.content.collect { case callout: Block.Callout => callout.kind }.toSet ==
              CalloutKind.values.toSet
          )

      assertions && assertTrue(first == second)
    },
    test("generates typed API pages and search entries") {
      generate("api-reference", validApiReference) match
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
    test("rejects unknown API symbol directives") {
      generate("valid", emptyApiReference) match
        case Right(_)    => assertTrue(false)
        case Left(error) => assertTrue(error.message.contains("unknown API symbol 'trait:scalive.LiveView'"))
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
        emptyApiReference
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
            emptyApiReference
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
    val repository = fixtures.resolve(fixture).resolve("repository")
    ContentPipeline.generate(
      repositoryRoot = repository,
      contentRoot = repository.resolve("documentation/content"),
      allowedSourceRoots = Seq(Path.of("examples")),
      apiReference = apiReference
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
end ContentPipelineSpec
