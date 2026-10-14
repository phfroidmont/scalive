package auth

import zio.*
import zio.json.*

import scalive.*

final case class PublicSessionId(value: String) derives JsonCodec
final case class AuthClaims(publicSessionId: PublicSessionId) derives JsonCodec
final case class CurrentUser(email: String, name: String)
final case class CurrentSession(user: CurrentUser, publicSessionId: PublicSessionId)

final case class ConnectedAuth(
  currentUser: CurrentUser,
  revalidate: IO[LiveConnectedTurnFailure, Unit])

trait AuthService:
  def authenticate(cookieToken: String): UIO[Option[CurrentSession]]
  def resume(publicSessionId: PublicSessionId): UIO[Option[CurrentSession]]

object AuthService:
  val live: ULayer[AuthService] = ZLayer.succeed(
    new AuthService:
      def authenticate(cookieToken: String): UIO[Option[CurrentSession]]        = ZIO.none
      def resume(publicSessionId: PublicSessionId): UIO[Option[CurrentSession]] = ZIO.none
  )

trait Accounts:
  def plan(email: String): UIO[String]

object Accounts:
  val live: ULayer[Accounts] = ZLayer.succeed(
    new Accounts:
      def plan(email: String): UIO[String] = ZIO.succeed("Free")
  )

object Pages:
  val login   = live / "login"
  val profile = live / "profile"
  val status  = live / "status"

object AuthMountAspect:
  val SessionCookieName = "session"

  val authenticated: LiveSessionMountAspect[AuthService, Any, AuthClaims, ConnectedAuth] =
    LiveSessionMountAspect.fromRequest(
      request =>
        request.request.cookie(SessionCookieName) match
          case None         => ZIO.fail(Pages.login.location.seeOther)
          case Some(cookie) =>
            ZIO.serviceWithZIO[AuthService](auth =>
              auth
                .authenticate(cookie.content)
                .someOrFail(Pages.login.location.seeOther)
                .map(current => AuthClaims(current.publicSessionId) -> connectedAuth(auth, current))
            ),
      (claims, _) =>
        ZIO.serviceWithZIO[AuthService](auth =>
          auth
            .resume(claims.publicSessionId)
            .someOrFail(LiveSessionMountFailure.redirect(Pages.login.location))
            .map(connectedAuth(auth, _))
        )
    )

  private def connectedAuth(auth: AuthService, current: CurrentSession): ConnectedAuth =
    ConnectedAuth(
      current.user,
      auth
        .resume(current.publicSessionId)
        .someOrFail(LiveConnectedTurnFailure.redirect(Pages.login.location))
        .unit
    )
end AuthMountAspect

final class ProfileLiveView(currentUser: CurrentUser, accounts: Accounts)
    extends LiveView.Eventless[String]:
  def mount(ctx: MountContext): Task[String] = accounts.plan(currentUser.email)

  def view(model: Signal[String]): HtmlElement[Nothing] =
    mainTag(
      h1(s"Welcome, ${currentUser.name}"),
      dl(dt("Plan"), dd(model))
    )

final class StatusLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext): Task[Unit]            = ZIO.unit
  def view(model: Signal[Unit]): HtmlElement[Nothing] = h1("All systems operational")
