import java.util.concurrent.atomic.AtomicInteger

import zio.ZIO
import zio.json.*

import scalive.*

class TypedFormFeedbackLiveView
    extends LiveView[TypedFormFeedbackLiveView.Msg, TypedFormFeedbackLiveView.Model]:
  import TypedFormFeedbackLiveView.*

  def mount(ctx: MountContext) = ZIO.succeed(Model.initial)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Updated(update) =>
      val applied = update.applyTo(model.form)
      val status  = if applied.valuesChanged then "Edited" else model.status
      ZIO.succeed(
        model.copy(
          form = applied.form,
          status = status,
          updateCount = model.updateCount + 1,
          blurCount = model.blurCount + Option.when(applied.kind == FormUpdateKind.Blurred)(1).size,
          effects = model.effects :+ s"update:${applied.kind.toString.toLowerCase}"
        )
      )
    case Msg.Submitted(event) =>
      val status = if event.form.values == model.form.values then model.status else "Edited"
      ZIO.succeed(
        model.copy(
          form = event.form,
          status = status,
          submitCount = model.submitCount + 1,
          effects = model.effects :+ "submit"
        )
      )
    case Msg.PhoneBlurred(value) =>
      ZIO.succeed(
        model.copy(
          form = model.form.updated(Phone, value.trim),
          phoneEffectCount = model.phoneEffectCount + 1,
          effects = model.effects :+ s"phone:$value"
        )
      )
    case Msg.SeedInvalidDate =>
      ZIO.succeed(model.copy(form = model.form.updated(Date, "not-a-date")))
    case Msg.CheckboxBlurred(value) =>
      ZIO.succeed(
        model.copy(
          checkboxBlurValue = value,
          checkboxBlurCount = model.checkboxBlurCount + 1,
          effects = model.effects :+ s"checkbox:$value"
        )
      )
    case Msg.TopicsBlurred(value) =>
      ZIO.succeed(
        model.copy(
          topicsBlurValue = value,
          topicsBlurCount = model.topicsBlurCount + 1,
          effects = model.effects :+ s"topics:$value"
        )
      )
    case Msg.SeedTopics =>
      ZIO.succeed(model.copy(form = model.form.updated(Topics, Vector("elixir", "erlang"))))
    case Msg.ChangeTopicToken =>
      ZIO.succeed(model.copy(elixirToken = "beam"))
    case Msg.SelectChoice(value) =>
      val updated = model.form.updated(Choice, value)
      ZIO.succeed(
        model.copy(
          form = updated,
          status = if updated.values == model.form.values then model.status else "Edited"
        )
      )
    case Msg.RemoveRow(key) =>
      val exists = model.form.rows(RowSchema).exists(_.key == key)
      ZIO.succeed(model.copy(form = if exists then model.form.removed(RowSchema, key)
      else model.form))
    case Msg.MoveRowDown(key) =>
      val rows  = model.form.rows(RowSchema)
      val index = rows.indexWhere(_.key == key)
      val moved =
        if index >= 0 && index < rows.size - 1 then
          model.form.movedAfter(RowSchema, key, rows(index + 1).key)
        else model.form
      ZIO.succeed(model.copy(form = moved))
    case Msg.AddReplacement =>
      val exists = model.form.rows(RowSchema).exists(_.key == RowA)
      val form   =
        if exists then model.form
        else model.form.added(RowSchema, RowA)(RowName.initial(""))
      ZIO.succeed(model.copy(form = form))
    case Msg.Reset => ZIO.succeed(Model.initial)
  end handleMessage

  override def view(model: Signal[Model]) =
    val formSignal = model.map(_.form)
    val binding    = formSignal.bind(
      DomRef("typed-feedback"),
      onUpdate = Msg.Updated(_),
      onSubmit = Msg.Submitted(_),
      feedback = FormFeedback.AfterBlur
    )
    val name     = binding.field(Name)
    val email    = binding.field(Email)
    val phone    = binding.field(Phone).onBlur(Msg.PhoneBlurred(_))
    val date     = binding.field(Date)
    val choice   = binding.field(Choice)
    val checkbox = binding.field(Checkbox).onBlur(Msg.CheckboxBlurred(_))
    val select   = binding.field(Select)
    val notes    = binding.field(Notes)
    val rows     = binding.rows(RowSchema)
    val topics   = binding.field(Topics).onBlur(Msg.TopicsBlurred(_))
    val scala    = topics.item("language-scala")
    val elixir   = topics.item("language-elixir")
    val erlang   = topics.item("language-erlang")

    div(
      binding.render(
        name.text(name.validationAttributes),
        name.errorFeedback(error => error.map(_.message)),
        email.email(email.validationAttributes, phx.debounce := 300),
        email.errorFeedback(error => error.map(_.message)),
        phone.tel(phone.validationAttributes),
        phone.errorFeedback(error => error.map(_.message)),
        date.date(date.validationAttributes),
        div(
          dom.hook("BlurFeedback", DomRef("choice-widget")),
          input(
            typ      := "hidden",
            idAttr   := choice.id,
            nameAttr := choice.name,
            value    := choice.fieldValue
          ),
          button(
            idAttr := "choice-option",
            typ    := "button",
            on.click(Msg.SelectChoice("selected")),
            "Select choice"
          ),
          button(
            idAttr := "choice-close",
            typ    := "button",
            hidden := true,
            on.click(choice.blurred)
          ),
          choice.errorFeedback(error => error.map(_.message))
        ),
        checkbox.checkbox("checked"),
        checkbox.errorFeedback(error => error.map(_.message)),
        fieldSet(
          legend("Topics"),
          scala.checkbox("scala", topics.validationAttributes),
          label(forId := scala.id, "Scala"),
          elixir.checkbox(model.map(_.elixirToken), topics.validationAttributes),
          label(forId                                                     := elixir.id, "Elixir"),
          erlang.checkbox("erlang", topics.validationAttributes, disabled := true),
          label(forId                                                     := erlang.id, "Erlang"),
          topics.errorFeedback(error => error.map(_.message))
        ),
        select.select(
          Vector("red" -> "Red", "green" -> "Green", "blue" -> "Blue"),
          multiple := true
        ),
        select.errorFeedback(error => error.map(_.message)),
        notes.textarea(notes.validationAttributes),
        notes.errorFeedback(error => error.map(_.message)),
        div(
          idAttr := "rows",
          rows.splitBy(_.key) { (key, row) =>
            val rowName = binding.field(RowSchema, key, RowName)
            div(
              dataAttr("row-key") := key.value,
              row.presence(),
              rowName.text(rowName.validationAttributes),
              rowName.errorFeedback(error => error.map(_.message)),
              button(
                typ                  := "button",
                dataAttr("move-row") := key.value,
                on.click(Msg.MoveRowDown(key)),
                "Move down"
              ),
              button(
                typ                    := "button",
                dataAttr("remove-row") := key.value,
                on.click(Msg.RemoveRow(key)),
                "Remove"
              )
            )
          }
        ),
        button(idAttr := "submit", typ := "submit", "Submit")
      ),
      button(idAttr := "outside", typ := "button", "Outside"),
      button(
        idAttr := "seed-invalid-date",
        typ    := "button",
        on.click(Msg.SeedInvalidDate),
        "Seed invalid date"
      ),
      button(
        idAttr := "seed-topics",
        typ    := "button",
        on.click(Msg.SeedTopics),
        "Seed topics"
      ),
      button(
        idAttr := "change-topic-token",
        typ    := "button",
        on.click(Msg.ChangeTopicToken),
        "Change topic token"
      ),
      button(
        idAttr := "add-replacement",
        typ    := "button",
        on.click(Msg.AddReplacement),
        "Add replacement"
      ),
      button(idAttr := "reset", typ := "button", on.click(Msg.Reset), "Reset"),
      liveComponent(FeedbackComponent, id = "typed-feedback-component", props = ()),
      div(idAttr := "status", model.map(_.status)),
      div(idAttr := "update-count", model.map(_.updateCount.toString)),
      div(idAttr := "blur-count", model.map(_.blurCount.toString)),
      div(idAttr := "phone-effect-count", model.map(_.phoneEffectCount.toString)),
      div(idAttr := "checkbox-blur-value", model.map(_.checkboxBlurValue)),
      div(idAttr := "checkbox-blur-count", model.map(_.checkboxBlurCount.toString)),
      div(idAttr := "topics-blur-value", model.map(_.topicsBlurValue)),
      div(idAttr := "topics-blur-count", model.map(_.topicsBlurCount.toString)),
      div(idAttr := "submit-count", model.map(_.submitCount.toString)),
      div(idAttr := "effects", model.map(_.effects.mkString("|"))),
      div(idAttr := "server-name", formSignal.map(_.field(Name).fieldValue)),
      div(idAttr := "server-email", formSignal.map(_.field(Email).fieldValue)),
      div(idAttr := "server-phone", formSignal.map(_.field(Phone).fieldValue)),
      div(
        idAttr := "server-date-raw",
        formSignal.map(_.field(Date).rawValues.toJson)
      ),
      div(idAttr := "server-notes", formSignal.map(_.field(Notes).fieldValue)),
      div(idAttr := "server-choice", formSignal.map(_.field(Choice).fieldValue)),
      div(idAttr := "server-checkbox", formSignal.map(_.field(Checkbox).rawValues.mkString(","))),
      div(idAttr := "server-topics", formSignal.map(_.field(Topics).rawValues.toJson)),
      div(idAttr := "server-select", formSignal.map(_.field(Select).rawValues.mkString(","))),
      div(
        idAttr := "result",
        formSignal.map(form => if form.valueOption.nonEmpty then "valid" else "invalid")
      ),
      div(idAttr := "mount-id", model.map(_.mountId.toString))
    )
  end view
