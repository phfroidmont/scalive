package routing

import zio.*
import zio.http.URL

import scalive.*

// docs:start routing-declarations
object Routes:
  val search =
    (live / "search").queryOptional[String]("q")

val application = Live.router(
  Routes.search -> SearchLiveView()
)
// docs:end routing-declarations

// docs:start routing-eventless-view
final class SearchLiveView extends LiveView.Routed.Eventless[SearchLiveView.Model, Option[String]]:
  import SearchLiveView.*

  def mount(params: Option[String], ctx: MountContext) =
    ZIO.succeed(search(params))

  override def handleParams(model: Model, params: Option[String], url: URL, ctx: ParamsContext) =
    ZIO.succeed(search(params))
  // docs:end routing-eventless-view

  def view(model: Signal[Model]): HtmlElement[Nothing] =
    mainTag(p("Results for ", model.map(_.query)))

object SearchLiveView:
  final case class Model(query: String)

  private def search(params: Option[String]): Model =
    Model(params.map(_.trim).getOrElse(""))

object TypedRoutes:
  // docs:start routing-map-params
  final case class SearchTerm private (value: String)

  object SearchTerm:
    def from(raw: String): Option[SearchTerm] =
      Some(raw.trim).filter(term => term.nonEmpty && term.length <= 100).map(SearchTerm(_))

  final case class SearchParams(query: Option[SearchTerm])

  val search =
    (live / "search")
      .queryOptional[String]("q")
      .mapParams(raw => SearchParams(raw.flatMap(SearchTerm.from)))(params =>
        params.query.map(_.value)
      )
  // docs:end routing-map-params

object Locations:
  // docs:start routing-location
  val destination = Routes.search.location(Some("LiveView"))
  // destination.href == "/search?q=LiveView"
  // docs:end routing-location

  // docs:start routing-fragment
  val heading = Routes.search.location(Some("LiveView")).withFragment("results")
  // heading.href == "/search?q=LiveView#results"
  // docs:end routing-fragment
