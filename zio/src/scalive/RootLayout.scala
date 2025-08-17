package scalive

import scalive.HtmlElement

object RootLayout:
  def apply[RootModel](content: HtmlElement): HtmlElement =
    htmlRootTag(
      lang := "en",
      metaTag(charset := "utf-8"),
      bodyTag(
        // content
      )
    )
