{%
title = "JS commands, browser events, and hooks"
description = "Compose client commands and exchange validated typed payloads with focused JavaScript hooks."
order = 40
section = guides
%}

## Choose The Boundary {#choose-the-boundary}

Keep behavior in Scala unless it needs a browser API or must happen immediately
without a server round trip. Scalive provides three increasingly powerful
boundaries:

- compose @:apiSymbol(val:scalive.JS)`JS`@:@ commands for patch-aware DOM effects;
- push a typed @:apiSymbol(opaque-type:scalive.ServerToBrowserEvent)`ServerToBrowserEvent`@:@ for a browser operation requested by the server;
- attach a focused hook with @:apiSymbol(def:scalive.dom.hook)`dom.hook`@:@ when JavaScript owns browser lifecycle work.

Use an ordinary typed LiveView message when the action changes server state.
JavaScript should not become a second application model.

## Compose Client-Only Commands {#compose-client-only-commands}

Commands are immutable values executed in composition order:

```scala
private val revealDetails =
  JS.show(to = panelRef.selector)
    .hide(to = placeholderRef.selector)
    .toggle(to = detailRef.selector)

button(on.click(revealDetails), "Run composed command")
```

This click performs no server transition. Phoenix applies the show, hide, and
toggle operations in the browser while preserving them across compatible DOM
patches. Prefer typed @:apiSymbol(opaque-type:scalive.DomRef)`DomRef`@:@ selectors over handwritten CSS strings.

Use @:apiSymbol(def:scalive.Client.exec)`ctx.client.exec`@:@ when a server callback must execute a client-only command after
work completes. A command containing a typed message push belongs on a rendered
binding, where Scalive can encode that binding correctly.

## Push Typed Server Events {#push-typed-server-events}

Name the direction explicitly and derive JSON codecs for the payload:

```scala
final case class CopyRequest(requestId: String, text: String) derives JsonEncoder

private val CopyRequestEvent =
  ServerToBrowserEvent[CopyRequest]("browser-copy-request")

ctx.client.push(CopyRequestEvent, CopyRequest(requestId, SampleText))
```

@:apiSymbol(def:scalive.Client.push)`push`@:@ is connected-only: disconnected rendering has no browser transport. Encoding can
still fail, so keep payloads small and model failure in the surrounding effect.
Do not put secrets in browser events or traces.

## Return Typed Hook Results {#return-typed-hook-results}

Declare the reverse direction and install it once on the LiveView:

```scala
final case class CopyResult(requestId: String, ok: Boolean) derives JsonDecoder

private val CopyResultEvent =
  BrowserToServerEvent[CopyResult]("browser-copy-result")

override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.empty.onBrowserEvent(CopyResultEvent) { (model, result, _) =>
    ZIO.succeed(applyCopyResult(model, result))
  }
```

@:apiSymbol(def:scalive.LiveHooks.onBrowserEvent)`onBrowserEvent`@:@ decodes a matching root event before invoking the handler. A
malformed matching payload is rejected without changing the model. The Scala
codec does not make JavaScript trustworthy: validate shape, length, permissions,
and browser failures before calling `this.pushEvent`.

## Give Every Hook Stable Identity {#give-every-hook-stable-identity}

Phoenix hooks require an element ID. @:apiSymbol(def:scalive.dom.hook)`dom.hook`@:@ renders the hook name and typed ID together:

```scala
private val hookRef = DomRef(s"$instanceId-hook")

div(
  dom.hook("BrowserInterop", hookRef),
  // Hook-owned content
)
```

Derive IDs from the component or nested LiveView instance. Fixed IDs collide
when a page renders the same example twice. Keep hook selectors scoped to
`this.el` unless the behavior deliberately owns a document-level resource.

## Correlate Retries And Clean Up {#correlate-retries-and-clean-up}

Browser work can finish after a retry, navigation, or disconnect. Include a
bounded request ID in both directions and accept a result only when it matches
the currently pending operation. Do not let an older clipboard or dialog result
overwrite newer state.

When reset must also undo client-only DOM state, compose the typed reset push
with the inverse show and hide commands on the reset button. Reset the server
model and every browser-owned effect through one explicit interaction.

Set a destroyed flag or abort owned asynchronous work in the hook's `destroyed`
callback. Check it again after every awaited browser operation and tolerate
`pushEvent` failure because the LiveSocket may disconnect before completion.

## Exercise The Complete Flow {#exercise-the-complete-flow}

The [browser integration example](../examples/browser-integration.md) combines a
client-only command with correlated clipboard events. Its executable source
derives every DOM ID from the nested instance, resets deterministically, handles
permission failure, and projects operation metadata without exposing copied text.
