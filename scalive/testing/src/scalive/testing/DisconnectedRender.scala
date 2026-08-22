package scalive.testing

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import zio.*
import zio.http.*

import scalive.{FormData, FormPath, FormSubmitter}

/** Runs serverless tests against the first, disconnected HTTP render.
  *
  * A disconnected render executes finalized routes directly. For a Scalive Live route this
  * exercises the production HTTP lifecycle, including routing, mount aspects, disconnected `mount`,
  * initial `handleParams`, layouts, components, nested LiveViews, session metadata, and CSRF
  * response handling. It does not start a server, join a LiveSocket, perform the separate connected
  * mount, dispatch events, or run connected asynchronous work. Ordinary GET and POST forms can be
  * submitted explicitly through [[RenderedForm.submit]].
  */
object DisconnectedRender:
  /** Executes `request` against `routes` and captures the returned page.
    *
    * The body is fully consumed before the route scope closes, then replaced on
    * [[RenderedPage.response]] with a replayable string body. HTTP responses of every status are
    * successful results, and redirects are never followed.
    */
  def run[R](
    routes: Routes[R, Nothing],
    request: Request
  ): ZIO[R, Throwable, RenderedPage] =
    execute(routes, request, TestCookieJar.from(request))

  private[testing] def execute[R](
    routes: Routes[R, Nothing],
    request: Request,
    cookies: TestCookieJar
  ): ZIO[R, Throwable, RenderedPage] =
    val prepared = cookies.addTo(request)
    ZIO.scoped {
      routes.runZIO(prepared).flatMap { response =>
        response.body.asString.flatMap { html =>
          ZIO.attempt {
            val document           = Jsoup.parse(html, prepared.url.encode)
            val replayableResponse = response.copy(body = Body.fromString(html))
            val nextFlow           = TestHttpFlow(prepared.url, cookies.updated(response))
            RenderedPage(replayableResponse, html, document, nextFlow)
          }
        }
      }
    }
end DisconnectedRender

/** Exact filters used to select one rendered form.
  *
  * Each `None` is a wildcard. When both fields are defined, both must match. An empty query matches
  * every form and succeeds only when the page contains exactly one form.
  */
final case class FormQuery(
  action: Option[String] = None,
  method: Option[Method] = None)

/** Describes why [[RenderedPage.form]] could not select exactly one form. */
enum FormQueryError:
  case NotFound(query: FormQuery)
  case MultipleMatches(query: FormQuery, count: Int)

/** A response and semantic view of its Jsoup-parsed HTML. */
final class RenderedPage private[testing] (
  responseValue: Response,
  htmlValue: String,
  document: Document,
  flow: TestHttpFlow):

  /** The route response with its body replaced by a replayable body containing [[html]]. */
  val response: Response = responseValue

  /** The complete decoded response body before Jsoup parsing. */
  val html: String = htmlValue

  /** Returns the parsed document's decoded, combined text with whitespace normalized by Jsoup. */
  def text: String = document.text()

  /** Returns every parsed `form` element in DOM order. */
  def forms: Vector[RenderedForm] =
    val baseHref = Option(document.selectFirst("base[href]"))
      .map(_.attr("href")).filter(_.nonEmpty)
    document.select("form").asScala.toVector.map(RenderedForm(_, flow, baseHref))

  /** Selects exactly one form matching `query`. */
  def form(query: FormQuery = FormQuery()): Either[FormQueryError, RenderedForm] =
    val matches = forms.filter(renderedForm =>
      query.action.forall(renderedForm.action.contains) &&
        query.method.forall(_ == renderedForm.method)
    )

    matches match
      case Vector(form) => Right(form)
      case Vector()     => Left(FormQueryError.NotFound(query))
      case forms        => Left(FormQueryError.MultipleMatches(query, forms.size))

  /** Follows this response's local `303 See Other` location with a GET request.
    *
    * Cookies accumulated by the preceding request flow are included. Other redirect statuses and
    * external locations fail explicitly because this serverless harness cannot reproduce their
    * browser or network semantics.
    */
  def followSeeOther[R](routes: Routes[R, Nothing]): ZIO[R, Throwable, RenderedPage] =
    if response.status != Status.SeeOther then
      ZIO.fail(
        IllegalArgumentException(
          s"Expected 303 See Other, got ${response.status.code} ${response.status.text}."
        )
      )
    else
      response.header(Header.Location) match
        case None => ZIO.fail(IllegalArgumentException("303 See Other response has no Location."))
        case Some(location) =>
          ZIO
            .fromEither(flow.resolveLocal(location.url.encode, "redirect location"))
            .flatMap(url => DisconnectedRender.execute(routes, Request.get(url), flow.cookies))
