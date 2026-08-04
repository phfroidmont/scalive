package scalive.docs.pipeline

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.jdk.ClasspathLoaders
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

  private def tastyContext(): Context =
    val javaBase = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", "java.base")
    Context.initialize(
      ClasspathLoaders.read((testClasses +: javaBase +: runtimeClasspath).distinct.toList)
    )

  override def spec = suite("TastyInspectionSpec")(
    test("TASTy Query exposes language features and visibility") {
      given Context = tastyContext()
      val context   = summon[Context]
      val fixture   = context.findTopLevelClass(
        "scalive.docs.pipeline.fixtures.TastyQueryFixture"
      )
      val overloads = fixture.declarations.collect {
        case method: TermSymbol if method.name.toString == "overloaded" => method
      }
      val extension = fixture.declarations.collectFirst {
        case method: TermSymbol if method.name.toString == "fixtureExtension" => method
      }
      val inherited = fixture.linearization.flatMap(_.declarations).collectFirst {
        case method: TermSymbol if method.name.toString == "inheritedMember" => method
      }
      val protectedMember = fixture.declarations.collectFirst {
        case method: TermSymbol if method.name.toString == "protectedMember" => method
      }
      val exports = context.findTopLevelModuleClass(
        "scalive.docs.pipeline.fixtures.TastyQueryExports"
      )
      val exported = exports.declarations.collectFirst {
        case method: TermSymbol if method.name.toString == "exportedMember" => method
      }
      val fixturePackage = context.findPackage("scalive.docs.pipeline.fixtures")
      val isOpaque = fixturePackage.declarations.collect {
        case owner: ClassSymbol if owner.isModuleClass => owner.declarations
      }.flatten.exists {
        case member: TypeMemberSymbol =>
          member.name.toString == "TastyQueryOpaque" && member.isOpaqueTypeAlias
        case _ => false
      }

      assertTrue(
        overloads.size == 2,
        extension.exists(_.isExtensionMethod),
        inherited.nonEmpty,
        protectedMember.exists(!_.isPublic),
        exported.exists(_.isExport),
        isOpaque,
        overloads.forall(_.tree.exists(!_.pos.isUnknown))
      )
    },
    test("TASTy Inspector reads documentation comments") {
      val comments = TastyDocumentation
        .inspect(Seq(testClasses), runtimeClasspath)
        .map(_.flatMap(_.comment))

      assertTrue(
        comments.exists(_.exists(_.contains("Fixture owner documentation"))),
        comments.exists(_.exists(_.contains("Integer overload documentation"))),
        comments.exists(_.exists(_.contains("Inherited member documentation")))
      )
    },
    test("actual compilation outputs expose inherited DOM and testing APIs") {
      given Context = tastyContext()
      val context       = summon[Context]
      val packageObject = context.findTopLevelModuleClass("scalive.package")
      val inheritedNames = packageObject.linearization
        .flatMap(_.declarations)
        .map(_.name.toString)
        .toSet
      val testing = context.findTopLevelModuleClass("scalive.testing.DisconnectedRender")

      assertTrue(
        inheritedNames.contains("div"),
        inheritedNames.contains("htmlAttr"),
        testing.declarations.exists(_.name.toString == "run")
      )
    }
  )
end TastyInspectionSpec
