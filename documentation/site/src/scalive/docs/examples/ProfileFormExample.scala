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
      ZIO.succeed(model.copy(form = Profile.Definition.from(event), saved = None))
    case Msg.Save(event) =>
      ZIO.succeed(
        model.copy(
          form = Profile.Definition.from(event),
          saved = event.value.toOption
        )
      )
    case Msg.Reset =>
      ZIO.succeed(Model(Profile.Definition.initial()))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val profileForm    = model.map(_.form)
    val nameField      = profileForm.field(Profile.Name)
    val emailField     = profileForm.field(Profile.Email)
    val biographyField = profileForm.field(Profile.Biography)

    div(
      cls := "docs-profile-form",
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
        Profile.Definition.onChange(Msg.Validate(_)),
        Profile.Definition.onSubmit(Msg.Save(_)),
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
          button(typ := "submit", submission.replaceTextWith("Saving..."), "Save profile"),
          button(typ := "button", on.click(Msg.Reset), "Reset form")
        )
      )
    )
  end view

  private def field(content: Mod[Msg]*): HtmlElement[Msg] =
    div(cls := "docs-profile-field", content)
end ProfileFormExample

object ProfileFormExample:
  final case class Profile(name: String, email: String, biography: String)

  object Profile:
    val BiographyMaxLength = 500
    val Root               = FormRoot("profile")

    val Name = Root
      .string("name")
      .map(_.trim)
      .required("validation.name.required")

    val Email = Root
      .string("email")
      .map(_.trim)
      .required("validation.email.required")
      .validate("validation.email.invalid")(EmailPattern.matches)

    val Biography = Root
      .string("biography")
      .map(_.trim)
      .required("validation.biography.required")
      .validate("validation.biography.too_long")(
        _.length <= BiographyMaxLength
      )

    val Definition = Root.form(Profile.apply)(Name, Email, Biography)

    private val EmailPattern = """^[^\s@]+@[^\s@]+\.[^\s@]+$""".r

  private val messages = Map(
    "validation.name.required"      -> "Name is required.",
    "validation.email.required"     -> "Email is required.",
    "validation.email.invalid"      -> "Enter a valid email address.",
    "validation.biography.required" -> "Biography is required.",
    "validation.biography.too_long" ->
      s"Biography must be ${Profile.BiographyMaxLength} characters or fewer."
  )

  final case class Model(form: Profile.Definition.Form, saved: Option[Profile] = None)

  enum Msg:
    case Validate(event: FormEvent[Profile])
    case Save(event: FormEvent[Profile])
    case Reset
end ProfileFormExample
// docs:end profile-form-example
