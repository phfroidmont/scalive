import scalive.*

object RootLayout:
  def apply(content: HtmlElement): HtmlElement =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        scriptTag(defer  := true, typ               := "text/javascript", src := "/static/app.js"),
        titleTag("Scalive Example")
      ),
      bodyTag(
        content
      )
    )
