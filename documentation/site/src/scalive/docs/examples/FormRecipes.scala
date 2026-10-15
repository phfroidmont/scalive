package scalive.docs.examples

import scalive.*

/** Compile-checked fragments used by the typed Forms guide. */
object FormRecipes:
  // docs:start form-cross-field-validation
  object RegistrationForm:
    final case class Registration(email: String, confirmation: String)

    val Root         = FormRoot("registration")
    val Email        = Root.text("email").map(_.trim)
    val Confirmation = Root.text("confirmation").map(_.trim)
    val Fields       = Root.product[Registration]((email = Email, confirmation = Confirmation))
    val Definition   = Fields.emap { registration =>
      Either.cond(
        registration.email == registration.confirmation,
        registration,
        Fields.errors(
          Confirmation,
          FieldIssue("validation.email.mismatch", Some("mismatch"))
        )
      )
    }
  // docs:end form-cross-field-validation

  // docs:start form-custom-field-input
  object QuantityForm:
    final case class Order(quantity: Int)

    val PositiveInteger = FieldInput[Int](
      {
        case Vector(value) =>
          value.toIntOption
            .filter(_ > 0).toRight(
              FieldIssues.one(FieldIssue("validation.quantity.positive", Some("positive_integer")))
            )
        case Vector() =>
          Left(FieldIssues.one(FieldIssue("validation.quantity.required", Some("required"))))
        case _ =>
          Left(
            FieldIssues.one(FieldIssue("validation.quantity.duplicate", Some("duplicate_value")))
          )
      },
      quantity => Vector(quantity.toString)
    )

    val Root       = FormRoot("order")
    val Quantity   = Root.field("quantity", PositiveInteger)
    val Definition = Root.product[Order]((quantity = Quantity))

    def quantityControl(form: Signal[Definition.Form]): HtmlElement[Nothing] =
      val quantity = form.field(Quantity)
      div(
        label(forId := quantity.id, "Quantity"),
        input(typ   := "text", quantity.inputAttributes, quantity.validationAttributes),
        quantity.errorFeedback(error => error.map(_.message))
      )
  end QuantityForm
  // docs:end form-custom-field-input

  // docs:start form-checkbox-input
  object NotificationForm:
    final case class Preferences(enabled: Boolean)

    val Root       = FormRoot("notifications")
    val Enabled    = Root.field("enabled", FieldInput.checkbox())
    val Definition = Root.product[Preferences]((enabled = Enabled))
    val initial    = Definition.initial(Enabled.initial(true))

    enum Msg:
      case Changed(event: Definition.Event)
      case Submitted(event: Definition.Event)

    def render(form: Signal[Definition.Form]): HtmlElement[Msg] =
      val enabled = form.field(Enabled)
      scalive.form(
        idAttr := "notifications-form",
        Definition.onChange(Msg.Changed(_)),
        Definition.onSubmit(Msg.Submitted(_)),
        label(forId := enabled.id, "Enable notifications"),
        enabled.checkbox(enabled.validationAttributes),
        enabled.errorFeedback(error => error.map(_.message)),
        button(typ := "submit", "Save")
      )
  // docs:end form-checkbox-input

  // docs:start form-repeated-checkbox-items
  object TopicForm:
    final case class Preferences(topics: Vector[String])

    val Root       = FormRoot("subscriptions")
    val Topics     = Root.texts("topics")
    val Definition = Root.product[Preferences]((topics = Topics))

    enum Msg:
      case Changed(event: Definition.Event)
      case Submitted(event: Definition.Event)

    def render(
      form: Signal[Definition.Form],
      researchToken: Signal[String]
    ): HtmlElement[Msg] =
      val topics   = form.field(Topics)
      val news     = topics.item("news-choice")
      val research = topics.item("research-choice")
      scalive.form(
        idAttr := "subscriptions-form",
        Definition.onChange(Msg.Changed(_)),
        Definition.onSubmit(Msg.Submitted(_)),
        fieldSet(
          legend("Topics"),
          news.checkbox("news", topics.validationAttributes),
          label(forId := news.id, "News"),
          research.checkbox(researchToken, topics.validationAttributes),
          label(forId := research.id, "Research"),
          topics.errorFeedback(error => error.map(_.message))
        ),
        button(typ := "submit", "Save")
      )
  end TopicForm
  // docs:end form-repeated-checkbox-items

  // docs:start form-event-target
  object TargetedValidation:
    enum ChangedField:
      case Email, Confirmation, Unknown

    def changedField(event: RegistrationForm.Definition.Event): ChangedField =
      event.meta.target match
        case Some(address) if address == RegistrationForm.Email.address        => ChangedField.Email
        case Some(address) if address == RegistrationForm.Confirmation.address =>
          ChangedField.Confirmation
        case _ => ChangedField.Unknown
  // docs:end form-event-target

  // docs:start form-phoenix-repeated-controls
  object PhoenixPhoneForm:
    final case class Phone(label: String, number: String)
    final case class Contact(phones: Vector[Phone])

    val Root       = FormRoot("contact")
    val Phones     = Root.rows("phones")
    val Label      = Phones.text("label")
    val Number     = Phones.text("number")
    val PhoneRows  = Phones.product[Phone]((label = Label, number = Number))
    val Definition = Root.product[Contact]((phones = PhoneRows))
    val Adapter    = PhoenixNestedParamsAdapter(Definition, PhoneRows)

    enum Msg:
      case Validate(event: Adapter.Event)
      case Save(event: Adapter.Event)

    def render(form: Definition.Form): HtmlElement[Msg] =
      scalive.form(
        Adapter.onChange(Msg.Validate(_)),
        Adapter.onSubmit(Msg.Save(_)),
        form.rows(PhoneRows).zipWithIndex.map { case (row, index) =>
          val phoneLabel  = row.field(Label)
          val phoneNumber = row.field(Number)
          fieldSet(
            Adapter.persistentId(row, index),
            Adapter.sortControl(row, index),
            legend(s"Phone ${index + 1}"),
            label(forId := Adapter.fieldId("contact-form", index, Label), "Phone label"),
            input(
              typ      := "text",
              idAttr   := Adapter.fieldId("contact-form", index, Label),
              nameAttr := Adapter.fieldName(index, Label),
              value    := phoneLabel.fieldValue
            ),
            label(forId := Adapter.fieldId("contact-form", index, Number), "Phone number"),
            input(
              typ      := "tel",
              idAttr   := Adapter.fieldId("contact-form", index, Number),
              nameAttr := Adapter.fieldName(index, Number),
              value    := phoneNumber.fieldValue
            ),
            button(
              typ        := "button",
              nameAttr   := Adapter.dropName,
              value      := index.toString,
              aria.label := s"Remove phone ${index + 1}",
              on.click(JS.dispatch("change")),
              "Remove phone"
            )
          )
        },
        input(typ := "hidden", nameAttr := Adapter.dropName),
        button(
          typ      := "button",
          nameAttr := Adapter.sortName,
          value    := "new",
          on.click(JS.dispatch("change")),
          "Add phone"
        ),
        button(typ := "submit", "Save contact")
      )
  end PhoenixPhoneForm
  // docs:end form-phoenix-repeated-controls

  // docs:start form-raw-codec
  object RawPickerControl:
    val Codec = FormCodec.requiredString("picker[value]")

    enum Msg:
      case Changed(event: RawFormEvent[String])

    def render: HtmlElement[Msg] =
      form(
        on.change.form(Codec)(Msg.Changed(_)),
        input(typ := "text", nameAttr := "picker[value]")
      )
  // docs:end form-raw-codec
end FormRecipes
