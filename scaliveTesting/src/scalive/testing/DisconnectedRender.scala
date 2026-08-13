package scalive.testing

import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import zio.*
import zio.http.*

import scalive.FormPath

/** Runs serverless tests against the first, disconnected HTTP render.
  *
  * A disconnected render executes the supplied finalized routes directly. For a Scalive Live route
  * this exercises the production HTTP lifecycle, including route and parameter decoding, mount
  * aspects, disconnected `mount`, initial `handleParams`, layouts, components, nested LiveViews,
  * session metadata, and CSRF response handling. It does not start a server, follow redirects, join
  * a LiveSocket, perform the separate connected mount, dispatch events, submit forms, or run
  * connected subscriptions and asynchronous work.
  *
  * The result is a snapshot of one route execution. Generated LiveView IDs, signed session values,
  * and CSRF values are intentionally opaque and may differ between snapshots; prefer semantic
  * accessors over comparing independently rendered HTML.
  *
  * A typical test queries the form by its rendered action and effective method:
  *
  * {{{
  * import zio.*
  * import zio.http.*
  * import scalive.*
  * import scalive.testing.*
  *
  * val check =
  *   for
  *     page <- DisconnectedRender.run(routes, Request.get(URL.root))
  *     form <- ZIO
  *               .fromEither(page.form(FormQuery(Some("/profiles"), Some(Method.POST))))
  *               .orDieWith(error => new AssertionError(error.toString))
  *   yield page.response.status == Status.Ok &&
  *     page.text.contains("Profile") &&
  *     form.values(FormPath("profile", "name")) == Vector("Alice")
  * }}}
  */
object DisconnectedRender:
  /** Executes `request` against `routes` and captures the returned page.
    *
    * No network listener is involved: `routes.runZIO(request)` runs in a fresh ZIO scope. The body
    * is fully consumed as a string before that scope closes, then replaced on
    * [[RenderedPage.response]] with a replayable string body. Status, headers, and the other
    * response metadata are preserved. [[RenderedPage.html]] is the consumed, decoded string, not a
    * Jsoup serialization and not a byte-for-byte copy of the original body encoding.
    *
    * The complete request is passed through unchanged, so routing, query parameters, headers,
    * cookies, and request bodies remain available to the route. The required route environment `R`
    * remains in the returned effect.
    *
    * HTTP responses of every status are successful results, including not-found, server-error, and
    * redirect responses. Redirects are returned for inspection and are never followed. Because the
    * routes have no typed error, the `Throwable` failure channel covers materializing and parsing
    * the page snapshot; defects and interruption retain their normal ZIO semantics.
    *
    * @param routes
    *   finalized routes to execute directly
    * @param request
    *   the complete HTTP request supplied to the routes
    * @tparam R
    *   the environment required by the routes
    * @return
    *   an effect producing a disconnected page snapshot
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
end DisconnectedRender

/** Exact filters used to select one rendered form.
  *
  * Each `None` is a wildcard. When both fields are defined, both must match. An entirely empty
  * query therefore matches every form and succeeds only when the page contains exactly one form.
  * Matching is performed against the Jsoup-parsed attribute value and the effective method exposed
  * by [[RenderedForm]]; action values are not resolved against the request URL, and string matching
  * is case-sensitive.
  *
  * @param action
  *   exact `action` attribute to require; forms without the attribute do not match a defined value
  * @param method
  *   effective method to require; values other than `Method.GET` or `Method.POST` cannot match
  */
final case class FormQuery(
  action: Option[String] = None,
  method: Option[Method] = None)

/** Describes why [[RenderedPage.form]] could not select exactly one form. */
enum FormQueryError:
  /** No rendered form matched `query`.
    *
    * @param query
    *   the immutable query used for the failed lookup
    */
  case NotFound(query: FormQuery)

  /** More than one rendered form matched `query`.
    *
    * @param query
    *   the immutable query used for the failed lookup
    * @param count
    *   the number of matching forms in the parsed snapshot
    */
  case MultipleMatches(query: FormQuery, count: Int)

