{%
title = "Rendering, bindings, and diffs"
description = "Project models into typed HTML, bind messages, and understand how server tree changes become browser DOM patches."
order = 4
section = learn
%}

## Render From The Model {#render-from-the-model}

@:apiSymbol(def:scalive.LiveView.render)`render(model)`@:@ returns an
@:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. Treat it as a pure
projection: read the model, build the intended tree, and leave effects and state
transitions in lifecycle methods.

The counter renders its count directly. The cart derives disabled states,
quantities, subtotals, and totals from the same model used by its handler.
Ordinary Scala expressions choose between the empty state and the cart table.

Pure rendering gives Scalive a deterministic description it can recreate and
compare after every successful model transition. Application code describes the
resulting HTML; it does not issue imperative DOM mutations.

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

1. `render` emits an event binding associated with a typed message.
2. The browser captures the DOM event and sends its binding data.
3. Scalive resolves the binding to the corresponding `Msg`.
4. `handleMessage` receives the committed model and proposes the next model.
5. Scalive renders and compiles the proposed HTML tree.
6. The tree diff compares it with the last committed tree.
7. After a successful render, Scalive commits the new model and tree.
8. The browser receives the diff and patches its existing DOM.

## From Tree Changes To DOM Changes {#from-tree-changes-to-dom-changes}

An unchanged tree may produce no diff. Changed dynamic text, attributes, or
subtrees produce updates for those positions rather than replacing the whole
document. This normally preserves unaffected DOM nodes and their browser state.

The diff encoding and generated binding identifiers are framework details.
Application code should depend on models, messages, and rendered structure, not
on a particular wire payload.

## Preserve Collection Identity {#preserve-collection-identity}

Use @:apiSymbol(extension:scalive.splitBy)`splitBy(key)(render)`@:@ for a
collection whose entries have stable domain identity. The shopping cart keys
each row by product SKU:

```scala
model.lines.splitBy(_.product.sku) { line =>
  tr(
    td(line.product.name),
    td(line.quantity.toString)
  )
}
```

Stable keys let the diff distinguish insertion, removal, movement, and an update
to an existing entry. Keys must be unique within that collection and stable
across renders. Prefer a domain identifier such as `sku`; use an index only when
position really is the entry's identity.

The [HTML and event bindings guide](../guides/html-dsl-and-event-bindings.md)
covers the DSL and input bindings in depth. Continue with
[Lifecycle, state ownership, and reconnects](lifecycle-and-connection-behavior.md#two-independent-mounts)
to place this loop inside a connection lifetime.
