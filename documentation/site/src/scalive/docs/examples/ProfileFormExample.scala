package scalive.docs.examples

import zio.{Task, ZIO}

import scalive.*

// docs:start profile-form-example
final class ProfileFormExample extends LiveView[ProfileFormExample.Msg, ProfileFormExample.Model]:
  import ProfileFormExample.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(Profile.Definition.initial()))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      ZIO.succeed(model.copy(form = event.form, previewed = None, saved = None))
    case Msg.Submit(event, intent) =>
      intent match
        case Right(Profile.Intent.Preview) =>
          ZIO.succeed(model.copy(form = event.form, previewed = event.valueOption, saved = None))
        case Right(Profile.Intent.Save) =>
          ZIO.succeed(model.copy(form = event.form, previewed = None, saved = event.valueOption))
        case Left(_) =>
          ZIO.succeed(model.copy(form = event.form, previewed = None, saved = None))
    case Msg.Reset =>
      ZIO.succeed(Model(Profile.Definition.initial()))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val profileForm    = model.map(_.form)
    val nameField      = profileForm.field(Profile.Name)
    val emailField     = profileForm.field(Profile.Email)
    val biographyField = profileForm.field(Profile.Biography)

    div(
      cls := "docs-profile-form",
      model.map(_.previewed).option { profile =>
        p(
          dataAttr("profile-previewed") := "",
          cls                           := "docs-profile-saved",
          role                          := "status",
          profile.map(profile => s"Previewing ${profile.name}'s profile.")
        )
      },
      model.map(_.saved).option { profile =>
        p(
          dataAttr("profile-saved") := "",
          cls                       := "docs-profile-saved",
          role                      := "status",
          profile.map(profile => s"Saved ${profile.name}'s profile.")
        )
      },
      form(
        dataAttr("profile-form") := "",
        idAttr                   := "profile-form",
        Profile.Definition.onChange(Msg.Validate(_)),
        Profile.Definition.onSubmit(Profile.Submitter)(Msg.Submit.apply),
        field(
          label(forId := nameField.id, "Name"),
          nameField.text(
            nameField.validationAttributes,
            placeholder := "Ada Lovelace"
          ),
          nameField.errorFeedback(
            error => error.map(value => messages(value.message)),
            dataAttr("field-error") := "name"
          )
        ),
        field(
          label(forId := emailField.id, "Email"),
          emailField.email(
            emailField.validationAttributes,
            placeholder := "ada@example.com"
          ),
          emailField.errorFeedback(
            error => error.map(value => messages(value.message)),
            dataAttr("field-error") := "email"
          )
        ),
        field(
          label(forId := biographyField.id, "Biography"),
          biographyField.textarea(
            biographyField.validationAttributes,
            rows        := 5,
            placeholder := s"Up to ${Profile.BiographyMaxLength} characters"
          ),
          biographyField.errorFeedback(
            error => error.map(value => messages(value.message)),
            dataAttr("field-error") := "biography"
          )
        ),
        div(
          cls := "docs-profile-actions",
          Profile.Submitter.button(Profile.Intent.Preview)("Preview profile"),
          Profile.Submitter.button(Profile.Intent.Save)(
            submission.replaceTextWith("Saving..."),
            "Save profile"
          ),
          button(typ := "button", on.click(Msg.Reset), "Reset form")
        )
      )
    )
  end view

  private def field(content: Mod.Input[Msg]*): HtmlElement[Msg] =
    div(cls := "docs-profile-field", Mod.flatten(content))
end ProfileFormExample

object ProfileFormExample:
  final case class Profile(name: String, email: String, biography: String)

  object Profile:
    val BiographyMaxLength = 500
    val Root               = FormRoot("profile")

    val Name = Root
      .text("name")
      .map(_.trim)
      .required(FieldIssue("validation.name.required", Some("required")))

    val Email = Root
      .text("email")
      .map(_.trim)
      .required(FieldIssue("validation.email.required", Some("required")))
      .validate(FieldIssue("validation.email.invalid", Some("invalid_email")))(EmailPattern.matches)

    val Biography = Root
      .text("biography")
      .map(_.trim)
      .required(FieldIssue("validation.biography.required", Some("required")))
      .validate(FieldIssue("validation.biography.too_long", Some("too_long")))(
        _.length <= BiographyMaxLength
      )

    val Definition = Root.product[Profile]((Name, Email, Biography))

    enum Intent(val wireValue: String):
      case Preview extends Intent("preview")
      case Save    extends Intent("save")

    val Submitter = Definition.submitter(Intent.values)(_.wireValue)

    private val EmailPattern = """^[^\s@]+@[^\s@]+\.[^\s@]+$""".r
  end Profile

  private val messages = Map(
    "validation.name.required"      -> "Name is required.",
    "validation.email.required"     -> "Email is required.",
    "validation.email.invalid"      -> "Enter a valid email address.",
    "validation.biography.required" -> "Biography is required.",
    "validation.biography.too_long" ->
      s"Biography must be ${Profile.BiographyMaxLength} characters or fewer."
  )

  final case class Model(
    form: Profile.Definition.Form,
    previewed: Option[Profile] = None,
    saved: Option[Profile] = None)

  enum Msg:
    case Validate(event: Profile.Definition.Event)
    case Submit(
      event: Profile.Definition.Event,
      intent: Either[FormSubmitter.DecodeError, Profile.Intent])
    case Reset
end ProfileFormExample
// docs:end profile-form-example
