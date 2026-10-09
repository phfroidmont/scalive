// docs:start learn-signup-live-view
package quickstart

import zio.{Task, ZIO}

import scalive.*

final class SignupLiveView extends LiveView[SignupLiveView.Msg, SignupLiveView.Model]:
  import SignupLiveView.*

  // docs:start learn-signup-mount
  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(form = Signup.Definition.initial(), signedUp = None))
  // docs:end learn-signup-mount

  def handleMessage(model: Model, ctx: MessageContext) =
    // docs:start learn-signup-change
    case Msg.Changed(event) =>
      ZIO.succeed(model.copy(form = event.form))
    // docs:end learn-signup-change
    // docs:start learn-signup-submit
    case Msg.Submitted(event) =>
      event.form.result match
        case Right(signup) =>
          ZIO.succeed(Model(form = Signup.Definition.initial(), signedUp = Some(signup)))
        case Left(_) =>
          ZIO.succeed(model.copy(form = event.form))
    // docs:end learn-signup-submit

  // docs:start learn-signup-view
  def view(model: Signal[Model]): HtmlElement[Msg] =
    val signupForm = model.map(_.form)
    val name       = signupForm.field(Signup.Name)
    val email      = signupForm.field(Signup.Email)

    mainTag(
      h1("Sign up for updates"),
      form(
        idAttr := "signup-form",
        Signup.Definition.onChange(Msg.Changed(_)),
        Signup.Definition.onSubmit(Msg.Submitted(_)),
        label(forId := name.id, "Name"),
        name.text(name.validationAttributes),
        name.errorFeedback(error => error.map(_.message)),
        label(forId := email.id, "Email"),
        email.email(email.validationAttributes),
        email.errorFeedback(error => error.map(_.message)),
        button(typ := "submit", "Sign up")
      ),
      model.map(_.signedUp).option { signup =>
        p(role := "status", signup.map(signup => s"Thanks, ${signup.name}. Check ${signup.email}."))
      }
    )
  // docs:end learn-signup-view
end SignupLiveView

object SignupLiveView:
  // docs:start learn-signup-model
  final case class Model(form: Signup.Definition.Form, signedUp: Option[Signup])

  enum Msg:
    case Changed(event: Signup.Definition.Event)
    case Submitted(event: Signup.Definition.Event)
  // docs:end learn-signup-model
// docs:end learn-signup-live-view
