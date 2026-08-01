package scalive.examples.auth

import zio.*
import zio.http.*

import scalive.examples.ExamplesRoutes
import scalive.{FormCodec, HttpFormDecoder, LiveSecurity}

final case class AuthHttpConfig(secureCookies: Boolean)
final case class AuthHttpConfigError(message: String) extends Exception(message)

object AuthHttpConfig:
  val SecureCookiesEnvironmentVariable = "SCALIVE_SECURE_COOKIES"
  val local                            = AuthHttpConfig(secureCookies = false)

  def fromEnvironment(
    environment: Map[String, String]
  ): IO[AuthHttpConfigError, AuthHttpConfig] =
    environment.get(SecureCookiesEnvironmentVariable) match
      case None                                          => ZIO.succeed(local)
      case Some(value) if value.equalsIgnoreCase("true") =>
        ZIO.succeed(AuthHttpConfig(secureCookies = true))
      case Some(value) if value.equalsIgnoreCase("false") =>
        ZIO.succeed(AuthHttpConfig(secureCookies = false))
      case Some(_) =>
        ZIO.fail(
          AuthHttpConfigError(
            s"$SecureCookiesEnvironmentVariable must be either true or false when set"
          )
        )

final class AuthHttpRoutes(
  security: LiveSecurity):
  import AuthHttpRoutes.*

  val routes: Routes[AuthService, Nothing] =
    Routes(
      SessionRoute -> handler { (request: Request) =>
        login(request)
      },
      LogoutRoute -> handler { (request: Request) =>
        logout(request)
      }
    )

  private val loginDecoder =
    HttpFormDecoder.urlEncoded(LoginForm.Definition.codec, FormMaxBytes, security.csrf)

  private val logoutDecoder =
    HttpFormDecoder.urlEncoded(FormCodec.formData, FormMaxBytes, security.csrf)

  private def login(request: Request): URIO[AuthService, Response] =
    loginDecoder.respond(request, _ => invalidLoginResponse, logMalformedLogin) { credentials =>
      ZIO.serviceWithZIO[AuthService](_.login(credentials)).map {
        case Some(result) =>
          ExamplesRoutes.profile.location.seeOther.addCookie(
            security.cookies.make(SessionCookieName, result.cookieToken.value)
          )
        case None => invalidLoginResponse
      }
    }

  private def logout(request: Request): URIO[AuthService, Response] =
    logoutDecoder.respond(request, _ => Response.forbidden) { _ =>
      val cookieToken = request
        .cookie(SessionCookieName)
        .map(cookie => SessionCookieToken(cookie.content))
      ZIO
        .foreachDiscard(cookieToken)(token => ZIO.serviceWithZIO[AuthService](_.logout(token)))
        .as(
          ExamplesRoutes.home.location.seeOther.addCookie(
            security.cookies.expire(SessionCookieName)
          )
        )
    }

  private def logMalformedLogin(error: HttpFormDecoder.Error): UIO[Unit] =
    error match
      case HttpFormDecoder.Error.Body(_) | HttpFormDecoder.Error.Representation(_) =>
        ZIO.logWarning(s"Rejected malformed login form: ${error.code}")
      case _ => ZIO.unit

  private def invalidLoginResponse: Response =
    security.flash.seeOther(
      ExamplesRoutes.login.location,
      LoginLiveView.InvalidLoginFlash -> LoginLiveView.InvalidLoginMessage
    )
end AuthHttpRoutes

object AuthHttpRoutes:
  val SessionCookieName = "__scalive_examples_session"

  val SessionRoute = Method.POST / "auth" / "session"
  val LogoutRoute  = Method.POST / "auth" / "logout"

  private[auth] val FormMaxBytes = 4096L
