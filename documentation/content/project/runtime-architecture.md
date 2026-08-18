{%
title = "Runtime architecture"
description = "A contributor's tour of Scalive's routing, socket lifecycle, rendering pipeline, concurrency model, and resource ownership."
order = 2
section = project
%}

## Read This As An Implementation Map {#implementation-map}

This page describes how the current Scalive runtime is assembled. It is for
contributors investigating behavior, debugging a lifecycle, or deciding where a
runtime change belongs. For the application-facing mental model, begin with
[Learn](../learn/index.md#start-here).

@:callout(warning)

`LiveView`, `LiveComponent`, `LiveContext`, `Signal`, and the typed HTML DSL are
public application APIs. Runtime types such as `LiveChannel`, `Socket`,
`RuntimeState`, `ViewGraph`, and `Diff` are `private[scalive]` implementation
details. Their names, fields, queue topology, and wire representation may change
without an application migration path.

@:@

## Read The Runtime In Layers {#runtime-layers}

Scalive translates a typed server-side state machine into the protocol spoken by
the Phoenix LiveView JavaScript client. The main layers are:

| Layer | Responsibility | Primary source area |
| --- | --- | --- |
| Application API | Declares LiveViews, components, models, messages, contexts, signals, and typed HTML | `scalive/src/scalive/LiveView.scala`, `LiveComponent.scala`, `LiveContext.scala`, and `Signal.scala` |
| Routing and security | Matches HTTP routes, composes layouts and mount aspects, issues and validates session and CSRF data, and exposes the socket endpoint | `scalive/src/scalive/routing/` |
| Channel and socket lifecycle | Tracks root and nested LiveView lifecycles, owns connection-scoped state, and coordinates inbound and outbound work | `scalive/src/scalive/routing/LiveChannel.scala`, `Socket.scala`, and `socket/` |
| Rendering | Builds signal-backed view graphs, evaluates snapshots, resolves bindings and children, and computes protocol diffs | `scalive/src/scalive/rendering/` |
| Protocol transport | Decodes and encodes Phoenix-compatible channel messages and writes WebSocket frames | `scalive/src/scalive/protocol/` and `routing/LiveRoutesRuntime.scala` |
| Browser runtime | Captures bound DOM events and applies server diffs to the existing DOM | The external Phoenix LiveView JavaScript client |

The connected path can be reduced to this loop:

```text
Phoenix LiveView client
        │ WebSocket messages
        ▼
LiveRoutesRuntime ──► LiveChannel ──► Socket
                                        │
             typed message ──► LiveView lifecycle
                                        │ proposed model
                                        ▼
                         ViewGraph ──► RenderSnapshot
                                        │
                       previous tree ──► TreeDiff ──► Diff
                                                        │
                                                        └──► client DOM patch
```

This is not an actor or operating-system process hierarchy. The concrete
implementation uses ZIO scopes, fibers, queues, references, streams, and
semaphores inside one JVM process.

## Follow The Two Runtime Entries {#runtime-entries}

A routed LiveView has two independent entries: an ordinary HTTP GET and a later
socket join. They execute similar application callbacks but create different
models and own different resources.

### Disconnected HTTP Render {#disconnected-http-render}

`LiveRoute.toZioRoute` owns the initial request path:

1. The typed route decodes URL parameters and runs disconnected mount aspects.
2. The route constructs the LiveView and a disconnected `LiveContext`.
3. `mount` and initial parameter handling produce a temporary model or redirect.
4. Scalive builds a `ViewGraph` containing the LiveView, live layouts, root
   layout, CSRF metadata, and the signed live-session token.
5. The graph is evaluated once and rendered as a complete HTML document.
6. Request-scoped component graphs and the root graph are disposed before the
   request scope ends.

The browser retains the resulting DOM and bootstrap metadata. The server does
not retain this model or graph for the connected lifecycle.

### Connected Socket Bootstrap {#connected-socket-bootstrap}

`LiveRoutesRuntime` owns the ZIO HTTP WebSocket application. It decodes each
Phoenix channel message and delegates it by topic. A root join acts as a
dispatcher barrier so later topic work waits for the lifecycle that defines the
root socket.

The join path then:

1. Validates CSRF authorization, signed live-session claims, route and live
   session identity, mount-aspect claims, layout compatibility, and tracked
   static assets.
2. Uses `LiveChannel` to replace any previous lifecycle for the same root and
   start a new `Socket` in the connection scope.
3. Uses `SocketBootstrap` to allocate runtime state and create a connected
   `LiveContext`.
4. Runs connected mount and initial parameter handling to produce a fresh model.
5. Builds the persistent root `ViewGraph`, evaluates its first snapshot, commits
   the initial model and rendered state, and computes an initial `Diff`.
6. Starts the socket's client-event and server-event fibers and publishes the
   join reply through its outbox.

The initial diff reconciles the connected tree with the disconnected DOM. It is
not a continuation of the disconnected graph.

## Follow One Connected Turn {#connected-turn}

Browser events enter `SocketInbound`; subscription values, managed async
completions, and component outputs enter `SocketOutbound`. Both paths converge
on the same per-socket lifecycle lock before they can change model or rendered
state.

A normal browser event follows these stages:

1. `LiveRoutesRuntime` decodes the WebSocket frame and `TopicDispatcher`
   preserves ordering for its topic.
2. `LiveChannel` locates the target socket and places the event in its inbox.
3. The client-event fiber acquires the socket's lifecycle lock.
4. Raw event hooks may inspect or halt the untyped browser event.
5. The runtime resolves the generated binding identifier from the last committed
   rendered snapshot and routes component-targeted events when necessary.
6. Runtime type validation confirms that the binding produced the root or
   component message type expected at that location.
7. Typed hooks and `handleMessage` receive the last committed model and produce a
   proposed next model.
8. The view graph evaluates the proposal, child components and nested LiveViews
   participate in the render transaction, and `TreeDiff` compares the candidate
   snapshot with the committed snapshot.
9. After rendering and after-render hooks succeed, the runtime replaces the
   committed model and rendered state.
10. The reply or diff enters the socket outbox, `LiveChannel` merges socket
    outboxes, and `SerialWriter` performs ordered WebSocket writes.

Live patches also acquire the lifecycle lock. They retain the socket, run typed
parameter handling against the new URL, and pass the resulting model through the
same render and commit path. Navigate and redirect commands instead tell the
client to establish the appropriate destination lifecycle.

## Understand The Render Transaction {#render-transaction}

`LiveView.view` is invoked when `ViewGraph` constructs the root graph, not for
every model transition. The graph stores ordinary static structure and staged
dynamic slots created from signals, conditionals, keyed collections, components,
streams, flash content, and nested LiveViews.

Each evaluation has a monotonically increasing signal revision:

- A source signal receives the proposed model and current URL.
- Derived `map` and `zip` values are sampled at most once in that evaluation.
- A cached sample is reused when dependency revisions and Scala equality show
  that its value did not change.
- Candidate graph work is rolled back when graph evaluation fails.
- Successfully removed child scopes are disposed when their transaction commits.

Evaluation produces a `RenderSnapshot.Compiled` tree and a binding registry.
`TreeDiff` compares that tree with the previous compiled tree. Fingerprints avoid
descending into unchanged subtrees; keyed collections, streams, and components
retain identity-specific behavior; repeated static templates can be shared in
the resulting `Diff`.

The runtime state commit is narrower than an application transaction. A failed
handler or render does not replace the committed model and snapshot, but an
external effect completed by application code is not rolled back. A successful
state commit also precedes delivery over the network, so transport failure does
not undo application effects or reconstruct an earlier model.

## Track Ownership And Serialization {#ownership-and-serialization}

One physical WebSocket owns one `LiveChannel`. That channel may own a root socket
and multiple nested sockets. Each socket has its own model, rendered snapshot,
components, subscriptions, async tasks, uploads, flash, stream state, and
lifecycle lock.

| Runtime owner | Work it serializes or owns |
| --- | --- |
| `TopicDispatcher` | Preserves inbound order within a channel topic while allowing independent topics to progress |
| `LiveChannel` | Tracks root and nested sockets, upload ownership, nested topology, joins, leaves, and merged socket output |
| `Socket` and `RuntimeState` | Own one LiveView lifecycle and its connection-scoped mutable runtime state |
| Lifecycle lock | Serializes browser events, server messages, async completions, parameter changes, and model/render commits for one socket |
| Client-event fiber | Drains browser events for one socket |
| Server-event fiber | Merges subscriptions, async completions, and component output into the same lifecycle path |
| ZIO scope | Finalizes channel, socket, fiber, graph, upload, and async resources when ownership ends |
| `SerialWriter` | Ensures concurrent runtime output becomes an ordered sequence of WebSocket writes |

This serialization means two transitions cannot concurrently commit different
models to the same socket. Different topics may make progress independently, and
application services called by those lifecycles must still define their own
concurrency guarantees.

## Distinguish Components From Nested LiveViews {#children}

A stateful `LiveComponent` has its own props, model, hooks, bindings, and child
view graph, but it participates in its owner's render transaction. Its runtime
identity combines component class and application-provided ID; the protocol CID
is internal routing state. Component messages are serialized by the owning
socket's lifecycle lock.

A nested LiveView is a separate socket lifecycle inside the same `LiveChannel`.
It owns an independent model, component tree, queues, and lifecycle lock. The
channel coordinates parent-child topology, replacement, sticky navigation, and
crash propagation. Treating a nested LiveView as a separate lifecycle rather
than a component is therefore important when changing cleanup or concurrency
behavior.

## Follow Failure And Cleanup {#failure-and-cleanup}

Normal socket shutdown closes queues, interrupts managed async work, shuts down
uploads, stops event fibers, and disposes root and component graphs. Nested
lifecycles are removed through the channel topology that owns them.

An unhandled lifecycle failure enters `SocketCrashRuntime`. The first crash:

1. Publishes a Phoenix-compatible error payload.
2. Clears subscriptions and interrupts managed work.
3. Shuts down event queues and upload resources.
4. Disposes view graphs under the lifecycle lock.
5. Runs its configured crash callback. A linked nested lifecycle uses this to
   propagate the crash to its parent when that behavior is enabled.

A later root or non-sticky nested join creates a new socket, model, and graph. A
sticky nested LiveView is the exception: while its existing socket remains owned
by the channel, a rejoin can return that socket's current rendered tree instead
of mounting again. State that must survive ordinary remounts must therefore live
in an application service or external store rather than in `RuntimeState` or a
LiveView model.

## Change The Narrowest Subsystem {#source-map}

Use these entry points when locating a runtime change:

| Concern | Start with |
| --- | --- |
| Public lifecycle or capability shape | `LiveView.scala`, `LiveComponent.scala`, and `LiveContext.scala` |
| Route construction, layouts, sessions, or mount aspects | `routing/LiveRouteDsl.scala`, `LiveRoute.scala`, and `LiveLayouts.scala` |
| Join validation or protocol dispatch | `routing/LiveRoutesRuntime.scala` and `protocol/WebSocketMessage.scala` |
| Root and nested socket ownership | `routing/LiveChannel.scala` and `Socket.scala` |
| Mount and initial connected rendering | `socket/SocketBootstrap.scala` |
| Browser events, patches, and navigation | `socket/SocketInbound.scala` |
| Subscriptions, async completions, and component output | `socket/SocketOutbound.scala` |
| Model/render commit behavior | `socket/SocketModelRuntime.scala` and `socket/SocketRuntimeState.scala` |
| Component lifecycle and rendering | `socket/SocketComponentRuntime.scala` and `socket/SocketComponentState.scala` |
| Signal staging and evaluation | `Signal.scala` and `rendering/ViewGraph.scala` |
| Snapshot compilation and diff encoding | `rendering/RenderSnapshot.scala`, `TreeDiff.scala`, and `Diff.scala` |
| Cleanup and terminal failures | `socket/SocketCrashRuntime.scala` and `SocketOutbound.scala` |

Runtime changes should be verified at the narrowest level first, then through a
connected vertical slice. `SignalSpec`, `TreeDiffSpec`, `SocketSpec`,
`ViewGraphSocketSpec`, and the component and routing lifecycle specifications
cover the main internal boundaries. Changes affecting browser-observable
behavior should also pass the upstream end-to-end suite with
`./scripts/e2e-run-upstream.sh`.

For supported behavior rather than implementation structure, return to the
[Learn lifecycle](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)
or consult the [compatibility matrix](compatibility.md#compatibility-target).
