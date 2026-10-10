{%
title = "Models, messages, and effects"
description = "Give the counter a model and a Reset message, then represent richer state and typed intent with the small ZIO vocabulary Scalive requires."
order = 2
section = learn
%}

## One model, one message type {#one-model-one-message-type}

A @:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@ owns one model
type and accepts one message type. Its three required methods are:

```scala fragment
def mount(ctx: MountContext): Task[Model]
def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]
def view(model: Signal[Model]): HtmlElement[Msg]
```

`Model` contains the state needed to display this LiveView. `view` reads that
state through a `Signal` whose value follows committed model changes. `Msg` lists
the inputs that may change the state. Context values provide capabilities valid
at the current lifecycle stage; they are not additional application state.

## Give the counter a model {#give-the-counter-a-model}

The quick start used an `Int` as the model and accepted two messages. A case
class leaves room for more fields, and a third message adds a way back to zero.
Replace `app/src/quickstart/CounterLiveView.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CounterLiveView.scala, learn-counter)

`Model` is an immutable case class, so each transition returns a new value with
`copy`. `Msg` is an enum, so the compiler warns when `handleMessage` misses a
case. Keeping both in the companion object gives other files one place to find
them, such as `CounterLiveView.Msg.Reset`.

Run `mill app.run` again. The page now has a **Reset** button that returns the
count to `0`.

## Keep the browser at a decoding boundary {#keep-the-browser-at-a-decoding-boundary}

A binding such as `on.click(Msg.Reset)` attaches a constant, server-constructed
intent. The browser reports that the binding fired; it does not construct the
`Msg.Reset` value. The same applies when the server constructs a message with a
typed domain value it already owns.

Values supplied by the browser remain untrusted even when a message case can
carry them. Decode and validate those values at the binding boundary before
domain logic uses them. For structured input, use
@:apiSymbol(class:scalive.FormDefinition)`FormDefinition`@:@ and carry its
@:apiSymbol(class:scalive.FormEvent)`FormEvent`@:@ in the message, as
[Your first form](your-first-form.md) shows.

## Read the minimum ZIO vocabulary {#read-the-minimum-zio-vocabulary}

Scalive callbacks return effects because mounting or changing state may call a
service, perform I/O, or fail. Learn only requires this small subset:

| Form | Meaning here |
| --- | --- |
| `Task[A]` | An effect that may fail with a `Throwable` or succeed with `A` |
| `ZIO.succeed(value)` | Evaluate a non-throwing expression when the effect runs |
| `ZIO.attempt(expression)` | Evaluate a synchronous expression that may throw |
| `.map(f)` | Transform an effect's successful value |
| `.as(value)` | Run an effect, then succeed with `value` |
| `.flatMap(f)` or `for` | Sequence effects while using earlier results |
| `.catchAll(f)` | Recover a failure with another effect |
| `.provide(layer)` | Supply dependencies at the application boundary |

Returning an effect describes work for Scalive to run. Do not create another
ZIO runtime inside a callback. [Loading data and live updates](loading-data-and-live-updates.md)
shows the lifecycle-managed way to run work in the background.

## Produce explicit state transitions {#produce-explicit-state-transitions}

@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ produces the initial model.
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ receives the
last committed model and returns an effect producing a proposed next model.
Scalive evaluates the view graph and commits that model only when the
transition and graph evaluation succeed.

Richer state works the same way. A small cart keeps its source state and its
transitions together, and its messages carry the product they refer to:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart-model)

`lines` is the source state. `totalInCents` is derived rather than stored as a
second value that could become inconsistent. The message handler is then an
exhaustive description of the allowed transitions:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart-handle-message)

The [next lesson](rendering-and-dom-updates.md#render-the-cart) renders this
cart and adds it to the application.

For service-backed transitions, perform the service effect and return the model
that should be rendered next. If a failure is actionable by the user, recover it
into explicit model state rather than silently discarding it. Effects completed
before a later failure are not automatically rolled back.

## Keep messages meaningful {#keep-messages-meaningful}

Prefer messages that describe application intent and carry already typed
values, such as `Add(product)` rather than an event name and a raw string. This
keeps decoding at the binding boundary and lets `handleMessage` work only with
valid `Msg` values.

Keep source state in the model and derive presentation values with pure signal
transformations in `view`. Keep durable records behind a service or database.
Keep lifecycle capabilities, fibers, and subscription handles out of the model.
