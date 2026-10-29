{%
title = "Rendering, bindings, and diffs"
description = "Render a model with signals, show content conditionally, render keyed lists with row-specific messages, and understand how changes reach the browser."
order = 3
section = learn
%}

## Build a view from the model {#render-from-the-model}

@:apiSymbol(def:scalive.LiveView.view)`view(model)`@:@ receives a
`Signal[Model]` and returns an
@:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. This is the
counter's view from the previous lesson:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CounterLiveView.scala, learn-counter-view)

Scalive calls `view` once per lifecycle to build the page's structure. The
model is a `Signal`, not a `Model`: it describes a value that changes over time,
so `model.count` does not compile. Use `model.map(_.count.toString)` to derive a
signal, and place it where the HTML should follow the model, in a text position
as here or in an attribute such as `disabled := model.map(_.count == 0)`.

Everything else in the view is static. The labels, the buttons, and their
bindings are ordinary Scala values that never change. Leave effects and state
transitions to lifecycle methods rather than running them from `view`.

## Render the cart {#render-the-cart}

The cart from the [previous lesson](models-and-messages.md#produce-explicit-state-transitions)
shows the patterns most views need: content that appears and disappears, a list
of rows, and buttons that act on one row. Create
`app/src/quickstart/CartLiveView.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart)

Add a route for it to `Routes.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Routes.scala, learn-cart-route)

Then register it in `Main.scala`, next to the counter:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Main.scala, learn-cart-registration)

Run `mill app.run` and open `http://localhost:8080/cart`. Add a few products,
remove one, and add enough to pass $30.00. The sections below explain each part
of the view.

## Show content conditionally {#show-content-conditionally}

An `if` cannot inspect a signal, because the view is built before any model
value exists. Instead, map the model to the value that decides what to show and
let Scalive switch the content when that value changes.

**Show a section when a condition holds.** `when` renders its content while a
`Signal[Boolean]` is `true` and nothing while it is `false`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart-when)

**Render an optional value.** `option` renders its content while a
`Signal[Option[A]]` holds a value. The function receives a `Signal[A]` of that
value, so the text keeps following it:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart-option)

**Switch between two layouts.** `choose` renders its first argument while the
condition is `true` and its second while it is `false`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart-choose)

A branch is anything an element accepts, not just one element: text, a
`Vector` of siblings, or an attribute, as in `selected.when(cls := "selected")`.

**Render each case of an enum.** `matchCases` renders one branch per case of an
enum or sealed type. Each branch receives a signal of its own case's value, and
the compiler rejects a missing case:

```scala fragment
delivery.matchCases { is =>
  (
    is.Pending(_ => "Not shipped yet"),
    is.Shipped(shipped => p("Tracking number ", shipped.map(_.tracking))),
    is.Delivered(_ => strong("Delivered"))
  )
}
```

For an open set of values such as strings, `choose` also accepts value-to-branch
pairs, such as `tab.choose("details" -> details, "history" -> history)`; it
renders nothing for a value without a pair. Each of these builds every branch
once and switches between them, which is why the view needs no `if`.

## Preserve collection identity {#preserve-collection-identity}

Use @:apiSymbol(extension:scalive.splitBy)`splitBy(key) { ... }`@:@ on a collection
signal whose entries have stable domain identity. The cart keys each row by
product SKU in the `choose` above:
`model.map(_.lines).splitBy(_.product.sku)((_, line) => lineRow(line))`.

The function receives the key and a `Signal` of that row's value. Stable keys
let the diff distinguish insertion, removal, movement, and an update to an
existing entry. The rendered protocol sends new entries, changed entry
dynamics, and positional references to retained entries instead of resending the
whole keyed region. Keys must be unique within that collection and stable across
updates. Prefer a domain identifier such as `sku`; use `splitByIndex` only when
position really is the entry's identity.

The product buttons above the cart use an ordinary Scala `map` instead. Their
list is a fixed `Vector`, not a signal, so it never changes after the view is
built.

