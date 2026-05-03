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
    case Msg.ToggleModal => model.copy(renderModal = !model.renderModal)
    case Msg.NestedEvent =>
      model.copy(nestedEventCount = model.nestedEventCount + 1)
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
        button(phx.onClick(showModal("my-modal")), "Open modal"),
        button(phx.onClick(Msg.ToggleModal), "Toggle modal render"),
        button(phx.onClick(showModal("my-modal-2")), "Open second modal"),
        button(phx.onClick(Msg.Tick), "Tick"),
        button(phx.onClick(JS.navigate("/form")), "Live navigate"),
        nestedEventControls(model),
        tooltip("tooltip-example-portal", "Hover me", portal = true, model.count),
        tooltip("tooltip-example-no-portal", "Hover me (no portal)", portal = false, model.count)
      ),
      Option.when(model.renderModal)(
        portal("portal-source", target = "#root-portal")(
          modal("my-modal", model.count, first = true),
          div(idAttr := "hook-test", phx.hook := "InsidePortal", "This should get a data attribute")
        )
      ),
      portal("portal-with-live-component", target = "#root-portal")(liveComponentLike(model)),
      portal("nested-lv-button", target = "body")(
        button(phx.onClick(Msg.NestedEvent), "Trigger event in nested LV (from teleported button)")
      ),
      portal("nested-lv", target = "body")(nestedTeleportedLiveView),
      portal("tooltip-example-portal-portal", target = "body")(
        tooltipBody("tooltip-example-portal", model.count)
      ),
      portal("portal-source-2", target = "#app-portal")(
        modal("my-modal-2", model.count, first = false)
      ),
      div(
        idAttr := "root-portal"
      ),
      div(
        idAttr := "app-portal"
      )
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
    case NestedEvent
    case PrependItem
    case Tick

  final case class Model(
    param: Option[String] = None,
    count: Int = 0,
    renderModal: Boolean = true,
    nestedEventCount: Int = 0,
    items: Vector[(Int, String)] = Vector(1 -> "Item 1", 2 -> "Item 2"),
    nextItem: Int = 1000)

  private def modal(id: String, count: Int, first: Boolean) =
    div(
      idAttr    := id,
      styleAttr := "display: none;",
      div(idAttr := s"$id-bg", styleAttr := "display: none;"),
      div(
        div(
          idAttr    := s"$id-container",
          styleAttr := "display: none;",
          div(
            idAttr := s"$id-content",
            if first then
              Vector(
                span("This is a modal."),
                p("DOM patching works as expected: ", count.toString)
              )
            else Vector(span("This is a second modal."))
          )
        )
      )
    )

  private def showModal(id: String) =
    JS.show(to = s"#$id").show(to = s"#$id-bg").show(to = s"#$id-container")

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
