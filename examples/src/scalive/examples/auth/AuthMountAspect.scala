package scalive.examples.auth

import zio.*
import zio.http.Response

import scalive.*
import scalive.examples.ExamplesRoutes

object AuthMountAspect:
  val authenticated: LiveMountAspect[AuthService, Any, Any, AuthClaims, CurrentSession] =
    LiveMountAspect.fromRequest[AuthService, Any, AuthClaims, CurrentSession](
      request =>
        ZIO
          .serviceWithZIO[AuthService] { authService =>
            request.request.cookie(AuthHttpRoutes.SessionCookieName) match
              case Some(cookie) =>
                authService.authenticate(SessionCookieToken(cookie.content))
              case None => ZIO.none
          }.flatMap {
            case Some(currentSession) =>
              ZIO.succeed(AuthClaims(currentSession.publicSessionId) -> currentSession)
            case None =>
              ZIO.fail(AuthHttpRoutes.seeOther(ExamplesRoutes.login.location))
          },
      (claims, _) =>
        ZIO.serviceWithZIO[AuthService](_.resume(claims.publicSessionId)).flatMap {
          case Some(currentSession) => ZIO.succeed(currentSession)
          case None => ZIO.fail(LiveMountFailure.redirect(ExamplesRoutes.login.location))
        }
    )

object LoginMountAspect:
  val prepared: LiveMountAspect[AuthService, Any, Any, LoginClaims, LoginContext] =
    LiveMountAspect.fromRequest[
      AuthService,
      Any,
      LoginClaims,
      LoginContext
    ](
      request =>
        ZIO
          .serviceWithZIO[AuthService] { authService =>
            request.request.cookie(AuthHttpRoutes.LoginContextCookieName) match
              case Some(cookie) =>
                authService.prepareLogin(LoginContextCookieToken(cookie.content))
              case None => ZIO.none
          }.flatMap {
            case Some(context) =>
              ZIO.succeed(LoginClaims(context.publicId) -> context)
            case None =>
              ZIO.fail(Response.seeOther(AuthHttpRoutes.loginBootstrapUrl(invalid = false)))
          },
      (claims, _) =>
        ZIO.serviceWithZIO[AuthService](_.resumeLogin(claims.publicId)).flatMap {
          case Some(context) => ZIO.succeed(context)
          case None          =>
            ZIO.fail(
              LiveMountFailure.redirectUnsafe(
                AuthHttpRoutes.loginBootstrapUrl(invalid = false)
              )
            )
        }
    )
end LoginMountAspect
