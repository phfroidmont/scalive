import ColocatedLiveView.*

import scalive.*
import scalive.LiveIO.given

class ColocatedLiveView extends LiveView[Msg, Model]:

  def mount(ctx: MountContext) =
    Model()

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.SubmitPhone(phone) => model.copy(phone = phone)
    case Msg.PushJs             => ctx.client.exec(JS.toggle(to = "#hello")).as(model)

  def render(model: Model) =
    div(
      form(
        phx.onSubmit(params => Msg.SubmitPhone(params.getOrElse("user[phone_number]", ""))),
        input(
          typ         := "text",
          idAttr      := "user-phone-number",
          nameAttr    := "user[phone_number]",
          phx.hook    := "PhoneNumber",
          placeholder := "phone"
        )
      ),
      p(idAttr := "phone", model.phone),
      div(
        idAttr    := "runtime",
        phx.hook  := "Runtime",
        styleAttr := "display: none;",
        "Runtime hook works!"
      ),
      button(phx.onClick(Msg.PushJs), "Push JS from server"),
      h1(idAttr := "hello", "Hello!"),
      rawHtml(
        """
          |<pre>def plain_example(), do: :ok</pre>
          |<pre>
          |  Current temperature: <span class="na">@temperature</span>
          |  <span class="nt">button</span>
          |</pre>
          |""".stripMargin
      )
    )
end ColocatedLiveView

object ColocatedLiveView:
  enum Msg:
    case SubmitPhone(phone: String)
    case PushJs

  final case class Model(phone: String = "")
