import zio.ZIO
import zio.http.URL
import zio.json.ast.Json
import zio.stream.ZStream

import scalive.*
import scalive.codecs.{BooleanAsAttrPresenceEncoder, StringAsIsEncoder}

private val fieldset = HtmlTag("fieldset")
private val readonly = htmlAttr("readonly", BooleanAsAttrPresenceEncoder)

final case class FormQueryParams(
  liveComponent: Boolean = false,
  noId: Boolean = false,
  noChangeEvent: Boolean = false,
  jsChange: Boolean = false,
  autoRecover: Option[String] = None,
  disabledFieldset: Boolean = false,
  checkboxes: Boolean = false,
  latencyMode: Boolean = false)

object FormQueryParams:
  val codec: LiveQueryCodec[FormQueryParams] =
    LiveQueryCodec.custom(
      decodeFn = url =>
        Right(
          FormQueryParams(
            liveComponent = url.queryParam("live-component").isDefined,
            noId = url.queryParam("no-id").isDefined,
            noChangeEvent = url.queryParam("no-change-event").isDefined,
            jsChange = url.queryParam("js-change").isDefined,
            autoRecover = url.queryParam("phx-auto-recover"),
            disabledFieldset = url.queryParam("disabled-fieldset").contains("true"),
            checkboxes = url.queryParam("checkboxes").contains("1"),
            latencyMode = url.queryParam("phx-change").contains("validate")
          )
        ),
      encodeFn = _ => Right("?")
    )

class FormLiveView(nested: Boolean = false) extends LiveView[FormLiveView.Msg, FormLiveView.Model]:
  import FormLiveView.*

  override val queryCodec: LiveQueryCodec[FormQueryParams] = FormQueryParams.codec

  def mount = Model()

  override def handleParams(model: Model, params: FormQueryParams, _url: URL) =
    model.copy(query = params)

  def handleMessage(model: Model) =
    case Msg.Validate(event) =>
      maybeAwait(model, "validate").as(model.copy(values = model.values ++ event.raw.asMap))
    case Msg.Save(_) =>
      maybeAwait(model, "save").as(model.copy(submitted = true))
    case Msg.CustomRecovery(_) =>
      model.copy(values = model.values.updated("b", "custom value from server"))
    case Msg.ButtonTest => model

  override def interceptEvent(model: Model, event: String, value: Json) =
    if event == "sandbox:eval" then ZIO.succeed(E2ESandboxEval.handle(model, event, value))
    else ZIO.succeed(InterceptResult.cont(applyRawFormValue(model, value)))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    val content =
      div(
        renderForm(model),
        if model.submitted then p("Form was submitted!") else ""
      )
    if nested then div(idAttr := "nested", content) else content

  private def renderForm(model: Model) =
    val formModel = Form.of(
      "",
      FormState(formData(model), Right(formData(model)), submitted = false),
      FormCodec.formData
    )
    val formAttrs = Vector.newBuilder[Mod[Msg]]
    if !model.query.noId then formAttrs += (idAttr := "test-form")
    formAttrs += formModel.onSubmit(Msg.Save(_))
    if !model.query.noChangeEvent then
      formAttrs += (
        if model.query.jsChange then phx.onChange(JS.push(Msg.Validate(EmptyFormEvent)))
        else formModel.onChange(Msg.Validate(_))
      )
    model.query.autoRecover.foreach(_ =>
      formAttrs += phx.autoRecover(Msg.CustomRecovery(EmptyFormEvent))
    )
    formAttrs += (cls := "myformclass")

    form(
      formAttrs.result(),
      fieldset(
        disabled := model.query.disabledFieldset,
        formModel.text("a", readonly := true),
        formModel.text("b")
      ),
      formModel.text("c"),
      select(
        nameAttr := "d",
        option(value := "foo", selected := model.values.get("d").contains("foo"), "foo"),
        option(value := "bar", selected := model.values.get("d").contains("bar"), "bar"),
        option(value := "baz", selected := model.values.get("d").contains("baz"), "baz")
      ),
      button(
        typ             := "submit",
        phx.disableWith := "Submitting",
        phx.onClick(JS.dispatch("test")),
        "Submit with JS"
      ),
      button(
        idAttr          := "submit",
        typ             := "submit",
        phx.disableWith := "Submitting",
        "Submit"
      ),
      button(
        typ := "button",
        phx.onClick(Msg.ButtonTest),
        phx.disableWith := "Loading",
        "Non-form Button"
      )
    )
  end renderForm

  private def applyRawFormValue(model: Model, value: Json): Model =
    value match
      case Json.Str(raw) => model.copy(values = model.values ++ FormData.fromUrlEncoded(raw).asMap)
      case _             => model

  private def maybeAwait(model: Model, event: String) =
    if model.query.latencyMode then E2ELatencyGate.await(event) else ZIO.unit

  private def formData(model: Model): FormData =
    FormData.fromMap(model.values)
