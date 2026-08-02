package scalive.examples

import scalive.*

object ExamplesLayout extends LiveLayout[Any, Any]:
  def render[Msg](content: HtmlElement[Msg], ctx: LiveLayoutContext[Any, Any]): HtmlElement[Msg] =
    div(
      cls := "min-h-screen lg:grid lg:grid-cols-[18rem_minmax(0,1fr)]",
      asideTag(
        cls := "border-b border-base-300 bg-base-100 lg:sticky lg:top-0 lg:h-screen lg:overflow-y-auto lg:border-b-0 lg:border-r",
        div(
          cls := "px-5 pb-3 pt-5 lg:px-6 lg:pb-6 lg:pt-8",
          link.pushNavigate(
            ExamplesRoutes.home.location,
            cls := "inline-flex items-baseline gap-2 text-xl font-bold tracking-tight",
            span(cls := "text-primary", "Scalive"),
            span("examples")
          ),
          p(
            cls := "mt-2 max-w-sm text-sm leading-6 text-base-content/65",
            "Focused examples of typed, server-rendered interactive applications."
          )
        ),
        navigation(ctx.currentUrl.path.encode)
      ),
      mainTag(
        cls := "min-w-0 px-5 py-8 sm:px-8 lg:px-12 lg:py-12 xl:px-16",
        div(cls := "mx-auto max-w-6xl", content)
      )
    )

  private def navigation(currentPath: String): HtmlElement[Nothing] =
    navTag(
      aria.label := "Example navigation",
      cls        := "flex gap-6 overflow-x-auto px-5 pb-5 lg:block lg:px-4 lg:pb-8",
      ExampleCatalog.byCategory.map { (category, entries) =>
        sectionTag(
          cls := "min-w-max lg:mb-6",
          h2(
            cls := "mb-2 px-2 text-xs font-semibold uppercase tracking-[0.16em] text-base-content/50",
            category
          ),
          ul(
            cls := "menu menu-horizontal gap-1 rounded-box bg-transparent p-0 lg:menu-vertical lg:w-full",
            entries.map { entry =>
              val entryPath = entry.location.href.takeWhile(char => char != '?' && char != '#')
              li(
                link.pushNavigate(
                  entry.location,
                  cls := (if entryPath == currentPath then "active font-medium" else "font-medium"),
                  entry.title
                )
              )
            }
          )
        )
      }
    )
end ExamplesLayout
