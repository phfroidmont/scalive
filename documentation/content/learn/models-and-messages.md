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
  def mount(ctx: MountContext): Task[Model]
  def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model]
  def view(model: Signal[Model]): HtmlElement[Msg]
```

`Model` contains the state needed to display this LiveView. `view` reads that
state through a `Signal` whose value follows committed model changes. `Msg` lists
the inputs that may change the state. Context values provide capabilities valid
at the current lifecycle stage; they are not additional application state.

The quick start deliberately used an `Int` and two messages. A more
representative counter makes the model extensible and adds another intent:

```scala
final case class Model(count: Int)

enum Msg:
  case Decrement, Increment, Reset
```

`Model` is an immutable case class. `Msg` is an enum, so pattern matching checks
the complete input set. Message cases may carry typed domain values: the
shopping cart uses `Add(product)` and `Remove(product)` rather than passing
unstructured event names through application code.

## Keep The Browser At A Decoding Boundary {#keep-the-browser-at-a-decoding-boundary}

A binding such as `on.click(Msg.Reset)` attaches a constant, server-constructed
intent. The browser reports that the binding fired; it does not construct the
`Msg.Reset` value. The same applies when the server constructs a message with a
typed domain value it already owns.

Values supplied by the browser remain untrusted even when a message case can
carry them. Decode and validate those values at the binding boundary before
domain logic uses them. For structured input, use
@:apiSymbol(class:scalive.FormDefinition)`FormDefinition`@:@ and carry its
@:apiSymbol(class:scalive.FormEvent)`FormEvent`@:@ in the message; the
[typed forms guide](../guides/typed-forms-and-validation.md) covers that path.

## Read The Minimum ZIO Vocabulary {#read-the-minimum-zio-vocabulary}

Scalive callbacks return effects because mounting or changing state may call a
service, perform I/O, or fail. Learn only requires this small subset:

| Form | Meaning here |
| --- | --- |
| `Task[A]` | An effect that may fail with a `Throwable` or succeed with `A` |
| `ZIO.succeed(value)` | Evaluate a non-throwing expression when the effect runs |
| `ZIO.attempt(expression)` | Evaluate a synchronous expression that may throw |
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

For example, a small cart can keep its source state and transitions together:

```scala
enum Product(val sku: String, val name: String):
  case Coffee extends Product("coffee", "Coffee beans")
  case Notebook extends Product("notebook", "Notebook")
  case Sticker extends Product("sticker", "Scalive sticker")

final case class Line(product: Product, quantity: Int)

final case class Model(lines: Vector[Line]):
  def add(product: Product): Model =
    lines.indexWhere(_.product == product) match
      case -1 => copy(lines = lines :+ Line(product, quantity = 1))
      case index =>
        val line = lines(index)
        copy(lines = lines.updated(index, line.copy(quantity = line.quantity + 1)))

  def remove(product: Product): Model =
    copy(lines = lines.flatMap { line =>
      if line.product != product then Some(line)
      else if line.quantity > 1 then Some(line.copy(quantity = line.quantity - 1))
      else None
    })

  def itemCount: Int = lines.map(_.quantity).sum

object Model:
  val empty = Model(Vector.empty)

enum Msg:
  case Add(product: Product)
  case Remove(product: Product)
  case Clear
```

`lines` is the source state. `itemCount` is derived rather than stored as a
second value that could become inconsistent. The message handler is then an
exhaustive description of the allowed transitions:

```scala
def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model] =
  case Msg.Add(product)    => ZIO.succeed(model.add(product))
  case Msg.Remove(product) => ZIO.succeed(model.remove(product))
  case Msg.Clear           => ZIO.succeed(Model.empty)
```

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
