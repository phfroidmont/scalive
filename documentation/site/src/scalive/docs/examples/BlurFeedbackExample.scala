package scalive.docs.examples

import zio.{Task, ZIO}

import scalive.*

// docs:start blur-feedback-example
final class BlurFeedbackExample
    extends LiveView[BlurFeedbackExample.Msg, BlurFeedbackExample.Model]:
  import BlurFeedbackExample.*

  def mount(ctx: MountContext): Task[Model] = ZIO.succeed(Model.initial)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Updated(update) =>
      val applied = update.applyTo(model.form)
      val status  = if applied.valuesChanged then None else model.status
      ZIO.succeed(model.copy(form = applied.form, status = status))
    case Msg.Submitted(event) =>
      ZIO.succeed(model.copy(form = event.form, status = Some("Submitted")))
    case Msg.NormalizePhone(value) =>
      val form = model.form.updated(Profile.Phone, value.trim)
      ZIO.succeed(model.copy(form = form, status = None))
    case Msg.ResetCurrent =>
      ZIO.succeed(model.copy(form = model.form.pristine))
    case Msg.SelectChoice =>
      ZIO.succeed(model.copy(form = model.form.updated(Profile.Choice, "selected"), status = None))
    case Msg.ResetInitial =>
      ZIO.succeed(Model.initial)

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val binding = model
      .map(_.form).bind(
        FormRef,
        Msg.Updated(_),
        Msg.Submitted(_),
        feedback = FormFeedback.AfterBlur
      )
    val name   = binding.field(Profile.Name)
    val phone  = binding.field(Profile.Phone).onBlur(Msg.NormalizePhone(_))
    val choice = binding.field(Profile.Choice)
    val rows   = binding.rows(Profile.ContactRows)

    binding.render(
      model.map(_.status).option(status => p(role := "status", status)),
      label(forId := name.id, "Name"),
      name.text(name.validationAttributes),
      name.errorFeedback(error => error.map(_.message)),
      label(forId := phone.id, "Phone"),
      phone.tel(phone.validationAttributes),
      phone.errorFeedback(error => error.map(_.message)),
      div(
        idAttr := choice.id,
        role   := "group",
        choice.validationAttributes,
        input(typ  := "hidden", nameAttr := choice.name, value := choice.fieldValue),
        button(typ := "button", on.click(Msg.SelectChoice), "Choose an option"),
        // A real composite widget dispatches this only when focus leaves the whole widget.
        button(typ := "button", on.click(choice.blurred), "Close picker")
      ),
      choice.errorFeedback(error => error.map(_.message)),
      rows.splitBy(_.key) { (key, row) =>
        val email = binding.field(Profile.ContactRows, key, Profile.ContactEmail)
        div(
          row.presence(),
          label(forId := email.id, "Contact email"),
          email.email(email.validationAttributes),
          email.errorFeedback(error => error.map(_.message))
        )
      },
      button(typ := "submit", "Save"),
      button(typ := "button", on.click(Msg.ResetCurrent), "Clear feedback"),
      button(typ := "button", on.click(Msg.ResetInitial), "Reset values")
    )
  end view
end BlurFeedbackExample

object BlurFeedbackExample:
  final case class Contact(email: String)
  final case class Profile(name: String, phone: String, choice: String, contacts: Vector[Contact])

  object Profile:
    val Root         = FormRoot("profile")
    val Name         = Root.text("name").required(FieldIssue("Name is required"))
    val Phone        = Root.text("phone")
    val Choice       = Root.text("choice").required(FieldIssue("Choose an option"))
    val Contacts     = Root.rows("contacts")
    val ContactEmail = Contacts.text("email").required(FieldIssue("Email is required"))
    val ContactRows  = Contacts.product[Contact](Tuple1(ContactEmail))
    val Definition   = Root.product[Profile]((Name, Phone, Choice, ContactRows))

    private val FirstContact = FormRowKey
      .from[Contacts.type]("primary")
      .fold(error => throw new IllegalArgumentException(error.code), identity)

    def initial: Definition.Form = Definition.initial(
      Name.initial(""),
      Phone.initial(""),
      Choice.initial(""),
      ContactRows.initial(ContactRows.row(FirstContact)(ContactEmail.initial("")))
    )

  private val FormRef = DomRef("blur-feedback-profile")

  final case class Model(form: Profile.Definition.Form, status: Option[String] = None)
  object Model:
    def initial: Model = Model(Profile.initial)

  enum Msg:
    case Updated(update: Profile.Definition.Update)
    case Submitted(event: Profile.Definition.Event)
    case NormalizePhone(value: String)
    case SelectChoice
    case ResetCurrent
    case ResetInitial
end BlurFeedbackExample
// docs:end blur-feedback-example
