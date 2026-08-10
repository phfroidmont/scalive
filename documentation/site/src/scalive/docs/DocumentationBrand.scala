package scalive.docs

import scalive.*
import scalive.codecs.StringAsIsEncoder

private[docs] object DocumentationBrand:
  private[docs] val TopPath    = "M12 52V28L84 4V28L36 44H48L40 52Z"
  private[docs] val BottomPath = "M56 44H84V68L12 92V68L60 52H48Z"

  private val svgTag    = HtmlTag("svg")
  private val pathTag   = HtmlTag("path")
  private val viewBox   = htmlAttr("viewBox", StringAsIsEncoder)
  private val pathData  = htmlAttr("d", StringAsIsEncoder)
  private val focusable = htmlAttr("focusable", StringAsIsEncoder)

  def mark(className: String): HtmlElement[Nothing] =
    svgTag(
      cls         := className,
      viewBox     := "0 0 96 96",
      aria.hidden := true,
      focusable   := "false",
      pathTag(cls := "docs-brand-plane docs-brand-plane-top", pathData    := TopPath),
      pathTag(cls := "docs-brand-plane docs-brand-plane-bottom", pathData := BottomPath)
    )

  def lockup: HtmlElement[Nothing] =
    span(
      cls := "docs-brand-lockup",
      mark("docs-brand-mark"),
      span(cls := "docs-brand-wordmark", "scalive")
    )
