{%
title = "Browser commands, events, and hooks"
description = "Compose client commands and exchange validated typed payloads with focused JavaScript hooks."
order = 50
section = guides
group = "Browser integration"
%}

## Before you start {#prerequisites}

Start with a `LiveView` that renders an interactive element. You can understand
and compose `JS` command values independently; running browser hooks or exchanging
browser events additionally requires working browser assets, CSRF, and a `LiveSocket`
connection from [Client setup and static assets](static-assets-and-client-setup.md#connect-live-socket).

## Choose the boundary {#choose-the-boundary}

Keep behavior in Scala unless it needs a browser API or must happen immediately
without a server round trip. Scalive provides three increasingly powerful
boundaries:

- compose @:apiSymbol(val:scalive.JS)`JS`@:@ commands for patch-aware DOM effects;
- push a typed @:apiSymbol(opaque-type:scalive.ServerToBrowserEvent)`ServerToBrowserEvent`@:@ for a browser operation requested by the server;
- attach a focused hook with @:apiSymbol(def:scalive.dom.hook)`dom.hook`@:@ when JavaScript owns browser lifecycle work.

Use an ordinary typed LiveView message when the action changes server state.
JavaScript should not become a second application model.

## Compose browser tasks {#compose-client-only-commands}

Commands are immutable values executed in composition order. This confirmation
dialog opens, confirms, and closes without a server round trip, except for the
confirmation itself:

@:sourceRegion(documentation/fixtures/guides/src/browser/ConfirmDialogLiveView.scala, browser-dialog-commands)

Bind each command like any other event value:

@:sourceRegion(documentation/fixtures/guides/src/browser/ConfirmDialogLiveView.scala, browser-dialog-buttons)

This task uses the stable command families without making JavaScript a second
state model: `focus` and `focusFirst` move focus, while `pushFocus`/`popFocus`
restore it; class and attribute commands retain client mutations across
compatible patches; `transition`, `show`, `hide`, and `toggle` provide visual
effects; and `dispatch` notifies browser-owned code. `JS.push(Msg.ConfirmDialog)`
is a typed Scala message push and must remain on a rendered event binding. Prefer
typed @:apiSymbol(opaque-type:scalive.DomRef)`DomRef`@:@ selectors over handwritten CSS strings; consult the `JS` API for each
command's timing and targeting options rather than treating this overview as an
exhaustive reference.

Use @:apiSymbol(def:scalive.Client.exec)`ctx.client.exec`@:@ when a server callback must execute a client-only command after
work completes. It accepts only client-only commands: a command containing a
typed message push does not compile there, because it belongs on a rendered
binding, where Scalive can encode that binding correctly.

## Push typed server events {#push-typed-server-events}

Name the direction explicitly and derive JSON codecs for the payload. The
[browser integration example](../examples/browser-integration.md) asks its hook
to copy text:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-server-event)

Push the event from a message handler:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-push)

@:apiSymbol(def:scalive.Client.push)`push`@:@ is connected-only: disconnected rendering has no browser transport. Encoding can
still fail, so keep payloads small and model failure in the surrounding effect.
Do not put secrets in browser events or traces.

## Handle typed browser results {#return-typed-hook-results}

Declare the reverse direction:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-browser-event)

Then declare which message it becomes in the LiveView's
@:apiSymbol(def:scalive.LiveView.browserEvents)`browserEvents`@:@:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-root-events)

Scalive decodes the payload of each matching event and sends the message to
`handleMessage`, after any event hooks, like a binding's message:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-result)

A malformed payload is logged at debug level and consumed without changing the
model, and so is an event the LiveView doesn't declare. A view that wraps another must
therefore forward the inner view's `browserEvents`, or the inner view never
receives them. A successful decode proves only the payload's shape. Any browser can send any
event, so the server must still validate the decoded values and authorize the
action in `handleMessage`, for example by checking that `requestId` names an
operation this LiveView started. Checks in JavaScript, such as the length limits
in the hook below, only improve usability and handle browser failures; they
protect nothing.

A stateful component declares the events it receives the same way, with its own
messages:

@:sourceRegion(documentation/fixtures/guides/src/browser/CopyButtonComponent.scala, browser-component-events)

Use `this.pushEvent` for an event the LiveView receives and `this.pushEventTo`
with an element inside the component for an event the component receives;
targeting decides whose declarations apply, while `BrowserToServerEvent`
determines how its payload is decoded.

## Implement the browser hook {#implement-the-browser-hook}

For an application with a custom module bundle, put the hook in a focused module
such as `assets/js/browser-interop.js`. This is the hook the documentation
application registers:

@:sourceRegion(documentation/site/assets/js/browser-interop.js, browser-integration-hook)

`handleEvent` receives events pushed by `ctx.client.push`. `pushEvent` sends the
result back to the LiveView, which decodes it into `Msg.CopyFinished` and checks
it in `handleMessage`.

The copying itself is in a second module, `assets/js/browser-copy.js`, with
bounded input and clipboard failure handling. Its explicit failure result lets
Scala clear or report the pending operation instead of waiting forever:

