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
        content <- resources(GeneratedDocumentation.ResourcePath)
        js      <- resources("public/app.js")
        css     <- resources("public/app.css")
      yield assertTrue(
        content.size == 1,
        GeneratedDocumentation.load(getClass.getClassLoader).exists(_.pages.nonEmpty),
        js.size == 1,
        css.size == 1
      )
    },
    test("keeps pipeline dependencies off the site runtime classpath") {
      for
        laika <- resources("laika/api/MarkupParser.class")
        tasty <- resources("tastyquery/Contexts$Context.class")
      yield assertTrue(laika.isEmpty, tasty.isEmpty)
    }
  )
end ClasspathResourcesSpec
