package httpforms

import zio.*

import scalive.*

// docs:start http-forms-static-form
final class LoginLiveView extends LiveView.Eventless[LoginForm.Definition.Form]:
  def mount(ctx: MountContext): Task[LoginForm.Definition.Form] =
    ZIO.succeed(LoginForm.Definition.initial())

  def view(model: Signal[LoginForm.Definition.Form]): HtmlElement[Nothing] =
    val email    = model.field(LoginForm.Email)
    val password = model.field(LoginForm.Password)

    mainTag(
      flash(LoginError)(message => div(role := "alert", message)),
      Form.http(FormAction.from(SessionRoute))(
        idAttr := "login-form",
        label(forId                    := email.id, "Email"),
        email.email(autoComplete       := "username", required         := true),
        label(forId                    := password.id, "Password"),
        password.password(autoComplete := "current-password", required := true),
        button(typ                     := "submit", "Sign in")
      )
    )
  // docs:end http-forms-static-form

final class DashboardLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext): Task[Unit] = ZIO.unit

  // docs:start http-forms-sign-out
  def view(model: Signal[Unit]): HtmlElement[Nothing] =
    mainTag(
      h1("Dashboard"),
      Form.http(FormAction.from(SignOutRoute))(
        button(typ := "submit", "Sign out")
      )
    )
  // docs:end http-forms-sign-out
