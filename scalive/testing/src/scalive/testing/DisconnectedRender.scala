package scalive.testing

import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import zio.*
import zio.http.*

import scalive.FormPath

/** Runs serverless tests against the first, disconnected HTTP render.
  *
  * A disconnected render executes finalized routes directly. For a Scalive Live route this
  * exercises the production HTTP lifecycle, including routing, mount aspects, disconnected `mount`,
  * initial `handleParams`, layouts, components, nested LiveViews, session metadata, and CSRF
  * response handling. It does not start a server, follow redirects, join a LiveSocket, perform the
  * separate connected mount, dispatch events, submit forms, or run connected asynchronous work.
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
    ZIO.scoped {
      routes.runZIO(request).flatMap { response =>
        response.body.asString.flatMap { html =>
          ZIO.attempt {
            val document           = Jsoup.parse(html, request.url.encode)
            val replayableResponse = response.copy(body = Body.fromString(html))
            RenderedPage(replayableResponse, html, document)
          }
        }
      }
    }

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

/** A response and read-only semantic view of its Jsoup-parsed HTML. */
final class RenderedPage private[testing] (
  responseValue: Response,
  htmlValue: String,
  document: Document):

  /** The route response with its body replaced by a replayable body containing [[html]]. */
  val response: Response = responseValue

  /** The complete decoded response body before Jsoup parsing. */
  val html: String = htmlValue

  /** Returns the parsed document's decoded, combined text with whitespace normalized by Jsoup. */
  def text: String = document.text()

  /** Returns every parsed `form` element in DOM order. */
  def forms: Vector[RenderedForm] =
    document.select("form").asScala.toVector.map(RenderedForm(_))

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

object RenderedPage:
  private[testing] def apply(
    response: Response,
    html: String,
    document: Document
  ): RenderedPage =
    new RenderedPage(response, html, document)

/** A read-only view of one form in a parsed disconnected-render snapshot.
  *
  * Field accessors inspect rendered markup only. They do not dispatch bindings or construct a
  * browser's successful-controls data set.
  */
final class RenderedForm private[testing] (element: Element):
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

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))
end RenderedForm

object RenderedForm:
  private[testing] def apply(element: Element): RenderedForm =
    new RenderedForm(element)

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
