{%
title = "Rendering, bindings, and diffs"
description = "Project models into typed HTML, bind messages, and understand how server tree changes become browser DOM patches."
order = 4
section = learn
%}

## Build A View From The Model {#render-from-the-model}

@:apiSymbol(def:scalive.LiveView.view)`view(model)`@:@ receives a
`Signal[Model]` and returns an
@:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. Scalive invokes it
once to construct the signal-backed view graph for a disconnected request or
connected socket lifetime. Use the model signal directly for dynamic content,
or derive smaller signals with pure `.map` transformations. Leave effects and
state transitions in lifecycle methods.

The counter places its mapped count signal directly in a text position. The cart
derives disabled states, quantities, subtotals, and totals from the same model
signal. Staged operators such as `choose`, `option`, and signal `splitBy`
construct dynamic branches and keyed rows once, then update their content as the
model changes.

## Evaluation And Graph Lifetime {#evaluation-and-graph-lifetime}

Each successful lifecycle turn evaluates one signal transaction at one new
revision. A derived `map` or `zip` is sampled at most once in that transaction.
If Scala equality says its value and dependency revisions are unchanged, Scalive
reuses the committed sample; the final tree diff independently suppresses
unchanged encoded scalar values. All sinks observe the same proposed model, and
the model, signal evaluation, bindings, and rendered snapshot commit together.

Signals belong to the disconnected request, connected socket, component, or
staged row scope that created them. A signal is visible in its own scope and
descendants, but cannot escape from a child or sibling scope. Scalive disposes
the graph when that owner ends. Removed keyed and stream rows are disposed after
the enclosing transaction commits; a failed transaction keeps the previously
committed rows and disposes candidate-only rows.

The finite branches declared by `choose` are constructed once and retained even
while inactive, preserving their binding and component identity when selected
again. They are released with the owning graph. Keyed and stream collections use
explicit identity instead: rows are retained while their key is present and are
released when it is removed. This trades bounded retained memory for stable
branch identity and avoids reconstructing ordinary tree structure on updates.

Pure graph construction gives Scalive a deterministic description it can
evaluate after every successful model transition without rebuilding the whole
HTML tree. Application code describes the resulting HTML; it does not issue
imperative DOM mutations. Signal transformations must also remain pure because
Scalive may skip them when their dependencies have not changed.

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

1. The view graph contains an event binding associated with a typed message.
2. The browser captures the DOM event and sends its binding data.
3. Scalive resolves the binding to the corresponding `Msg`.
4. `handleMessage` receives the committed model and proposes the next model.
5. Scalive evaluates affected signals and compiles the proposed snapshot.
6. The diff compares it with the last committed snapshot.
7. After a successful evaluation, Scalive commits the new model and snapshot.
8. The browser receives the diff and patches its existing DOM.

## From Tree Changes To DOM Changes {#from-tree-changes-to-dom-changes}

An evaluation whose dynamic slots are unchanged may produce no diff. Changed
dynamic text, attributes, or staged subtrees produce updates for those positions
rather than replacing the whole document. This normally preserves unaffected
DOM nodes and their browser state.

The diff encoding and generated binding identifiers are framework details.
Application code should depend on models, messages, and rendered structure, not
on a particular wire payload.

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
covers the DSL and input bindings in depth. Continue with
[Lifecycle, state ownership, and reconnects](lifecycle-and-connection-behavior.md#two-independent-mounts)
to place this loop inside a connection lifetime.
