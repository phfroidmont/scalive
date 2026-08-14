package scalive.docs.model

import zio.json.*

final case class DocumentationBundle(
  formatVersion: Int,
  navigation: Navigation,
  pages: Vector[Page],
  examples: Vector[ExampleDefinition],
  apiReference: ApiReference,
  searchEntries: Vector[SearchEntry])
    derives JsonCodec

object DocumentationBundle:
  val CurrentFormatVersion = 7

final case class Page(
  route: String,
  metadata: PageMetadata,
  source: PageSource,
  outline: PageOutline,
  content: Vector[Block])
    derives JsonCodec

final case class PageMetadata(
  title: String,
  description: String,
  order: Int,
  section: Section)
    derives JsonCodec

enum Section:
  case Home, Learn, Guides, Examples, Api, Project

object Section:
  given JsonCodec[Section] = JsonCodec[String].transformOrFail(
    {
      case "home"     => Right(Section.Home)
      case "learn"    => Right(Section.Learn)
      case "guides"   => Right(Section.Guides)
      case "examples" => Right(Section.Examples)
      case "api"      => Right(Section.Api)
      case "project"  => Right(Section.Project)
      case other      => Left(s"Unknown documentation section: $other")
    },
    {
      case Section.Home     => "home"
      case Section.Learn    => "learn"
      case Section.Guides   => "guides"
      case Section.Examples => "examples"
      case Section.Api      => "api"
      case Section.Project  => "project"
    }
  )

final case class Navigation(items: Vector[NavigationItem]) derives JsonCodec

final case class NavigationItem(
  title: String,
  route: String,
  section: Section,
  children: Vector[NavigationItem])
    derives JsonCodec

final case class PageOutline(items: Vector[OutlineItem]) derives JsonCodec

final case class OutlineItem(
  id: String,
  title: String,
  level: Int,
  children: Vector[OutlineItem])
    derives JsonCodec

final case class SourceLocation(path: String, line: Int) derives JsonCodec

@jsonDiscriminator("type")
sealed trait PageSource derives JsonCodec

object PageSource:
  @jsonHint("authored")
  final case class Authored(location: SourceLocation) extends PageSource

  @jsonHint("generatedApi")
  final case class GeneratedApi(symbolId: String) extends PageSource

final case class SourceRegion(
  path: String,
  startLine: Int,
  endLineInclusive: Int)
    derives JsonCodec

final case class ExampleSource(
  label: String,
  path: String,
  region: String,
  language: Option[String])
    derives JsonCodec

final case class ExampleDescriptor(
  id: String,
  title: String,
  description: String,
  topics: Vector[String],
  aliases: Vector[String],
  resetDescription: String,
  sources: Vector[ExampleSource])
    derives JsonCodec

final case class CompilationFailure(
  id: String,
  source: String,
  sourceTokens: Vector[CodeToken],
  diagnostic: String)
    derives JsonCodec

final case class ExampleSourceCode(
  label: String,
  region: SourceRegion,
  language: Option[String],
  text: String,
  tokens: Vector[CodeToken])
    derives JsonCodec

final case class ExampleDefinition(
  descriptor: ExampleDescriptor,
  sources: Vector[ExampleSourceCode],
  compilationFailures: Vector[CompilationFailure])
    derives JsonCodec

final case class CodeToken(text: String, styles: Vector[String]) derives JsonCodec

final case class ListItem(content: Vector[Block]) derives JsonCodec

final case class TableCell(content: Vector[Inline]) derives JsonCodec

final case class TableRow(cells: Vector[TableCell]) derives JsonCodec

@jsonDiscriminator("type")
sealed trait Block derives JsonCodec

