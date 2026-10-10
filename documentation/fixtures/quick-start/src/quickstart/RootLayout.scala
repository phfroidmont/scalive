// docs:start quick-start-root-layout
package quickstart

import scalive.*

final class RootLayout(clientAssets: LiveViewClientAssets, assets: StaticAssets)
    extends LiveRootLayout[Any, Any]:
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
        liveTitle(pageTitle, default = "Scalive quick start"),
        clientAssets.phoenixScript,
        clientAssets.liveViewScript,
        assets.trackedScript("app.js", defer := true, typ := "text/javascript")
      ),
      bodyTag(content)
    )
// docs:end quick-start-root-layout
