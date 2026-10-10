package httpforms

import zio.*
import zio.http.{Request, Response, Routes, Status, handler}

import scalive.*

trait Sessions:
  def create(credentials: Credentials): IO[Sessions.Error, String]
  def revoke(token: String): UIO[Unit]

object Sessions:
  enum Error:
    case InvalidCredentials
    case Unavailable

// docs:start http-forms-handler
final class LoginHttpRoutes(security: LiveSecurity):
  private val FormMaxBytes = 4096L

  private val loginDecoder = HttpFormDecoder.urlEncodedValue(
    LoginForm.Definition,
    maxBytes = FormMaxBytes,
    csrf = security.csrf
  )

  private val signOutDecoder =
    HttpFormDecoder.urlEncoded(FormCodec.formData, maxBytes = FormMaxBytes, csrf = security.csrf)

  val routes: Routes[Sessions, Nothing] = Routes(
    SessionRoute -> handler((request: Request) => createSession(request)),
    SignOutRoute -> handler((request: Request) => signOut(request))
  )

  private def createSession(request: Request): URIO[Sessions, Response] =
    loginDecoder.respond(
      request,
      onValidation = _ => invalidLogin,
      onRejected = error => ZIO.logWarning(s"login form rejected code=${error.code}")
    ) { credentials =>
      ZIO
        .serviceWithZIO[Sessions](_.create(credentials))
        .map { token =>
          Pages.dashboard.location.seeOther
            .addCookie(security.cookies.make("session", token))
        }
        .catchAll {
          case Sessions.Error.InvalidCredentials => invalidLogin
          case Sessions.Error.Unavailable => ZIO.succeed(Response.status(Status.ServiceUnavailable))
        }
    }

  private def signOut(request: Request): URIO[Sessions, Response] =
    signOutDecoder.respond(request, onValidation = _ => ZIO.succeed(Response.forbidden)) { _ =>
      val revoke = ZIO.foreachDiscard(request.cookie("session")) { cookie =>
        ZIO.serviceWithZIO[Sessions](_.revoke(cookie.content))
      }
      revoke.as(Pages.login.location.seeOther.addCookie(security.cookies.expire("session")))
    }

  private def invalidLogin: UIO[Response] =
    security.flash.seeOther(
      Pages.login.location,
      LoginError -> "The sign-in request was invalid. Please try again."
    )
end LoginHttpRoutes
// docs:end http-forms-handler

object LoginApp:
  // docs:start http-forms-assembly
  def routes(config: ZioHttpConfig): Routes[Sessions, Nothing] =
    val security    = LiveSecurity(config)
    val application = Live.router(
      Pages.login     -> LoginLiveView(),
      Pages.dashboard -> DashboardLiveView()
    )
    ZioHttp.routes(application, config) ++ LoginHttpRoutes(security).routes
  // docs:end http-forms-assembly
