# Scalive Runtime Target Implementation Plan

## Status And Purpose

This document defines the implementation plan for the target runtime described in
[`runtime-target-architecture.md`](runtime-target-architecture.md).

The implementation is a greenfield rewrite in the final packages and modules. The current runtime
is retained only as an immutable behavioral oracle in a separate Git worktree. The new runtime does
not introduce a legacy backend, a `v2` namespace, runtime feature flags, or compatibility adapters.

The plan deliberately builds thin end-to-end slices. It does not complete the renderer, kernel,
protocol, and transport as isolated horizontal projects before exercising them together.

## Fixed Decisions

1. The current runtime is frozen at a known commit and remains executable in a separate worktree.
2. The greenfield branch uses the final public API and internal package names immediately.
3. API changes required by the target architecture happen before the new runtime is implemented.
4. Mill modules enforce dependency direction from the beginning.
5. Mill module selectors use a hierarchy such as `scalive.runtime.kernel`.
6. Published artifact names use kebab-case, such as `scalive-runtime-kernel`.
7. `scalive` remains the documented facade artifact.
8. ZIO HTTP value types may remain in the public API where they provide the best user experience.
   Executable routes, WebSockets, frames, server integration, cookies, and CSRF transport remain in
   the ZIO HTTP transport module.
9. Existing internal implementation tests are not automatically specifications. Public behavior,
   API contracts, Phoenix fixtures, and browser tests are the compatibility boundaries.

## Repository And Module Layout

```text
scalive/src                              # scalive facade
scalive/api/src
scalive/render/src
scalive/runtime/contracts/src
scalive/runtime/resources/src
scalive/runtime/topology/src
scalive/runtime/kernel/src
scalive/runtime/connection/src
scalive/protocol/phoenix/src
scalive/transport/zio-http/src
scalive/testing/src
```

Each module keeps its tests under its own `test/src` directory.

### Published Artifacts

| Mill module | Published artifact |
| --- | --- |
| `scalive` | `scalive` |
| `scalive.api` | `scalive-api` |
| `scalive.render` | `scalive-render` |
| `scalive.runtime.contracts` | `scalive-runtime-contracts` |
| `scalive.runtime.resources` | `scalive-runtime-resources` |
| `scalive.runtime.topology` | `scalive-runtime-topology` |
| `scalive.runtime.kernel` | `scalive-runtime-kernel` |
| `scalive.runtime.connection` | `scalive-runtime-connection` |
| `scalive.protocol.phoenix` | `scalive-protocol-phoenix` |
| `scalive.transport.zio-http` | `scalive-transport-zio-http` |
| `scalive.testing` | `scalive-testing` |

### Dependency Graph

In this list, `A -> B` means that `A` depends on `B`.

```text
scalive.render              -> scalive.api
scalive.runtime.contracts   -> scalive.api
scalive.runtime.resources   -> scalive.api + scalive.runtime.contracts
scalive.runtime.topology    -> scalive.api + scalive.runtime.contracts
scalive.runtime.kernel      -> scalive.api + scalive.render
                               + scalive.runtime.contracts
                               + scalive.runtime.resources
scalive.runtime.connection  -> scalive.runtime.contracts
                               + scalive.runtime.kernel
                               + scalive.runtime.topology
                               + scalive.runtime.resources
scalive.protocol.phoenix    -> scalive.runtime.contracts + scalive.render
scalive.transport.zio-http  -> scalive.api
                               + scalive.runtime.connection
                               + scalive.protocol.phoenix
scalive                     -> scalive.api + scalive.transport.zio-http
scalive.testing             -> scalive + explicitly supported test harnesses
```

`scalive.runtime.contracts` must remain small. It owns runtime identities, protocol-neutral ingress
and egress, reservation ports, rejection reasons, and generic ordered batches such as
`OutboundBatch[Delta]`. Render trees and concrete render deltas remain owned by `scalive.render`.

All internal artifacts must be published because they are transitive dependencies of the facade.
They are implementation artifacts, not separately documented user APIs.

## Target Public API Changes

### Connection Availability

Replace unconditional connected-only capabilities and disconnected no-ops with an explicit state:

