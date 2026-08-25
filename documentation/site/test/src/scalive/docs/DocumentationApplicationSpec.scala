package scalive.docs

import java.time.Duration
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.docs.auth.{AuthService, PublicSessionId}
import scalive.docs.examples.{ExampleRegistry, Reports, reportsFixtureService}
import scalive.docs.model.{ExampleCategory, PageSource, Section}
import scalive.testing.DisconnectedRender

object DocumentationApplicationSpec extends ZIOSpecDefault:
  private val security = LiveSecurity(
    ZioHttpConfig(
      "documentation-site-spec-secret-32-bytes",
      Duration.ofHours(1),
      secureCookie = false
    ).toOption.get
  )
  private val config = DocumentationConfig(
    8080,
    PublicOrigin
      .from("https://docs.example.test")
      .fold(error => throw new IllegalArgumentException(error), identity),
    "documentation-application-spec-secret"
  )
  private val assetNames = Seq(
    "app.css",
    "app.js",
    "favicon.svg",
    "fonts.css",
    "instrument-sans-OFL.txt",
    "jetbrains-mono-OFL.txt",
    "runtime-connected-lifetime.svg",
    "runtime-connected-turn.svg",
    "runtime-disconnected-lifetime.svg",
    "search-index.json"
  )
  private val authService = AuthService.inMemory()
  private val liveConnections = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe.run(LiveConnections.make[PublicSessionId](_ => ZIO.unit)).getOrThrow()
  }
  private val documentationEnvironment =
    ZEnvironment[Reports](reportsFixtureService)
      .add[AuthService](authService)
      .add[LiveConnections[PublicSessionId]](liveConnections)

  private def url(value: String): URL =
    URL.decode(value).fold(throw _, identity)

  private def loadApplication: Task[DocumentationApplication] =
    for
      bundle <- ZIO
                  .fromEither(GeneratedDocumentation.load(getClass.getClassLoader))
                  .mapError(new IllegalArgumentException(_))
      application <- ZIO
                       .fromEither(DocumentationApplication.from(bundle))
                       .mapError(new IllegalArgumentException(_))
    yield application

  override def spec = suite("DocumentationApplicationSpec")(
    test("constructs a literal route and location for every generated page") {
      for application <- loadApplication
      yield assertTrue(
        application.pages.nonEmpty,
        application.pages.map(_.page.route).distinct.size == application.pages.size,
        application.pages.forall(entry => entry.codec.render == entry.page.route),
        application.pages.forall(entry => entry.codec.decode(Path(entry.page.route)).contains(())),
        application.pages.forall(entry => entry.location.href == entry.page.route),
        application.bundle.searchEntries.forall(application.searchLocation(_).nonEmpty),
        Set(Section.Learn, Section.Guides, Section.Examples, Section.Api, Section.Project)
          .subsetOf(application.bundle.navigation.items.map(_.section).toSet)
      )
    },
    test("renders every generated page as meaningful disconnected HTML") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath("public", assetNames)
                  )
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        failures <- ZIO.foreach(application.pages) { entry =>
                      DisconnectedRender
                        .run(routes, Request.get(url(entry.page.route)))
                        .map { rendered =>
                          val document = Jsoup.parse(rendered.html, entry.page.route)
                          val expectedTitle =
                            if entry.page.route == "/" then "Scalive"
                            else s"${entry.page.metadata.title} | Scalive"
                          val expectedHeading = entry.page.source match
                            case PageSource.GeneratedApi(id) =>
                              application.apiSymbol(id).map(_.name).getOrElse(entry.page.metadata.title)
                            case _ => entry.page.metadata.title
                          val outlineIds = entry.page.outline.items.flatMap(flattenOutline).map(_.id)
                          val missingOutlineIds = outlineIds.filter(id => document.getElementById(id) == null)
                          val substantiveContent = document.selectFirst("main").clone()
                          substantiveContent.select("h1, .docs-page-links").remove()
                          val failedChecks = Vector(
                            Option.when(rendered.response.status != Status.Ok)("status"),
                            Option.when(document.title() != expectedTitle)(s"title '${document.title()}'"),
                            Option.when(
                              document.select("meta[name=description]").attr("content") != entry.page.metadata.description
                            )("description"),
                            Option.when(
                              document.select("link[rel=canonical]").attr("href") !=
                                config.publicOrigin.absolute(entry.page.route)
                            )(s"canonical '${document.select("link[rel=canonical]").attr("href")}'"),
                            Option.when(document.select("h1").size() != 1)("h1 count"),
                            Option.when(
                              document.selectFirst("h1").text() != expectedHeading
                            )("h1 title"),
                            Option.when(
                              document.select("nav[aria-label='Primary navigation'] a").size() != 5
                            )(s"primary nav ${document.select("nav[aria-label='Primary navigation'] a").size()}"),
                            Option.when(
                              substantiveContent.text().trim.isEmpty
                            )("meaningful text"),
                            Option.when(missingOutlineIds.nonEmpty)(s"outline ${missingOutlineIds.mkString(",")}"),
                            Option.when(
                              document.select("#docs-connection-status[phx-hook=ConnectionStatus]").size() != 1
                            )("connection hook"),
                            Option.when(
                              document.select("#docs-theme-selector[phx-hook=ThemeSelector]").size() != 1
                            )("theme hook"),
                            Option.when(
                              document.select("#docs-page-metadata[phx-hook=PageMetadata]").size() != 1
                            )("metadata hook"),
                            Option.when(
                              document.select("#docs-global-search form[action=/search][method=get]").size() != 1
                            )("search fallback"),
                            Option.when(
                              !document.select(".docs-page-links").text().contains("Report a documentation issue")
                            )("issue link"),
                            entry.page.source match
                              case PageSource.Authored(location) =>
                                Option.when(
                                  !document.select(".docs-page-links a").asScala.exists(link =>
                                    link.text() == "Edit this page" &&
                                      link.attr("href").contains(s"/${location.path}#L${location.line}")
                                  )
                                )("edit link")
                              case _: PageSource.GeneratedApi =>
                                Option.when(
                                  document.select(".docs-page-links").text().contains("Edit this page")
                                )("generated edit link")
                          ).flatten
                          Option.when(failedChecks.nonEmpty)(s"${entry.page.route}: ${failedChecks.mkString(", ")}")
                        }
                    }.map(_.flatten)
      yield assertTrue(failures.isEmpty)
    },
    test("renders the Signal lockup and gives only the homepage a wide shell") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes       = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        home        <- DisconnectedRender.run(routes, Request.get(URL.root))
        learn       <- DisconnectedRender.run(routes, Request.get(url("/learn")))
        homeDocument  = Jsoup.parse(home.html)
        learnDocument = Jsoup.parse(learn.html)
        brand          = homeDocument.selectFirst("a.docs-brand[aria-label='Scalive home']")
      yield assertTrue(
        brand != null,
        brand.select("svg[viewBox='0 0 96 96'][aria-hidden=true] path").size() == 2,
        brand.select(".docs-brand-wordmark").text() == "scalive",
        homeDocument.select(".docs-shell.docs-shell-wide").size() == 1,
        homeDocument.select(".docs-section-nav, .docs-outline").isEmpty,
        learnDocument.select(".docs-shell:not(.docs-shell-wide)").size() == 1,
        learnDocument.select(".docs-section-nav").size() == 1,
        learnDocument.select(".docs-outline").size() == 1,
        homeDocument.select("link[rel=stylesheet]").asScala.toVector
          .map(_.attr("href")).exists(_.contains("/fonts-")),
        homeDocument.select("link[rel=icon][type='image/svg+xml']").attr("href")
          .contains("/favicon-"),
        home.html.indexOf("scalive.docs.theme") < home.html.indexOf("/fonts-"),
        home.html.indexOf("/fonts-") < home.html.indexOf("/app-"),
        !home.html.contains("&quot;scalive.docs.theme")
      )
    },
    test("keeps the Learn overview concise and lifecycle details ordered") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath("public", assetNames)
                  )
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        rendered <- DisconnectedRender.run(routes, Request.get(url("/learn")))
        document = Jsoup.parse(rendered.html)
        lifecycleRendered <- DisconnectedRender.run(
                               routes,
                               Request.get(url("/learn/lifecycle-and-connection-behavior"))
                             )
        lifecycleDocument = Jsoup.parse(lifecycleRendered.html)
        learnRoot = application.bundle.navigation.items.find(_.section == Section.Learn).get
        learnPages = learnRoot +: learnRoot.children
        independentMounts = lifecycleDocument.getElementById("two-independent-mounts")
        handoff = lifecycleDocument.getElementById("follow-the-connected-mount")
        httpTrace = lifecycleDocument.selectFirst("figure[data-trace-viewer=http-get]")
        connectedTrace = lifecycleDocument.selectFirst(
                           "figure[data-trace-viewer=live-socket-join]"
                         )
        timeline = lifecycleDocument.getElementById("follow-the-lifecycle-timeline")
        stateOwnership = lifecycleDocument.getElementById("put-state-in-the-right-lifetime")
        reconnect = lifecycleDocument.getElementById("treat-reconnect-as-a-new-lifecycle")
        testing = lifecycleDocument.getElementById("test-at-the-lifecycle-boundary")
        lifecycleBlocks = lifecycleDocument.select("article.docs-content > *").asScala.toVector
      yield assertTrue(
        document.select("figure[data-trace-viewer]").isEmpty,
        document.getElementById("start-here") != null,
        document.getElementById("know-which-side-owns-what") != null,
        document.select("nav.docs-learn-progress .docs-learn-progress-count").text() ==
          s"1 of ${learnPages.size}",
        lifecycleDocument.select("figure[data-trace-viewer]").size() == 2,
        httpTrace.text().contains("Model A"),
        httpTrace.text().contains("End request lifecycle"),
        connectedTrace.text().contains("Model B"),
        connectedTrace.text().contains("Initial rendered diff"),
        lifecycleBlocks.indexOf(independentMounts) < lifecycleBlocks.indexOf(handoff),
        lifecycleBlocks.indexOf(handoff) < lifecycleBlocks.indexOf(httpTrace),
        lifecycleBlocks.indexOf(httpTrace) < lifecycleBlocks.indexOf(connectedTrace),
        lifecycleBlocks.indexOf(connectedTrace) < lifecycleBlocks.indexOf(timeline),
        lifecycleBlocks.indexOf(timeline) < lifecycleBlocks.indexOf(stateOwnership),
        lifecycleBlocks.indexOf(stateOwnership) < lifecycleBlocks.indexOf(reconnect),
        lifecycleBlocks.indexOf(reconnect) < lifecycleBlocks.indexOf(testing),
        lifecycleDocument.select("nav.docs-learn-progress .docs-learn-progress-count").text() ==
          s"${learnPages.indexWhere(_.route == "/learn/lifecycle-and-connection-behavior") + 1} of ${learnPages.size}"
      )
    },
    test("renders runtime diagrams in narrative order with accessible fallbacks") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes       = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        rendered    <- DisconnectedRender.run(
                         routes,
                         Request.get(url("/project/runtime-architecture"))
                       )
        document = Jsoup.parse(rendered.html)
        diagrams = document.select("figure[data-diagram]").asScala.toVector
        ownership = diagrams.head
        connectedTurn = diagrams(1)
        diagramObjects = diagrams.flatMap(_.select("object[type='image/svg+xml']").asScala)
        diagramLinks = document
          .select(".docs-diagram-heading a, .docs-diagram-panel-heading a").asScala.toVector
      yield assertTrue(
        diagrams.map(_.attr("data-diagram")) == Vector(
          "runtime-ownership",
          "runtime-connected-turn"
        ),
        diagrams.forall(diagram =>
          Option(diagram.selectFirst("figcaption")).exists(caption =>
            caption.parent() == diagram &&
              caption.select(".docs-diagram-caption[id]").text().nonEmpty
          )
        ),
        diagrams.forall(_.select("p.docs-visually-hidden[id]").text().nonEmpty),
        ownership.hasClass("docs-diagram-comparison"),
        ownership.select(".docs-diagram-panels").size() == 1,
        ownership.select(".docs-diagram-panel").size() == 2,
        ownership.select(".docs-diagram-panel-title").eachText().asScala.toVector == Vector(
          "Disconnected HTTP",
          "Connected WebSocket"
        ),
        diagrams.forall(_.select(".docs-diagram-viewport").isEmpty),
        connectedTurn.hasClass("docs-diagram-single"),
        connectedTurn.select(".docs-diagram-single-canvas").size() == 1,
        connectedTurn.select("[tabindex=0]").isEmpty,
        connectedTurn.select(".docs-diagram-heading a").text() == "Open full-size SVG",
        diagramObjects.size == 3,
        diagramObjects.forall(objectElement =>
          objectElement.attr("aria-hidden") == "true" &&
            objectElement.attr("tabindex") == "-1" &&
            objectElement.attr("data").contains("/static/runtime-")
        ),
        diagramLinks.map(_.attr("href")) == diagramObjects.map(_.attr("data"))
      )
    },
    test("renders editorial sections as flat indexes and preserves the API tree") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes       = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        learn       <- DisconnectedRender.run(routes, Request.get(url("/learn/models-and-messages")))
        guides      <- DisconnectedRender.run(routes, Request.get(url("/guides/testing")))
        project     <- DisconnectedRender.run(routes, Request.get(url("/project")))
        api         <- DisconnectedRender.run(routes, Request.get(url("/api/scalive/live-view")))
        learnDocument   = Jsoup.parse(learn.html)
        guidesDocument  = Jsoup.parse(guides.html)
        projectDocument = Jsoup.parse(project.html)
        apiDocument     = Jsoup.parse(api.html)
        learnLinks = learnDocument.select(".docs-section-index > nav > ol > li > a").asScala.toVector
        guideLinks = guidesDocument.select(".docs-section-index > nav > ul > li > a").asScala.toVector
        guideGroups = guidesDocument.select(".docs-nav-group").asScala.toVector.map { heading =>
                        heading.text() -> heading.nextElementSibling().select("a").asScala.toVector.map(_.attr("href"))
                      }
        projectLinks = projectDocument.select(".docs-section-index > nav > ul > li > a").asScala.toVector
      yield assertTrue(
        learnDocument.select(".docs-section-index details").isEmpty,
        learnLinks.nonEmpty,
        learnLinks.forall(_.select(".docs-section-index-label").text().nonEmpty),
        learnLinks.forall(_.select(".docs-section-index-number").text().nonEmpty),
        learnLinks.map(_.attr("href")).distinct.size == learnLinks.size,
        learnDocument.select(
          ".docs-section-index a[href='/learn/models-and-messages'][aria-current=page]"
        ).size() == 1,
        guidesDocument.select(".docs-section-index details").isEmpty,
        guideLinks.headOption.exists(_.text() == "Overview"),
        guideLinks.exists(_.text() == "Testing LiveViews"),
        guideGroups == Vector(
          "Orientation" -> Vector("/guides/phoenix-live-view-orientation"),
          "Interfaces and input" -> Vector(
            "/guides/html-dsl-and-event-bindings",
            "/guides/typed-forms-and-validation",
            "/guides/http-forms-and-redirects",
            "/guides/uploads-and-consumption"
          ),
          "Routing and application structure" -> Vector(
            "/guides/routes-and-navigation",
            "/guides/layouts-sessions-and-mount-aspects",
            "/guides/authentication",
            "/guides/nested-liveviews"
          ),
          "State, services, and components" -> Vector(
            "/guides/services-and-zlayer-injection",
            "/guides/components-and-communication",
            "/guides/streams-and-collection-updates"
          ),
          "Async and lifecycle" -> Vector(
            "/guides/async-work-and-subscriptions",
            "/guides/lifecycle-hooks",
            "/guides/flash-title-and-lifecycle-ux"
          ),
          "Browser integration" -> Vector("/guides/browser-integration"),
          "Testing and troubleshooting" -> Vector(
            "/guides/testing",
            "/guides/troubleshooting"
          ),
          "Assets and operations" -> Vector(
            "/guides/static-assets-and-client-setup",
            "/guides/configuration",
            "/guides/deployment"
          )
        ),
        apiDocument.select(".docs-api-navigation details").size() > 0,
        apiDocument.select(".docs-api-navigation nav > ul > li").asScala.toVector
          .map(_.attr("data-api-nav-item")) == Vector("scalive"),
        apiDocument.select(".docs-api-navigation [data-api-nav-item=api]").isEmpty,
        apiDocument.select(".docs-section-index").isEmpty
      ) && assertTrue(
        projectLinks.map(_.text()) == Vector(
          "Overview",
          "Why I Built Scalive",
          "Runtime architecture",
          "Phoenix LiveView compatibility"
        ),
        projectDocument.select(
          ".docs-section-index a[href='/project'][aria-current=page]"
        ).size() == 1
      )
    },
    test("renders generated API summaries, signatures, and pinned sources") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath("public", assetNames)
                  )
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        rendered <- DisconnectedRender.run(
                      routes,
                      Request.get(url("/api/scalive/live-view"))
                    )
        companionRendered <- DisconnectedRender.run(
                               routes,
                               Request.get(url("/api/scalive/live-view/companion"))
                             )
        expectedSources = application.bundle.apiReference.symbols
                            .filter(_.route == "/api/scalive/live-view")
                             .flatMap(_.signatures)
                             .map(signature => application.bundle.apiReference.metadata.sourceLink(signature.source).url)
                             .toSet
        document        = Jsoup.parse(rendered.html)
        companionDocument = Jsoup.parse(companionRendered.html)
        liveViewTrait   = document.selectFirst("[data-api-symbol='trait:scalive.LiveView']")
        liveViewDeclaration = liveViewTrait.selectFirst(".docs-api-signature code")
        firstMember = document.selectFirst(".docs-api-member-group [data-api-member]")
        titleRow = document.selectFirst(".docs-api-title-row")
        renderedSources = document
                            .select("[data-api-symbol] a")
                            .asScala.toVector
                            .filter(_.text() == "View source")
                            .map(_.attr("href"))
                            .toSet
      yield assertTrue(
        rendered.response.status == Status.Ok,
        rendered.text.contains("scalive.LiveView"),
        rendered.text.contains("View source"),
        renderedSources == expectedSources,
        rendered.html.contains("data-api-symbol"),
        !document.select(".docs-api-signature .keyword").isEmpty,
        liveViewTrait != null,
        liveViewDeclaration.text() == "trait LiveView[Msg, Model]",
        liveViewDeclaration.select("span").size() > 1,
        liveViewTrait.select(".docs-code-block").size() == 1,
        liveViewTrait.select(".docs-code-toolbar, [data-code-copy]").isEmpty,
        titleRow.children().first().hasClass("docs-api-title-kind-trait"),
        titleRow.select("h1").text() == "LiveView",
        document.select(".docs-api-qualified-name").isEmpty,
        document.select(".docs-api-group-heading, .docs-api-kind").isEmpty,
        firstMember.id().nonEmpty,
        firstMember.select("h3.docs-visually-hidden").text().nonEmpty,
        firstMember.select("pre.docs-api-member-signature code").size() >= 1,
        firstMember.select(".docs-code-block, [data-code-copy]").isEmpty,
        liveViewTrait.text().contains(
          "A LiveView is mounted independently for the disconnected HTTP render"
        ),
        !liveViewTrait.select(".docs-api-documentation code").isEmpty,
        !liveViewTrait.select(".docs-api-tag-section").isEmpty,
        liveViewTrait.select("a[href='/api/scalive/html-element']").text() == "HtmlElement",
        document.select(".docs-api-companion-reference a").attr("href") ==
          "/api/scalive/live-view/companion",
        companionDocument.select(".docs-api-companion-reference a").attr("href") ==
          "/api/scalive/live-view",
        companionDocument.select("[data-api-symbol='object:scalive.LiveView']").size() == 1,
        companionDocument.select("[data-api-symbol='trait:scalive.LiveView']").isEmpty,
        !rendered.text.contains("[[LiveView]]")
      )
    },
    test("renders authored API directives as inline references with concise previews") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        rendered <- DisconnectedRender.run(routes, Request.get(url("/learn/models-and-messages")))
        document  = Jsoup.parse(rendered.html)
        reference = document.selectFirst("[data-api-reference='trait:scalive.LiveView']")
      yield assertTrue(
        reference != null,
        reference.select("a[href='/api/scalive/live-view'] code").text() == "LiveView[Msg, Model]",
        reference.select("[data-api-reference-preview][role=tooltip][hidden]").size() == 1,
        reference.select(".docs-api-reference-kind").isEmpty,
        reference.select("code.docs-api-reference-signature, pre .docs-api-reference-signature").isEmpty,
        reference.select("span.docs-api-reference-signature").size() == 1,
        reference.select(".docs-api-reference-signature").text() == "trait LiveView[Msg, Model]",
        reference.select(".docs-api-reference-signature .keyword").text() == "trait",
        reference.select(".docs-api-reference-signature .type-name").text().contains("LiveView"),
        reference.select(".docs-api-reference-summary").text().contains(
          "Defines a server-rendered view with typed messages and model state."
        ),
        reference.select("[data-api-symbol]").isEmpty
      )
    },
    test("renders a bounded URL-filtered example catalog") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        rendered <- DisconnectedRender.run(routes, Request.get(url("/examples")))
        filtered <- DisconnectedRender.run(
                      routes,
                      Request.get(url("/examples?topic=keyed-rendering"))
                    )
        searched <- DisconnectedRender.run(
                      routes,
                      Request.get(url("/examples?q=LiveStream"))
                    )
        unknown <- DisconnectedRender.run(
                     routes,
                     Request.get(url("/examples?topic=unknown"))
                   )
        document         = Jsoup.parse(rendered.html)
        filteredDocument = Jsoup.parse(filtered.html)
        searchedDocument = Jsoup.parse(searched.html)
        unknownDocument  = Jsoup.parse(unknown.html)
        expectedExampleIds = application.bundle.examples.map(_.descriptor.id).toSet
        renderedExampleIds = document.select("[data-example-card]").asScala.toVector
                               .map(_.attr("data-example-card")).filter(_.nonEmpty).toSet
      yield assertTrue(
        rendered.response.status == Status.Ok,
        document.select("[data-example-catalog]").size() == 1,
        expectedExampleIds.subsetOf(renderedExampleIds),
        document.select(".docs-example, [data-example-child], [data-trace-viewer-child]").isEmpty,
        document.select(".docs-code-block").isEmpty,
        document.select(".docs-example-category").size() == ExampleCategory.values.size + 1,
        document.select("[data-standalone-lab] a[href='/examples/authentication/lab']").asScala
          .exists(_.text().startsWith("Authentication lab")),
        document.select("[data-standalone-lab] a[href='/examples/authentication/lab']").asScala
          .exists(_.text() == "Open authentication lab"),
        document.select("a[data-example-topic-filter][href='/examples?topic=keyed-rendering']").size() == 1,
        filtered.response.status == Status.Ok,
        filteredDocument.select("[data-example-card]").size() == 1,
        filteredDocument.select("[data-example-card=shopping-cart]").size() == 1,
        filteredDocument.select("[data-example-card=counter]").isEmpty,
        filteredDocument.select("[data-example-topic-filter][aria-current=page]").text() ==
          "Keyed rendering",
        filteredDocument.select("[role=status]").text().contains("1 example"),
        searched.response.status == Status.Ok,
        searchedDocument.select("[data-example-card]").size() == 1,
        searchedDocument.select("[data-example-card=activity-stream]").size() == 1,
        searchedDocument.select(".docs-example-catalog-status[role=status]").text() ==
          "1 example for 'LiveStream'",
        unknown.response.status == Status.Ok,
        unknownDocument.select("[data-example-card]").isEmpty,
        unknownDocument.select(".docs-example-catalog-status[role=status]").text() == "0 examples",
        unknownDocument.select(".docs-example-catalog-empty a[href='/examples']").text() ==
          "Clear search and filters",
        application.bundle.searchEntries.exists(entry =>
              entry.id == "example:/examples/counter" &&
              entry.title == "Typed counter" &&
              entry.description ==
                "A LiveView with a count model and Decrement, Reset, and Increment messages." &&
              entry.route == "/examples/counter" && entry.fragment.isEmpty
          ),
        application.bundle.searchEntries.exists(entry =>
          entry.id == "example:/examples/shopping-cart" &&
            entry.title == "Connection-local shopping cart" &&
            entry.route == "/examples/shopping-cart" &&
            entry.fragment.isEmpty && entry.text.contains("keyed rendering")
        ),
        application.bundle.searchEntries.exists(entry =>
          entry.id == "example:/examples/lifecycle" &&
            entry.title == "Lifecycle and connection state" &&
            entry.route == "/examples/lifecycle" &&
            entry.fragment.isEmpty && entry.text.contains("page title")
        ),
        application.bundle.searchEntries.exists(entry =>
          entry.id == "example:/examples/profile-form" &&
            entry.title == "Typed profile form" &&
            entry.route == "/examples/profile-form" &&
            entry.fragment.isEmpty && entry.text.contains("validation")
        ),
        application.bundle.searchEntries.exists(entry =>
          entry.id == "example:/examples/navigation" &&
            entry.title == "Typed documentation navigation" &&
            entry.route == "/examples/navigation" &&
            entry.fragment.isEmpty && entry.text.contains("LiveLocation")
        )
      )
    },
    test("renders one source-backed example on each canonical detail route") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes       = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        counter <- DisconnectedRender.run(routes, Request.get(url("/examples/counter")))
        browser <- DisconnectedRender.run(routes, Request.get(url("/examples/browser-integration")))
        cart <- DisconnectedRender.run(routes, Request.get(url("/examples/shopping-cart")))
        lifecycle <- DisconnectedRender.run(routes, Request.get(url("/examples/lifecycle")))
        navigation <- DisconnectedRender.run(routes, Request.get(url("/examples/navigation")))
        profile <- DisconnectedRender.run(routes, Request.get(url("/examples/profile-form")))
        voting <- DisconnectedRender.run(routes, Request.get(url("/examples/voting-components")))
        counterDocument = Jsoup.parse(counter.html)
        browserDocument = Jsoup.parse(browser.html)
        cartDocument    = Jsoup.parse(cart.html)
        lifecycleDocument = Jsoup.parse(lifecycle.html)
        navigationDocument = Jsoup.parse(navigation.html)
        profileDocument   = Jsoup.parse(profile.html)
        votingDocument    = Jsoup.parse(voting.html)
        counterExample  = counterDocument.selectFirst("#example-counter")
        browserExample  = browserDocument.selectFirst("#example-browser-integration")
        cartExample     = cartDocument.selectFirst("#example-shopping-cart")
        counterNestedId = ExampleRegistry.instanceId("/examples/counter", "counter")
        cartNestedId    = ExampleRegistry.instanceId("/examples/shopping-cart", "shopping-cart")
        lifecycleNestedId = ExampleRegistry.instanceId("/examples/lifecycle", "lifecycle")
        navigationNestedId = ExampleRegistry.instanceId("/examples/navigation", "navigation")
        profileNestedId = ExampleRegistry.instanceId("/examples/profile-form", "profile-form")
        votingNestedId = ExampleRegistry.instanceId("/examples/voting-components", "voting-components")
      yield assertTrue(
        counter.response.status == Status.Ok,
        counterDocument.select(".docs-example").size() == 1,
        counterExample.attr("data-example-child") == counterNestedId,
        counterExample.select(s"#$counterNestedId[data-phx-session][data-phx-parent-id]").size() == 1,
        counterExample.select("[role=status] strong").text() == "0",
        counterExample.select(".docs-code").text().contains("class CounterExample"),
        browser.response.status == Status.Ok,
        browserExample.select(".docs-code-block").size() == 2,
        browserExample.select(".docs-code-block > figcaption").eachText().asScala.toVector ==
          Vector("LiveView", "Browser hook"),
        browserExample.select(".docs-code").text().contains("class BrowserInteropExample"),
        browserExample.select(".docs-code").text().contains("createBrowserInteropHook"),
        browserExample.select(".docs-code-source-link a").size() == 2,
        counterDocument.select(".docs-section-nav").isEmpty,
        cart.response.status == Status.Ok,
        cartDocument.select(".docs-example").size() == 1,
        cartExample.attr("data-example-child") == cartNestedId,
        cartExample.select(s"#$cartNestedId[data-phx-session][data-phx-parent-id]").size() == 1,
        cartExample.select("[data-cart-item-count]").text() == "0 items",
        cartExample.select(".docs-code").text().contains("class ShoppingCartExample"),
        lifecycle.response.status == Status.Ok,
        lifecycleDocument.select(".docs-example").size() == 1,
        lifecycleDocument.select("#example-lifecycle").attr("data-example-child") ==
          lifecycleNestedId,
        lifecycleDocument.select("[data-mount-phase]").text() == "Disconnected HTTP mount",
        lifecycleDocument.select("[data-lifecycle-title]").text() == "Lifecycle example",
        lifecycleDocument.select(".docs-code").text().contains("class LifecycleExample"),
        navigation.response.status == Status.Ok,
        navigationDocument.select("#example-navigation").attr("data-example-child") ==
          navigationNestedId,
        navigationDocument.select("[data-navigation-destination]").text() ==
          "/search?q=LiveView",
        navigationDocument.select(".docs-code").text().contains("class NavigationExample"),
        profile.response.status == Status.Ok,
        profileDocument.select(".docs-example").size() == 1,
        profileDocument.select("#example-profile-form").attr("data-example-child") == profileNestedId,
        profileDocument.select("[data-profile-form]").size() == 1,
        profileDocument.select("[data-field-error] .form-error").isEmpty,
        profileDocument.select(".docs-code").text().contains("class ProfileFormExample"),
        voting.response.status == Status.Ok,
        votingDocument.select("#example-voting-components").attr("data-example-child") == votingNestedId,
        votingDocument.select("[data-vote-component]").size() == 2,
        votingDocument.select("[data-component-id]").size() == 2,
        votingDocument.select("[data-props-revision]").size() == 2,
        votingDocument.select(".docs-code").text().contains("class VotingComponentsExample")
      )
    },
    test("serves tracked assets and leaves unknown paths as real 404 responses") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath("public", assetNames)
                  )
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment) ++ assets.routes
        home    <- DisconnectedRender.run(routes, Request.get(URL.root))
        missing <- ZIO.scoped(routes.runZIO(Request.get(url("/not-a-documentation-page"))))
        extra   <- ZIO.scoped(routes.runZIO(Request.get(url("/learn/extra"))))
        reportsLab <- DisconnectedRender.run(
                        routes,
                        Request.get(url("/examples/service-injection/lab"))
                      )
        searchAsset = Jsoup
                        .parse(home.html)
                        .select("#docs-global-search")
                        .attr("data-search-index")
        document = Jsoup.parse(home.html)
        reportsDocument = Jsoup.parse(reportsLab.html)
        assetPaths = document
                        .select("script[src], link[rel=stylesheet]")
                       .asScala.toVector.map(element =>
                         Option(element.attr("src")).filter(_.nonEmpty).getOrElse(element.attr("href"))
                       )
      yield assertTrue(
        home.response.status == Status.Ok,
        assetPaths.size == 3,
        assetPaths.forall(_.startsWith("/static/")),
        searchAsset.startsWith("/static/search-index-"),
        reportsLab.response.status == Status.Ok,
        reportsDocument.select("[data-report-selected]").text() == "Daily sales",
        missing.status == Status.NotFound,
        extra.status == Status.NotFound
      )
    },
    test("renders URL-addressable search with an ordinary GET fallback") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        empty <- DisconnectedRender.run(routes, Request.get(url("/search")))
        symbol <- DisconnectedRender.run(routes, Request.get(url("/search?q=scalive.LiveView")))
        alias <- DisconnectedRender.run(routes, Request.get(url("/search?q=live%20view")))
        heading <- DisconnectedRender.run(routes, Request.get(url("/search?q=why%20scalive")))
        missing <- DisconnectedRender.run(routes, Request.get(url("/search?q=zyxwvutsrq")))
        emptyDocument   = Jsoup.parse(empty.html)
        symbolDocument  = Jsoup.parse(symbol.html)
        headingDocument = Jsoup.parse(heading.html)
        searchForm      = symbolDocument.selectFirst("main form[action=/search]")
      yield assertTrue(
        empty.response.status == Status.Ok,
        empty.text.contains("Enter a term"),
        emptyDocument.title() == "Search | Scalive",
        emptyDocument.select("link[rel=canonical]").attr("href") ==
          "https://docs.example.test/search",
        emptyDocument.select("meta[name=robots]").attr("content") == "noindex,follow",
        searchForm.attr("method") == "get",
        searchForm.select("input[name=q]").attr("value") == "scalive.LiveView",
        searchForm.attr("phx-submit").isEmpty,
        symbolDocument.select(".docs-search-result").text().contains("scalive.LiveView"),
        alias.text.contains("scalive.LiveView"),
        headingDocument.select(".docs-search-result a[href=/#why-scalive]").size() == 1,
        missing.text.contains("No results for 'zyxwvutsrq'.")
      )
    },
    test("serves a manifest-derived sitemap and self-contained robots policy") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes = application.routes(assets, security, config).provideEnvironment(documentationEnvironment)
        sitemapResponse <- ZIO.scoped(routes.runZIO(Request.get(url("/sitemap.xml"))))
        robotsResponse  <- ZIO.scoped(routes.runZIO(Request.get(url("/robots.txt"))))
        sitemap         <- sitemapResponse.body.asString
        robots          <- robotsResponse.body.asString
        sitemapDocument = Jsoup.parse(sitemap, "", org.jsoup.parser.Parser.xmlParser())
        locations       = sitemapDocument.select("loc").asScala.toVector.map(_.text())
      yield assertTrue(
        sitemapResponse.status == Status.Ok,
        robotsResponse.status == Status.Ok,
        locations == application.pages.map(_.page.route).distinct.sorted.map(config.publicOrigin.absolute),
        locations.exists(_.contains("/api/scalive/live-view")),
        !locations.exists(_.endsWith("/search")),
        robots ==
          "User-agent: *\nAllow: /\nSitemap: https://docs.example.test/sitemap.xml\n"
      )
    }
  )

  private def flattenOutline(item: scalive.docs.model.OutlineItem)
    : Vector[scalive.docs.model.OutlineItem] =
    item +: item.children.flatMap(flattenOutline)
end DocumentationApplicationSpec
