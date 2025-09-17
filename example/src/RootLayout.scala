import scalive.*

object RootLayout:
  def apply(content: HtmlElement): HtmlElement =
    htmlRootTag(
      lang              := "en",
      dataAttr("theme") := "business",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        scriptTag(
          defer           := true,
          phx.trackStatic := true,
          typ             := "text/javascript",
          src             := "/static/app.js"
        ),
        linkTag(phx.trackStatic := true, rel := "stylesheet", href := "/static/app.css"),
        titleTag("Scalive Example")
      ),
      bodyTag(
        content
      )
    )
