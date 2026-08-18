# Scalive Runtime Target Architecture

## Status And Purpose

This document assesses Scalive's current runtime architecture and defines the target architecture
that would be chosen if the runtime were designed from scratch today.

It is an architecture reference, not an implementation plan. It defines responsibilities,
boundaries, invariants, state models, and tradeoffs. It does not prescribe migration phases, task
ordering, or compatibility scaffolding for moving the current implementation toward the target.

The target assumes that:

- Scalive continues to use the Phoenix LiveView JavaScript client and wire protocol;
- Scala 3 and ZIO remain the implementation platform;
- application state remains server-owned;
- a LiveView model belongs to one connection lifecycle and is not durable state;
- disconnected HTTP rendering and connected socket rendering remain independent mounts;
- live session migration between JVM processes is not required; and
- API quality and type safety remain more important than copying Phoenix internals.

If transparent session migration or replicated in-memory LiveView state becomes a requirement, the
session ownership and durability model in this document must be revisited.

## Executive Assessment

Scalive's conceptual architecture is strong. Its public programming model, signal-backed rendering,
and lifecycle semantics are close to the architecture that should be retained long term.

The main weakness is not the chosen paradigm. It is that one logical state machine is currently
distributed across many queues, fibers, semaphores, references, and feature-specific runtimes. The
implementation has grown feature by feature, so important invariants are enforced procedurally
across `SocketInbound`, `SocketOutbound`, `SocketModelRuntime`, `SocketComponentRuntime`,
`LiveChannel`, and the rendering graph rather than represented directly in a small number of state
types.

The target architecture therefore preserves the application-facing model while replacing implicit
coordination with:

- one serialized command owner per root or nested LiveView lifecycle;
- one coherent immutable state value per lifecycle and per connection topology;
- an explicit draft, validate, render, commit, and publish turn boundary;
- exact identities and revisions for correctness;
- bounded queues with defined overload behavior;
- scoped resource ownership and explicit supervision;
- a pure rendering core separated from Phoenix protocol encoding; and
- one shared transition coordinator for root LiveViews and components.

The target is not a different framework. It is a more explicit and mechanically enforceable form of
the framework Scalive already intends to be.

## Current Architecture Assessment

### Strengths To Preserve

#### Typed application state machines

`LiveView[Msg, Model]` is a strong framework boundary. `mount` creates state,
`handleMessage` performs an effectful state transition, and `view` projects a read-only model signal
into typed HTML. The message type restricts which values rendered bindings may produce.

This is clearer and safer than exposing socket assign maps, generic process messages, callback
tuples, or protocol payloads to application code. The eventless `Nothing` variant and typed routed
parameters reinforce the same design.

Primary source landmarks:

- `scalive/src/scalive/LiveView.scala`
- `scalive/src/scalive/LiveComponent.scala`
- `scalive/src/scalive/LiveContext.scala`
- `scalive/src/scalive/LiveParamsCodec.scala`

#### Read-only signals and compile-once rendering

`Signal` intentionally exposes transformation but not mutation, sampling, effects, or subscription.
That restriction permits caching and skipped evaluation without changing application behavior.

Signal scopes also encode ownership. Parent signals are visible to descendants, while child and
sibling signals cannot escape their render scope. Keyed row scopes and component scopes can be
retained or disposed according to render identity.

Building the view graph once per lifecycle is also the right tradeoff. It avoids reconstructing and
recompiling the complete HTML tree for every message while preserving a declarative application API.

Primary source landmarks:

- `scalive/src/scalive/Signal.scala`
- `scalive/src/scalive/rendering/ViewGraph.scala`
- `scalive/src/scalive/rendering/RenderSnapshot.scala`
- `docs/benchmarks/view-graph-performance.md`

#### Per-lifecycle serialization

Browser events, subscription values, async completions, component outputs, and live patches
eventually acquire the same lifecycle semaphore. This provides the essential invariant that only one
model/render transition commits to a socket at a time.

Phoenix obtains a similar property from one mailbox per LiveView process. Scalive's use of ZIO
primitives is appropriate for the JVM; the semantic invariant matters more than copying the BEAM
mechanism.

Primary source landmarks:

- `scalive/src/scalive/socket/SocketInbound.scala`
- `scalive/src/scalive/socket/SocketOutbound.scala`
- `scalive/src/scalive/socket/SocketRuntimeState.scala`

#### Resource ownership and cleanup

The runtime consistently treats graphs, event fibers, async work, subscriptions, uploads, and nested
lifecycles as connection-owned resources. Normal shutdown and crash paths interrupt or close most
owned work, and socket crash handling is idempotent.

The use of ZIO `Scope` is directionally correct and should remain the foundation of lifecycle
ownership.

Primary source landmarks:

- `scalive/src/scalive/Socket.scala`
- `scalive/src/scalive/socket/SocketCrashRuntime.scala`
- `scalive/src/scalive/socket/SocketOutbound.scala`
- `scalive/src/scalive/routing/LiveChannel.scala`

#### Components and nested LiveViews have distinct semantics

A stateful component has local props, model, hooks, bindings, and render state, but participates in
the parent LiveView's render transaction. A nested LiveView is a separate lifecycle with its own
model, queueing, rendering, and cleanup semantics.

That distinction is correct. Components should not be turned into independent network actors, and
nested LiveViews should not be reduced to component state.

#### Protocol compatibility is kept behind typed APIs

Application code works with typed messages, locations, forms, uploads, streams, and client commands.
Phoenix topics, CIDs, payload field names, binding identifiers, and diff encoding remain internal.

This separation is a major design strength and should become stricter in the target architecture.

### Architectural Risks And Complexity

#### Correctness depends on probabilistic fingerprints

`TreeDiff` treats equal 32-bit fingerprints as proof that a subtree is unchanged. Those fingerprints
are assembled with `MurmurHash3`, ordinary `hashCode`, and string hashes in
`RenderSnapshot`.

A collision can therefore suppress a real DOM update. The probability may be low for one comparison,
but it is not an acceptable correctness boundary for a long-running rendering system. Hashes may be
used to avoid expensive comparisons when they differ. Equal hashes must be verified by exact
identity, revision, or value comparison.