end TypedFormFeedbackLiveView

object TypedFormFeedbackLiveView:
  final case class Row(name: String)
  final case class Data(
    name: String,
    email: String,
    phone: String,
    date: String,
    choice: String,
    checkbox: Option[String],
    topics: Vector[String],
    select: Vector[String],
    notes: String,
    rows: Vector[Row])

  val Root      = FormRoot("typed")
  val Name      = Root.text("name").required(FieldIssue("Name is required"))
  val Email     = Root.text("email").required(FieldIssue("Email is required"))
  val Phone     = Root.text("phone").required(FieldIssue("Phone is required"))
  val Date      = Root.text("date")
  val Choice    = Root.text("choice").required(FieldIssue("Choice is required"))
  val Checkbox  = Root.optionalText("checkbox")
  val Topics    = Root.texts("topics").validate(FieldIssue("Choose at least one topic"))(_.nonEmpty)
  val Select    = Root.texts("select")
  val Notes     = Root.text("notes").required(FieldIssue("Notes are required"))
  val RowGroup  = Root.rows("rows")
  val RowName   = RowGroup.text("name").required(FieldIssue("Row name is required"))
  val RowSchema = RowGroup.product[Row](Tuple1(RowName))
  val Definition =
    Root.product[Data](
      (Name, Email, Phone, Date, Choice, Checkbox, Topics, Select, Notes, RowSchema)
    )

  val RowA = FormRowKey.from[RowGroup.type]("row-a").toOption.get
  val RowB = FormRowKey.from[RowGroup.type]("row-b").toOption.get

  enum Msg:
    case Updated(update: Definition.Update)
    case Submitted(event: Definition.Event)
    case PhoneBlurred(value: String)
    case SeedInvalidDate
    case CheckboxBlurred(value: String)
    case TopicsBlurred(value: String)
    case SeedTopics
    case ChangeTopicToken
    case SelectChoice(value: String)
    case RemoveRow(key: RowGroup.Key)
    case MoveRowDown(key: RowGroup.Key)
    case AddReplacement
    case Reset

  final case class Model(
    form: Definition.Form,
    status: String = "Preserved",
    updateCount: Int = 0,
    blurCount: Int = 0,
    phoneEffectCount: Int = 0,
    checkboxBlurValue: String = "not blurred",
    checkboxBlurCount: Int = 0,
    topicsBlurValue: String = "not blurred",
    topicsBlurCount: Int = 0,
    elixirToken: String = "elixir",
    submitCount: Int = 0,
    effects: Vector[String] = Vector.empty,
    mountId: Int = nextMountId())

  object Model:
    def initial: Model = Model(
      Definition.initial(
        Name.initial(""),
        Email.initial(""),
        Phone.initial(""),
        Date.initial(""),
        Choice.initial(""),
        Checkbox.initial(None),
        Topics.initial(Vector.empty),
        Select.initial(Vector.empty),
        Notes.initial(""),
        RowSchema.initial(
          RowSchema.row(RowA)(RowName.initial("")),
          RowSchema.row(RowB)(RowName.initial("second"))
        )
      )
    )

  private val mountIds           = AtomicInteger(0)
  private def nextMountId(): Int = mountIds.incrementAndGet()

  object FeedbackComponent
      extends LiveComponent[Unit, FeedbackComponent.Msg, FeedbackComponent.Model]:
    val Root       = FormRoot("component")
    val Name       = Root.text("name").required(FieldIssue("Component name is required"))
    val Definition = Root.product[Tuple1[String]](Tuple1(Name))

    enum Msg:
      case Updated(update: Definition.Update)
      case Submitted(event: Definition.Event)
      case Blurred(value: String)

    final case class Model(
      form: Definition.Form,
      blurValue: String = "not blurred",
      blurCount: Int = 0)

    def mount(props: Unit, ctx: MountContext) =
      ZIO.succeed(Model(Definition.initial(Name.initial(""))))

    def handleMessage(props: Unit, model: Model, ctx: MessageContext) =
      case Msg.Updated(update)  => ZIO.succeed(model.copy(form = update.applyTo(model.form).form))
      case Msg.Submitted(event) => ZIO.succeed(model.copy(form = event.form))
      case Msg.Blurred(value)   =>
        ZIO.succeed(model.copy(blurValue = value, blurCount = model.blurCount + 1))

    override def view(
      props: Signal[Unit],
      model: Signal[Model],
      self: ComponentRef[Msg]
    ) =
      val formSignal = model.map(_.form)
      val binding    = formSignal.bind(
        DomRef("component-feedback"),
        onUpdate = Msg.Updated(_),
        onSubmit = Msg.Submitted(_),
        feedback = FormFeedback.AfterBlur
      )
      val name = binding.field(Name).onBlur(Msg.Blurred(_))

      div(
        binding.render(
          phx.target(self),
          name.text(name.validationAttributes, phx.target(self)),
          name.errorFeedback(error => error.map(_.message))
        ),
        div(idAttr := "component-server-name", formSignal.map(_.field(Name).fieldValue)),
        div(idAttr := "component-blur-value", model.map(_.blurValue)),
        div(idAttr := "component-blur-count", model.map(_.blurCount.toString))
      )
  end FeedbackComponent
end TypedFormFeedbackLiveView
