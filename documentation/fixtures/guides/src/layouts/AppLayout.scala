// docs:start layouts-app-layout
package layouts

import scalive.*

object AppLayout:
  /** A shorter heading than the page title, for pages that provide one. */
  val Heading = LiveLayoutKey[String]("heading")

final class AppLayout extends LiveLayout[Any, Any]:
  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    val path    = ctx.currentUrl.map(_.path.encode)
    val heading = ctx.layoutValue(AppLayout.Heading).combineWithFn(ctx.pageTitle)(_.orElse(_))
    div(
      headerTag(
        p(heading.map(_.getOrElse("Projects"))),
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