end RenderedPage

object RenderedPage:
  private[testing] def apply(
    response: Response,
    html: String,
    document: Document,
    flow: TestHttpFlow
  ): RenderedPage =
    new RenderedPage(response, html, document, flow)

/** A semantic view of one form in a parsed disconnected-render snapshot.
  *
  * Field accessors inspect rendered markup only. Submission requires explicit [[FormData]] and does
  * not dispatch bindings or construct a browser's successful-controls data set.
  */
final class RenderedForm private[testing] (
  element: Element,
  flow: TestHttpFlow,
  baseHref: Option[String]):
  /** Returns the parsed `id` attribute, preserving absent versus present-empty. */
  def id: Option[String] = attribute("id")

  /** Returns the parsed, unresolved `action` attribute. */
  def action: Option[String] = attribute("action")

  /** Returns `POST` for a case-insensitive `post` attribute and `GET` otherwise. */
  def method: Method =
    if attribute("method").exists(_.equalsIgnoreCase("post")) then Method.POST else Method.GET

  /** Returns named descendant controls in DOM order. */
  def fields: Vector[RenderedField] =
    element
      .select("button[name], input[name], select[name], textarea[name]").asScala.toVector
      .map(RenderedField(_))

  /** Returns every selected field name, retaining duplicates and DOM order. */
  def names: Vector[String] = fields.map(_.name)

  /** Returns Jsoup values for fields whose parsed name exactly equals `name`. */
  def values(name: String): Vector[String] =
    fields.collect { case field if field.name == name => field.value }

  /** Returns [[values]] for the browser-style name rendered by `path`. */
  def values(path: FormPath): Vector[String] =
    values(path.name)

  /** Reports whether the form has a `phx-change` attribute. */
  def hasChangeBinding: Boolean = element.hasAttr("phx-change")

  /** Reports whether the form has a `phx-submit` attribute. */
  def hasSubmitBinding: Boolean = element.hasAttr("phx-submit")

  /** Reports whether the form has a `phx-trigger-action` attribute. */
  def triggersAction: Boolean = element.hasAttr("phx-trigger-action")

  /** Submits explicit ordered fields to this form's local ordinary HTTP action.
    *
    * The caller supplies the complete successful-control payload, including any rendered CSRF
    * field. An optional submitter is appended after those fields. GET submission replaces the
    * action query. POST submission retains the query and supports only
    * `application/x-www-form-urlencoded`. Cookies accumulated by the source page are included.
    */
  def submit[R](
    routes: Routes[R, Nothing],
    data: FormData,
    submitter: Option[FormSubmitter] = None
  ): ZIO[R, Throwable, RenderedPage] =
    val fields = data.raw ++ submitter.map(value => value.name -> value.value)

    ZIO.fromEither(flow.resolveFormAction(action, baseHref)).flatMap { target =>
      val request = method match
        case Method.POST =>
          val encoding = attribute("enctype").filter(_.nonEmpty)
            .getOrElse("application/x-www-form-urlencoded")
          encoding.toLowerCase(java.util.Locale.ROOT) match
            case "multipart/form-data" | "text/plain" =>
              ZIO.fail(
                IllegalArgumentException(
                  s"Unsupported POST form encoding '$encoding'; only application/x-www-form-urlencoded is supported."
                )
              )
            case _ =>
              ZIO.succeed(Request.post(target, Body.fromURLEncodedForm(Form.fromStrings(fields*))))
        case _ =>
          val encoded = Form.fromStrings(fields*).urlEncoded(StandardCharsets.UTF_8)
          ZIO
            .fromEither(flow.replaceQuery(target, encoded))
            .map(Request.get)

      request.flatMap(DisconnectedRender.execute(routes, _, flow.cookies))
    }

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))
end RenderedForm

object RenderedForm:
  private[testing] def apply(
    element: Element,
    flow: TestHttpFlow,
    baseHref: Option[String]
  ): RenderedForm =
    new RenderedForm(element, flow, baseHref)

