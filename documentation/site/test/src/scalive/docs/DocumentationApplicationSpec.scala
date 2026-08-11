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
                            )("issue link")
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
    test("renders the source-backed counter as a disconnected nested LiveView") {
      for
        application <- loadApplication
        assets      <- StaticAssets.load(StaticAssetConfig.classpath("public", assetNames))
        rendered <- DisconnectedRender.run(
                      application.routes(assets, security, config),
                      Request.get(url("/examples"))
                    )
        document = Jsoup.parse(rendered.html)
        example  = document.selectFirst("#example-counter")
        nestedId = ExampleRegistry.instanceId("/examples", "counter")
      yield assertTrue(
        rendered.response.status == Status.Ok,
        example != null,
        example.attr("data-example-child") == nestedId,
        example.select(s"#$nestedId[data-phx-session][data-phx-child-id]").size() == 1,
        example.select("[role=status][aria-live=polite][aria-atomic=true] strong").text() == "0",
        example.select("fieldset legend.docs-visually-hidden").text() == "Counter controls",
        example.select("[data-example-controls]:not([disabled])").size() == 1,
        example.select("button[phx-click]").asScala.exists(_.text() == "Reset"),
        example.select(".docs-code-block > figcaption").text() == "Source",
        example.select(".docs-code-block [data-code-copy][hidden]").size() >= 1,
        example.select(".docs-code-block[data-code-expandable] [data-code-expand][hidden]").size() == 1,
        document.select("#typed-counter").text() == "Typed counter",
        document.select("#typed-counter + p").text().contains("Decrement, Reset, and Increment"),
        example.select(".docs-example-topics").isEmpty,
        example.select(".docs-example-rendered h3").text() == "Result",
        example.selectFirst(".docs-code-block").elementSiblingIndex() <
          example.selectFirst(".docs-example-rendered").elementSiblingIndex(),
        example.selectFirst(".docs-example-rendered").elementSiblingIndex() <
          example.selectFirst("[data-example-disconnected]").elementSiblingIndex(),
        example.select(".docs-code").text().contains("class CounterExample"),
        !example.select(".docs-code .keyword").isEmpty,
        example.select("[data-compilation-failure]").isEmpty,
        example.select("a").asScala.exists(link =>
          link.text() == "View source" && link.attr("href").contains("CounterExample.scala#L")
        ),
        application.bundle.searchEntries.exists(entry =>
              entry.id == "example:/examples#example-counter" &&
              entry.title == "Typed counter" &&
              entry.description ==
                "A LiveView with a count model and Decrement, Reset, and Increment messages."
          )
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
        missing <- DisconnectedRender.run(routes, Request.get(url("/search?q=no-such-result")))
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
        missing.text.contains("No results for 'no-such-result'.")
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
