{%
title = "Runtime architecture"
description = "A contributor's tour of Scalive's modules, lifecycle kernel, rendering pipeline, transport, and resource ownership."
order = 2
section = project
%}

## Read This As An Implementation Map {#implementation-map}

This page describes the Milestone-11 runtime. It is for contributors locating a
behavior, following a lifecycle turn, or deciding which module should own a
change. For the application-facing model, begin with
[Learn](../learn/index.md#start-here).

The runtime is deliberately split at protocol-neutral boundaries. Application
code declares a `LiveApplication`; ZIO HTTP admits requests and Phoenix frames;
the connection layer owns connected lifecycles; the kernel commits typed state
transitions; rendering produces semantic trees and deltas; and the Phoenix
module alone projects those values onto the client protocol.

## Read The Module Graph {#module-graph}

The Mill modules and their direct dependencies are:

```text
scalive.render             ─► api
scalive.runtime.contracts  ─► api
scalive.runtime.resources  ─► api, contracts
scalive.runtime.topology   ─► api, contracts
scalive.runtime.kernel     ─► api, render, contracts, resources
scalive.runtime.connection ─► contracts, kernel, topology, resources
scalive.protocol.phoenix   ─► contracts, render
scalive.transport.zio-http ─► api, connection, protocol.phoenix
scalive facade             ─► api, transport.zio-http
scalive.testing            ─► facade
```

Here `A ─► B` means that module A directly depends on module B.

`scalive` is the aggregate facade artifact: it depends on `scalive.api` and
`scalive.transport.zio-http`, so applications get the public API and the default
runtime assembly without depending on internal modules individually.

| Module | Responsibility | Source root |
| --- | --- | --- |
| `scalive.api` | Public lifecycle, context capabilities, routing, HTML, forms, streams, and uploads | `scalive/api/src/scalive/` |
| `scalive.render` | Protocol-neutral retained render programs, semantic trees, bindings, and exact deltas | `scalive/render/src/scalive/render/` |
| `scalive.runtime.contracts` | Runtime identities, cleanup, nested-topology contracts, and ordered outbound reservations | `scalive/runtime/contracts/src/scalive/runtime/contracts/` |
| `scalive.runtime.resources` | Candidate-prepared resource indexes, cleanup-safe activation, and upload registry/mutation state | `scalive/runtime/resources/src/scalive/runtime/resources/` |
| `scalive.runtime.topology` | Immutable nested-registration forests and transactional topology plans | `scalive/runtime/topology/src/scalive/runtime/topology/` |
| `scalive.runtime.kernel` | The single-owner, protocol-neutral lifecycle state machine | `scalive/runtime/kernel/src/scalive/runtime/kernel/` |
| `scalive.runtime.connection` | Root and nested lifecycle supervision, contexts, components, ingress, uploads, and serialized output | `scalive/runtime/connection/src/scalive/runtime/connection/` |
| `scalive.protocol.phoenix` | Phoenix envelope decoding, output shapes, upload frames, and rendered-tree projection | `scalive/protocol/phoenix/src/scalive/protocol/phoenix/` |
| `scalive.transport.zio-http` | HTTP rendering, security tokens, websocket admission, dispatch, and ZIO HTTP routes | `scalive/transport/zio-http/src/scalive/` |
| `scalive.testing` | Disconnected semantic pages and connected, production-admitted test handles | `scalive/testing/src/scalive/testing/` |

Module dependencies are declared in `build.mill`. Most runtime implementation
types are `private[scalive]`; application code should depend on the public
types in `scalive.api`, not on the internal state machines described below.

## Enter Through `LiveApplication` And `ZioHttp.routes` {#runtime-entries}

`Live.router(...)` produces a `LiveApplication[R]`: a declarative route catalog,
socket path, optional live layout, and root layout. `ZioHttp.routes(application,
config)` synchronously validates duplicate session names and rendered paths,
compiles the heterogeneous typed routes behind one audited boundary, and returns
ordinary GET routes plus `<socketPath>/websocket`.

A routed LiveView has two independent executions. The HTTP request renders HTML
and signed bootstrap claims in a request-owned scope. The admitted WebSocket join
creates a fresh model and a separate resource scope.

@:diagram(runtime-ownership)

### Disconnected HTTP Render {#disconnected-http-render}

For a GET, the selected `CompiledRoute` decodes typed path and query parameters
and runs the session and route mount aspects. It constructs a `RootLifecycle`
and `DisconnectedRootTurn` whose contexts expose `Connection.Disconnected`,
then runs mount, initial parameter handling, hooks, flash, and redirect handling.

The retained `RenderProgram` produces an evaluated tree, resolving disconnected
components in request scope. ZIO HTTP renders that tree through the live and root
layouts and adds CSRF plus signed session and static bootstrap data. After the
response, all request-owned render, component, and prepared-resource scopes
close. Neither the model nor committed render continues into the WebSocket
lifecycle.

### ZIO HTTP Admission And Connected Bootstrap {#connected-bootstrap}

The WebSocket route owns one physical Phoenix writer, one
`ConnectionSupervisor`, and its connection-local registries. `PhoenixProtocol`
decodes text envelopes, while bounded workers process upload frames. A join gate
serializes root admission before `ZioHttpAdmission` validates the request.

Admission verifies the topic/root identity, signed session and static claims,
canonical URL, route and live-session identity, root-layout and mount-claim
markers, CSRF cookie/token pair, and whether the requested route may replace the
current root. Nested joins additionally reserve an exact active topology
registration. Rejected joins receive Phoenix-compatible stale or unauthorized
replies without constructing application lifecycle state.

Only after admission does the transport rebuild the connected `RootLifecycle`
and ask the supervisor to start a `RootConnection`. Connected mount and initial
parameter handling create a fresh model. The first connected `RenderDelta`
reconciles that model with the disconnected browser DOM; it does not resume the
HTTP render transaction.

## Follow One Connected Command {#connected-turn}

A `RootConnection` turns browser events, typed messages, patches, async and
subscription completions, component work, and upload mutations into
epoch-qualified `SessionCommand`s. Its bounded ingress fiber submits those
commands to the lifecycle's `SessionKernel`, which processes one transition at a
time.

@:diagram(runtime-connected-turn)

The bounded ingress and kernel mailboxes serialize lifecycle transitions. A
reserved mailbox path prevents a browser patch acknowledgement from deadlocking
behind ordinary work. External producers receive explicit saturation or closed
errors; runtime-owned producers backpressure where dropping would violate
lifecycle semantics.

## Understand The Render Transaction {#render-transaction}

`RenderProgram.compile` invokes `LiveView.view` once for a lifecycle and retains
the static template plus its signal scopes. Each turn evaluates that program
against the proposed immutable model and the previous `CommittedRender`.
Signal revisions allow unchanged nodes to be reused without hashes being treated
as equality proof.

Evaluation produces a `RenderCandidate` containing:

- an immutable `EvaluatedTree`;
- the binding table and sampled signal state;
- component, nested-lifecycle, and stream requirements; and
- a `CandidateScope` for resources prepared by that evaluation.

`SessionKernel.runTurn` evaluates and stabilizes the root and component graph,
validates stream and upload requirements, and prepares resources and nested
topology. It then reserves an ordered publication slot, runs after-render work,
validates continuations, and computes the `RenderDelta` with `TreeDiffer`.

All of that work remains provisional. A failure closes candidate scopes and
rollback plans while revision N remains active. The interruption-masked commit
tail replaces framework-owned state, marks retired owners stale, activates
prepared resources and topology, and fills the reserved output slot. Retired owners
are finalized after replacement.

The `RootConnection` outbound fiber drains that reservation as
`ConnectionOutput`. `PhoenixRenderedEncoder` projects the protocol-neutral delta
into Phoenix statics, dynamics, component CIDs, stream operations, events,
replies, and navigation payloads before the physical writer sends it. Encoding
and network I/O occur after N+1 is active; a write failure closes the connection
but does not restore revision N.

This boundary provides framework-state atomicity, not database transactionality.
Effects already performed by application handlers are not rolled back when
later rendering or publication fails.

## Track Components And Nested Lifecycles {#children}

A stateful component has local props, model, hooks, bindings, render state, and
component-scoped resources, but it does not own a network mailbox or independent
commit. `ComponentRuntime` prepares component candidates while the parent
`SessionKernel` stabilizes and commits the complete root and component forest.
Phoenix CIDs are projection identifiers, not runtime ownership identities.

A nested LiveView is a separate connected lifecycle on the same physical
WebSocket. It owns a `RootConnection`, kernel, ingress, committed revision, and
resource owners. Transactional topology registration protects joins with exact
parent and generation identities. Parent cleanup retires non-retained
descendants, while sticky descendants may detach and later reattach.

## Track Bounds And Scoped Ownership {#ownership-and-serialization}

The runtime does not use unbounded work queues. `ConnectionConfig` validates
positive capacities for root ingress, outbound reservations, kernel mailboxes,
continuations, writers, and upload chunks. The ZIO HTTP assembly currently
supplies fixed conservative capacities and caps websocket/upload frame sizes.
`OutboundReservations` reserves queue position before work begins, preserving
publication order even when turns finish asynchronously or a reservation is
released.

Ownership follows scopes rather than global registries:

| Owner | Principal resources |
| --- | --- |
| WebSocket scope | `ConnectionSupervisor`, physical Phoenix writer, joined topics, upload joins |
| Supervisor child scope | One root or nested `RootConnection` and linked descendants |
| Root connection scope | Kernel, ingress/outbound fibers, command promises, uploads, render program |
| Committed render scope | Active component and stream-row scopes plus prepared resources for one revision |
| Candidate scope | Isolated resources awaiting commit; closed on rollback |
| Component owner | Component async tasks, subscriptions, streams, uploads, and child render scopes |

Closing a scope shuts down queues and reservations, interrupts owned fibers,
retires uploads and managed resources, closes committed render programs, and
revokes topology registrations. `RuntimeCleanup` composes cleanup without
letting one failing finalizer skip the others.

## Change And Test The Narrowest Subsystem {#source-map}

| Concern | Start with | Focused suites |
| --- | --- | --- |
| Public lifecycle and connection capabilities | `scalive/api/src/scalive/lifecycle/` | `scalive/api/test/src/scaliveapi/ConnectionCapabilitiesSpec.scala`, `ComponentApiSpec.scala` |
| Routes, sessions, layouts, application assembly | `scalive/api/src/scalive/routing/` | `scalive/api/test/src/scaliveapi/RoutedConstructionSpec.scala` |
| Signals, trees, bindings, streams, and deltas | `scalive/render/src/scalive/render/` | `RenderProgramSpec`, `StreamRenderingSpec`, `TreeDifferSpec`, `NestedRenderingSpec` |
| Transactional command processing | `scalive/runtime/kernel/src/scalive/runtime/kernel/SessionKernel.scala` | `SessionKernelSpec`, `ComponentKernelSpec`, `CandidateScopeIntegrationSpec` |
| Managed resources and uploads | `scalive/runtime/resources/` and `runtime/connection/` | `PreparedResourceSpec`, `ManagedAsyncSpec`, `ManagedSubscriptionsSpec`, `UploadWorkerSpec` |
| Root/nested ownership and output order | `scalive/runtime/connection/` and `runtime/topology/` | `RootConnectionSpec`, `ConnectionSupervisorSpec`, `NestedTopologyRuntimeSpec`, `SerialWriterSpec` |
| Phoenix frames and rendered projection | `scalive/protocol/phoenix/` | `PhoenixProtocolSpec`, `PhoenixRenderedEncoderSpec`, `PhoenixUploadProtocolSpec` |
| HTTP security, admission, and websocket integration | `scalive/transport/zio-http/` | `ZioHttpSpec`, `ZioHttpSecuritySpec`, `ZioHttpUploadSpec` |
| Application-facing test harness | `scalive/testing/` | `DisconnectedRenderSpec`, `ConnectedRenderSpec` |

Run the narrow module suite first, then a connected vertical slice. Changes to
browser-observable behavior should also be checked with
`./scripts/e2e-run-upstream.sh`; do not infer that the complete pinned upstream
suite is green merely because one native or browser scenario passes.

For supported behavior rather than internals, return to the
[Learn lifecycle](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)
or consult the [compatibility matrix](compatibility.md#compatibility-target).
