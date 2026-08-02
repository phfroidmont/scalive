package scalive.examples.navigation

import zio.ZIO
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.examples.ExamplesRoutes

final private[examples] case class RawSearchParams(query: Option[String], page: Option[String])
    derives Schema

final class SearchTerm private (val value: String) extends AnyVal

object SearchTerm:
  def from(raw: String): Option[SearchTerm] =
    Option(raw.trim).filter(_.nonEmpty).map(new SearchTerm(_))

final class SearchPage private (val value: Int) extends AnyVal:
  def previous: Option[SearchPage] = SearchPage.from(value - 1)
  def next: SearchPage             = SearchPage.from(value + 1).getOrElse(this)

object SearchPage:
  val First: SearchPage = new SearchPage(1)

  def from(value: Int): Option[SearchPage] =
    Option.when(value > 0)(new SearchPage(value))

  def from(raw: String): Option[SearchPage] =
    raw.trim.toIntOption.flatMap(from)

final case class SearchParams(query: Option[SearchTerm], page: SearchPage):
  def withPage(page: SearchPage): SearchParams = copy(page = page)

  private[examples] def toRaw: RawSearchParams =
    RawSearchParams(
      query.map(_.value),
      Option.when(page != SearchPage.First)(page.value.toString)
    )

object SearchParams:
  val Empty: SearchParams = SearchParams(None, SearchPage.First)

  private[examples] def fromRaw(raw: RawSearchParams): SearchParams =
    SearchParams(
      raw.query.flatMap(SearchTerm.from),
      raw.page.flatMap(SearchPage.from).getOrElse(SearchPage.First)
    )

final class SearchLiveView
    extends LiveView.Routed[SearchLiveView.Msg, SearchLiveView.Model, SearchParams]:
  import SearchLiveView.*

  def mount(params: SearchParams, ctx: MountContext) =
    ZIO.succeed(Model(params))

  override def handleParams(
    model: Model,
    params: SearchParams,
    url: URL,
    ctx: ParamsContext
  ) =
    val canonical = ExamplesRoutes.search.location(params)
    val current   = URL(url.path, queryParams = url.queryParams).encode
    val updated   = model.copy(params = params)
    if current == canonical.href then ZIO.succeed(updated)
    else ctx.nav.replacePatch(canonical).as(updated)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Search(event) =>
      event.value match
        case Right(query) =>
          val location = ExamplesRoutes.search.location(SearchParams(query, SearchPage.First))
          ctx.nav.pushNavigate(location).as(model)
        case Left(_) =>
          ZIO.succeed(model)

  def render(model: Model) =
    val query   = model.params.query.map(_.value)
    val page    = model.params.page
    val matches = query match
      case None        => Articles
      case Some(value) =>
        val needle = value.toLowerCase
        Articles.filter(article =>
          article.title.toLowerCase.contains(needle) ||
            article.summary.toLowerCase.contains(needle)
        )

    val resultCount = matches.size.toLong
    val pageStart   = (page.value.toLong - 1L) * PageSize.toLong
    val pageEnd     = (pageStart + PageSize.toLong).min(resultCount)
    val pageResults =
      if pageStart >= resultCount then Vector.empty
      else matches.slice(pageStart.toInt, pageEnd.toInt)

    val previousPage =
      page.previous.map(previous => ExamplesRoutes.search.location(model.params.withPage(previous)))

    val nextPage =
      if pageEnd < resultCount then
        Some(
          ExamplesRoutes.search.location(
            model.params.withPage(page.next)
          )
        )
      else None

    val clearSearch = ExamplesRoutes.search.location(SearchParams.Empty)

    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Navigation"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Typed search navigation"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Every destination is a complete LiveLocation built from SearchParams. Patches keep this LiveView mounted; submitting the form performs a message-driven navigate."
        )
      ),
      form(
        cls := "mb-6 flex flex-col gap-3 rounded-box border border-base-300 bg-base-100 p-5 sm:flex-row",
        phx.onSubmitForm(QueryField)(Msg.Search.apply),
        label(
          cls := "form-control flex-1",
          span(cls := "label-text mb-2 font-medium", "Search the example notes"),
          input(
            typ         := "search",
            nameAttr    := QueryField.name,
            value       := query.getOrElse(""),
            placeholder := "Try streams or forms",
            cls         := "input input-bordered w-full"
          )
        ),
        button(
          typ := "submit",
          cls := "btn btn-primary self-end",
          "Navigate to results"
        )
      ),
      div(
        cls := "mb-5 flex flex-wrap items-center justify-between gap-3",
        div(
          p(
            cls := "font-semibold",
            query.fold("All topics")(value => s"Results for '$value'")
          ),
          p(
            cls := "text-sm text-base-content/60",
            s"Page ${page.value}; noncanonical search URLs are replaced with their normalized form."
          )
        ),
        link.replacePatch(
          clearSearch,
          cls := "btn btn-ghost btn-sm",
          "Clear with replace-patch"
        )
      ),
      if pageResults.isEmpty then
        div(
          cls := "rounded-box border border-dashed border-base-300 p-10 text-center text-base-content/60",
          "No notes are available on this page."
        )
      else
        div(
          cls := "grid gap-4",
          pageResults.map { article =>
            articleTag(
              cls := "rounded-box border border-base-300 bg-base-100 p-5 shadow-sm",
              h2(cls := "text-lg font-semibold", article.title),
              p(cls  := "mt-2 leading-7 text-base-content/70", article.summary)
            )
          }
        )
      ,
      navTag(
        cls := "mt-6 flex items-center justify-between",
        previousPage.fold(span())(location =>
          link.pushPatch(location, cls := "btn btn-outline", "Previous page")
        ),
        nextPage.fold(span())(location =>
          link.pushPatch(location, cls := "btn btn-outline", "Next page")
        )
      )
    )
  end render
end SearchLiveView

object SearchLiveView:
  final case class Article(title: String, summary: String)

  final case class Model(params: SearchParams)

  enum Msg:
    case Search(event: FormEvent[Option[SearchTerm]])

  private val PageSize   = 3
  private val QueryField = FormField.string(FormPath("query")).map(SearchTerm.from)

  private val Articles = Vector(
    Article("Typed routes", "Schema-derived query values produce complete, reusable locations."),
    Article("Live patches", "Patch navigation changes parameters without remounting the LiveView."),
    Article("Stateful forms", "Typed form events separate decoding, validation, and persistence."),
    Article("Activity streams", "Opaque stream handles drive efficient collection patches."),
    Article("Live components", "Stable identities preserve local component state across renders."),
    Article("Async work", "Typed task keys replace stale work and report deterministic outcomes."),
    Article("Uploads", "Scoped storage keeps accepted files separate from temporary entries.")
  )
