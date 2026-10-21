package scalive.testing

import java.util.Locale
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.jsoup.select.Selector.SelectorParseException

/** Describes why a CSS query could not select rendered elements. */
enum HtmlQueryError:
  /** The selector is not valid Jsoup CSS syntax, for example because it is blank.
    *
    * @param selector
    *   The selector that failed to parse.
    * @param message
    *   Jsoup's description of the parse error.
    */
  case InvalidSelector(selector: String, message: String)

  /** No element matches a selector that must match exactly one.
    *
    * @param selector
    *   The selector that matched nothing.
    */
  case NotFound(selector: String)

  /** More than one element matches a selector that must match exactly one.
    *
    * @param selector
    *   The ambiguous selector.
    * @param count
    *   The number of matching elements.
    */
  case MultipleMatches(selector: String, count: Int)

  /** Describes the failure in a sentence that names the selector. */
  def description: String = this match
    case InvalidSelector(selector, message) => s"Invalid selector '$selector': $message"
    case NotFound(selector)                 => s"'$selector' matches no element."
    case MultipleMatches(selector, count)   => s"'$selector' matches $count elements, not one."
end HtmlQueryError

/** An immutable semantic snapshot parsed from retained HTML.
  *
  * Queries inspect rendered markup, not browser properties or successful form controls. Each query
  * is isolated from other queries, including Jsoup selectors that modify their working document.
  * Snapshots and selected elements have no live-view, HTTP, or scope ownership.
  *
  * @param html
  *   The exact input string, before parsing or markup repair.
  */
final class RenderedHtml private (
  val html: String,
  document: Document):
  /** The parsed document's decoded, combined text with whitespace normalized by Jsoup. */
  val text: String = document.text()

  /** Selects exactly one element, reporting missing or ambiguous matches explicitly. */
  def selectOne(selector: String): Either[HtmlQueryError, RenderedElement] =
    RenderedHtml.exactlyOne(selector, selectAll(selector))

  /** Selects all matching elements in Jsoup document order; no matches produce an empty vector.
    *
    * Invalid selectors, including empty and blank selectors, produce
    * [[HtmlQueryError.InvalidSelector]]. The results contain immutable values, not references to
    * mutable Jsoup elements.
    */
  def selectAll(selector: String): Either[HtmlQueryError, Vector[RenderedElement]] =
    RenderedHtml.select(document, selector, includeRoot = true)

/** Constructors for [[RenderedHtml]] snapshots. */
object RenderedHtml:
  /** Parses an HTML document or ordinary body content while retaining the exact input string.
    *
    * Uses Jsoup's HTML document parser, including its implied document elements and markup repair.
    * This is not HTML validation or context-specific fragment parsing, such as a fragment inside a
    * table. Scripts are never executed.
    */
  def parse(html: String): RenderedHtml = new RenderedHtml(html, Jsoup.parse(html))

  private[testing] def exactlyOne(
    selector: String,
    selected: Either[HtmlQueryError, Vector[RenderedElement]]
  ): Either[HtmlQueryError, RenderedElement] =
    selected.flatMap {
      case Vector(element) => Right(element)
      case Vector()        => Left(HtmlQueryError.NotFound(selector))
      case matches         => Left(HtmlQueryError.MultipleMatches(selector, matches.size))
    }

  private[testing] def select(
    root: Element,
    selector: String,
    includeRoot: Boolean
  ): Either[HtmlQueryError, Vector[RenderedElement]] =
    // Jsoup's :matchText selector mutates its tree, so every query needs an isolated copy.
    val copy     = root.clone()
    val selected =
      try Right(copy.select(selector).asScala.toVector)
      catch
        case error: SelectorParseException =>
          Left(HtmlQueryError.InvalidSelector(selector, error.getMessage))
        case error: IllegalArgumentException =>
          Left(HtmlQueryError.InvalidSelector(selector, error.getMessage))
    selected.map(_.filter(element => includeRoot || !(element eq copy)).map(RenderedElement(_)))
end RenderedHtml

/** Immutable values captured from one selected rendered element.
  *
  * @param tagName
  *   The Jsoup-normalized HTML tag name.
  * @param text
  *   Decoded, combined text with whitespace handled according to Jsoup's HTML tag rules.
  * @param value
  *   The same Jsoup value used by [[RenderedField.value]]: textarea text with outer whitespace
  *   trimmed and internal whitespace preserved, otherwise the element's own `value` attribute or an
  *   empty string. Does not infer selected options, checkbox defaults, native-input sanitization,
  *   or browser submission values.
  */
final class RenderedElement private (
  val tagName: String,
  val text: String,
  val value: String,
  private val attributes: Map[String, String],
  element: Element):

  /** Returns a decoded, unresolved attribute value with a case-insensitive HTML name lookup.
    * Preserves absent versus present-empty and does not synthesize absolute URL attributes.
    */
  def attribute(name: String): Option[String] = attributes.get(name.toLowerCase(Locale.ROOT))

  /** The element's classes, from its `class` attribute. */
  def classes: Set[String] =
    attribute("class").fold(Set.empty)(_.split("\\s+").filter(_.nonEmpty).toSet)

  /** Selects exactly one element inside this one, reporting missing or ambiguous matches. */
  def selectOne(selector: String): Either[HtmlQueryError, RenderedElement] =
    RenderedHtml.exactlyOne(selector, selectAll(selector))

  /** Selects all matching elements inside this one, in document order, excluding the element
    * itself.
    */
  def selectAll(selector: String): Either[HtmlQueryError, Vector[RenderedElement]] =
    RenderedHtml.select(element, selector, includeRoot = false)

private[testing] object RenderedElement:
  def apply(element: Element): RenderedElement =
    val attributes = element
      .attributes().asList().asScala.iterator
      .map(attribute => attribute.getKey.toLowerCase(Locale.ROOT) -> attribute.getValue).toMap
    new RenderedElement(element.tagName(), element.text(), element.`val`(), attributes, element)
