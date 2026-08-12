package scalive.docs

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.docs.examples.ExampleRegistry
import scalive.docs.model.{PageSource, Section}
import scalive.testing.DisconnectedRender

object DocumentationApplicationSpec extends ZIOSpecDefault:
  private val security = LiveSecurity(
    TokenConfig("documentation-site-spec-secret", 1.hour),
    CookiePolicy(secure = false)
  )
  private val config = DocumentationConfig(
    8080,
    PublicOrigin
      .from("https://docs.example.test")
      .fold(error => throw new IllegalArgumentException(error), identity)
  )
  private val assetNames = Seq(
    "app.css",
    "app.js",
    "favicon.svg",
    "fonts.css",
    "instrument-sans-OFL.txt",
    "jetbrains-mono-OFL.txt",
    "search-index.json"
  )

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
        routes = application.routes(assets, security, config)
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
        routes       = application.routes(assets, security, config)
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
    test("renders editorial sections as flat indexes and preserves the API tree") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes       = application.routes(assets, security, config)
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
      yield assertTrue(
        learnDocument.select(".docs-section-index details").isEmpty,
        learnLinks.map(_.select(".docs-section-index-label").text()) == Vector(
          "Start here",
          "Quick start",
          "Project anatomy",
          "Models and messages",
          "Rendering and DOM updates"
        ),
        learnLinks.map(_.select(".docs-section-index-number").text()) ==
          Vector("01", "02", "03", "04", "05"),
        learnDocument.select(
          ".docs-section-index a[href='/learn/models-and-messages'][aria-current=page]"
        ).size() == 1,
        guidesDocument.select(".docs-section-index details").isEmpty,
        guideLinks.headOption.exists(_.text() == "Overview"),
        guideLinks.exists(_.text() == "Testing LiveViews"),
        projectDocument.select(".docs-section-nav").isEmpty,
        apiDocument.select(".docs-api-navigation details").size() > 0,
        apiDocument.select(".docs-section-index").isEmpty
      )
    },
    test("renders generated API summaries, signatures, and pinned sources") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath("public", assetNames)
                  )
        routes = application.routes(assets, security, config)
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
        routes = application.routes(assets, security, config)
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
        routes = application.routes(assets, security, config)
        rendered <- DisconnectedRender.run(routes, Request.get(url("/examples")))
        filtered <- DisconnectedRender.run(
                      routes,
                      Request.get(url("/examples?topic=keyed-rendering"))
                    )
        unknown <- DisconnectedRender.run(
                     routes,
                     Request.get(url("/examples?topic=unknown"))
                   )
        document         = Jsoup.parse(rendered.html)
        filteredDocument = Jsoup.parse(filtered.html)
        unknownDocument  = Jsoup.parse(unknown.html)
      yield assertTrue(
        rendered.response.status == Status.Ok,
        document.select("[data-example-catalog]").size() == 1,
        document.select("[data-example-card]").size() == 2,
        document.select(".docs-example, [data-example-child], [data-inspector-child]").isEmpty,
        document.select(".docs-code-block").isEmpty,
        document.select("a[href='/examples/counter']").asScala.exists(_.text() == "Typed counter"),
        document.select("a[href='/examples/shopping-cart']").asScala.exists(
          _.text() == "Connection-local shopping cart"
        ),
        document.select("a[data-example-topic-filter][href='/examples?topic=keyed-rendering']").size() == 1,
        filtered.response.status == Status.Ok,
        filteredDocument.select("[data-example-card]").size() == 1,
        filteredDocument.select("[data-example-card=shopping-cart]").size() == 1,
        filteredDocument.select("[data-example-card=counter]").isEmpty,
        filteredDocument.select("[data-example-topic-filter][aria-current=page]").text() ==
          "Keyed rendering",
        filteredDocument.select("[role=status]").text().contains("1 example"),
        unknown.response.status == Status.Ok,
        unknownDocument.select("[data-example-card]").isEmpty,
        unknownDocument.select(".docs-example-catalog-status[role=status]").text() == "0 examples",
        unknownDocument.select(".docs-example-catalog-empty a[href='/examples']").text() ==
          "Show all examples",
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
        )
      )
    },
    test("renders one source-backed example on each canonical detail route") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes       = application.routes(assets, security, config)
        counter <- DisconnectedRender.run(routes, Request.get(url("/examples/counter")))
        cart <- DisconnectedRender.run(routes, Request.get(url("/examples/shopping-cart")))
        counterDocument = Jsoup.parse(counter.html)
        cartDocument    = Jsoup.parse(cart.html)
        counterExample  = counterDocument.selectFirst("#example-counter")
        cartExample     = cartDocument.selectFirst("#example-shopping-cart")
        counterNestedId = ExampleRegistry.instanceId("/examples/counter", "counter")
        cartNestedId    = ExampleRegistry.instanceId("/examples/shopping-cart", "shopping-cart")
      yield assertTrue(
        counter.response.status == Status.Ok,
        counterDocument.select(".docs-example").size() == 1,
        counterExample.attr("data-example-child") == counterNestedId,
        counterExample.select(s"#$counterNestedId[data-phx-session][data-phx-child-id]").size() == 1,
        counterExample.select("[role=status] strong").text() == "0",
        counterExample.select(".docs-code").text().contains("class CounterExample"),
        counterDocument.select(".docs-section-nav").isEmpty,
        cart.response.status == Status.Ok,
        cartDocument.select(".docs-example").size() == 1,
        cartExample.attr("data-example-child") == cartNestedId,
        cartExample.select(s"#$cartNestedId[data-phx-session][data-phx-child-id]").size() == 1,
        cartExample.select("[data-cart-item-count]").text() == "0 items",
        cartExample.select(".docs-code").text().contains("class ShoppingCartExample")
      )
    },
    test("serves tracked assets and leaves unknown paths as real 404 responses") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(
                    StaticAssetConfig.classpath("public", assetNames)
                  )
        routes = application.routes(assets, security, config) ++ assets.routes
        home    <- DisconnectedRender.run(routes, Request.get(URL.root))
        missing <- ZIO.scoped(routes.runZIO(Request.get(url("/not-a-documentation-page"))))
        extra   <- ZIO.scoped(routes.runZIO(Request.get(url("/learn/extra"))))
        searchAsset = Jsoup
                        .parse(home.html)
                        .select("#docs-global-search")
                        .attr("data-search-index")
        document = Jsoup.parse(home.html)
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
        missing.status == Status.NotFound,
        extra.status == Status.NotFound
      )
    },
    test("renders URL-addressable search with an ordinary GET fallback") {
      for
        application <- loadApplication
        assets <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        routes = application.routes(assets, security, config)
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
        routes = application.routes(assets, security, config)
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
