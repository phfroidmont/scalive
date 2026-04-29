import scalive.*

object E2ERootLayout extends LiveRootLayout[Any, Any]:
  import java.nio.file.Paths

  private val runtime = zio.Runtime.default

  private lazy val resourceRoot: java.nio.file.Path =
    Option(getClass.getClassLoader.getResource("public"))
      .map(url => Paths.get(url.toURI))
      .getOrElse(throw new IllegalArgumentException("public resources directory not found"))

  private def hashOrDie(rel: String): String =
    zio.Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(StaticAssetHasher.hashedPath(rel, resourceRoot)).getOrThrowFiberFailure()
    }

  private val hashedJs       = s"/static/${hashOrDie("app.js")}"
  private val hashedCss      = s"/static/${hashOrDie("app.css")}"
  private val hashedDaisyCss = s"/static/${hashOrDie("daisy.css")}"

  val daisyCssHref: String = hashedDaisyCss

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
        scriptTag(
          defer           := true,
          phx.trackStatic := true,
          typ             := "text/javascript",
          src             := hashedJs
        ),
        linkTag(phx.trackStatic := true, rel := "stylesheet", href := hashedCss),
        titleTag("Scalive E2E")
      ),
      bodyTag(content)
    )
end E2ERootLayout
