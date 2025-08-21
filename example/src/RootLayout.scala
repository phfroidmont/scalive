import scalive.*

object RootLayout:
  def apply(content: HtmlElement): HtmlElement =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset := "utf-8"),
        scriptTag(defer := true, typ := "text/javascript", src := "/static/app.js")
      ),
      bodyTag(
        content
      )
    )
