// docs:start layouts-project-layout
package layouts

import scalive.*

final class ProjectLayout extends LiveLayout[String, Any]:
  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[String, Any]): HtmlElement[Msg] =
    sectionTag(
      p(link.pushNavigate(Routes.projects.location, "All projects")),
      h1(ctx.params.map(project => s"Project $project")),
      content
    )
// docs:end layouts-project-layout
