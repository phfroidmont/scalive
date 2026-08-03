package scalive

import scala.quoted.*

opaque type DomRef = String

object DomRef:
  private val ValidIdentifier = "[A-Za-z_][A-Za-z0-9_-]*".r

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
    def value: String           = ref
    def attr: Mod.Attr[Nothing] = idAttr := ref
    def selector: DomSelector   = DomSelector.css(s"#$ref")

opaque type DomSelector = Option[String]

object DomSelector:
  val current: DomSelector = None

  def css(value: String): DomSelector =
    require(value.nonEmpty, "DOM selector must not be empty")
    Some(value)

  extension (selector: DomSelector)
    private[scalive] def jsonValue: Option[String] = selector
    private[scalive] def requiredValue: String     =
      selector.getOrElse(throw new IllegalArgumentException("current element is not valid here"))
