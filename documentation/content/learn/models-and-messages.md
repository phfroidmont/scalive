{%
title = "Models and messages"
description = "Represent LiveView state, events, and transitions with Scala types."
order = 3
section = learn
%}

## One model, one message type {#one-model-one-message-type}

A @:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@ owns a model and accepts one
message type. The model is the state needed to render the current interface. The
message type lists the events that may change that state.

The counter makes both types explicit:

@:sourceRegion(documentation/site/src/scalive/docs/examples/CounterExample.scala, counter-example)

`Model` is an immutable case class. `Msg` is an enum, so its cases form a closed
set that pattern matching can check. Message cases may also carry typed data;
the shopping cart uses `Add(product)` and `Remove(product)` rather than passing
an unstructured event name through application code.

## State transitions {#state-transitions}

@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ produces the initial model.
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ receives the current model
and returns an effect that produces the next model. Each counter branch uses
`copy`, making the transition from old state to new state visible at the call
site.

The same pattern scales to richer state. The cart keeps transition logic beside
`Model`: `add` increments an existing line or appends one, `remove` decrements or
deletes one, and `Clear` returns `Model.empty`. Values such as item count and
total are derived from `lines` instead of being stored as additional mutable
state.

@:sourceRegion(documentation/site/src/scalive/docs/examples/ShoppingCartExample.scala, shopping-cart-example)

## Keep messages meaningful {#keep-messages-meaningful}

Prefer messages that describe application intent and carry already typed
values. This keeps decoding concerns at the binding boundary and lets
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ work only with valid `Msg` values. Keep model transitions small
and explicit; move repeated domain operations onto the model when that makes
the handler easier to read.

Next, see how the model becomes HTML in
[Rendering and DOM updates](rendering-and-dom-updates.md#render-from-the-model).
