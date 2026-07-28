package scalive.examples.forms

import zio.ZIO

import scalive.*

final case class Profile(name: String, email: String, biography: String)

object Profile:
  val BiographyMaxLength = 500

  val codec: FormCodec[Profile] =
    FormCodec { data =>
      val fields    = data.nested("profile")
      val name      = fields.string("name").getOrElse("").trim
      val email     = fields.string("email").getOrElse("").trim
      val biography = fields.string("biography").getOrElse("").trim
      val errors    = Vector.newBuilder[FormError]

      if name.isEmpty then errors += FormError("name", "Name is required.")
      if email.isEmpty then errors += FormError("email", "Email is required.")
      else if !EmailPattern.matches(email) then
        errors += FormError("email", "Enter a valid email address.")
      if biography.isEmpty then errors += FormError("biography", "Biography is required.")
      else if biography.length > BiographyMaxLength then
        errors += FormError(
          "biography",
          s"Biography must be $BiographyMaxLength characters or fewer."
        )

      val accumulated = errors.result()
      if accumulated.isEmpty then Right(Profile(name, email, biography))
      else Left(FormErrors(accumulated))
    }

  private val EmailPattern = """^[^\s@]+@[^\s@]+\.[^\s@]+$""".r

final class ProfileFormLiveView
    extends LiveView[ProfileFormLiveView.Msg, ProfileFormLiveView.Model]:
  import ProfileFormLiveView.*

  def mount(ctx: MountContext) =
    ZIO.succeed(Model(initialState))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) => ZIO.succeed(model.copy(form = event.state, saved = None))
    case Msg.Save(event)     =>
      event.value match
        case Right(profile) => ZIO.succeed(model.copy(form = event.state, saved = Some(profile)))
        case Left(_)        => ZIO.succeed(model.copy(form = event.state, saved = None))

  def render(model: Model) =
    val profileForm = Form.of("profile", model.form, Profile.codec)

    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Forms"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Typed profile form"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "Form.of decodes change and submit events into Profile values while keeping raw input, used fields, and path-specific errors together."
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
          label(forId := profileForm.id("name"), cls := "label", span(cls := "label-text", "Name")),
          profileForm.text(
            "name",
            cls         := "input input-bordered w-full",
            placeholder := "Ada Lovelace"
          ),
          fieldErrors(profileForm, "name")
        ),
        field(
          label(
            forId := profileForm.id("email"),
            cls   := "label",
            span(cls := "label-text", "Email")
          ),
          profileForm.email(
            "email",
            cls         := "input input-bordered w-full",
            placeholder := "ada@example.com"
          ),
          fieldErrors(profileForm, "email")
        ),
        field(
          label(
            forId := profileForm.id("biography"),
            cls   := "label",
            span(cls := "label-text", "Biography")
          ),
          profileForm.textarea(
            "biography",
            cls         := "textarea textarea-bordered min-h-36 w-full",
            placeholder := s"Up to ${Profile.BiographyMaxLength} characters"
          ),
          fieldErrors(profileForm, "biography")
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
  final case class Model(form: FormState[Profile], saved: Option[Profile] = None)

  enum Msg:
    case Validate(event: FormEvent[Profile])
    case Save(event: FormEvent[Profile])

  private val initialState = FormState(
    raw = FormData.empty,
    value = Profile.codec.decode(FormData.empty),
    submitted = false
  )

  private def field(form: Mod[Msg]*): HtmlElement[Msg] =
    div(cls := "form-control", form)

  private def fieldErrors(form: Form[Profile], path: String): HtmlElement[Nothing] =
    if form.isUsed(path) then
      div(
        form.errorsFor(path).map(error => p(cls := "mt-2 text-sm text-error", error.message))
      )
    else div()
