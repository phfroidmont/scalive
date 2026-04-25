import zio.stream.ZStream
import zio.ZIO
import zio.json.ast.Json

import scalive.*

private val actionAttr = htmlAttr("action", scalive.codecs.StringAsIsEncoder)
private val methodAttr = htmlAttr("method", scalive.codecs.StringAsIsEncoder)
private val multipleAttr = htmlAttr("multiple", scalive.codecs.BooleanAsAttrPresenceEncoder)
private val placeholderAttr = htmlAttr("placeholder", scalive.codecs.StringAsIsEncoder)

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

class Issue3819LiveView extends LiveView[Issue3819LiveView.Msg, Boolean]:
  import Issue3819LiveView.*

  def mount = false

  def handleMessage(model: Boolean) =
    case Msg.Noop(_) => model

  override def interceptEvent(model: Boolean, event: String, value: Json) =
    if event == "reconnected" then ZIO.succeed(InterceptResult.halt(true))
    else ZIO.succeed(InterceptResult.cont(model))

  def subscriptions(model: Boolean) = ZStream.empty

  def render(reconnected: Boolean) =
    div(
      form(
        idAttr := "recover",
        phx.onChangeForm(Msg.Noop(_)),
        phx.onSubmitForm(Msg.Noop(_)),
        button("Submit")
      ),
      if reconnected then p(idAttr := "reconnected", "Reconnected!") else ""
    )
end Issue3819LiveView

object Issue3819LiveView:
  enum Msg:
    case Noop(data: FormData)

class Issue3107LiveView extends LiveView[Issue3107LiveView.Msg.type, Boolean]:
  def mount = true

  def handleMessage(model: Boolean) =
    case Issue3107LiveView.Msg => false

  def subscriptions(model: Boolean) = ZStream.empty

  def render(disabledButton: Boolean) =
    form(
      phx.onChange(Issue3107LiveView.Msg),
      select(
        option(value := "ONE", "ONE"),
        option(value := "TWO", "TWO")
      ),
      button(disabled := disabledButton, "OK")
    )
end Issue3107LiveView

object Issue3107LiveView:
  case object Msg

class Issue3083LiveView extends LiveView[Issue3083LiveView.Msg.type, Issue3083LiveView.Model]:
  import Issue3083LiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg => model

  override def interceptEvent(model: Model, event: String, value: Json) =
    if event != "sandbox:eval" then ZIO.succeed(InterceptResult.cont(model))
    else
      val code = value match
        case Json.Obj(fields) => fields.collectFirst { case ("value", Json.Str(v)) => v }.getOrElse("")
        case _                => ""
      val selected = code match
        case value if value.contains("[1,2]") => Some(Vector(1, 2))
        case value if value.contains("[2,3]") => Some(Vector(2, 3))
        case value if value.contains("[3,4]") => Some(Vector(3, 4))
        case _                                => None

      selected match
        case Some(values) => ZIO.succeed(InterceptResult.haltReply(model.copy(selected = values), Json.Obj("result" -> Json.Null)))
        case None         => ZIO.succeed(E2ESandboxEval.handle(model, event, value))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    form(
      idAttr := "form",
      phx.onChange(Msg),
      select(
        idAttr       := "ids",
        nameAttr     := "ids[]",
        multipleAttr := true,
        (1 to 5).map(number => option(value := number.toString, selected := model.selected.contains(number), number.toString))
      ),
      input(typ := "text", placeholderAttr := "focus me!")
    )
end Issue3083LiveView

object Issue3083LiveView:
  final case class Model(selected: Vector[Int] = Vector.empty)
  case object Msg
