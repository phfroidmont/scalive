package scalive.docs

import java.net.URL
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

object ClasspathResourcesSpec extends ZIOSpecDefault:
  private def resources(path: String): Task[List[URL]] =
    ZIO.attempt(Thread.currentThread().getContextClassLoader.getResources(path).asScala.toList)

  override def spec = suite("ClasspathResourcesSpec")(
    test("loads one generated content bundle alongside unique npm assets") {
      for
        content     <- resources(GeneratedDocumentation.ResourcePath)
        searchIndex <- resources(GeneratedDocumentation.SearchResourcePath)
        js          <- resources("public/app.js")
        css         <- resources("public/app.css")
        fonts       <- resources("public/fonts.css")
        favicon     <- resources("public/favicon.svg")
        instrumentLicense <- resources("public/instrument-sans-OFL.txt")
        jetbrainsLicense  <- resources("public/jetbrains-mono-OFL.txt")
        bundle = GeneratedDocumentation.load(getClass.getClassLoader)
        search = GeneratedDocumentation.loadSearchEntries(getClass.getClassLoader)
      yield assertTrue(
        content.size == 1,
        searchIndex.size == 1,
        bundle.exists(_.pages.nonEmpty),
        bundle.exists(
          _.examples.map(_.descriptor.id) == Vector("counter", "lifecycle", "shopping-cart")
        ),
        search == bundle.map(_.searchEntries),
        js.size == 1,
        css.size == 1,
        fonts.size == 1,
        favicon.size == 1,
        instrumentLicense.size == 1,
        jetbrainsLicense.size == 1
      )
    },
    test("keeps pipeline dependencies off the site runtime classpath") {
      for
        laika     <- resources("laika/api/MarkupParser.class")
        query     <- resources("tastyquery/Contexts$Context.class")
        inspector <- resources("scala/tasty/inspector/TastyInspector.class")
      yield assertTrue(laika.isEmpty, query.isEmpty, inspector.isEmpty)
    }
  )
end ClasspathResourcesSpec
