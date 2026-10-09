// docs:start learn-search
package quickstart

import zio.http.URL
import zio.{Task, ZIO}

import scalive.*

final class SearchLiveView
    extends LiveView.Routed[SearchLiveView.Msg, SearchLiveView.Model, Option[String]]:
  import SearchLiveView.*

  def mount(params: Option[String], ctx: MountContext): Task[Model] =
    ZIO.succeed(modelFrom(params))

  override def handleParams(
    model: Model,
    params: Option[String],
    url: URL,
    ctx: ParamsContext
  ): Task[Model] =
    ZIO.succeed(modelFrom(params))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ClearQuery =>
      ctx.nav.replacePatch(Routes.search.location(None)).as(model)

  def view(model: Signal[Model]): HtmlElement[Msg] =
    mainTag(
      p("Search: ", model.map(_.query)),
      link.pushPatch(Routes.search.location(Some("scala")), "Search for Scala"),
      button(on.click(Msg.ClearQuery), "Clear")
    )

object SearchLiveView:
  final case class Model(query: String)

  enum Msg:
    case ClearQuery

  private def modelFrom(params: Option[String]): Model =
    Model(params.map(_.trim.take(100)).filter(_.nonEmpty).getOrElse(""))
// docs:end learn-search
