{%
title = "Learn"
description = "Understand Scalive's server-owned programming model, then build and reason about a complete LiveView application."
order = 0
section = learn
%}

## Start Here {#start-here}

Scalive lets you build interactive pages in Scala while keeping application
state on the server. The server renders HTML and handles interactions. The
browser shows that HTML, sends events such as clicks and form changes, and
applies the small updates returned by the server.

**Ready to build something? [Create and run the counter in Quick start.](quick-start.md)**

The central type is a
@:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@:

- `Model` is the page state.
- `Msg` lists the interactions the page accepts.
- @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ creates the starting state.
- @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ turns a
  message and the current state into the next state.
- @:apiSymbol(def:scalive.LiveView.view)`view`@:@ describes the HTML for that
  state and binds browser events to messages.

The basic loop is: render a page, receive a typed message, update the model, and
patch the changed HTML. Most application behavior stays in Scala; use browser
JavaScript only for features that require browser APIs.

The [lifecycle page](lifecycle-and-connection-behavior.md#two-independent-mounts)
explains how the initial HTTP response, live connection, and reconnects relate.
The [runtime architecture](../project/runtime-architecture.md#runtime-at-a-glance)
is available when you need implementation details.

## Know Which Side Owns What {#know-which-side-owns-what}

| Concern | Server | Browser |
| --- | --- | --- |
| Application state | Owns models, transitions, services, and durable data | Keeps the rendered DOM and intentional browser-local state |
| Rendering | Produces typed HTML and updates | Applies patches to the existing DOM |
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
4. [URL state and navigation](url-state-and-navigation.md) covers typed routes,
   URL-driven state, and live navigation.
5. [Rendering, bindings, and diffs](rendering-and-dom-updates.md) follows one
   update from typed HTML to a browser DOM patch.
6. [Lifecycle, state ownership, and reconnects](lifecycle-and-connection-behavior.md)
   explains mount phases, resource lifetime, failures, cleanup, and remounting.
7. [Where to go next](where-to-go-next.md) maps common application needs to the
   relevant guides, examples, and API reference.

Scalive is alpha software. These pages describe the current recommended API and
may change when a clearer or safer Scala design becomes available.
