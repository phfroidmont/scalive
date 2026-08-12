{%
title = "Rendering and DOM updates"
description = "Render typed HTML, bind messages, and preserve collection identity with keys."
order = 4
section = learn
%}

## Render from the model {#render-from-the-model}

`render(model)` describes the HTML tree for the current model and returns an
instance of @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. Treat rendering as a
pure projection: read the model, build the tree, and leave effects and state
transitions in lifecycle methods such as `mount` and `handleMessage`.

The counter renders its count directly. The cart derives labels, disabled
state, quantities, subtotals, and totals from the same model used by its message
handler. Conditional Scala expressions choose between the empty state and the
cart table.

## Bind events to messages {#bind-events-to-messages}

Event bindings from @:apiSymbol(object:scalive.on)`on`@:@ are part of the typed HTML
tree. For example, `on.click(Msg.Increment)` associates a button click with a
value accepted by the view's `Msg` type. A binding can also construct a message
with data, as in `on.click(Msg.Add(product))`.

The result is a direct loop: render a model, receive a typed message, produce the
next model, and render again. Rendering does not update the DOM itself.

## From tree changes to DOM changes {#from-tree-changes-to-dom-changes}

After a connected model update, Scalive renders and compiles the next HTML tree,
compares it with the previous compiled tree, and produces a tree diff. Unchanged
nodes can produce no diff, while changed dynamic text, attributes, or subtrees
produce updates for those positions. The browser applies the resulting patch to
the existing DOM rather than replacing the whole document.

This behavior is why render purity matters: the model should be enough to
recreate the intended tree on every render. Application code describes the
resulting HTML; it does not issue imperative DOM mutations.

## Key collection entries {#key-collection-entries}

Use @:apiSymbol(extension:scalive.splitBy)`splitBy(key)(render)`@:@ when rendering a collection whose
entries have stable domain identity. Its key function associates each rendered
entry with that identity.

The shopping cart keys each row by product SKU:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ShoppingCartExample.scala, shopping-cart-example)

With stable keys, the tree diff can recognize an unchanged entry after a
reorder, distinguish an insertion from an update, and remove entries when the
collection shrinks. Keys must be stable and unique within that rendered
collection. Prefer a domain identifier such as `sku`; use an index only when
position really is the entry's identity.

Review the state side of this loop in
[Models and messages](models-and-messages.md#one-model-one-message-type).
Continue with [Lifecycle and connection behavior](lifecycle-and-connection-behavior.md#two-independent-mounts)
to place renders in the disconnected, connected, and reconnect lifecycles.
