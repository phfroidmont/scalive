package scalive.docs

import zio.http.Routes
import zio.http.codec.PathCodec

import scalive.*
import scalive.docs.auth.{AuthHttpRoutes, AuthLab, AuthLabRoutes, AuthService}
import scalive.docs.examples.{ExampleRegistry, Reports, ReportsExample}
import scalive.docs.model.*
import scalive.docs.xray.{DocumentationRuntimeTraceFactory, DocumentationTraceStore}

final private[docs] case class DocumentationPageEntry(
  page: Page,
  codec: PathCodec[Unit],
  location: LiveLocation)

final private[docs] case class DocumentationRouteMetadata(
  title: String,
  description: String,
  canonicalPath: String,
  indexable: Boolean)

final private[docs] class DocumentationApplication private (
  val bundle: DocumentationBundle,
  val pages: Vector[DocumentationPageEntry],
  val homeContent: HomePageContent,
  private val pagesByRoute: Map[String, Page],
  private val locationsByRoute: Map[String, LiveLocation],
  private val examplesById: Map[String, ExampleDefinition],
  private val apiSymbolsById: Map[String, ApiSymbol],
  private val authService: AuthService):

  def page(route: String): Option[Page] = pagesByRoute.get(route)

  def location(route: String): Option[LiveLocation] = locationsByRoute.get(route)

  def apiSymbol(id: String): Option[ApiSymbol] = apiSymbolsById.get(id)

  def example(id: String): Option[ExampleDefinition] = examplesById.get(id)

  def metadata(route: String): Option[DocumentationRouteMetadata] =
    page(route)
      .map { page =>
        DocumentationRouteMetadata(
          page.metadata.title,
          page.metadata.description,
          page.route,
          indexable = true
        )
      }.orElse(
        Option.when(route == ReportsExample.LabRoute)(
          DocumentationRouteMetadata(
            "Reports service injection lab",
            "A standalone LiveView route constructed from a Reports service layer.",
            DocumentationApplication.ExamplesRoute + "/service-injection",
            indexable = false
          )
        )
      ).orElse(
        Option.when(route == AuthLabRoutes.LoginPath)(
          DocumentationRouteMetadata(
            "Authentication lab",
            "Sign in through ordinary HTTP, then mount a protected LiveView with revalidated session claims.",
            DocumentationApplication.GuidesRoute + "/authentication",
            indexable = false
          )
        )
      ).orElse(
        Option.when(route == AuthLabRoutes.ProfilePath)(
          DocumentationRouteMetadata(
            "Protected authentication lab",
            "Inspect and reset the current visitor's bounded authentication lab session.",
            DocumentationApplication.GuidesRoute + "/authentication",
            indexable = false
          )
        )
      ).orElse(
        Option.when(route == DocumentationApplication.SearchRoute)(
          DocumentationRouteMetadata(
            "Search",
            "Search Scalive learning content, examples, API symbols, and compatibility notes.",
            DocumentationApplication.SearchRoute,
            indexable = false
          )
        )
      )

  def searchLocation(entry: SearchEntry): Option[LiveLocation] =
    entry.fragment.fold(location(entry.route))(fragment =>
      location(entry.route).map(_.withFragment(fragment))
    )

  def routes(
    assets: StaticAssets,
    security: LiveSecurity,
    config: DocumentationConfig
  ): Routes[Reports, Nothing] = routes(assets, security, config, None)

  def routes(
    assets: StaticAssets,
    security: LiveSecurity,
    config: DocumentationConfig,
    traceStore: DocumentationTraceStore
  ): Routes[Reports, Nothing] = routes(assets, security, config, Some(traceStore))

  private def routes(
    assets: StaticAssets,
    security: LiveSecurity,
    config: DocumentationConfig,
    traceStore: Option[DocumentationTraceStore]
  ): Routes[Reports, Nothing] =
    val renderer  = DocumentationRenderer(this, traceStore)
    val homeEntry = pages.find(_.page.route == "/").getOrElse {
      throw new IllegalStateException("Missing validated homepage route '/'.")
    }
    val homeRoute = Live.route(homeEntry.codec) -> DocumentationHomeLiveView(
      homeEntry.page,
      homeContent,
      this,
      renderer
    )
    val examplesPage = pages.find(_.page.route == DocumentationApplication.ExamplesRoute).getOrElse {
      throw new IllegalStateException("Missing validated examples catalog route '/examples'.")
    }
    val fragments = pages
      .filterNot(entry =>
        entry.page.route == "/" || entry.page.route == DocumentationApplication.ExamplesRoute
      ).map { entry =>
        Live.route(entry.codec) -> DocumentationPageLiveView(entry.page, renderer)
      }
    val examplesRoute = DocumentationApplication.ExamplesCatalogRoute ->
      DocumentationExamplesLiveView(examplesPage.page, this, renderer)
    val searchRoute = DocumentationApplication.SearchRouteBuilder ->
      DocumentationSearchLiveView(this)
    val router = Live.router
      .withSecurity(security)
      .withRootLayout(DocumentationRootLayout(this, assets, config.publicOrigin))
      .withLayout(DocumentationLayout(this, assets, config.publicOrigin))
    val tracedRouter = traceStore.fold(router)(store =>
      router.withRuntimeTrace(DocumentationRuntimeTraceFactory(store))
    )
    val supplementalRoutes = Vector[LiveRouteFragment[Reports, Any]](
      examplesRoute,
      searchRoute,
      ReportsExample.route,
      AuthLab.loginRoute,
      AuthLab.protectedSession(authService)
    ) ++ fragments
    val liveRoutes = tracedRouter(
      homeRoute,
      supplementalRoutes*
    )
    liveRoutes ++
      AuthHttpRoutes(authService, security).routes ++
      DocumentationMetadataRoutes.routes(this, config.publicOrigin)
  end routes