end FormLiveView

object FormLiveView:
  enum Msg:
    case Validate(event: FormEvent[FormData])
    case Save(event: FormEvent[FormData])
    case CustomRecovery(event: FormEvent[FormData])
    case ButtonTest

  val EmptyFormEvent: FormEvent[FormData] =
    FormEvent(FormData.empty, Right(FormData.empty))

  final case class Model(
    query: FormQueryParams = FormQueryParams(),
    values: Map[String, String] = Map(
      "a" -> "foo",
      "b" -> "bar",
      "c" -> "baz",
      "d" -> "bar"
    ),
    submitted: Boolean = false)

class FormDynamicInputsLiveView
    extends LiveView[FormDynamicInputsLiveView.Msg, FormDynamicInputsLiveView.Model]:
  import FormDynamicInputsLiveView.*

  override val queryCodec: LiveQueryCodec[FormQueryParams] = FormQueryParams.codec

  def mount = Model()

  override def handleParams(model: Model, params: FormQueryParams, _url: URL) =
    model.copy(checkboxes = params.checkboxes)

  def handleMessage(model: Model) =
    case Msg.Validate(event) => updateFromEvent(model, event)
    case Msg.Save(event)     => updateFromEvent(model, event).copy(submitted = true)

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    val formModel = Form.of(
      "my_form",
      FormState(formData(model), Right(dynamicInputsForm(model)), submitted = false),
      DynamicInputsForm.codec
    )
    val usersSortName = formModel.name(FormPath("users_sort").array)
    val usersDropName = formModel.name(FormPath("users_drop").array)

    div(
      form(
        idAttr := "my-form",
        formModel.onChange(Msg.Validate(_)),
        formModel.onSubmit(Msg.Save(_)),
        styleAttr := "display: flex; flex-direction: column; gap: 4px; max-width: 500px;",
        fieldset(
          input(
            typ         := "text",
            idAttr      := "my-form_name",
            nameAttr    := formModel.name("name"),
            value       := formModel.value("name"),
            placeholder := "name"
          ),
          model.users.indices.map { index =>
            div(
              styleAttr := "padding: 4px; border: 1px solid gray;",
              input(typ := "hidden", nameAttr := usersSortName, value := index.toString),
              input(
                typ         := "text",
                idAttr      := s"my-form_users_${index}_name",
                nameAttr    := formModel.name(FormPath("users", index.toString, "name")),
                value       := formModel.value(FormPath("users", index.toString, "name")),
                placeholder := "name"
              ),
              if model.checkboxes then
                label(
                  input(
                    typ      := "checkbox",
                    nameAttr := usersDropName,
                    value    := index.toString
                  ),
                  " Remove"
                )
              else
                button(
                  typ      := "button",
                  nameAttr := usersDropName,
                  value    := index.toString,
                  phx.onClick(JS.dispatch("change")),
                  "Remove"
                )
            )
          }
        ),
        input(typ := "hidden", nameAttr := usersDropName),
        if model.checkboxes then
          label(
            input(typ := "checkbox", nameAttr := usersSortName),
            " add more"
          )
        else
          button(
            typ      := "button",
            nameAttr := usersSortName,
            value    := "new",
            phx.onClick(JS.dispatch("change")),
            "add more"
          )
      ),
      if model.submitted then p("Form was submitted!") else ""
    )
  end render