```scala
enum Connection[+Connected]:
  case Disconnected
  case Connected(capabilities: Connected)
```

A capability matrix must be defined for every lifecycle phase. Every context that can run during a
disconnected lifecycle, including component and after-render contexts, must expose connected-only
operations through `Connection`. Message contexts are always connected and may expose their
capabilities directly.

Uploads, streams, flash, and navigation remain available in the phases where they have meaningful
disconnected behavior. No unavailable operation may silently succeed as a no-op.

### Routed LiveViews

Keep the ergonomic `LiveView.Routed` name, but make it a sibling of `LiveView`, not a subtype.
There must be no inherited unrouted `mount` method that can only fail with a defect.

Parameterized route construction must package the route codec, lifecycle factory, parameterized
mount, parameter handling, and decode recovery together. Unparameterized route builders accept only
ordinary `LiveView` definitions, while parameterized builders accept only routed definitions.

The heterogeneous route catalog must recover types through one sealed existential adapter rather
than repeated casts throughout the runtime.

### Component References

`ComponentRef` becomes an opaque semantic target. It exposes neither a Phoenix CID nor a meaningful
`toString` representation.

Rendering associates a component-relative target with an exact `ComponentInstanceId`. The Phoenix
adapter owns the `ComponentInstanceId -> CID` mapping and emits `phx-target`. Application component
identity remains independent from protocol CID allocation.

### Route Assembly

`Live.router(...)` produces a declarative `LiveApplication[R]` instead of executable ZIO HTTP
routes. The transport adapter performs the executable assembly:

```scala
val application = Live.router.withRootLayout(rootLayout)(routes*)
val httpRoutes  = ZioHttp.routes(application)
```

Typed ZIO HTTP values such as `URL`, `Request`, `PathCodec`, and `QueryCodec` may remain in public
signatures where replacing them would make the API worse. `Routes`, `WebSocketApp`, frames, channel
operations, server startup, cookie handling, and CSRF transport do not belong in `scalive.api`.

### Signals And HTML

The read-only `Signal` API remains in `scalive.api`. Signal source expressions may carry opaque
scope identities, but scope ownership, sampling, caching, revisions, and disposal belong to
`scalive.render`.

The public HTML algebra must not contain protocol or runtime state such as Phoenix CIDs,
`Diff.Stream`, socket values, or committed component state. Component, nested LiveView, stream, and
flash values remain declarative input to the renderer.

## Implementation Milestones

### 0. Freeze The Baseline And Define The Oracle

Freeze the current commit in an immutable branch or tag and create a separate legacy worktree.
Record the native test and upstream browser baseline before changing the greenfield branch.

Create a parity manifest based on `UPSTREAM_COMPATIBILITY.md`. Every scenario must eventually be
classified as required behavior, an intentional Scala divergence, out of scope, or a known legacy
defect.

Oracle comparisons use normalized observables only:

- HTTP status, redirect location, cookie semantics, and semantic DOM;
- Phoenix event kind, reply status, navigation, title, and normalized diff structure;
- resource results, ordering, cancellation, and cleanup;
- trace kind, stage, correlation identity, and outcome.

Tokens, signatures, CSRF values, timestamps, durations, CIDs, binding IDs, and map ordering are not
compared textually.

Acceptance criteria:

- The legacy native test suite is reproducible from the worktree.
- `./scripts/e2e-run-upstream.sh` is reproducible from the worktree.
- The baseline commit and compatibility target are recorded in the parity manifest.

### 1. Create The Mill Graph And Finalize The API

Create the complete Mill hierarchy and assign every published module an explicit `artifactName`.
Centralize Scala, ZIO, formatting, linting, testing, and publication configuration in shared build
traits.

Move or rewrite the public boundary in `scalive.api`:

- lifecycle definitions, contexts, hooks, and `Task`;
- `Signal`, typed HTML, bindings, JS commands, and generated DOM definitions;
- routes, locations, layouts, sessions, and mount aspects;
- forms and public async, subscription, stream, and upload declarations;
- the `scalive.*` package API.

Apply the target `Connection`, routed LiveView, `ComponentRef`, and route assembly designs in this
milestone. Remove protocol-shaped and runtime-shaped cases from the public HTML algebra.

