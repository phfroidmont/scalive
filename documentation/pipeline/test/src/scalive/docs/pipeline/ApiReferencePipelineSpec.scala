package scalive.docs.pipeline

import java.nio.file.Files
import java.nio.file.Path

import zio.test.*

import scalive.docs.model.*

object ApiReferencePipelineSpec extends ZIOSpecDefault:
  private def hasSignature(
    symbols: Vector[ApiSymbol],
    qualifiedName: String,
    kind: ApiSymbolKind,
    expected: String
  ): Boolean =
    symbols.exists(symbol =>
      symbol.qualifiedName == qualifiedName &&
        symbol.kind == kind &&
        symbol.signatures.exists(_.signature == expected)
    )

  private def packageSignaturesHideSyntheticNames(symbols: Vector[ApiSymbol]): Boolean =
    symbols.iterator
      .filter(_.kind == ApiSymbolKind.Package)
      .flatMap(_.signatures)
      .forall(!_.signature.contains("$package"))

  private def signaturesHideSyntheticParents(symbols: Vector[ApiSymbol]): Boolean =
    symbols.iterator.flatMap(_.signatures).forall { signature =>
      !signature.signature.contains("reflect.Enum") &&
      !signature.signature.contains("Mirror.Sum") &&
      !signature.signature.contains("Mirror.Product")
    }

  private def symbolIdsAreUnique(symbols: Vector[ApiSymbol]): Boolean =
    symbols.iterator.map(_.id).toSet.size == symbols.size

  private def signatureIdsAreUnique(symbols: Vector[ApiSymbol]): Boolean =
    val ids = symbols.iterator.flatMap(_.signatures).map(_.id).toVector
    ids.toSet.size == ids.size

  private def signaturesAreHighlighted(symbols: Vector[ApiSymbol]): Boolean =
    symbols.iterator.flatMap(_.signatures).forall(_.tokens.exists(_.styles.nonEmpty))

  private def signatureTokensMatchText(symbols: Vector[ApiSymbol]): Boolean =
    symbols.iterator.flatMap(_.signatures).forall(signature =>
      signature.tokens.iterator.map(_.text).mkString == signature.signature
    )

  private def routesAreValid(symbols: Vector[ApiSymbol]): Boolean =
    symbols.forall(_.route.matches("/api(?:/[a-z0-9]+(?:-[a-z0-9]+)*)+"))

  private def repositorySourcesAreValid(symbols: Vector[ApiSymbol]): Boolean =
    symbols.iterator.flatMap(_.signatures).forall {
      case ApiSignature(_, _, _, _, ApiSource.Repository(region), _) =>
        !region.path.startsWith("/") &&
        !region.path.startsWith("out/") &&
        region.startLine > 0 &&
        Files.isRegularFile(repositoryRoot.resolve(region.path))
      case _ => true
    }

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
      val liveViewObject = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.LiveView" && symbol.kind == ApiSymbolKind.Object
      )
      val handleMessage = symbols.find(_.qualifiedName == "scalive.LiveView.handleMessage")
      val hooks         = symbols.find(_.qualifiedName == "scalive.LiveView.hooks")
      val routedMount   = symbols.find(_.qualifiedName == "scalive.LiveView.Routed.mount")
      val routedEventless = symbols.find(
        _.qualifiedName == "scalive.LiveView.Routed.Eventless"
      )
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
      val mod = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.Mod" && symbol.kind == ApiSymbolKind.Trait
      )
      val liveRouteParamsBuilder = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.LiveRouteParamsBuilder" &&
          symbol.kind == ApiSymbolKind.Class
      )
      val packages = symbols.filter(_.kind == ApiSymbolKind.Package).map(symbol =>
        symbol.qualifiedName -> symbol.ownerId
      ).toMap
      assertTrue(
        result.isRight,
        symbols.exists(_.qualifiedName == "scalive.LiveView"),
        symbols.exists(_.qualifiedName == "scalive.codecs.Encoder"),
        symbols.exists(_.qualifiedName == "scalive.testing.DisconnectedRender"),
        hasSignature(symbols, "scalive", ApiSymbolKind.Package,
          "package object scalive extends HtmlTags with HtmlAttrs with ComplexHtmlKeys with Components"),
        hasSignature(symbols, "scalive.codecs", ApiSymbolKind.Package, "package scalive.codecs"),
        hasSignature(symbols, "scalive.testing", ApiSymbolKind.Package, "package scalive.testing"),
        packages.get("scalive").contains(None),
        packages.get("scalive.codecs").contains(Some("package:scalive")),
        packages.get("scalive.testing").contains(Some("package:scalive")),
        packageSignaturesHideSyntheticNames(symbols),
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
        liveViewTrait.exists(_.route == "/api/scalive/live-view"),
        liveViewObject.exists(_.route == "/api/scalive/live-view/companion"),
        handleMessage.exists(_.route == "/api/scalive/live-view"),
        exportedNames.forall(name =>
          symbols.find(_.qualifiedName == name)
            .exists(_.signatures.exists(_.origin.exposure == ApiExposure.Exported))
        ),
        liveComponent.exists(_.signatures.size == 2),
        liveViewTrait.exists(_.signatures.exists(_.signature == "trait LiveView[Msg, Model]")),
        mod.exists(_.signatures.exists(_.signature == "trait Mod[+Msg]")),
        liveRouteParamsBuilder.exists(_.signatures.exists(_.signature.startsWith(
          "class LiveRouteParamsBuilder[R, A, -Need, Ctx, Params, Capability <: LiveRouteParamsCapability]"
        ))),
        hasSignature(symbols, "scalive.HtmlElement", ApiSymbolKind.Class,
          "class HtmlElement[+Msg](tag: HtmlTag, mods: Vector[Mod[Msg]])"),
        hasSignature(symbols, "scalive.HtmlElement.appended", ApiSymbolKind.Def,
          "def appended[Msg2 >: Msg](mod: Mod[Msg2]*): HtmlElement[Msg2]"),
        hasSignature(symbols, "scalive.component", ApiSymbolKind.Def,
          "def component[Props, Msg, Model](liveComponent: LiveComponent[Props, Msg, Model], id: String): LiveComponentInstance[Props, Msg, Model]"),
        hasSignature(symbols, "scalive.dropTarget", ApiSymbolKind.Extension,
          "extension def dropTarget[R](upload: LiveUpload[R]): Mod.Attr[Nothing]"),
        hasSignature(symbols, "scalive.splitBy", ApiSymbolKind.Extension,
          "extension def splitBy[T](items: IterableOnce[T])[Key, Msg](key: T => Key)(project: (Key, T) => HtmlElement[Msg]): Mod[Msg]"),
        hasSignature(symbols, "scalive.AsyncValue", ApiSymbolKind.Enum, "enum AsyncValue[+A]"),
        hasSignature(symbols, "scalive.AsyncValue", ApiSymbolKind.Object, "object AsyncValue"),
        hasSignature(symbols, "scalive.Mod.Attr", ApiSymbolKind.Enum,
          "enum Attr[+Msg] extends Mod[Msg]"),
        hasSignature(symbols, "scalive.FormAction.Method", ApiSymbolKind.Enum,
          "enum Method(attributeValue: String)"),
        hasSignature(symbols, "scalive.PhxUpdate", ApiSymbolKind.Enum,
          "enum PhxUpdate(value: String)"),
        hasSignature(symbols, "scalive.LiveParamsCodec.DecodeError", ApiSymbolKind.Class,
          "class DecodeError(message: String, cause: Option[Throwable] = ...) extends RuntimeException"),
        hasSignature(symbols, "scalive.StaticAssetSource.Classpath", ApiSymbolKind.Class,
          "class Classpath(resourcePrefix: String, assets: Set[String], classLoader: ClassLoader) extends StaticAssetSource"),
        hasSignature(symbols, "scalive.ContextAppend", ApiSymbolKind.Object,
          "object ContextAppend extends LowPriorityContextAppend"),
        signaturesHideSyntheticParents(symbols),
        hooks.exists(_.signatures.exists(_.signature == "def hooks: LiveHooks[Msg, Model]")),
        routedEventless.exists(_.signatures.exists(_.signature ==
          "trait Eventless[Model, Params] extends LiveView.Eventless[Model] with LiveView.Routed[Nothing, Model, Params]"
        )),
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

      assertTrue(
        first == second,
        symbolIdsAreUnique(symbols),
        signatureIdsAreUnique(symbols),
        signaturesAreHighlighted(symbols),
        signatureTokensMatchText(symbols),
        routesAreValid(symbols),
        repositorySourcesAreValid(symbols)
      )
    }
  ) @@ TestAspect.sequential
end ApiReferencePipelineSpec
