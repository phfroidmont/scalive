package scalive.docs.model

object ExampleCatalog:
  val AsyncReport = ExampleDescriptor(
    id = "async-report",
    title = "Managed async report",
    description =
      "A typed task key drives deterministic success, failure, replacement, retry, and cancellation states.",
    topics = Vector("async work", "cancellation", "failure handling", "lifecycle"),
    aliases = Vector("AsyncKey", "AsyncValue", "LiveAsyncResult", "replace", "retry"),
    resetDescription = "Cancel active work and return the report state to empty.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/AsyncReportExample.scala",
        region = "async-report-example",
        language = Some("scala")
      )
    )
  )

  val ActivityStream = ExampleDescriptor(
    id = "activity-stream",
    title = "Bounded activity stream",
    description =
      "Durable activity history drives an opaque stream handle with stable IDs and a five-row DOM window.",
    topics = Vector("streams", "collections", "DOM patches", "bounded rendering"),
    aliases = Vector("LiveStream", "LiveStreamDef", "insert", "delete", "reset", "keepLast"),
    resetDescription = "Restore the initial durable history and rendered stream window.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/ActivityStreamExample.scala",
        region = "activity-stream-example",
        language = Some("scala")
      )
    )
  )

  val Counter = ExampleDescriptor(
    id = "counter",
    title = "Typed counter",
    description = "A LiveView with a count model and Decrement, Reset, and Increment messages.",
    topics = Vector("typed messages", "server state", "DOM patches"),
    aliases = Vector("increment", "decrement", "reset"),
    resetDescription = "Set the count back to zero.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/CounterExample.scala",
        region = "counter-example",
        language = Some("scala")
      )
    )
  )

  val BrowserIntegration = ExampleDescriptor(
    id = "browser-integration",
    title = "Browser integration",
    description =
      "Composed JS commands and a focused hook exchange correlated, typed browser events.",
    topics = Vector("JS commands", "browser events", "hooks", "client interop"),
    aliases = Vector(
      "JS",
      "ServerToBrowserEvent",
      "BrowserToServerEvent",
      "LiveHooks",
      "dom.hook"
    ),
    resetDescription = "Restore the client panel and clear the latest browser operation.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala",
        region = "browser-integration-example",
        language = Some("scala")
      ),
      ExampleSource(
        label = "Browser hook",
        path = "documentation/site/assets/js/browser-interop.js",
        region = "browser-integration-hook",
        language = Some("javascript")
      )
    )
  )

  val ShoppingCart = ExampleDescriptor(
    id = "shopping-cart",
    title = "Connection-local shopping cart",
    description = "Typed messages update an immutable cart with derived totals and SKU-keyed rows.",
    topics = Vector("typed messages", "immutable state", "derived state", "keyed rendering"),
    aliases = Vector("cart", "products", "quantity", "subtotal", "clear"),
    resetDescription = "Remove every item and return the cart to its empty state.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/ShoppingCartExample.scala",
        region = "shopping-cart-example",
        language = Some("scala")
      )
    )
  )

  val SubscriptionClock = ExampleDescriptor(
    id = "subscription-clock",
    title = "Managed clock subscription",
    description =
      "An instance-scoped subscription key starts, replaces, cancels, and resets a managed clock stream.",
    topics = Vector("subscriptions", "ZStream", "cancellation", "lifecycle"),
    aliases = Vector("SubscriptionKey", "start", "replace", "cancel", "clock"),
    resetDescription = "Cancel the clock and clear its tick history.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala",
        region = "subscription-clock-example",
        language = Some("scala")
      )
    )
  )

  val TextUpload = ExampleDescriptor(
    id = "text-upload",
    title = "Summarize-and-discard text upload",
    description =
      "One bounded text file becomes aggregate facts while its consumed bytes are immediately discarded.",
    topics = Vector("uploads", "validation", "resource ownership", "security"),
    aliases = Vector("LiveUploadDef", "consumeCompleted", "ConsumeDecision", "liveFileInput"),
    resetDescription = "Discard active upload state and clear every retained summary.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/TextUploadExample.scala",
        region = "text-upload-example",
        language = Some("scala")
      )
    )
  )

  val ServiceInjection = ExampleDescriptor(
    id = "service-injection",
    title = "Reports service injection",
    description =
      "A layer-backed route constructor-injects a reports service while selection remains connection-local.",
    topics = Vector("services", "ZLayer", "dependency injection", "route environments"),
    aliases = Vector("constructor injection", "ZLayer.fromFunction", "Reports", "refresh"),
    resetDescription = "Restore the first loaded report without querying the service again.",
    sources = Vector(
      ExampleSource(
        label = "Reports service",
        path = "documentation/site/src/scalive/docs/examples/ReportsExample.scala",
        region = "reports-service",
        language = Some("scala")
      ),
      ExampleSource(
        label = "Layer-backed LiveView route",
        path = "documentation/site/src/scalive/docs/examples/ReportsExample.scala",
        region = "reports-liveview",
        language = Some("scala")
      )
    )
  )

  val Lifecycle = ExampleDescriptor(
    id = "lifecycle",
    title = "Lifecycle and connection state",
    description =
      "Connection-aware mounting, keyed flash messages, page-title projection, and after-render effects.",
    topics = Vector("lifecycle", "connection state", "flash", "page title"),
    aliases = Vector("mount", "connected", "disconnected", "after render", "notification"),
    resetDescription = "Clear the notification and restore the default projected title.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/LifecycleExample.scala",
        region = "lifecycle-example",
        language = Some("scala")
      )
    )
  )

  val ProfileForm = ExampleDescriptor(
    id = "profile-form",
    title = "Typed profile form",
    description =
      "Rooted fields decode change and submit events with normalization and path-specific validation.",
    topics = Vector("forms", "validation", "typed input", "accessibility"),
    aliases = Vector("FormRoot", "FormEvent", "profile", "errors", "submit"),
    resetDescription = "Clear every field, validation state, and saved profile.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/ProfileFormExample.scala",
        region = "profile-form-example",
        language = Some("scala")
      )
    )
  )

  val Navigation = ExampleDescriptor(
    id = "navigation",
    title = "Typed documentation navigation",
    description =
      "Named route builders encode real documentation search parameters into checked LiveLocation values.",
    topics = Vector("routing", "navigation", "typed parameters", "search"),
    aliases = Vector(
      "LiveLocation",
      "pushNavigate",
      "replaceNavigate",
      "queryOptional",
      "routes"
    ),
    resetDescription = "Restore the initial LiveView search destination.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/NavigationExample.scala",
        region = "navigation-example",
        language = Some("scala")
      )
    )
  )

  val VotingComponents = ExampleDescriptor(
    id = "voting-components",
    title = "Voting components",
    description =
      "Stable component instances isolate local votes while typed outputs report changes to their parent LiveView.",
    topics = Vector("components", "typed outputs", "local state", "parent communication"),
    aliases = Vector("LiveComponent", "ComponentRef", "sendUpdate", "emit", "WithOutput"),
    resetDescription = "Reset both component models and the parent report state.",
    sources = Vector(
      ExampleSource(
        label = "LiveView",
        path = "documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala",
        region = "voting-components-example",
        language = Some("scala")
      )
    )
  )

  val entries: Vector[ExampleDescriptor] =
    Vector(
      ActivityStream,
      AsyncReport,
      BrowserIntegration,
      Counter,
      Lifecycle,
      Navigation,
      ProfileForm,
      ServiceInjection,
      ShoppingCart,
      SubscriptionClock,
      TextUpload,
      VotingComponents
    )
end ExampleCatalog
