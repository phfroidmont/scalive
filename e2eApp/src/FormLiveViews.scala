import zio.ZIO
import zio.http.URL
import zio.json.ast.Json

import scalive.*
import scalive.LiveIO.given
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
  portal: Boolean = false,
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
            portal = url.queryParam("portal").isDefined,
            latencyMode = url.queryParam("phx-change").contains("validate")
          )
        ),
      encodeFn = _ => Right("?")
    )

class FormLiveView extends LiveView[FormLiveView.Msg, FormLiveView.Model]:
  import FormLiveView.*

  override val queryCodec: LiveQueryCodec[FormQueryParams] = FormQueryParams.codec

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: FormQueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(query = params)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      maybeAwait(model, "validate").as(model.copy(values = model.values ++ event.raw.asMap))
    case Msg.Save(_) =>
      maybeAwait(model, "save").as(model.copy(submitted = true))
    case Msg.CustomRecovery(_) =>
      model.query.autoRecover match
        case Some("patch-recovery") => ctx.nav.pushPatch("/form?patched=true").as(model)
        case _ => model.copy(values = model.values.updated("b", "custom value from server"))
    case Msg.ButtonTest => model

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks
      .empty[Msg, Model].rawEvent("form-raw") { (model: Model, event: LiveEvent, _) =>
        if event.cid.nonEmpty then LiveEventHookResult.cont(model)
        else if event.bindingId == "sandbox:eval" then
          E2ESandboxEval.handle(model, event.bindingId, event.value)
        else LiveEventHookResult.cont(applyRawFormValue(model, event.value))
      }.rawEvent("form-e2e-events") { (model: Model, event: LiveEvent, ctx: MessageContext) =>
        if event.cid.nonEmpty then LiveEventHookResult.cont(model)
        else
          event.bindingId match
            case "validate" =>
              maybeAwait(model, "validate").map { _ =>
                LiveEventHookResult.halt(
                  model.copy(values = model.values ++ rawFormData(event).asMap)
                )
              }
            case "save" =>
              maybeAwait(model, "save")
                .map(_ => LiveEventHookResult.halt(model.copy(submitted = true)))
            case "custom-recovery" =>
              LiveEventHookResult.halt(
                model.copy(values = model.values.updated("b", "custom value from server"))
              )
            case "patch-recovery" =>
              ctx.nav.pushPatch("/form?patched=true").as(LiveEventHookResult.halt(model))
            case "button-test" => LiveEventHookResult.halt(model)
            case _             => LiveEventHookResult.cont(model)
      }

  def render(model: Model) =
    val formContent = renderFormContent(model)
    div(
      if model.query.portal then h1("Form") else "",
      if model.query.portal then portal("form-portal", target = "body")(formContent)
      else formContent,
      if model.submitted then p("Form was submitted!") else ""
    )

  private def renderFormContent(model: Model): Mod[Msg] =
    if model.query.liveComponent then
      liveComponent(
        FormComponent,
        id = "form-component",
        props = FormComponent.Props(model.query, model.values)
      )
    else FormLiveView.renderForm(model.query, model.values, Some(Msg.Validate(EmptyFormEvent)))

  private def applyRawFormValue(model: Model, value: Json): Model =
    value match
      case Json.Str(raw) => model.copy(values = model.values ++ FormData.fromUrlEncoded(raw).asMap)
      case _             => model

  private def rawFormData(event: LiveEvent): FormData =
    event.value.asString.map(FormData.fromUrlEncoded).getOrElse(FormData.fromMap(event.params))

  private def maybeAwait(model: Model, event: String) =
    FormLiveView.maybeAwait(model.query, event)

end FormLiveView

