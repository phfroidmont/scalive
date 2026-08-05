package scalive.docs

import zio.http.*

private[docs] object DocumentationMetadataRoutes:
  def routes(
    application: DocumentationApplication,
    origin: PublicOrigin
  ): Routes[Any, Nothing] =
    val sitemap = sitemapXml(application, origin)
    val robots  = robotsText(origin)
    Routes(
      Method.GET / "sitemap.xml"  -> handler(response(sitemap, MediaType.application.xml, true)),
      Method.HEAD / "sitemap.xml" -> handler(response(sitemap, MediaType.application.xml, false)),
      Method.GET / "robots.txt"   -> handler(response(robots, MediaType.text.plain, true)),
      Method.HEAD / "robots.txt"  -> handler(response(robots, MediaType.text.plain, false))
    )

  private[docs] def sitemapXml(
    application: DocumentationApplication,
    origin: PublicOrigin
  ): String =
    val urls = application.pages.map(_.page.route).distinct.sorted.map { route =>
      s"  <url><loc>${origin.absolute(route)}</loc></url>"
    }
    (Vector(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
      "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">"
    ) ++ urls :+ "</urlset>").mkString("\n") + "\n"

  private[docs] def robotsText(origin: PublicOrigin): String =
    s"User-agent: *\nAllow: /\nSitemap: ${origin.absolute("/sitemap.xml")}\n"

  private def response(content: String, mediaType: MediaType, includeBody: Boolean): Response =
    Response(
      status = Status.Ok,
      headers = Headers(Header.ContentType(mediaType)),
      body = if includeBody then Body.fromString(content) else Body.empty
    )
end DocumentationMetadataRoutes
