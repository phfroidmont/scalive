package scalive.docs.pipeline

import java.nio.file.Path

import zio.test.*

import scalive.docs.pipeline.fixtures.TastyQueryFixture

object TastyInspectionSpec extends ZIOSpecDefault:
  private val testClasses = Path.of(
    classOf[TastyQueryFixture].getProtectionDomain.getCodeSource.getLocation.toURI
  )

  private val runtimeClasspath =
    System.getProperty("java.class.path").split(java.io.File.pathSeparator).toVector
      .map(Path.of(_))
      .filter(path => java.nio.file.Files.exists(path))

  override def spec = suite("TastyInspectionSpec")(
    test("TASTy Inspector reads documentation comments") {
      val comments = TastyDocumentation
        .inspect(Seq(testClasses), runtimeClasspath)
        .map(_.flatMap(_.comment))

      assertTrue(
        comments.exists(_.exists(_.contains("Fixture owner documentation"))),
        comments.exists(_.exists(_.contains("Integer overload documentation"))),
        comments.exists(_.exists(_.contains("Inherited member documentation")))
      )
    }
  )
end TastyInspectionSpec
