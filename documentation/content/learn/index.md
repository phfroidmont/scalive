{%
title = "Learn"
description = "Understand Scalive's server-owned programming model, then build and reason about a complete LiveView application."
order = 0
section = learn
%}

## Start here {#start-here}

Scalive lets you build interactive pages in Scala while keeping application
state on the server. The server renders HTML and handles interactions. The
browser shows that HTML, sends events such as clicks and form changes, and
applies the small updates returned by the server.

**Ready to build something? [Create and run the counter in the quick start.](quick-start.md)**

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

## Know which side owns what {#know-which-side-owns-what}

| Concern | Server | Browser |
| --- | --- | --- |
| Application state | Owns models, transitions, services, and durable data | Keeps the rendered DOM and intentional browser-local state |
| Rendering | Produces typed HTML and updates | Applies patches to the existing DOM |
| Events | Resolves bindings to typed messages and runs handlers | Captures DOM events and sends binding data |
| Effects | Runs ZIO effects, async work, and subscriptions | Runs hooks and commands that require browser APIs |
| Connection | Validates the join and owns connected LiveView lifecycle resources | Opens, monitors, disconnects, and reconnects `LiveSocket` |
| Security | Treats URLs, connect parameters, and event payloads as untrusted | Is not an authority for identity or authorization |

## Take the ordered path {#take-the-ordered-path}

The pages build one application, starting from the quick-start counter. Each
page adds a file or a page to the same project:

1. [Quick start](quick-start.md) creates and runs a complete standalone counter.
2. [Models, messages, and effects](models-and-messages.md) gives the counter a
   model and a Reset message, and introduces the small ZIO vocabulary Scalive
   uses.
3. [Rendering, bindings, and diffs](rendering-and-dom-updates.md) builds a cart
   with conditional content, keyed rows, and row-specific messages.
4. [Your first form](your-first-form.md) builds a sign-up form that validates as
   the visitor types.
5. [Loading data and live updates](loading-data-and-live-updates.md) gives a page
   a service, loads data once the page connects, and refreshes it on a timer.
6. [URL state and navigation](url-state-and-navigation.md) keeps search state in
   a typed URL.
7. [Lifecycle, state ownership, and reconnects](lifecycle-and-connection-behavior.md)
   explains mount phases, resource lifetime, failures, cleanup, and remounting.
8. [Testing your LiveView](testing-your-liveview.md) adds a test module and tests
   the counter, the form, and the service-backed page.
9. [How the pieces fit](how-the-pieces-fit.md) recaps the startup path and the
   boundaries between routes, layouts, LiveViews, services, and the browser.
10. [Where to go next](where-to-go-next.md) maps common application needs to the
    relevant guides, examples, and API reference.

Scalive is alpha software. These pages describe the current recommended API and
may change when a clearer or safer Scala design becomes available.
