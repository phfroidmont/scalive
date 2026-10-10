{%
title = "Connected lifecycle registration"
description = "A connected mount acquires one registration handle and releases that exact handle when its LiveView lifecycle closes."
order = 14
section = examples
%}

A LiveView whose connected mount acquires a registration and releases it when
the LiveView's lifecycle closes. Ordinary messages do not acquire it again.

## Try It {#try-it}

1. Read **Status** and **Handle**. The status shows "Acquired": the connected
   mount registered this LiveView and stored the handle in its model.
2. Click **Update model** a few times. **Model-only checks** counts up, and the
   handle stays the same.
3. Open this page in another tab. That tab shows a different handle, because it
   is a separate lifecycle.
4. Click **Reset checks**. The checks return to 0 without a new registration.

The release happens when the lifecycle closes, so this page cannot show it. The
example's tests check that closing the LiveView releases the displayed handle.

@:example(connected-resource)

## How It Works {#how-it-works}

- `mount` matches `ctx.connection` and calls
  `connected.resources.acquireRelease(register)(unregister)` only in the
  connected branch, so the disconnected render acquires nothing.
- Scalive runs `unregister` once, with the exact handle, when the lifecycle
  closes, for example when the visitor leaves or the connection drops. A
  reconnect mounts a new lifecycle, which registers again.
- Nested LiveViews own their resources the same way, even when they share one
  WebSocket with their parent. State shared by a logical application session
  belongs in a longer-lived service instead.

## Register At A Route Boundary {#register-at-a-route-boundary}

Keep acquisition in `mount` when the acquired value belongs in the initial model,
as in the preview above. When registration is only a lifecycle side effect, attach
it to the route instead, without forwarding the page's lifecycle methods:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ConnectedResourceExample.scala, connected-resource-route-example)

This separate application accepts an ordinary page and captures the registration
service. The initializer runs only for the connected root, after all admission and
context checks and before the page's connected `mount`; it does not change the
page's context or model. Its test uses the application/route harness to check that
disconnected rendering does not acquire, acquisition precedes connected page
mount, and leaving releases once even when the transport subsequently closes.

`withConnectedResources` is also available on named live-session builders. Session
initializers run before route initializers, in declaration order at each level.
They still own resources per connected root lifecycle, not per named session,
application session, or shared WebSocket. See the
[initializer contract](../guides/connected-resources.md#route-and-session-resources)
for failures, context projection, and remount behavior.

## Related Guides {#related-guides}

- [Connected resources](../guides/connected-resources.md#connected-resources)
  explains acquisition, release, and when to use a route initializer.
