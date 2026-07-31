package scalive.examples.auth

import zio.*
import zio.http.*

import scalive.examples.ExamplesRoutes
import scalive.{CsrfProtection, FormData, LiveLocation}

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
  config: AuthHttpConfig,
  csrfProtection: CsrfProtection):
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

  private def login(request: Request): UIO[Response] =
    FormData.fromUrlEncodedBody(request.body, FormMaxBytes).either.flatMap {
      case Left(error) =>
        ZIO
          .logWarning(
            s"Rejected malformed login form: ${formDecodeErrorName(error)}"
          ).as(formDecodeErrorResponse(error))
      case Right(data) =>
        csrfProtection.validate(request, data) match
          case Left(_)  => ZIO.succeed(Response.forbidden)
          case Right(_) =>
            LoginForm.codec.decode(data) match
              case Left(_)            => ZIO.succeed(invalidLoginResponse)
              case Right(credentials) =>
                authService.login(credentials).map {
                  case Some(result) =>
                    seeOther(ExamplesRoutes.profile.location).addCookie(
                      sessionCookie(result.cookieToken, config.secureCookies)
                    )
                  case None => invalidLoginResponse
                }
    }

  private def logout(request: Request): UIO[Response] =
    FormData.fromUrlEncodedBody(request.body, FormMaxBytes).either.flatMap {
      case Left(error) => ZIO.succeed(formDecodeErrorResponse(error))
      case Right(data) =>
        val cookieToken = request
          .cookie(SessionCookieName)
          .map(cookie => SessionCookieToken(cookie.content))

        (csrfProtection.validate(request, data), cookieToken) match
          case (Right(_), Some(token)) =>
            authService.logout(token).map {
              case true =>
                seeOther(ExamplesRoutes.home.location).addCookie(
                  expiredSessionCookie(config.secureCookies)
                )
              case false => Response.forbidden
            }
          case _ => ZIO.succeed(Response.forbidden)
    }

  private def invalidLoginResponse: Response =
    seeOther(ExamplesRoutes.login.location(Some(true)))
end AuthHttpRoutes

object AuthHttpRoutes:
  val SessionCookieName = "__scalive_examples_session"

  val SessionRoute = Method.POST / "auth" / "session"
  val LogoutRoute  = Method.POST / "auth" / "logout"

  private[auth] val FormMaxBytes = 4096L

  private[auth] def seeOther(location: LiveLocation): Response =
    URL.decode(location.href).fold(_ => Response.internalServerError, Response.seeOther)

  private def formDecodeErrorResponse(error: FormData.DecodeError): Response =
    val status = error match
      case FormData.DecodeError.InvalidContentType(_) => Status.UnsupportedMediaType
      case FormData.DecodeError.BodyTooLarge(_)       => Status.RequestEntityTooLarge
      case _                                          => Status.BadRequest
    status.toResponse

  private def formDecodeErrorName(error: FormData.DecodeError): String =
    error match
      case FormData.DecodeError.InvalidContentType(_)     => "invalid_content_type"
      case FormData.DecodeError.BodyTooLarge(_)           => "body_too_large"
      case FormData.DecodeError.BodyRead(_)               => "body_read"
      case FormData.DecodeError.InvalidUrlEncoding(_)     => "invalid_url_encoding"
      case FormData.DecodeError.UnsupportedField(_, kind) => s"unsupported_$kind"

  private def sessionCookie(
    token: SessionCookieToken,
    secureCookies: Boolean
  ): Cookie.Response =
    Cookie.Response(
      SessionCookieName,
      token.value,
      path = Some(Path.root),
      isSecure = secureCookies,
      isHttpOnly = true,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  private def expiredSessionCookie(secureCookies: Boolean): Cookie.Response =
    Cookie.Response(
      SessionCookieName,
      "",
      path = Some(Path.root),
      isSecure = secureCookies,
      isHttpOnly = true,
      maxAge = Some(Duration.Zero),
      sameSite = Some(Cookie.SameSite.Lax)
    )
end AuthHttpRoutes