object Block:
  @jsonHint("paragraph")
  final case class Paragraph(content: Vector[Inline]) extends Block

  @jsonHint("heading")
  final case class Heading(level: Int, id: String, content: Vector[Inline]) extends Block

  @jsonHint("code")
  final case class Code(
    language: Option[String],
    text: String,
    tokens: Vector[CodeToken],
    sourceRegion: Option[SourceRegion])
      extends Block

  @jsonHint("bulletList")
  final case class BulletList(items: Vector[ListItem]) extends Block

  @jsonHint("orderedList")
  final case class OrderedList(start: Int, items: Vector[ListItem]) extends Block

  @jsonHint("quote")
  final case class Quote(content: Vector[Block]) extends Block

  @jsonHint("table")
  final case class Table(header: Vector[TableCell], rows: Vector[TableRow]) extends Block

  @jsonHint("rule")
  case object Rule extends Block

  @jsonHint("image")
  final case class Image(source: String, alt: String, title: Option[String]) extends Block

  @jsonHint("callout")
  final case class Callout(
    kind: CalloutKind,
    title: Option[String],
    content: Vector[Block])
      extends Block

  @jsonHint("exampleRef")
  final case class ExampleRef(id: String) extends Block

  @jsonHint("labRef")
  final case class LabRef(id: String) extends Block

  @jsonHint("sourceCode")
  final case class SourceCode(
    region: SourceRegion,
    language: Option[String],
    text: String,
    tokens: Vector[CodeToken])
      extends Block

  @jsonHint("apiSymbolRef")
  final case class ApiSymbolRef(id: String) extends Block

  @jsonHint("compatibilityRef")
  final case class CompatibilityRef(id: String) extends Block
end Block

enum CalloutKind:
  case Info, Tip, Warning, Error

object CalloutKind:
  given JsonCodec[CalloutKind] = JsonCodec[String].transformOrFail(
    {
      case "info"    => Right(CalloutKind.Info)
      case "tip"     => Right(CalloutKind.Tip)
      case "warning" => Right(CalloutKind.Warning)
      case "error"   => Right(CalloutKind.Error)
      case other     => Left(s"Unknown callout kind: $other")
    },
    {
      case CalloutKind.Info    => "info"
      case CalloutKind.Tip     => "tip"
      case CalloutKind.Warning => "warning"
      case CalloutKind.Error   => "error"
    }
  )

@jsonDiscriminator("type")
sealed trait Inline derives JsonCodec

object Inline:
  @jsonHint("text")
  final case class Text(value: String) extends Inline

  @jsonHint("emphasis")
  final case class Emphasis(content: Vector[Inline]) extends Inline

  @jsonHint("strong")
  final case class Strong(content: Vector[Inline]) extends Inline

  @jsonHint("strike")
  final case class Strike(content: Vector[Inline]) extends Inline

  @jsonHint("code")
  final case class Code(value: String) extends Inline

  @jsonHint("link")
  final case class Link(
    content: Vector[Inline],
    target: LinkTarget,
    title: Option[String])
      extends Inline

  @jsonHint("apiSymbolRef")
  final case class ApiSymbolRef(id: String, label: String) extends Inline

  @jsonHint("lineBreak")
  case object LineBreak extends Inline

@jsonDiscriminator("type")
sealed trait LinkTarget derives JsonCodec

object LinkTarget:
  @jsonHint("internal")
  final case class Internal(route: String, fragment: Option[String]) extends LinkTarget

  @jsonHint("external")
  final case class External(url: String) extends LinkTarget

final case class ApiReference(
  metadata: ApiReferenceMetadata,
  symbols: Vector[ApiSymbol])
    derives JsonCodec

final case class ApiReferenceMetadata(
  repositoryUrl: String,
  revision: String,
  domTypesVersion: String,
  domGeneratorPath: String)
    derives JsonCodec:
  def sourceLink(source: ApiSource): ApiSourceLink =
    val repository = repositoryUrl.stripSuffix("/")
    source match
      case ApiSource.Repository(region) =>
        val lines =
          if region.startLine == region.endLineInclusive then s"#L${region.startLine}"
          else s"#L${region.startLine}-L${region.endLineInclusive}"
        ApiSourceLink(
          s"$repository/blob/$revision/${region.path}$lines",
          s"${region.path}:${region.startLine}-${region.endLineInclusive}"
        )
      case ApiSource.GeneratedDom =>
        ApiSourceLink(
          s"$repository/blob/$revision/$domGeneratorPath",
          s"Generated from Scala DOM Types $domTypesVersion"
        )

final case class ApiSourceLink(url: String, label: String) derives JsonCodec