Relevant source:

- `scalive/src/scalive/rendering/TreeDiff.scala`
- `scalive/src/scalive/rendering/RenderSnapshot.scala`

#### Binding identity depends on key stringification

Keyed binding paths derive a token from the key's runtime class and `String.valueOf(key)`. Distinct
keys can be unequal while sharing the same class and textual representation. Those rows then receive
the same structural binding path even without a cryptographic hash collision.

Bindings from multiple rows are combined with ordinary map replacement, so a duplicate generated ID
can silently replace an earlier handler. An event may consequently target the wrong row.

Render identity must use exact retained row identity. Arbitrary domain-key `toString` output must not
participate in correctness.

Relevant source:

- `scalive/src/scalive/rendering/BindingId.scala`
- `scalive/src/scalive/rendering/ViewGraph.scala`
- `scalive/src/scalive/rendering/BindingRegistry.scala`

#### The intended render transaction is split across systems

The runtime intends to make a model and rendered snapshot visible together, but participating state
can change before the complete turn succeeds.

Examples include:

- component handlers updating `componentsRef` before the parent render commits;
- component mount, update, and after-render callbacks running while the parent graph is being
  evaluated;
- signal graph commit actions running when graph evaluation completes, before root after-render
  hooks and the socket state replacement;
- async start and cancel operations mutating a global task registry during an application callback;
- flash, streams, client events, navigation, hooks, uploads, and subscriptions using separate
  mutable runtime stores; and
- nested topology using a separate render transaction from component and signal transactions.

External effects performed by application code cannot be rolled back, and the architecture should
not pretend otherwise. Framework-owned model, graph, component, hook, child-topology, and resource
state can and should participate in one explicit commit protocol. State owned by separate session and
connection fibers uses prepared, inactive reservations and an interruption-masked commit tail rather
than pretending that two owners share one in-memory transaction.

Relevant source:

- `scalive/src/scalive/socket/SocketModelRuntime.scala`
- `scalive/src/scalive/socket/SocketComponentRuntime.scala`
- `scalive/src/scalive/socket/SocketAsyncRuntime.scala`
- `scalive/src/scalive/NestedLiveView.scala`
- `scalive/src/scalive/Signal.scala`

#### One state machine is represented by many mutable locations

`RuntimeState` contains the LiveView, graph, queues, lock, model/render pair, pending navigation
model, URL, subscriptions, navigation, uploads, streams, client events, flash, async tasks,
components, component CIDs, redirect count, crash state, and bootstrap output.

The lifecycle semaphore serializes most access, but atomicity depends on every code path knowing
which subset of references must be read, updated, restored, drained, or cleaned up together. Adding a
feature expands the state record and usually adds another runtime adapter and cleanup path.

The same pattern exists at connection level. `LiveChannel` distributes topology across socket,
upload-owner, nested-entry, render-plan, generation, and join-reservation maps protected by partially
overlapping locks.

Relevant source:

- `scalive/src/scalive/socket/SocketRuntimeState.scala`
- `scalive/src/scalive/socket/SocketBootstrap.scala`
- `scalive/src/scalive/routing/LiveChannel.scala`
- `scalive/src/scalive/NestedLiveView.scala`

#### Navigation is an implicit intermediate state

Patch navigation places a model in `pendingNavigationModelRef`, publishes a browser navigation
command, and waits for a later live-patch message. Other turns may use the pending model before that
patch arrives. The next patch consumes it without carrying an explicit navigation identity.

The behavior may be correct for the expected client ordering, but the state machine does not contain
enough information to prove that a patch acknowledges the intended navigation. Stale messages,
interleaved server events, redirects, and reconnects are handled by convention rather than an
explicit transition state.

Relevant source:

- `scalive/src/scalive/socket/SocketInbound.scala`
- `scalive/src/scalive/socket/SocketModelRuntime.scala`
- `scalive/src/scalive/socket/SocketBootstrap.scala`

#### Queue bounds do not provide end-to-end backpressure

The browser-event socket inbox is bounded, but its upstream per-topic dispatcher queue is unbounded.
When the inbox fills, the topic worker blocks and later frames accumulate in the dispatcher queue.

The async-completion queue, component-output queue, socket outbox, merged channel output, and serial
writer queue are also unbounded. `TopicDispatcher` creates a permanent worker for every observed
topic and does not retire that worker before the connection scope closes. A client can therefore
create workers for unknown topics or cause memory growth behind a slow handler or writer.

Relevant source:

- `scalive/src/scalive/routing/TopicDispatcher.scala`
- `scalive/src/scalive/routing/SerialWriter.scala`
- `scalive/src/scalive/routing/LiveChannel.scala`
- `scalive/src/scalive/socket/SocketBootstrap.scala`

#### Root and component transition logic is duplicated

Root lifecycle handling is divided among inbound dispatch, outbound dispatch, model coordination,
and feature runtimes. `SocketComponentRuntime` implements comparable hook, handler, navigation,
async, model, render, and reply behavior for components.

Components require different storage and ownership semantics, but they do not require a separate
transition protocol. Parallel lifecycle interpreters make behavior drift and transactional fixes
more likely.

#### Rendering owns too many responsibilities

`ViewGraph` currently contains template compilation, signal evaluation, scope management, binding
generation, dynamic attributes, components, nested LiveViews, flash, keyed collections, streams,
static tracking, and resource-resolution hooks.

One visible symptom is dynamic attribute choice handling: a synthetic element is rendered to HTML,
then its opening tag is sliced back into an attribute string. Structured attributes should remain
structured throughout compilation and evaluation.

The compile-once graph concept is good. The concentration of unrelated responsibilities in one
implementation unit is not required by that concept.

#### Type erasure is spread across the runtime

Some heterogeneous state is unavoidable. Components with different model types, upload writers with
different result types, and signal caches require existential boundaries somewhere.

