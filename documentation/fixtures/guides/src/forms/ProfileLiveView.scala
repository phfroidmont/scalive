// docs:start forms-liveview
package forms

import zio.*

import scalive.*

final class ProfileLiveView(profiles: Profiles)
    extends LiveView[ProfileLiveView.Msg, ProfileLiveView.Model]:
  import ProfileLiveView.*

  // docs:start forms-mount
  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(Profile.Definition.initial()))
  // docs:end forms-mount

  def handleMessage(model: Model, ctx: MessageContext) =
    // docs:start forms-change
    case Msg.Validate(event) =>
      ZIO.succeed(model.copy(form = event.form, saved = None))
    // docs:end forms-change
    // docs:start forms-submit
    case Msg.Save(event) =>
      event.form.result match
        case Right(profile) =>
          profiles.save(profile).as(model.copy(form = event.form, saved = Some(profile)))
        case Left(_) =>
          ZIO.succeed(model.copy(form = event.form, saved = None))
    // docs:end forms-submit
    // docs:start forms-update
    case Msg.UseExampleName =>
      ZIO.succeed(model.copy(form = model.form.updated(Profile.Name, "Grace Hopper")))
    // docs:end forms-update
    // docs:start forms-reset
    case Msg.Reset =>
      ZIO.succeed(Model(Profile.Definition.initial()))
    // docs:end forms-reset

  // docs:start forms-render
  def view(model: Signal[Model]): HtmlElement[Msg] =
    val profileForm = model.map(_.form)
    val name        = profileForm.field(Profile.Name)
    val email       = profileForm.field(Profile.Email)

    mainTag(
      form(
        idAttr := "profile-form",
        Profile.Definition.onChange(Msg.Validate(_)),
        Profile.Definition.onSubmit(Msg.Save(_)),
        label(forId := name.id, "Name"),
        name.text(name.validationAttributes),
        name.errorFeedback(error => error.map(value => message(value.message))),
        label(forId := email.id, "Email"),
        email.email(email.validationAttributes),
        email.errorFeedback(error => error.map(value => message(value.message))),
        button(typ := "button", on.click(Msg.UseExampleName), "Use example name"),
        button(typ := "submit", "Save"),
        button(typ := "button", on.click(Msg.Reset), "Reset")
      ),
      model.map(_.saved).option { saved =>
        p(role := "status", saved.map(profile => s"Saved ${profile.name}."))
      }
    )
  // docs:end forms-render
end ProfileLiveView

object ProfileLiveView:
  // docs:start forms-model
  final case class Model(form: Profile.Definition.Form, saved: Option[Profile] = None)

  enum Msg:
    case Validate(event: Profile.Definition.Event)
    case Save(event: Profile.Definition.Event)
    case UseExampleName
    case Reset
  // docs:end forms-model

  // docs:start forms-messages
  private val messages = Map(
    "validation.name.required"  -> "Name is required.",
    "validation.email.required" -> "Email is required.",
    "validation.email.invalid"  -> "Enter a valid email address."
  )

  private def message(key: String): String = messages.getOrElse(key, key)
  // docs:end forms-messages

  // docs:start forms-initial-values
  def editing(existing: Profile): Profile.Definition.Form =
    Profile.Definition.initial(
      Profile.Name.initial(existing.name),
      Profile.Email.initial(existing.email)
    )
  // docs:end forms-initial-values
// docs:end forms-liveview
