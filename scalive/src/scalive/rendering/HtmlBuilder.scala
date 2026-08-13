package scalive

/** Advanced standalone serialization for a resolved Scalive HTML tree.
  *
  * Normal LiveView responses are rendered by the lifecycle and diff runtime. This helper is useful
  * for tests, integrations, and final serialization after framework-owned content such as
  * LiveComponents and flash placeholders has been resolved. Event bindings are rendered as
  * generated protocol IDs and `JSCommand` JSON; the resulting markup is interactive only when used
  * with the corresponding LiveView runtime and binding registry.
  */
object HtmlBuilder:

  /** Serializes `el` to one HTML string.
    *
    * Ordinary text and all quoted attribute values are HTML-escaped. Raw text created with
    * [[scalive.rawHtml]] is emitted verbatim. Escaping prevents markup injection in those text and
    * attribute contexts but is not sanitization: values such as URL schemes, inline CSS, and
    * browser protocol strings are not interpreted or filtered.
    *
    * A tag marked `void` emits `<tag/>` and no closing tag. If such an element contains content,
    * the content is still serialized after that opening markup, so valid DSL code should not give
    * void tags children. When `isRoot` is true, the exact prefix `<!doctype html>` is added; this
    * flag does not require an `html` tag, add missing document structure, or otherwise change
    * element rendering.
    *
    * Unresolved LiveComponent and flash placeholders require runtime state and fail fast; resolve
    * them through the LiveView rendering lifecycle. An unresolved nested LiveView is reduced to a
    * child marker only and is not mounted by this helper.
    *
    * @param el
    *   resolved root element to serialize
    * @param isRoot
    *   whether to prepend an HTML doctype
    * @return
    *   complete serialized markup for this tree
    */
  def build(el: HtmlElement[?], isRoot: Boolean = false): String =
    RenderSnapshot.renderHtml(RenderSnapshot.compile(el), isRoot)
