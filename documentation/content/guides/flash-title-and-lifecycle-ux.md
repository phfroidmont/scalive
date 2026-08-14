{%
title = "Lifecycle feedback and page state"
description = "Communicate lifecycle state through flash, document titles, connection feedback, and post-render observation."
order = 42
section = guides
group = "Async and lifecycle"
%}

## Prerequisites {#prerequisites}

Start with a LiveView and a root layout that renders `liveTitle`. Review
[Lifecycle and connection behavior](../learn/lifecycle-and-connection-behavior.md)
before attaching effects to lifecycle hooks.

## Render Keyed Flash {#render-keyed-flash}

Define a stable @:apiSymbol(opaque-type:scalive.FlashKind)`FlashKind`@:@, update it through the phase context, and render only
that key with @:apiSymbol(object:scalive.flash)`flash`@:@:

```scala
private val Saved = FlashKind("saved")

case Msg.Save =>
  save *> ctx.flash.put(Saved, "Changes saved.").as(model)

def render(model: Model) =
  div(
    flash(Saved) { message =>
      p(role := "status", aria.live := "polite", message)
    }
  )
```

@:apiSymbol(def:scalive.Flash.put)`put`@:@ replaces the value for the same key.
@:apiSymbol(def:scalive.Flash.clear)`clear`@:@ removes that key, and
@:apiSymbol(def:scalive.Flash.clearAll)`clearAll`@:@ removes every flash value owned by the current lifecycle. Prefer a
specific key when independent notices can coexist.

Flash is lifecycle state, not a substitute for model data. Use it for brief
feedback such as a save result or navigation notice. Keep values free of
secrets, and put information that must survive arbitrary reloads in durable
application state instead.

## Derive The Page Title {#derive-the-page-title}

Override @:apiSymbol(def:scalive.LiveView.pageTitle)`pageTitle`@:@ on the routed root
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ and derive it from the same
model used by @:apiSymbol(def:scalive.LiveView.render)`render`@:@:

```scala
override def pageTitle(model: Model): Option[String] =
  Some(model.currentTitle)
```

The root layout's @:apiSymbol(def:scalive.liveTitle)`liveTitle`@:@ renders that title during disconnected HTTP
rendering. Connected model changes send title metadata so the client updates
`document.title`. Returning `None` or a blank title uses the root layout's
fallback.

Only the root @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ owns the document title. A nested
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ can project a
title-like value for its own interface, but its @:apiSymbol(def:scalive.LiveView.pageTitle)`pageTitle`@:@ result does not
replace the containing document's title. The embedded lifecycle example makes
that boundary visible by displaying its projection inside the example.

## Show Connection State {#show-connection-state}

Render both states and let declarative connection bindings switch them in the
browser:

```scala
div(
  span(connection.visibleWhenConnected, "Connected"),
  span(connection.visibleWhenDisconnected, "Offline")
)
```

The disconnected state is visible in static HTML by default. Once the
LiveSocket connects, the client hides it and reveals the connected state; it
reverses those attributes if the transport drops. Include text or another
non-color cue so the state remains understandable without color perception.

Controls that need the server should not imply that an offline click succeeded.
Scalive's documentation shell freezes embedded examples while disconnected;
applications can use the same connection bindings with JS commands to disable
or explain unavailable interaction.

## Keep After-Render Effects Observational {#keep-after-render-effects-observational}

Declare static hooks once on the @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.afterRender { (model, ctx) =>
    ZIO.when(ctx.connected)(recordRenderedTitle(model.currentTitle))
  }
```

An after-render hook observes a render that already succeeded. It cannot return
a replacement model. Use @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@,
@:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@, an async completion,
or a subscription message when an effect must produce the next state.

Hooks are installed independently for disconnected and connected lifecycles.
Guard socket-only work with @:apiSymbol(def:scalive.LifecycleContext.connected)`ctx.connected`@:@, keep effects idempotent where
practical, and avoid starting unmanaged fibers from a hook.

## Exercise The Behavior {#exercise-the-behavior}

Use the [lifecycle example](../examples/lifecycle.md) to put and clear flash,
change the projected title, inspect connected mount state, and reset the nested
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@. Its source keeps the model, messages, flash key, title projection, and
after-render hook together.

For the full lifecycle sequence and reconnect model, read
[Lifecycle, state ownership, and reconnects](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts).

## Related Tasks {#related-tasks}

- Install the title-owning document shell with [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#install-root-and-ordinary-layouts).
- Deliver delayed UI state through [Asynchronous work and subscriptions](async-work-and-subscriptions.md#prerequisites).
- Verify title and reconnect behavior with [Testing LiveViews](testing.md#test-in-a-browser).
