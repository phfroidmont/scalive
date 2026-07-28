package scalive.examples

import scalive.*

final class HomeLiveView extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = LiveIO.succeed(())

  def render(model: Unit) =
    div(
      headerTag(
        cls := "mb-10 border-b border-base-300 pb-8",
        div(cls := "badge badge-primary badge-outline mb-4", "Getting started"),
        h1(
          cls := "max-w-3xl text-4xl font-bold tracking-tight sm:text-5xl",
          "Build with Scalive, one concept at a time"
        ),
        p(
          cls := "mt-5 max-w-2xl text-lg leading-8 text-base-content/70",
          "Each route is a focused, runnable example. Start here to see how the server, assets, layouts, typed routes, and LiveViews fit together."
        )
      ),
      ExampleCatalog.byCategory.map { (category, entries) =>
        sectionTag(
          cls := "mb-10",
          h2(
            cls := "mb-4 text-sm font-semibold uppercase tracking-[0.16em] text-base-content/55",
            category
          ),
          div(
            cls := "grid gap-4 md:grid-cols-2 xl:grid-cols-3",
            entries.map { entry =>
              link.navigate(
                entry.location,
                cls := "card border border-base-300 bg-base-100 shadow-sm transition hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md",
                articleTag(
                  cls := "card-body",
                  h3(cls   := "card-title text-lg", entry.title),
                  p(cls    := "leading-7 text-base-content/65", entry.description),
                  span(cls := "mt-2 text-sm font-semibold text-primary", "Open example")
                )
              )
            }
          )
        )
      }
    )
end HomeLiveView
