package scalive.docs.model

import zio.json.*

final case class DocumentationBundle(
  formatVersion: Int,
  navigation: Navigation,
  pages: Vector[Page],
  apiSymbols: Vector[ApiSymbol],
  searchEntries: Vector[SearchEntry])
    derives JsonCodec

final case class Page(
  route: String,
  metadata: PageMetadata,
  source: SourceLocation,
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

final case class SourceRegion(
  path: String,
  startLine: Int,
  endLineInclusive: Int)
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

  @jsonHint("lineBreak")
  case object LineBreak extends Inline

@jsonDiscriminator("type")
sealed trait LinkTarget derives JsonCodec

object LinkTarget:
  @jsonHint("internal")
  final case class Internal(route: String, fragment: Option[String]) extends LinkTarget

  @jsonHint("external")
  final case class External(url: String) extends LinkTarget

final case class ApiSymbol(
  id: String,
  name: String,
  qualifiedName: String,
  kind: String,
  signature: String,
  route: String,
  source: SourceRegion)
    derives JsonCodec

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