/** A response and read-only semantic view of its Jsoup-parsed HTML.
  *
  * Parsing uses the request URL as Jsoup's base URI and HTML parsing rules, so malformed markup may
  * be repaired, tag and attribute names are normalized as HTML, entities are decoded, and text
  * whitespace is normalized. These parsed semantics can differ from the exact decoded response
  * string in [[html]].
  *
  * Instances, and the [[RenderedForm]] and [[RenderedField]] wrappers obtained from them, use
  * reference identity rather than structural equality. They describe this one render snapshot;
  * compare their public values when making assertions.
  */
final class RenderedPage private[testing] (
  responseValue: Response,
  htmlValue: String,
  document: Document):

  /** The route response with its body replaced by a replayable body containing [[html]].
    *
    * Status and response metadata such as headers and cookies are preserved. A redirect remains a
    * redirect response; it has not been followed.
    */
  val response: Response = responseValue

  /** The complete response body decoded to a string before Jsoup parsing.
    *
    * This preserves the consumed string as returned by the body decoder. It is not normalized or
    * reserialized by Jsoup; use it for low-level markup assertions and [[text]], [[forms]], or
    * [[form]] for semantic assertions.
    */
  val html: String = htmlValue

  /** Returns the parsed document's decoded, combined text with whitespace normalized by Jsoup. */
  def text: String = document.text()

  /** Returns every parsed `form` element in DOM order.
    *
    * This includes forms regardless of action, method, or LiveView bindings. Each invocation
    * creates new reference-identity wrappers over elements in the same parsed snapshot.
    */
  def forms: Vector[RenderedForm] =
    document.select("form").asScala.toVector.map(RenderedForm(_))

  /** Selects exactly one form matching `query`.
    *
    * Forms are filtered in DOM order using all defined fields. This returns
    * [[FormQueryError.NotFound]] for zero matches and [[FormQueryError.MultipleMatches]] with the
    * observed count for two or more; it never silently chooses the first match. The default empty
    * query consequently means "the only form on the page", not "the first form".
    *
    * @param query
    *   optional exact action and effective-method filters
    */
  def form(query: FormQuery = FormQuery()): Either[FormQueryError, RenderedForm] =
    val matches = forms.filter(renderedForm =>
      query.action.forall(renderedForm.action.contains) &&
        query.method.forall(_ == renderedForm.method)
    )

    matches match
      case Vector(form) => Right(form)
      case Vector()     => Left(FormQueryError.NotFound(query))
      case forms        => Left(FormQueryError.MultipleMatches(query, forms.size))
end RenderedPage

object RenderedPage:
  private[testing] def apply(
    response: Response,
    html: String,
    document: Document
  ): RenderedPage =
    new RenderedPage(response, html, document)

/** A read-only view of one form in a parsed disconnected-render snapshot.
  *
  * The field accessors inspect rendered markup only. They do not dispatch `phx-change` or
  * `phx-submit`, choose a submitter, construct an HTTP request, or submit to [[action]]. In
  * particular, [[fields]] and [[values]] are not the browser's successful-controls data set: named
  * descendants are retained even when disabled, unchecked, or otherwise unsuccessful, while
  * controls associated through an external `form` attribute are not included.
  */
