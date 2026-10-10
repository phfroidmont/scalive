// docs:start layouts-app-layout
package layouts

import scalive.*

final class AppLayout extends LiveLayout[Any, Any]:
  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    val path = ctx.currentUrl.map(_.path.encode)
    div(
      headerTag(
        navTag(
          aria.label := "Main",
          navLink(Routes.home.location, "Home", path.map(_ == "/")),
          navLink(Routes.projects.location, "Projects", path.map(_.startsWith("/projects")))
        )
      ),
      mainTag(content)
    )

  private def navLink[Msg](
    location: LiveLocation,
    label: String,
    current: Signal[Boolean]
  ): HtmlElement[Msg] =
    link.pushNavigate(
      location,
      aria.current.optional(current.map(Option.when(_)("page"))),
      label
    )
// docs:end layouts-app-layout