The current runtime spreads `Any`, `Object`, `ClassTag`, and `asInstanceOf` across component
dispatch, async delivery, uploads, streams, bindings, and rendering. Runtime type recovery is
therefore repeated rather than concentrated in a small number of audited adapters.

#### Phase capabilities are good but disconnected behavior is not explicit enough

Separate mount, message, params, and after-render context traits communicate lifecycle capabilities
well. However, connected-only operations exposed during disconnected mount or parameter handling are
implemented as no-ops. That makes unavailable behavior legal and silent rather than explicit in the
type model.

The context hierarchy also repeats large capability lists across root and component phases. Adding a
new capability requires broad public and internal edits.

## Target Design Goals

The target runtime has the following goals:

1. Preserve the typed, immutable, server-owned application programming model.
2. Make one lifecycle owner solely responsible for ordering and committing its state.
3. Represent lifecycle phases and pending navigation explicitly.
4. Make framework-owned turn state atomic across model, render, components, hooks, and resources.
5. Keep render evaluation deterministic and application signal transformations pure.
6. Use exact identity and revision semantics for correctness.
7. Keep Phoenix protocol details at transport and encoding boundaries.
8. Bound every queue and define overload behavior.
9. Use ZIO scopes for ownership and explicit policies for supervision.
10. Concentrate unavoidable type erasure behind sealed adapters.
11. Support disconnected and connected execution through the same lifecycle kernel.
12. Provide first-class testing and observability at command and transition boundaries.

## Target Non-Goals

The target does not:

- reproduce Phoenix or BEAM internals;
- add a generic actor framework;
- use STM as a substitute for a clear single-owner state machine;
- persist or replicate LiveView models;
- provide exactly-once external application effects;
- move ordinary application state into the browser;
- rebuild the complete HTML tree on every transition;
- expose wire payloads, topics, CIDs, or binding IDs as application APIs;
- introduce a plugin abstraction for every runtime feature; or
- create abstraction layers solely for hypothetical alternative browser protocols.

## Architectural Invariants

The following invariants define the target architecture.

### Lifecycle ownership

1. Every root or nested LiveView lifecycle has one unique `LifecycleId` and `Epoch`.
2. Exactly one scoped fiber owns and mutates a lifecycle's state.
3. Every model-affecting input is processed through that owner's command mailbox.
4. No external fiber mutates committed lifecycle state directly.
5. Every child registration records the exact parent lifecycle ID and epoch.

### Turn atomicity

1. A turn starts from one immutable committed state.
2. Context capabilities write only to turn-local draft state or a turn-local operation journal.
3. Render evaluation, component updates, hooks, and child requirements produce candidate state.
4. Cross-owner topology changes and outbound capacity are prepared but remain externally inactive.
5. Candidate state and every preparation are validated before the commit tail begins.
6. A failed candidate leaves the previous committed state active and closes candidate resources.
7. The commit tail is interruption-masked and contains only bounded, infallible state activation.
8. A partial commit-tail defect is terminal for the connection and cannot publish candidate output.
9. Retired resources are made stale at commit and finalized after replacement state becomes active.
10. Network publication occurs after state activation and never rolls committed state back.
11. External application effects are outside runtime rollback and remain the application's
   transaction responsibility.

### Identity and rendering

1. Hash equality is never proof of semantic equality.
2. Binding IDs are unique within their owning rendered lifecycle.
3. Duplicate generated identities fail the candidate turn rather than replacing an existing entry.
4. Keyed row identity uses exact domain-key equality and a retained runtime row identity.
5. Component identity is independent from protocol CID allocation.
6. Protocol encoding cannot mutate application or render state.

### Concurrency and overload

1. Every queue has a finite capacity.
2. Every queue documents backpressure, rejection, conflation, or connection-termination behavior.
3. No model or diff is silently dropped by default.
4. Unknown topics do not allocate lifecycle workers.
5. A slow client cannot cause unbounded server memory growth.
6. Upload byte processing cannot monopolize the model-transition mailbox.

### Encapsulation

1. Only the Phoenix adapter understands Phoenix payload maps and wire field names.
2. Only the connection supervisor understands topic-to-lifecycle routing.
3. Only the session kernel commits LiveView and component state.
4. Only the render engine owns template, signal, snapshot, and diff representations.
5. Unavoidable erased values cross one sealed boundary and are immediately recovered through typed
   operations.

## Target Architecture Overview

```text
                              Application
                    LiveView / Component / Signal / HTML
                                  |
                                  v
                         +------------------+
                         | Lifecycle Kernel |
                         | one-shot or live |
                         +---------+--------+
                                   |
                    candidate input|render plan
                                   v
                         +------------------+
                         |  Render Engine   |
                         | template + diff  |
                         +---------+--------+
                                   |
                            protocol delta
                                   v
+----------+      +----------------+----------------+      +----------------+
| ZIO HTTP | ---> | Phoenix Transport And Protocol | ---> | Phoenix Client |
+----------+      +----------------+----------------+      +----------------+
                                   |
                                   v
                         +---------------------+
                         | Connection          |
                         | Supervisor          |
                         | topology + writer   |
                         +----------+----------+
                                    |
                       +------------+-------------+
                       |                          |
                       v                          v
              +----------------+          +----------------+
              | Root Session   |          | Nested Session |
              | Kernel         |          | Kernel         |
              +-------+--------+          +-------+--------+
                      |                           |
                      v                           v
              Component Forest            Component Forest
              Resource Registry           Resource Registry
```

There are two uses of the lifecycle kernel:

- a disconnected HTTP request runs a one-shot lifecycle in the request scope and renders a full
  document; and
- a connected root or nested LiveView runs a persistent session kernel owned by the WebSocket
  connection scope.

The two executions create independent models and resources, but share transition and rendering
semantics.

## Public Application Boundary

The target retains these primary concepts:

- `LiveView[Msg, Model]` as the typed root state machine;
- `LiveComponent[Props, Msg, Model]` as stateful local UI;
- immutable models and typed messages;
- typed routed parameters and locations;
- read-only `Signal[A]` values;
- typed HTML and event bindings;
- phase-appropriate context capabilities;
- typed async, subscription, stream, upload, flash, navigation, and client operations; and
- independent nested LiveViews.

