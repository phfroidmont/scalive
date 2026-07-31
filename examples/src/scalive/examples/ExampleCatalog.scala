package scalive.examples

import scalive.LiveLocation
import scalive.examples.navigation.SearchParams

final case class ExampleEntry(
  category: String,
  title: String,
  description: String,
  location: LiveLocation)

object ExampleCatalog:
  val entries = List(
    ExampleEntry(
      category = "Getting started",
      title = "Catalog",
      description =
        "Application setup, tracked assets, layouts, typed routes, and an eventless LiveView.",
      location = ExamplesRoutes.home.location
    ),
    ExampleEntry(
      category = "State",
      title = "Shopping cart",
      description = "Typed messages, connection-local state, derived totals, and keyed rows.",
      location = ExamplesRoutes.shoppingCart.location
    ),
    ExampleEntry(
      category = "Services",
      title = "Guestbook",
      description = "A shared in-memory service built from a ZLayer and injected into a LiveView.",
      location = ExamplesRoutes.guestbook.location
    ),
    ExampleEntry(
      category = "Subscriptions",
      title = "Clock",
      description = "Start, replace, and cancel a ZStream with one typed subscription key.",
      location = ExamplesRoutes.subscriptions.location
    ),
    ExampleEntry(
      category = "Async work",
      title = "Report generator",
      description =
        "Typed async results with success, failure, replacement, retry, and cancellation.",
      location = ExamplesRoutes.async.location
    ),
    ExampleEntry(
      category = "Authentication",
      title = "Login and profile",
      description =
        "Ordinary HTTP login and logout, framework CSRF, an opaque cookie, and protected Live routes.",
      location = ExamplesRoutes.login.location
    ),
    ExampleEntry(
      category = "Forms",
      title = "Profile editor",
      description =
        "Typed decoding, accumulated validation, used-field feedback, and successful submission.",
      location = ExamplesRoutes.profileForm.location
    ),
    ExampleEntry(
      category = "Uploads",
      title = "Document uploader",
      description =
        "Small upload constraints, progress, cancellation, consumption, scoped storage, and deletion.",
      location = ExamplesRoutes.documents.location
    ),
    ExampleEntry(
      category = "Navigation",
      title = "Search navigation",
      description =
        "Schema-derived query parameters, complete typed locations, patch links, and server-driven navigation.",
      location = ExamplesRoutes.search.location(SearchParams(None, None))
    ),
    ExampleEntry(
      category = "Collections",
      title = "Activity stream",
      description =
        "A durable activity vector paired with an opaque stream for bounded inserts, deletes, and resets.",
      location = ExamplesRoutes.activity.location
    ),
    ExampleEntry(
      category = "Components",
      title = "Voting components",
      description =
        "Stable component identities, local state, typed parent messages, and targeted prop updates.",
      location = ExamplesRoutes.voting.location
    ),
    ExampleEntry(
      category = "Client interop",
      title = "Browser integration",
      description =
        "Client-only JS commands, a typed server event, and a validated raw hook reply.",
      location = ExamplesRoutes.browserInterop.location
    ),
    ExampleEntry(
      category = "Lifecycle UX",
      title = "Notifications",
      description =
        "Connection state, keyed flash, title changes, and a side-effect-only after-render hook.",
      location = ExamplesRoutes.notifications.location
    )
  )

  val byCategory = entries.map(_.category).distinct.map { category =>
    category -> entries.filter(_.category == category)
  }
end ExampleCatalog
