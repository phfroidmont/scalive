import scala.util.Random

import KeyedComprehensionLiveView.*
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.LiveIO.given

class KeyedComprehensionLiveView(assets: StaticAssets) extends LiveView[Msg, Model]:

  override val queryCodec: LiveQueryCodec[UrlParams] = ParamsCodec

  def mount(ctx: MountContext) =
    Model(
      activeTab = "all_keyed",
      items = randomItems(10),
      count = 10
    )

  override def handleParams(model: Model, params: UrlParams, _url: URL, ctx: ParamsContext) =
    val _   = ctx
    val tab = params.tab.getOrElse("all_keyed")
    model.copy(activeTab = normalizeTab(tab))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Randomize => model.copy(items = randomItems(model.items.size))

  def render(model: Model) =
    div(
      assets.stylesheet("daisy.css", typ := "text/css"),
      cls := "p-8",
      div(
        cls := "border-b border-gray-200 mb-6",
        navTag(
          role := "tablist",
          cls  := "tabs tabs-border",
          a(
            cls  := tabClass(model.activeTab, "all_keyed"),
            href := "/keyed-comprehension?tab=all_keyed",
            "All keyed"
          ),
          a(
            cls  := tabClass(model.activeTab, "rows_keyed"),
            href := "/keyed-comprehension?tab=rows_keyed",
            "Rows keyed"
          ),
          a(
            cls  := tabClass(model.activeTab, "no_keyed"),
            href := "/keyed-comprehension?tab=no_keyed",
            "No keyed"
          )
        )
      ),
      button(cls := "btn", phx.onClick(Msg.Randomize), "randomize"),
      div(
        model.items.splitBy(_.id) { (_, item) =>
          div(cls := "hidden", item.entry.foo.bar)
        }
      ),
      (1 to 2).map(i => renderTable(i, model))
    )

  private def renderTable(i: Int, model: Model) =
    div(
      cls := "mt-8 flow-root",
      div(
        cls := "-mx-4 -my-2 overflow-x-auto sm:-mx-6 lg:-mx-8",
        div(
          cls := "inline-block min-w-full py-2 align-middle sm:px-6 lg:px-8",
          table(
            cls := "min-w-full divide-y divide-gray-300",
            thead(
              tr(
                th(cls := "py-3.5 px-3 text-left text-sm font-semibold", "Foo"),
                th(cls := "py-3.5 px-3 text-left text-sm font-semibold", "Count")
              )
            ),
            tbody(
              cls := "divide-y divide-gray-200",
              model.items.splitBy(_.id) { (_, item) =>
                tr(
                  td(
                    cls := "py-4 px-3 text-sm whitespace-nowrap",
                    span(
                      " Count: ",
                      model.count.toString,
                      " Name: ",
                      item.entry.foo.bar,
                      s" $i "
                    )
                  ),
                  td(
                    cls := "py-4 px-3 text-sm whitespace-nowrap",
                    " ",
                    model.count.toString,
                    " "
                  )
                )
              }
            )
          )
        )
      )
    )

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("sandbox") { (model, event, _) =>
      E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  private def normalizeTab(tab: String): String =
    tab match
      case "all_keyed" | "rows_keyed" | "no_keyed" => tab
      case _                                       => "all_keyed"

  private def tabClass(activeTab: String, tab: String): String =
    if activeTab == tab then "tab tab-active" else "tab"

  private def randomItems(size: Int): List[Item] =
    Random
      .shuffle((1 to size).toList)
      .map(id =>
        Item(
          id = id,
          entry = Entry(Foo(s"item-$id"))
        )
      )
end KeyedComprehensionLiveView

object KeyedComprehensionLiveView:

  enum Msg:
    case Randomize

  final case class UrlParams(tab: Option[String]) derives Schema

  val ParamsCodec: LiveQueryCodec[UrlParams] =
    LiveQueryCodec[UrlParams]

  final case class Model(activeTab: String, items: List[Item], count: Int)

  final case class Item(id: Int, entry: Entry)
  final case class Entry(foo: Foo)
  final case class Foo(bar: String)
