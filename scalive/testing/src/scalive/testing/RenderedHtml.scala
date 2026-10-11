package scalive.testing

import java.util.Locale
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
    selectAll(selector).flatMap {
      case Vector(element) => Right(element)
      case Vector()        => Left(HtmlQueryError.NotFound(selector))
      case matches         => Left(HtmlQueryError.MultipleMatches(selector, matches.size))
    }

  /** Selects all matching elements in Jsoup document order; no matches produce an empty vector.
    *
    * Invalid selectors, including empty and blank selectors, produce
    * [[HtmlQueryError.InvalidSelector]]. The results contain immutable values, not references to
    * mutable Jsoup elements.
    */
  def selectAll(selector: String): Either[HtmlQueryError, Vector[RenderedElement]] =
    // Jsoup's :matchText selector mutates its tree, so every query needs an isolated copy.
    val selected =
      try Right(document.clone().select(selector))
      catch
        case error: SelectorParseException =>
          Left(HtmlQueryError.InvalidSelector(selector, error.getMessage))
        case error: IllegalArgumentException =>
          Left(HtmlQueryError.InvalidSelector(selector, error.getMessage))

    selected.map(_.asScala.toVector.map { element =>
      val attributes = element
        .attributes().asList().asScala.iterator
        .map(attribute => attribute.getKey.toLowerCase(Locale.ROOT) -> attribute.getValue).toMap
      new RenderedElement(element.tagName(), element.text(), element.`val`(), attributes)
    })
end RenderedHtml

/** Constructors for [[RenderedHtml]] snapshots. */
object RenderedHtml:
  /** Parses an HTML document or ordinary body content while retaining the exact input string.
    *
    * Uses Jsoup's HTML document parser, including its implied document elements and markup repair.
    * This is not HTML validation or context-specific fragment parsing, such as a fragment inside a
    * table. Scripts are never executed.
    */
  def parse(html: String): RenderedHtml = new RenderedHtml(html, Jsoup.parse(html))

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
final class RenderedElement private[testing] (
  val tagName: String,
  val text: String,
  val value: String,
  private val attributes: Map[String, String]):

  /** Returns a decoded, unresolved attribute value with a case-insensitive HTML name lookup.
    * Preserves absent versus present-empty and does not synthesize absolute URL attributes.
    */
  def attribute(name: String): Option[String] = attributes.get(name.toLowerCase(Locale.ROOT))
