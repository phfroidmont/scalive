import zio.*
import zio.http.URL
import zio.json.*
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.derived

import scalive.*
import scalive.LiveIO.given
import scalive.Signal.*

class PortalLiveView
    extends LiveView.Routed[PortalLiveView.Msg, PortalLiveView.Model, PortalLiveView.QueryParams]:
  import PortalLiveView.*

  def mount(_params: QueryParams, ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: QueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(param = params.param)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.onRawEvent { (model, event, _) =>
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
    case Msg.NestedPortalClick =>
      model.copy(nestedPortalCount = model.nestedPortalCount + 1)
    case Msg.Tick => model.copy(count = model.count + 1)

  override def view(model: Signal[Model]) =
    val count = model.map(_.count)

    div(
      mainTag(
        styleAttr := "flex: 1; padding: 2rem;",
        h1("Modal example"),
        p("Current param: ", model.map(_.param.getOrElse(""))),
        button(
          on.click(
            JS.pushPatch(
              count.map(value => E2ERoutes.portal.location(QueryParams(Some((value + 1).toString))))
            )
          ),
          "Patch this LiveView"
        ),
        button(on.click(showModal("my-modal")), "Open modal"),
        button(on.click(Msg.ToggleModal), "Toggle modal render"),
        button(
          on.click(
            showModal("my-modal-2").show(to = DomSelector.css("#inner-red-box"))
          ),
          "Open second modal"
        ),
        button(on.click(Msg.Tick), "Tick"),
        button(on.click(JS.pushNavigate(E2ERoutes.formLocation.location)), "Live navigate"),
        liveView("nested", NestedLive()),
        tooltip("tooltip-example-portal", "Hover me", portal = true, count),
        tooltip("tooltip-example-no-portal", "Hover me (no portal)", portal = false, count),
        nestedPortalExample(model),
        button(on.click(showModal("non-teleported-modal")), "Open non-teleported modal")
      ),
      model
        .map(_.renderModal).when(
          portal("portal-source", target = DomSelector.css("#root-portal"))(
            modal("my-modal", count, first = true),
            div(
              dom.hook("InsidePortal", DomRef("hook-test")),
              "This should get a data attribute"
            )
          )
        ),
      portal("portal-with-live-component", target = DomSelector.css("#root-portal"))(
        liveComponent(LiveComponentFixture, id = "lc", props = ())
      ),
      portal("tooltip-example-portal-portal", target = DomSelector.css("body"))(
        tooltipBody("tooltip-example-portal", count)
      ),
      portal("portal-source-2", target = DomSelector.css("#app-portal"))(
        modal("my-modal-2", count, first = false, inner = Vector(secondModalInnerPortal))
      ),
      modal("non-teleported-modal", count, first = false, inner = nonTeleportedModalMenu),
      div(idAttr := "app-portal")
    )
  end view
end PortalLiveView

object PortalLiveView:
  private val mainTag = HtmlTag("main")

  final case class QueryParams(param: Option[String] = None) derives Schema

  enum Msg:
    case ToggleModal
    case ToggleNestedPortals
    case NestedPortalClick
    case Tick

  final case class Model(
    param: Option[String] = None,
    count: Int = 0,
    renderModal: Boolean = true,
    renderNestedPortals: Boolean = true,
    nestedPortalCount: Int = 0)

  private def modal(
    id: String,
    count: Signal[Int],
    first: Boolean,
    inner: Vector[Mod[Msg]] = Vector.empty
  ) =
    val copy: Vector[Mod[Msg]] =
      if first then
        Vector(
          span("This is a modal."),
          p("DOM patching works as expected: ", count.map(_.toString)),
          button(
            on.click(
              JS.pushPatch(
                count.map(value =>
                  E2ERoutes.portal.location(QueryParams(Some((value + 1).toString)))
                )
              )
            ),
            "Patch this LiveView"
          )
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
          on.click(hideModal(id))
        ),
        div(
          idAttr    := s"$id-container",
          styleAttr := "display: none; position: relative; z-index: 1; margin: 4rem;",
          on.clickAway(hideModal(id)),
          div(idAttr := s"$id-content", copy, inner)
        )
      )
    )
  end modal

  private def showModal(id: String) =
    JS.show(to = DomSelector.css(s"#$id"))
      .show(to = DomSelector.css(s"#$id-bg"))
      .show(to = DomSelector.css(s"#$id-container"))

  private def hideModal(id: String) =
    JS.hide(to = DomSelector.css(s"#$id-container"))
      .hide(to = DomSelector.css(s"#$id-bg"))
      .hide(to = DomSelector.css(s"#$id"))

  private def nestedPortalExample(model: Signal[Model]) =
    div(
      cls := "border border-purple-600 mt-8 p-4",
      h2("Nested Portal Test"),
      button(on.click(Msg.ToggleNestedPortals), "Toggle nested portals"),
      p(
        "Nested portal count: ",
        span(idAttr := "nested-portal-count", model.map(_.nestedPortalCount.toString))
      ),
      model
        .map(_.renderNestedPortals).when(
          portal("nested-portal-source", target = DomSelector.css("#root-portal"))(
            div(
              idAttr := "outer-portal",
              h3("Outer Portal"),
              portal("inner-portal-source", target = DomSelector.css("body"))(
                div(
                  idAttr := "inner-portal",
                  h4("Inner Portal (nested inside outer)"),
                  p(idAttr := "nested-portal-content", "Tick count: ", model.map(_.count.toString)),
                  button(on.click(Msg.NestedPortalClick), "Click nested portal button")
                )
              )
            )
          )
        )
    )

  private def nonTeleportedModalMenu: Vector[Mod[Msg]] =
    Vector(
      button(
        on.click(JS.show(to = DomSelector.css("#teleported-menu-content"))),
        "Open menu"
      ),
      portal("teleported-menu", target = DomSelector.css("body"))(
        div(
          idAttr    := "teleported-menu-content",
          cls       := "hidden z-[100] fixed top-0 left-0 border border-red-500 p-4 bg-white",
          styleAttr := "display: none; position: fixed; top: 0; left: 0; z-index: 1000;",
          button(
            on.click(JS.hide(to = DomSelector.css("#teleported-menu-content"))),
            "Close menu"
          )
        )
      )
    )

  private def secondModalInnerPortal =
    portal(
      "modal-2-inner-portal",
      target = DomSelector.css("#my-modal-2-content"),
      wrapperClass = Some("contents")
    )(
      div(
        cls := "size-96 bg-gray-300 absolute top-0 right-0",
        styleAttr := "width: 24rem; height: 24rem; background-color: rgb(209 213 219); position: absolute; top: 0; right: 0;",
        portal(
          "modal-2-inner-portal-2",
          target = DomSelector.css("#my-modal-2-content"),
          wrapperClass = Some("contents")
        )(
          div(
            idAttr := "inner-red-box",
            cls    := "absolute top-0 right-0 bg-red-500 size-32",
            styleAttr := "display: none; width: 8rem; height: 8rem; background-color: rgb(239 68 68); position: absolute; top: 0; right: 0;",
            on.clickAway(JS.hide()),
            "test"
          )
        )
      )
    )

  private def tooltip(id: String, label: String, portal: Boolean, count: Signal[Int]) =
    div(
      dom.hook("PortalTooltip", DomRef(s"$id-wrapper")),
      dataAttr("id")   := id,
      dataAttr("show") := JS.show(to = DomSelector.css(s"#$id"), blocking = false).toJson,
      dataAttr("hide") := JS.hide(to = DomSelector.css(s"#$id"), blocking = false).toJson,
      button(idAttr := s"$id-activator", label),
      if portal then "" else tooltipBody(id, count)
    )

  private def tooltipBody(id: String, count: Signal[Int]) =
    div(
      idAttr    := id,
      role      := "tooltip",
      styleAttr := "display: none; position: absolute; top: 0; left: 0;",
      "Hey there! ",
      count.map(_.toString)
    )

  private def evalCode(value: Json): String =
    value match
      case Json.Obj(fields) =>
        fields.collectFirst { case ("value", Json.Str(code)) => code }.getOrElse("")
      case _ => ""

  class NestedLive extends LiveView[NestedLive.Msg.type, Int]:
    def mount(ctx: MountContext) =
      0

    def handleMessage(model: Int, ctx: MessageContext) =
      (_: NestedLive.Msg.type) => model + 1

    override def view(count: Signal[Int]) =
      div(
        cls := "border border-orange-200",
        h1("Nested LiveView"),
        p(idAttr := "nested-event-count", count.map(_.toString)),
        button(on.click(NestedLive.Msg), "Trigger event in nested LV"),
        portal("nested-lv-button", target = DomSelector.css("body"))(
          button(
            on.click(NestedLive.Msg),
            "Trigger event in nested LV (from teleported button)"
          )
        ),
        portal("nested-lv", target = DomSelector.css("body"))(
          liveView("nested-teleported", NestedTeleportedLive())
        )
      )

  object NestedLive:
    case object Msg

  class NestedTeleportedLive extends LiveView[NestedTeleportedLive.Msg.type, Unit]:
    def mount(ctx: MountContext) =
      ()

    def handleMessage(model: Unit, ctx: MessageContext) =
      (_: NestedTeleportedLive.Msg.type) => model

    override def view(model: Signal[Unit]) =
      div(
        cls := "border border-green-200",
        h1("Nested teleport LiveView"),
        button(on.click(NestedTeleportedLive.Msg), "Toggle event in teleported LV")
      )

  object NestedTeleportedLive:
    case object Msg

  object LiveComponentFixture
      extends LiveComponent[
        Unit,
        LiveComponentFixture.Msg.type,
        LiveComponentFixture.Model
      ]:
    import LiveComponentFixture.*

    def mount(props: Unit, ctx: MountContext) =
      ctx.streams.create(ItemsStream, InitialItems).map(items => Model(items = items))

    def handleMessage(props: Unit, model: Model, ctx: MessageContext) =
      (_: Msg.type) => prependItem(model, ctx.streams)

    override def hooks: ComponentLiveHooks[Unit, Msg.type, Model] =
      ComponentLiveHooks.empty.onRawEvent { (_, model, event, ctx) =>
        if event.bindingId == "prepend" then
          prependItem(model, ctx.streams).map(LiveEventHookResult.halt)
        else LiveEventHookResult.cont(model)
      }

    override def view(
      props: Signal[Unit],
      model: Signal[Model],
      self: ComponentRef[Msg.type]
    ) =
      div(
        idAttr := "teleported-lc",
        cls    := "border border-red-200",
        h1("LiveComponent"),
        ul(
          idAttr     := "stream-in-lc",
          phx.update := PhxUpdate.Stream,
          model.map(_.items).stream((domId, item) => li(idAttr := domId, item.map(_.name)))
        ),
        button(on.click(Msg), phx.target(self), "Prepend item"),
        portal("teleported-from-lc-button", target = DomSelector.css("body"))(
          button(
            dom.hook("TeleportedLCButton", DomRef("lcbtn")),
            "Prepend item (teleported)"
          )
        )
      )

    private def prependItem(model: Model, streams: Streams) =
      val item = Item(model.nextItem, s"Item ${model.nextItem}")
      streams
        .insert(ItemsStream, item, at = StreamAt.First)
        .map(items => model.copy(items = items, nextItem = model.nextItem + 1))

    final case class Item(id: Int, name: String)
    final case class Model(
      items: LiveStream[Item],
      nextItem: Int = 1000)

    case object Msg

    private val ItemsStream  = LiveStreamDef.byId[Item, Int]("items")(_.id)
    private val InitialItems = Vector(Item(1, "Item 1"), Item(2, "Item 2"))
  end LiveComponentFixture
end PortalLiveView