final class RenderedForm private[testing] (element: Element):
  /** Returns the parsed `id` attribute, preserving absent versus present-empty as `None` or `Some`.
    */
  def id: Option[String] = attribute("id")

  /** Returns the parsed, unresolved `action` attribute.
    *
    * Absence is `None`, a present empty attribute is `Some("")`, and relative actions are not
    * resolved against the page URL.
    */
  def action: Option[String] = attribute("action")

  /** Returns the form's effective HTTP method for this API.
    *
    * A `method` attribute equal to `post`, ignoring case, produces `Method.POST`. Every other
    * value, including absent, empty, `get`, and `dialog`, produces `Method.GET` in this API. It
    * does not model dialog submission, and no other method is exposed.
    */
  def method: Method =
    if attribute("method").exists(_.equalsIgnoreCase("post")) then Method.POST else Method.GET

  /** Returns named descendant controls in DOM order.
    *
    * The exact selection is `button[name], input[name], select[name], textarea[name]`. The `name`
    * attribute only has to be present and may be empty. Unnamed controls and controls outside the
    * form, including those associated with a `form` attribute, are omitted. Disabled controls,
    * unchecked checkboxes and radios, buttons that were not used as a submitter, and non-data input
    * types are not filtered out.
    */
  def fields: Vector[RenderedField] =
    element
      .select("button[name], input[name], select[name], textarea[name]").asScala.toVector
      .map(RenderedField(_))

  /** Returns the [[RenderedField.name]] of every selected field, retaining duplicates and DOM
    * order.
    */
  def names: Vector[String] = fields.map(_.name)

  /** Returns Jsoup values for fields whose parsed name exactly equals `name`.
    *
    * Duplicate names and DOM order are retained. This is a markup query, not a browser submission;
    * see [[RenderedField.value]] for the effective value rules.
    *
    * @param name
    *   the case-sensitive control name to select
    */
  def values(name: String): Vector[String] =
    fields.collect { case field if field.name == name => field.value }

  /** Returns [[values values]] for the browser-style name rendered by `path`.
    *
    * @param path
    *   a structured field path whose `FormPath.name` is matched exactly
    */
  def values(path: FormPath): Vector[String] =
    values(path.name)

  /** Reports whether the form has a `phx-change` attribute.
    *
    * This tests attribute presence only: empty values and values such as `"false"` still return
    * `true`. It does not dispatch the binding.
    */
  def hasChangeBinding: Boolean = element.hasAttr("phx-change")

  /** Reports whether the form has a `phx-submit` attribute.
    *
    * This tests attribute presence only: empty values and values such as `"false"` still return
    * `true`. It does not submit the form or dispatch the binding.
    */
  def hasSubmitBinding: Boolean = element.hasAttr("phx-submit")

  /** Reports whether the form has a `phx-trigger-action` attribute.
    *
    * This tests attribute presence, not its textual or boolean interpretation, so
    * `phx-trigger-action="false"` also returns `true`. No ordinary form action is triggered.
    */
  def triggersAction: Boolean = element.hasAttr("phx-trigger-action")

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))
end RenderedForm

object RenderedForm:
  private[testing] def apply(element: Element): RenderedForm =
    new RenderedForm(element)

/** A named `button`, `input`, `select`, or `textarea` in a parsed form snapshot.
  *
  * This exposes Jsoup's parsed markup state, not the mutable state of a browser control and not a
  * successful form-submission entry. The wrapper has reference identity; compare its accessors in
  * assertions.
  */
final class RenderedField private[testing] (element: Element):
  /** Returns the Jsoup-normalized HTML tag name, such as `input` or `textarea`. */
  def tagName: String = element.tagName()

  /** Returns the parsed `id` attribute, preserving absent versus present-empty as `None` or `Some`.
    */
  def id: Option[String] = attribute("id")

  /** Returns the parsed `name` attribute.
    *
    * Selection guarantees that the attribute is present, but its value may be empty. Matching by
    * [[RenderedForm.values]] is case-sensitive.
    */
  def name: String = element.attr("name")

  /** Returns the value reported by Jsoup's `Element.val()`.
    *
    * For a `textarea`, this is its decoded text with Jsoup whitespace normalization. For every
    * other selected tag, including `input`, `button`, and `select`, it is the parsed `value`
    * attribute or `""` when that attribute is absent. It therefore does not apply browser
    * successful-control rules: it does not exclude disabled or unchecked controls, supply the
    * checkbox default `"on"`, identify the clicked submitter, or derive one or more values from a
    * select's selected options.
    */
  def value: String = element.`val`()

  /** Returns a non-empty parsed `type` attribute for an `input`, otherwise `None`.
    *
    * The attribute value is not converted to the browser's effective input type; absent and
    * present-empty `type` attributes are both `None`, and non-input controls always return `None`.
    */
  def inputType: Option[String] =
    Option.when(tagName == "input")(element.attr("type")).filter(_.nonEmpty)

  /** Reports whether the control has a `required` attribute.
    *
    * This is an attribute-presence check only and does not perform browser constraint validation.
    */
  def required: Boolean = element.hasAttr("required")

  private def attribute(name: String): Option[String] =
    Option.when(element.hasAttr(name))(element.attr(name))
end RenderedField

object RenderedField:
  private[testing] def apply(element: Element): RenderedField =
    new RenderedField(element)
