import scalive.*

object RootLayout extends LiveRootLayout[Any, Any]:
  import java.nio.file.Paths

  private val runtime = zio.Runtime.default

  // TODO externalize config in common with ServeHashedResourcesMiddleware
  private lazy val resourceRoot: java.nio.file.Path =
    Option(getClass.getClassLoader.getResource("public"))
      .map(url => Paths.get(url.toURI))
      .getOrElse(throw new IllegalArgumentException("public resources directory not found"))

  private def hashOrDie(rel: String): String =
    zio.Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(StaticAssetHasher.hashedPath(rel, resourceRoot)).getOrThrowFiberFailure()
    }

  private val hashedJs  = s"/static/${hashOrDie("app.js")}"
  private val hashedCss = s"/static/${hashOrDie("app.css")}"

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
        scriptTag(
          defer           := true,
          phx.trackStatic := true,
          typ             := "text/javascript",
          src             := hashedJs
        ),
        linkTag(phx.trackStatic := true, rel := "stylesheet", href := hashedCss),
        titleTag("Scalive Example")
      ),
      bodyTag(
        content
      )
    )
end RootLayout
