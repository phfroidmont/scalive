package layouts

import zio.*

import scalive.*

final class HomeLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext): Task[Unit] = ZIO.unit

  def view(model: Signal[Unit]): HtmlElement[Nothing] =
    p("Welcome.")

final class ProjectsLiveView extends LiveView.Eventless[Vector[String]]:
  def mount(ctx: MountContext): Task[Vector[String]] = ZIO.succeed(Vector("scalive", "zio"))

  override def pageTitle(projects: Vector[String]): Option[String] = Some("All projects")

  def view(model: Signal[Vector[String]]): HtmlElement[Nothing] =
    ul(
      model.splitBy(identity)((project, _) =>
        li(link.pushNavigate(Routes.project.location(project), project))
      )
    )

// docs:start layouts-layout-values
final class ProjectLiveView extends LiveView.Routed.Eventless[String, String]:
  def mount(project: String, ctx: MountContext): Task[String] = ZIO.succeed(project)

  override def pageTitle(project: String): Option[String] = Some(s"Open issues · Project $project")

  override def layoutValues(project: String): LiveLayoutValues =
    LiveLayoutValues(AppLayout.Heading := s"Project $project")

  def view(model: Signal[String]): HtmlElement[Nothing] =
    p(model.map(project => s"Open issues for $project."))
// docs:end layouts-layout-values
