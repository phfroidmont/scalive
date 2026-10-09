package httpforms

import zio.*

import scalive.*

// docs:start http-forms-trigger
final class ValidatedLoginLiveView
    extends LiveView[ValidatedLoginLiveView.Msg, ValidatedLoginLiveView.Model]:
  import ValidatedLoginLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(LoginForm.Definition.initial()))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      ZIO.succeed(Model(event.form))
    case Msg.Submit(event) =>
      ZIO.succeed(Model(event.form, submitHttp = event.form.isValid))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val form     = model.map(_.form)
    val email    = form.field(LoginForm.Email)
    val password = form.field(LoginForm.Password)

    Form.http(FormAction.from(SessionRoute))(
      idAttr := "login-form",
      LoginForm.Definition.onChange(Msg.Validate(_)),
      LoginForm.Definition.onSubmit(Msg.Submit(_)),
      phx.triggerAction := model.map(_.submitHttp),
      label(forId              := email.id, "Email"),
      email.email(autoComplete := "username"),
      email.errorFeedback(error => error.map(_.message)),
      label(forId                    := password.id, "Password"),
      password.password(autoComplete := "current-password"),
      password.errorFeedback(error => error.map(_.message)),
      button(typ := "submit", submission.replaceTextWith("Signing in..."), "Sign in")
    )
end ValidatedLoginLiveView

object ValidatedLoginLiveView:
  final case class Model(form: LoginForm.Definition.Form, submitHttp: Boolean = false)

  enum Msg:
    case Validate(event: LoginForm.Definition.Event)
    case Submit(event: LoginForm.Definition.Event)
// docs:end http-forms-trigger
