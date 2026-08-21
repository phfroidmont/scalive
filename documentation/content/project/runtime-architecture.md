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

A routed LiveView still has two independent mounts. The HTTP request and the
websocket join create different models and different resource scopes.

### Disconnected HTTP Render {#disconnected-http-render}

For a GET, the selected `CompiledRoute`:

1. decodes typed path and query parameters and runs session then route mount
   aspects;
2. constructs a `RootLifecycle` and a `DisconnectedRootTurn`, whose contexts
   expose `Connection.Disconnected`;
3. runs mount, initial parameter handling, hooks, flash, and redirect handling;
4. compiles and evaluates a `RenderProgram`, resolving disconnected components
   with `DisconnectedComponentRenderer`;
5. renders the resulting `EvaluatedTree` into the live layout and root layout,
   adding CSRF and signed session/static bootstrap data; and
6. closes request-owned render, component, and prepared-resource scopes.

This is a one-turn server render. The browser keeps the HTML and bootstrap data,
but the server does not carry its model or committed render into the connected
lifecycle.

### ZIO HTTP Admission And Connected Bootstrap {#connected-bootstrap}

The websocket route creates one scoped `SerialWriter`, one
`ConnectionSupervisor`, and connection-local registries. `PhoenixProtocol`
decodes text envelopes; upload chunks use bounded binary frames. Root joins are
serialized by a join gate before `ZioHttpAdmission` accepts them.

Admission verifies the topic/root identity, signed session and static claims,
canonical URL, route and live-session identity, root-layout and mount-claim
markers, CSRF cookie/token pair, and whether the requested route may replace the
current root. Nested joins additionally reserve an exact active topology
registration. Rejected joins receive Phoenix-compatible stale or unauthorized
replies without constructing application lifecycle state.

After admission, the transport rebuilds the connected `RootLifecycle`, supplies
`Connection.Connected` metadata, and asks `ConnectionSupervisor` to start a
`RootConnection`. Connected mount and initial parameter handling therefore
produce a fresh model. The first connected `RenderDelta` reconciles that fresh
tree with the browser's disconnected DOM; it is not a continuation of the HTTP
render transaction.

## Follow One Connected Command {#connected-turn}

`ConnectionSupervisor` owns every connected lifecycle on one physical
websocket. Each root or nested lifecycle is represented by a `RootConnection`.
The connection translates browser events, typed messages, patches, async and
subscription completions, component work, and upload mutations into
epoch-qualified `SessionCommand`s.

`RootConnection` uses a bounded ingress queue and command correlation table.
Its ingress fiber submits commands to the lifecycle's `SessionKernel`; its
outbound fiber drains ordered reservations and sends `ConnectionOutput` through
the transport sink. A normal event follows this path:

```text
Phoenix frame
  └─► ZioHttp + PhoenixProtocol
        └─► ConnectionSupervisor / RootConnection
              └─► SessionKernel(SessionCommand)
                    ├─► lifecycle handler and hooks
                    ├─► RenderProgram candidate
                    ├─► component/resource/topology preparation
                    ├─► TreeDiffer(RenderDelta)
                    └─► atomic commit or candidate discard
                          └─► ConnectionOutput
                                └─► PhoenixRenderedEncoder
                                      └─► SerialWriter
```

The kernel is the single owner of committed lifecycle state. Its bounded FIFO
mailbox serializes commands, while a reserved mailbox path prevents a server
patch acknowledgement from deadlocking behind ordinary work. External
producers receive explicit saturation or closed errors; runtime-owned producers
backpressure where dropping would violate lifecycle semantics.

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

`SessionKernel.runTurn` runs the handler, builds and stabilizes this candidate,
prepares resources and topology changes, runs after-render work, validates the
result, and computes a `RenderDelta` with `TreeDiffer`. Only then does commit
replace the model, render, component forest, resource index, and topology plan.
The old committed scopes are retired after replacement. Any failure discards
candidate scopes and rollback plans while leaving the previous committed state
active.

This is an in-memory runtime transaction, not a transaction over arbitrary
application effects. Database or network effects already completed by a handler
are not undone, and a later websocket write failure does not roll committed
model state back.

`PhoenixRenderedEncoder` is downstream of this transaction. It turns the
protocol-neutral tree and delta into Phoenix statics/dynamics, component CIDs,
stream references and operations, events, replies, and navigation payloads. Its
connection-local `PhoenixRenderedState` is projection state, not application
state.

## Track Components And Nested Lifecycles {#children}

A stateful component owns typed props, model, hooks, bindings, render program,
and component-scoped resources. `ComponentRuntime` prepares component candidates,
but the owning `SessionKernel` stabilizes and commits the complete root/component
forest in one turn. Component messages therefore share the owning lifecycle's
serialization and cannot commit independently of their root.

A nested LiveView is different: it gets its own `RootConnection`, kernel,
mailbox, committed render, and resource owners inside the same physical
connection. `NestedTopologyState` prepares registration changes transactionally;
`NestedTopologyRuntime` admits exact registration generations and coordinates
attachment, subtree retirement, sticky detachment/reattachment, and linked
failure. Parent cleanup recursively closes non-retained descendants.

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
| Websocket scope | `ConnectionSupervisor`, physical `SerialWriter`, joined topics, upload joins |
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
