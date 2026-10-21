package sessions

import zio.*

import scalive.*

object LogAnnotations:
  // docs:start sessions-log-annotations
  val currentUser =
    Aspects.currentUser.logAnnotated(user => ZIOAspect.annotated("user", user.id.toString))
  val workspaceAccess = Aspects.workspaceAccess.logAnnotated(access =>
    ZIOAspect.annotated("workspace", access.workspaceId)
  )
  // docs:end sessions-log-annotations

  final class AnnotatedWorkspaceLiveView(access: WorkspaceAccess) extends LiveView.Eventless[Unit]:
    // Logged with `user` and `workspace`, in the page render and once connected.
    def mount(ctx: MountContext): Task[Unit]            = ZIO.logInfo("Workspace opened")
    def view(model: Signal[Unit]): HtmlElement[Nothing] = h1(access.workspaceId)

  val application = Live.router(
    Live
      .session("annotated-account")
      .withMountAspect(currentUser)(
        Routes.workspace
          .withMountAspect(workspaceAccess)
          .context((_, access) => AnnotatedWorkspaceLiveView(access))
      )
  )
