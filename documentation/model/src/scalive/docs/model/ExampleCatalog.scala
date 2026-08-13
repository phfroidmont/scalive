package scalive.docs.model

object ExampleCatalog:
  val ActivityStream = ExampleDescriptor(
    id = "activity-stream",
    title = "Bounded activity stream",
    description =
      "Durable activity history drives an opaque stream handle with stable IDs and a five-row DOM window.",
    topics = Vector("streams", "collections", "DOM patches", "bounded rendering"),
    aliases = Vector("LiveStream", "LiveStreamDef", "insert", "delete", "reset", "keepLast"),
    resetDescription = "Restore the initial durable history and rendered stream window.",
    source = ExampleSource(
      path = "documentation/site/src/scalive/docs/examples/ActivityStreamExample.scala",
      region = "activity-stream-example",
      language = Some("scala")
    )
  )

  val Counter = ExampleDescriptor(
    id = "counter",
    title = "Typed counter",
    description = "A LiveView with a count model and Decrement, Reset, and Increment messages.",
    topics = Vector("typed messages", "server state", "DOM patches"),
    aliases = Vector("increment", "decrement", "reset"),
    resetDescription = "Set the count back to zero.",
    source = ExampleSource(
      path = "documentation/site/src/scalive/docs/examples/CounterExample.scala",
      region = "counter-example",
      language = Some("scala")
    )
  )

  val ShoppingCart = ExampleDescriptor(
    id = "shopping-cart",
    title = "Connection-local shopping cart",
    description = "Typed messages update an immutable cart with derived totals and SKU-keyed rows.",
    topics = Vector("typed messages", "immutable state", "derived state", "keyed rendering"),
    aliases = Vector("cart", "products", "quantity", "subtotal", "clear"),
    resetDescription = "Remove every item and return the cart to its empty state.",
    source = ExampleSource(
      path = "documentation/site/src/scalive/docs/examples/ShoppingCartExample.scala",
      region = "shopping-cart-example",
      language = Some("scala")
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
    source = ExampleSource(
      path = "documentation/site/src/scalive/docs/examples/LifecycleExample.scala",
      region = "lifecycle-example",
      language = Some("scala")
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
    source = ExampleSource(
      path = "documentation/site/src/scalive/docs/examples/ProfileFormExample.scala",
      region = "profile-form-example",
      language = Some("scala")
    )
  )

  val entries: Vector[ExampleDescriptor] =
    Vector(ActivityStream, Counter, Lifecycle, ProfileForm, ShoppingCart)
end ExampleCatalog
