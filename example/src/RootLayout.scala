import scalive.*

final class RootLayout(assets: StaticAssets) extends LiveRootLayout[Any, Any]:

  def key(ctx: LiveLayoutContext[Any, Any]): String = "example-root"

  def apply[Msg](content: HtmlElement[Msg]): HtmlElement[Msg] =
    render(
      content,
      LiveLayoutContext((), zio.http.Request.get(zio.http.URL.root), zio.http.URL.root, ())
    )

  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    htmlRootTag(
      lang              := "en",
      dataAttr("theme") := "business",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        assets.trackedScript(
          "app.js",
          defer := true,
          typ   := "text/javascript"
        ),
        assets.trackedStylesheet("app.css"),
        titleTag("Scalive Example")
      ),
      bodyTag(
        content
      )
    )
