package sessions

import zio.*

import scalive.*

object NamedContexts:
  // docs:start sessions-named-contexts
  type Account = (user: CurrentUser)

  val currentUser = Aspects.currentUser.map(user => (user = user))

  val workspaceAccess =
    LiveRouteMountAspect.make[Workspaces, String, Account, (access: WorkspaceAccess)] {
      (destination, account) =>
        ZIO
          .serviceWithZIO[Workspaces](_.authorize(account.user, destination.params))
          .map(access => (access = access))
    }

  val account = Live
    .session("account")
    .withMountAspect(currentUser)(
      Routes.account.context((account: Account) => AccountLiveView(account.user)),
      Routes.workspace
        .withMountAspect(workspaceAccess)
        .context(context => WorkspaceLiveView(context.user, context.access))
    )
  // docs:end sessions-named-contexts

  val application = Live.router(account)
