import zio.stream.ZStream

import scalive.*

import JsLiveView.*

class JsLiveView extends LiveView[Msg, Model]:

  def init = Model(count = 0)

  def update(model: Model) =
    case Msg.Increment => model.copy(count = model.count + 1)

  def view(model: Dyn[Model]) =
    div(
      div(
        idAttr        := "my-modal",
        aria.expanded := false,
        styleAttr     := "display: none;",
        "Test"
      ),
      button(
        phx.onClick(
          JS.show(to = "#my-modal", transition = "fade-in", time = 50)
            .setAttribute(("aria-expanded", "true"), to = "#my-modal")
            .setAttribute(("open", "true"), to = "#my-modal")
        ),
        "show modal"
      ),
      button(
        phx.onClick(
          JS.hide(to = "#my-modal", transition = "fade-out", time = 50)
            .setAttribute(("aria-expanded", "false"), to = "#my-modal")
            .removeAttribute("open", to = "#my-modal")
        ),
        "hide modal"
      ),
      button(
        phx.onClick(
          JS.toggle(to = "#my-modal", in = "fade-in", out = "fade-out", time = 50)
            .toggleAttribute("aria-expanded", "true", "false", to = "#my-modal")
            .toggleAttribute("open", "true", to = "#my-modal")
        ),
        "toggle modal"
      ),
      detailsTag(
        phx.onMounted(JS.ignoreAttributes(Seq("open"))),
        summaryTag("Details"),
        button(
          phx.onClick(Msg.Increment),
          model(_.count.toString)
        )
      )
    )

  def subscriptions(model: Model) = ZStream.empty
end JsLiveView

object JsLiveView:

  enum Msg:
    case Increment

  final case class Model(count: Int)
