import scala.util.Random

import KeyedComprehensionLiveView.*
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.LiveIO.given

class KeyedComprehensionLiveView(assets: StaticAssets)
    extends LiveView.Routed[Msg, Model, UrlParams]:

  def mount(_params: UrlParams, ctx: MountContext) =
    Model(
      activeTab = "all_keyed",
      items = randomItems(10),
      size = 10,
      count = 0
    )

  override def handleParams(model: Model, params: UrlParams, _url: URL, ctx: ParamsContext) =
    val _   = ctx
    val tab = params.tab.getOrElse("all_keyed")
    model.copy(activeTab = normalizeTab(tab))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Randomize => model.copy(items = randomItems(model.size), count = model.count + 1)
    case Msg.ChangeSize(size) =>
      model.copy(items = randomItems(size), size = size, count = model.count + 1)
    case Msg.ChangeFirst =>
      val first = Item(2000, Entry(other = "hey", foo = Foo(System.nanoTime.toString)))
      model.copy(items = first +: model.items.drop(1))
    case Msg.ChangeOther =>
      model.copy(items =
        model.items.map(item =>
          item.copy(entry = item.entry.copy(other = s"hey ${System.nanoTime}"))
        )
      )

  override def view(model: Signal[Model]) =
    val activeTab = model.map(_.activeTab)
    val count     = model.map(_.count)

    div(
      assets.stylesheet("daisy.css", typ := "text/css"),
      cls := "p-8",
      div(
        cls := "border-b border-gray-200 mb-6",
        navTag(
          role := "tablist",
          cls  := "tabs tabs-border",
          link.pushPatch(
            E2ERoutes.keyedComprehension.location(UrlParams(Some("all_keyed"))),
            cls := activeTab.map(tabClass(_, "all_keyed")),
            "All keyed"
          ),
          link.pushPatch(
            E2ERoutes.keyedComprehension.location(UrlParams(Some("rows_keyed"))),
            cls := activeTab.map(tabClass(_, "rows_keyed")),
            "Rows keyed"
          ),
          link.pushPatch(
            E2ERoutes.keyedComprehension.location(UrlParams(Some("no_keyed"))),
            cls := activeTab.map(tabClass(_, "no_keyed")),
            "No keyed"
          )
        )
      ),
      button(cls := "btn", on.click(Msg.Randomize), "randomize"),
      button(cls := "btn", on.click(Msg.ChangeFirst), "change first"),
      button(cls := "btn", on.click(Msg.ChangeOther), "change other"),
      form(
        input(
          on.change(params =>
            Msg.ChangeSize(params.get("size").flatMap(_.toIntOption).getOrElse(0))
          ),
          nameAttr := "size",
          value    := model.map(_.size.toString)
        )
      ),
      (1 to 2).map(i =>
        activeTab.choose(
          "all_keyed" -> renderTable(
            i,
            model.map(_.items),
            count,
            keyedRows = true,
            keyedCells = true
          ),
          "rows_keyed" -> renderTable(
            i,
            model.map(_.items),
            count,
            keyedRows = true,
            keyedCells = false
          ),
          "no_keyed" -> renderTable(
            i,
            model.map(_.items),
            count,
            keyedRows = false,
            keyedCells = false
          )
        )
      )
    )
  end view

  private def renderTable(
    i: Int,
    items: Signal[List[Item]],
    count: Signal[Int],
    keyedRows: Boolean,
    keyedCells: Boolean
  ) =
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
              renderRows(items, count, keyedRows, keyedCells, i)
            )
          )
        )
      )
    )

  private def renderRows(
    items: Signal[List[Item]],
    count: Signal[Int],
    keyedRows: Boolean,
    keyedCells: Boolean,
    i: Int
  ): Mod[Msg] =
    if keyedRows then
      items.splitBy(_.id) { (itemId, item) =>
        renderRow(count, keyedCells, Some(itemId), item, i)
      }
    else
      items.splitByIndex { (_, item) =>
        renderRow(count, keyedCells, None, item, i)
      }

  private def renderRow(
    count: Signal[Int],
    keyedCells: Boolean,
    itemId: Option[Int],
    item: Signal[Item],
    i: Int
  ) =
    val cells = Vector(
      "1" -> renderCell(
        prefix = " Count: ",
        count = count.map(_.toString),
        separator = " Name: ",
        name = item.map(_.entry.foo.bar),
        suffix = s" $i "
      ),
      "2" -> renderCell(
        prefix = " ",
        count = count.map(_.toString),
        separator = "",
        name = item.map(_ => ""),
        suffix = " "
      )
    )

    tr(
      if keyedCells then
        cells.splitBy { case (slotId, _) => s"${itemId.get}_$slotId" }((_, cell) => cell._2)
      else cells.map(_._2)
    )

  private def renderCell(
    prefix: String,
    count: Signal[String],
    separator: String,
    name: Signal[String],
    suffix: String
  ) =
    td(
      cls := "py-4 px-3 text-sm whitespace-nowrap",
      span(prefix, count, separator, name, suffix)
    )

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.onRawEvent { (model, event, _) =>
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
      .shuffle((1 to (size * 2)).toList)
      .take(size)
      .map(id =>
        Item(
          id = id,
          entry = Entry(other = "hey", foo = Foo(s"New${id + 1}"))
        )
      )
end KeyedComprehensionLiveView

object KeyedComprehensionLiveView:

  enum Msg:
    case Randomize
    case ChangeSize(size: Int)
    case ChangeFirst
    case ChangeOther

  final case class UrlParams(tab: Option[String]) derives Schema

  final case class Model(activeTab: String, items: List[Item], size: Int, count: Int)

  final case class Item(id: Int, entry: Entry)
  final case class Entry(other: String, foo: Foo)
  final case class Foo(bar: String)
