{%
title = "Lifecycle, state ownership, and reconnects"
description = "Understand disconnected and connected mounts, state lifetime, commit behavior, cleanup, failures, and reconnects."
order = 5
section = learn
%}

## Two Independent Mounts {#two-independent-mounts}

A routed @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ first mounts during an
ordinary HTTP request. `ctx.connection` is `Connection.Disconnected`, and the
resulting HTML should already be useful. When `LiveSocket` joins, Scalive mounts
a new lifecycle with `Connection.Connected(capabilities)`.

These are two model instances, not two phases mutating shared state. Both mounts
must rebuild valid state from their inputs. Match
@:apiSymbol(def:scalive.LifecycleContext.connection)`ctx.connection`@:@ and use
connected-only tools from the matched `capabilities`; do not rely on the
disconnected model to carry values into the connected mount.

The lifecycle example records which mount created its model and starts its clock
only for the connected lifecycle:

@:sourceRegion(documentation/site/src/scalive/docs/examples/LifecycleExample.scala, lifecycle-example)

## Follow The Connected Mount {#follow-the-connected-mount}

The disconnected response leaves the browser with useful DOM, a signed session,
and CSRF metadata, not the temporary model. A `LiveSocket` join uses that
bootstrap data to establish a fresh connected lifecycle:

@:trace(live-socket-join)

The successful join returns an initial rendered diff inside its reply. The
browser reconciles that tree with the existing disconnected DOM rather than
replacing it with a second HTML document.

## Follow The Lifecycle Timeline {#follow-the-lifecycle-timeline}

| Stage | Connection | Model and work |
| --- | --- | --- |
| HTTP mount | Disconnected | Build a temporary model for useful initial HTML |
| HTTP render | Disconnected | Render the response inside layouts; this model ends with the request |
| Socket mount | Connected | Build a new model and start connection-owned work |
| Initial live render | Connected | Render and commit the initial connected tree |
| Message handling | Connected | Produce a proposed model from the last committed model |
| Render and diff | Connected | Render the proposal, compare trees, then commit after success |
| Socket termination | Ending | Interrupt and release connection-owned resources |
| Rejoin | Connected | Start a new lifecycle and mount again from durable inputs |

Async completions and subscription values enter the same typed message flow as
browser events. Lifecycle capabilities such as flash, navigation, async work,
and subscriptions belong to the context for the phase in which they are valid.

## Put State In The Right Lifetime {#put-state-in-the-right-lifetime}

| State | Owner and lifetime | Examples |
| --- | --- | --- |
| Render-derived value | Recomputed from the model | Labels, totals, disabled state |
| Disconnected model | One HTTP render | Initial page data and useful no-JavaScript HTML |
| Connected model | One socket lifecycle | Selection, validation, loaded view data |
| Lifecycle resource | Current connection | Subscriptions, async tasks, uploads |
| Injected service | Application-defined lifetime | Repositories, caches, shared domain state |
| Durable storage | Beyond the process or connection | Orders, documents, audit history |
| Browser-local state | Current document or hook | Focus, scroll, third-party widget state |

A module-level mutable value is not visitor state. A LiveView model is isolated
to one lifecycle. An injected service can deliberately outlive that lifecycle,
but it must define its own concurrency, isolation, and durability semantics.

State that must survive reconnect belongs in a service or durable store. Reload
it during mount and keep only the connection's rendering and interaction state
in the model.

## Treat Reconnect As A New Lifecycle {#treat-reconnect-as-a-new-lifecycle}

When the transport rejoins, the LiveView mounts again. Rebuild its model from
durable inputs, restart required connection-scoped work, and expect the old
socket's subscriptions, async tasks, uploads, and nested LiveViews to be
released.

Use this checklist:

- Make `mount` safe to run repeatedly.
- Match `ctx.connection` and run socket-only work only in the
  `Connection.Connected(capabilities)` branch.
- Use lifecycle-managed APIs for async work and subscriptions.
- Make repeated external mount effects idempotent where necessary.
- Test the reconnect behavior that matters to the application in a browser.

## Understand Failure And Commit Boundaries {#understand-failure-and-commit-boundaries}

A handler returns a proposed model. Scalive renders that model and makes it the
next committed model only after the transition and render path succeed. An
unhandled handler or render failure therefore does not commit the proposal.

This is not a database transaction. Service calls, writes, or other external
effects completed before a later render failure are not rolled back. Recover
expected, user-actionable failures into explicit model state and use normal
transaction boundaries for durable operations.

A failed connected lifecycle may terminate and later be replaced by a fresh
join. Reconnect logic must not depend on recovering the previous model object.
Exact protocol diagnostics and operational recovery belong in the
[troubleshooting guide](../guides/troubleshooting.md).

## Render Connection State Declaratively {#render-connection-state-declaratively}

@:apiSymbol(lazy-val:scalive.connection.visibleWhenConnected)`connection.visibleWhenConnected`@:@
and
@:apiSymbol(lazy-val:scalive.connection.visibleWhenDisconnected)`connection.visibleWhenDisconnected`@:@
render browser bindings that react immediately to socket state. Use them for an
offline label or to disable controls whose events cannot reach the server.

The server does not receive a normal application message merely because the
transport drops. Design recovery around remounting rather than an assumed
disconnect message.

Continue with [Where to go next](where-to-go-next.md) to choose the next topic
for the application you want to build.
