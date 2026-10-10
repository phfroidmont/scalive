package guards

import scalive.*

// docs:start guards-root-layout
final class RootLayout(
  clientAssets: LiveViewClientAssets,
  appAssets: StaticAssets,
  guardAssets: NavigationGuardAssets)
    extends LiveRootLayout[Any, Any]:
  def view[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveRootLayoutContext[Any, Any]
  ): HtmlElement[Msg] =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset := "utf-8"),
        liveTitle(pageTitle, default = "Application"),
        guardAssets.script,
        clientAssets.phoenixScript,
        clientAssets.liveViewScript,
        appAssets.trackedScript("app.js", defer := true, typ := "text/javascript")
      ),
      bodyTag(content)
    )
// docs:end guards-root-layout
