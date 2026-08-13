package scalive.defs.complex

import scalive.HtmlAttr
import scalive.codecs.*

/** HTML attributes that require aliases, dynamic names, or manually selected codecs. */
trait ComplexHtmlKeys extends NamespacedHtmlKeys:

  // #Note: we use attrs instead of props here because of https://github.com/raquo/Laminar/issues/136

  /** Defines the element's space-separated CSS classes.
    *
    * The value is emitted as the HTML `class` attribute without parsing or normalization.
    */
  val className: HtmlAttr[String] = new HtmlAttr("class", StringAsIsEncoder)

  /** Concise alias for [[className]]. */
  val cls: HtmlAttr[String] = className

  /** Defines the space-separated relationship types of a linked resource.
    *
    * For example, a stylesheet link uses `rel := "stylesheet"`. Values are emitted without parsing
    * or normalization.
    */
  lazy val rel: HtmlAttr[String] = new HtmlAttr("rel", StringAsIsEncoder)

  /** Defines the accessibility role of the current element.
    *
    * Prefer an HTML element with the required native semantics when one exists; use `role` only
    * when additional semantics are necessary. The value is emitted without validating ARIA role
    * names.
    *
    * See: [[http://www.w3.org/TR/role-attribute/#s_role_module_attributes]]
    */
  lazy val role: HtmlAttr[String] = new HtmlAttr("role", StringAsIsEncoder)

  /** Creates a custom `data-*` string attribute from `suffix`.
    *
    * The suffix is appended verbatim and the resulting attribute name receives only generic
    * [[HtmlAttr]] validation; it is not lowercased or otherwise normalized. Callers remain
    * responsible for `data-*` naming rules. In particular, use a non-empty lowercase suffix that
    * does not start with `xml` (case-insensitive) or contain a colon. Kebab-case supports standard
    * `HTMLElement.dataset` mapping: for example, `dataAttr("test-value")` is exposed as
    * `element.dataset.testValue` in JavaScript.
    *
    * @throws IllegalArgumentException
    *   if the resulting HTML attribute name is invalid
    */
  def dataAttr(suffix: String): HtmlAttr[String] = new HtmlAttr(s"data-$suffix", StringAsIsEncoder)

  /** Defines inline CSS declarations through the HTML `style` attribute.
    *
    * The declaration string is emitted without CSS parsing or sanitization. Prefer classes and a
    * stylesheet for reusable styles, and do not interpolate untrusted CSS.
    */
  lazy val styleAttr: HtmlAttr[String] = new HtmlAttr("style", StringAsIsEncoder)

end ComplexHtmlKeys
