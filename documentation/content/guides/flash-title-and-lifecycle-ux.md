{%
title = "Flash, title, and lifecycle UX"
description = "Render keyed flash messages, derive browser titles, and communicate connection state."
order = 50
section = guides
%}

## Render Keyed Flash {#render-keyed-flash}

Define a stable `FlashKind`, update it through the phase context, and render only
that key with `flash`:

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

`put` replaces the value for the same key. `clear(Saved)` removes that key, and
`clearAll` removes every flash value owned by the current lifecycle. Prefer a
specific key when independent notices can coexist.

Flash is lifecycle state, not a substitute for model data. Use it for brief
feedback such as a save result or navigation notice. Keep values free of
secrets, and put information that must survive arbitrary reloads in durable
application state instead.

## Derive The Page Title {#derive-the-page-title}

Override `pageTitle` on the routed root LiveView and derive it from the same
model used by `render`:

```scala
override def pageTitle(model: Model): Option[String] =
  Some(model.currentTitle)
```

The root layout's `liveTitle` renders that title during disconnected HTTP
rendering. Connected model changes send title metadata so the client updates
`document.title`. Returning `None` or a blank title uses the root layout's
fallback.

Only the root LiveView owns the document title. A nested LiveView can project a
title-like value for its own interface, but its `pageTitle` result does not
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

Declare static hooks once on the LiveView:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.afterRender { (model, ctx) =>
    ZIO.when(ctx.connected)(recordRenderedTitle(model.currentTitle))
  }
```

An after-render hook observes a render that already succeeded. It cannot return
a replacement model. Use `handleMessage`, `handleParams`, an async completion,
or a subscription message when an effect must produce the next state.

Hooks are installed independently for disconnected and connected lifecycles.
Guard socket-only work with `ctx.connected`, keep effects idempotent where
practical, and avoid starting unmanaged fibers from a hook.

## Exercise The Behavior {#exercise-the-behavior}

Use the [lifecycle example](../examples/lifecycle.md) to put and clear flash,
change the projected title, inspect connected mount state, and reset the nested
LiveView. Its source keeps the model, messages, flash key, title projection, and
after-render hook together.

For the full lifecycle sequence and reconnect model, read
[Lifecycle and connection behavior](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts).
