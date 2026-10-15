package layouts

import scalive.*

// docs:start layouts-root-layout
final class RootLayout(assets: StaticAssets) extends LiveRootLayout[Any, Any]:
  def view[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveRootLayoutContext[Any, Any]
  ): HtmlElement[Msg] =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        liveTitle(pageTitle, default = "Projects", suffix = " · Scalive"),
        assets.trackedStylesheet("app.css")
      ),
      bodyTag(content)
    )
// docs:end layouts-root-layout
