package scalive.docs

import java.net.URL
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

import scalive.docs.model.DiagramCatalog

object ClasspathResourcesSpec extends ZIOSpecDefault:
  private def resources(path: String): Task[List[URL]] =
    ZIO.attempt(Thread.currentThread().getContextClassLoader.getResources(path).asScala.toList)

  override def spec = suite("ClasspathResourcesSpec")(
    test("loads generated documentation and its required runtime assets") {
      for
        content     <- resources(GeneratedDocumentation.ResourcePath)
        searchIndex <- resources(GeneratedDocumentation.SearchResourcePath)
        js          <- resources("public/app.js")
        css         <- resources("public/app.css")
        fonts       <- resources("public/fonts.css")
        favicon     <- resources("public/favicon.svg")
        instrumentLicense <- resources("public/instrument-sans-OFL.txt")
        jetbrainsLicense  <- resources("public/jetbrains-mono-OFL.txt")
        diagramAssets <- ZIO.foreach(DiagramCatalog.entries.flatMap(_.assets)) { asset =>
                           resources(s"public/${asset.filename}")
                         }
        bundle = GeneratedDocumentation.load(getClass.getClassLoader)
        search = GeneratedDocumentation.loadSearchEntries(getClass.getClassLoader)
        hasContent = bundle.exists(value => value.pages.nonEmpty && value.examples.nonEmpty)
        hasCounter = bundle.exists(_.examples.exists(_.descriptor.id == "counter"))
        searchMatchesBundle = search == bundle.map(_.searchEntries)
      yield assertTrue(
        content.size == 1,
        searchIndex.size == 1,
        hasContent,
        hasCounter,
        searchMatchesBundle,
        js.size == 1,
        css.size == 1,
        fonts.size == 1,
        favicon.size == 1,
        instrumentLicense.size == 1,
        jetbrainsLicense.size == 1,
        diagramAssets.forall(_.size == 1)
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
