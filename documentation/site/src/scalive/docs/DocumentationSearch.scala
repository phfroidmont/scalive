package scalive.docs

import zio.http.URL

import scalive.*
import scalive.codecs.StringAsIsEncoder
import scalive.docs.model.*

final private case class DocumentationSearchModel(
  query: Option[String],
  results: Vector[SearchEntry],
  total: Int)

final private[docs] class DocumentationSearchLiveView(application: DocumentationApplication)
    extends LiveView.Routed.Eventless[DocumentationSearchModel, Option[String]]:
  private val MaxResults = 50
  private val ariaLive   = htmlAttr("aria-live", StringAsIsEncoder)
  private val role       = htmlAttr("role", StringAsIsEncoder)

  def mount(params: Option[String], ctx: MountContext): LiveIO[DocumentationSearchModel] =
    LiveIO.succeed(search(params))

  override def handleParams(
    model: DocumentationSearchModel,
    params: Option[String],
    url: URL,
    ctx: ParamsContext
  ): LiveIO[DocumentationSearchModel] =
    LiveIO.succeed(search(params))

  override def pageTitle(model: DocumentationSearchModel): Option[String] =
    Some("Search | Scalive")

  def render(model: DocumentationSearchModel): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-content docs-search-page",
      h1("Search"),
      p("Search learning content, examples, API symbols, and compatibility notes."),
      searchForm(model.query),
      searchResults(model)
    )

  private def search(params: Option[String]): DocumentationSearchModel =
    val query = params.map(_.trim).filter(_.nonEmpty)
    val all   = query.fold(Vector.empty[SearchEntry])(value =>
      SearchRanking.search(value, application.bundle.searchEntries, Int.MaxValue)
    )
    DocumentationSearchModel(query, all.take(MaxResults), all.size)

  private def searchForm(query: Option[String]): HtmlElement[Nothing] =
    form(
      cls    := "docs-search-page-form",
      action := DocumentationApplication.SearchRoute,
      method := "get",
      label(
        htmlAttr("for", StringAsIsEncoder) := "docs-search-page-input",
        "Search the documentation"
      ),
      div(
        cls := "docs-search-page-control",
        input(
          idAttr                                      := "docs-search-page-input",
          typ                                         := "search",
          nameAttr                                    := DocumentationApplication.SearchParameter,
          value                                       := query.getOrElse(""),
          placeholder                                 := "Try LiveView or handleMessage",
          htmlAttr("autocomplete", StringAsIsEncoder) := "off"
        ),
        button(typ := "submit", "Search")
      )
    )

  private def searchResults(model: DocumentationSearchModel): HtmlElement[Nothing] =
    sectionTag(
      cls      := "docs-search-results",
      ariaLive := "polite",
      model.query match
        case None =>
          Vector[Mod[Nothing]](
            Mod.Content.Tag(p("Enter a term to search the generated documentation index."))
          )
        case Some(query) if model.results.isEmpty =>
          Vector[Mod[Nothing]](
            Mod.Content.Tag(p(role := "status", s"No results for '$query'."))
          )
        case Some(query) =>
          val summary =
            if model.total <= MaxResults then s"${model.total} results for '$query'."
            else s"Showing the first $MaxResults of ${model.total} results for '$query'."
          Vector[Mod[Nothing]](
            Mod.Content.Tag(p(role := "status", cls := "docs-search-summary", summary)),
            Mod.Content.Tag(
              ol(
                model.results.map { entry =>
                  li(
                    cls := "docs-search-result",
                    application.searchLocation(entry) match
                      case Some(location) =>
                        Vector[Mod[Nothing]](
                          Mod.Content.Tag(link.pushNavigate(location, entry.title)),
                          Mod.Content.Tag(
                            span(
                              cls := "docs-search-result-kind",
                              DocumentationSearchLiveView.kindLabel(entry.kind)
                            )
                          ),
                          Mod.Content.Tag(p(entry.description))
                        )
                      case None =>
                        throw new IllegalArgumentException(
                          s"Invalid search result destination: ${entry.id}"
                        )
                  )
                }
              )
            )
          )
    )
end DocumentationSearchLiveView

private[docs] object DocumentationSearchLiveView:
  def kindLabel(kind: SearchEntryKind): String = kind match
    case SearchEntryKind.Page          => "Page"
    case SearchEntryKind.Heading       => "Heading"
    case SearchEntryKind.Example       => "Example"
    case SearchEntryKind.ApiSymbol     => "API"
    case SearchEntryKind.Compatibility => "Compatibility"
