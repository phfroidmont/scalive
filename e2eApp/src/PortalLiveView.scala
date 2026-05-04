import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json

import scalive.*
import scalive.LiveIO.given

class PortalLiveView extends LiveView[PortalLiveView.Msg, PortalLiveView.Model]:
  import PortalLiveView.*

  override val queryCodec: LiveQueryCodec[QueryParams] = QueryParams.codec

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(param = params.param)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("sandbox") { (model, event, _) =>
      if event.bindingId != "sandbox:eval" then LiveEventHookResult.cont(model)
      else
        evalCode(event.value) match
          case code if code.contains("send(self(), :tick)") =>
            LiveEventHookResult.haltReply(
              model.copy(count = model.count + 1),
              Json.Obj("result" -> Json.Null)
            )
          case _ => E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ToggleModal         => model.copy(renderModal = !model.renderModal)
    case Msg.ToggleNestedPortals =>
      model.copy(renderNestedPortals = !model.renderNestedPortals)
    case Msg.NestedEvent =>
      model.copy(nestedEventCount = model.nestedEventCount + 1)
    case Msg.NestedPortalClick =>
      model.copy(nestedPortalCount = model.nestedPortalCount + 1)
    case Msg.PrependItem =>
      model.copy(
        items = (model.nextItem, s"Item ${model.nextItem}") +: model.items,
        nextItem = model.nextItem + 1
      )
    case Msg.Tick => model.copy(count = model.count + 1)

  def render(model: Model) =
    div(
      mainTag(
        styleAttr := "flex: 1; padding: 2rem;",
        h1("Modal example"),
        p("Current param: ", model.param.getOrElse("")),
        button(phx.onClick(JS.patch(s"/portal?param=${model.count + 1}")), "Patch this LiveView"),
        button(phx.onClick(showModal("my-modal")), "Open modal"),
        button(phx.onClick(Msg.ToggleModal), "Toggle modal render"),
        button(
          phx.onClick(showModal("my-modal-2").show(to = "#inner-red-box")),
          "Open second modal"
        ),
        button(phx.onClick(Msg.Tick), "Tick"),
        button(phx.onClick(JS.navigate("/form")), "Live navigate"),
        nestedEventControls(model),
        tooltip("tooltip-example-portal", "Hover me", portal = true, model.count),
        tooltip("tooltip-example-no-portal", "Hover me (no portal)", portal = false, model.count),
        nestedPortalExample(model),
        button(phx.onClick(showModal("non-teleported-modal")), "Open non-teleported modal")
      ),
      Option.when(model.renderModal)(
        portal("portal-source", target = "#root-portal")(
          modal("my-modal", model.count, first = true),
          div(idAttr := "hook-test", phx.hook := "InsidePortal", "This should get a data attribute")
        )
      ),
      portal("portal-with-live-component", target = "#root-portal")(liveComponentLike(model)),
      portal("teleported-from-lc-button", target = "body")(
        button(phx.onClick(Msg.PrependItem), "Prepend item (teleported)")
      ),
      portal("nested-lv-button", target = "body")(
        button(phx.onClick(Msg.NestedEvent), "Trigger event in nested LV (from teleported button)")
      ),
      portal("nested-lv", target = "body")(nestedTeleportedLiveView),
      portal("tooltip-example-portal-portal", target = "body")(
        tooltipBody("tooltip-example-portal", model.count)
      ),
      portal("portal-source-2", target = "#app-portal")(
        modal("my-modal-2", model.count, first = false, inner = Vector(secondModalInnerPortal))
      ),
      modal("non-teleported-modal", model.count, first = false, inner = nonTeleportedModalMenu),
      div(idAttr := "root-portal"),
      div(idAttr := "app-portal")
    )
end PortalLiveView

object PortalLiveView:
  private val mainTag = HtmlTag("main")

  final case class QueryParams(param: Option[String] = None)

  object QueryParams:
    val codec: LiveQueryCodec[QueryParams] =
      LiveQueryCodec.custom(
        decodeFn = url => Right(QueryParams(param = url.queryParam("param"))),
        encodeFn = params => Right(params.param.fold("?")(param => s"?param=$param"))
      )

  enum Msg:
    case ToggleModal
    case ToggleNestedPortals
    case NestedEvent
    case NestedPortalClick
    case PrependItem
    case Tick

  final case class Model(
    param: Option[String] = None,
    count: Int = 0,
    renderModal: Boolean = true,
    renderNestedPortals: Boolean = true,
    nestedEventCount: Int = 0,
    nestedPortalCount: Int = 0,
    items: Vector[(Int, String)] = Vector(1 -> "Item 1", 2 -> "Item 2"),
    nextItem: Int = 1000)

  private def modal(
    id: String,
    count: Int,
    first: Boolean,
    inner: Vector[Mod[Msg]] = Vector.empty
  ) =
    val copy: Vector[Mod[Msg]] =
      if first then
        Vector(
          span("This is a modal."),
          p("DOM patching works as expected: ", count.toString),
          button(phx.onClick(JS.patch(s"/portal?param=${count + 1}")), "Patch this LiveView")
        )
      else
        val text =
          if id == "non-teleported-modal" then
            "This is a non-teleported modal. Open the menu and click an item. The modal must not close."
          else "This is a second modal."
        Vector(span(text))

    div(
      idAttr    := id,
      styleAttr := "display: none;",
      div(idAttr := s"$id-bg", cls := "fixed", styleAttr := "display: none;"),
      div(
        cls  := "fixed",
        role := "dialog",
        div(
          cls       := "fixed",
          styleAttr := "position: fixed; inset: 0; z-index: 0;",
          phx.onClick(hideModal(id))
        ),
        div(
          idAttr    := s"$id-container",
          styleAttr := "display: none; position: relative; z-index: 1; margin: 4rem;",
          phx.onClickAway(hideModal(id)),
          div(idAttr := s"$id-content", copy, inner)
        )
      )
    )
  end modal

  private def showModal(id: String) =
    JS.show(to = s"#$id").show(to = s"#$id-bg").show(to = s"#$id-container")

  private def hideModal(id: String) =
    JS.hide(to = s"#$id-container").hide(to = s"#$id-bg").hide(to = s"#$id")

  private def nestedEventControls(model: Model) =
    div(
      h1("Nested LiveView"),
      p(idAttr := "nested-event-count", model.nestedEventCount.toString),
      button(phx.onClick(Msg.NestedEvent), "Trigger event in nested LV")
    )

  private def nestedTeleportedLiveView =
    div(
      h1("Nested teleport LiveView"),
      button("Toggle event in teleported LV")
    )

  private def liveComponentLike(model: Model) =
    div(
      idAttr := "teleported-lc",
      h1("LiveComponent"),
      ul(
        idAttr       := "stream-in-lc",
        phx.onUpdate := "stream",
        model.items.map { case (id, label) => li(idAttr := s"items-$id", label) }
      ),
      button(phx.onClick(Msg.PrependItem), "Prepend item")
    )

  private def nestedPortalExample(model: Model) =
    div(
      cls := "border border-purple-600 mt-8 p-4",
      h2("Nested Portal Test"),
      button(phx.onClick(Msg.ToggleNestedPortals), "Toggle nested portals"),
      p(
        "Nested portal count: ",
        span(idAttr := "nested-portal-count", model.nestedPortalCount.toString)
      ),
      Option.when(model.renderNestedPortals)(
        portal("nested-portal-source", target = "#root-portal")(
          div(
            idAttr := "outer-portal",
            h3("Outer Portal"),
            portal("inner-portal-source", target = "body")(
              div(
                idAttr := "inner-portal",
                h4("Inner Portal (nested inside outer)"),
                p(idAttr := "nested-portal-content", "Tick count: ", model.count.toString),
                button(phx.onClick(Msg.NestedPortalClick), "Click nested portal button")
              )
            )
          )
        )
      )
    )

  private def nonTeleportedModalMenu: Vector[Mod[Msg]] =
    Vector(
      button(phx.onClick(JS.show(to = "#teleported-menu-content")), "Open menu"),
      portal("teleported-menu", target = "body")(
        div(
          idAttr    := "teleported-menu-content",
          cls       := "hidden z-[100] fixed top-0 left-0 border border-red-500 p-4 bg-white",
          styleAttr := "display: none; position: fixed; top: 0; left: 0; z-index: 1000;",
          button(phx.onClick(JS.hide(to = "#teleported-menu-content")), "Close menu")
        )
      )
    )

  private def secondModalInnerPortal =
    portal("modal-2-inner-portal", target = "#my-modal-2-content", wrapperClass = Some("contents"))(
      div(
        cls := "size-96 bg-gray-300 absolute top-0 right-0",
        styleAttr := "width: 24rem; height: 24rem; background-color: rgb(209 213 219); position: absolute; top: 0; right: 0;",
        portal(
          "modal-2-inner-portal-2",
          target = "#my-modal-2-content",
          wrapperClass = Some("contents")
        )(
          div(
            idAttr := "inner-red-box",
            cls    := "absolute top-0 right-0 bg-red-500 size-32",
            styleAttr := "display: none; width: 8rem; height: 8rem; background-color: rgb(239 68 68); position: absolute; top: 0; right: 0;",
            phx.onClickAway(JS.hide()),
            "test"
          )
        )
      )
    )

  private def tooltip(id: String, label: String, portal: Boolean, count: Int) =
    div(
      idAttr           := s"$id-wrapper",
      phx.hook         := "PortalTooltip",
      dataAttr("id")   := id,
      dataAttr("show") := JS.show(to = s"#$id", blocking = false).toJson,
      dataAttr("hide") := JS.hide(to = s"#$id", blocking = false).toJson,
      button(idAttr := s"$id-activator", label),
      if portal then "" else tooltipBody(id, count)
    )

  private def tooltipBody(id: String, count: Int) =
    div(
      idAttr    := id,
      role      := "tooltip",
      styleAttr := "display: none; position: absolute; top: 0; left: 0;",
      "Hey there! ",
      count.toString
    )

  private def evalCode(value: Json): String =
    value match
      case Json.Obj(fields) =>
        fields.collectFirst { case ("value", Json.Str(code)) => code }.getOrElse("")
      case _ => ""
end PortalLiveView