/** A named `button`, `input`, `select`, or `textarea` in a parsed form snapshot. */
final class RenderedField private[testing] (element: Element):
  /** Returns the Jsoup-normalized HTML tag name. */
  def tagName: String = element.tagName()

  /** Returns the parsed `id` attribute, preserving absent versus present-empty. */
  def id: Option[String] = attribute("id")

  /** Returns the parsed `name` attribute. */
  def name: String = element.attr("name")

  /** Returns the value reported by Jsoup's `Element.val()`. */
  def value: String = element.`val`()

  /** Returns a non-empty parsed `type` attribute for an input. */
  def inputType: Option[String] =
    Option.when(tagName == "input")(element.attr("type")).filter(_.nonEmpty)

  /** Reports whether the control has a `required` attribute. */
  def required: Boolean = element.hasAttr("required")

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))

object RenderedField:
  private[testing] def apply(element: Element): RenderedField =
    new RenderedField(element)

final private[testing] case class TestHttpFlow(url: URL, cookies: TestCookieJar):
  def resolveFormAction(
    action: Option[String],
    baseHref: Option[String]
  ): Either[Throwable, URL] =
    action.filter(_.nonEmpty) match
      case None        => Right(url)
      case Some(value) =>
        val base = baseHref match
          case None          => Right(url.toJavaURI)
          case Some(encoded) => resolveReference(url.toJavaURI, encoded, "form base")
        base.flatMap(resolveAgainst(_, value, "form action"))

  def resolveLocal(value: String, description: String): Either[Throwable, URL] =
    resolveAgainst(url.toJavaURI, value, description)

  private def resolveAgainst(
    base: URI,
    value: String,
    description: String
  ): Either[Throwable, URL] =
    resolveReference(base, value, description).flatMap { target =>
      if sameOrigin(url.toJavaURI, target) then
        URL
          .fromURI(target)
          .toRight(IllegalArgumentException(s"Invalid $description '$value'."))
          .map(_.copy(fragment = None))
      else Left(IllegalArgumentException(s"Cannot execute external $description '$value'."))
    }

  private def resolveReference(
    base: URI,
    value: String,
    description: String
  ): Either[Throwable, URI] =
    Try(URI.create(value)).toEither
      .left.map(error => IllegalArgumentException(s"Invalid $description: ${error.getMessage}"))
      .map(base.resolve)

  private def sameOrigin(source: URI, target: URI): Boolean =
    (Option(source.getRawAuthority), Option(target.getRawAuthority)) match
      case (None, None) => true
      case (Some(_), Some(_)) =>
        Option(source.getScheme).exists(scheme =>
          Option(target.getScheme).exists(_.equalsIgnoreCase(scheme))
        ) &&
          Option(source.getHost).exists(host =>
            Option(target.getHost).exists(_.equalsIgnoreCase(host))
          ) && effectivePort(source) == effectivePort(target)
      case _ => false

  private def effectivePort(value: URI): Int =
    if value.getPort >= 0 then value.getPort
    else
      Option(value.getScheme).map(_.toLowerCase(java.util.Locale.ROOT)) match
        case Some("http")  => 80
        case Some("https") => 443
        case _             => -1

  def replaceQuery(target: URL, encoded: String): Either[Throwable, URL] =
    val base  = target.copy(queryParams = QueryParams.empty, fragment = None).encode
    val value = if encoded.isEmpty then base else s"$base?$encoded"
    URL.decode(value).left.map(error => IllegalArgumentException(error.getMessage))

final private[testing] case class TestCookieJar(values: Map[String, Cookie.Request]):
  def addTo(request: Request): Request =
    values.values.toVector.sortBy(_.name).foldLeft(request.removeHeader(Header.Cookie)) {
      (current, cookie) => current.addCookie(cookie)
    }

  def updated(response: Response): TestCookieJar =
    val next =
      response.headers(Header.SetCookie).map(_.value).foldLeft(values) { (current, cookie) =>
        val expired = cookie.maxAge.exists(duration => duration.isZero || duration.isNegative)
        if expired then current - cookie.name
        else current.updated(cookie.name, Cookie.Request(cookie.name, cookie.content))
      }
    TestCookieJar(next)

private[testing] object TestCookieJar:
  def from(request: Request): TestCookieJar =
    TestCookieJar(
      request.cookies.iterator
        .map(cookie => cookie.name -> Cookie.Request(cookie.name, cookie.content)).toMap
    )
