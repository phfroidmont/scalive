{%
title = "Phoenix LiveView orientation"
description = "Map Phoenix LiveView concepts to Scalive's typed Scala API without requiring Elixir experience."
order = 1
section = guides
%}

## Start With The Programming Model {#start-with-the-programming-model}

Phoenix LiveView is a server-side programming model for interactive web pages.
The server owns page state, renders HTML, receives browser events, and sends
incremental updates over a persistent connection. The browser keeps the DOM in
sync and runs JavaScript only where browser-specific behavior is needed.

Scalive brings that model to Scala 3 and ZIO. It is an independent
implementation, not Phoenix running on the JVM and not a line-for-line port of
Phoenix's Elixir API. You do not need to know Elixir to use Scalive.

Scalive is also alpha software and does not claim complete Phoenix LiveView
parity. Read [Project status](../project/index.md#project-status) before choosing
it for an application.

## Map The Core Concepts {#map-the-core-concepts}

| Phoenix LiveView concept | Scalive concept | What it means |
| --- | --- | --- |
| LiveView | @:apiSymbol(trait:scalive.LiveView)`LiveView[Msg, Model]`@:@ | A server-owned interactive page with a typed model and typed messages. |
| Socket assigns | `Model` | Immutable application state passed explicitly to lifecycle and rendering methods. |
| `mount` | @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ | Creates the initial model for the HTTP render and again for the connected live process. |
| `handle_event` and other callbacks | @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ | Handles values from the view's `Msg` type and returns the next model in @:apiSymbol(type-alias:scalive.LiveIO)`LiveIO`@:@. |
| HEEx template | @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@ and Scala HTML builders | Produces typed HTML directly from Scala values. |
| `phx-*` event binding | Typed bindings such as @:apiSymbol(lazy-val:scalive.on.click)`on.click(message)`@:@ | Connects browser interactions to values accepted by the LiveView's message type. |
| Diff and DOM patch | Scalive tree diff and the Phoenix LiveView JavaScript client | Sends changed render data to the browser instead of replacing the whole document. |
| LiveComponent | @:apiSymbol(trait:scalive.LiveComponent)`LiveComponent[Props, Msg, Model]`@:@ | Gives a stateful child component typed inputs, messages, and local state. |
| Router live route | @:apiSymbol(val:scalive.live)`live`@:@ with @:apiSymbol(val:scalive.Live.router)`Live.router`@:@ | Connects a URL pattern to a LiveView through typed route declarations. |
| Route parameters | @:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ and route codecs | Decodes path and query data before application code uses it. |
| Root and live layouts | @:apiSymbol(trait:scalive.LiveRootLayout)`LiveRootLayout`@:@ and @:apiSymbol(trait:scalive.LiveLayout)`LiveLayout`@:@ | Separates the complete HTML document from shared markup around live content. |
| `live_session` and `on_mount` | @:apiSymbol(def:scalive.Live.session)`Live.session`@:@ and @:apiSymbol(class:scalive.LiveMountAspect)`LiveMountAspect`@:@ | Groups routes and applies typed setup or authorization at mount boundaries. |
| Commands and hooks | @:apiSymbol(val:scalive.JS)`JS`@:@ and DOM hooks | Describes client effects and integrates JavaScript when a browser API is required. |

The names do not imply identical APIs or complete behavior coverage. They show
where to start when a Phoenix guide or discussion uses a familiar concept.

## Follow One Interaction {#follow-one-interaction}

A Scalive interaction has four explicit parts:

1. @:apiSymbol(def:scalive.LiveView.render)`render(model)`@:@ builds an
   @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@ and binds an interaction to a
   typed message.
2. The Phoenix LiveView JavaScript client sends the interaction over the live
   connection.
3. @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage(model, ctx)`@:@ receives the message and uses
   @:apiSymbol(type-alias:scalive.LiveIO)`LiveIO`@:@ to produce
   the next model.
4. Scalive renders again, computes a tree diff, and sends the update for the
   browser to patch into the existing DOM.

The model remains on the server. Scalive does not require a second client-side
state tree or a JavaScript component framework for ordinary interactions.

For the current code shape, follow the [Quick start](../learn/quick-start.md),
then read [Models and messages](../learn/models-and-messages.md#one-model-one-message-type)
and [Rendering and DOM updates](../learn/rendering-and-dom-updates.md#render-from-the-model).

## Understand The Two Mount Phases {#understand-the-two-mount-phases}

The first request is ordinary HTTP. Scalive mounts the
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ and renders a
complete response before a live connection exists. The browser then connects,
and Scalive mounts a new connected lifecycle for events and updates.

Do not treat the disconnected model instance as connected session storage.
Make @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ safe to run in both phases, and use
@:apiSymbol(def:scalive.LifecycleContext.connected)`ctx.connected`@:@ when work should
run only after the live connection exists. [Project anatomy](../learn/project-anatomy.md#understand-both-mounts)
traces both phases through a complete application.

## Translate State And Effects {#translate-state-and-effects}

Phoenix examples commonly update values stored as socket assigns. In Scalive,
put those values in an immutable `Model`, represent allowed inputs with a `Msg`
enum or sealed hierarchy, and return a new model from
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@.

Effects use ZIO through @:apiSymbol(type-alias:scalive.LiveIO)`LiveIO`@:@; they are not encoded as Phoenix callback
tuples. Subscriptions, async work, navigation, flash, uploads, and component
updates use typed context capabilities or dedicated Scalive values. Consult the
[API reference](../api/index.md#packages) for the API that exists in the current
revision rather than translating an Elixir call by name.

## Keep Browser Code At The Edge {#keep-browser-code-at-the-edge}

Scalive uses the Phoenix LiveView JavaScript client for the live connection and
DOM patching. Application state and normal event handling stay in Scala. Use a
hook or @:apiSymbol(val:scalive.JS)`JS`@:@ command when behavior depends on a browser API, a third-party
JavaScript widget, focus management, transitions, or another client-only
concern.

This client relationship does not make every Phoenix server feature available
in Scalive. Protocol compatibility, public API coverage, and framework feature
coverage are separate concerns.

## Read Scalive Documentation First {#read-scalive-documentation-first}

Phoenix documentation is useful for understanding the broader LiveView model,
but Phoenix code is not Scalive code. Names, lifecycle results, route setup,
templates, effects, and testing APIs may differ intentionally.

Use the [Learn path](../learn/index.md#start-here) for current application code
and the [API reference](../api/index.md#core-abstraction) for the current public
surface. If a Phoenix feature is important to your application, do not infer
support from this concept map; check the current API and raise an issue as
described in [Project status](../project/index.md#report-an-issue).