final case class ApiSymbol(
  id: String,
  ownerId: Option[String],
  name: String,
  qualifiedName: String,
  kind: ApiSymbolKind,
  summary: String,
  signatures: Vector[ApiSignature],
  route: String,
  fragment: Option[String])
    derives JsonCodec

final case class ApiDocumentation(
  body: Vector[Block],
  tags: Vector[ApiDocumentationTag])
    derives JsonCodec

final case class ApiDocumentationTag(
  name: String,
  subject: Option[String],
  content: Vector[Block])
    derives JsonCodec

final case class ApiSignature(
  id: String,
  signature: String,
  tokens: Vector[CodeToken],
  origin: ApiOrigin,
  source: ApiSource,
  documentation: Option[ApiDocumentation])
    derives JsonCodec

final case class ApiOrigin(qualifiedName: String, exposure: ApiExposure) derives JsonCodec

@jsonDiscriminator("type")
sealed trait ApiSource derives JsonCodec

object ApiSource:
  @jsonHint("repository")
  final case class Repository(region: SourceRegion) extends ApiSource

  @jsonHint("generatedDom")
  case object GeneratedDom extends ApiSource

enum ApiSymbolKind:
  case Package, Class, Trait, Object, Enum, OpaqueType, TypeAlias
  case Def, Extension, Val, LazyVal, Var, Given

object ApiSymbolKind:
  given JsonCodec[ApiSymbolKind] = JsonCodec[String].transformOrFail(
    {
      case "package"    => Right(ApiSymbolKind.Package)
      case "class"      => Right(ApiSymbolKind.Class)
      case "trait"      => Right(ApiSymbolKind.Trait)
      case "object"     => Right(ApiSymbolKind.Object)
      case "enum"       => Right(ApiSymbolKind.Enum)
      case "opaqueType" => Right(ApiSymbolKind.OpaqueType)
      case "typeAlias"  => Right(ApiSymbolKind.TypeAlias)
      case "def"        => Right(ApiSymbolKind.Def)
      case "extension"  => Right(ApiSymbolKind.Extension)
      case "val"        => Right(ApiSymbolKind.Val)
      case "lazyVal"    => Right(ApiSymbolKind.LazyVal)
      case "var"        => Right(ApiSymbolKind.Var)
      case "given"      => Right(ApiSymbolKind.Given)
      case other        => Left(s"Unknown API symbol kind: $other")
    },
    {
      case ApiSymbolKind.Package    => "package"
      case ApiSymbolKind.Class      => "class"
      case ApiSymbolKind.Trait      => "trait"
      case ApiSymbolKind.Object     => "object"
      case ApiSymbolKind.Enum       => "enum"
      case ApiSymbolKind.OpaqueType => "opaqueType"
      case ApiSymbolKind.TypeAlias  => "typeAlias"
      case ApiSymbolKind.Def        => "def"
      case ApiSymbolKind.Extension  => "extension"
      case ApiSymbolKind.Val        => "val"
      case ApiSymbolKind.LazyVal    => "lazyVal"
      case ApiSymbolKind.Var        => "var"
      case ApiSymbolKind.Given      => "given"
    }
  )
end ApiSymbolKind

enum ApiMemberCategory(val id: String, val title: String):
  case Types          extends ApiMemberCategory("types", "Types")
  case CoreApi        extends ApiMemberCategory("core-api", "Core API")
  case Components     extends ApiMemberCategory("components", "Components")
  case Streams        extends ApiMemberCategory("streams", "Streams")
  case Uploads        extends ApiMemberCategory("uploads", "Uploads")
  case HtmlElements   extends ApiMemberCategory("html-elements", "HTML elements")
  case HtmlAttributes extends ApiMemberCategory("html-attributes", "HTML attributes")
  case DomHelpers     extends ApiMemberCategory("dom-helpers", "DOM helpers")
  case Extensions     extends ApiMemberCategory("extensions", "Extension methods")
  case Methods        extends ApiMemberCategory("methods", "Methods")
  case Values         extends ApiMemberCategory("values", "Values")
  case Givens         extends ApiMemberCategory("givens", "Givens")

