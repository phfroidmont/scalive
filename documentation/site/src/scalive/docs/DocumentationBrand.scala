package scalive.docs

import scalive.*
import scalive.codecs.StringAsIsEncoder

private[docs] object DocumentationBrand:
  private[docs] val TopPath    = "M18 52V26L78 2V28L38 44H46L42 52Z"
  private[docs] val BottomPath = "M54 44H78V70L18 94V68L58 52H50Z"

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
