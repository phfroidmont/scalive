package scalive

import scala.quoted.*

/** A reusable, validated HTML `id` value which can also produce its exact CSS selector.
  *
  * Construct references with [[DomRef.apply]]. Values are nominal: a raw `String` cannot be used as
  * a `DomRef` or [[DomSelector]].
  */
opaque type DomRef = String

/** Constructs and exposes reusable DOM references. */
object DomRef:
  private val ValidIdentifier = "[A-Za-z_][A-Za-z0-9_-]*".r

  /** Creates a DOM reference from a CSS-safe identifier.
    *
    * Accepted values match `[A-Za-z_][A-Za-z0-9_-]*`. Invalid compile-time constants fail
    * compilation; dynamically computed values are checked with `require` when this method runs. The
    * value is used verbatim and is not trimmed or escaped.
    *
    * @throws IllegalArgumentException
    *   if a dynamic value is empty or does not match the accepted identifier grammar
    */
  inline def apply(inline value: String): DomRef = ${ DomRef.fromExpr('value) }

  private def fromExpr(value: Expr[String])(using Quotes): Expr[DomRef] =
    value.value match
      case Some(candidate) if !ValidIdentifier.matches(candidate) =>
        quotes.reflect.report.errorAndAbort(
          s"DOM reference must be a CSS-safe identifier, got '$candidate'"
        )
      case _ =>
        '{
          val candidate = $value
          require(
            candidate.matches("[A-Za-z_][A-Za-z0-9_-]*"),
            s"DOM reference must be a CSS-safe identifier, got '$candidate'"
          )
          candidate
        }

  extension (ref: DomRef)
    /** Returns the validated identifier as a string. */
    def value: String = ref

    /** Returns an `id` attribute assigning this reference to an element. */
    def attr: Mod.Attr[Nothing] = idAttr := ref

    /** Returns the exact ID selector for this reference, for example `#settings-panel`. */
    def selector: DomSelector = DomSelector.css(s"#$ref")
end DomRef

/** A nominal DOM selection used by JS commands and DOM-targeting APIs.
  *
  * Use [[DomSelector.css]] for an explicit CSS selector or [[DomSelector.current]] where an API
  * supports its current/source element. Raw strings are deliberately not accepted.
  */
opaque type DomSelector = Option[String]

/** Constructors for explicit and context-relative DOM selections. */
object DomSelector:
  /** Selects the current element according to the consuming API.
    *
    * For a JS command's `to` parameter this is the element executing the command. For `JS.push`, an
    * omitted target uses the source's normal `phx-target`/LiveView target resolution, and an
    * omitted loading selector adds no targets beyond the source's normal loading state. APIs which
    * require an explicit destination, such as `portal` and `phx.target`, reject `current` at
    * runtime.
    */
  val current: DomSelector = None

  /** Creates an explicit CSS selector.
    *
    * The selector must be non-empty, but is otherwise preserved verbatim: this method does not
    * trim, parse, escape, or validate CSS syntax. Invalid syntax is therefore reported by the
    * browser when the selector is used. Matching is performed by the consuming browser API and may
    * select multiple elements.
    *
    * @throws IllegalArgumentException
    *   if `value` is empty
    */
  def css(value: String): DomSelector =
    require(value.nonEmpty, "DOM selector must not be empty")
    Some(value)

  extension (selector: DomSelector)
    private[scalive] def jsonValue: Option[String] = selector
    private[scalive] def requiredValue: String     =
      selector.getOrElse(throw new IllegalArgumentException("current element is not valid here"))