end FormDynamicInputsLiveView

object FormDynamicInputsLiveView:
  enum Msg:
    case Validate(event: FormEvent[DynamicInputsForm])
    case Save(event: FormEvent[DynamicInputsForm])

  final case class UserInput(name: String)
  final case class DynamicInputsForm(
    name: String,
    usersSort: Vector[String],
    usersDrop: Set[String],
    userNames: Map[String, String])

  object DynamicInputsForm:
    val codec: FormCodec[DynamicInputsForm] =
      FormCodec { data =>
        Right(
          DynamicInputsForm(
            name = data.string("my_form[name]").getOrElse(""),
            usersSort = data.values("my_form[users_sort][]"),
            usersDrop = data.values("my_form[users_drop][]").filter(_.nonEmpty).toSet,
            userNames = userNames(data)
          )
        )
      }

    private def userNames(data: FormData): Map[String, String] =
      val prefix = "my_form[users]["
      val suffix = "][name]"
      data.raw.collect {
        case (key, value) if key.startsWith(prefix) && key.endsWith(suffix) =>
          key.stripPrefix(prefix).stripSuffix(suffix) -> value
      }.toMap

  final case class Model(
    name: String = "",
    users: Vector[UserInput] = Vector.empty,
    checkboxes: Boolean = false,
    submitted: Boolean = false)

  private def updateFromEvent(model: Model, event: FormEvent[DynamicInputsForm]): Model =
    event.value match
      case Right(data) => updateFromData(model, data)
      case Left(_)     => model

  private def updateFromData(model: Model, data: DynamicInputsForm): Model =
    val users =
      data.usersSort.filterNot(data.usersDrop).foldLeft(Vector.empty[UserInput]) { (acc, key) =>
        if key == "new" then acc :+ UserInput("")
        else
          val value = data.userNames.getOrElse(key, "")
          acc :+ UserInput(value)
      }
    model.copy(name = data.name, users = users)

  private def dynamicInputsForm(model: Model): DynamicInputsForm =
    DynamicInputsForm(
      name = model.name,
      usersSort = model.users.indices.map(_.toString).toVector,
      usersDrop = Set.empty,
      userNames = model.users.zipWithIndex.map { case (user, index) =>
        index.toString -> user.name
      }.toMap
    )

  private def formData(model: Model): FormData =
    val raw = Vector.newBuilder[(String, String)]
    raw += "my_form[name]" -> model.name
    model.users.zipWithIndex.foreach { case (user, index) =>
      raw += "my_form[users_sort][]"         -> index.toString
      raw += s"my_form[users][$index][name]" -> user.name
    }
    FormData(raw.result())
end FormDynamicInputsLiveView

class FormStreamLiveView extends LiveView[FormStreamLiveView.Msg, FormStreamLiveView.Model]:
  import FormStreamLiveView.*

  def mount =
    LiveContext.stream(ItemsStream, InitialItems).map(items => Model(items = items))

  def handleMessage(model: Model) =
    case Msg.Validate(_) => E2ELatencyGate.await("validate") *> inc(model)
    case Msg.Save(_)     => E2ELatencyGate.await("save") *> inc(model)
    case Msg.Ping        => model

  override def interceptEvent(model: Model, event: String, value: Json) =
    ZIO.succeed(E2ESandboxEval.handle(model, event, value))

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    val data = FormData(Vector("myname" -> model.count.toString, "other" -> model.count.toString))
    val formModel = Form.of("", FormState(data, Right(data), submitted = false), FormCodec.formData)

    div(
      model.count.toString,
      form(
        idAttr := "test-form",
        formModel.onChange(Msg.Validate(_)),
        formModel.onSubmit(Msg.Save(_)),
        formModel.text("myname"),
        formModel.text("other"),
        div(idAttr := "form-stream-hook", phx.hook := "FormHook", phx.onUpdate := "ignore"),
        ul(
          idAttr       := "form-stream",
          phx.onUpdate := "stream",
          model.items.stream { (domId, item) =>
            li(idAttr := domId, phx.hook := "FormStreamHook", s"*%{id: ${item.id}}")
          }
        ),
        button(idAttr := "submit", phx.disableWith := "Saving...", "Submit")
      )
    )

  private def inc(model: Model) =
    val next = model.streamCount + 1
    LiveContext
      .streamInsert(ItemsStream, Item(next)).map(items =>
        model.copy(items = items, count = model.count + 1, streamCount = next)
      )
