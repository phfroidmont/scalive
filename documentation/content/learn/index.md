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
- @:apiSymbol(def:scalive.LiveView.view)`view`@:@ constructs a signal-backed
  view graph of typed HTML from a read-only model signal.

Scalive constructs that graph once per disconnected request or connected socket
lifetime, evaluates its signals as the model changes, and sends only snapshot
differences to the Phoenix LiveView JavaScript client. Ordinary application
behavior therefore stays in Scala without requiring a second client-side state
tree.

## Follow One Page From HTTP To DOM {#follow-one-page-from-http-to-dom}

A page reaches the DOM and becomes live in three stages:

1. **Disconnected render.** An ordinary HTTP request reaches a typed Live route.
   Scalive invokes `mount` with `connected = false`, renders the temporary model,
   and returns complete HTML inside the configured layouts.
2. **Connected mount.** The browser loads the JavaScript client and opens
   `LiveSocket` with the server-issued CSRF token. Scalive invokes `mount` again
   to create an independent connected model; it does not continue the
   disconnected model instance.
3. **Live updates.** Browser interactions resolve to typed `Msg` values.
   `handleMessage` proposes a model through `LiveIO`, the view graph
   evaluates affected signals, and Scalive commits the model and sends the
   resulting diff. A rejoin cleans up connection-owned work and repeats the
   connected mount.

The boundary between the first two stages is easy to miss. Follow the initial
HTTP request until its temporary lifecycle ends:

@:trace(http-get)

The browser keeps the returned DOM, but Model A is gone. [Follow the connected
mount](lifecycle-and-connection-behavior.md#follow-the-connected-mount) to see
how `LiveSocket` establishes Model B from durable inputs. That boundary also
explains which side owns each part of the application.

## Know Which Side Owns What {#know-which-side-owns-what}

| Concern | Server | Browser |
| --- | --- | --- |
| Application state | Owns models, transitions, services, and durable data | Keeps the rendered DOM and intentional browser-local state |
| Rendering | Produces view graphs of typed HTML, snapshots, and diffs | Applies patches to the existing DOM |
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