The legacy runtime and its private implementation tests do not compile in the new graph. Behavioral
scenarios that still need to be reimplemented remain tracked in the parity manifest.

Acceptance criteria:

- `scalive.api` compiles and tests independently.
- It cannot import any other Scalive module.
- Compile-time tests cover connected capability availability, routed construction, component
  targeting, eventless views, forms, streams, uploads, and nominal identifiers.
- Generated DOM sources belong to `scalive.api`.

Verification:

```bash
mill --ticker false scalive.api.test
```

### 2. Build The Minimal Render Engine

Implement the render functionality required by the first root LiveView slice:

- opaque monotonic `TemplateId`, `TemplateSlotId`, and `BindingSlotId` values;
- structured template IR for elements, text, attributes, and bindings;
- immutable `RenderProgram` compiled once per lifecycle;
- signal evaluation with one sample per evaluation revision;
- immutable `EvaluatedTree`, `CommittedRender`, and `RenderCandidate`;
- immutable `BindingTable` containing typed dispatch operations;
- protocol-neutral `RenderDelta` and exact `TreeDiffer`;
- full `HtmlRenderer`;
- explicit closeable candidate scopes.

A render revision is proof of an exact retained change, not a hash. Sharing and exact revision
identity should avoid unnecessary structural comparisons. Hashes may only provide fast negative
checks; equal hashes must never suppress exact verification.

`BindingTable` insertion fails on every duplicate. No `Map.updated`, `++`, or `toMap` operation may
silently select a handler when assembling bindings.

Acceptance criteria:

- Static and dynamic HTML render correctly.
- Exact unchanged output produces an empty semantic delta.
- Forced hash collisions cannot suppress a real update.
- Duplicate bindings fail the candidate.
- A failed candidate leaves the committed tree, signal evaluation, bindings, and scopes unchanged.

Verification:

```bash
mill --ticker false scalive.render.test
```

### 3. Build The In-Memory Lifecycle Kernel

Implement the kernel without HTTP, WebSockets, or Phoenix payloads. Introduce at least:

```text
SessionCommand
SessionState
Committed
TurnDraft
TurnCandidate
PendingNavigation
SessionFailure
```

The kernel owns one bounded mailbox, one transition fiber, one private bounded continuation
worklist, one session scope, and one immutable session state carried as the loop parameter. It does
not use a semaphore around a collection of independently mutable references.

Implement the turn protocol:

```text
dequeue command
  -> validate state and epoch
  -> resolve typed dispatch
  -> run hooks and handler
  -> build the turn draft
  -> evaluate the render candidate
  -> prepare resources, topology, and outbound capacity
  -> run after-render behavior
  -> validate the complete candidate
  -> compute the semantic delta
  -> run the interruption-masked commit tail
  -> finalize retired resources outside the commit tail
```

Outbound reservations are FIFO slots installed before commit. Publishing completes an already
reserved slot and does not perform a potentially blocking `Queue.offer` in the commit tail.

Candidate resources use explicitly closeable child scopes and closed activation gates. Rollback
closes the candidate scope. Commit opens prepared gates and retains their handles under the session
scope. No fallible acquisition or unbounded wait occurs in the commit tail.

Acceptance criteria:

- Handler, render, validation, and preparation failures preserve the previous committed state.
- Interruption before the commit tail activates no candidate state or output.
- The commit tail is bounded, infallible `UIO` except for defects, and contains no acquisition.
- A commit-tail defect is terminal for the connection.
- State activation precedes output publication.
- Self-generated continuations never block on the mailbox consumed by the current turn.

Verification:

```bash
mill --ticker false \
  scalive.runtime.contracts.test \
  scalive.runtime.resources.test \
  scalive.runtime.kernel.test
```

### 4. Deliver The First End-To-End Root Slice

Connect all modules with a minimal counter LiveView:

```text
disconnected GET
  -> disconnected mount
  -> full HTML and bootstrap
  -> WebSocket join
  -> independent connected mount
  -> typed click binding
  -> SessionCommand
  -> turn and render candidate
  -> commit
  -> RenderDelta
  -> Phoenix diff
  -> bounded serial writer
```

