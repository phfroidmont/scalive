package sessions

import zio.*
import zio.http.codec.PathCodec
import zio.http.{Request, Response}
import zio.json.*

import scalive.*

// docs:start sessions-domain
final case class SessionId(value: String) derives JsonCodec
final case class AuthClaims(sessionId: SessionId) derives JsonCodec
final case class CurrentUser(id: Long, name: String)
final case class AuthenticatedSession(sessionId: SessionId, currentUser: CurrentUser)
final case class WorkspaceAccess(workspaceId: String, canEdit: Boolean)

trait Authentication:
  def authenticate(request: Request): IO[Response, AuthenticatedSession]
  def resume(sessionId: SessionId): IO[LiveMountFailure, CurrentUser]

trait Workspaces:
  def authorize(user: CurrentUser, workspaceId: String): IO[LiveRouteMountFailure, WorkspaceAccess]
// docs:end sessions-domain

object Aspects:
  // docs:start sessions-current-user
  val currentUser =
    LiveSessionMountAspect.fromRequest[Authentication, AuthClaims, CurrentUser](
      request =>
        ZIO.serviceWithZIO[Authentication](_.authenticate(request.request)).map { session =>
          AuthClaims(session.sessionId) -> session.currentUser
        },
      (claims, _) => ZIO.serviceWithZIO[Authentication](_.resume(claims.sessionId))
    )
  // docs:end sessions-current-user

  // docs:start sessions-workspace-access
  val workspaceAccess =
    LiveRouteMountAspect.make[Workspaces, String, CurrentUser, WorkspaceAccess] {
      (destination, user) =>
        ZIO.serviceWithZIO[Workspaces](_.authorize(user, destination.params))
    }
  // docs:end sessions-workspace-access

object Routes:
  val account   = live / "account"
  val settings  = live / "account" / "settings"
  val workspace = live / "workspaces" / PathCodec.string("workspaceId")

// docs:start sessions-account-layout
final class AccountLayout extends LiveLayout[Any, CurrentUser]:
  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, CurrentUser])
    : HtmlElement[Msg] =
    div(
      headerTag(p(s"Signed in as ${ctx.context.name}")),
      mainTag(content)
    )
// docs:end sessions-account-layout

final class AccountLiveView(user: CurrentUser) extends LiveView[AccountLiveView.Msg, Unit]:
  import AccountLiveView.*

  def mount(ctx: MountContext): Task[Unit] = ZIO.unit

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.OpenSettings => ctx.nav.pushNavigate(Routes.settings.location)

  def view(model: Signal[Unit]): HtmlElement[Msg] =
    div(
      h1(s"${user.name}'s account"),
      button(on.click(Msg.OpenSettings), "Open settings")
    )

object AccountLiveView:
  enum Msg:
    case OpenSettings

final class SettingsLiveView(user: CurrentUser) extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext): Task[Unit]            = ZIO.unit
  def view(model: Signal[Unit]): HtmlElement[Nothing] = h1(s"Settings for ${user.name}")

final class WorkspaceLiveView(user: CurrentUser, access: WorkspaceAccess)
    extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext): Task[Unit]            = ZIO.unit
  def view(model: Signal[Unit]): HtmlElement[Nothing] =
    h1(s"${user.name}: ${access.workspaceId}")

object Application:
  // docs:start sessions-account
  val account = Live
    .session("account")
    .withMountAspect(Aspects.currentUser)
    .withLayout(AccountLayout())(
      Routes.account.context((user: CurrentUser) => AccountLiveView(user)),
      Routes.settings.context((user: CurrentUser) => SettingsLiveView(user)),
      // docs:start sessions-workspace-route
      Routes.workspace
        .withMountAspect(Aspects.workspaceAccess)
        .context((context: (CurrentUser, WorkspaceAccess)) =>
          WorkspaceLiveView(context._1, context._2)
        )
      // docs:end sessions-workspace-route
    )
  // docs:end sessions-account

  val application = Live.router(account)
