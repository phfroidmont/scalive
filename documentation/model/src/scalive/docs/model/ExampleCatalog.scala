package scalive.docs.model

object ExampleCatalog:
  val Counter = ExampleDescriptor(
    id = "counter",
    title = "Counter",
    description = "Change typed server state and reset it explicitly.",
    topics = Vector("state", "events", "rendering"),
    aliases = Vector("increment", "decrement", "reset"),
    resetDescription = "Set the count back to zero.",
    source = ExampleSource(
      path = "documentation/site/src/scalive/docs/examples/CounterExample.scala",
      region = "counter-example",
      language = Some("scala")
    )
  )

  val entries: Vector[ExampleDescriptor] = Vector(Counter)