object ApiMemberCategory:
  def group(members: Vector[ApiSymbol]): Vector[(ApiMemberCategory, Vector[ApiSymbol])] =
    val rootPackage = members.exists(_.ownerId.contains("package:scalive"))
    members
      .groupBy(categoryFor).toVector.sortBy { case (category, _) =>
        if rootPackage then rootPackageRank(category) else category.ordinal
      }
      .map { case (category, symbols) => category -> symbols }

  private def rootPackageRank(category: ApiMemberCategory): Int = category match
    case ApiMemberCategory.CoreApi        => 0
    case ApiMemberCategory.Components     => 1
    case ApiMemberCategory.Streams        => 2
    case ApiMemberCategory.Uploads        => 3
    case ApiMemberCategory.HtmlElements   => 4
    case ApiMemberCategory.HtmlAttributes => 5
    case ApiMemberCategory.DomHelpers     => 6
    case ApiMemberCategory.Types          => 7
    case _                                => category.ordinal + 8

  private def categoryFor(symbol: ApiSymbol): ApiMemberCategory =
    if symbol.kind == ApiSymbolKind.TypeAlias || symbol.kind == ApiSymbolKind.OpaqueType then
      ApiMemberCategory.Types
    else if symbol.ownerId.contains("package:scalive") then rootPackageCategory(symbol)
    else
      symbol.kind match
        case ApiSymbolKind.Extension => ApiMemberCategory.Extensions
        case ApiSymbolKind.Def       => ApiMemberCategory.Methods
        case ApiSymbolKind.Given     => ApiMemberCategory.Givens
        case _                       => ApiMemberCategory.Values

  private def rootPackageCategory(symbol: ApiSymbol): ApiMemberCategory =
    val origins = symbol.signatures.map(_.origin.qualifiedName)
    if origins.exists(_.startsWith("scalive.defs.tags.")) then ApiMemberCategory.HtmlElements
    else if origins.exists(_.startsWith("scalive.defs.attrs.")) then
      ApiMemberCategory.HtmlAttributes
    else if origins.exists(_.startsWith("scalive.defs.complex.")) then ApiMemberCategory.DomHelpers
    else if origins.exists(_.startsWith("scalive.defs.components.")) then
      ApiMemberCategory.Components
    else if origins.exists(_.startsWith("scalive.streams.")) then ApiMemberCategory.Streams
    else if origins.exists(_.startsWith("scalive.upload.")) then ApiMemberCategory.Uploads
    else ApiMemberCategory.CoreApi
end ApiMemberCategory

enum ApiExposure:
  case Direct, Exported, Inherited

object ApiExposure:
  given JsonCodec[ApiExposure] = JsonCodec[String].transformOrFail(
    {
      case "direct"    => Right(ApiExposure.Direct)
      case "exported"  => Right(ApiExposure.Exported)
      case "inherited" => Right(ApiExposure.Inherited)
      case other       => Left(s"Unknown API exposure: $other")
    },
    {
      case ApiExposure.Direct    => "direct"
      case ApiExposure.Exported  => "exported"
      case ApiExposure.Inherited => "inherited"
    }
  )

final case class SearchEntry(
  id: String,
  kind: SearchEntryKind,
  title: String,
  description: String,
  route: String,
  fragment: Option[String],
  section: Section,
  text: String)
    derives JsonCodec

enum SearchEntryKind:
  case Page, Heading, Example, ApiSymbol, Compatibility

object SearchEntryKind:
  given JsonCodec[SearchEntryKind] = JsonCodec[String].transformOrFail(
    {
      case "page"          => Right(SearchEntryKind.Page)
      case "heading"       => Right(SearchEntryKind.Heading)
      case "example"       => Right(SearchEntryKind.Example)
      case "apiSymbol"     => Right(SearchEntryKind.ApiSymbol)
      case "compatibility" => Right(SearchEntryKind.Compatibility)
      case other           => Left(s"Unknown search entry kind: $other")
    },
    {
      case SearchEntryKind.Page          => "page"
      case SearchEntryKind.Heading       => "heading"
      case SearchEntryKind.Example       => "example"
      case SearchEntryKind.ApiSymbol     => "apiSymbol"
      case SearchEntryKind.Compatibility => "compatibility"
    }
  )
