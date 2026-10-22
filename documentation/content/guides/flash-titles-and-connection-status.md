{%
title = "Flash messages, page titles, and connection status"
description = "Show flash messages, derive the document title from the model, tell visitors when the page is offline, and keep after-render effects observational."
order = 44
section = guides
group = "Async and lifecycle"
%}

## Before you start {#prerequisites}

You need a `LiveView` with rendered state. The title section also requires a
root layout that renders `liveTitle`. Review [Lifecycle and connection behavior](../learn/lifecycle-and-connection-behavior.md)
before attaching effects to lifecycle hooks.

## Render keyed flash {#render-keyed-flash}

Define a stable @:apiSymbol(class:scalive.FlashKind)`FlashKind`@:@, update it through the phase context, and render only
that key with @:apiSymbol(object:scalive.flash)`flash`@:@. `Saved` is
`FlashKind("saved")`, a kind for text. Put the message from `handleMessage`:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-flash-put)

Then render that key in `view`:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-flash-render)

@:apiSymbol(def:scalive.Flash.put)`put`@:@ replaces the value for the same key.
@:apiSymbol(def:scalive.Flash.clear)`clear`@:@ removes that key, and
@:apiSymbol(def:scalive.Flash.clearAll)`clearAll`@:@ removes every flash value owned by the current lifecycle. Prefer a
specific key when independent notices can coexist.

A notice with more than text, such as a title and whether it can be undone, uses
a kind for its own type. `FlashKind.json` stores the value as JSON, and `put`,
`get`, and `flash` take and return that type:

@:sourceRegion(documentation/fixtures/guides/src/feedback/ArchiveNotice.scala, feedback-flash-typed)

A stored value that no longer decodes, for example one an earlier deployment
encoded differently, is treated as absent.

Putting the same message again while it is shown changes nothing on the page,
so a toast whose hook hides it after a delay would not start a new timer. Use
the form of `flash` that also receives `put`, a number that changes on every
put while the message is shown, and put it in the element's `id`:

@:sourceRegion(documentation/fixtures/guides/src/feedback/SavedToast.scala, feedback-flash-put-number)

The browser keeps an element whose `id` stays the same and only updates it. A
new `id` makes it replace the element, so `AutoDismiss` mounts again.

When the view already knows the text, the message needs no value.
`FlashKind.unit("saved")` declares a kind that holds nothing:
`ctx.flash.put(kind, ())` shows it, and the put number tells puts apart.

Flash is lifecycle state, not a substitute for model data. Use it for brief
feedback such as a save result or navigation notice. Keep values free of
secrets, and put information that must survive arbitrary reloads in durable
application state instead.

## Derive the page title {#derive-the-page-title}

Override @:apiSymbol(def:scalive.LiveView.pageTitle)`pageTitle`@:@ on the routed root
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ and derive it from the same
model exposed as a signal to @:apiSymbol(def:scalive.LiveView.view)`view`@:@:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-page-title)

The root layout's @:apiSymbol(def:scalive.liveTitle)`liveTitle`@:@ renders that title during disconnected HTTP
rendering. Connected model changes send title metadata so the client updates
`document.title`. Returning `None` or a blank title uses the root layout's
fallback.

Only the root @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ owns the document title. A nested
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ can project a
title-like value for its own interface, but its @:apiSymbol(def:scalive.LiveView.pageTitle)`pageTitle`@:@ result does not
replace the containing document's title. The embedded lifecycle example makes
that boundary visible by displaying its projection inside the example.

## Show connection state {#show-connection-state}

Render both states and let declarative connection bindings switch them in the
browser:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-connection)

The disconnected state is visible in static HTML by default. Once the
LiveSocket connects, the client hides it and reveals the connected state; it
reverses those attributes if the transport drops. Include text or another
non-color cue so the state remains understandable without color perception.

### Disable controls while offline {#disable-controls-while-offline}

A control that needs the server should not look usable while the page is
offline. Render it disabled, then let the connection bindings run
@:apiSymbol(val:scalive.JS)`JS`@:@ commands that enable it once the page
connects and disable it again if the connection drops:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-offline-control)

The button stays disabled in the static HTML and until the first connection,
so an early click cannot be lost. The client applies the bindings again after
each reconnect, and attributes changed by `JS` commands survive later server
patches.

Put connection bindings in the root LiveView. When the connection drops, the
client tears down [nested LiveViews](nested-liveviews.md) before it runs the
disconnected commands, so bindings inside a nested LiveView do not react to the
outage.

## Keep after-render effects observational {#keep-after-render-effects-observational}

Declare static hooks once on the @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-after-render)

An after-render hook observes a render that already succeeded and cannot
change the model. When an effect must produce the next state, use
@:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@,
@:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@, an async
completion, or a subscription message instead.
[Lifecycle hooks](lifecycle-hooks.md#after-render-limits) describes the rest of
the after-render contract.

## Exercise the behavior {#exercise-the-behavior}

Use the [lifecycle example](../examples/lifecycle.md) to put and clear flash,
change the projected title, inspect connected mount state, and reset the nested
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@. Its source keeps the model, messages, flash key, title projection, and
after-render hook together.

For the full lifecycle sequence and reconnect model, read
[Lifecycle, state ownership, and reconnects](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts).

## Related tasks {#related-tasks}

- Install the title-owning document shell with [Layouts](layouts.md#install-root-and-ordinary-layouts).
- Deliver delayed UI state through [Asynchronous work](async-work.md#prerequisites).
- Verify title and reconnect behavior with [Testing LiveViews](testing.md#test-in-a-browser).
