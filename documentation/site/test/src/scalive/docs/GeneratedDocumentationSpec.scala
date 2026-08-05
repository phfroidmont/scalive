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
        val representativeRoutes = Set("/", "/learn", "/api/scalive/live-view")
        val rendered = bundle.pages.filter(page => representativeRoutes(page.route))
          .map(page => HtmlBuilder.build(renderer.render(page))).mkString

        assertTrue(
          representativeRoutes.subsetOf(bundle.pages.map(_.route).toSet),
          bundle.examples.map(_.descriptor.id) == Vector("counter"),
          bundle.examples.head.source.text.contains("class CounterExample"),
          bundle.apiReference.symbols.exists(_.qualifiedName == "scalive.LiveView"),
          bundle.searchEntries.exists(_.title == "scalive.LiveView"),
          rendered.contains("<h1>Scalive</h1>"),
          rendered.contains("<h2 id=\"why-scalive\">Why Scalive</h2>"),
          rendered.contains("href=\"/learn#start-here\""),
          rendered.contains("data-callout=\"info\""),
          rendered.contains("GeneratedDocumentation.scala"),
          rendered.contains("scalive.LiveView"),
          rendered.contains("View source"),
          !rendered.contains("<script")
        )

      result.fold(error => assertTrue(error.isEmpty), identity)
    }
  )
end GeneratedDocumentationSpec
