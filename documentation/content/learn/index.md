{%
title = "Learn"
description = "Understand Scalive's server-owned programming model, then build and reason about a complete LiveView application."
order = 0
section = learn
%}

## Start Here {#start-here}

Scalive is a Scala 3 implementation of the LiveView programming model. The
server owns application state, renders HTML, receives browser events, and sends
incremental updates over a persistent connection. The browser displays that
HTML, forwards bound events, applies patches, and runs JavaScript only where a
browser API is required.

A @:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@ is the central
abstraction:

- `Model` is immutable server-side state for one LiveView lifecycle.
- `Msg` is the closed set of typed inputs that may change that state.
- @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ creates an initial model.
- @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ uses a ZIO
  effect to produce the next model.
- @:apiSymbol(def:scalive.LiveView.render)`render`@:@ projects the model into
  typed HTML.

Scalive compares consecutive render trees and sends only their differences to
the Phoenix LiveView JavaScript client. Ordinary application behavior therefore
stays in Scala without requiring a second client-side state tree.

## Follow One Page From HTTP To DOM {#follow-one-page-from-http-to-dom}

The complete lifecycle has two starts and a repeated update loop:

1. An ordinary HTTP request reaches a typed Live route.
2. Scalive mounts a disconnected model and renders useful initial HTML inside
   the configured layouts.
3. The browser receives the document, loads the JavaScript client, and returns
   the server-issued CSRF token while opening the live connection.
4. Scalive creates an independent connected lifecycle and mounts a new model.
   It does not continue the disconnected model instance.
5. A browser interaction resolves to a typed `Msg` declared by the rendered
   tree.
6. `handleMessage` produces a proposed next model through `LiveIO`.
7. Scalive renders that model, computes a tree diff, commits the model after a
   successful render, and sends the patch.
8. The browser applies the patch to the existing DOM.
9. If the connection is replaced or rejoins, connection-owned work is cleaned
   up and a new connected lifecycle mounts from durable inputs.

## Know Which Side Owns What {#know-which-side-owns-what}

| Concern | Server | Browser |
| --- | --- | --- |
| Application state | Owns models, transitions, services, and durable data | Keeps the rendered DOM and intentional browser-local state |
| Rendering | Produces typed HTML trees and diffs | Applies patches to the existing DOM |
| Events | Resolves bindings to typed messages and runs handlers | Captures DOM events and sends binding data |
| Effects | Runs ZIO effects, async work, and subscriptions | Runs hooks and commands that require browser APIs |
| Connection | Validates the join and owns connection-scoped resources | Opens, monitors, disconnects, and reconnects `LiveSocket` |
| Security | Treats URLs, connect parameters, and event payloads as untrusted | Is not an authority for identity or authorization |

## Take The Ordered Path {#take-the-ordered-path}

The pages build one mental model in this order:

1. [Quick start](quick-start.md) creates and runs a complete standalone counter.
2. [Project anatomy](project-anatomy.md) assigns startup, routing, layout,
   LiveView, asset, and browser responsibilities.
3. [Models, messages, and effects](models-and-messages.md) explains immutable
   state, typed intent, and the small ZIO vocabulary used by Scalive.
4. [Rendering, bindings, and diffs](rendering-and-dom-updates.md) follows one
   update from typed HTML to a browser DOM patch.
5. [Lifecycle, state ownership, and reconnects](lifecycle-and-connection-behavior.md)
   explains mount phases, resource lifetime, failures, cleanup, and remounting.
6. [Where to go next](where-to-go-next.md) maps common application needs to the
   relevant guides, examples, and API reference.

Scalive is alpha software. These pages describe the current recommended API and
may change when a clearer or safer Scala design becomes available.
