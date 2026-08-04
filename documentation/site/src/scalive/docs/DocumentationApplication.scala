package scalive.docs

import zio.http.Routes
import zio.http.codec.PathCodec

import scalive.*
import scalive.docs.model.*

final private[docs] case class DocumentationPageEntry(
  page: Page,
  codec: PathCodec[Unit],
  location: LiveLocation)

final private[docs] class DocumentationApplication private (
  val bundle: DocumentationBundle,
  val pages: Vector[DocumentationPageEntry],
  private val pagesByRoute: Map[String, Page],
  private val locationsByRoute: Map[String, LiveLocation],
  private val apiSymbolsById: Map[String, ApiSymbol]):

  def page(route: String): Option[Page] = pagesByRoute.get(route)

  def location(route: String): Option[LiveLocation] = locationsByRoute.get(route)

  def apiSymbol(id: String): Option[ApiSymbol] = apiSymbolsById.get(id)

  def routes(assets: StaticAssets, security: LiveSecurity): Routes[Any, Nothing] =
    val renderer  = DocumentationRenderer(this)
    val fragments = pages.map { entry =>
      Live.route(entry.codec) -> DocumentationPageLiveView(entry.page, renderer)
    }
    Live.router
      .withSecurity(security)
      .withRootLayout(DocumentationRootLayout(this, assets))
      .withLayout(DocumentationLayout(this))(
        fragments.head,
        fragments.tail*
      )

private[docs] object DocumentationApplication:
  def from(bundle: DocumentationBundle): Either[String, DocumentationApplication] =
    for
      _ <- validateFormat(bundle)
      _ <- Either.cond(bundle.pages.nonEmpty, (), "Generated documentation contains no pages.")
      entries <- buildEntries(bundle.pages)
      _       <- validateReferences(bundle)
    yield new DocumentationApplication(
      bundle,
      entries,
      bundle.pages.map(page => page.route -> page).toMap,
      entries.map(entry => entry.page.route -> entry.location).toMap,
      bundle.apiReference.symbols.map(symbol => symbol.id -> symbol).toMap
    )

  private def validateFormat(bundle: DocumentationBundle): Either[String, Unit] =
    Either.cond(
      bundle.formatVersion == DocumentationBundle.CurrentFormatVersion,
      (),
      s"Unsupported documentation format ${bundle.formatVersion}; expected ${DocumentationBundle.CurrentFormatVersion}."
    )

  private def buildEntries(pages: Vector[Page]): Either[String, Vector[DocumentationPageEntry]] =
    val duplicateRoutes = pages
      .groupBy(_.route).collect {
        case (route, matches) if matches.size > 1 => route
      }.toVector.sorted
    if duplicateRoutes.nonEmpty then
      Left(s"Generated documentation contains duplicate routes: ${duplicateRoutes.mkString(", ")}.")
    else
      pages.foldLeft[Either[String, Vector[DocumentationPageEntry]]](Right(Vector.empty)) {
        case (result, page) =>
          result.flatMap { entries =>
            val codec = PathCodec(page.route)
            Live
              .route(codec)
              .locationEither
              .left.map(_.message)
              .flatMap(location =>
                Either.cond(
                  location.href == page.route,
                  entries :+ DocumentationPageEntry(page, codec, location),
                  s"Documentation route '${page.route}' encoded as '${location.href}'."
                )
              )
          }
      }

  private def validateReferences(bundle: DocumentationBundle): Either[String, Unit] =
    val routes  = bundle.pages.map(_.route).toSet
    val symbols = bundle.apiReference.symbols.map(_.id).toSet
    val errors  = bundle.pages.flatMap { page =>
      collectReferences(page.content).flatMap {
        case ContentReference.Route(route) if !routes(route) =>
          Vector(s"${page.route}: unknown internal route '$route'.")
        case ContentReference.ApiSymbol(id) if !symbols(id) =>
          Vector(s"${page.route}: unknown API symbol '$id'.")
        case _ => Vector.empty
      }
    }
    Either.cond(errors.isEmpty, (), errors.distinct.sorted.mkString(" "))

  private enum ContentReference:
    case Route(route: String)
    case ApiSymbol(id: String)

  private def collectReferences(blocks: Vector[Block]): Vector[ContentReference] =
    blocks.flatMap {
      case Block.Paragraph(content)     => collectInlineReferences(content)
      case Block.Heading(_, _, content) => collectInlineReferences(content)
      case Block.BulletList(items)      => items.flatMap(item => collectReferences(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => collectReferences(item.content))
      case Block.Quote(content)         => collectReferences(content)
      case Block.Table(header, rows)    =>
        (header ++ rows.flatMap(_.cells)).flatMap(cell => collectInlineReferences(cell.content))
      case Block.Callout(_, _, content) => collectReferences(content)
      case Block.ApiSymbolRef(id)       => Vector(ContentReference.ApiSymbol(id))
      case _                            => Vector.empty
    }

  private def collectInlineReferences(inlines: Vector[Inline]): Vector[ContentReference] =
    inlines.flatMap {
      case Inline.Emphasis(content)        => collectInlineReferences(content)
      case Inline.Strong(content)          => collectInlineReferences(content)
      case Inline.Strike(content)          => collectInlineReferences(content)
      case Inline.Link(content, target, _) =>
        val nested = collectInlineReferences(content)
        target match
          case LinkTarget.Internal(route, _) => ContentReference.Route(route) +: nested
          case _: LinkTarget.External        => nested
      case _ => Vector.empty
    }
end DocumentationApplication
