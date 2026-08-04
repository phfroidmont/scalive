package scalive.docs

import java.net.URL
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.util.Using
import zio.*
import zio.test.*

object ClasspathResourcesSpec extends ZIOSpecDefault:
  private val markerPath    = "scalive/docs/generated/pipeline.txt"
  private val markerContent = "scalive-documentation-pipeline-v1\n"

  private def resources(path: String): Task[List[URL]] =
    ZIO.attempt(Thread.currentThread().getContextClassLoader.getResources(path).asScala.toList)

  private def read(url: URL): Task[String] =
    ZIO.attemptBlocking(
      Using.resource(url.openStream())(stream =>
        String(stream.readAllBytes(), StandardCharsets.UTF_8)
      )
    )

  override def spec = suite("ClasspathResourcesSpec")(
    test("loads one generated resource alongside unique npm assets") {
      for
        marker <- resources(markerPath)
        js     <- resources("public/app.js")
        css    <- resources("public/app.css")
        content <- ZIO.foreach(marker.headOption)(read)
      yield assertTrue(
        marker.size == 1,
        content.contains(markerContent),
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
