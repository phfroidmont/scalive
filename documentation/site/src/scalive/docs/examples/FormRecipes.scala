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
    val Fields       = Root.product[Registration]((Email, Confirmation))
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
    val Definition = Root.product[Order](Tuple1(Quantity))

    def quantityControl(form: Definition.Form): HtmlElement[Nothing] =
      val quantity = form.field(Quantity)
      div(
        label(forId := quantity.id, "Quantity"),
        input(
          typ      := "text",
          idAttr   := quantity.id,
          nameAttr := quantity.name,
          value    := quantity.fieldValue,
          quantity.validationAttributes
        ),
        quantity.errorFeedback(error => error.message)
      )
  end QuantityForm
  // docs:end form-custom-field-input

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
    val PhoneRows  = Phones.product[Phone]((Label, Number))
    val Definition = Root.product[Contact](Tuple1(PhoneRows))
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
