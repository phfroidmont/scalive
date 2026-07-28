package scalive.examples.auth

import zio.*
import zio.http.*

import scalive.LiveLocation
import scalive.examples.ExamplesRoutes

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

final class AuthHttpRoutes(authService: AuthService, config: AuthHttpConfig):
  import AuthHttpRoutes.*

  val routes: Routes[Any, Nothing] =
    Routes(
      Method.GET / "auth" / "login" / "bootstrap" -> handler { (request: Request) =>
        bootstrap(request)
      },
      Method.POST / "auth" / "session" -> handler { (request: Request) =>
        login(request)
      },
      Method.POST / "auth" / "logout" -> handler { (request: Request) =>
        logout(request)
      }
    )

  private def bootstrap(request: Request): UIO[Response] =
    authService.beginLogin.map { loginBootstrap =>
      val invalid = request.queryParam("invalid").contains("true")
      Response
        .seeOther(loginUrl(invalid)).addCookie(
          loginContextCookie(loginBootstrap.cookieToken, config.secureCookies)
        )
    }

  private def login(request: Request): UIO[Response] =
    request.body.asURLEncodedForm.option.flatMap { form =>
      val cookieToken = request
        .cookie(LoginContextCookieName)
        .map(cookie => LoginContextCookieToken(cookie.content))

      cookieToken match
        case Some(cookieToken) =>
          val csrf = form
            .flatMap(field(_, LoginCsrfField))
            .map(LoginCsrfToken(_))
            .getOrElse(LoginCsrfToken(""))
          val email    = form.flatMap(field(_, EmailField)).getOrElse("")
          val password = form.flatMap(field(_, PasswordField)).getOrElse("")

          authService.login(cookieToken, csrf, email, password).map {
            case Some(result) =>
              seeOther(ExamplesRoutes.profile.location).addCookies(
                sessionCookie(result.cookieToken, config.secureCookies),
                expiredLoginContextCookie(config.secureCookies)
              )
            case None => invalidLoginResponse
          }
        case None => ZIO.succeed(invalidLoginResponse)
    }

  private def logout(request: Request): UIO[Response] =
    request.body.asURLEncodedForm.option.flatMap { form =>
      val submitted = for
        cookie      <- request.cookie(SessionCookieName)
        decodedForm <- form
        csrf        <- field(decodedForm, LogoutCsrfField)
      yield SessionCookieToken(cookie.content) -> LogoutCsrfToken(csrf)

      submitted match
        case Some((cookieToken, csrfToken)) =>
          authService.logout(cookieToken, csrfToken).map {
            case true =>
              seeOther(ExamplesRoutes.home.location).addCookie(
                expiredSessionCookie(config.secureCookies)
              )
            case false => Response.forbidden
          }
        case None => ZIO.succeed(Response.forbidden)
    }

  private def field(form: Form, name: String): Option[String] =
    form.get(name).flatMap(_.stringValue)

  private def invalidLoginResponse: Response =
    Response
      .seeOther(loginBootstrapUrl(invalid = true))
      .addCookie(expiredLoginContextCookie(config.secureCookies))
end AuthHttpRoutes

object AuthHttpRoutes:
  val LoginContextCookieName = "__scalive_examples_login"
  val SessionCookieName      = "__scalive_examples_session"
  val LoginBootstrapPath     = "/auth/login/bootstrap"
  val SessionPath            = "/auth/session"
  val LogoutPath             = "/auth/logout"
  val LoginCsrfField         = "login_csrf"
  val LogoutCsrfField        = "logout_csrf"
  val EmailField             = "email"
  val PasswordField          = "password"

  private val LoginContextCookieMaxAge = AuthServiceConfig.DefaultLoginContextTtl

  private[auth] def seeOther(location: LiveLocation): Response =
    URL.decode(location.href).fold(_ => Response.internalServerError, Response.seeOther)

  private[auth] def loginBootstrapUrl(invalid: Boolean): URL =
    val base = URL(Path.root / "auth" / "login" / "bootstrap")
    if invalid then base.addQueryParam("invalid", "true") else base

  private def loginUrl(invalid: Boolean): URL =
    val base = URL.decode(ExamplesRoutes.login.location.href).getOrElse(URL.root)
    if invalid then base.addQueryParam("invalid", "true") else base

  private def loginContextCookie(
    token: LoginContextCookieToken,
    secureCookies: Boolean
  ): Cookie.Response =
    Cookie.Response(
      LoginContextCookieName,
      token.value,
      path = Some(Path.root),
      isSecure = secureCookies,
      isHttpOnly = true,
      maxAge = Some(LoginContextCookieMaxAge),
      sameSite = Some(Cookie.SameSite.Lax)
    )

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

  private def expiredLoginContextCookie(secureCookies: Boolean): Cookie.Response =
    expiredCookie(LoginContextCookieName, secureCookies)

  private def expiredSessionCookie(secureCookies: Boolean): Cookie.Response =
    expiredCookie(SessionCookieName, secureCookies)

  private def expiredCookie(name: String, secureCookies: Boolean): Cookie.Response =
    Cookie.Response(
      name,
      "",
      path = Some(Path.root),
      isSecure = secureCookies,
      isHttpOnly = true,
      maxAge = Some(Duration.Zero),
      sameSite = Some(Cookie.SameSite.Lax)
    )

end AuthHttpRoutes
