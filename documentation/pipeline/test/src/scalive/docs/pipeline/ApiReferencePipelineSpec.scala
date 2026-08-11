package scalive.docs.pipeline

import java.nio.file.Files
import java.nio.file.Path

import zio.test.*

import scalive.docs.model.*

object ApiReferencePipelineSpec extends ZIOSpecDefault:
  private val targetRoots = Vector(
    Path.of(classOf[scalive.LiveView[?, ?]].getProtectionDomain.getCodeSource.getLocation.toURI),
    Path.of(
      classOf[scalive.testing.DisconnectedRender.type].getProtectionDomain.getCodeSource.getLocation.toURI
    )
  ).distinct
  private val dependencyClasspath =
    System.getProperty("java.class.path").split(java.io.File.pathSeparator).toVector
      .map(Path.of(_))
      .filter(Files.exists(_))
  private val repositoryRoot = Iterator
    .iterate(targetRoots.head.toAbsolutePath.normalize())(_.getParent)
    .takeWhile(_ != null)
    .find(path => Files.isRegularFile(path.resolve("build.mill")))
    .getOrElse(throw IllegalStateException("Unable to locate the repository root."))

  private val config = ApiReferenceConfig(
    repositoryRoot,
    targetRoots,
    dependencyClasspath,
    ApiReferenceMetadata(
      "https://github.com/phfroidmont/scalive",
      "0123456789abcdef0123456789abcdef01234567",
      "18.1.0",
      "DomDefsGenerator.mill"
    )
  )

  override def spec = suite("ApiReferencePipelineSpec")(
    test("extracts the package-convention boundary and import scalive exposures") {
      val result = ApiReferencePipeline.generate(config)
      val symbols = result.toOption.toVector.flatMap(_.symbols)

      val div = symbols.find(_.qualifiedName == "scalive.div")
      val liveUpload = symbols.find(_.qualifiedName == "scalive.LiveUpload")
      val liveView = symbols.find(_.qualifiedName == "scalive.LiveView")
      val liveViewTrait = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.LiveView" && symbol.kind == ApiSymbolKind.Trait
      )
      val handleMessage = symbols.find(_.qualifiedName == "scalive.LiveView.handleMessage")
      val routedMount   = symbols.find(_.qualifiedName == "scalive.LiveView.Routed.mount")
      val liveViewDocumentation = liveViewTrait.toVector.flatMap(_.signatures)
        .flatMap(_.documentation)
      val liveViewLinks = liveViewDocumentation.flatMap(_.body).flatMap {
        case Block.Paragraph(content) => content.collect { case link: Inline.Link => link }
        case _                        => Vector.empty
      }
      val exportedNames = Set("scalive.LiveStream", "scalive.LiveUpload")
      val liveComponent = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.liveComponent" && symbol.kind == ApiSymbolKind.Def
      )

      assertTrue(
        result.isRight,
        symbols.exists(_.qualifiedName == "scalive.LiveView"),
        symbols.exists(_.qualifiedName == "scalive.codecs.Encoder"),
        symbols.exists(_.qualifiedName == "scalive.testing.DisconnectedRender"),
        div.exists(_.signatures.exists(_.origin.exposure == ApiExposure.Inherited)),
        div.exists(_.signatures.forall(_.source == ApiSource.GeneratedDom)),
        div.exists(_.summary.nonEmpty),
        div.flatMap(_.signatures.headOption).exists(signature =>
          result.toOption.exists(_.metadata.sourceLink(signature.source).label ==
            "Generated from Scala DOM Types 18.1.0")
        ),
        liveView.flatMap(_.signatures.headOption).exists(signature =>
          result.toOption.exists(_.metadata.sourceLink(signature.source).url.contains(
            "/blob/0123456789abcdef0123456789abcdef01234567/scalive/src/scalive/"
          ))
        ),
        liveUpload.exists(_.signatures.exists(_.origin.exposure == ApiExposure.Exported)),
        liveUpload.exists(_.route == "/api/scalive/live-upload"),
        exportedNames.forall(name =>
          symbols.find(_.qualifiedName == name)
            .exists(_.signatures.exists(_.origin.exposure == ApiExposure.Exported))
        ),
        liveComponent.exists(_.signatures.size == 2),
        liveViewDocumentation.exists(_.body.count(_.isInstanceOf[Block.Paragraph]) == 2),
        liveViewDocumentation.exists(_.tags.map(_.subject).contains(Some("Msg"))),
        liveViewLinks.exists(_.target == LinkTarget.Internal(
          "/api/scalive/html-element",
          None
        )),
        handleMessage.exists(_.signatures.flatMap(_.documentation).exists(documentation =>
          documentation.body.count(_.isInstanceOf[Block.Paragraph]) == 2 &&
            documentation.tags.map(_.name) == Vector("param", "param", "return")
        )),
        routedMount.exists(symbol =>
          symbol.signatures.size == 2 && symbol.signatures.forall(_.documentation.nonEmpty)
        ),
        symbols.filter(_.qualifiedName == "scalive.LiveView").forall(!_.summary.contains("[[")),
        symbols.forall(_.summary.nonEmpty),
        !symbols.exists(_.qualifiedName.startsWith("scalive.upload.")),
        !symbols.exists(_.qualifiedName.startsWith("scalive.streams.")),
        !symbols.exists(_.qualifiedName.startsWith("scalive.socket.")),
        symbols.forall(symbol =>
          symbol.qualifiedName == "scalive" ||
            symbol.qualifiedName == "scalive.codecs" ||
            symbol.qualifiedName == "scalive.testing" ||
            symbol.qualifiedName.startsWith("scalive.")
        )
      )
    },
    test("filters protected, private, and synthetic implementation members") {
      val symbols = ApiReferencePipeline.generate(config).toOption.toVector.flatMap(_.symbols)
      assertTrue(
        !symbols.exists(_.qualifiedName.contains("boolAsPresenceHtmlAttr")),
        !symbols.exists(_.qualifiedName.contains("scopedPrivateMember")),
        !symbols.exists(_.name.contains("$default$")),
        !symbols.exists(_.name == "<init>"),
        !symbols.exists(_.qualifiedName == "scalive.JSCommands.Binding"),
        !symbols.exists(_.qualifiedName == "scalive.ComponentRef.cid"),
        !symbols.exists(_.qualifiedName == "scalive.StaticAssets.manifest"),
        !symbols.exists(_.qualifiedName == "scalive.PhxUpdate.value")
      )
    },
    test("emits deterministic IDs, source ranges, routes, and signatures") {
      val first = ApiReferencePipeline.generate(config)
      val second = ApiReferencePipeline.generate(config)
      val symbols = first.toOption.toVector.flatMap(_.symbols)
      val repositorySources = symbols.flatMap(_.signatures).collect {
        case ApiSignature(_, _, _, _, ApiSource.Repository(region), _) => region
      }

      assertTrue(
        first == second,
        symbols.map(_.id).distinct.size == symbols.size,
        symbols.flatMap(_.signatures.map(_.id)).distinct.size == symbols.flatMap(_.signatures).size,
        symbols.flatMap(_.signatures).forall(_.tokens.exists(_.styles.nonEmpty)),
        symbols.forall(_.route.matches("/api(?:/[a-z0-9]+(?:-[a-z0-9]+)*)+")),
        repositorySources.forall(region =>
          !region.path.startsWith("/") && !region.path.startsWith("out/") && region.startLine > 0
        ),
        repositorySources.forall(region => Files.isRegularFile(repositoryRoot.resolve(region.path)))
      )
    }
  ) @@ TestAspect.sequential
end ApiReferencePipelineSpec
