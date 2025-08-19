package scalive

import scalive.HtmlElement

object RootLayout:
  def apply(content: HtmlElement): HtmlElement =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset := "utf-8")
      ),
      bodyTag(
        content
      )
    )
