import scalive.*

final class E2ERootLayout(assets: StaticAssets) extends LiveRootLayout[Any, Any]:
  def key(ctx: LiveLayoutContext[Any, Any]): String = "e2e-root"

  def apply[Msg](content: HtmlElement[Msg]): HtmlElement[Msg] =
    render(
      content,
      LiveLayoutContext((), zio.http.Request.get(zio.http.URL.root), zio.http.URL.root, ())
    )

  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset  := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        assets.trackedScript(
          "app.js",
          defer := true,
          typ   := "text/javascript"
        ),
        assets.trackedStylesheet("app.css"),
        titleTag("Scalive E2E")
      ),
      bodyTag(
        hookOutsideLiveView(ctx),
        content,
        div(idAttr := "root-portal")
      )
    )

  private def hookOutsideLiveView(ctx: LiveLayoutContext[Any, Any]): Mod[Nothing] =
    if ctx.currentUrl.path.encode == "/issues/4147" then
      Mod.Content.Tag(div(phx.hook("HookOutside", "foobar")))
    else Mod.Content.Text("")
end E2ERootLayout
