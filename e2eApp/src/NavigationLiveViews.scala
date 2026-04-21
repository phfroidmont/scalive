import java.net.URI

import NavigationLiveViews.*
import zio.stream.ZStream

import scalive.*

class NavigationALiveView(initialParam: Option[String]) extends LiveView[Msg, Model]:

  def init = Model(paramCurrent = initialParam, paramNext = 1)

  def update(model: Model) =
    case _ => model

  def view(model: Model) =
    NavigationLayout(
      div(
        h1("This is page A"),
        p("Current param: ", model.paramCurrent.getOrElse("")),
        link.patch(
          s"/navigation/a?param=${model.paramNext}",
          cls := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch this LiveView"
        ),
        link.patchReplace(
          s"/navigation/a?param=${model.paramNext}",
          cls := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch (Replace)"
        ),
        link.navigate(
          "/navigation/b#items-item-42",
          cls := "inline-flex rounded bg-slate-200 px-4 py-2",
          "Navigate to 42"
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end NavigationALiveView

class NavigationBLiveView(withContainer: Boolean) extends LiveView[Msg, Model]:

  def init =
    Model(items = (1 to 100).toList.map(i => Item(s"item-$i", i)), withContainer = withContainer)

  def update(model: Model) =
    case Msg.Noop => model
    case _        => model

  override def handleParams(model: Model, params: Map[String, String], uri: URI) =
    val withContainer = params.get("container").contains("1")
    val containerFlow = withContainer || model.withContainer
    val selectedItem  =
      if containerFlow then
        val path = Option(uri.getPath).filter(_.nonEmpty).getOrElse("")
        path.stripPrefix("/navigation/b/") match
          case ""                   => None
          case same if same == path => None
          case id                   => Some(id)
      else None
    model.copy(
      withContainer = withContainer,
      selectedItem = selectedItem
    )

  def view(model: Model) =
    NavigationLayout(
      div(
        h1("This is page B"),
        a(
          href := "#items-item-42",
          cls  := "mb-2 inline-flex rounded bg-slate-200 px-4 py-2",
          "Go to 42."
        ),
        if model.selectedItem.isEmpty then
          div(
            idAttr    := "my-scroll-container",
            styleAttr :=
              (if model.withContainer then
                 "height: 85vh; overflow-y: scroll; width: 100%; border: 1px solid #e2e8f0; border-radius: 0.375rem;"
               else "width: 100%; border: 1px solid #e2e8f0; border-radius: 0.375rem;"),
            ul(
              idAttr    := "items",
              styleAttr := "padding: 1rem; list-style: none;",
              model.items.splitBy(_.id) { (_, item) =>
                li(
                  idAttr    := s"items-${item.id}",
                  styleAttr := "padding: 0.5rem; border-bottom: 1px solid #e2e8f0;",
                  link.patch(
                    s"/navigation/b/${item.id}",
                    "Item ",
                    item.name.toString
                  )
                )
              }
            )
          )
        else "",
        if model.selectedItem.nonEmpty then
          div(
            p("Item ", model.selectedItem.getOrElse(""))
          )
        else ""
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end NavigationBLiveView

class RedirectLoopLiveView(loop: Boolean) extends LiveView[Msg, Model]:
  def init =
    if loop then Model(shouldLoop = false, message = Some("Too many redirects"))
    else Model(shouldLoop = true, message = None)

  def update(model: Model) =
    case Msg.TriggerLoop => model.copy(message = Some("Too many redirects"), shouldLoop = false)
    case _               => model

  override def handleParams(model: Model, params: Map[String, String], uri: URI) =
    if params.get("loop").contains("true") && model.shouldLoop then
      val path  = Option(uri.getPath).getOrElse("")
      val query = Option(uri.getRawQuery).getOrElse("")
      val to    = if query.isEmpty then path else s"$path?$query"
      LiveContext.pushPatch(to).as(model)
    else model

  def view(model: Model) =
    NavigationLayout(
      div(
        if model.message.nonEmpty then
          div(
            idAttr := "message",
            model.message.getOrElse("")
          )
        else "",
        link.patch(
          "/navigation/redirectloop?loop=true",
          "Redirect Loop"
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end RedirectLoopLiveView

object NavigationLiveViews:

  enum Msg:
    case TriggerLoop
    case Noop

  final case class Item(id: String, name: Int)
  final case class Model(
    paramCurrent: Option[String] = None,
    paramNext: Int = 1,
    items: List[Item] = Nil,
    selectedItem: Option[String] = None,
    withContainer: Boolean = false,
    shouldLoop: Boolean = false,
    message: Option[String] = None)
