package scalive.docs.pipeline

import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import scalive.docs.model.*
import zio.*
import zio.test.*

object ExternalLinkCheckerSpec extends ZIOSpecDefault:
  override def spec = suite("ExternalLinkCheckerSpec")(
    test("checks success, redirects, HEAD fallback, failures, and deduplicates requests") {
      ZIO.acquireReleaseWith(ZIO.attempt(HttpFixture.start()))(fixture => ZIO.attempt(fixture.stop()).orDie) {
        fixture => ZIO.succeed {
          val root = fixture.root
          val bundle = testBundle(
            repositoryUrl = root,
            pageLinks = Vector(
              s"$root/deduplicated#first",
              s"$root/redirect",
              s"$root/fallback",
              s"$root/z-missing",
              s"$root/a-missing",
              s"$root/deduplicated#second",
              "mailto:docs@example.com"
            )
          )

          val failures = ExternalLinkChecker.check(
            bundle,
            Duration.ofSeconds(2),
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
          )

          assertTrue(
            failures == Vector(
              ExternalLinkChecker.Failure(s"$root/a-missing", "HTTP 404"),
              ExternalLinkChecker.Failure(s"$root/z-missing", "HTTP 404")
            ),
            fixture.deduplicatedRequests.get() == 1,
            fixture.fallbackHeadRequests.get() == 1,
            fixture.fallbackGetRequests.get() == 1,
            ExternalLinkChecker.collect(bundle).forall(!_.startsWith("mailto:"))
          )
        }
      }
    },
    test("collects recursively authored and Scaladoc links plus API source and generated links") {
      val root = "https://example.test/repository"
      val apiLink = "https://api.example.test/reference"
      val authoredLink = "https://docs.example.test/nested#section"
      val documentation = ApiDocumentation(
        body = Vector(Block.Quote(Vector(paragraph(apiLink)))),
        tags = Vector(ApiDocumentationTag("return", None, Vector(Block.Callout(
          CalloutKind.Info,
          None,
          Vector(paragraph(apiLink + "/tag"))
        ))))
      )
      val bundle = testBundle(root, Vector(authoredLink), Some(documentation), includeSource = true)

      val links = ExternalLinkChecker.collect(bundle)

      assertTrue(
        links.contains(authoredLink.stripSuffix("#section")),
        links.contains(apiLink),
        links.contains(apiLink + "/tag"),
        links.contains(s"$root/blob/revision/src/Example.scala"),
        links.exists(_.startsWith(s"$root/edit/master/documentation/content/test.md")),
        links.exists(_.startsWith(s"$root/issues/new?")),
        links == links.distinct.sorted
      )
    }
  )

  private def testBundle(
    repositoryUrl: String,
    pageLinks: Vector[String],
    apiDocumentation: Option[ApiDocumentation] = None,
    includeSource: Boolean = false
  ): DocumentationBundle =
    val page = Page(
      route = "/test",
      metadata = PageMetadata("Test page", "Test description", 1, Section.Guides),
      source = PageSource.Authored(SourceLocation("documentation/content/test.md", 1)),
      outline = PageOutline(Vector.empty),
      content = Vector(Block.BulletList(Vector(ListItem(Vector(Block.Callout(
        CalloutKind.Info,
        None,
        pageLinks.map(paragraph)
      ))))))
    )
    val signatures =
      if includeSource || apiDocumentation.nonEmpty then Vector(ApiSignature(
        id = "signature",
        signature = "def example: Unit",
        tokens = Vector.empty,
        origin = ApiOrigin("Example", ApiExposure.Direct),
        source = ApiSource.Repository(SourceRegion("src/Example.scala", 1, 1)),
        documentation = apiDocumentation
      ))
      else Vector.empty
    val symbols =
      if signatures.nonEmpty then Vector(ApiSymbol(
        id = "object:Example",
        ownerId = None,
        name = "Example",
        qualifiedName = "Example",
        kind = ApiSymbolKind.Object,
        summary = "Example",
        signatures = signatures,
        route = "/api/example",
        fragment = None
      ))
      else Vector.empty

    DocumentationBundle(
      DocumentationBundle.CurrentFormatVersion,
      Navigation(Vector.empty),
      Vector(page),
      Vector.empty,
      ApiReference(ApiReferenceMetadata(repositoryUrl, "revision", "1.0", "Generator.mill"), symbols),
      Vector.empty
    )

  private def paragraph(url: String): Block.Paragraph =
    Block.Paragraph(Vector(Inline.Link(
      Vector(Inline.Strong(Vector(Inline.Text(url)))),
      LinkTarget.External(url),
      None
    )))

  private final class HttpFixture(
    server: HttpServer,
    val deduplicatedRequests: AtomicInteger,
    val fallbackHeadRequests: AtomicInteger,
    val fallbackGetRequests: AtomicInteger
  ):
    val root = s"http://127.0.0.1:${server.getAddress.getPort}"
    def stop(): Unit = server.stop(0)

  private object HttpFixture:
    def start(): HttpFixture =
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val deduplicated = new AtomicInteger()
      val fallbackHead = new AtomicInteger()
      val fallbackGet = new AtomicInteger()
      val _ = server.createContext("/ok", exchange => respond(exchange, 200))
      val _ = server.createContext("/deduplicated", exchange => {
        deduplicated.incrementAndGet()
        respond(exchange, 200)
      })
      val _ = server.createContext("/redirect", exchange => {
        exchange.getResponseHeaders.add("Location", "/ok")
        respond(exchange, 302)
      })
      val _ = server.createContext("/fallback", exchange => {
        if exchange.getRequestMethod == "HEAD" then
          fallbackHead.incrementAndGet()
          respond(exchange, 405)
        else
          fallbackGet.incrementAndGet()
          respond(exchange, 200)
      })
      val _ = server.createContext("/a-missing", exchange => respond(exchange, 404))
      val _ = server.createContext("/z-missing", exchange => respond(exchange, 404))
      val _ = server.createContext("/edit", exchange => respond(exchange, 200))
      val _ = server.createContext("/issues", exchange => respond(exchange, 200))
      server.start()
      HttpFixture(server, deduplicated, fallbackHead, fallbackGet)

    private def respond(exchange: HttpExchange, status: Int): Unit =
      exchange.sendResponseHeaders(status, -1)
      exchange.close()
end ExternalLinkCheckerSpec
