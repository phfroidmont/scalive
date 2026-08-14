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
  def authenticated(
    auth: AuthService
  ): LiveMountAspect[Any, Any, Any, AuthClaims, CurrentSession] =
    LiveMountAspect.authenticated[Any, AuthClaims, CurrentSession](
      AuthHttpRoutes.SessionCookieName,
      AuthLabRoutes.login.location
    )(
      token =>
        auth
          .authenticate(SessionCookieToken(token))
          .map(_.map(current => AuthClaims(current.publicSessionId) -> current)),
      claims => auth.resume(claims.publicSessionId)
    )
// docs:end authentication-mount-aspect

final class AuthHttpRoutes(auth: AuthService, security: LiveSecurity):
  import AuthHttpRoutes.*

  val routes: Routes[Any, Nothing] = Routes(
    AuthLabRoutes.SessionRoute -> handler((request: Request) => login(request)),
    AuthLabRoutes.ResetRoute   -> handler((request: Request) => reset(request))
  )

  private val loginDecoder =
    HttpFormDecoder.urlEncoded(LoginForm.Definition.codec, FormMaxBytes, security.csrf)

  private val resetDecoder =
    HttpFormDecoder.urlEncoded(FormCodec.formData, FormMaxBytes, security.csrf)

  // docs:start authentication-http-actions
  private def login(request: Request): UIO[Response] =
    loginDecoder.respond(request, _ => invalidLoginResponse, logMalformedLogin) { credentials =>
      auth.login(visitor(request), credentials).map {
        case LoginDecision.Successful(result) =>
          AuthLabRoutes.profile.location.seeOther.addCookie(
            security.cookies.make(SessionCookieName, result.cookieToken.value)
          )
        case LoginDecision.Invalid     => invalidLoginResponse
        case LoginDecision.RateLimited => rateLimitedResponse
      }
    }

  private def reset(request: Request): UIO[Response] =
    resetDecoder.respond(request, _ => Response.forbidden) { _ =>
      val cookieToken = request
        .cookie(SessionCookieName)
        .map(cookie => SessionCookieToken(cookie.content))
      auth
        .reset(visitor(request), cookieToken)
        .as(
          AuthLabRoutes.login.location.seeOther.addCookie(
            security.cookies.expire(SessionCookieName)
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

  private def invalidLoginResponse: Response =
    security.flash.seeOther(
      AuthLabRoutes.login.location,
      LoginLiveView.LoginErrorFlash -> LoginLiveView.InvalidLoginMessage
    )

  private def rateLimitedResponse: Response =
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

  def render(model: Unit) =
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
  end render
end LoginLiveView

object LoginLiveView:
  val LoginErrorFlash     = FlashKind("error")
  val InvalidLoginMessage = "The sign-in request was invalid. Please try again."
  val RateLimitedMessage  = "Too many attempts. Wait one minute or reset the lab."

final class ProfileLiveView(currentSession: CurrentSession)
    extends LiveView.Eventless[CurrentSession]:
  def mount(ctx: MountContext) = ZIO.succeed(currentSession)

  def render(model: CurrentSession) =
    articleTag(
      cls := "docs-auth-lab",
      headerTag(
        cls := "docs-auth-header",
        p(cls := "docs-auth-kicker", "Authenticated session"),
        h1(s"Welcome, ${model.user.name}"),
        p(
          "The connected mount resumed this session from a signed, non-secret public identifier and checked the server record again."
        )
      ),
      sectionTag(
        cls := "docs-auth-card docs-auth-profile",
        dl(
          dt("Current user"),
          dd(model.user.email),
          dt("Public session ID"),
          dd(code(model.publicSessionId.value))
        ),
        scalive.Form.http(FormAction.from(AuthLabRoutes.ResetRoute))(
          button(typ := "submit", "Sign out and reset lab")
        )
      )
    )

object AuthLab:
  val loginRoute: LiveRouteFragment[Any, Any] = AuthLabRoutes.login(LoginLiveView())

  def protectedSession(auth: AuthService): LiveRouteFragment[Any, Any] =
    Live
      .session("documentation-authentication-lab")
      .withMountAspect(AuthMountAspect.authenticated(auth))(
        AuthLabRoutes.profile.context(ProfileLiveView.apply)
      )