Application callbacks remain effectful because they may call services or perform I/O. The runtime
does not attempt to roll back those effects if a later render fails.

### Connection availability

Connected-only capabilities are represented explicitly rather than as disconnected no-ops. Mount
and parameter contexts expose a connection state such as:

```scala
enum Connection[+Connected]:
  case Disconnected
  case Connected(capabilities: Connected)
```

Message contexts are always connected and can expose their connected capabilities directly.

The exact public syntax can remain ergonomic, but it must be impossible to start async work or a
subscription during a disconnected lifecycle without explicitly handling the disconnected case.

### Routed views

Routed and unrouted definitions have distinct type-level construction paths. A routed LiveView does
not inherit an unrouted mount operation that can only terminate with a defect. The router accepts a
definition whose parameter codec and mount function are structurally paired.

## Phoenix Transport And Protocol Adapter

The Phoenix adapter owns:

- WebSocket frame decoding and encoding;
- Phoenix event names and payload shapes;
- join references and message references;
- topic syntax;
- session and CSRF bootstrap fields;
- component CIDs at the protocol boundary;
- live-patch and redirect payload encoding;
- upload protocol frames; and
- conversion from internal render deltas to Phoenix diff JSON.

It produces a small internal command algebra. The session kernel never receives arbitrary Phoenix
JSON objects when a narrower decoded value is available.

Protocol decoding validates payload shape but does not establish application trust. Route values,
connect parameters, form data, and binding payloads remain untrusted until decoded at their typed
boundary.

The adapter contains no application model transition or render-graph mutation logic.

## Disconnected HTTP Lifecycle

The disconnected route performs one scoped execution of the lifecycle kernel:

```text
decode route
  -> run mount policy
  -> construct LiveView
  -> mount disconnected model
  -> handle initial parameters
  -> evaluate candidate tree
  -> render full HTML and bootstrap metadata
  -> run transactional after-render behavior
  -> close request-owned graph and component resources
```

It uses the same render templates, component interpreter, identity validation, hooks, and turn-draft
rules as a connected session. Its environment supplies a disconnected resource interpreter:

- connected-only resource operations are unavailable;
- uploads and streams may create renderable disconnected declarations where supported;
- navigation produces an HTTP redirect outcome; and
- client commands are encoded only where disconnected HTML requires them.

The disconnected model and graph are never installed into the connected session.

## Connection Supervisor

One `ConnectionSupervisor` belongs to one physical WebSocket scope. It owns:

- authorization established for the connection;
- the root and nested lifecycle registry;
- topic-to-lifecycle routing;
- parent-child topology;
- sticky nested lifecycle attachment state;
- pending joins and join epochs;
- upload topic ownership;
- the bounded connection ingress policy;
- merged outbound protocol messages; and
- one serial WebSocket writer.

Its state is one immutable value:

```scala
final case class ChannelState(
  lifecycles: Map[LifecycleId, LifecycleHandle],
  topics: Map[Topic, LifecycleId],
  topology: ChildTopology,
  pendingJoins: Map[JoinId, JoinReservation],
  preparedTopology: Map[TopologyTransactionId, PreparedTopology],
  uploadOwners: Map[UploadRef, LifecycleId],
  outboundReservations: OutboundReservationState,
  status: ConnectionStatus
)
```

The supervisor processes topology commands serially. It does not mutate lifecycle model or render
state. Session commands are forwarded to lifecycle mailboxes only after topic ownership and epoch
validation.

Topology and outbound reservations are scoped leases. Preparing one allocates no child session and
makes no registration visible to client joins. A lease is activated only by the parent turn's commit
tail or released when the candidate fails.

### Lifecycle identity

Topics are transport addresses, not lifecycle identities. Reusing a topic after root replacement or
child remount creates a new lifecycle ID and epoch. Delayed events, leaves, crash signals, and upload
messages carry or resolve to the expected lifecycle epoch before they can affect current state.

### Nested supervision

Parent crash propagation is an explicit supervisor decision. It never depends on a delayed daemon
fiber or a lookup by reusable topic alone.

Sticky nested views remain owned by the connection supervisor while detached. A valid sticky rejoin
reattaches the existing lifecycle; a non-sticky rejoin creates a fresh lifecycle.

## Session Kernel

Each connected root or nested LiveView has one `SessionKernel` with:

- one bounded command mailbox;
- one scoped transition fiber;
- one immutable session state;
- one render coordinator;
- one component forest;
- one owner-scoped resource registry; and
- one output channel to the connection supervisor.

The kernel uses an internal actor pattern implemented directly with ZIO `Queue`, `Promise`, `Scope`,
and a scoped fiber. It does not require an actor framework.

Because only the transition fiber owns the state, the session does not need a semaphore around a set
of independently mutable references. The current state can be a loop parameter rather than a public
`Ref`.

### Session commands

Every model-affecting input enters one command algebra:

```scala
enum SessionCommand:
  case ClientEvent(event: DecodedEvent, reply: ReplyHandle)
  case ServerMessage(message: BoundMessage)
  case ParamsPatch(patch: PatchAcknowledgement, reply: ReplyHandle)
  case AsyncCompleted(completion: AsyncCompletion)
  case SubscriptionValue(value: SubscriptionMessage)
  case ComponentOutput(output: BoundComponentOutput)
  case UploadProgress(progress: DecodedUploadProgress, reply: ReplyHandle)
  case ResourceFailed(failure: ManagedResourceFailure)
```

Heartbeat handling, raw WebSocket framing, and bulk upload bytes do not require the model mailbox.

Orderly application commands use the FIFO mailbox. Connection termination, linked supervisor abort,
and administrative shutdown are control-plane interruption, not priority mailbox messages. The
connection supervisor closes or interrupts the session scope directly, allowing a nonterminating
callback and queued commands to be abandoned under structured cleanup.

Typed dispatch closures hide heterogeneous message types. A binding lookup returns an operation that
knows how to invoke the exact root or component transition. The session kernel does not receive an
`Any` and then use a `ClassTag` to rediscover its intended handler.

