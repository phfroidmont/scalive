{%
title = "Models, messages, and effects"
description = "Represent LiveView state, typed intent, and effectful transitions with Scala and the small ZIO vocabulary Scalive requires."
order = 3
section = learn
%}

## One Model, One Message Type {#one-model-one-message-type}

A @:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@ owns one model
type and accepts one message type:

```scala
trait LiveView[Msg, Model]:
  def mount(ctx: MountContext): LiveIO[Model]
  def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]
  def view(model: Signal[Model]): HtmlElement[Msg]
```

`Model` contains the state needed to display this LiveView. `view` constructs a
signal-backed view graph from a read-only signal carrying that state. `Msg`
lists the inputs that may change the state. Context values provide capabilities
valid at the current lifecycle stage; they are not additional application state.

The quick start deliberately used an `Int` and two messages. A more
representative counter makes the model extensible and adds another intent:

@:sourceRegion(documentation/site/src/scalive/docs/examples/CounterExample.scala, counter-example)

`Model` is an immutable case class. `Msg` is an enum, so pattern matching checks
the complete input set. Message cases may carry typed domain values: the
shopping cart uses `Add(product)` and `Remove(product)` rather than passing
unstructured event names through application code.

## Read The Minimum ZIO Vocabulary {#read-the-minimum-zio-vocabulary}

Scalive callbacks return effects because mounting or changing state may call a
service, perform I/O, or fail. Learn only requires this small subset:

| Form | Meaning here |
| --- | --- |
| `LiveIO[A]` | An effect that may fail with a `Throwable` or succeed with `A`; currently an alias for `Task[A]` |
| `ZIO.succeed(value)` | Produce an already computed successful value |
| `ZIO.attempt(expression)` | Evaluate synchronous code that may throw |
| `.map(f)` | Transform an effect's successful value |
| `.flatMap(f)` or `for` | Sequence effects while using earlier results |
| `.catchAll(f)` | Recover a failure with another effect |
| `.provide(layer)` | Supply dependencies at the application boundary |

Returning an effect describes work for Scalive to run. Do not create another
ZIO runtime inside a callback. Use lifecycle capabilities such as managed async
work or subscriptions for connection-owned background work instead of starting
an unmanaged fiber.

## Produce Explicit State Transitions {#produce-explicit-state-transitions}

@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ produces the initial model.
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ receives the
last committed model and returns an effect producing a proposed next model.
Scalive evaluates the view graph and commits that model only when the
transition and graph evaluation succeed.

The cart keeps repeated transitions beside `Model`: `add` increments an existing
line or appends one, `remove` decrements or deletes one, and `Clear` returns
`Model.empty`. Item counts, subtotals, and totals are derived from `lines`
instead of being stored as independently mutable values.

@:sourceRegion(documentation/site/src/scalive/docs/examples/ShoppingCartExample.scala, shopping-cart-example)

For service-backed transitions, perform the service effect and return the model
that should be rendered next. If a failure is actionable by the user, recover it
into explicit model state rather than silently discarding it. Effects completed
before a later failure are not automatically rolled back.

## Keep Messages Meaningful {#keep-messages-meaningful}

Prefer messages that describe application intent and carry already typed
values. This keeps decoding at the binding boundary and lets
`handleMessage` work only with valid `Msg` values.

Keep source state in the model and derive presentation values with pure signal
transformations in `view`.
Keep durable records behind a service or database. Keep lifecycle capabilities,
fibers, and subscription handles out of the model.

Next, follow the resulting model through
[Rendering, bindings, and diffs](rendering-and-dom-updates.md#render-from-the-model).
