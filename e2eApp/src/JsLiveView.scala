import JsLiveView.*
import zio.ZIO

import scalive.*

class JsLiveView extends LiveView[Msg, Model]:

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(count = 0))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Increment => ZIO.succeed(model.copy(count = model.count + 1))

  override def view(model: Signal[Model]) =
    div(
      div(
        Modal.attr,
        aria.expanded := false,
        styleAttr     := "display: none;",
        "Test"
      ),
      button(
        on.click(
          JS.show(to = Modal.selector, transition = "fade-in", time = 50)
            .setAttribute(("aria-expanded", "true"), to = Modal.selector)
            .setAttribute(("open", "true"), to = Modal.selector)
        ),
        "show modal"
      ),
      button(
        on.click(
          JS.hide(to = Modal.selector, transition = "fade-out", time = 50)
            .setAttribute(("aria-expanded", "false"), to = Modal.selector)
            .removeAttribute("open", to = Modal.selector)
        ),
        "hide modal"
      ),
      button(
        on.click(
          JS.toggle(to = Modal.selector, in = "fade-in", out = "fade-out", time = 50)
            .toggleAttribute("aria-expanded", "true", "false", to = Modal.selector)
            .toggleAttribute("open", "true", to = Modal.selector)
        ),
        "toggle modal"
      ),
      detailsTag(
        dom.onMount(JS.ignoreAttributes(Seq("open"))),
        summaryTag("Details"),
        button(
          on.click(Msg.Increment),
          model.map(_.count.toString)
        )
      )
    )

end JsLiveView

object JsLiveView:

  private val Modal = DomRef("my-modal")

  enum Msg:
    case Increment

  final case class Model(count: Int)