@:sourceRegion(documentation/site/assets/js/browser-copy.js, browser-integration-copy-module)

The hook imports that module when it mounts instead of at the top of its file;
[Load a heavy hook on demand](#load-a-heavy-hook-on-demand) explains why and
what the import requires of the hook.

## Register the hook with LiveSocket {#register-the-hook-with-live-socket}

Register the hook under the same name rendered by `dom.hook`. With the packaged
clients, set it in `window.liveSocketOptions` from a script the root layout
renders, here an ES module that imports the factory:

```js
import { createBrowserInteropHook } from "./browser-interop.js"

window.liveSocketOptions = {
  hooks: { BrowserInterop: createBrowserInteropHook() },
}
```

Render it with `assets.trackedScript("app.js", typ := "module")`. Module scripts
run in document order with the other deferred scripts, so the options are set
before Scalive connects the `LiveSocket`; see
[Client setup and static assets](static-assets-and-client-setup.md#connect-live-socket).
With a [custom bundle](static-assets-and-client-setup.md#build-the-client-bundle),
pass the same `hooks` object in the options of the bundle's own `LiveSocket`.
Do not create a second `LiveSocket` just for hooks.

## Give every hook stable identity {#give-every-hook-stable-identity}

Phoenix hooks require an element ID. Derive it from the LiveView's instance:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-hook-ref)

@:apiSymbol(def:scalive.dom.hook)`dom.hook`@:@ then renders the hook name and
typed ID together on the element the hook owns. `HookName` is
`"BrowserInterop"`, the name registered with `LiveSocket` above:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-hook-element)

Derive IDs from the component or nested LiveView instance. Fixed IDs collide
when a page renders the same example twice. Keep hook selectors scoped to
`this.el` unless the behavior deliberately owns a document-level resource.

## Correlate retries and clean up {#correlate-retries-and-clean-up}

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

## Load a heavy hook on demand {#load-a-heavy-hook-on-demand}

`LiveSocket` looks a hook up when its element mounts, so every hook must be
registered in the script the page loads first. A static `import` of a large
library in a hook's file therefore puts that library on every page, including
the pages that never render the hook. Keep the registered hook small instead and
let it import its heavy module when it mounts. A bundler that splits code emits
the module as its own chunk, and the browser downloads the chunk only on a page
that renders the hook, once per document.

This needs a build that emits ES modules with code splitting, and a root layout
that loads the entry script as a module; see
[Load a deployment manifest](static-assets-and-client-setup.md#load-a-deployment-manifest)
for a worked build. A single-file bundle cannot split.

The copy module above is small; it stands in for an editor, a chart, or an image
cropper. The hook starts the import in `mounted` and keeps the promise:

@:sourceRegion(documentation/site/assets/js/browser-interop.js, browser-integration-hook-load)

Two rules make this safe:

- **Register `handleEvent` listeners before the import settles.** The server can
  push an event as soon as the view has mounted. A listener registered after an
  `await` misses it; a listener registered at once waits for the module inside
  its own body, as this one does.
- **Check for destruction after the import.** A visitor can navigate away while
  the chunk downloads. When the import resolves for a destroyed hook, do
  nothing: its element is gone and its `pushEvent` no longer has a view. A hook
  that sets up a library on `this.el` after the import needs the same check
  first.

The import can also fail: the network dropped, or the page was opened before a
deployment and asks for a chunk the new server no longer has. Log the failure
and show the visitor a state they can see, not a control that silently does
nothing:

@:sourceRegion(documentation/site/assets/js/browser-interop.js, browser-integration-hook-load-failed)

The LiveView renders the notice hidden, inside the hook's element:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BrowserInteropExample.scala, browser-integration-hook-failed)

`this.js().show` reveals it in a way later patches keep, where a style set
directly on the element would be reverted by the next patch.

The notice asks for a reload and does not retry. A later mount of the hook in
the same document may fail the same way without asking the server again, because
a browser may remember a failed import for the life of the document. A full
page load is the dependable recovery.

For the deployment case, visitors usually hear earlier: a page that reconnects
to a server whose tracked assets changed reports `staticChanged`, and a layout
can [offer a reload after a deployment](layouts.md#offer-a-reload-after-a-deployment)
before anyone mounts the hook. Keeping old chunks available across deployments
is a [deployment](deployment.md#know-what-a-deployment-invalidates) concern.

Scalive has no helper for this pattern; it is ordinary JavaScript in the hook.

## Related tasks {#related-tasks}

The [browser integration example](../examples/browser-integration.md) combines a
client-only command with correlated clipboard events. Its executable source
derives every DOM ID from the nested instance, resets deterministically, handles
permission failure, and projects operation metadata without exposing copied text.

Use [Client setup and static assets](static-assets-and-client-setup.md) to build
and serve the bundle. Use [Guard unsaved changes](navigation-guards.md#prerequisites)
for the framework-owned confirmation runtime. Use
[Lifecycle hooks](lifecycle-hooks.md) for server-side interception and
lifecycle-wide policy rather than browser API integration.