### Session states

Lifecycle phases are explicit:

```scala
enum SessionState[Model]:
  case Bootstrapping(bootstrap: BootstrapState[Model])
  case Active(epoch: Epoch, committed: Committed[Model])
  case Navigating(epoch: Epoch, pending: PendingNavigation[Model])
  case Closing(epoch: Epoch, committed: Option[Committed[Model]])
  case Crashed(epoch: Epoch, failure: SessionFailure)
  case Closed(epoch: Epoch)
```

Terminal states reject new model commands deterministically. Crash state is visible to tracing and
supervision even if the protocol keeps the lifecycle entry available for a reconnect handshake.

### Committed state

One value represents the complete framework-owned state visible between turns:

```scala
final case class Committed[Model](
  model: Model,
  url: URL,
  render: CommittedRender,
  components: ComponentForest,
  hooks: HookRegistry,
  flash: FlashState,
  streams: StreamRegistry,
  uploads: UploadRegistry,
  resources: ResourceIndex,
  navigation: NavigationState,
  revision: TurnRevision
)
```

Large substructures remain focused types, but they are parts of one committed state rather than
independently committed references.

## Lifecycle Turn Transaction

A normal turn follows this state machine:

```text
dequeue command
  -> validate lifecycle epoch and command state
  -> resolve typed target against committed bindings
  -> run raw and typed hooks
  -> run root or component handler
  -> produce model and turn-local operation journal
  -> evaluate component forest, render graph, and nested requirements
  -> prepare inactive topology registrations and an outbound batch slot
  -> finalize the candidate tree with prepared registration data
  -> run after-render hooks against the candidate journal
  -> validate identities, preparations, and resource operations
  -> compute diff against the committed render state
  -> enter interruption-masked commit tail
  -> replace committed session state
  -> activate gated resources and prepared topology
  -> mark retired resources stale
  -> publish the ordered output batch into the reserved slot as the final commit-tail operation
  -> finalize retired resources outside the commit tail
```

### Turn draft

Lifecycle contexts write to a turn-local draft rather than global runtime stores:

```scala
final case class TurnDraft[Model](
  model: Model,
  componentChanges: ComponentChanges,
  hookChanges: HookChanges,
  flashChanges: FlashChanges,
  streamChanges: StreamChanges,
  uploadChanges: UploadChanges,
  navigation: Option[NavigationCommand],
  resourceOperations: Vector[ResourceOperation],
  componentOutputs: Vector[BoundComponentOutput],
  clientEvents: Vector[ClientEvent]
)
```

At most one navigation command is accepted in a turn. Conflicting operations fail the draft before
commit.

### Candidate resources

Graph nodes, component instances, and any resources that must be allocated before validation belong
to a candidate child scope. A failed candidate closes that scope. A successful commit transfers or
retains ownership under the session scope.

Async tasks and subscriptions requested by a callback are journaled and activated only when their
owning turn commits. An immediately completing task therefore cannot deliver a result before the
state that started it is committed. Candidate workers may be allocated behind a closed gate; commit
activation only opens that gate and is an infallible `UIO` operation. Acquisition that can fail
happens before commit.

Component outputs emitted during a component callback are appended to the bounded turn journal. They
become commands in the session fiber's private continuation worklist after commit. A callback never
blocks offering output to the same mailbox whose only consumer is currently running that callback.
Exceeding the per-turn continuation bound fails the candidate before commit.

### Commit and publication

Before commit, the session obtains a scoped reservation for one ordered outbound batch and an
inactive topology preparation when the render declares nested views. A batch may contain a reply,
diff, navigation, and other protocol messages whose relative order belongs to one turn. These
reservations can fail or time out without changing committed state.

The commit tail runs under `uninterruptibleMask` and contains no unbounded wait or fallible resource
acquisition. It replaces session state, opens staged resource gates, activates the topology
preparation, invalidates retired owner epochs, and publishes into the already reserved outbound slot
as one ordered batch and its final operation. Activation operations are idempotent and keyed by
lifecycle epoch and turn revision.

A defect during this tail terminates the connection and closes both old and candidate scopes; the
candidate output is not published unless the final reserved-slot operation completed. After a
successful tail, stale owner epochs prevent retired resources from delivering messages while their
finalizers run.

State activation precedes network writing. Once a turn is committed, a later transport failure does
not restore the previous model. The session crashes or closes according to transport supervision
policy, and a later ordinary rejoin mounts new state.

This boundary provides runtime-state atomicity, not database transactionality or exactly-once effects.

## Navigation State

Navigation is part of `SessionState`, not a side reference.

```scala
final case class PendingNavigation[Model](
  id: NavigationId,
  sourceUrl: URL,
  destination: LiveLocation,
  kind: NavigationKind,
  committed: Committed[Model],
  stagedModel: Model,
  flash: NavigationFlash,
  deferred: Vector[DeferredSessionCommand],
  deadline: Instant
)
```

The outbound navigation payload is correlated with `NavigationId` internally. A live-patch
acknowledgement must match the active destination and lifecycle epoch before parameter handling can
consume the staged model.

While patch navigation is pending, the session continues draining its mailbox but appends ordinary
browser events, server messages, async completions, subscription values, and component outputs to the
bounded `deferred` queue without executing them. This preserves their arrival order and prevents an
intervening turn from changing the staged model before parameter handling.

A stale or mismatched patch is rejected or answered without consuming pending state. The matching
patch runs parameter handling against `stagedModel`. After that turn commits, deferred commands return
to the session fiber's private continuation worklist in FIFO order.

Parameter handling for the matching patch may request another patch. That follow-up receives a new
`NavigationId`, replaces the acknowledged pending navigation, retains the deferred queue, and is
subject to the configured redirect-chain bound. Live navigation or full redirect ends the current
patch state according to its terminal navigation semantics.

If the deferred queue reaches its bound, normal session-mailbox overload policy applies. If the
matching patch does not arrive before `deadline`, the session terminates and relies on ordinary
reconnect/remount behavior. Connection shutdown and supervisor abort interrupt the session scope
immediately rather than waiting behind deferred work.

