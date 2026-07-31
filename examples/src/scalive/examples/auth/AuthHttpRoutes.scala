package scalive.examples.auth

import zio.*
import zio.http.*

import scalive.examples.ExamplesRoutes
import scalive.{FormCodec, FormData, HttpFormDecoder, LiveLocation, LiveSecurity}

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
  authService: AuthService,
  security: LiveSecurity):
  import AuthHttpRoutes.*

  val routes: Routes[Any, Nothing] =
    Routes(
      SessionRoute -> handler { (request: Request) =>
        login(request)
      },
      LogoutRoute -> handler { (request: Request) =>
        logout(request)
      }
    )

  private val loginDecoder =
    HttpFormDecoder.urlEncoded(LoginForm.codec, FormMaxBytes, security.csrf)

  private val logoutDecoder =
    HttpFormDecoder.urlEncoded(FormCodec.formData, FormMaxBytes, security.csrf)

  private def login(request: Request): UIO[Response] =
    loginDecoder.decode(request).either.flatMap {
      case Left(error @ HttpFormDecoder.Error.Body(_)) =>
        ZIO
          .logWarning(
            s"Rejected malformed login form: ${formDecodeErrorName(error)}"
          ).as(formDecodeErrorResponse(error))
      case Left(error @ HttpFormDecoder.Error.Representation(_)) =>
        ZIO
          .logWarning(
            s"Rejected malformed login form: ${formDecodeErrorName(error)}"
          ).as(formDecodeErrorResponse(error))
      case Left(HttpFormDecoder.Error.Csrf(_))       => ZIO.succeed(Response.forbidden)
      case Left(HttpFormDecoder.Error.Validation(_)) => ZIO.succeed(invalidLoginResponse)
      case Right(credentials)                        =>
        authService.login(credentials).map {
          case Some(result) =>
            seeOther(ExamplesRoutes.profile.location).addCookie(
              security.cookies.make(SessionCookieName, result.cookieToken.value)
            )
          case None => invalidLoginResponse
        }
    }

  private def logout(request: Request): UIO[Response] =
    logoutDecoder.decode(request).either.flatMap {
      case Left(error @ HttpFormDecoder.Error.Body(_)) =>
        ZIO.succeed(formDecodeErrorResponse(error))
      case Left(error @ HttpFormDecoder.Error.Representation(_)) =>
        ZIO.succeed(formDecodeErrorResponse(error))
      case Left(HttpFormDecoder.Error.Csrf(_))       => ZIO.succeed(Response.forbidden)
      case Left(HttpFormDecoder.Error.Validation(_)) => ZIO.succeed(Response.forbidden)
      case Right(_)                                  =>
        val cookieToken = request
          .cookie(SessionCookieName)
          .map(cookie => SessionCookieToken(cookie.content))

        cookieToken match
          case Some(token) =>
            authService.logout(token).map {
              case true =>
                seeOther(ExamplesRoutes.home.location).addCookie(
                  security.cookies.expire(SessionCookieName)
                )
              case false => Response.forbidden
            }
          case None => ZIO.succeed(Response.forbidden)
    }

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

  private[auth] def seeOther(location: LiveLocation): Response =
    Response.seeOther(location.url)

  private def formDecodeErrorResponse(error: HttpFormDecoder.Error): Response =
    val status = error match
      case HttpFormDecoder.Error.Representation(
            FormData.RepresentationError.InvalidContentType(_)
          ) =>
        Status.UnsupportedMediaType
      case HttpFormDecoder.Error.Body(FormData.BodyError.TooLarge(_)) =>
        Status.RequestEntityTooLarge
      case _ => Status.BadRequest
    status.toResponse

  private def formDecodeErrorName(error: HttpFormDecoder.Error): String =
    error match
      case HttpFormDecoder.Error.Representation(
            FormData.RepresentationError.InvalidContentType(_)
          ) =>
        "invalid_content_type"
      case HttpFormDecoder.Error.Body(FormData.BodyError.TooLarge(_)) => "body_too_large"
      case HttpFormDecoder.Error.Body(FormData.BodyError.Read(_))     => "body_read"
      case HttpFormDecoder.Error.Representation(
            FormData.RepresentationError.InvalidUrlEncoding(_)
          ) =>
        "invalid_url_encoding"
      case HttpFormDecoder.Error.Representation(
            FormData.RepresentationError.UnsupportedField(_, kind)
          ) =>
        s"unsupported_$kind"
      case HttpFormDecoder.Error.Csrf(_)       => "csrf"
      case HttpFormDecoder.Error.Validation(_) => "validation"
end AuthHttpRoutes
