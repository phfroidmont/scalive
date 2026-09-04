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
      !signature.signature.contains("extends reflect.Enum") &&
      !signature.signature.contains("extends deriving.Mirror.Sum") &&
      !signature.signature.contains("extends deriving.Mirror.Product")
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
    ),
    Path.of(
      classOf[scalive.testing.TastyQueryEnum]
        .getProtectionDomain.getCodeSource.getLocation.toURI
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
  private val apiSettings = ApiReferenceFiles
    .loadSettings(repositoryRoot.resolve("documentation/pipeline/api/reference.json"))
    .fold(error => throw IllegalStateException(error.message), identity)

  private val config = ApiReferenceConfig(
    repositoryRoot,
    targetRoots,
    dependencyClasspath,
    apiSettings.metadata("0123456789abcdef0123456789abcdef01234567"),
    curatedSummaries = apiSettings.summaries
  )
  private lazy val result  = ApiReferencePipeline.generate(config)
  private lazy val symbols = result.toOption.toVector.flatMap(_.symbols)

  override def spec = suite("ApiReferencePipelineSpec")(
    test("extracts only the public API boundary") {
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
        !symbols.exists(_.qualifiedName.startsWith("scalive.upload.")),
        !symbols.exists(_.qualifiedName.startsWith("scalive.streams.")),
        !symbols.exists(_.qualifiedName.startsWith("scalive.socket.")),
        symbols.forall(symbol =>
          symbol.qualifiedName == "scalive" ||
            symbol.qualifiedName == "scalive.codecs" ||
            symbol.qualifiedName == "scalive.testing" ||
            symbol.qualifiedName.startsWith("scalive.")
        ),
        !symbols.exists(_.qualifiedName.contains("boolAsPresenceHtmlAttr")),
        !symbols.exists(_.qualifiedName.contains("scopedPrivateMember")),
        !symbols.exists(_.qualifiedName.startsWith("scalive.LiveConnectedTurnGuard")),
        !symbols.exists(_.qualifiedName.startsWith("scalive.LiveRouteDefinition")),
        !symbols.exists(_.name.contains("$default$")),
        !symbols.exists(_.name == "<init>"),
        !symbols.exists(_.qualifiedName == "scalive.JSCommands.Binding"),
        !symbols.exists(_.qualifiedName == "scalive.ComponentRef.cid"),
        !symbols.exists(_.qualifiedName == "scalive.StaticAssets.manifest"),
        !symbols.exists(_.qualifiedName == "scalive.PhxUpdate.value")
      )
    },
    test("transforms documentation, signatures, and exposures") {
      val div        = symbols.find(_.qualifiedName == "scalive.div")
      val liveUpload = symbols.find(_.qualifiedName == "scalive.LiveUpload")
      val liveViewTrait = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.LiveView" && symbol.kind == ApiSymbolKind.Trait
      )
      val handleMessage = symbols.find(_.qualifiedName == "scalive.LiveView.handleMessage")
      val hooks         = symbols.find(_.qualifiedName == "scalive.LiveView.hooks")
      val routedMount   = symbols.find(_.qualifiedName == "scalive.LiveView.Routed.mount")
      val routedEventless = symbols.find(_.qualifiedName == "scalive.LiveView.Routed.Eventless")
      val guardConnectedTurns = symbols.find(
        _.qualifiedName == "scalive.LiveSessionBuilder.Admitted.guardConnectedTurns"
      )
      val documentation = liveViewTrait.toVector.flatMap(_.signatures).flatMap(_.documentation)
      val links = documentation.flatMap(_.body).flatMap {
        case Block.Paragraph(content) => content.collect { case link: Inline.Link => link }
        case _                        => Vector.empty
      }

      assertTrue(
        div.exists(_.signatures.exists(_.origin.exposure == ApiExposure.Inherited)),
        div.exists(_.signatures.forall(_.source == ApiSource.GeneratedDom)),
        Set("scalive.LiveStream", "scalive.LiveUpload").forall(name =>
          symbols.find(_.qualifiedName == name)
            .exists(_.signatures.exists(_.origin.exposure == ApiExposure.Exported))
        ),
        liveUpload.exists(_.signatures.exists(_.origin.exposure == ApiExposure.Exported)),
        symbols.find(symbol =>
          symbol.qualifiedName == "scalive.liveComponent" && symbol.kind == ApiSymbolKind.Def
        ).exists(_.signatures.size == 7),
        liveViewTrait.exists(_.signatures.exists(_.signature == "trait LiveView[Msg, Model]")),
        hasSignature(symbols, "scalive.dropTarget", ApiSymbolKind.Extension,
          "extension def dropTarget[R](upload: LiveUpload[R]): Mod.Attr[Nothing]"),
        signaturesHideSyntheticParents(symbols),
        hooks.exists(_.signatures.exists(_.signature == "def hooks: LiveHooks[Msg, Model]")),
        routedEventless.exists(_.signatures.exists(_.signature ==
          "trait Eventless[Model, Params] extends LiveView.Routed[Nothing, Model, Params]"
        )),
        documentation.exists(_.body.count(_.isInstanceOf[Block.Paragraph]) == 2),
        documentation.exists(_.tags.map(_.subject).contains(Some("Msg"))),
        links.exists(_.target == LinkTarget.Internal("/api/scalive/html-element", None)),
        handleMessage.exists(_.signatures.flatMap(_.documentation).exists(documentation =>
          documentation.body.count(_.isInstanceOf[Block.Paragraph]) == 2 &&
            documentation.tags.map(_.name) == Vector("param", "param", "return")
        )),
        routedMount.exists(symbol =>
          symbol.signatures.size == 1 && symbol.signatures.forall(_.documentation.nonEmpty)
        ),
        guardConnectedTurns.exists(_.signatures.forall(_.documentation.nonEmpty)),
        symbols.filter(_.qualifiedName == "scalive.LiveView").forall(!_.summary.contains("[[")),
        symbols.forall(_.summary.nonEmpty)
      )
    },
    test("keeps the public Forms entry points explicitly documented") {
      val required = Set(
        "scalive.FieldInput",
        "scalive.FieldInput.apply",
        "scalive.FieldInput.text",
        "scalive.Form",
        "scalive.Form.added",
        "scalive.Form.field",
        "scalive.Form.movedBefore",
        "scalive.Form.onChange",
        "scalive.Form.rows",
        "scalive.Form.updatedRaw",
        "scalive.FormAddress",
        "scalive.FormCodec",
        "scalive.FormCodec.requiredString",
        "scalive.FormData",
        "scalive.FormData.get",
        "scalive.FormData.raw",
        "scalive.FormData.values",
        "scalive.FormDefinition",
        "scalive.FormDefinition.emap",
        "scalive.FormDefinition.event",
        "scalive.FormDefinition.initial",
        "scalive.FormDefinition.withLimits",
        "scalive.FormDefinition.workflow",
        "scalive.FormEvent",
        "scalive.FormEventMeta",
        "scalive.FormEventMeta.browserTarget",
        "scalive.FormEventMeta.diagnostics",
        "scalive.FormEventMeta.metadata",
        "scalive.FormEventMeta.submitter",
        "scalive.FormEventMeta.target",
        "scalive.FormField",
        "scalive.FormField.emap",
        "scalive.FormField.initial",
        "scalive.FormField.required",
        "scalive.FormField.validate",
        "scalive.FormFieldView",
        "scalive.FormFieldView.checkbox",
        "scalive.FormFieldView.email",
        "scalive.FormFieldView.errorFeedback",
        "scalive.FormFieldView.hasVisibleErrors",
        "scalive.FormFieldView.hidden",
        "scalive.FormFieldView.password",
        "scalive.FormFieldView.select",
        "scalive.FormFieldView.text",
        "scalive.FormFieldView.textarea",
        "scalive.FormFieldView.validationAttributes",
        "scalive.FormLimits",
        "scalive.FormPath",
        "scalive.FormRoot",
        "scalive.FormRoot.field",
        "scalive.FormRoot.optionalText",
        "scalive.FormRoot.product",
        "scalive.FormRoot.rows",
        "scalive.FormRoot.text",
        "scalive.FormRoot.texts",
        "scalive.FormRowKey",
        "scalive.FormRowKey.from",
        "scalive.FormRowView.address",
        "scalive.FormRowView.bind",
        "scalive.FormRowView.field",
        "scalive.FormRowView.isUsed",
        "scalive.FormRowView.key",
        "scalive.FormRowView.presence",
        "scalive.FormSubmitter",
        "scalive.FormWorkflow",
        "scalive.FormWorkflow.beginSave",
        "scalive.FormWorkflow.isDirty",
        "scalive.FormWorkflow.reset",
        "scalive.FormWorkflow.saveCancelled",
        "scalive.FormWorkflow.saveFailed",
        "scalive.FormWorkflow.saveSucceeded",
        "scalive.FormWorkflow.updated",
        "scalive.HttpFormDecoder",
        "scalive.HttpFormDecoder.Error",
        "scalive.HttpFormDecoder.decode",
        "scalive.HttpFormDecoder.respond",
        "scalive.HttpFormDecoder.urlEncoded",
        "scalive.HttpFormDecoder.urlEncodedValue",
        "scalive.PhoenixNestedParamsAdapter",
        "scalive.PhoenixNestedParamsAdapter.configured",
        "scalive.PhoenixNestedParamsAdapter.fieldName",
        "scalive.PhoenixNestedParamsAdapter.persistentId",
        "scalive.PhoenixNestedParamsAdapter.sortControl",
        "scalive.RepeatedGroup",
        "scalive.RepeatedGroup.field",
        "scalive.RepeatedGroup.optionalText",
        "scalive.RepeatedGroup.product",
        "scalive.RepeatedGroup.text",
        "scalive.RepeatedGroup.texts",
        "scalive.RepeatedRows",
        "scalive.RepeatedRows.initial",
        "scalive.RepeatedRows.row",
        "scalive.RawFormEvent"
      )
      val requiredExtensions = Set(
        "extension:scalive.FormFieldView.checkbox",
        "extension:scalive.FormFieldView.email",
        "extension:scalive.FormFieldView.errorFeedback",
        "extension:scalive.FormFieldView.errorId",
        "extension:scalive.FormFieldView.hasVisibleErrors",
        "extension:scalive.FormFieldView.hidden",
        "extension:scalive.FormFieldView.id",
        "extension:scalive.FormFieldView.name",
        "extension:scalive.FormFieldView.password",
        "extension:scalive.FormFieldView.select",
        "extension:scalive.FormFieldView.text",
        "extension:scalive.FormFieldView.textarea",
        "extension:scalive.FormFieldView.validationAttributes",
        "extension:scalive.FormRowView.presence"
      )
      val forms      = symbols.filter(symbol => required(symbol.qualifiedName))
      val extensions = symbols.filter(symbol => requiredExtensions(symbol.id))
      val curated    = config.curatedSummaries.keySet
      def deliberatelyCurated(symbol: ApiSymbol): Boolean =
        val expectedSignatures =
          if symbol.id == "def:scalive.HttpFormDecoder.urlEncoded" then 2 else 1
        curated(symbol.id) && symbol.signatures.size == expectedSignatures
      val undocumented = (forms ++ extensions).distinct.filterNot(symbol =>
        symbol.signatures.forall(_.documentation.nonEmpty) ||
          deliberatelyCurated(symbol)
      )

      assertTrue(
        result.isRight,
        forms.map(_.qualifiedName).toSet == required,
        extensions.map(_.id).toSet == requiredExtensions,
        undocumented.map(_.qualifiedName).isEmpty
      )
    },
    test("exposes parameterless and parameterized enum cases") {
      val parameterless = symbols.find(
        _.qualifiedName == "scalive.testing.TastyQueryEnum.Parameterless"
      )
      val parameterized = symbols.find(
        _.qualifiedName == "scalive.testing.TastyQueryEnum.Parameterized"
      )
      val existingParameterized = symbols.find(
        _.qualifiedName == "scalive.LiveConnectedTurnFailure.Redirect"
      )
      val connectedRendered = symbols.find(
        _.qualifiedName == "scalive.testing.ConnectedAction.Rendered"
      )
      val connectedNavigation = symbols.find(
        _.qualifiedName == "scalive.testing.ConnectedAction.LiveNavigation"
      )
      val joinUnauthorized = symbols.find(
        _.qualifiedName == "scalive.testing.ConnectedJoinFailure.Unauthorized"
      )
      val joinRedirect = symbols.find(
        _.qualifiedName == "scalive.testing.ConnectedJoinFailure.Redirect"
      )
      val syntheticEnumMembers = Set("ordinal", "values", "valueOf", "fromOrdinal")
      val fixtureSynthetics = symbols.filter(symbol =>
        symbol.qualifiedName.startsWith("scalive.testing.TastyQueryEnum.") &&
          syntheticEnumMembers(symbol.name)
      )
      val emittedIds = symbols.map(_.id).toSet

      assertTrue(
        parameterless.exists(symbol =>
          symbol.ownerId.contains("enum:scalive.testing.TastyQueryEnum") &&
            symbol.kind == ApiSymbolKind.Val &&
            symbol.route == "/api/scalive/testing/tasty-query-enum" &&
            symbol.fragment.nonEmpty &&
            symbol.signatures.exists(_.signature ==
              "val Parameterless: testing.TastyQueryEnum"
            )
        ),
        parameterized.exists(symbol =>
          symbol.ownerId.contains("enum:scalive.testing.TastyQueryEnum") &&
            symbol.kind == ApiSymbolKind.Enum &&
            symbol.route == "/api/scalive/testing/tasty-query-enum/parameterized" &&
            symbol.fragment.isEmpty &&
            symbol.signatures.exists(_.signature ==
              "enum Parameterized(value: String) extends testing.TastyQueryEnum"
            )
        ),
        existingParameterized.exists(
          _.ownerId.contains("enum:scalive.LiveConnectedTurnFailure")
        ),
        connectedRendered.exists(symbol =>
          symbol.ownerId.contains("enum:scalive.testing.ConnectedAction") &&
            symbol.kind == ApiSymbolKind.Val &&
            symbol.route == "/api/scalive/testing/connected-action"
        ),
        connectedNavigation.exists(symbol =>
          symbol.ownerId.contains("enum:scalive.testing.ConnectedAction") &&
            symbol.kind == ApiSymbolKind.Enum &&
            symbol.route == "/api/scalive/testing/connected-action/live-navigation"
        ),
        joinUnauthorized.exists(symbol =>
          symbol.ownerId.contains("enum:scalive.testing.ConnectedJoinFailure") &&
            symbol.kind == ApiSymbolKind.Val &&
            symbol.route == "/api/scalive/testing/connected-join-failure"
        ),
        joinRedirect.exists(symbol =>
          symbol.ownerId.contains("enum:scalive.testing.ConnectedJoinFailure") &&
            symbol.kind == ApiSymbolKind.Enum &&
            symbol.route == "/api/scalive/testing/connected-join-failure/redirect"
        ),
        fixtureSynthetics.isEmpty,
        symbols.forall(_.ownerId.forall(emittedIds))
      )
    },
    test("emits valid IDs, routes, sources, and highlighted signatures") {
      val div = symbols.find(_.qualifiedName == "scalive.div")
      val liveView = symbols.find(_.qualifiedName == "scalive.LiveView")
      val liveViewTrait = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.LiveView" && symbol.kind == ApiSymbolKind.Trait
      )
      val liveViewObject = symbols.find(symbol =>
        symbol.qualifiedName == "scalive.LiveView" && symbol.kind == ApiSymbolKind.Object
      )
      val liveUpload    = symbols.find(_.qualifiedName == "scalive.LiveUpload")
      val handleMessage = symbols.find(_.qualifiedName == "scalive.LiveView.handleMessage")

      assertTrue(
        div.exists(_.summary.nonEmpty),
        div.flatMap(_.signatures.headOption).exists(signature =>
          result.toOption.exists(_.metadata.sourceLink(signature.source).label ==
            s"Generated from Scala DOM Types ${apiSettings.domTypesVersion}")
        ),
        liveView.flatMap(_.signatures.headOption).exists(signature =>
          result.toOption.exists(_.metadata.sourceLink(signature.source).url.contains(
            "/blob/0123456789abcdef0123456789abcdef01234567/scalive/api/src/scalive/"
          ))
        ),
        liveUpload.exists(symbol => symbol.route == "/api/scalive" && symbol.fragment.nonEmpty),
        liveViewTrait.exists(_.route == "/api/scalive/live-view"),
        liveViewObject.exists(_.route == "/api/scalive/live-view/companion"),
        handleMessage.exists(_.route == "/api/scalive/live-view"),
        symbolIdsAreUnique(symbols),
        signatureIdsAreUnique(symbols),
        signaturesAreHighlighted(symbols),
        signatureTokensMatchText(symbols),
        routesAreValid(symbols),
        repositorySourcesAreValid(symbols)
      )
    },
    test("generates the public API deterministically") {
      assertTrue(result == ApiReferencePipeline.generate(config))
    }
  ) @@ TestAspect.sequential
end ApiReferencePipelineSpec
