import JsLiveView.*

import scalive.*
import scalive.LiveIO.given

class JsLiveView extends LiveView[Msg, Model]:

  def mount(ctx: MountContext) =
    Model(count = 0)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Increment => model.copy(count = model.count + 1)

  def render(model: Model) =
    div(
      div(
        Modal.attr,
        aria.expanded := false,
        styleAttr     := "display: none;",
        "Test"
      ),
      button(
        phx.onClick(
          JS.show(to = Modal.selector, transition = "fade-in", time = 50)
            .setAttribute(("aria-expanded", "true"), to = Modal.selector)
            .setAttribute(("open", "true"), to = Modal.selector)
        ),
        "show modal"
      ),
      button(
        phx.onClick(
          JS.hide(to = Modal.selector, transition = "fade-out", time = 50)
            .setAttribute(("aria-expanded", "false"), to = Modal.selector)
            .removeAttribute("open", to = Modal.selector)
        ),
        "hide modal"
      ),
      button(
        phx.onClick(
          JS.toggle(to = Modal.selector, in = "fade-in", out = "fade-out", time = 50)
            .toggleAttribute("aria-expanded", "true", "false", to = Modal.selector)
            .toggleAttribute("open", "true", to = Modal.selector)
        ),
        "toggle modal"
      ),
      detailsTag(
        phx.onMounted(JS.ignoreAttributes(Seq("open"))),
        summaryTag("Details"),
        button(
          phx.onClick(Msg.Increment),
          model.count.toString
        )
      )
    )

end JsLiveView

object JsLiveView:

  private val Modal = DomRef("my-modal")

  enum Msg:
    case Increment

  final case class Model(count: Int)
