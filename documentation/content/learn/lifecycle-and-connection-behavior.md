{%
title = "Lifecycle, state ownership, and reconnects"
description = "Understand disconnected and connected mounts, state lifetime, commit behavior, cleanup, failures, and reconnects."
order = 6
section = learn
%}

## Treat Each Mount As Independent {#two-independent-mounts}

The [project anatomy](project-anatomy.md#understand-both-mounts) introduced the
disconnected HTTP mount and the separate connected socket mount. Both must build
a valid model from their inputs; the connected mount cannot recover the model
created for the HTTP response.

Match @:apiSymbol(def:scalive.LifecycleContext.connection)`ctx.connection`@:@
when initial state or work depends on the lifecycle. The lifecycle example only
records which kind of mount created the model:

```scala
def mount(ctx: MountContext): Task[Model] =
  val connectedMount = ctx.connection match
    case Connection.Disconnected => false
    case Connection.Connected(_) => true

  ZIO.succeed(Model(connectedMount, currentTitle = DefaultTitle))
```

Start lifecycle-owned work only from a matched
`Connection.Connected(capabilities)` branch. This example starts no clock or
other background task.

## Follow The Handoff From HTTP To Live {#follow-the-connected-mount}

The browser keeps one document while server ownership crosses two independent
lifecycles. Read the following sequences as one handoff: the HTTP request creates
Model A and useful DOM, then `LiveSocket` uses the retained bootstrap data to
join and create Model B.

**Disconnected HTTP render.** The initial request owns Model A only long enough
to produce the complete response:

@:trace(http-get)

When that request ends, the browser retains the rendered DOM, signed session,
and CSRF metadata. Model A and its request-owned resources are gone.

**Connected LiveSocket mount.** The browser presents that bootstrap data while
joining the live endpoint. A successful join starts a fresh lifecycle:

@:trace(live-socket-join)

The join reply contains an initial rendered diff for Model B. The browser
reconciles it with the existing disconnected DOM rather than loading a second
HTML document. This preserves a useful initial page while keeping the two server
models and their resources independent.

## Follow The Lifecycle Timeline {#follow-the-lifecycle-timeline}

The complete lifecycle can now be summarized as:

| Stage | Connection | Model and work |
| --- | --- | --- |
| HTTP mount | Disconnected | Build a temporary model for useful initial HTML |
| HTTP render | Disconnected | Render the response inside layouts; this model ends with the request |
| Socket mount | Connected | Build a new model and start lifecycle-owned work |
| Initial live render | Connected | Render and commit the initial connected tree |
| Message handling | Connected | Produce a proposed model from the last committed model |
| Render and diff | Connected | Render the proposal, compare trees, then commit after success |
| Socket termination | Ending | Interrupt and release lifecycle-owned resources |
| Rejoin | Connected | Start a new lifecycle and mount again from durable inputs |

Async completions and subscription values enter the same typed message flow as
browser events. Lifecycle capabilities such as flash, navigation, async work,
and subscriptions belong to the context for the phase in which they are valid.

## Put State In The Right Lifetime {#put-state-in-the-right-lifetime}

| State | Owner and lifetime | Examples |
| --- | --- | --- |
| Render-derived value | Recomputed from the model | Labels, totals, disabled state |
| Disconnected model | One HTTP render | Initial page data and useful no-JavaScript HTML |
| Connected model | One connected lifecycle | Selection, validation, loaded view data |
| Lifecycle resource | One connected LiveView | Connected resources, subscriptions, async tasks, uploads |
| Injected service | Application-defined lifetime | Repositories, caches, shared domain state |
| Durable storage | Beyond the process or connection | Orders, documents, audit history |
| Browser-local state | Current document or hook | Focus, scroll, third-party widget state |

A module-level mutable value is not visitor state. A LiveView model is isolated
to one lifecycle. An injected service can deliberately outlive that lifecycle,
but it must define its own concurrency, isolation, and durability semantics.

State that must survive reconnect belongs in a service or durable store. Reload
it during mount and keep only the connected lifecycle's rendering and
interaction state in the model.

## Treat Reconnect As A New Lifecycle {#treat-reconnect-as-a-new-lifecycle}

When the transport rejoins, the LiveView mounts again. Rebuild its model from
durable inputs, restart required lifecycle-owned work, and expect the old
lifecycle's connected resources, subscriptions, async tasks, uploads, and nested
LiveViews to be released.

Use this checklist:

- Make `mount` safe to run repeatedly.
- Match `ctx.connection` and run socket-only work only in the
  `Connection.Connected(capabilities)` branch.
- Use [lifecycle-managed APIs](../guides/async-work-and-subscriptions.md#choose-the-resource-by-shape)
  for async work, subscriptions, and non-message acquisition and finalization.
- Make repeated external mount effects idempotent where necessary.
- Use the connected harness to prove that a new server transport reruns
  [`withAdmission`](../guides/testing.md#test-reconnect-admission) and remounts.
- Use a real browser to prove retry delay, retry count, offline behavior, and
  other JavaScript-client reconnect timing.

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

## Test At The Lifecycle Boundary {#test-at-the-lifecycle-boundary}

Use a disconnected test for initial HTTP state and a connected test for typed
server interactions, lifecycle replacement, and server-side reconnect
admission. Use a real browser when the behavior depends on when the client
detects transport loss, schedules a reconnect, JavaScript, or DOM patching. The
[testing guide](../guides/testing.md#choose-the-test-boundary) explains these
boundaries and their available support.
