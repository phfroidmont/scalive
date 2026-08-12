package scalive.docs.pipeline

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import scala.util.control.NonFatal

import zio.json.*

import scalive.docs.model.*

object ExternalLinkChecker:
  final case class Failure(url: String, reason: String):
    def message: String = s"$url: $reason"

  private val DefaultTimeout = Duration.ofSeconds(10)

  def collect(bundle: DocumentationBundle): Vector[String] =
    val metadata      = bundle.apiReference.metadata
    val authoredLinks = bundle.pages.flatMap(page => linksInBlocks(page.content))
    val scaladocLinks = bundle.apiReference.symbols.flatMap(_.signatures).flatMap { signature =>
      signature.documentation.toVector.flatMap { documentation =>
        linksInBlocks(documentation.body) ++
          documentation.tags.flatMap(tag => linksInBlocks(tag.content))
      }
    }
    val apiSourceLinks = bundle.apiReference.symbols
      .flatMap(_.signatures)
      .map(signature => metadata.sourceLink(signature.source).url)
    val generatedPageLinks = bundle.pages.flatMap { page =>
      val issue = issueLink(metadata, page)
      page.source match
        case PageSource.Authored(location) =>
          Vector(
            s"${metadata.repositoryUrl.stripSuffix("/")}/edit/master/${location.path}#L${location.line}",
            issue
          )
        case PageSource.GeneratedApi(_) => Vector(issue)
    }

    (authoredLinks ++ scaladocLinks ++ apiSourceLinks ++ generatedPageLinks)
      .filter(isHttp)
      .map(withoutFragment)
      .distinct
      .sorted

  def check(
    bundle: DocumentationBundle,
    timeout: Duration = DefaultTimeout,
    client: HttpClient = defaultClient(DefaultTimeout)
  ): Vector[Failure] =
    collect(bundle).flatMap(url => checkUrl(client, url, timeout)).sortBy(_.url)

  def main(arguments: Array[String]): Unit = arguments.toList match
    case bundlePath :: Nil =>
      val bundle = Files
        .readString(Path.of(bundlePath), StandardCharsets.UTF_8)
        .fromJson[DocumentationBundle]
        .fold(
          message => throw new IllegalArgumentException(s"Invalid documentation bundle: $message"),
          identity
        )
      val failures = check(bundle)
      if failures.nonEmpty then
        throw new IllegalArgumentException(
          failures.map(_.message).mkString("External link check failed:\n", "\n", "")
        )
      println(s"Checked ${collect(bundle).size} external documentation links.")
    case _ =>
      throw new IllegalArgumentException("Expected the generated documentation bundle path.")

  private def defaultClient(connectTimeout: Duration): HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(connectTimeout)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  private def checkUrl(
    client: HttpClient,
    url: String,
    timeout: Duration
  ): Option[Failure] =
    try
      val uri        = URI.create(url)
      val headStatus = send(client, uri, "HEAD", timeout)
      val status     =
        if headStatus == 405 || headStatus == 501 then send(client, uri, "GET", timeout)
        else headStatus
      if status >= 200 && status < 400 then None
      else Some(Failure(url, s"HTTP $status"))
    catch
      case error: InterruptedException =>
        Thread.currentThread().interrupt()
        Some(Failure(url, "request interrupted"))
      case NonFatal(error) =>
        val detail =
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        Some(Failure(url, detail))

  private def send(
    client: HttpClient,
    uri: URI,
    method: String,
    timeout: Duration
  ): Int =
    val request = HttpRequest
      .newBuilder(uri)
      .timeout(timeout)
      .header("User-Agent", "Scalive documentation link checker")
      .method(method, HttpRequest.BodyPublishers.noBody())
      .build()
    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()

  private def linksInBlocks(blocks: Vector[Block]): Vector[String] =
    blocks.flatMap {
      case Block.Paragraph(content)     => linksInInlines(content)
      case Block.Heading(_, _, content) => linksInInlines(content)
      case Block.BulletList(items)      => items.flatMap(item => linksInBlocks(item.content))
      case Block.OrderedList(_, items)  => items.flatMap(item => linksInBlocks(item.content))
      case Block.Quote(content)         => linksInBlocks(content)
      case Block.Table(header, rows)    =>
        (header ++ rows.flatMap(_.cells)).flatMap(cell => linksInInlines(cell.content))
      case Block.Image(source, _, _)    => Vector(source)
      case Block.Callout(_, _, content) => linksInBlocks(content)
      case _                            => Vector.empty
    }

  private def linksInInlines(inlines: Vector[Inline]): Vector[String] =
    inlines.flatMap {
      case Inline.Emphasis(content)        => linksInInlines(content)
      case Inline.Strong(content)          => linksInInlines(content)
      case Inline.Strike(content)          => linksInInlines(content)
      case _: Inline.ApiSymbolRef          => Vector.empty
      case Inline.Link(content, target, _) =>
        val nested = linksInInlines(content)
        target match
          case LinkTarget.External(url) => url +: nested
          case _: LinkTarget.Internal   => nested
      case _ => Vector.empty
    }

  private def isHttp(url: String): Boolean =
    url.regionMatches(true, 0, "http://", 0, 7) ||
      url.regionMatches(true, 0, "https://", 0, 8)

  private def withoutFragment(url: String): String =
    val fragment = url.indexOf('#')
    if fragment < 0 then url else url.substring(0, fragment)

  private def issueLink(metadata: ApiReferenceMetadata, page: Page): String =
    val source = page.source match
      case PageSource.Authored(location) => location.path
      case PageSource.GeneratedApi(id)   => s"generated API symbol $id"
    val title = encode(s"Documentation: ${page.metadata.title}")
    val body  = encode(
      s"Page: ${page.route}\nSource: $source\nDocumented revision: ${metadata.revision}\n\nDescribe the issue:"
    )
    s"${metadata.repositoryUrl.stripSuffix("/")}/issues/new?title=$title&body=$body"

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
end ExternalLinkChecker