end DocumentationApplication

private[docs] object DocumentationApplication:
  val SearchRoute                      = "/search"
  val SearchParameter                  = "q"
  private[docs] val SearchRouteBuilder =
    (live / "search").queryOptional[String](SearchParameter)
  val ExamplesRoute                      = "/examples"
  val GuidesRoute                        = "/guides"
  val TopicParameter                     = "topic"
  private[docs] val ExamplesCatalogRoute =
    (live / "examples").queryOptional[String](TopicParameter)

  def from(bundle: DocumentationBundle): Either[String, DocumentationApplication] =
    for
      _ <- validateFormat(bundle)
      _ <- Either.cond(bundle.pages.nonEmpty, (), "Generated documentation contains no pages.")
      _ <- Either.cond(
             !bundle.pages.exists(_.route == SearchRoute),
             (),
             s"Generated documentation uses reserved route '$SearchRoute'."
           )
      _       <- validateExamples(bundle)
      entries <- buildEntries(bundle.pages)
      home    <- validateHomepage(bundle.pages)
      _       <- validateReferences(bundle)
      _       <- validateCanonicalExamples(bundle)
      _       <- validateSearchEntries(bundle)
    yield new DocumentationApplication(
      bundle,
      entries,
      home,
      bundle.pages.map(page => page.route -> page).toMap,
      entries.map(entry => entry.page.route -> entry.location).toMap,
      bundle.examples.map(example => example.descriptor.id -> example).toMap,
      bundle.apiReference.symbols.map(symbol => symbol.id -> symbol).toMap,
      AuthService.inMemory()
    )

  private def validateHomepage(pages: Vector[Page]): Either[String, HomePageContent] =
    val rootPages = pages.filter(_.route == "/")
    val homePages = pages.filter(_.metadata.section == Section.Home)
    for
      page <- rootPages match
                case Vector(value) => Right(value)
                case Vector()      => Left("Generated documentation is missing homepage route '/'.")
                case values        =>
                  Left(s"Generated documentation contains ${values.size} homepage routes '/'.")
      _ <- Either.cond(
             homePages == Vector(page),
             (),
             "Generated documentation must contain exactly one page in section Home, at route '/'."
           )
      content <- HomePageContent.from(page)
    yield content

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
    val routes   = bundle.pages.map(_.route).toSet
    val anchors  = bundle.pages.map(page => page.route -> pageAnchors(page)).toMap
    val symbols  = bundle.apiReference.symbols.map(_.id).toSet
    val examples = bundle.examples.map(_.descriptor.id).toSet
    val errors   = bundle.pages.flatMap { page =>
      val references        = collectReferences(page.content)
      val duplicateExamples = references
        .collect { case ContentReference.Example(id) => id }
        .groupBy(identity)
        .collect {
          case (id, matches) if matches.sizeIs > 1 =>
            s"${page.route}: example '$id' appears more than once."
        }
      duplicateExamples ++ references.flatMap {
        case ContentReference.Route(route, _) if !routes(route) =>
          Vector(s"${page.route}: unknown internal route '$route'.")
        case ContentReference.Route(route, Some(fragment)) if !anchors(route)(fragment) =>
          Vector(s"${page.route}: unknown internal anchor '$route#$fragment'.")
        case ContentReference.ApiSymbol(id) if !symbols(id) =>
          Vector(s"${page.route}: unknown API symbol '$id'.")
        case ContentReference.Example(id) if !examples(id) =>
          Vector(s"${page.route}: unknown example '$id'.")
        case _ => Vector.empty
      }
    }
    Either.cond(errors.isEmpty, (), errors.distinct.sorted.mkString(" "))

  private def validateExamples(bundle: DocumentationBundle): Either[String, Unit] =
    val duplicateIds = bundle.examples.groupBy(_.descriptor.id).collect {
      case (id, matches) if matches.sizeIs > 1 =>
        s"Generated documentation contains duplicate example '$id'."
    }
    val runtimeById =
      ExampleRegistry.entries.map(entry => entry.descriptor.id -> entry.descriptor).toMap
    val generatedById =
      bundle.examples.map(example => example.descriptor.id -> example.descriptor).toMap
    val missing = (runtimeById.keySet -- generatedById.keySet).toVector.sorted.map { id =>
      s"Generated documentation is missing example '$id'."
    }
    val unexpected = (generatedById.keySet -- runtimeById.keySet).toVector.sorted.map { id =>
      s"Generated documentation contains unknown runtime example '$id'."
    }
    val mismatched = (runtimeById.keySet intersect generatedById.keySet).toVector.sorted.collect {
      case id if runtimeById(id) != generatedById(id) =>
        s"Generated metadata differs from runtime example '$id'."
    }
    val errors =
      ExampleRegistry.validationErrors ++ duplicateIds ++ missing ++ unexpected ++ mismatched
    Either.cond(errors.isEmpty, (), errors.toVector.sorted.mkString(" "))

  private def validateCanonicalExamples(bundle: DocumentationBundle): Either[String, Unit] =
    val pagesByRoute  = bundle.pages.map(page => page.route -> page).toMap
    val catalogErrors = pagesByRoute.get(ExamplesRoute) match
      case None => Vector("Generated documentation is missing examples catalog page '/examples'.")
      case Some(page) =>
        Vector(
          Option.when(page.metadata.section != Section.Examples)(
            "Examples catalog page '/examples' must use section Examples."
          ),
          Option.when(exampleReferences(page.content).nonEmpty)(
            "Examples catalog page '/examples' must not embed executable examples."
          )
        ).flatten
    val detailErrors = bundle.examples.flatMap { example =>
      val descriptor = example.descriptor
      val route      = s"/examples/${descriptor.id}"
      pagesByRoute.get(route) match
        case None => Vector(s"Generated documentation is missing canonical example page '$route'.")
        case Some(page) =>
          val references = exampleReferences(page.content)
          Vector(
            Option.when(page.metadata.section != Section.Examples)(
              s"Canonical example page '$route' must use section Examples."
            ),
            Option.when(page.metadata.title != descriptor.title)(
              s"Canonical example page '$route' title differs from example '${descriptor.id}'."
            ),
            Option.when(page.metadata.description != descriptor.description)(
              s"Canonical example page '$route' description differs from example '${descriptor.id}'."
            ),
            Option.when(references != Vector(descriptor.id))(
              s"Canonical example page '$route' must embed exactly '${descriptor.id}'."
            )
          ).flatten
    }
    val errors = catalogErrors ++ detailErrors
    Either.cond(errors.isEmpty, (), errors.toVector.sorted.mkString(" "))
  end validateCanonicalExamples

  private def validateSearchEntries(bundle: DocumentationBundle): Either[String, Unit] =
    val anchors      = bundle.pages.map(page => page.route -> pageAnchors(page)).toMap
    val duplicateIds = bundle.searchEntries
      .groupBy(_.id).collect { case (id, matches) if matches.size > 1 => id }.toVector.sorted
    val targetErrors = bundle.searchEntries.flatMap { entry =>
      anchors.get(entry.route) match
        case None => Vector(s"Search entry '${entry.id}' targets unknown route '${entry.route}'.")
        case Some(values) =>
          entry.fragment.collect {
            case fragment if !values(fragment) =>
              s"Search entry '${entry.id}' targets unknown anchor '${entry.route}#$fragment'."
          }.toVector
    }
    val duplicateErrors =
      duplicateIds.map(id => s"Generated documentation contains duplicate search id '$id'.")
    val errors = duplicateErrors ++ targetErrors
    Either.cond(errors.isEmpty, (), errors.mkString(" "))

  private def pageAnchors(page: Page): Set[String] =
    flattenOutline(page.outline.items).map(_.id).toSet ++ directiveAnchors(page.content)

  private def flattenOutline(items: Vector[OutlineItem]): Vector[OutlineItem] =
    items.flatMap(item => item +: flattenOutline(item.children))

  private def directiveAnchors(blocks: Vector[Block]): Set[String] =
    blocks.flatMap {
      case Block.ExampleRef(id)         => Vector(s"example-$id")
      case Block.CompatibilityRef(id)   => Vector(s"compatibility-$id")
      case Block.BulletList(items)      => items.flatMap(item => directiveAnchors(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => directiveAnchors(item.content))
      case Block.Quote(content)         => directiveAnchors(content)
      case Block.Callout(_, _, content) => directiveAnchors(content)
      case _                            => Set.empty[String]
    }.toSet

  private def exampleReferences(blocks: Vector[Block]): Vector[String] =
    blocks.flatMap {
      case Block.ExampleRef(id)         => Vector(id)
      case Block.LabRef(_)              => Vector.empty
      case Block.BulletList(items)      => items.flatMap(item => exampleReferences(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => exampleReferences(item.content))
      case Block.Quote(content)         => exampleReferences(content)
      case Block.Callout(_, _, content) => exampleReferences(content)
      case _                            => Vector.empty
    }

  private enum ContentReference:
    case Route(route: String, fragment: Option[String])
    case ApiSymbol(id: String)
    case Example(id: String)

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
      case Block.ExampleRef(id)         => Vector(ContentReference.Example(id))
      case Block.LabRef(_)              => Vector.empty
      case _                            => Vector.empty
    }

  private def collectInlineReferences(inlines: Vector[Inline]): Vector[ContentReference] =
    inlines.flatMap {
      case Inline.Emphasis(content)        => collectInlineReferences(content)
      case Inline.Strong(content)          => collectInlineReferences(content)
      case Inline.Strike(content)          => collectInlineReferences(content)
      case Inline.ApiSymbolRef(id, _)      => Vector(ContentReference.ApiSymbol(id))
      case Inline.Link(content, target, _) =>
        val nested = collectInlineReferences(content)
        target match
          case LinkTarget.Internal(route, fragment) =>
            ContentReference.Route(route, fragment) +: nested
          case _: LinkTarget.External => nested
      case _ => Vector.empty
    }
end DocumentationApplication
