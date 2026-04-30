package scalive

object HtmlBuilder:

  def build(el: HtmlElement[?], isRoot: Boolean = false): String =
    RenderSnapshot.renderHtml(RenderSnapshot.compile(el), isRoot)
