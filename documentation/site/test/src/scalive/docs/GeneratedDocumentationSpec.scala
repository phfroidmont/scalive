package scalive.docs

import scalive.*
import zio.test.*

object GeneratedDocumentationSpec extends ZIOSpecDefault:
  override def spec = suite("GeneratedDocumentationSpec")(
    test("decodes and renders generated pages through typed Scalive nodes") {
      val result = for
        bundle      <- GeneratedDocumentation.load(getClass.getClassLoader)
        application <- DocumentationApplication.from(bundle)
      yield
        val renderer             = DocumentationRenderer(application)
        val representativeRoutes = Set("/learn", "/api/scalive/live-view")
        val rendered = bundle.pages.filter(page => representativeRoutes(page.route))
          .map(page => HtmlBuilder.build(renderer.render(page))).mkString
        val home = application.page("/").map(page =>
          HtmlBuilder.build(
            DocumentationHomeLiveView(page, application.homeContent, application, renderer).render(())
          )
        ).getOrElse("")
        val routes = bundle.pages.map(_.route).toSet
        val exampleIds = bundle.examples.map(_.descriptor.id)
        val expectedExampleIds = Vector(
          "activity-stream",
          "async-report",
          "browser-integration",
          "counter",
          "lifecycle",
          "navigation",
          "profile-form",
          "service-injection",
          "shopping-cart",
          "subscription-clock",
          "text-upload",
          "voting-components"
        )
        val counterSource = bundle.examples.find(_.descriptor.id == "counter")
          .flatMap(_.sources.find(_.label == "LiveView")).map(_.text)
        val hasShoppingCartSource = bundle.examples.exists(example =>
          example.descriptor.id == "shopping-cart" &&
            example.sources.exists(_.text.contains("class ShoppingCartExample"))
        )
        val browserSources = bundle.examples.find(_.descriptor.id == "browser-integration")
          .map(_.sources)
        val apiQualifiedNames = bundle.apiReference.symbols.map(_.qualifiedName).toSet
        val liveViewSearchEntries = bundle.searchEntries.filter(_.title == "scalive.LiveView")
        val hasHomePage = bundle.pages.exists(page =>
          page.route == "/" && page.metadata.title == "Live interfaces. Typed end to end."
        )

        assertTrue(
          representativeRoutes.subsetOf(routes),
          exampleIds == expectedExampleIds,
          counterSource.exists(_.contains("class CounterExample")),
          hasShoppingCartSource,
          browserSources.exists(_.map(_.label) == Vector("LiveView", "Browser hook")),
          browserSources.exists(_.exists(_.text.contains("createBrowserInteropHook"))),
          apiQualifiedNames.contains("scalive.LiveView"),
          liveViewSearchEntries.nonEmpty,
          liveViewSearchEntries.exists(_.text.contains("mounted independently")),
          hasHomePage,
          home.contains("href=\"/learn#start-here\""),
          home.contains("data-callout=\"info\""),
          rendered.contains("href=\"/learn/quick-start\""),
          rendered.contains("scalive.LiveView"),
          rendered.contains("View source"),
          !rendered.contains("<script")
        )

      result.fold(error => assertTrue(error.isEmpty), identity)
    }
  )
end GeneratedDocumentationSpec
