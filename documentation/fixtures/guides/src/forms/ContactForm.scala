package forms

import zio.*

import scalive.*

// docs:start forms-rows-definition
final case class Phone(label: String, number: String)
final case class Contact(name: String, phones: Vector[Phone])

object Contact:
  val Root        = FormRoot("contact")
  val Name        = Root.text("name").map(_.trim)
  val Phones      = Root.rows("phones")
  val PhoneLabel  = Phones.text("label").map(_.trim)
  val PhoneNumber = Phones
    .text("number")
    .map(_.trim)
    .required(FieldIssue("validation.phone.required", Some("required")))
  val PhoneRows  = Phones.product[Phone]((label = PhoneLabel, number = PhoneNumber))
  val Definition = Root.product[Contact]((name = Name, phones = PhoneRows))
// docs:end forms-rows-definition

final class ContactLiveView extends LiveView[ContactLiveView.Msg, ContactLiveView.Model]:
  import ContactLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(Contact.Definition.initial()))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) => ZIO.succeed(Model(event.form))
    case Msg.Save(event)     => ZIO.succeed(Model(event.form))

  // docs:start forms-rows-render
  def view(model: Signal[Model]): HtmlElement[Msg] =
    val contactForm = model.map(_.form)

    form(
      idAttr := "contact-form",
      Contact.Definition.onChange(Msg.Validate(_)),
      Contact.Definition.onSubmit(Msg.Save(_)),
      contactForm.rows(Contact.PhoneRows).splitBy(_.key) { (key, row) =>
        val number = contactForm.field(Contact.PhoneRows, key, Contact.PhoneNumber)
        div(
          row.presence(),
          label(forId := number.id, "Phone number"),
          number.tel(number.validationAttributes)
        )
      },
      button(typ := "submit", "Save")
    )
  // docs:end forms-rows-render

object ContactLiveView:
  final case class Model(form: Contact.Definition.Form)

  enum Msg:
    case Validate(event: Contact.Definition.Event)
    case Save(event: Contact.Definition.Event)