Implement for this slice:

- a root-only `ConnectionSupervisor`;
- admission and routing for validated topics;
- heartbeat, root join, event, reply, and diff Phoenix codecs;
- bounded ingress, outbound reservations, and serial writing;
- disconnected HTTP lifecycle, signed session bootstrap, and CSRF;
- ZIO HTTP WebSocket upgrade and frame integration;
- `ZioHttp.routes`.

Do not recreate a generic per-topic dispatcher. Unknown topics are rejected before a worker,
lifecycle, or queue is allocated.

Acceptance criteria:

- HTTP and connected mounts create independent models.
- A typed click updates the browser-visible DOM through the Phoenix client.
- Candidate rollback is observable through the kernel harness.
- A slow client reaches the documented terminal policy without unbounded growth.
- No Phoenix JSON or wire field names appear in the kernel or renderer.
- Every queue in the slice has a finite capacity and tested saturation behavior.

### 5. Complete Routing, Lifecycle Hooks, And Navigation

Add:

- typed parameter decoding and routed mount;
- initial and patch `handleParams`;
- mount aspects, layouts, root layouts, and live sessions;
- root raw, event, info, async, params, and after-render hooks;
- page title, flash, static tracking, client events, and forms;
- patch, live navigation, full redirect, and redirect bounds.

Implement `SessionState.Navigating` with an explicit `NavigationId`, source and destination, staged
model, previous committed state, flash, deadline, and bounded FIFO deferred commands.

Only an acknowledgement matching the active lifecycle epoch and destination may consume pending
navigation. Ordinary model commands are deferred without execution until the matching parameter
turn commits.

Acceptance criteria:

- A stale or mismatched patch cannot consume the pending model.
- Deferred commands retain FIFO order.
- Navigation timeout and redirect-chain overflow have deterministic terminal behavior.
- Flash, title, layout, session, and static-tracking behavior match the public contract.
- Applicable upstream navigation scenarios pass.

### 6. Complete Render Identity And Components

Extend the renderer with:

- choices, optionals, and structured dynamic attributes;
- keyed collections with exact retained row identity;
- stream and component placeholders;
- nested LiveView and flash declarations;
- candidate retirement and rollback of render scopes.

Every keyed slot owns an exact `Map[K, RowState]`. `RowState` contains an opaque monotonic `RowId`,
its scope, bindings, retained template state, and last render revision. Scala map hashing may index
keys, but exact equality determines identity. `toString` is diagnostic only.

Removing a row makes it stale at commit and closes it afterward. Reintroducing a removed key creates
a new `RowId`. Duplicate keys fail candidate validation.

Build the component system:

- `ComponentForest` as part of `Committed`;
- exact `ComponentInstanceId` independent from CID;
- one sealed `MountedComponent` existential boundary;
- one transition coordinator shared by root and component nodes;
- component mount, update, message, async, output, hooks, and after-render;
- post-commit component outputs in the private continuation worklist.

Phoenix component CID allocation and routing state is connection-scoped protocol state. It is not
part of the application component identity or semantic render tree.

Acceptance criteria:

- Keyed reordering preserves row identity and bindings.
- Row removal and reintroduction have the specified scope lifecycle.
- Component and root state become visible atomically.
- Failed parent or component candidates roll back the entire component forest.
- Component outputs are ordered and bounded without self-mailbox deadlock.
- Component events work without exposing CIDs through the public API.
- Duplicate component and binding identities fail before commit.

### 7. Add Managed Resources And Streams

Introduce unified ownership:

```scala
enum OwnerId:
  case Root(lifecycle: LifecycleId)
  case Component(lifecycle: LifecycleId, component: ComponentInstanceId)

final case class ResourceToken(
  owner: OwnerId,
  ownerEpoch: Epoch,
  key: ResourceKey,
  generation: Long
)
```

Add resources in this order:

1. asynchronous tasks;
2. subscriptions;
3. managed client operations;
4. streams;
5. unified owner cleanup.

Async tasks start behind closed gates and deliver typed completion commands only after activation.
Replacement invalidates the previous token. Late completion from a stale token cannot affect state.

