{%
title = "Rendering, bindings, and diffs"
description = "Project models into typed HTML, bind messages, and understand how server tree changes become browser DOM patches."
order = 5
section = learn
%}

## Build A View From The Model {#render-from-the-model}

@:apiSymbol(def:scalive.LiveView.view)`view(model)`@:@ receives a
`Signal[Model]` and returns an
@:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. Write it as a
description of what the page should contain:

```scala
override def view(model: Signal[Int]): HtmlElement[Msg] =
  mainTag(
    button(typ := "button", on.click(Msg.Decrement), "Decrease"),
    outputTag(aria.live := "polite", model.map(_.toString)),
    button(typ := "button", on.click(Msg.Increment), "Increase")
  )
```

Static values such as the button labels are ordinary Scala values. Put a signal
in a text or attribute position when that value should follow the model. Leave
effects and state transitions in lifecycle methods rather than running them
from `view`.

## Derive Display Values With Pure Functions {#derive-display-values}

The counter places its mapped count signal directly in a text position. The cart
derives disabled states, quantities, subtotals, and totals from the same model
signal. Use `.map` for one input and `.zip` when a display value depends on
multiple signals. Operators such as `choose`, `option`, and signal `splitBy`
describe conditional or repeated content.

Keep every signal transformation pure: the same input should produce the same
output without changing state, performing I/O, or starting work. This lets
Scalive evaluate only what the rendered result needs. Application code describes
HTML; it does not issue imperative DOM mutations.

## Bind Events To Typed Messages {#bind-events-to-typed-messages}

Bindings from @:apiSymbol(object:scalive.on)`on`@:@ are values in the typed HTML
tree. For example,
@:apiSymbol(lazy-val:scalive.on.click)`on.click(Msg.Increment)`@:@ associates a
button click with a value accepted by the view's `Msg` type. A binding can also
construct a message carrying data, as in `on.click(Msg.Add(product))`.

This is server-side type safety: Scala verifies that the rendered tree emits
messages accepted by its LiveView. Browser URLs and event payloads remain
untrusted input and must still be decoded and validated at their boundaries.

## Follow One Event To The DOM {#follow-one-event-to-the-dom}

One connected interaction follows this sequence:

1. The rendered tree contains an event binding associated with a typed message.
2. The browser captures the DOM event and sends its binding data.
3. Scalive resolves the binding to the corresponding `Msg`.
4. `handleMessage` receives the committed model and proposes the next model.
5. Scalive evaluates affected signals and compiles the proposed snapshot.
6. The diff compares it with the last committed snapshot.
7. After a successful evaluation, Scalive commits the new model and snapshot.
8. The browser receives the diff and patches its existing DOM.

## Let Scalive Patch The DOM {#from-tree-changes-to-dom-changes}

An evaluation whose dynamic slots are unchanged may produce no diff. Changed
dynamic text, attributes, or staged subtrees produce updates for those positions
rather than replacing the whole document. This normally preserves unaffected
DOM nodes and their browser state.

The diff encoding and generated binding identifiers are framework details.
Application code should depend on models, messages, and rendered structure, not
on a particular wire payload. See
[Runtime architecture](../project/runtime-architecture.md#runtime-at-a-glance)
for implementation-level signal revisions, scopes, candidate rendering, commit,
and protocol details.

## Preserve Collection Identity {#preserve-collection-identity}

Use @:apiSymbol(extension:scalive.splitBy)`splitBy(key) { ... }`@:@ on a collection
signal whose entries have stable domain identity. The shopping cart keys each
retained row by product SKU:

```scala
model.map(_.lines).splitBy(_.product.sku) { (_, line) =>
  tr(
    td(line.map(_.product.name)),
    td(line.map(_.quantity.toString))
  )
}
```

Stable keys let the diff distinguish insertion, removal, movement, and an update
to an existing entry. The rendered protocol sends new entries, changed entry
dynamics, and positional references to retained entries instead of resending the
whole keyed region. Keys must be unique within that collection and stable across
updates. Prefer a domain identifier such as `sku`; use an index only when
position really is the entry's identity.

The collection key is server-side render identity, not an HTML `id`. The browser
merges the rendered diff and then patches the resulting HTML. Give each repeated
root a stable HTML `id` as well when preserving or moving that exact DOM node
matters, for example when it owns focus or other browser-local state. Use a
[stream](../guides/streams-and-collection-updates.md#choose-streams-deliberately)
when the collection should instead produce explicit ID-addressed browser
operations.

The [HTML and event bindings guide](../guides/html-dsl-and-event-bindings.md)
covers the DSL and input bindings in depth.
