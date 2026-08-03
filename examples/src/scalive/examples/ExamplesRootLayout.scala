package scalive.examples

import scalive.*

final class ExamplesRootLayout(assets: StaticAssets) extends LiveRootLayout[Any, Any]:
  def key(ctx: LiveLayoutContext[Any, Any]): String = "examples-root"

  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveLayoutContext[Any, Any]
  ): HtmlElement[Msg] =
    htmlRootTag(
      lang              := "en",
      dataAttr("theme") := "business",
      cls               := "min-h-full bg-base-200",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        assets.trackedScript(
          "app.js",
          defer := true,
          typ   := "text/javascript"
        ),
        assets.trackedStylesheet("app.css"),
        liveTitle(pageTitle, default = "Scalive Examples", suffix = " | Scalive Examples")
      ),
      bodyTag(
        cls := "min-h-screen bg-base-200 text-base-content antialiased",
        content
      )
    )