Subscriptions declare `Lossless` or `Latest` delivery. The runtime never silently conflates an
ordinary lossless subscription.

Streams have distinct snapshot and operation representations in render state. They are not encoded
as ordinary keyed collections with protocol fields attached.

Acceptance criteria:

- An immediately completing task cannot deliver before the turn that started it commits.
- Replaced and removed resources cannot deliver stale commands.
- Removing a component closes all resources owned by that component.
- No managed resource uses an unowned daemon fiber.
- Subscription backpressure or conflation follows the declared policy.
- Stream insert, delete, reset, placement, and component ownership match Phoenix behavior.

### 8. Add Nested LiveView Topology

The renderer emits declarative `NestedRequirement` values. It never starts, stops, or registers a
socket.

`scalive.runtime.topology` computes retain, prepare, replace, revoke, subtree retirement, and sticky
reattachment operations. A prepared topology lease reserves registration metadata but remains
inactive and starts no child kernel.

The parent commit tail activates the lease. A subsequent browser join validates the token, expected
topic, exact parent lifecycle, parent epoch, registration epoch, and active state. Only then may the
connection start a child `SessionKernel` or reattach a compatible detached sticky child.

Disconnected nested rendering recursively executes one-shot kernels in the request scope. Those
models and resources are never installed into the connected child lifecycle.

Acceptance criteria:

- A committed nested requirement that is never joined allocates no child kernel.
- A stale join after replacement or parent remount is rejected.
- Sticky rejoin attaches only the matching retained child.
- Revocation makes registration unavailable immediately and retires joined subtrees after commit.
- Connection shutdown closes detached sticky children and all descendants.
- Parent-child crash propagation uses exact lifecycle identities and explicit policy.

### 9. Add Uploads

Keep upload metadata, validation, progress, and application callbacks in the session kernel. Route
bulk binary chunks through dedicated bounded workers owned by the connection or upload-entry scope.
Bulk bytes never pass through the model-transition mailbox.

The connection validates upload topic ownership against exact lifecycle identity and epoch. Upload
destination writer state and result types cross one sealed existential adapter.

Acceptance criteria:

- Allow, preflight, upload join, chunk, progress, cancel, consume, and cleanup work end to end.
- Component-scoped uploads use exact component ownership.
- External uploader and destination writer failures follow explicit failure policy.
- Per-entry chunk overflow produces an explicit protocol failure.
- Stale upload tokens and owner epochs cannot write or report progress.
- Large upload traffic cannot monopolize the model mailbox.

### 10. Complete Backpressure, Failure Policy, Observability, And Security

Backpressure behavior is implemented throughout earlier milestones and audited here as one system.

| Queue | Required policy |
| --- | --- |
| Connection ingress | Transport backpressure, then close on sustained abuse |
| Session mailbox | Bounded FIFO; explicit rejection or close on client saturation |
| Turn continuations | Bounded private worklist; overflow fails the candidate |
| Subscription delivery | Explicit lossless or latest-value policy |
| Async completion | Bounded delivery through the session mailbox only |
| Session output | Reserved ordered batches; slow-client failure is terminal |
| Serial writer | Bounded FIFO; write failure terminates the connection |
| Upload chunks | Bounded per entry; explicit protocol failure on overflow |

Capacities live in an internal runtime configuration with strictly positive values. Tests use small
capacities to exercise every boundary. Production defaults are calibrated by benchmarks and are not
public semantic contracts.

Add structured events for command acceptance, turn start, handler completion, candidate validation,
diff completion, state commit, output publication, resource activation and retirement, failure, and
session closure. Correlation identities include connection, lifecycle, epoch, command, turn,
navigation, and resource identity. Application models and browser payloads are redacted unless an
explicit projection contract is configured.

Complete security testing for signed sessions, CSRF, nested registration tokens, upload ownership,
binding lookup, route input, forms, connect parameters, and protocol payloads. Unknown topics and
invalid authorization must be rejected before lifecycle allocation.

Acceptance criteria:

