import NavigationLiveViews.*
import zio.http.URL
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.LiveIO.given
import scalive.Signal.*

class NavigationALiveView() extends LiveView.Routed[Msg, Model, AParams]:

  def mount(_params: AParams, ctx: MountContext) =
    Model(paramCurrent = None, paramNext = 1)

  override def handleParams(model: Model, params: AParams, _url: URL, ctx: ParamsContext) =
    model.copy(paramCurrent = params.param.map(_.toString))

  def handleMessage(model: Model, ctx: MessageContext) =
    _ => model

  override def view(model: Signal[Model]) =
    val nextLocation =
      model.map(current => E2ERoutes.navigationA.location(AParams(Some(current.paramNext))))

    NavigationLayout(
      div(
        h1("This is page A"),
        p("Current param: ", model.map(_.paramCurrent.getOrElse(""))),
        a(
          href                       := nextLocation.map(_.href),
          dataAttr("phx-link")       := "patch",
          dataAttr("phx-link-state") := "push",
          cls                        := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
          "Patch this LiveView"
        ),
        a(
          href                       := nextLocation.map(_.href),
          dataAttr("phx-link")       := "patch",
          dataAttr("phx-link-state") := "replace",
          cls                        := "inline-flex rounded bg-slate-200 px-4 py-2 mr-2",
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

  override def view(model: Signal[Model]) =
    val withContainer = model.map(_.withContainer)
    val selectedItem  = model.map(_.selectedItem)

    NavigationLayout(
      div(
        h1("This is page B"),
        a(
          href := "#items-item-42",
          cls  := "mb-2 inline-flex rounded bg-slate-200 px-4 py-2",
          "Go to 42."
        ),
        selectedItem
          .map(_.isEmpty).when(
            div(
              idAttr    := "my-scroll-container",
              styleAttr := withContainer.map(enabled =>
                if enabled then
                  "height: 85vh; overflow-y: scroll; width: 100%; border: 1px solid #e2e8f0; border-radius: 0.375rem;"
                else "width: 100%; border: 1px solid #e2e8f0; border-radius: 0.375rem;"
              ),
              ul(
                idAttr    := "items",
                styleAttr := "padding: 1rem; list-style: none;",
                model.map(_.items).splitBy(_.id) { (_, item) =>
                  li(
                    idAttr    := item.map(current => s"items-${current.id}"),
                    styleAttr := "padding: 0.5rem; border-bottom: 1px solid #e2e8f0;",
                    a(
                      href := item.zip(withContainer).map { case (current, enabled) =>
                        E2ERoutes.navigationBItemLocation
                          .location(
                            current.id -> Option.when(enabled)("1")
                          ).href
                      },
                      dataAttr("phx-link")       := "patch",
                      dataAttr("phx-link-state") := "push",
                      "Item ",
                      item.map(_.name.toString)
                    )
                  )
                }
              )
            )
          ),
        selectedItem.option(item =>
          div(
            p("Item ", item)
          )
        )
      )
    )
  end view
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

  override def view(model: Signal[Model]) =
    NavigationLayout(
      div(
        model
          .map(_.message).option(message =>
            div(
              idAttr := "message",
              message
            )
          ),
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
