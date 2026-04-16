import scalive.*

object NavigationLayout:

  def apply(content: HtmlElement): HtmlElement =
    div(
      styleTag(
        "html, body {",
        "  margin: 0;",
        "  padding: 0;",
        "  font-family: system-ui, -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Ubuntu, \"Helvetica Neue\", sans-serif;",
        "  font-size: 1rem;",
        "}"
      ),
      div(
        styleAttr := "display: flex; width: 100%; height: 100vh;",
        div(
          styleAttr := "position: fixed; height: 100vh; background-color: #f8fafc; border-right: 1px solid; width: 20rem; display: flex; flex-direction: column; padding: 1rem; gap: 0.5rem;",
          h1(
            styleAttr := "margin-bottom: 1rem; font-size: 1.125rem; line-height: 1.75rem;",
            "Navigation"
          ),
          link.navigate(
            "/navigation/a",
            styleAttr := "background-color: #f1f5f9; padding: 0.5rem;",
            "LiveView A"
          ),
          link.navigate(
            "/navigation/b",
            styleAttr := "background-color: #f1f5f9; padding: 0.5rem;",
            "LiveView B"
          ),
          link.navigate(
            "/stream",
            styleAttr := "background-color: #f1f5f9; padding: 0.5rem;",
            "LiveView (other session)"
          ),
          link.navigate(
            "/navigation/dead",
            styleAttr := "background-color: #f1f5f9; padding: 0.5rem;",
            "Dead View"
          )
        ),
        div(styleAttr := "margin-left: 22rem; flex: 1; padding: 2rem;", content)
      )
    )
end NavigationLayout
