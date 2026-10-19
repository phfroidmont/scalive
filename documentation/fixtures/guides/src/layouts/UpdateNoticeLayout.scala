// docs:start layouts-update-notice
package layouts

import scalive.*

final class UpdateNoticeLayout extends LiveLayout[Any, Any]:
  def view[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    ctx.connection match
      case Connection.Connected(connected) if connected.staticChanged =>
        content.prepended(
          div(
            role := "status",
            "A new version of this application is available. ",
            a(href := ctx.currentUrl.map(_.encode), "Reload")
          )
        )
      case _ => content
// docs:end layouts-update-notice
