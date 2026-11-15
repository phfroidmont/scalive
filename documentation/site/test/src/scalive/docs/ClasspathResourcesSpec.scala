package scalive.docs

import java.net.URL
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

import scalive.{StaticAssetRoot, StaticAssets}
import scalive.docs.model.DiagramCatalog

object ClasspathResourcesSpec extends ZIOSpecDefault:
  private def resources(path: String): Task[List[URL]] =
    ZIO.attempt(Thread.currentThread().getContextClassLoader.getResources(path).asScala.toList)

  override def spec = suite("ClasspathResourcesSpec")(
    test("loads generated documentation and its required runtime assets") {
      for
        content     <- resources(GeneratedDocumentation.ResourcePath)
        searchIndex <- resources(GeneratedDocumentation.SearchResourcePath)
        assets      <- StaticAssets.manifest(StaticAssetRoot.classpath("public"))
        required = Vector(
                     "app.js",
                     "app.css",
                     "fonts.css",
                     "favicon.svg",
                     "instrument-sans-OFL.txt",
                     "jetbrains-mono-OFL.txt",
                     "search-index.json"
                   ) ++ DiagramCatalog.entries.flatMap(_.assets).map(_.filename)
        missing = required.filter(assets.pathOption(_).isEmpty)
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
        missing.isEmpty
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
