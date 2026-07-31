package scalive.examples.auth

import zio.*

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
              ZIO.fail(AuthHttpRoutes.seeOther(ExamplesRoutes.login.location(None)))
          },
      (claims, _) =>
        ZIO.serviceWithZIO[AuthService](_.resume(claims.publicSessionId)).flatMap {
          case Some(currentSession) => ZIO.succeed(currentSession)
          case None => ZIO.fail(LiveMountFailure.redirect(ExamplesRoutes.login.location(None)))
        }
    )
