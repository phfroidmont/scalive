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

        assertTrue(
          representativeRoutes.subsetOf(bundle.pages.map(_.route).toSet),
          bundle.examples.map(_.descriptor.id) == Vector("counter", "lifecycle", "shopping-cart"),
          bundle.examples.head.source.text.contains("class CounterExample"),
          bundle.examples.exists(example =>
            example.descriptor.id == "shopping-cart" &&
              example.source.text.contains("class ShoppingCartExample")
          ),
          bundle.apiReference.symbols.exists(_.qualifiedName == "scalive.LiveView"),
          bundle.searchEntries.exists(_.title == "scalive.LiveView"),
          bundle.searchEntries.exists(entry =>
            entry.title == "scalive.LiveView" && entry.text.contains("mounted independently")
          ),
          bundle.pages.exists(page =>
            page.route == "/" && page.metadata.title == "Live interfaces. Typed end to end."
          ),
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
