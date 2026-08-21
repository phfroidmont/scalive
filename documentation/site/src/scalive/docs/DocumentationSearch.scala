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

  override def view(model: Signal[DocumentationSearchModel]): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-content docs-search-page",
      h1("Search"),
      p("Search learning content, examples, API symbols, and compatibility notes."),
      searchForm(model.map(_.query)),
      searchResults(model)
    )

  private def search(params: Option[String]): DocumentationSearchModel =
    val query = params.map(_.trim).filter(_.nonEmpty)
    val all   = query.fold(Vector.empty[SearchEntry])(value =>
      SearchRanking.search(value, application.bundle.searchEntries, Int.MaxValue)
    )
    DocumentationSearchModel(query, all.take(MaxResults), all.size)

  private def searchForm(query: Signal[Option[String]]): HtmlElement[Nothing] =
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
          value                                       := query.map(_.getOrElse("")),
          placeholder                                 := "Try LiveView or handleMessage",
          htmlAttr("autocomplete", StringAsIsEncoder) := "off"
        ),
        button(typ := "submit", "Search")
      )
    )

  private def searchResults(model: Signal[DocumentationSearchModel]): HtmlElement[Nothing] =
    val noQuery    = model.map(_.query.isEmpty)
    val noResults  = model.map(value => value.query.nonEmpty && value.results.isEmpty)
    val hasResults = model.map(_.results.nonEmpty)
    sectionTag(
      cls      := "docs-search-results",
      ariaLive := "polite",
      noQuery.when(p("Enter a term to search the generated documentation index.")),
      noResults.when(
        p(
          role := "status",
          model.map(value => s"No results for '${value.query.getOrElse("")}'.")
        )
      ),
      hasResults.when(
        p(
          role := "status",
          cls  := "docs-search-summary",
          model.map { value =>
            val query = value.query.getOrElse("")
            if value.total <= MaxResults then s"${value.total} results for '$query'."
            else s"Showing the first $MaxResults of ${value.total} results for '$query'."
          }
        )
      ),
      hasResults.when(
        ol(
          model.map(_.results).splitBy(_.id) { (_, entry) =>
            val location = entry.map(value =>
              application.searchLocation(value).getOrElse {
                throw new IllegalArgumentException(
                  s"Invalid search result destination: ${value.id}"
                )
              }
            )
            li(
              cls := "docs-search-result",
              link.pushNavigate(location, entry.map(_.title)),
              span(
                cls := "docs-search-result-kind",
                entry.map(value => DocumentationSearchLiveView.kindLabel(value.kind))
              ),
              p(entry.map(_.description))
            )
          }
        )
      )
    )
  end searchResults
end DocumentationSearchLiveView

private[docs] object DocumentationSearchLiveView:
  def kindLabel(kind: SearchEntryKind): String = kind match
    case SearchEntryKind.Page          => "Page"
    case SearchEntryKind.Heading       => "Heading"
    case SearchEntryKind.Example       => "Example"
    case SearchEntryKind.ApiSymbol     => "API"
    case SearchEntryKind.Compatibility => "Compatibility"
