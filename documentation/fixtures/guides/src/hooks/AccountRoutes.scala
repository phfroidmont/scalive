package hooks

import zio.*

import scalive.*

trait AccountAccess:
  def isActive: UIO[Boolean]

trait Accounts:
  def currentAccess: IO[LiveRouteMountFailure, AccountAccess]

final class AccountLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext): Task[Unit] =
    ZIO.unit

  def view(model: Signal[Unit]): HtmlElement[Nothing] =
    p("Account")

object AccountRoutes:
  val loadAccountAccess =
    LiveRouteMountAspect.fromRequest[Accounts, Unit, AccountAccess](_ =>
      ZIO.serviceWithZIO[Accounts](_.currentAccess)
    )

  // docs:start hooks-route-guard
  val accountRoute =
    (live / "account")
      .withMountAspect(loadAccountAccess)
      .guardConnectedTurns((access: AccountAccess) =>
        access.isActive.flatMap(active =>
          if active then ZIO.unit
          else ZIO.fail(LiveConnectedTurnFailure.reload("account access changed"))
        )
      ) -> AccountLiveView()
  // docs:end hooks-route-guard