The collection key is server-side render identity, not an HTML `id`. Give each
repeated root a stable HTML `id` as well when preserving or moving that exact
DOM node matters, for example when it owns focus or other browser-local state.
Use a [stream](../guides/streams-and-collection-updates.md#choose-streams-deliberately)
when the collection should instead produce explicit ID-addressed browser
operations.

## Send a row's value with its message {#send-row-specific-messages}

A binding can take a `Signal` of a message instead of a constant. Each row
builds its **Remove one** message from its own line:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/CartLiveView.scala, learn-cart-row)

`on.click(line.map(line => Msg.Remove(line.product)))` sends the message for the
row's current value when the button is clicked. The browser only reports which
binding fired; the server builds the message, so the product is still a typed
value that the browser cannot forge.

## Write helpers that take signals {#write-helpers-that-take-signals}

`lineRow` takes a `Signal[Line]`, not a `Line`, because the row it builds must
keep following the model. Write helpers this way when they return HTML whose
content changes.

`money` takes a plain `Int` because it only formats a value. Call it inside
`map`, as in `line.map(line => money(line.subtotalInCents))`. A helper that
takes plain values is easier to reuse and test, so keep formatting and other
calculations in plain functions and pass signals only to helpers that render.

## Derive display values with pure functions {#derive-display-values}

Each value derived from the model, such as the total or a disabled state, is
one `map` of the model signal. When a value depends on two separate signals,
combine them:

```scala fragment
val fullName = firstName.combineWithFn(lastName)((first, last) => s"$first $last")
```

@:apiSymbol(object:scalive.Signal)`Signal`@:@ documents the tuple forms for more
inputs.

Keep every signal transformation pure: the same input should produce the same
output without changing state, performing I/O, or starting work. Scalive may
skip or reuse a transformation whose input has not changed, so a side effect in
`map` would make the view incorrect.

## Bind events to typed messages {#bind-events-to-typed-messages}

Bindings from @:apiSymbol(object:scalive.on)`on`@:@ are values in the typed HTML
tree. @:apiSymbol(lazy-val:scalive.on.click)`on.click(Msg.Increment)`@:@
associates a button click with a value accepted by the view's `Msg` type, and
`on.click(Msg.Add(product))` carries data. Scala checks that the rendered tree
emits only messages its LiveView accepts.

Browser URLs and event payloads remain untrusted input and must still be decoded
and validated at their boundaries. The
[HTML and event bindings guide](../guides/html-dsl-and-event-bindings.md) lists
the other events, such as key presses, focus, and rate limiting.

## Follow one event to the DOM {#follow-one-event-to-the-dom}

One connected interaction follows this sequence:

1. The rendered tree contains an event binding associated with a typed message.
2. The browser captures the DOM event and sends its binding data.
3. Scalive resolves the binding to the corresponding `Msg`.
4. `handleMessage` receives the committed model and proposes the next model.
5. Scalive evaluates affected signals and compiles the proposed snapshot.
6. The diff compares it with the last committed snapshot.
7. After a successful evaluation, Scalive commits the new model and snapshot.
8. The browser receives the diff and patches its existing DOM.

## Let Scalive patch the DOM {#from-tree-changes-to-dom-changes}

An evaluation whose dynamic slots are unchanged may produce no diff. Changed
dynamic text, attributes, or staged subtrees produce updates for those positions
rather than replacing the whole document. This normally preserves unaffected
DOM nodes and their browser state.

A token-list attribute such as `class` that mixes static and signal values,
as in `cls := "card", cls := selected.map(...)`, keeps its static tokens in the
page and updates only its dynamic ones. Static tokens therefore come first in
the rendered value, as
[composing token-list attributes](../guides/html-dsl-and-event-bindings.md#compose-token-list-attributes)
describes.

The diff encoding and generated binding identifiers are framework details.
Application code should depend on models, messages, and rendered structure, not
on a particular wire payload. See
[Runtime architecture](../project/runtime-architecture.md#runtime-at-a-glance)
for implementation-level signal revisions, scopes, candidate rendering, commit,
and protocol details.

Next, [Your first form](your-first-form.md) adds a page that validates input as
the visitor types.
