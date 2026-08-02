import NavigationLiveViews.*
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.LiveIO.given

class NavigationALiveView() extends LiveView.Routed[Msg, Model, AParams]:

  def mount(_params: AParams, ctx: MountContext) =
    Model(paramCurrent = None, paramNext = 1)

  override def handleParams(model: Model, params: AParams, _url: URL, ctx: ParamsContext) =
    model.copy(paramCurrent = params.param.map(_.toString))

  def handleMessage(model: Model, ctx: MessageContext) =
    _ => model

  def render(model: Model) =
    NavigationLayout(
      div(
        h1("This is page A"),
        p("Current param: ", model.paramCurrent.getOrElse("")),
        link.pushPatch(
          E2ERoutes.navigationA.location(AParams(Some(model.paramNext))),
          cls := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch this LiveView"
        ),
        link.replacePatch(
          E2ERoutes.navigationA.location(AParams(Some(model.paramNext))),
          cls := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch (Replace)"
        ),
        link.pushNavigate(
          E2ERoutes.navigationB.location(BParams(false)).withFragment("items-item-42"),
          cls := "inline-flex rounded bg-slate-200 px-4 py-2",
          "Navigate to 42"
        )
      )
    )

end NavigationALiveView

class NavigationBLiveView() extends LiveView.Routed[Msg, Model, BParams]:

  def mount(_params: BParams, ctx: MountContext) =
    Model(items = (1 to 100).toList.map(i => Item(s"item-$i", i)), withContainer = false)

  def handleMessage(model: Model, ctx: MessageContext) =
    _ => model

  override def handleParams(model: Model, params: BParams, _url: URL, ctx: ParamsContext) =
    val _             = ctx
    val containerFlow = params.withContainerRequested || model.withContainer
    val selectedItem  = if containerFlow then params.itemId else None
    model.copy(
      withContainer = params.withContainerRequested,
      selectedItem = selectedItem
    )

  def render(model: Model) =
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
                  link.pushPatch(
                    E2ERoutes.navigationBItemLocation.location(
                      item.id -> Option.when(model.withContainer)("1")
                    ),
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
end NavigationBLiveView

class RedirectLoopLiveView() extends LiveView.Routed[Msg, Model, RedirectLoopParams]:

  def mount(_params: RedirectLoopParams, ctx: MountContext) =
    Model(shouldLoop = false, message = None)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.TriggerLoop =>
      model.copy(message = Some("Too many redirects"), shouldLoop = false)
    case _ => model

  override def handleParams(
    model: Model,
    params: RedirectLoopParams,
    _url: URL,
    ctx: ParamsContext
  ) =
    if params.loop.contains(true) then
      if model.shouldLoop then ctx.nav.pushPatchUnsafe("?loop=true").as(model)
      else model.copy(message = Some("Too many redirects"), shouldLoop = false)
    else model.copy(message = None, shouldLoop = true)

  def render(model: Model) =
    NavigationLayout(
      div(
        if model.message.nonEmpty then
          div(
            idAttr := "message",
            model.message.getOrElse("")
          )
        else "",
        link.pushPatchUnsafe(
          "?loop=true",
          "Redirect Loop"
        )
      )
    )

end RedirectLoopLiveView

object NavigationLiveViews:

  enum Msg:
    case TriggerLoop
    case Noop

  final case class AParams(param: Option[Int]) derives Schema

  final case class BParams(withContainerRequested: Boolean, itemId: Option[String] = None)

  final case class RedirectLoopParams(loop: Option[Boolean]) derives Schema

  final case class Item(id: String, name: Int)
  final case class Model(
    paramCurrent: Option[String] = None,
    paramNext: Int = 1,
    items: List[Item] = Nil,
    selectedItem: Option[String] = None,
    withContainer: Boolean = false,
    shouldLoop: Boolean = false,
    message: Option[String] = None)
