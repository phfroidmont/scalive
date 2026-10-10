package auth

import zio.*
import zio.http.{Routes, Server}

import scalive.*

object AuthApp:
  // docs:start auth-protected-routes
  val protectedRoutes =
    Live
      .session("authenticated")
      .withAdmission(AuthMountAspect.authenticated)(_.publicSessionId)(
        Pages.profile.context((auth: ConnectedAuth, accounts: Accounts) =>
          ProfileLiveView(auth.currentUser, accounts)
        ),
        Pages.status(StatusLiveView())
      )
  // docs:end auth-protected-routes

  def serve(
    config: ZioHttpConfig,
    authHttpRoutes: Routes[AuthService & LiveConnections[PublicSessionId], Nothing]
  ): Task[Unit] =
    val application = Live.router(protectedRoutes)
    val routes      = ZioHttp.routes(application, config) ++ authHttpRoutes
    // docs:start auth-provide-local
    Server
      .serve(routes)
      .provide(
        Server.default,
        AuthService.live,
        Accounts.live,
        LiveConnections.local[PublicSessionId]
      )
    // docs:end auth-provide-local

  // docs:start auth-provide-distributed
  def liveConnections(
    bus: ZLayer[Any, Throwable, LiveDisconnectBus[PublicSessionId]]
  ): ZLayer[Any, Throwable, LiveConnections[PublicSessionId]] =
    bus >>> LiveConnections.distributed[PublicSessionId]
  // docs:end auth-provide-distributed
end AuthApp