Live navigation and full redirects produce terminal or replacement outcomes instead of pretending
to be ordinary render turns.

## Render Engine

The render engine has four responsibilities:

1. Compile declarative typed HTML and signal structure into an immutable template program.
2. Evaluate that program against candidate inputs and retained scoped state.
3. Produce an immutable evaluated render tree and active binding table.
4. Compare evaluated trees and produce a protocol-independent render delta suitable for Phoenix
   encoding.

It does not own socket queues, navigation state, upload transport, channel topology, or network
publication.

### Template IR

The compiler produces a structured intermediate representation such as:

```text
Element(tag, staticAttributes, dynamicAttributes, children)
Text(staticValue | signal)
Choice(signal, retainedBranches)
Optional(signal, childTemplate)
Keyed(signal, keyFunction, rowTemplate)
Stream(streamHandle, rowTemplate)
Component(componentDefinition)
NestedView(nestedDefinition)
Binding(bindingTemplate)
Flash(kind, template)
```

HTML attributes remain attribute values. Dynamic attribute choices are never converted to synthetic
HTML and parsed back into strings.

### Evaluation

Evaluation produces:

```scala
final case class RenderCandidate(
  tree: EvaluatedTree,
  bindings: BindingTable,
  signalState: SignalEvaluation,
  componentChanges: ComponentChanges,
  nestedChanges: NestedTopologyChanges,
  resourceChanges: RenderResourceChanges,
  stagedScope: CandidateScope
)
```

Signal transformations are sampled at most once per evaluation revision. Cached values are reused
when exact dependency revisions and Scala equality establish that the result is unchanged.

### Exact revisions and equality

Every evaluated node or slot carries deterministic revision information derived from:

- template identity;
- exact child revisions;
- signal dependency revisions;
- retained keyed row identity;
- exact scalar value equality; and
- component render revision.

A hash can index interned structures or provide a fast negative check. Equal hashes always fall back
to exact revision or structural comparison before an update is suppressed or a static template is
shared.

### Render tree and Phoenix diff

The evaluated tree represents HTML and runtime identity. It is not itself the Phoenix wire payload.

`TreeDiffer` compares the previous committed tree with the candidate tree and produces exact
semantic changes. `PhoenixDiffEncoder` then maps those changes into the static/dynamic fragment,
component, stream, title, event, and navigation shapes required by the Phoenix client.

Separating the semantic tree from the wire representation prevents protocol optimizations from
becoming correctness assumptions in graph evaluation.

### Disconnected HTML

The full HTML renderer consumes the same evaluated tree used by connected diffing. It adds root
layout, CSRF, session, and static-tracking metadata through structured document composition.

## Render Identity And Bindings

### Template identity

Template nodes receive collision-free runtime IDs when the template program is compiled. IDs need
only be unique within the owning lifecycle graph.

### Keyed row identity

Each keyed slot stores an exact map from domain key to retained row instance. A row instance owns:

- an opaque monotonic `RowId`;
- its child signal scope;
- its compiled row template;
- its active binding instances; and
- its last render revision.

Reordering retains the row ID. Removal retires the row scope after commit. Reintroduction after
removal creates a new row ID unless the API explicitly defines retention.

Domain-key hashing uses normal map collision handling and exact equality. Domain-key stringification
is only diagnostic text.

### Binding identity

A binding instance is derived from exact retained runtime identity:

```text
BindingId = lifecycle epoch + template binding slot + optional row/component instance ID
```

The transport encoding may shorten or encode this value, but collisions are detected before the
candidate commits. Binding table insertion never silently replaces an existing handler.

The binding table stores a typed dispatch operation. Browser payload decoding occurs inside that
operation, so a successfully resolved binding cannot later target an unrelated message type.

## Component Architecture

Components remain part of the owning session's committed state. They do not receive independent
network mailboxes.

The component forest owns:

- exact `(component definition, application ID)` identity;
- props and model;
- hook state;
- a retained component render program;
- component-scoped resource ownership;
- output routing; and
- protocol CID assignment as an adapter concern.

Root and component callbacks use one transition coordinator. Differences are supplied through a
typed node adapter rather than duplicated lifecycle control flow.

The heterogeneous registry uses a sealed existential boundary:

```scala
trait MountedComponent:
  type Props
  type Msg
  type Model

  def transition(
    command: ComponentCommand[Msg],
    state: ComponentState[Props, Model],
    context: ComponentTurnContext[Props, Msg, Model]
  ): LiveIO[ComponentDraft[Props, Model]]
```

Erasure is handled when a typed component is packed into or unpacked from this adapter. Internal
component code does not repeatedly cast application models and messages.

Component mount, update, message handling, after-render hooks, graph changes, and resource operations
remain candidate state until the parent session turn commits.

## Nested LiveView Architecture

A nested LiveView is an independent `SessionKernel` supervised by the connection.

The parent render candidate emits declarative child requirements:

```scala
final case class NestedRequirement(
  applicationId: NestedViewId,
  definition: NestedDefinition,
  sticky: Boolean,
  linkParentOnCrash: Boolean
)
```

The render engine does not start or stop sockets. After the parent candidate validates, the session
asks the connection topology coordinator to prepare the difference between committed and candidate
requirements. The preparation returns signed registration data needed to finalize the parent's
rendered markup, but remains invisible to joins until the parent commit tail activates it.

The preparation contains registration operations, not child-session start operations:

```text
retain active registration or joined child
prepare join registration with new epoch
replace join registration
revoke registration and retire joined child subtree
prepare sticky child reattachment
```

Activating the preparation records registrations against the exact parent lifecycle ID and turn
revision. It does not mount a new child. The Phoenix client first receives the parent output and then
sends the nested join containing its join metadata, URL, connect parameters, and authorization
context. A valid join either creates a fresh child `SessionKernel` and records its lifecycle ID or,
for a matching retained sticky child, reattaches that existing kernel and returns its current
rendered tree.

