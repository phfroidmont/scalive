package scalive.examples.navigation

import zio.ZIO
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.examples.ExamplesRoutes

final case class SearchParams(query: Option[String], page: Option[Int]) derives Schema

final class SearchLiveView
    extends LiveView.Routed[SearchLiveView.Msg, SearchLiveView.Model, SearchParams]:
  import SearchLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(SearchParams(None, None), page = 1))

  override def handleParams(
    model: Model,
    params: SearchParams,
    url: URL,
    ctx: ParamsContext
  ) =
    ZIO.succeed(model.copy(params = params, page = normalizePage(params.page)))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Search(rawQuery) =>
      val query    = Option(rawQuery.trim).filter(_.nonEmpty)
      val location = ExamplesRoutes.search.location(SearchParams(query, Some(1)))
      ctx.nav.pushNavigate(location).as(model)

  def render(model: Model) =
    val query   = model.params.query.map(_.trim).filter(_.nonEmpty)
    val matches = query match
      case None        => Articles
      case Some(value) =>
        val needle = value.toLowerCase
        Articles.filter(article =>
          article.title.toLowerCase.contains(needle) ||
            article.summary.toLowerCase.contains(needle)
        )

    val resultCount = matches.size.toLong
    val pageStart   = (model.page.toLong - 1L) * PageSize.toLong
    val pageEnd     = (pageStart + PageSize.toLong).min(resultCount)
    val pageResults =
      if pageStart >= resultCount then Vector.empty
      else matches.slice(pageStart.toInt, pageEnd.toInt)

    val previousPage =
      if model.page > 1 then
        Some(
          ExamplesRoutes.search.location(
            SearchParams(model.params.query, Some(model.page - 1))
          )
        )
      else None

    val nextPage =
      if pageEnd < resultCount then
        Some(
          ExamplesRoutes.search.location(
            SearchParams(model.params.query, Some(model.page + 1))
          )
        )
      else None

    val clearSearch = ExamplesRoutes.search.location(SearchParams(None, None))

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
        phx.onSubmit(params => Msg.Search(params.getOrElse("query", ""))),
        label(
          cls := "form-control flex-1",
          span(cls := "label-text mb-2 font-medium", "Search the example notes"),
          input(
            typ         := "search",
            nameAttr    := "query",
            value       := model.params.query.getOrElse(""),
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
            s"Page ${model.page}; absent and non-positive page values display as page 1."
          )
        ),
        link.patchReplace(
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
          link.patch(location, cls := "btn btn-outline", "Previous page")
        ),
        nextPage.fold(span())(location =>
          link.patch(location, cls := "btn btn-outline", "Next page")
        )
      )
    )
  end render
end SearchLiveView

object SearchLiveView:
  final case class Article(title: String, summary: String)

  final case class Model(params: SearchParams, page: Int)

  enum Msg:
    case Search(query: String)

  private val PageSize = 3

  private val Articles = Vector(
    Article("Typed routes", "Schema-derived query values produce complete, reusable locations."),
    Article("Live patches", "Patch navigation changes parameters without remounting the LiveView."),
    Article("Stateful forms", "Typed form events separate decoding, validation, and persistence."),
    Article("Activity streams", "Opaque stream handles drive efficient collection patches."),
    Article("Live components", "Stable identities preserve local component state across renders."),
    Article("Async work", "Typed task keys replace stale work and report deterministic outcomes."),
    Article("Uploads", "Scoped storage keeps accepted files separate from temporary entries.")
  )

  private def normalizePage(page: Option[Int]): Int =
    page.filter(_ > 0).getOrElse(1)
