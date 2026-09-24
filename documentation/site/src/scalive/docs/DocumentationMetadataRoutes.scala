package scalive.docs

import java.nio.charset.StandardCharsets

import zio.Chunk
import zio.http.*
import zio.http.codec.PathCodec

import scalive.StaticAssets
import scalive.docs.model.{PageSource, Section}

private[docs] object DocumentationMetadataRoutes:
  def routes(
    application: DocumentationApplication,
    assets: StaticAssets,
    origin: PublicOrigin
  ): Routes[Any, Nothing] =
    val sitemap      = sitemapXml(application, origin)
    val robots       = robotsText(origin)
    val markdown     = DocumentationMarkdown(application, assets, origin)
    val llms         = llmsText(application, markdown)
    val markdownType = MediaType("text", "markdown")
    val companions   = application.pages.flatMap { entry =>
      val path         = PathCodec(DocumentationMarkdown.path(entry.page.route))
      lazy val content = markdown.render(entry.page)
      Vector(
        RoutePattern(Method.GET, path) -> handler((_: Request) =>
          response(content, markdownType, true)
        ),
        RoutePattern(Method.HEAD, path) -> handler(response("", markdownType, false))
      )
    }
    Routes(
      Method.GET / "sitemap.xml"  -> handler(response(sitemap, MediaType.application.xml, true)),
      Method.HEAD / "sitemap.xml" -> handler(response(sitemap, MediaType.application.xml, false)),
      Method.GET / "robots.txt"   -> handler(response(robots, MediaType.text.plain, true)),
      Method.HEAD / "robots.txt"  -> handler(response(robots, MediaType.text.plain, false)),
      Method.GET / "llms.txt"     -> handler(response(llms, MediaType.text.plain, true)),
      Method.HEAD / "llms.txt"    -> handler(response(llms, MediaType.text.plain, false))
    ) ++ Routes(Chunk.fromIterable(companions))

  private def llmsText(
    application: DocumentationApplication,
    markdown: DocumentationMarkdown
  ): String =
    val pages    = application.bundle.pages.filter(_.source.isInstanceOf[PageSource.Authored])
    val sections = Section.values.toVector.flatMap { section =>
      val entries = pages
        .filter(_.metadata.section == section)
        .sortBy(page => (page.metadata.order, page.route))
      val title = section match
        case Section.Home => "Overview"
        case Section.Api  => "API"
        case other        => other.toString
      Option.when(entries.nonEmpty)(
        s"## $title\n\n${entries.map(markdown.indexEntry).mkString("\n")}"
      )
    }
    (Vector(
      "# Scalive",
      "> Scalive is a Scala 3 framework for server-rendered, interactive web applications, inspired by Phoenix LiveView and powered by ZIO.",
      "Scalive is in alpha; APIs may change. These links provide Markdown versions of the documentation, including resolved source examples. The API overview links to the generated API reference. Interactive examples and labs link to their browser pages."
    ) ++ sections).mkString("\n\n") + "\n"

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
      headers = Headers(Header.ContentType(mediaType, charset = Some(StandardCharsets.UTF_8))),
      body = if includeBody then Body.fromString(content) else Body.empty
    )
end DocumentationMetadataRoutes