If the browser never joins, the registration remains inert and consumes no child session resources.
A stale join after parent replacement is rejected by registration epoch and token. Revocation makes
the registration unavailable immediately; any already joined child subtree is marked stale at
commit and finalized afterward.

The prepare and activate protocol coordinates the session and connection owners. A failed parent
candidate releases its preparation. A commit-tail activation defect is terminal for the connection,
so parent output is never published with an uninstalled registration.

## Managed Resources

Async tasks, subscriptions, streams, uploads, hooks, and component outputs use one owner model:

```scala
enum OwnerId:
  case Root(lifecycle: LifecycleId)
  case Component(lifecycle: LifecycleId, component: ComponentInstanceId)
```

Every resource also has a typed domain key and an owner epoch. Replacement, cancellation, completion,
and cleanup validate both.

### Resource registry

The session resource registry records handles and policies, while concrete resources remain owned by
ZIO scopes. Removing an owner closes all resources under that owner through one operation rather
than feature-specific cleanup spread across event handlers.

### Async work

Async tasks are forked under the session or component owner scope, never as unowned daemon fibers.
Completion enqueues a typed session command. Replacing a task invalidates the previous task token so a
late completion cannot affect current state.

### Subscriptions

Subscription stream consumption belongs to an owner scope. Values return through the session
mailbox. A subscription may declare an explicit delivery policy such as lossless or latest-value
conflation; the runtime never silently chooses conflation for an ordinary message stream.

### Streams

`LiveStream` remains an opaque render-oriented handle, not application business state. Stream
snapshots and emitted stream operations have distinct render representations instead of overloading
an ordinary keyed node with protocol-specific semantics.

### Uploads

Upload metadata, validation, progress, and application callbacks are coordinated through the session
kernel. Bulk chunks flow through dedicated bounded upload workers owned by the connection or upload
entry scope.

Writer state and completed results remain existential internally, but their erased representation is
contained inside one upload destination adapter.

## Backpressure And Queue Policy

Every queue is bounded and has an explicit policy.

| Queue | Target behavior |
| --- | --- |
| Connection ingress | Apply transport backpressure; reject malformed or unknown topics before lifecycle allocation |
| Session commands | Preserve ordering; backpressure external owned producers; close or reject abusive client input on sustained overflow |
| Turn continuations | Bound component outputs and other self-generated follow-up commands per turn; append without blocking the session fiber |
| Subscription delivery | Use the subscription's declared lossless or conflated policy |
| Async completion | Bounded delivery owned by the session; completion cannot mutate state outside the mailbox |
| Session output | Preserve committed reply/diff order; failure to enqueue is a terminal slow-client condition |
| Serial writer | Bounded FIFO; write failure fails pending sends and terminates the connection |
| Upload chunks | Bounded per-entry flow control with explicit protocol failure on overflow |

Arbitrary diffs are not safely conflatable because component, stream, navigation, and reply semantics
may depend on intermediate protocol state. When a client cannot consume required output within the
configured bound, terminating and remounting is safer than unbounded growth or silent loss.

Queue capacities are configuration and operational policy, not public API contracts.

## Failure And Supervision

Failure policy is explicit by source:

| Failure | Owner and outcome |
| --- | --- |
| Application handler or render failure | Session turn fails; previous state remains committed; session crashes unless recovered by application policy |
| Managed async task failure | Converted to its typed completion result; does not crash the session by default |
| Subscription stream termination | Follows declared subscription policy; may stop, retry, notify, or fail the owner |
| Component callback failure | Parent session turn fails because component state is part of that transaction |
| Nested child failure | Connection supervisor applies the child's declared parent-link policy using lifecycle epochs |
| Upload writer failure | Upload entry fails and is cleaned up; application receives the supported upload failure path |
| Candidate resource acquisition failure | Candidate turn fails and closes its staged scope before commit |
| Commit-tail activation defect | Connection terminates; prepared state and all connection-owned scopes are closed; candidate output is not published |
| Protocol decode failure | Adapter rejects the message; severe or repeated violations may close the connection |
| Serial writer or transport failure | Connection scope terminates and closes all owned lifecycles, including detached sticky children, and all resources |
| Queue overflow | Apply that queue's documented rejection or terminal slow-consumer policy |

Scopes define ownership. Supervision policy defines whether a failure is converted, isolated,
propagated, or terminal. The two concepts are not conflated.

## Module And Dependency Boundaries

The target has one-way logical dependencies. In the following list, `A -> B` means that `A` depends
on `B`:

```text
scalive.render             -> scalive.api
scalive.runtime.resources  -> scalive.api
scalive.runtime.topology   -> scalive.api
scalive.runtime.kernel     -> scalive.api
scalive.runtime.kernel     -> scalive.render
scalive.runtime.kernel     -> scalive.runtime.resources
scalive.runtime.connection -> scalive.runtime.kernel
scalive.runtime.connection -> scalive.runtime.topology
scalive.runtime.connection -> scalive.runtime.resources
scalive.protocol.phoenix   -> protocol-neutral runtime ingress/egress contracts
scalive.protocol.phoenix   -> scalive.render delta contracts
scalive.transport.ziohttp  -> scalive.protocol.phoenix
scalive.transport.ziohttp  -> scalive.runtime.connection
scalive.testing            -> scalive.api and supported runtime harnesses
```

These may be source packages or separately compiled modules. The architectural requirement is that
dependency direction is enforced rather than relying only on `private[scalive]` visibility.

### `scalive.api`

Owns public lifecycle traits, contexts, typed HTML definitions, signals, routes, locations, forms,
resource declarations, and commands. It contains no WebSocket frames, protocol payloads, queues, or
runtime state stores.

### `scalive.render`

Owns template IR, signal evaluation, render scopes, evaluated trees, binding instances, exact diffing,
and full HTML rendering. It does not import channel or socket implementations.

### `scalive.runtime.kernel`

Owns session commands, session states, turn drafts, commit coordination, component forests, lifecycle
failure, and tracing boundaries.

### `scalive.runtime.resources`

Owns owner identities, scoped resource registries, async and subscription execution, stream command
state, upload control state, and unified cleanup.

### `scalive.runtime.topology`

