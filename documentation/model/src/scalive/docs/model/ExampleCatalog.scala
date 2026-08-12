package scalive.docs.model

object ExampleCatalog:
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

  val entries: Vector[ExampleDescriptor] = Vector(Counter, ShoppingCart)
end ExampleCatalog
