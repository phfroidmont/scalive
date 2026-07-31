package scalive.examples.auth

import zio.*

import scalive.*

final class LoginLiveView extends LiveView.Eventless[Unit]:
  import AuthHttpRoutes.*
  import LoginForm.*
  import LoginLiveView.*

  def mount(ctx: MountContext) =
    ZIO.unit

  def render(model: Unit) =
    val initialData = FormData(
      Vector(
        EmailPath.name -> DemoEmail
      )
    )
    val loginForm = Form.of(
      Root.name,
      FormState(initialData, codec.decode(initialData), submitted = false),
      codec
    )

    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-primary badge-outline mb-4", "Authentication"),
        h1(cls  := "text-4xl font-bold tracking-tight", "Sign in to the protected example"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "This LiveView renders a normal HTML form. Scalive adds browser-bound CSRF protection, then an ordinary HTTP handler validates the token and credentials before setting an opaque session cookie."
        )
      ),
      div(
        cls := "grid gap-6 lg:grid-cols-[minmax(0,32rem)_minmax(0,1fr)]",
        sectionTag(
          cls := "rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
          flash(InvalidLoginFlash) { message =>
            div(
              role := "alert",
              cls  := "alert alert-error mb-6",
              span(message)
            )
          },
          loginForm.http(FormAction.from(SessionRoute))(
            idAttr := FormId,
            cls    := "space-y-5",
            label(
              cls := "form-control w-full",
              span(cls := "label-text mb-2 font-semibold", "Email"),
              loginForm.email(
                "email",
                autoComplete := "username",
                maxLength    := EmailMaxLength,
                required     := true,
                cls          := "input input-bordered w-full"
              )
            ),
            label(
              cls := "form-control w-full",
              span(cls := "label-text mb-2 font-semibold", "Password"),
              loginForm.password(
                "password",
                autoComplete := "current-password",
                maxLength    := PasswordMaxLength,
                required     := true,
                cls          := "input input-bordered w-full"
              )
            ),
            button(typ := "submit", cls := "btn btn-primary w-full", "Sign in")
          )
        ),
        asideTag(
          cls := "rounded-box border border-base-300 bg-base-200 p-7",
          h2(cls := "text-lg font-semibold", "Demo credentials"),
          dl(
            cls := "mt-4 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-sm",
            dt(cls := "font-semibold text-base-content/60", "Email"),
            dd(cls := "font-mono", DemoEmail),
            dt(cls := "font-semibold text-base-content/60", "Password"),
            dd(cls := "font-mono", "scalive")
          )
        )
      )
    )
  end render
end LoginLiveView

object LoginLiveView:
  private val DemoEmail = "alice@example.com"

  private[auth] val InvalidLoginFlash   = FlashKind("error")
  private[auth] val InvalidLoginMessage = "The sign-in request was invalid. Please try again."
