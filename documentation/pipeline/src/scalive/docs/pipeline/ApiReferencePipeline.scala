package scalive.docs.pipeline

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import java.security.MessageDigest
import scala.collection.mutable

import tastyquery.Contexts.Context
import tastyquery.Modifiers.TermSymbolKind
import tastyquery.Symbols.*
import tastyquery.Symbols.TypeMemberDefinition
import tastyquery.Trees.Apply
import tastyquery.Trees.DefDef
import tastyquery.Trees.Select
import tastyquery.Trees.TermTree
import tastyquery.Trees.TypeApply
import tastyquery.Types.AppliedType
import tastyquery.Types.ByNameType
import tastyquery.Types.TermRef
import tastyquery.Types.Type
import tastyquery.Types.TypeAlias
import tastyquery.Types.TypeLambda
import tastyquery.Types.TypeOrMethodic
import tastyquery.Types.TypeRef
import tastyquery.jdk.ClasspathLoaders

import scalive.docs.model.*

final case class ApiReferenceConfig(
  repositoryRoot: Path,
  targetRoots: Seq[Path],
  dependencyClasspath: Seq[Path],
  metadata: ApiReferenceMetadata)

final case class ApiReferenceError(messages: Vector[String]):
  def message: String = messages.mkString("\n")

object ApiReferencePipeline:
  private val IncludedPackages    = Vector("scalive", "scalive.codecs", "scalive.testing")
  private val IncludedPackageSet  = IncludedPackages.toSet
  private val DomDefinitionPrefix = "scalive.defs."
  private val UniversalMethods    = Set("equals", "hashCode", "toString")

  def generate(config: ApiReferenceConfig): Either[ApiReferenceError, ApiReference] =
    try
      val javaBase  = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("modules", "java.base")
      val classpath = (
        config.targetRoots.toVector ++ Vector(javaBase) ++ config.dependencyClasspath
      ).distinct.toList
      given Context = Context.initialize(ClasspathLoaders.read(classpath))

      TastyDocumentation
        .inspect(config.targetRoots, config.dependencyClasspath)
        .left.map(message => ApiReferenceError(Vector(message)))
        .flatMap { documentation =>
          val extractor = Extractor(config, documentation)
          extractor.extract()
        }
    catch
      case error: Exception =>
        Left(ApiReferenceError(Vector(s"Unable to extract the public API: ${error.getMessage}")))

  final private case class RawSignature(
    groupId: String,
    ownerId: Option[String],
    name: String,
    qualifiedName: String,
    kind: ApiSymbolKind,
    signature: String,
    origin: ApiOrigin,
    source: ApiSource,
    comment: Option[String],
    generatedSummary: Option[String],
    pageOwner: Boolean)

  final private class Extractor(
    config: ApiReferenceConfig,
    documentation: Vector[TastyDocumentationRecord]
  )(using Context):
    private val raw                     = mutable.ArrayBuffer.empty[RawSignature]
    private val errors                  = mutable.ArrayBuffer.empty[String]
    private val visitedOwners           = mutable.HashSet.empty[(ClassSymbol, String, ApiExposure)]
    private val documentationByPosition = documentation.groupBy(_.position)
    private val sourceText              = mutable.HashMap.empty[Path, String]

    def extract(): Either[ApiReferenceError, ApiReference] =
      IncludedPackages.foreach(extractPackage)
      if errors.nonEmpty then Left(ApiReferenceError(errors.toVector.distinct.sorted))
      else
        val symbols = materialize(raw.toVector)
        if errors.nonEmpty then Left(ApiReferenceError(errors.toVector.distinct.sorted))
        else
          val routeCollisions =
            symbols.filter(_.fragment.isEmpty).groupBy(_.route).toVector.collect {
              case (route, owners) if owners.map(_.qualifiedName).distinct.sizeIs > 1 =>
                s"API route collision '$route': ${owners.map(_.qualifiedName).distinct.sorted.mkString(", ")}."
            }
          if routeCollisions.nonEmpty then Left(ApiReferenceError(routeCollisions.sorted))
          else Right(ApiReference(config.metadata, symbols))

    private def extractPackage(packageName: String): Unit =
      val packageSymbol = summon[Context].findPackage(packageName)
      val packageId     = symbolId(ApiSymbolKind.Package, packageName)
      val declarations  = packageSymbol.declarations.collect { case symbol: TermOrTypeSymbol =>
        symbol
      }
      val packageObjects = declarations.collect {
        case owner: ClassSymbol if isPackageObject(owner) => owner
      }
      val signature = packageObjects
        .find(_.name.toString == "package$")
        .map(packageObjectSignature(packageName, _))
        .getOrElse(s"package $packageName")
      raw += RawSignature(
        groupId = packageId,
        ownerId = parentPackage(packageName).map(symbolId(ApiSymbolKind.Package, _)),
        name = packageName.split('.').last,
        qualifiedName = packageName,
        kind = ApiSymbolKind.Package,
        signature = signature,
        origin = ApiOrigin(packageName, ApiExposure.Direct),
        source = ApiSource.Repository(SourceRegion(packagePath(packageName), 1, 1)),
        comment = None,
        generatedSummary = Some(s"Public APIs in the `$packageName` package."),
        pageOwner = true
      )

      declarations
        .collect {
          case owner: ClassSymbol if !isPackageObject(owner) && eligible(owner) => owner
        }.foreach { owner =>
          addOwner(owner, exposedName(owner, packageName), Some(packageId), ApiExposure.Direct)
        }

      packageObjects.foreach { owner =>
        owner.declarations.foreach {
          case nested: ClassSymbol if eligible(nested) =>
            addOwner(nested, exposedName(nested, packageName), Some(packageId), exposure(nested))
          case term: TermSymbol if eligibleTerm(term) =>
            addMember(term, s"$packageName.${sourceName(term)}", packageId, exposure(term))
          case member: TypeMemberSymbol if eligible(member) =>
            addMember(member, s"$packageName.${sourceName(member)}", packageId, exposure(member))
          case _ => ()
        }

        if packageName == "scalive" && owner.name.toString == "package$" then
          owner.linearization
            .drop(1)
            .filter(parent => originName(parent).startsWith(DomDefinitionPrefix))
            .foreach { parent =>
              parent.declarations.foreach {
                case nested: ClassSymbol if eligible(nested) =>
                  addOwner(
                    nested,
                    exposedName(nested, packageName),
                    Some(packageId),
                    ApiExposure.Inherited
                  )
                case term: TermSymbol if eligibleTerm(term) =>
                  addTerm(term, packageName, packageId, ApiExposure.Inherited, pageOwner = false)
                case member: TypeMemberSymbol if eligible(member) =>
                  addType(member, packageName, packageId, ApiExposure.Inherited, pageOwner = false)
                case _ => ()
              }
            }
      }
    end extractPackage

    private def addOwner(
      owner: ClassSymbol,
      qualifiedName: String,
      parentId: Option[String],
      ownerExposure: ApiExposure
    ): Unit =
      val key = (owner, qualifiedName, ownerExposure)
      if visitedOwners.add(key) && !emptyObject(owner) then
        val kind = classKind(owner)
        val id   = symbolId(kind, qualifiedName)
        addRaw(
          RawSignature(
            id,
            parentId,
            sourceName(owner),
            qualifiedName,
            kind,
            classSignature(owner, sourceName(owner)),
            ApiOrigin(originName(owner), ownerExposure),
            sourceFor(owner),
            commentFor(owner),
            None,
            pageOwner = true
          )
        )

        owner.declarations.foreach {
          case nested: ClassSymbol if eligible(nested) =>
            val nestedOwnerId =
              if owner.isModuleClass && owner.companionClass.exists(enumOwner =>
                  enumOwner.isEnum && enumOwner.sealedChildren.contains(nested)
                )
              then symbolId(ApiSymbolKind.Enum, qualifiedName)
              else id
            addOwner(
              nested,
              s"$qualifiedName.${sourceName(nested)}",
              Some(nestedOwnerId),
              ownerExposure
            )
          case term: TermSymbol if eligibleTerm(term) =>
            addMember(term, s"$qualifiedName.${sourceName(term)}", id, ownerExposure)
          case member: TypeMemberSymbol if eligible(member) =>
            addMember(member, s"$qualifiedName.${sourceName(member)}", id, ownerExposure)
          case _ => ()
        }

        if owner.isEnum then
          owner.sealedChildren.foreach {
            case enumCase: ClassSymbol if enumCase.isPublic =>
              addOwner(
                enumCase,
                s"$qualifiedName.${sourceName(enumCase)}",
                Some(id),
                ownerExposure
              )
            case enumCase: TermSymbol if enumCase.isPublic && enumCase.isEnumCase =>
              addTerm(
                enumCase,
                s"$qualifiedName.${sourceName(enumCase)}",
                id,
                ownerExposure,
                pageOwner = false
              )
            case _ => ()
          }
      end if
    end addOwner

    /** An object without public members, such as a companion that holds only private factories,
      * adds nothing to the reference. An object that extends a type stays, since it is a value, as
      * in `LiveUploadAccept.Any`.
      */
    private def emptyObject(owner: ClassSymbol): Boolean =
      owner.isModuleClass &&
        owner.parents.map(_.showBasic).forall(syntheticParent(owner, _)) &&
        !owner.declarations.exists {
          case nested: ClassSymbol      => eligible(nested)
          case term: TermSymbol         => eligibleTerm(term)
          case member: TypeMemberSymbol => eligible(member)
          case _                        => false
        }

    /** Adds a term or type member. An `export` forwarder of a definition outside the documented
      * packages is documented as that definition, under the exported name.
      */
    private def addMember(
      member: TermSymbol | TypeMemberSymbol,
      qualifiedName: String,
      ownerId: String,
      memberExposure: ApiExposure
    ): Unit =
      exportedDefinition(member) match
        case Some(target: ClassSymbol) =>
          if eligible(target) then
            addOwner(target, qualifiedName, Some(ownerId), ApiExposure.Exported)
        case Some(target: TermSymbol) =>
          addTerm(target, qualifiedName, ownerId, ApiExposure.Exported, pageOwner = false)
        case Some(target: TypeMemberSymbol) =>
          addType(target, qualifiedName, ownerId, ApiExposure.Exported, pageOwner = false)
        case _ =>
          member match
            case term: TermSymbol =>
              addTerm(term, qualifiedName, ownerId, memberExposure, pageOwner = false)
            case member: TypeMemberSymbol =>
              addType(member, qualifiedName, ownerId, memberExposure, pageOwner = false)

    /** The definition outside the documented packages that an `export` forwarder stands for.
      *
      * Exported classes, objects, and opaque types resolve through chained exports, such as
      * `scalive.LiveUploadDef` to `scalive.upload.api.LiveUploadDef` to the class
      * `scalive.upload.LiveUploadDef`. An exported method resolves to the method it calls. TASTy
      * marks only term forwarders as exports, so a type alias forwards when it keeps its target's
      * name. An exported type is an abstract member bounded by its target.
      */
    private def exportedDefinition(symbol: TermOrTypeSymbol): Option[TermOrTypeSymbol] =
      val target = symbol match
        case member: TypeMemberSymbol =>
          val alias = member.typeDef match
            case TypeMemberDefinition.TypeAlias(alias)               => Some(alias)
            case TypeMemberDefinition.AbstractType(TypeAlias(alias)) => Some(alias)
            case _                                                   => None
          alias
            .flatMap(referencedType)
            .filter(target => sourceName(target) == sourceName(member))
        case term: TermSymbol if term.isExport => forwardedTerm(term)
        case _                                 => None
      target.flatMap {
        case alias: TypeMemberSymbol if !alias.isOpaqueTypeAlias => exportedDefinition(alias)
        case term: TermSymbol if term.isExport                   => exportedDefinition(term)
        case definition                                          =>
          val packageName = packageOf(definition)
          Option.when(packageName.startsWith("scalive.") && !IncludedPackageSet(packageName))(
            definition
          )
      }

    private def referencedType(tpe: Type): Option[TermOrTypeSymbol] =
      tpe match
        case lambda: TypeLambda   => referencedType(lambda.resultType)
        case applied: AppliedType => referencedType(applied.tycon)
        case ref: TypeRef => ref.optSymbol.collect { case symbol: TermOrTypeSymbol => symbol }
        case _            => None

    private def forwardedTerm(term: TermSymbol): Option[TermOrTypeSymbol] =
      def module(tpe: TypeOrMethodic): Option[TermOrTypeSymbol] =
        tpe match
          case byName: ByNameType => module(byName.resultType)
          case ref: TermRef       =>
            ref.optSymbol.flatMap(value =>
              if value.isModuleVal then value.moduleClass
              else Option.when(value.isExport)(value)
            )
          case ref: TypeRef =>
            ref.optSymbol.collect { case owner: ClassSymbol if owner.isModuleClass => owner }
          case _ => None
      def called(tree: TermTree): Option[TermSymbol] =
        tree match
          case Apply(function, _)     => called(function)
          case TypeApply(function, _) => called(function)
          case select: Select => Some(select.symbol).collect { case value: TermSymbol => value }
          case _              => None
      module(term.declaredType).orElse(
        term.tree.collect { case definition: DefDef => definition }.flatMap(_.rhs).flatMap(called)
      )

    private def packageOf(symbol: Symbol): String =
      symbol.owner match
        case pkg: PackageSymbol => pkg.fullName.toString
        case owner              => packageOf(owner)

    private def addTerm(
      term: TermSymbol,
      qualifiedName: String,
      ownerId: String,
      termExposure: ApiExposure,
      pageOwner: Boolean
    ): Unit =
      val kind                = termKind(term)
      val actualQualifiedName =
        if qualifiedName.endsWith(s".${sourceName(term)}") then qualifiedName
        else s"$qualifiedName.${sourceName(term)}"
      val id = symbolId(kind, actualQualifiedName)
      addRaw(
        RawSignature(
          id,
          Some(ownerId),
          sourceName(term),
          actualQualifiedName,
          kind,
          termSignature(term),
          ApiOrigin(originName(term), termExposure),
          sourceFor(term),
          commentFor(term).orElse(parameterComment(term)),
          None,
          pageOwner
        )
      )

    /** A class's `@param` text documents the field that parameter defines. */
    private def parameterComment(term: TermSymbol): Option[String] =
      term.owner match
        case owner: ClassSymbol if term.isParamAccessor =>
          commentFor(owner).flatMap(ScaladocParser.parameter(_, sourceName(term)))
        case _ => None

    private def addType(
      member: TypeMemberSymbol,
      qualifiedName: String,
      ownerId: String,
      memberExposure: ApiExposure,
      pageOwner: Boolean
    ): Unit =
      val kind =
        if member.isOpaqueTypeAlias then ApiSymbolKind.OpaqueType else ApiSymbolKind.TypeAlias
      val actualQualifiedName =
        if qualifiedName.endsWith(s".${sourceName(member)}") then qualifiedName
        else s"$qualifiedName.${sourceName(member)}"
      val id = symbolId(kind, actualQualifiedName)
      addRaw(
        RawSignature(
          id,
          Some(ownerId),
          sourceName(member),
          actualQualifiedName,
          kind,
          typeSignature(member),
          ApiOrigin(originName(member), memberExposure),
          sourceFor(member),
          commentFor(member),
          None,
          pageOwner
        )
      )

    private def addRaw(value: RawSignature): Unit =
      value.source match
        case ApiSource.Repository(region) if region.path.startsWith("out/") =>
          errors += s"API symbol '${value.qualifiedName}' exposed a generated output path."
        case _ => raw += value

    private def materialize(values: Vector[RawSignature]): Vector[ApiSymbol] =
      val grouped      = values.groupBy(_.groupId).toVector.sortBy(_._1)
      val ownerEntries = grouped.collect {
        case (id, entries) if entries.exists(_.pageOwner) =>
          id -> entries.find(_.pageOwner).get
      }
      val qualifiedNamesByRoute = ownerEntries.map(_._2.qualifiedName).distinct.groupBy(routeFor)
      val presentationRoutes = qualifiedNamesByRoute.toVector.flatMap { case (baseRoute, names) =>
        names.map { name =>
          val route = if names.sizeIs > 1 then s"$baseRoute-${digest(name).take(8)}" else baseRoute
          name -> route
        }
      }.toMap
      val companionNames = ownerEntries
        .groupBy(_._2.qualifiedName).collect {
          case (name, entries)
              if entries.exists(_._2.kind == ApiSymbolKind.Object) &&
                entries.exists(_._2.kind != ApiSymbolKind.Object) =>
            name
        }.toSet
      val ownerRoutes = ownerEntries.map { case (id, entry) =>
        val primaryRoute = presentationRoutes(entry.qualifiedName)
        val route        =
          if entry.kind == ApiSymbolKind.Object && companionNames(entry.qualifiedName) then
            s"$primaryRoute/companion"
          else primaryRoute
        id -> route
      }.toMap

      val groups = grouped.map { case (id, entries) =>
        val first = entries.find(_.pageOwner).getOrElse(entries.head)
        val route =
          if first.pageOwner then ownerRoutes(id)
          else first.ownerId.flatMap(ownerRoutes.get).getOrElse(routeFor(first.qualifiedName))
        val fragment = Option.unless(first.pageOwner)(s"${slug(first.name)}-${digest(id).take(8)}")
        (id, entries, first, route, fragment)
      }
      val linkTargets = groups
        .flatMap { case (_, entries, _, route, fragment) =>
          entries.map(_.qualifiedName -> LinkTarget.Internal(route, fragment))
        }.groupMap(_._1)(_._2).view.mapValues(_.distinct.sortBy(_.toString).head).toMap

      groups.map { case (id, entries, first, route, fragment) =>
        val signatures = entries
          .groupBy(_.signature)
          .values
          .map(_.minBy(entry => (entry.origin.qualifiedName, entry.source.toString)))
          .toVector
          .sortBy(_.signature)
          .map { entry =>
            val signatureId   = s"$id:${digest(entry.signature)}"
            val signature     = ApiSignatureFormatter.format(entry.signature)
            val documentation = entry.comment.flatMap { comment =>
              ScaladocParser
                .parse(
                  comment,
                  signatureId.replace(':', '-'),
                  resolveSymbolLink(entry.qualifiedName, _, linkTargets)
                ) match
                case Left(messages) =>
                  errors ++= messages.map(message => s"${entry.qualifiedName}: $message")
                  None
                case Right(value) => Option.unless(value.body.isEmpty && value.tags.isEmpty)(value)
            }
            ApiSignature(
              id = signatureId,
              signature = signature,
              tokens = CodeHighlighter.highlight(Some("scala"), signature),
              origin = entry.origin,
              source = entry.source,
              documentation = documentation
            )
          }
        val generatedSummaries = (
          entries.flatMap(_.generatedSummary) ++
            signatures.flatMap(_.documentation.flatMap(ScaladocParser.summary))
        ).distinct.sorted
        // Overloads share their first paragraph; ApiSnapshot.validateDocumentation rejects the rest.
        val summary = Option
          .when(generatedSummaries.size == 1)(generatedSummaries.head)
          .getOrElse("")
        ApiSymbol(
          id = id,
          ownerId = first.ownerId,
          name = first.name,
          qualifiedName = first.qualifiedName,
          kind = first.kind,
          summary = summary,
          signatures = signatures,
          route = route,
          fragment = fragment
        )
      }
    end materialize

    private def eligible(symbol: TermOrTypeSymbol): Boolean =
      val record          = documentationRecord(symbol)
      val companionPublic = symbol match
        case owner: ClassSymbol if owner.isModuleClass =>
          owner.companionClass.forall(_.isPublic)
        case _ => true
      symbol.isPublic && companionPublic && (!symbol.isSynthetic || record.exists(_.exported))

    private def eligibleTerm(term: TermSymbol): Boolean =
      val name = term.name.toString
      eligible(term) &&
      !term.isModuleVal &&
      !term.isSetter &&
      name != "<init>" &&
      // Compiler-generated names, such as default arguments and `derives` instances.
      !name.contains('$') &&
      // An undocumented override of a universal method adds nothing to the reference.
      !(UniversalMethods(name) && commentFor(term).isEmpty)

    private def exposure(symbol: TermOrTypeSymbol): ApiExposure =
      val exported = symbol match
        case term: TermSymbol => term.isExport || documentationRecord(term).exists(_.exported)
        case _                => documentationRecord(symbol).exists(_.exported)
      if exported then ApiExposure.Exported else ApiExposure.Direct

    private def documentationRecord(
      symbol: TermOrTypeSymbol
    ): Option[TastyDocumentationRecord] =
      position(symbol).flatMap { position =>
        documentationByPosition.get(position).flatMap { records =>
          // TASTy can record one definition under several symbols, such as an enum class and its
          // companion module. They share the position, name, and comment, so any one documents it.
          val named     = records.filter(record => sourceName(record.name) == sourceName(symbol))
          val unanimous =
            named.map(record => (record.comment, record.exported)).distinct.sizeIs == 1
          if unanimous then named.headOption
          else if records.sizeIs == 1 then records.headOption
          else None
        }
      }

    private def commentFor(symbol: TermOrTypeSymbol): Option[String] =
      documentationRecord(symbol).flatMap(_.comment)

    private def resolveSymbolLink(
      currentQualifiedName: String,
      target: String,
      targets: Map[String, LinkTarget]
    ): Option[LinkTarget] =
      val scopes = Iterator
        .iterate(Option(currentQualifiedName))(
          _.flatMap(value =>
            value.lastIndexOf('.') match
              case -1    => None
              case index => Some(value.take(index))
          )
        )
        .takeWhile(_.nonEmpty).flatten.toVector
      val candidates = (
        scopes.map(scope => s"$scope.$target") ++
          Vector(target) ++ Option
            .unless(target.startsWith("scalive."))(s"scalive.$target").toVector
      ).distinct
      candidates.collectFirst(Function.unlift(targets.get))

    private def sourceFor(symbol: TermOrTypeSymbol): ApiSource =
      symbol.tree.map(_.pos).filterNot(_.isUnknown) match
        case Some(position) if isGenerated(position.sourceFile.path) => ApiSource.GeneratedDom
        case Some(position)                                          =>
          val path = position.sourceFile.path
          repositoryPath(path) match
            case Some(path) =>
              val startLine =
                if position.hasLineColumnInformation then position.startLine + 1
                else line(position.sourceFile.path, position.startOffset)
              val endLine =
                if position.hasLineColumnInformation then position.endLine + 1
                else line(position.sourceFile.path, position.endOffset)
              ApiSource.Repository(
                SourceRegion(path, startLine, endLine)
              )
            case None =>
              errors += s"API symbol '${originName(symbol)}' has a source outside the repository: '$path'."
              ApiSource.Repository(SourceRegion("<unknown>", 1, 1))
        case None =>
          errors += s"API symbol '${originName(symbol)}' has no source position."
          ApiSource.Repository(SourceRegion("<unknown>", 1, 1))

    private def position(symbol: TermOrTypeSymbol): Option[TastyPosition] =
      symbol.tree.map(_.pos).filterNot(_.isUnknown).map { position =>
        TastyPosition(
          position.sourceFile.path.replace('\\', '/'),
          position.startOffset,
          position.pointOffset,
          position.endOffset
        )
      }

    private def line(path: String, offset: Int): Int =
      try
        val source   = Path.of(path)
        val resolved =
          if source.isAbsolute then source
          else config.repositoryRoot.toAbsolutePath.normalize().resolve(source)
        val text = sourceText.getOrElseUpdate(resolved, java.nio.file.Files.readString(resolved))
        text.take(math.min(math.max(offset, 0), text.length)).count(_ == '\n') + 1
      catch case _: Exception => 1

    private def repositoryPath(path: String): Option[String] =
      try
        val repository = config.repositoryRoot.toAbsolutePath.normalize()
        val source     = Path.of(path)
        val absolute   =
          if source.isAbsolute then source.normalize() else repository.resolve(source).normalize()
        Option.when(absolute.startsWith(repository))(
          repository.relativize(absolute).toString.replace('\\', '/')
        )
      catch case _: Exception => None

    private def isGenerated(path: String): Boolean =
      path.replace('\\', '/').contains("generatedSources.dest/")

    private def classKind(owner: ClassSymbol): ApiSymbolKind =
      if owner.isModuleClass then ApiSymbolKind.Object
      else if owner.isEnum then ApiSymbolKind.Enum
      else if owner.isTrait then ApiSymbolKind.Trait
      else ApiSymbolKind.Class

    private def termKind(term: TermSymbol): ApiSymbolKind =
      if term.isExtensionMethod then ApiSymbolKind.Extension
      else if term.isGivenOrUsing then ApiSymbolKind.Given
      else
        term.kind match
          case TermSymbolKind.Def     => ApiSymbolKind.Def
          case TermSymbolKind.Var     => ApiSymbolKind.Var
          case TermSymbolKind.LazyVal => ApiSymbolKind.LazyVal
          case TermSymbolKind.Val     => ApiSymbolKind.Val
          case TermSymbolKind.Module  => ApiSymbolKind.Object

    private def classSignature(owner: ClassSymbol, name: String): String =
      val keyword = classKind(owner) match
        case ApiSymbolKind.Object => "object"
        case ApiSymbolKind.Enum   => "enum"
        case ApiSymbolKind.Trait  => "trait"
        case _                    => "class"
      val parameters = owner.typeParams
        .map { parameter =>
          val variance = parameter.declaredVariance.toString match
            case "Covariant"     => "+"
            case "Contravariant" => "-"
            case _               => ""
          val bounds         = parameter.declaredBounds.showBasic.trim
          val renderedBounds = if bounds.isEmpty then "" else s" $bounds"
          s"$variance${parameter.name}$renderedBounds"
        }.mkString("[", ", ", "]")
      val typeParameters = if owner.typeParams.isEmpty then "" else parameters
      val constructor    =
        if owner.isModuleClass || owner.isTrait then ""
        else
          owner.tree
            .map(_.rhs.constr.symbol).filter(_.isPublic)
            .map(value =>
              ApiSignatureFormatter.formatConstructor(
                value.declaredType.showBasic,
                defaultParameters(value)
              )
            )
            .getOrElse("")
      val renderedConstructor =
        if owner.isEnum && constructor == "()" then "" else constructor
      val parents = owner.parents
        .map(_.showBasic)
        .filterNot(parent => syntheticParent(owner, parent))
        .distinct
      val parentClause = if parents.isEmpty then "" else parents.mkString(" extends ", " with ", "")
      normalizeSignature(s"$keyword $name$typeParameters$renderedConstructor$parentClause")
    end classSignature

    private def syntheticParent(owner: ClassSymbol, parent: String): Boolean =
      parent == "scala.Object" ||
        parent == "java.lang.Object" ||
        owner.isEnum && parent == "scala.reflect.Enum" ||
        owner.isCaseClass && (parent == "scala.Product" || parent == "scala.Serializable") ||
        classKind(owner) == ApiSymbolKind.Object && parent.contains("Mirror")

    private def packageObjectSignature(packageName: String, owner: ClassSymbol): String =
      val parents = owner.parents
        .map(_.showBasic)
        .filterNot(parent => parent == "scala.Object" || parent == "java.lang.Object")
        .map { parent =>
          val typeArguments = parent.indexOf('[')
          val end           = if typeArguments < 0 then parent.length else typeArguments
          val prefix        = parent.take(end)
          val name          = prefix.substring(prefix.lastIndexOf('.') + 1)
          name + parent.drop(end)
        }.distinct
      val parentClause = if parents.isEmpty then "" else parents.mkString(" extends ", " with ", "")
      normalizeSignature(s"package object $packageName$parentClause")

    private def termSignature(term: TermSymbol): String =
      val keyword = termKind(term) match
        case ApiSymbolKind.Extension => "extension def"
        case ApiSymbolKind.Given     => "given"
        case ApiSymbolKind.Var       => "var"
        case ApiSymbolKind.LazyVal   => "lazy val"
        case ApiSymbolKind.Val       => "val"
        case _                       => "def"
      normalizeSignature(
        s"$keyword ${sourceName(term)}: ${ApiSignatureFormatter.markDefaultParameters(term.declaredType.showBasic, defaultParameters(term))}"
      )

    private def defaultParameters(term: TermSymbol): Set[String] =
      term.paramSymss.flatMap {
        case Left(parameters) => parameters.filter(_.isParamWithDefault).map(_.name.toString)
        case Right(_)         => Nil
      }.toSet

    private def typeSignature(member: TypeMemberSymbol): String =
      val rendered = member.typeDef match
        case TypeMemberDefinition.TypeAlias(alias) =>
          s"type ${sourceName(member)} = ${alias.showBasic}"
        case TypeMemberDefinition.AbstractType(bounds) =>
          s"type ${sourceName(member)} ${bounds.showBasic}"
        case TypeMemberDefinition.OpaqueTypeAlias(bounds, alias) =>
          s"opaque type ${sourceName(member)} ${bounds.showBasic} = ${alias.showBasic}"
      normalizeSignature(rendered)

    private def normalizeSignature(value: String): String =
      value
        .replace("scalive.package$.", "scalive.")
        // Applications name the `streams` and `upload` types through their exports in `scalive`.
        .replaceAll("(?<![\\w.])(scalive\\.)?(?:streams|upload)(?:\\.api)?\\.(?=[A-Z])", "$1")
        .replaceAll("\\s+", " ")
        .trim

    private def originName(symbol: TermOrTypeSymbol): String =
      val owner = symbol.owner match
        case pkg: PackageSymbol                           => pkg.fullName.toString
        case value: ClassSymbol if isPackageObject(value) =>
          value.owner match
            case pkg: PackageSymbol      => pkg.fullName.toString
            case outer: TermOrTypeSymbol => originName(outer)
        case value: TermOrTypeSymbol => originName(value)
      val separator = if owner.isEmpty then "" else "."
      s"$owner$separator${sourceName(symbol)}"

    private def exposedName(symbol: TermOrTypeSymbol, owner: String): String =
      s"$owner.${sourceName(symbol)}"

    private def sourceName(symbol: TermOrTypeSymbol): String = sourceName(symbol.name.toString)

    private def sourceName(name: String): String =
      name.stripSuffix("$").stripSuffix("$package")

    private def isPackageObject(owner: ClassSymbol): Boolean =
      val name = owner.name.toString
      owner.isModuleClass && (name == "package$" || name.endsWith("$package$"))

    private def symbolId(kind: ApiSymbolKind, qualifiedName: String): String =
      s"${kindName(kind)}:$qualifiedName"

    private def kindName(kind: ApiSymbolKind): String = kind match
      case ApiSymbolKind.Package    => "package"
      case ApiSymbolKind.Class      => "class"
      case ApiSymbolKind.Trait      => "trait"
      case ApiSymbolKind.Object     => "object"
      case ApiSymbolKind.Enum       => "enum"
      case ApiSymbolKind.OpaqueType => "opaque-type"
      case ApiSymbolKind.TypeAlias  => "type-alias"
      case ApiSymbolKind.Def        => "def"
      case ApiSymbolKind.Extension  => "extension"
      case ApiSymbolKind.Val        => "val"
      case ApiSymbolKind.LazyVal    => "lazy-val"
      case ApiSymbolKind.Var        => "var"
      case ApiSymbolKind.Given      => "given"

    private def routeFor(qualifiedName: String): String =
      "/api/" + qualifiedName.split('.').map(slug).mkString("/")

    private def slug(value: String): String =
      value
        .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
        .replaceAll("[^A-Za-z0-9]+", "-")
        .stripPrefix("-")
        .stripSuffix("-")
        .toLowerCase

    private def digest(value: String): String =
      MessageDigest
        .getInstance("SHA-256")
        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .map(byte => f"${byte & 0xff}%02x")
        .mkString
        .take(16)

    private def packagePath(packageName: String): String =
      packageName match
        case "scalive"         => "scalive/api/src/scalive/Scalive.scala"
        case "scalive.codecs"  => "scalive/api/src/scalive/codecs/Encoder.scala"
        case "scalive.testing" => "scalive/testing/src/scalive/testing/DisconnectedRender.scala"

    private def parentPackage(packageName: String): Option[String] =
      packageName
        .split('.')
        .inits
        .drop(1)
        .map(_.mkString("."))
        .find(IncludedPackageSet)

  end Extractor
end ApiReferencePipeline