end FormStreamLiveView

object FormStreamLiveView:
  enum Msg:
    case Validate(event: FormEvent[FormData])
    case Save(event: FormEvent[FormData])
    case Ping

  final case class Item(id: Int)
  final case class Model(items: LiveStream[Item], count: Int = 0, streamCount: Int = 3)

  val InitialItems = Vector(Item(1), Item(2), Item(3))
  val ItemsStream  = LiveStreamDef[Item]("items", item => s"items-${item.id}")

class FormFeedbackLiveView extends LiveView[FormFeedbackLiveView.Msg, FormFeedbackLiveView.Model]:
  import FormFeedbackLiveView.*

  def mount = Model()

  def handleMessage(model: Model) =
    case Msg.Validate(_)    => model.copy(validateCount = model.validateCount + 1)
    case Msg.Submit(_)      => model.copy(submitCount = model.submitCount + 1, feedbackUsed = true)
    case Msg.Inc            => model.copy(count = model.count + 1)
    case Msg.Dec            => model.copy(count = model.count - 1)
    case Msg.ToggleFeedback => model.copy(feedback = !model.feedback, feedbackUsed = false)

  override def interceptEvent(model: Model, event: String, value: Json) =
    if event == "sandbox:eval" then ZIO.succeed(E2ESandboxEval.handle(model, event, value))
    else
      val usedFeedback = value match
        case Json.Str(raw) => FormData.fromUrlEncoded(raw).string("myfeedback").exists(_.nonEmpty)
        case _             => false
      ZIO.succeed(
        InterceptResult.cont(model.copy(feedbackUsed = model.feedbackUsed || usedFeedback))
      )

  def subscriptions(model: Model) = ZStream.empty

  def render(model: Model) =
    val data      = FormData.empty
    val formModel = Form.of("", FormState(data, Right(data), submitted = false), FormCodec.formData)

    div(
      styleTag(".phx-no-feedback { display: none; }"),
      p("Button Count: ", model.count.toString),
      p("Validate Count: ", model.validateCount.toString),
      p("Submit Count: ", model.submitCount.toString),
      button(phx.onClick(Msg.Inc), cls := "bg-blue-500 text-white p-4", "+"),
      button(phx.onClick(Msg.Dec), cls := "bg-blue-500 text-white p-4", "-"),
      form(
        idAttr   := "myform",
        nameAttr := "test",
        formModel.onChange(Msg.Validate(_)),
        formModel.onSubmit(Msg.Submit(_)),
        formModel.text("name", cls       := "border border-gray-500", placeholder := "type sth"),
        formModel.text("myfeedback", cls := "border border-gray-500", placeholder := "myfeedback"),
        button(typ                       := "submit", "Submit"),
        button(typ                       := "reset", "Reset")
      ),
      div(
        Option.when(model.feedback)(phxFeedbackFor := "myfeedback"),
        cls := (if model.feedback && !model.feedbackUsed then "phx-no-feedback" else ""),
        dataAttr("feedback-container") := "",
        "I am visible, because phx-no-feedback is not set for myfeedback!"
      ),
      button(phx.onClick(Msg.ToggleFeedback), "Toggle feedback")
    )
end FormFeedbackLiveView

object FormFeedbackLiveView:
  enum Msg:
    case Validate(event: FormEvent[FormData])
    case Submit(event: FormEvent[FormData])
    case Inc
    case Dec
    case ToggleFeedback

  final case class Model(
    count: Int = 0,
    validateCount: Int = 0,
    submitCount: Int = 0,
    feedback: Boolean = true,
    feedbackUsed: Boolean = false)

  private val phxFeedbackFor = htmlAttr("phx-feedback-for", StringAsIsEncoder)
