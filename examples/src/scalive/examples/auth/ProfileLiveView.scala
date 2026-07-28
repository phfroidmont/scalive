package scalive.examples.auth

import zio.*

import scalive.*

final class ProfileLiveView(currentSession: CurrentSession)
    extends LiveView.Eventless[CurrentSession]:
  import AuthHttpRoutes.*

  def mount(ctx: MountContext) =
    ZIO.succeed(currentSession)

  def render(model: CurrentSession) =
    div(
      headerTag(
        cls := "mb-8 border-b border-base-300 pb-7",
        div(cls := "badge badge-success badge-outline mb-4", "Authenticated"),
        h1(cls  := "text-4xl font-bold tracking-tight", s"Welcome, ${model.user.name}"),
        p(
          cls := "mt-4 max-w-3xl text-lg leading-8 text-base-content/70",
          "The disconnected mount authenticated the opaque cookie. The connected mount resumed this session from the non-secret public ID in signed claims."
        )
      ),
      sectionTag(
        cls := "max-w-2xl rounded-box border border-base-300 bg-base-100 p-7 shadow-sm",
        dl(
          cls := "grid gap-5 sm:grid-cols-[10rem_minmax(0,1fr)]",
          dt(cls := "font-semibold text-base-content/60", "Current user"),
          dd(model.user.email),
          dt(cls := "font-semibold text-base-content/60", "Public session ID"),
          dd(cls := "break-all font-mono text-sm", model.publicSessionId.value)
        ),
        form(
          action := LogoutPath,
          method := "post",
          cls    := "mt-8 border-t border-base-300 pt-6",
          input(
            typ      := "hidden",
            nameAttr := LogoutCsrfField,
            value    := model.logoutCsrfToken.value
          ),
          button(typ := "submit", cls := "btn btn-outline btn-error", "Sign out and revoke session")
        )
      )
    )
end ProfileLiveView
