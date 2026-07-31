package scalive.examples.auth

import zio.*

import scalive.*
import scalive.examples.ExamplesRoutes

object AuthMountAspect:
  val authenticated =
    LiveMountAspect.authenticated(
      AuthHttpRoutes.SessionCookieName,
      ExamplesRoutes.login.location
    )(
      token =>
        ZIO
          .serviceWithZIO[AuthService](_.authenticate(SessionCookieToken(token)))
          .map(_.map(current => AuthClaims(current.publicSessionId) -> current)),
      claims => ZIO.serviceWithZIO[AuthService](_.resume(claims.publicSessionId))
    )
