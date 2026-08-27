package scalive.docs.auth

import zio.*
import zio.http.*

import scalive.*

object AuthLabRoutes:
  val LoginPath   = "/examples/authentication/lab"
  val ProfilePath = "/examples/authentication/lab/profile"

  val login   = live / "examples" / "authentication" / "lab"
  val profile = login / "profile"

  val SessionRoute = Method.POST / "examples" / "authentication" / "lab" / "session"
  val ResetRoute   = Method.POST / "examples" / "authentication" / "lab" / "reset"

// docs:start authentication-mount-aspect
object AuthMountAspect:
  val authenticated: LiveMountAspect[AuthService, Any, Any, AuthClaims, CurrentUser] =
    LiveMountAspect.fromRequest(
      request =>
        request.request.cookie(AuthHttpRoutes.SessionCookieName) match
          case None         => ZIO.fail(AuthLabRoutes.login.location.seeOther)
          case Some(cookie) =>
            ZIO
              .serviceWithZIO[AuthService](
                _.authenticate(SessionCookieToken(cookie.content))
              ).someOrFail(AuthLabRoutes.login.location.seeOther)
              .map(current =>
                AuthClaims(current.publicSessionId) ->
                  CurrentUser(current.user.email, current.user.name)
              ),
      (claims, _) =>
        ZIO
          .serviceWithZIO[AuthService](
            _.resume(claims.publicSessionId)
          ).someOrFail(LiveMountFailure.redirect(AuthLabRoutes.login.location))
          .map(current => CurrentUser(current.user.email, current.user.name))
    )
// docs:end authentication-mount-aspect

final class AuthHttpRoutes(security: LiveSecurity):
  import AuthHttpRoutes.*

  val routes: Routes[AuthService & LiveConnections[PublicSessionId], Nothing] = Routes(
    AuthLabRoutes.SessionRoute -> handler((request: Request) => login(request)),
    AuthLabRoutes.ResetRoute   -> handler((request: Request) => reset(request))
  )

  private val loginDecoder =
    HttpFormDecoder.urlEncoded(LoginForm.Definition.codec, FormMaxBytes, security.csrf)

  private val resetDecoder =
    HttpFormDecoder.urlEncoded(FormCodec.formData, FormMaxBytes, security.csrf)

  // docs:start authentication-http-actions
  private def login(request: Request): URIO[AuthService, Response] =
    loginDecoder
      .decode(request).foldZIO(
        error =>
          logMalformedLogin(error) *> (error match
            case HttpFormDecoder.Error.Validation(_) => invalidLoginResponse
            case _ => ZIO.succeed(error.toResponse(_ => Response.badRequest))),
        credentials =>
          ZIO.serviceWithZIO[AuthService](_.login(visitor(request), credentials)).flatMap {
            case LoginDecision.Successful(result) =>
              ZIO.succeed(
                AuthLabRoutes.profile.location.seeOther
                  .addCookie(security.cookies.make(SessionCookieName, result.cookieToken.value))
              )
            case LoginDecision.Invalid     => invalidLoginResponse
            case LoginDecision.RateLimited => rateLimitedResponse
          }
      )

  private def reset(
    request: Request
  ): URIO[AuthService & LiveConnections[PublicSessionId], Response] =
    resetDecoder.respond(request, _ => Response.forbidden) { _ =>
      val cookieToken = request
        .cookie(SessionCookieName)
        .map(cookie => SessionCookieToken(cookie.content))
      val revokeAndDisconnect = for
        publicSessionId <- ZIO.serviceWithZIO[AuthService](
                             _.reset(visitor(request), cookieToken)
                           )
        _ <- ZIO.foreachDiscard(publicSessionId)(LiveConnections.disconnect(_))
      yield AuthLabRoutes.login.location.seeOther
        .addCookie(security.cookies.expire(SessionCookieName))

      revokeAndDisconnect.catchAll(error =>
        ZIO.logErrorCause("Authentication reset fanout failed", Cause.fail(error)) *>
          ZIO.succeed(
            Response.internalServerError
              .addCookie(security.cookies.expire(SessionCookieName))
          )
      )
    }
  // docs:end authentication-http-actions

  private def visitor(request: Request): VisitorToken =
    VisitorToken(request.cookie(CsrfProtection.CookieName).fold("missing")(_.content))

  private def logMalformedLogin(error: HttpFormDecoder.Error): UIO[Unit] =
    error match
      case HttpFormDecoder.Error.Body(_) | HttpFormDecoder.Error.Representation(_) =>
        ZIO.logWarning(s"Rejected malformed authentication lab form: ${error.code}")
      case _ => ZIO.unit

  private def invalidLoginResponse: UIO[Response] =
    security.flash.seeOther(
      AuthLabRoutes.login.location,
      LoginLiveView.LoginErrorFlash -> LoginLiveView.InvalidLoginMessage
    )

  private def rateLimitedResponse: UIO[Response] =
    security.flash.seeOther(
      AuthLabRoutes.login.location,
      LoginLiveView.LoginErrorFlash -> LoginLiveView.RateLimitedMessage
    )