- No runtime production path uses `Queue.unbounded` or `mergeAllUnbounded`.
- Every failure source has an explicit supervision outcome.
- Every target observability boundary emits a correlated event.
- Models, secrets, and untrusted payloads are redacted by default.
- Protocol fuzzing cannot allocate arbitrary workers or bypass ownership and epoch validation.
- Connection termination closes every lifecycle, sticky child, upload worker, and resource scope.

### 11. Complete Testing, Documentation, Publication, And Cutover

Create the supported connected harness in `scalive.testing`. It must be able to finalize and join a
route, send browser-equivalent events, observe semantic output, control time and managed work, and
assert cleanup without exposing private runtime references.

Treat existing tests as follows:

| Existing tests | Target treatment |
| --- | --- |
| `scaliveapi/*Spec` | Port to `scalive.api.test` |
| HTML, Signal, TreeDiff, BindingRegistry | Rewrite against `scalive.render` contracts |
| Socket, TopicDispatcher, SerialWriter | Replace with kernel and connection harness tests |
| Lifecycle, navigation, component, async, flash | Port as connected behavioral scenarios |
| WebSocketMessage and upload wire tests | Convert to canonical Phoenix fixtures |
| `DisconnectedRender` | Preserve in `scalive.testing` |
| Private documentation-site harness | Replace with the public connected harness |
| Upstream browser E2E | Preserve as the final browser compatibility gate |

Update the documentation pipeline source roots, API target roots, examples, quick-start fixture,
public API snapshot, and publication workflow for the new module hierarchy. The public API
documentation covers `scalive.api`, public transport integration, the facade, and
`scalive.testing`, not internal runtime packages.

Create maintained benchmarks for:

- 100, 1,000, and 10,000 keyed rows;
- one-row update, reorder, removal, and reintroduction;
- binding-heavy rows and binding lookup;
- equal rendered values after changed model input;
- streams with a large snapshot and small patch;
- nested components and retained templates;
- late candidate failure and rollback;
- semantic diff and Phoenix encoding separately and end to end.

Track latency percentiles, bytes allocated per turn, retained heap per lifecycle, diff size, signal
sample count, and render/diff duration. The historical view-graph benchmark is context, not a release
threshold.

## Verification Gates

Run focused module tests while implementing each milestone. Once the first vertical slice exists,
the complete nested Scalive suite becomes a per-change gate.

```bash
mill --ticker false scalive.api.test
mill --ticker false scalive.render.test
mill --ticker false scalive.runtime.kernel.test
mill --ticker false scalive.runtime.connection.test
mill --ticker false scalive.protocol.phoenix.test
mill --ticker false scalive.transport.zio-http.test
mill --ticker false scalive.__.test
```

Run repository-wide tests and documentation checks before integration milestones and publication:

```bash
mill --ticker false __.test
mill --ticker false documentation.check
mill --ticker false __.reformat + __.fix
```

Run upstream browser tests for every change that affects Phoenix encoding, navigation, components,
nested views, streams, uploads, or transport behavior:

```bash
./scripts/e2e-run-upstream.sh
```

## Cutover Criteria

The greenfield runtime is ready to replace the legacy oracle only when all of the following hold:

1. The old `Socket`, `LiveChannel`, `Socket*Runtime`, `TopicDispatcher`, and equivalent legacy state
   machines do not exist in the greenfield source tree.
2. The Mill dependency graph makes all target dependency inversions impossible.
3. No runtime queue is unbounded and every saturation policy has deterministic tests.
4. Every applicable row in `UPSTREAM_COMPATIBILITY.md` links to concrete Scalive evidence.
5. Every browser-visible feature has deterministic native coverage and browser coverage where
   applicable.
6. The complete applicable upstream E2E suite passes in three consecutive CI runs with no hidden or
   selectively disabled tests.
7. Success, rollback, interruption, activation defect, stale epoch, saturation, and cleanup behavior
   are covered at each ownership boundary.
8. No oracle difference remains unclassified.
9. Tracing covers every target transition boundary and redacts sensitive data by default.
10. Documentation, examples, API snapshots, published artifacts, and the facade dependency graph are
    consistent with the new architecture.
11. Dedicated performance benchmarks meet thresholds fixed before cutover for CPU, allocation,
    retained heap, and output size.
