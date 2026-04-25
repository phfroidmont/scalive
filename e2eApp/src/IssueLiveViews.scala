import zio.stream.ZStream

import scalive.*

private val actionAttr = htmlAttr("action", scalive.codecs.StringAsIsEncoder)
private val methodAttr = htmlAttr("method", scalive.codecs.StringAsIsEncoder)

class Issue3719LiveView extends LiveView[Issue3719LiveView.Msg, Issue3719LiveView.Model]:
  import Issue3719LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Change(event) => model.copy(target = event.target.map(_.segments))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    div(
      form(
        phx.onChangeForm(FormCodec.formData)(Msg.Change(_)),
        input(idAttr := "a", typ := "text", nameAttr := "foo"),
        input(idAttr := "b", typ := "text", nameAttr := "foo[bar]")
      ),
      span(idAttr := "target", renderTarget(model.target))
    )

  private def renderTarget(target: Option[Vector[String]]): String =
    target match
      case Some(segments) => segments.map(segment => s"\"$segment\"").mkString("[", ", ", "]")
      case None           => "nil"
end Issue3719LiveView

object Issue3719LiveView:
  final case class Model(target: Option[Vector[String]] = None)
  enum Msg:
    case Change(event: FormEvent[FormData])

class Issue3814LiveView extends LiveView[Issue3814LiveView.Msg, Issue3814LiveView.Model]:
  import Issue3814LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Submit(event) =>
      val submitter = event.submitter.orElse(
        event.raw.get("i-am-the-submitter").map(value => FormSubmitter("i-am-the-submitter", value))
      )
      model.copy(triggerSubmit = true, submitter = submitter)

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    form(
      phx.onSubmitForm(FormCodec.formData)(Msg.Submit(_)),
      phx.triggerAction := model.triggerSubmit,
      actionAttr        := "/submit",
      methodAttr        := "post",
      input(typ := "hidden", nameAttr := "greeting", value := "hello"),
      model.submitter.map(submitter =>
        input(typ := "hidden", nameAttr := submitter.name, value := submitter.value)
      ),
      button(
        typ      := "submit",
        nameAttr := "i-am-the-submitter",
        value    := "submitter-value",
        "Submit"
      )
    )
end Issue3814LiveView

object Issue3814LiveView:
  final case class Model(triggerSubmit: Boolean = false, submitter: Option[FormSubmitter] = None)
  enum Msg:
    case Submit(event: FormEvent[FormData])
