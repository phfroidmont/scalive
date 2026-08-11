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

  val entries: Vector[ExampleDescriptor] = Vector(Counter)
