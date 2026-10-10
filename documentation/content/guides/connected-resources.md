{%
title = "Connected resources"
description = "Acquire a registration, observer, or external handle when a page connects and release it exactly once when the page closes."
order = 42
section = guides
group = "Async and lifecycle"
%}

## Before you start {#prerequisites}

Some work needs cleanup when the page closes but never sends the LiveView a
message: a presence registration, an observer, or an external handle. This
guide attaches such a resource to the connected LiveView lifecycle. Use
[asynchronous work](async-work.md#prerequisites) or
[subscriptions](subscriptions.md#prerequisites) instead when the work produces
messages, or when it must be replaced or cancelled while the page stays open.

## Acquire a resource in the connected mount {#connected-resources}

The @:apiSymbol(trait:scalive.ConnectedResources)`ConnectedResources`@:@
capability is exposed through the connected mount branch as
@:apiSymbol(def:scalive.RootMountConnected.resources)`connected.resources`@:@.
Call @:apiSymbol(def:scalive.ConnectedResources.acquireRelease)`acquireRelease`@:@
there and provide a non-failing finalizer. The registration example keeps the
acquired handle in its model:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ConnectedResourceExample.scala, connected-resource-mount)

The disconnected branch acquires nothing: the HTTP render ends as soon as the
response is sent. Every connected mount, including the one after a reconnect,
acquires a new resource.

The capability is intentionally limited to connected mount, either directly or
through the [route and session initializers](#route-and-session-resources)
below. Do not retain it in the model, a service, or a callback for later
acquisition; use a keyed managed API or an explicitly scoped service when
ownership changes after mount.

## Know when the finalizer runs {#know-when-the-finalizer-runs}

The acquisition `Task[A]` may fail normally. Once it succeeds, Scalive registers
the finalizer before returning the value. That finalizer runs exactly once when
the lifecycle closes, including when the remainder of connected mount or the
initial render fails. An acquisition failure does not run the finalizer, and an
attempt made after lifecycle closure fails before acquisition starts.

The finalizer is a `UIO[Unit]`: it has no expected typed failure. If an external
cleanup API returns `Task[Unit]`, make the policy explicit by recovering and
logging, retrying within a bound, or deliberately converting the failure to a
defect. Do not leave that decision implicit at shutdown.

ZIO runs acquisition and finalization uninterruptibly so interruption cannot
strand a partially registered resource. Keep both effects short and bounded;
a blocked acquisition or finalizer also delays lifecycle shutdown.

Cleanup starts after the server observes lifecycle termination. External
presence and locks still need leases or expiry for node failure and undetected
network partitions.

## Know who owns the resource {#know-who-owns-the-resource}

The owner is the connected LiveView lifecycle, not an application session ID.
Two tabs using the same login session acquire independent resources, and closing
one tab does not release the other tab's resource. Root and nested LiveViews on
one WebSocket also have independent lifecycles; closing the socket releases all
of them. Put genuinely session-shared resources in an injected service with
explicit reference counting or leases.

## Initialize at route or session boundaries {#route-and-session-resources}

Use `withConnectedResources` on a route or named live-session builder when an
acquired resource is only a lifecycle side effect, such as a registration or
lease. Keep direct connected-mount acquisition when its result is needed to
construct the page's model, as in the registration example above.

The modifier accepts `(Ctx, ConnectedResources) => Task[Result]` with
`Result <: Unit`. Finish an acquisition with `.unit` when the handle is needed
only by its finalizer. The callback does not enrich typed context or populate a
model, and it does not add a new environment requirement to the builder: capture
services while assembling the application or obtain them from typed context.

This application attaches a registration to a page without changing the page:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ConnectedResourceExample.scala, connected-resource-route-example)

`Ctx` is the context available **where the modifier is installed**. Plain route
and session builders supply `Any`; install after the
[mount aspects](live-sessions-and-mount-aspects.md#derive-typed-context-before-mount)
that produce the typed context you need. Later aspects or admission preserve the
earlier callback's context through projection, rather than passing it the final
accumulated tuple. A later `.context(factory)` call does not retroactively type
a plain route's initializer.

Initializers are lazy: neither constructing the builder nor disconnected
rendering runs them. After all session and route admission/context checks succeed,
Scalive runs session initializers first, then route initializers, in declaration
order within each level, before the page's connected `mount`. Repeated modifiers
append work; they do not replace earlier callbacks. This guarantee is about
mount, not construction: page factories and render compilation can precede the
initializers. Do not rely on registration side effects in a factory or while
building the render graph.

Execution is sequential and fail-fast. A `Task` failure is a mount failure, not an
authentication rejection or a `LiveMountFailure` result. Use mount aspects and
admission for controlled authorization outcomes. All initializers and direct
connected-mount acquisitions use the same existing `ConnectedResources` scope.
If an initializer or the remaining mount/initial render fails, lifecycle closure
rolls back successful acquisitions through their registered finalizers; later
initializers do not run after a failure. Keep acquisition and finalization short
and bounded, just as for direct mount acquisition.

The owner is one **connected root lifecycle**, not the WebSocket connection, the named
live-session group, or a logical application session. A fresh reconnect or route
navigation runs the applicable initializers again for the new root. Patches and
ordinary messages do not rerun them, and nested LiveViews do not inherit these
route/session initializers. Nested views can still acquire their own resources
directly during connected mount.

## Study the complete example {#study-the-complete-examples}

The connected registration example shows the acquired handle in its initial
model, updates ordinary model state without reacquiring, and releases the exact
handle when its LiveView closes:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ConnectedResourceExample.scala, connected-resource-example)

Run the [connected lifecycle registration](../examples/connected-resource.md)
alongside the documentation site's diagnostic views to watch acquisition and
release.

## Related tasks {#related-tasks}

- Run one finite task with [Asynchronous work](async-work.md#prerequisites).
- Emit messages over time with [Subscriptions](subscriptions.md#prerequisites).
- Derive the context an initializer reads with [Live sessions and mount aspects](live-sessions-and-mount-aspects.md#connected-resource-initialization).
- Understand when lifecycles close with [Lifecycle, state ownership, and reconnects](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts).