Owns root/nested parent-child relationships, lifecycle epochs, sticky attachment, join reservations,
and topology deltas. It does not run model transitions.

### `scalive.runtime.connection`

Owns the connection supervisor, lifecycle registry, prepared topology and outbound reservations,
topic routing, and serial output coordination. It depends on protocol-neutral ingress and egress
contracts rather than Phoenix payload classes.

### `scalive.protocol.phoenix`

Owns wire codecs and the mapping between internal commands/deltas and the Phoenix LiveView protocol.

### `scalive.transport.ziohttp`

Owns ZIO HTTP routes, WebSocket integration, cookies, CSRF transport, static assets, and server
startup adapters.

## Testing Architecture

The target supports tests at each boundary without exposing arbitrary implementation state.

### Render tests

Render tests verify:

- template compilation;
- exact revision propagation;
- keyed row retention and disposal;
- duplicate identity rejection;
- binding stability and uniqueness;
- hash-collision independence;
- component and stream deltas;
- full HTML equivalence; and
- Phoenix diff encoding as a separate concern.

Property tests generate trees, keys, reorderings, and known hash collisions to prove that optimized
and unoptimized diff paths produce equivalent semantic output.

### Session-kernel tests

A deterministic in-memory harness submits commands and observes committed state, output, resource
operations, and finalizers. It covers navigation interleaving, failed candidate rollback, component
atomicity, component-output ordering and bounds, stale epochs, queue pressure, interruption before
and during the commit tail, activation defects, and crash states without constructing raw private
refs.

### Connection tests

A connection harness exercises joins, leaves, sticky children, topology replacement, stale topics,
upload ownership, serial output, and transport failure. Nested cases include a committed requirement
that is never joined, a stale join after replacement, join metadata unavailable before the browser
join, supervisor cancellation around prepare and activate, and cleanup of detached sticky children
when the connection closes.

### Public connected harness

`scalive-testing` provides a supported connected harness alongside disconnected rendering. It can
join finalized routes, send browser-equivalent events, inspect semantic output, advance managed work,
and assert cleanup without tying application tests to internal socket classes.

### Protocol and browser tests

Protocol fixtures and upstream Phoenix end-to-end tests remain the compatibility boundary. Internal
render tests do not substitute for browser behavior tests.

## Observability

Every connection, lifecycle, command, turn, navigation, render, and resource operation has a stable
runtime correlation identity.

The session kernel emits structured events at these boundaries:

```text
command accepted
turn started
handler completed
candidate render started
candidate validated
diff completed
state committed
output enqueued
resource activated or retired
turn failed
session crashed or closed
```

Events include lifecycle ID, epoch, turn revision, command kind, initiator, duration, queue depth, and
outcome. Application models and browser payloads are redacted or projected through explicit tracing
contracts.

Metrics cover mailbox saturation, command latency, render latency, diff size, output backlog, active
resources, reconnects, crashes, and slow-client termination.

Observability is attached to transition boundaries rather than scattered through protocol and
feature-specific branches.

## Security Boundaries

The target preserves these security rules:

- Disconnected route authorization runs before the one-shot lifecycle; that lifecycle creates the
  signed live session and CSRF bootstrap data.
- CSRF and signed-session validation occur before a connected root lifecycle is created.
- A nested join validates its token, expected topic, parent lifecycle, and epoch.
- Client topics cannot allocate arbitrary workers or resources before ownership validation.
- Binding IDs are opaque routing capabilities scoped to a lifecycle epoch.
- Binding payloads, forms, URLs, connect parameters, and upload metadata remain untrusted input.
- Typed message bindings provide server-side type safety but do not make browser data trustworthy.
- Flash and mount claims remain integrity-protected transport data, not encrypted storage.
- Protocol identifiers are never authorization decisions without server-owned registration state.

## Architectural Tradeoffs

### Single-owner sessions versus parallel transitions

Serializing model transitions limits one LiveView to one active turn. This is intentional. LiveView
state is sequential, and parallel mutation would require conflict resolution while making browser
ordering harder to reason about.

Long-running work belongs in managed async resources that return results through the mailbox.

### Retained graph state versus reconstruction

The retained render program and row/component scopes consume more memory than full reconstruction.
They reduce repeated compilation, allocation, and payload work while preserving stable bindings and
identity. The existing performance record supports retaining this tradeoff.

### Commit before network delivery

Committing before output means transport failure can leave external effects and committed in-memory
state that the browser never observed. Rolling state back after an uncertain write would be worse and
could duplicate application effects. Reconnect creates a fresh ordinary lifecycle from durable input.

### Phoenix compatibility versus internal independence

Scalive must emit Phoenix-compatible payloads, but the wire format does not define the render engine,
component storage, lifecycle state, or supervision model. Compatibility remains an adapter boundary.

### Typed APIs versus heterogeneous runtime storage

Perfect compile-time typing across a registry of unrelated component, upload, and message types is
not possible without existential packaging. The target accepts one narrow erased boundary and keeps
typed operations on both sides of it.

## Target Architecture Summary

The target runtime is organized around two supervised state owners:

- a connection supervisor owns transport, lifecycle topology, upload routing, and serialized output;
- a session kernel owns every transition and committed state for one root or nested LiveView.

Each lifecycle turn produces a candidate model, component forest, render tree, hook state, nested
requirements, and resource journal. Cross-owner topology and output changes are prepared as inactive
scoped reservations. The session state and those preparations become externally active through one
bounded, interruption-masked commit protocol. Only then is protocol output published.

The render engine retains Scalive's compile-once signal graph but replaces probabilistic correctness,
stringified identity, mutable cross-system commit actions, and protocol-shaped rendering with exact
revisions, retained runtime identities, scoped candidates, structured render IR, and a separate
Phoenix encoder.

This shape preserves the aspects of Scalive that are already distinctive and valuable: a small typed
API, immutable server-owned state, pure signal projection, efficient incremental updates, idiomatic
ZIO effects, and Phoenix client compatibility. It changes the internal execution model so those
properties are explicit, bounded, and mechanically enforceable.