object FormLiveView:
  private val formAttr           = htmlAttr("form", StringAsIsEncoder)
  private val phxAutoRecoverAttr = htmlAttr("phx-auto-recover", StringAsIsEncoder)
  private val phxChangeAttr      = htmlAttr("phx-change", StringAsIsEncoder)
  private val phxClickAttr       = htmlAttr("phx-click", StringAsIsEncoder)
  private val phxSubmitAttr      = htmlAttr("phx-submit", StringAsIsEncoder)

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

  private def maybeAwait(query: FormQueryParams, event: String) =
    if query.latencyMode then E2ELatencyGate.await(event) else ZIO.unit

  private def rawFormData(event: LiveEvent): FormData =
    event.value.asString.map(FormData.fromUrlEncoded).getOrElse(FormData.fromMap(event.params))

  private def renderForm[Msg](
    query: FormQueryParams,
    values: Map[String, String],
    jsChangeMessage: Option[Msg],
    target: Option[Mod.Attr[Nothing]] = None
  ) =
    val formAttrs = Vector.newBuilder[Mod[Msg]]
    if !query.noId then formAttrs += (idAttr := "test-form")
    formAttrs += (phxSubmitAttr              := "save")
    if !query.noChangeEvent then
      formAttrs += (
        if query.jsChange then phx.onChange(JS.push(jsChangeMessage.get))
        else phxChangeAttr := "validate"
      )
    query.autoRecover.foreach(event => formAttrs += (phxAutoRecoverAttr := event))
    target.foreach(formAttrs += _)
    formAttrs += (cls := "myformclass")

    div(
      form(
        formAttrs.result(),
        fieldset(
          disabled := query.disabledFieldset,
          input(
            typ      := "text",
            nameAttr := "a",
            readonly := true,
            value    := values.getOrElse("a", "")
          ),
          input(typ := "text", nameAttr := "b", value := values.getOrElse("b", ""))
        ),
        input(typ := "text", nameAttr := "c", value := values.getOrElse("c", "")),
        select(
          nameAttr := "d",
          option(value := "foo", selected := values.get("d").contains("foo"), "foo"),
          option(value := "bar", selected := values.get("d").contains("bar"), "bar"),
          option(value := "baz", selected := values.get("d").contains("baz"), "baz")
        ),
        Option.when(!query.noId)(
          input(
            typ      := "text",
            nameAttr := "e",
            formAttr := "test-form",
            value    := values.getOrElse("e", "")
          )
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
          typ             := "button",
          phxClickAttr    := "button-test",
          phx.disableWith := "Loading",
          "Non-form Button"
        )
      ),
      Option.when(!query.noId)(
        input(
          typ      := "text",
          nameAttr := "f",
          formAttr := "test-form",
          value    := values.getOrElse("f", "")
        )
      )
    )
  end renderForm

  object FormComponent
      extends LiveComponent[FormComponent.Props, FormComponent.Msg, FormComponent.Model]:
    final case class Props(query: FormQueryParams, values: Map[String, String])
    final case class Model(
      query: FormQueryParams,
      values: Map[String, String],
      submitted: Boolean = false)

    enum Msg:
      case Validate(event: FormEvent[FormData])

    def mount(props: Props, ctx: MountContext) =
      Model(props.query, props.values)

    override def update(props: Props, model: Model, ctx: UpdateContext) =
      model.copy(query = props.query)

    override def hooks: ComponentLiveHooks[Props, Msg, Model] =
      ComponentLiveHooks.empty.rawEvent("form-component-events") { (_, model, event, ctx) =>
        event.bindingId match
          case "validate" =>
            maybeAwait(model.query, "validate").map { _ =>
              LiveEventHookResult.halt(
                model.copy(values = model.values ++ rawFormData(event).asMap)
              )
            }
          case "save" =>
            maybeAwait(model.query, "save")
              .map(_ => LiveEventHookResult.halt(model.copy(submitted = true)))
          case "custom-recovery" =>
            LiveEventHookResult.halt(
              model.copy(values = model.values.updated("b", "custom value from server"))
            )
          case "patch-recovery" =>
            ctx.nav.pushPatch("/form?patched=true").as(LiveEventHookResult.halt(model))
          case "button-test"             => LiveEventHookResult.halt(model)
          case _ if event.kind == "form" =>
            maybeAwait(model.query, "validate").map { _ =>
              LiveEventHookResult.halt(
                model.copy(values = model.values ++ rawFormData(event).asMap)
              )
            }
          case _ => LiveEventHookResult.cont(model)
      }

    def handleMessage(props: Props, model: Model, ctx: MessageContext) =
      case Msg.Validate(event) =>
        maybeAwait(model.query, "validate").as(model.copy(values = model.values ++ event.raw.asMap))

    def render(props: Props, model: Model, self: ComponentRef[Msg]) =
      div(
        FormLiveView.renderForm(
          model.query,
          model.values,
          Some(Msg.Validate(EmptyFormEvent)),
          Some(phx.target(self))
        ),
        if model.submitted then p("LC Form was submitted!") else ""
      )
  end FormComponent
end FormLiveView

class NestedFormLiveView extends LiveView[Unit, Unit]:
  def mount(ctx: MountContext) =
    ()

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Unit) => model

  def render(model: Unit) =
    div(liveView("nested", FormLiveView()))

class FormDynamicInputsLiveView
    extends LiveView[FormDynamicInputsLiveView.Msg, FormDynamicInputsLiveView.Model]:
  import FormDynamicInputsLiveView.*

  override val queryCodec: LiveQueryCodec[FormQueryParams] = FormQueryParams.codec

  def mount(ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: FormQueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(checkboxes = params.checkboxes)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) => updateFromEvent(model, event)
    case Msg.Save(event)     => updateFromEvent(model, event).copy(submitted = true)

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

  def mount(ctx: MountContext) =
    ctx.streams.init(ItemsStream, InitialItems).map(items => Model(items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(_) => E2ELatencyGate.await("validate") *> inc(model, ctx)
    case Msg.Save(_)     => E2ELatencyGate.await("save") *> inc(model, ctx)
    case Msg.Ping        => model

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("sandbox") { (model, event, _) =>
      E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

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

  private def inc(model: Model, ctx: MessageContext) =
    val next = model.streamCount + 1
    ctx.streams
      .insert(ItemsStream, Item(next)).map(items =>
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

  def mount(ctx: MountContext) =
    Model()

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(_) => model.copy(validateCount = model.validateCount + 1)
    case Msg.Submit(_)   =>
      model.copy(submitCount = model.submitCount + 1, feedbackUsed = true)
    case Msg.Inc            => model.copy(count = model.count + 1)
    case Msg.Dec            => model.copy(count = model.count - 1)
    case Msg.ToggleFeedback =>
      model.copy(feedback = !model.feedback, feedbackUsed = false)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("feedback-raw") { (model, event, _) =>
      if event.bindingId == "sandbox:eval" then
        E2ESandboxEval.handle(model, event.bindingId, event.value)
      else
        val usedFeedback = event.value match
          case Json.Str(raw) => FormData.fromUrlEncoded(raw).string("myfeedback").exists(_.nonEmpty)
          case _             => false
        LiveEventHookResult.cont(model.copy(feedbackUsed = model.feedbackUsed || usedFeedback))
    }

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
