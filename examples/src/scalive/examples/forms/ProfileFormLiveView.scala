package scalive.examples.forms

import zio.ZIO

import scalive.*

final case class Profile(name: String, email: String, biography: String)

object Profile:
  val BiographyMaxLength = 500
  val Root               = FormRoot("profile")

  val Name = Root
    .requiredString("name", "Name is required.")
    .map(_.trim)
    .validate("Name is required.")(_.nonEmpty)

  val Email = Root
    .requiredString("email", "Email is required.")
    .map(_.trim)
    .validate("Email is required.")(_.nonEmpty)
    .validate("Enter a valid email address.")(EmailPattern.matches)

  val Biography = Root
    .requiredString("biography", "Biography is required.")
    .map(_.trim)
    .validate("Biography is required.")(_.nonEmpty)
    .validate(s"Biography must be $BiographyMaxLength characters or fewer.")(
      _.length <= BiographyMaxLength
    )

  val Definition = Root.form(
    Name.codec.zip(Email.codec).zip(Biography.codec).map { case ((name, email), biography) =>
      Profile(name, email, biography)
    }
  )

  private val EmailPattern = """^[^\s@]+@[^\s@]+\.[^\s@]+$""".r
end Profile

final class ProfileFormLiveView
    extends LiveView[ProfileFormLiveView.Msg, ProfileFormLiveView.Model]:
  import ProfileFormLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(Profile.Definition.initial()))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      ZIO.succeed(model.copy(form = Profile.Definition.from(event), saved = None))
    case Msg.Save(event) =>
      event.value match
        case Right(profile) =>
          ZIO.succeed(
            model.copy(form = Profile.Definition.from(event), saved = Some(profile))
          )
        case Left(_) =>
          ZIO.succeed(model.copy(form = Profile.Definition.from(event), saved = None))

  def render(model: Model) =
    val profileForm    = model.form
    val nameField      = profileForm.field(Profile.Name)
    val emailField     = profileForm.field(Profile.Email)
    val biographyField = profileForm.field(Profile.Biography)

    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Forms"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Typed profile form"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "The rooted form definition decodes change and submit events into Profile values while keeping raw input, used fields, and path-specific errors together."
        )
      ),
      model.saved.map { profile =>
        div(
          cls := "alert alert-success mb-6",
          span(s"Saved ${profile.name}'s profile.")
        )
      },
      form(
        cls := "max-w-2xl space-y-6",
        profileForm.onChange(Msg.Validate(_)),
        profileForm.onSubmit(Msg.Save(_)),
        field(
          label(forId := nameField.id, cls := "label", span(cls := "label-text", "Name")),
          nameField.text(
            cls         := "input input-bordered w-full",
            placeholder := "Ada Lovelace"
          ),
          fieldErrors(nameField)
        ),
        field(
          label(
            forId := emailField.id,
            cls   := "label",
            span(cls := "label-text", "Email")
          ),
          emailField.email(
            cls         := "input input-bordered w-full",
            placeholder := "ada@example.com"
          ),
          fieldErrors(emailField)
        ),
        field(
          label(
            forId := biographyField.id,
            cls   := "label",
            span(cls := "label-text", "Biography")
          ),
          biographyField.textarea(
            cls         := "textarea textarea-bordered min-h-36 w-full",
            placeholder := s"Up to ${Profile.BiographyMaxLength} characters"
          ),
          fieldErrors(biographyField)
        ),
        button(
          typ             := "submit",
          cls             := "btn btn-primary",
          phx.disableWith := "Saving...",
          "Save profile"
        )
      )
    )
  end render
end ProfileFormLiveView

object ProfileFormLiveView:
  final case class Model(
    form: RootedForm[Profile.Root.type, Profile],
    saved: Option[Profile] = None)

  enum Msg:
    case Validate(event: FormEvent[Profile])
    case Save(event: FormEvent[Profile])

  private def field(form: Mod[Msg]*): HtmlElement[Msg] =
    div(cls := "form-control", form)

  private def fieldErrors(field: Form.Field[?]): HtmlElement[Nothing] =
    if field.isUsed then
      div(
        field.errorsFor.map(error => p(cls := "mt-2 text-sm text-error", error.message))
      )
    else div()
