{%
title = "Connected lifecycle registration"
description = "A connected mount acquires one registration handle and releases that exact handle when its LiveView lifecycle closes."
order = 11
section = examples
%}

The connected mount acquires a concrete registration and stores its handle in
the initial model. Updating or resetting ordinary model state does not reacquire
the registration. The preview makes acquisition visible; after its LiveView is
removed there is no remaining UI in which to show cleanup. The extracted source
and its lifecycle tests verify that closing the LiveView releases the exact
displayed handle.

Open the page in another tab to see a distinct handle for that independent
lifecycle. Nested LiveViews follow the same ownership rule even when they share
one WebSocket with their parent. State that should be shared by a logical
application session belongs in a longer-lived service instead.

@:example(connected-resource)

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
[initializer contract](../guides/async-work-and-subscriptions.md#route-and-session-resources)
for failures, context projection, and remount behavior.

Related guidance: [acquire other connected resources](../guides/async-work-and-subscriptions.md#connected-resources).
