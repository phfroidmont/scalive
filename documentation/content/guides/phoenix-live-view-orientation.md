{%
title = "Phoenix LiveView concepts in Scalive"
description = "Find the Scalive equivalent of the Phoenix LiveView concepts, callbacks, and functions you already use."
order = 1
section = guides
group = "Orientation"
%}

## Before you start {#prerequisites}

This page is for readers who know Phoenix LiveView. It maps the concepts,
callbacks, and functions you already use to their Scalive equivalents. Scalive
itself does not require Elixir or Phoenix experience; if both are new to you,
start with the [quick start](../learn/quick-start.md) instead.

## Start with the programming model {#start-with-the-programming-model}

Scalive keeps Phoenix LiveView's programming model. The server owns page state,
renders HTML, receives browser events, and sends incremental updates over a
persistent connection. The browser runs the same Phoenix LiveView JavaScript
client, keeps the DOM in sync, and runs application JavaScript only where
browser-specific behavior is needed.

Scalive is an independent implementation for Scala 3 and ZIO, not Phoenix
running on the JVM and not a line-for-line port of Phoenix's Elixir API.

Scalive is also alpha software and does not claim complete Phoenix LiveView
parity. Read [Project status](../project/index.md#project-status) before choosing
it for an application.

## Map the core concepts {#map-the-core-concepts}

| Phoenix LiveView concept | Scalive concept | What it means |
| --- | --- | --- |
| LiveView | @:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@ | A server-owned interactive page with a typed model and typed messages. |
| Socket assigns | `Model` and `Signal[Model]` | Immutable application state passed to lifecycle methods and exposed read-only to the signal-backed view graph. |
| `mount` | @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ | Creates the initial model for the HTTP render and again for the connected live process. |
| `handle_event`, `handle_info`, and `handle_async` | @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ | Handles every value of the view's `Msg` type, whether it comes from the browser, a subscription, or finished async work, and returns the next model in `Task`. |
| HEEx template | @:apiSymbol(def:scalive.LiveView.view)`view`@:@, @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@, and Scala HTML builders | Constructs typed HTML from signals and Scala values. |
| `phx-*` event binding | Typed bindings such as @:apiSymbol(lazy-val:scalive.on.click)`on.click(message)`@:@ | Connects browser interactions to values accepted by the LiveView's message type. |
| Diff and DOM patch | Scalive tree diff and the Phoenix LiveView JavaScript client | Sends changed render data to the browser instead of replacing the whole document. |
| LiveComponent | @:apiSymbol(trait:scalive.LiveComponent)`LiveComponent[Props, Msg, Model]`@:@ | Gives a stateful child component typed inputs, messages, and local state. |
| Router live route | @:apiSymbol(val:scalive.live)`live`@:@ with @:apiSymbol(val:scalive.Live.router)`Live.router`@:@ | Connects a URL pattern to a LiveView through typed route declarations. |
| Route parameters | @:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ and route codecs | Decodes path and query data before application code uses it. |
| Root and live layouts | @:apiSymbol(trait:scalive.LiveRootLayout)`LiveRootLayout`@:@ and @:apiSymbol(trait:scalive.LiveLayout)`LiveLayout`@:@ | Separates the complete HTML document from shared markup around live content. |
| `live_session` and `on_mount` | @:apiSymbol(def:scalive.Live.session)`Live.session`@:@, @:apiSymbol(class:scalive.LiveSessionMountAspect)`LiveSessionMountAspect`@:@, and @:apiSymbol(class:scalive.LiveRouteMountAspect)`LiveRouteMountAspect`@:@ | Groups routes; session aspects carry minimal signed claims across the HTTP-to-socket boundary, while claimless route aspects rerun with typed path parameters and URL for every route mount. |
| Commands and hooks | @:apiSymbol(val:scalive.JS)`JS`@:@ and DOM hooks | Describes client effects and integrates JavaScript when a browser API is required. |

The names do not imply identical APIs or complete behavior coverage. They show
where to start when a Phoenix guide or discussion uses a familiar concept.

## Follow one interaction {#follow-one-interaction}

A Scalive interaction has four explicit parts:

1. @:apiSymbol(def:scalive.LiveView.view)`view(model)`@:@ has constructed a view
   graph of @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@ values
   and bound an interaction to a typed message. It runs once for the current
   graph lifetime, not once per update.
2. The Phoenix LiveView JavaScript client sends the interaction over the live
   connection.
3. @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage(model, ctx)`@:@ receives the message and uses
   `Task` to produce
   the next model.
4. Scalive evaluates the affected signals, computes a snapshot diff, and sends
   the update for the browser to patch into the existing DOM.

The model remains on the server. Scalive does not require a second client-side
state tree or a JavaScript component framework for ordinary interactions.

For the current code shape, follow the [quick start](../learn/quick-start.md),
then read [Models, messages, and effects](../learn/models-and-messages.md#one-model-one-message-type)
and [Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#render-from-the-model).

## Understand the two mount phases {#understand-the-two-mount-phases}

The first request is ordinary HTTP. Scalive mounts the
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ and renders a
complete response before a live connection exists. The browser then connects,
and Scalive mounts a new connected lifecycle for events and updates.

Do not treat the disconnected model instance as connected session storage.
Make @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ safe to run in both phases,
match @:apiSymbol(def:scalive.LifecycleContext.connection)`ctx.connection`@:@,
and use capabilities from `Connection.Connected(capabilities)` only after the
live connection exists, which is Scalive's equivalent of
`if connected?(socket)`. [Loading data and live updates](../learn/loading-data-and-live-updates.md#load-data-when-the-page-connects)
starts async work and a subscription this way, and
[the lifecycle page](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)
traces both phases.

Original HTTP cookies and headers exist only at HTTP/document and disconnected
session boundaries. Choose a claim-bearing session aspect or claimless route
aspect using the canonical [mount-boundary comparison](live-sessions-and-mount-aspects.md#choose-the-right-boundary),
then review the [HTTP-to-connected phase boundary](live-sessions-and-mount-aspects.md#treat-mount-phases-independently).
Changing policy still belongs in connected-turn guards and authorization at each
sensitive operation.

## Translate state and effects {#translate-state-and-effects}

Phoenix examples commonly update values stored as socket assigns. In Scalive,
put those values in an immutable `Model`, represent allowed inputs with a `Msg`
enum or sealed hierarchy, and return a new model from
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@.

Effects use ZIO through `Task`; they are not encoded as Phoenix callback
tuples. Functions that take a `socket` in Phoenix become methods on the context
passed to `mount`, `handleMessage`, or `handleParams`, such as `ctx.flash` or
`ctx.nav`. Async work, subscriptions, and pushed events need a connected
lifecycle, so `mount` reaches them by matching `ctx.connection`, as described
in [the two mount phases](#understand-the-two-mount-phases).

| Phoenix LiveView | Scalive | Read |
| --- | --- | --- |
| `handle_params` | @:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@ on a @:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ | [Mount and react to parameters](routes-and-navigation.md#mount-and-react-to-parameters) |
| `handle_info` with `Phoenix.PubSub.subscribe` | A `ZStream` from an application service, started with @:apiSymbol(def:scalive.Subscriptions.start)`subscriptions.start`@:@; each value arrives in `handleMessage` | [Subscriptions](subscriptions.md#start-a-subscription-when-the-page-connects) |
| `assign_async` and `start_async` | @:apiSymbol(def:scalive.Async.start)`async.start`@:@ with an @:apiSymbol(opaque-type:scalive.AsyncKey)`AsyncKey`@:@; keep the result in an @:apiSymbol(enum:scalive.AsyncValue)`AsyncValue`@:@ in the model | [Asynchronous work](async-work.md#start-work-when-the-page-connects) |
| `stream`, `stream_insert`, and `stream_delete` | A @:apiSymbol(type-alias:scalive.LiveStream)`LiveStream`@:@ in the model, created with @:apiSymbol(def:scalive.Streams.create)`streams.create`@:@ and changed with @:apiSymbol(def:scalive.Streams.insert)`streams.insert`@:@ and @:apiSymbol(def:scalive.Streams.delete)`streams.delete`@:@ | [Streams and collection updates](streams-and-collection-updates.md#create-the-stream-during-mount) |
| `allow_upload`, `live_file_input`, and `consume_uploaded_entries` | @:apiSymbol(def:scalive.Uploads.allow)`uploads.allow`@:@, @:apiSymbol(def:scalive.liveFileInput)`liveFileInput`@:@, and @:apiSymbol(def:scalive.Uploads.consumeCompleted)`uploads.consumeCompleted`@:@ | [File uploads](uploads-and-consumption.md#complete-a-bounded-in-memory-upload) |
| `push_event` | @:apiSymbol(def:scalive.Client.push)`client.push`@:@ with a typed @:apiSymbol(opaque-type:scalive.ServerToBrowserEvent)`ServerToBrowserEvent`@:@ | [Push typed server events](browser-integration.md#push-typed-server-events) |
| `put_flash` | @:apiSymbol(def:scalive.Flash.put)`flash.put`@:@ with a @:apiSymbol(opaque-type:scalive.FlashKind)`FlashKind`@:@, rendered with @:apiSymbol(object:scalive.flash)`flash(kind)`@:@ | [Render keyed flash](flash-titles-and-connection-status.md#render-keyed-flash) |
| `push_patch`, `push_navigate`, and `redirect` | @:apiSymbol(def:scalive.Navigation.pushPatch)`nav.pushPatch`@:@, @:apiSymbol(def:scalive.MountNavigation.pushNavigate)`nav.pushNavigate`@:@, and @:apiSymbol(def:scalive.MountNavigation.redirect)`nav.redirect`@:@, with a destination built from a route's `location` | [Choose patch or navigate](routes-and-navigation.md#choose-patch-or-navigate) |
| `Phoenix.LiveViewTest` with `live` and `render_click` | @:apiSymbol(package:scalive.testing)`scalive.testing`@:@ with @:apiSymbol(def:scalive.testing.ConnectedRender.join)`ConnectedRender.join`@:@ and @:apiSymbol(def:scalive.testing.ConnectedView.clickButton)`clickButton`@:@ | [Testing LiveViews](testing.md#cover-connected-behavior) |

Check the [API reference](../api/index.md#packages) for anything this table
does not cover, rather than translating an Elixir call by name.

## Keep browser code at the edge {#keep-browser-code-at-the-edge}

Scalive uses the Phoenix LiveView JavaScript client for the live connection and
DOM patching. Application state and normal event handling stay in Scala. Use a
hook or @:apiSymbol(val:scalive.JS)`JS`@:@ command when behavior depends on a browser API, a third-party
JavaScript widget, focus management, transitions, or another client-only
concern.

This client relationship does not make every Phoenix server feature available
in Scalive. Protocol compatibility, public API coverage, and framework feature
coverage are separate concerns.

## Read Scalive documentation first {#read-scalive-documentation-first}

Phoenix documentation is useful for understanding the broader LiveView model,
but Phoenix code is not Scalive code. Names, lifecycle results, route setup,
templates, effects, and testing APIs may differ intentionally.

Use the [Learn path](../learn/index.md#start-here) for current application code
and the [API reference](../api/index.md#core-types) for the current public
surface. If a Phoenix feature is important to your application, do not infer
support from this concept map; check the current API and raise an issue as
described in [Project status](../project/index.md#report-an-issue).

## Related tasks {#related-tasks}

- Build typed markup and messages in [HTML and event bindings](html-dsl-and-event-bindings.md#prerequisites).
- Connect the browser client in [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Define application URLs in [Routes, parameters, and navigation](routes-and-navigation.md#prerequisites).
- Map Phoenix `live_session` and `on_mount` to [Scalive mount boundaries](live-sessions-and-mount-aspects.md#choose-the-right-boundary).