end AuthHttpRoutes

object AuthHttpRoutes:
  val SessionCookieName    = "__scalive_docs_auth_lab"
  private val FormMaxBytes = 4096L

final class LoginLiveView extends LiveView.Eventless[Unit]:
  import LoginForm.*
  import LoginLiveView.*

  def mount(ctx: MountContext) = ZIO.unit

  def view(model: Signal[Unit]): HtmlElement[Nothing] =
    val loginForm     = Definition.initial(Email.initial(AuthService.DemoEmail))
    val emailField    = loginForm.field(Email)
    val passwordField = loginForm.field(Password)

    articleTag(
      cls := "docs-auth-lab",
      headerTag(
        cls := "docs-auth-header",
        p(cls := "docs-auth-kicker", "Standalone authentication lab"),
        h1("Sign in to a protected LiveView"),
        p(
          "This form submits through ordinary HTTP. The protected route then authenticates the opaque cookie during disconnected mount and revalidates public claims when the socket connects."
        )
      ),
      div(
        cls := "docs-auth-grid",
        sectionTag(
          cls := "docs-auth-card",
          flash(LoginErrorFlash)(message =>
            div(role := "alert", cls := "docs-auth-alert", message)
          ),
          loginForm.http(FormAction.from(AuthLabRoutes.SessionRoute))(
            idAttr := FormId,
            cls    := "docs-auth-form",
            label(
              "Email",
              emailField.email(
                autoComplete := "username",
                maxLength    := EmailMaxLength,
                required     := true
              )
            ),
            label(
              "Password",
              passwordField.password(
                autoComplete := "current-password",
                maxLength    := PasswordMaxLength,
                required     := true
              )
            ),
            button(typ := "submit", "Sign in")
          )
        ),
        asideTag(
          cls := "docs-auth-credentials",
          h2("Fixed lab credentials"),
          dl(
            dt("Email"),
            dd(code(AuthService.DemoEmail)),
            dt("Password"),
            dd(code(AuthService.DemoPassword))
          ),
          p(
            "Five failed attempts pause this browser for one minute. Other visitors are unaffected."
          )
        )
      )
    )
  end view
end LoginLiveView

object LoginLiveView:
  val LoginErrorFlash     = FlashKind("error")
  val InvalidLoginMessage = "The sign-in request was invalid. Please try again."
  val RateLimitedMessage  = "Too many attempts. Wait one minute or reset the lab."

final class ProfileLiveView(currentUser: CurrentUser) extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = ZIO.unit

  def view(model: Signal[Unit]): HtmlElement[Nothing] =
    articleTag(
      cls := "docs-auth-lab",
      headerTag(
        cls := "docs-auth-header",
        p(cls := "docs-auth-kicker", "Authenticated session"),
        h1(s"Welcome, ${currentUser.name}"),
        p(
          "The connected mount resumed this session from a signed, non-secret public identifier and checked the server record again."
        )
      ),
      sectionTag(
        cls := "docs-auth-card docs-auth-profile",
        dl(dt("Current user"), dd(currentUser.email)),
        scalive.Form.http(FormAction.from(AuthLabRoutes.ResetRoute))(
          button(typ := "submit", "Sign out and reset lab")
        )
      )
    )

object AuthLab:
  val loginRoute: LiveRouteFragment[Any] { type Input = Any } =
    AuthLabRoutes.login(LoginLiveView())

  val protectedSession
    : LiveRouteFragment[AuthService & LiveConnections[PublicSessionId]] { type Input = Any } =
    Live
      .session("documentation-authentication-lab")
      .withAdmission(AuthMountAspect.authenticated)(_.publicSessionId)(
        AuthLabRoutes.profile.context(ProfileLiveView.apply)
      )
