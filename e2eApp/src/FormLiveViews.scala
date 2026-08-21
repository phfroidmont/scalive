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
  val decoder: LiveParamsDecoder[Unit, FormQueryParams] =
    LiveParamsDecoder.custom(
      decodeFn = (_, url) =>
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
        )
    )

class FormLiveView(initialQuery: FormQueryParams = FormQueryParams())
    extends LiveView.Routed[FormLiveView.Msg, FormLiveView.Model, FormQueryParams]:
  import FormLiveView.*

  def mount(_params: FormQueryParams, ctx: MountContext) =
    Model(query = initialQuery)

  override def handleParams(model: Model, params: FormQueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(query = params)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      maybeAwait(model, "validate").as(model.copy(values = model.values ++ event.raw.asMap))
    case Msg.Save(_) =>
      maybeAwait(model, "save").as(model.copy(submitted = true))
    case Msg.CustomRecovery(_) =>
      model.query.autoRecover match
        case Some("patch-recovery") => ctx.nav.pushPatchUnsafe("/form?patched=true").as(model)
        case _ => model.copy(values = model.values.updated("b", "custom value from server"))
    case Msg.ButtonTest => model

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks
      .empty[Msg, Model].onRawEvent { (model: Model, event: LiveEvent, _) =>
        if event.cid.nonEmpty then LiveEventHookResult.cont(model)
        else if event.bindingId == "sandbox:eval" then
          E2ESandboxEval.handle(model, event.bindingId, event.value)
        else LiveEventHookResult.cont(applyRawFormValue(model, event.value))
      }.onRawEvent { (model: Model, event: LiveEvent, ctx: MessageContext) =>
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
              ctx.nav.pushPatchUnsafe("/form?patched=true").as(LiveEventHookResult.halt(model))
            case "button-test" => LiveEventHookResult.halt(model)
            case _             => LiveEventHookResult.cont(model)
      }

  override def view(model: Signal[Model]) =
    val query      = model.map(_.query)
    val values     = model.map(_.values)
    val submitted  = model.map(_.submitted)
    val portalMode = query.map(_.portal)

    div(
      Signal.when(portalMode)(h1("Form")),
      portalMode.chooseMod(
        portal("form-portal", target = DomSelector.css("body"))(
          renderFormContent(query, values)
        ),
        renderFormContent(query, values)
      ),
      Signal.when(submitted)(p("Form was submitted!"))
    )

  private def renderFormContent(
    query: Signal[FormQueryParams],
    values: Signal[Map[String, String]]
  ): Mod[Msg] =
    val liveComponentMode = query.map(_.liveComponent)
    val componentProps    = query.zip(values).map { case (currentQuery, currentValues) =>
      FormComponent.Props(currentQuery, currentValues)
    }

    liveComponentMode.chooseMod(
      liveComponent(
        FormComponent,
        id = "form-component",
        props = componentProps
      ),
      FormLiveView.renderForm(query, values, Msg.Validate(EmptyFormEvent))
    )

  private def applyRawFormValue(model: Model, value: Json): Model =
    value match
      case Json.Str(raw) =>
        FormData
          .fromUrlEncoded(raw)
          .fold(_ => model, data => model.copy(values = model.values ++ data.asMap))
      case _ => model

  private def rawFormData(event: LiveEvent): FormData =
    event.value.asString
      .flatMap(raw => FormData.fromUrlEncoded(raw).toOption)
      .getOrElse(FormData.fromMap(event.params))

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
    event.value.asString
      .flatMap(raw => FormData.fromUrlEncoded(raw).toOption)
      .getOrElse(FormData.fromMap(event.params))

  private def renderForm[Msg](
    query: Signal[FormQueryParams],
    values: Signal[Map[String, String]],
    jsChangeMessage: Msg,
    target: Option[Mod.Attr[Msg]] = None
  ) =
    val idValue    = query.map(current => Option.unless(current.noId)("test-form"))
    val changeMode = query.map { current =>
      if current.noChangeEvent then 0
      else if current.jsChange then 1
      else 2
    }
    val autoRecover   = query.map(_.autoRecover)
    val disabledValue = query.map(_.disabledFieldset)
    val hasId         = query.map(current => !current.noId)
    val valueA        = values.map(_.getOrElse("a", ""))
    val valueB        = values.map(_.getOrElse("b", ""))
    val valueC        = values.map(_.getOrElse("c", ""))
    val selectedFoo   = values.map(_.get("d").contains("foo"))
    val selectedBar   = values.map(_.get("d").contains("bar"))
    val selectedBaz   = values.map(_.get("d").contains("baz"))
    val valueE        = values.map(_.getOrElse("e", ""))
    val valueF        = values.map(_.getOrElse("f", ""))

    div(
      form(
        idAttr.optional(idValue),
        phxSubmitAttr := "save",
        changeMode.chooseMod(
          1 -> on.change(JS.push(jsChangeMessage)),
          2 -> (phxChangeAttr := "validate")
        ),
        phxAutoRecoverAttr.optional(autoRecover),
        target,
        cls := "myformclass",
        fieldset(
          disabled := disabledValue,
          input(
            typ      := "text",
            nameAttr := "a",
            readonly := true,
            value    := valueA
          ),
          input(typ := "text", nameAttr := "b", value := valueB)
        ),
        input(typ := "text", nameAttr := "c", value := valueC),
        select(
          nameAttr := "d",
          option(value := "foo", selected := selectedFoo, "foo"),
          option(value := "bar", selected := selectedBar, "bar"),
          option(value := "baz", selected := selectedBaz, "baz")
        ),
        Signal.when(hasId)(
          input(
            typ      := "text",
            nameAttr := "e",
            formAttr := "test-form",
            value    := valueE
          )
        ),
        button(
          typ := "submit",
          submission.replaceTextWith("Submitting"),
          on.click(JS.dispatch("test")),
          "Submit with JS"
        ),
        button(
          idAttr := "submit",
          typ    := "submit",
          submission.replaceTextWith("Submitting"),
          "Submit"
        ),
        button(
          typ          := "button",
          phxClickAttr := "button-test",
          submission.replaceTextWith("Loading"),
          "Non-form Button"
        )
      ),
      Signal.when(hasId)(
        input(
          typ      := "text",
          nameAttr := "f",
          formAttr := "test-form",
          value    := valueF
        )
      )
    )
  end renderForm

  object FormComponent
      extends LiveComponent[
        FormComponent.Props,
        FormComponent.Msg,
        FormComponent.Model
      ]:
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
      ComponentLiveHooks.empty.onRawEvent { (_, model, event, ctx) =>
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
            ctx.nav.pushPatchUnsafe("/form?patched=true").as(LiveEventHookResult.halt(model))
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

    override def view(
      _props: Signal[Props],
      model: Signal[Model],
      self: ComponentRef[Msg]
    ) =
      val query     = model.map(_.query)
      val values    = model.map(_.values)
      val submitted = model.map(_.submitted)

      div(
        FormLiveView.renderForm(
          query,
          values,
          Msg.Validate(EmptyFormEvent),
          Some(phx.target(self))
        ),
        Signal.when(submitted)(p("LC Form was submitted!"))
      )
  end FormComponent
end FormLiveView

class NestedFormLiveView extends LiveView.Routed[Unit, FormQueryParams, FormQueryParams]:
  def mount(params: FormQueryParams, ctx: MountContext) =
    params

  override def handleParams(
    model: FormQueryParams,
    params: FormQueryParams,
    _url: URL,
    ctx: ParamsContext
  ) =
    params

  def handleMessage(model: FormQueryParams, ctx: MessageContext) =
    (_: Unit) => model

  override def view(model: Signal[FormQueryParams]) =
    div(liveView("nested", model)(NestedFormContentLiveView(_)))

private class NestedFormContentLiveView(initialQuery: FormQueryParams)
    extends LiveView[FormLiveView.Msg, FormLiveView.Model]:
  private val delegate = FormLiveView(initialQuery)

  def mount(ctx: MountContext) =
    FormLiveView.Model(query = initialQuery)

  override def hooks = delegate.hooks

  def handleMessage(model: FormLiveView.Model, ctx: MessageContext) =
    delegate.handleMessage(model, ctx)

  override def view(model: Signal[FormLiveView.Model]) =
    delegate.view(model)

class FormDynamicInputsLiveView
    extends LiveView.Routed[
      FormDynamicInputsLiveView.Msg,
      FormDynamicInputsLiveView.Model,
      FormQueryParams
    ]:
  import FormDynamicInputsLiveView.*

  def mount(_params: FormQueryParams, ctx: MountContext) =
    Model()

  override def handleParams(model: Model, params: FormQueryParams, _url: URL, ctx: ParamsContext) =
    model.copy(checkboxes = params.checkboxes)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) => updateFromEvent(model, event)
    case Msg.Save(event)     => updateFromEvent(model, event).copy(submitted = true)

  override def view(model: Signal[Model]) =
    val name       = model.map(_.name)
    val users      = model.map(_.users)
    val checkboxes = model.map(_.checkboxes)
    val submitted  = model.map(_.submitted)

    val usersSortName = FormPath("my_form", "users_sort").array.name
    val usersDropName = FormPath("my_form", "users_drop").array.name

    div(
      form(
        idAttr := "my-form",
        on.change.form(DynamicInputsForm.codec)(Msg.Validate(_)),
        on.submit.form(DynamicInputsForm.codec)(Msg.Save(_)),
        styleAttr := "display: flex; flex-direction: column; gap: 4px; max-width: 500px;",
        fieldset(
          input(
            typ         := "text",
            idAttr      := "my-form_name",
            nameAttr    := "my_form[name]",
            value       := name,
            placeholder := "name"
          ),
          users.splitByIndex { (index, user) =>
            val userName = user.map(_.name)
            div(
              styleAttr := "padding: 4px; border: 1px solid gray;",
              input(typ := "hidden", nameAttr := usersSortName, value := index.toString),
              input(
                typ         := "text",
                idAttr      := s"my-form_users_${index}_name",
                nameAttr    := FormPath("my_form", "users", index.toString, "name").name,
                value       := userName,
                placeholder := "name"
              ),
              checkboxes.choose(
                label(
                  input(
                    typ      := "checkbox",
                    nameAttr := usersDropName,
                    value    := index.toString
                  ),
                  " Remove"
                ),
                button(
                  typ      := "button",
                  nameAttr := usersDropName,
                  value    := index.toString,
                  on.click(JS.dispatch("change")),
                  "Remove"
                )
              )
            )
          }
        ),
        input(typ := "hidden", nameAttr := usersDropName),
        checkboxes.choose(
          label(
            input(typ := "checkbox", nameAttr := usersSortName),
            " add more"
          ),
          button(
            typ      := "button",
            nameAttr := usersSortName,
            value    := "new",
            on.click(JS.dispatch("change")),
            "add more"
          )
        )
      ),
      Signal.when(submitted)(p("Form was submitted!"))
    )
  end view
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

end FormDynamicInputsLiveView

class FormStreamLiveView extends LiveView[FormStreamLiveView.Msg, FormStreamLiveView.Model]:
  import FormStreamLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.create(ItemsStream, InitialItems).map(items => Model(items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(_) => E2ELatencyGate.await("validate") *> inc(model, ctx)
    case Msg.Save(_)     => E2ELatencyGate.await("save") *> inc(model, ctx)
    case Msg.Ping        => model

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.onRawEvent { (model, event, _) =>
      E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  override def view(model: Signal[Model]) =
    val count      = model.map(_.count)
    val countValue = count.map(_.toString)
    val items      = model.map(_.items)

    div(
      countValue,
      form(
        idAttr := "test-form",
        on.change.form(FormCodec.formData)(Msg.Validate(_)),
        on.submit.form(FormCodec.formData)(Msg.Save(_)),
        input(typ := "text", idAttr := "myname", nameAttr := "myname", value := countValue),
        input(typ := "text", idAttr := "other", nameAttr  := "other", value  := countValue),
        div(
          dom.hook("FormHook", DomRef("form-stream-hook")),
          phx.update := PhxUpdate.Ignore
        ),
        ul(
          idAttr     := "form-stream",
          phx.update := PhxUpdate.Stream,
          items.stream { (domId, item) =>
            val itemText = item.map(value => s"*%{id: ${value.id}}")
            li(dom.hook("FormStreamHook", DomRef(domId)), itemText)
          }
        ),
        button(idAttr := "submit", submission.replaceTextWith("Saving..."), "Submit")
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
    LiveHooks.empty.onRawEvent { (model, event, _) =>
      if event.bindingId == "sandbox:eval" then
        E2ESandboxEval.handle(model, event.bindingId, event.value)
      else
        val usedFeedback = event.value match
          case Json.Str(raw) =>
            FormData
              .fromUrlEncoded(raw)
              .toOption
              .flatMap(_.string("myfeedback"))
              .exists(_.nonEmpty)
          case _ => false
        LiveEventHookResult.cont(model.copy(feedbackUsed = model.feedbackUsed || usedFeedback))
    }

  override def view(model: Signal[Model]) =
    val count         = model.map(_.count)
    val validateCount = model.map(_.validateCount)
    val submitCount   = model.map(_.submitCount)
    val feedback      = model.map(_.feedback)
    val feedbackUsed  = model.map(_.feedbackUsed)

    val feedbackFor   = feedback.map(enabled => Option.when(enabled)("myfeedback"))
    val feedbackClass = feedback.zip(feedbackUsed).map { case (enabled, used) =>
      if enabled && !used then "phx-no-feedback" else ""
    }

    div(
      styleTag(".phx-no-feedback { display: none; }"),
      p("Button Count: ", count.map(_.toString)),
      p("Validate Count: ", validateCount.map(_.toString)),
      p("Submit Count: ", submitCount.map(_.toString)),
      button(on.click(Msg.Inc), cls := "bg-blue-500 text-white p-4", "+"),
      button(on.click(Msg.Dec), cls := "bg-blue-500 text-white p-4", "-"),
      form(
        idAttr   := "myform",
        nameAttr := "test",
        on.change.form(FormCodec.formData)(Msg.Validate(_)),
        on.submit.form(FormCodec.formData)(Msg.Submit(_)),
        input(
          typ         := "text",
          idAttr      := "name",
          nameAttr    := "name",
          value       := "",
          cls         := "border border-gray-500",
          placeholder := "type sth"
        ),
        input(
          typ         := "text",
          idAttr      := "myfeedback",
          nameAttr    := "myfeedback",
          value       := "",
          cls         := "border border-gray-500",
          placeholder := "myfeedback"
        ),
        button(typ := "submit", "Submit"),
        button(typ := "reset", "Reset")
      ),
      div(
        phxFeedbackFor.optional(feedbackFor),
        cls                            := feedbackClass,
        dataAttr("feedback-container") := "",
        "I am visible, because phx-no-feedback is not set for myfeedback!"
      ),
      button(on.click(Msg.ToggleFeedback), "Toggle feedback")
    )
  end view
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
