{%
title = "Runtime architecture"
description = "How Scalive moves from an HTTP render to connected, serialized state transitions, retained rendering, protocol diffs, and scoped cleanup."
order = 3
section = project
%}

## Runtime At A Glance {#runtime-at-a-glance}

Scalive is a server-owned UI runtime. A `LiveView[Msg, Model]` keeps its model in
the JVM, handles typed inputs, renders a structured HTML tree, and sends
incremental changes to the browser. The browser owns the DOM and browser-local
behavior, but it does not maintain a second copy of the application model.

The runtime is built around four boundaries:

- An initial HTTP render and a connected WebSocket lifecycle are independent
  executions. The browser keeps the HTML from the first, but the server does not
  carry that request's model into the second.
- Each connected LiveView has one lifecycle kernel that serializes state
  transitions and owns one committed revision at a time.
- Rendering and resource acquisition are provisional until the entire turn is
  ready to commit. A failed candidate never becomes partially active.
- The lifecycle and rendering core are protocol-neutral. Phoenix-specific frame
  decoding and diff encoding happen at the edge of the runtime.

The two principal paths through the system are:

```text
HTTP GET -> route -> disconnected mount -> full render -> HTML response -> close

WebSocket frame -> protocol decode -> connection ingress -> lifecycle kernel
                -> render candidate -> commit -> protocol encode -> browser
```

The first path runs once per HTTP request. The second path becomes a loop: every
browser event, application message, subscription value, async completion, or
upload update enters the same serialized transition machinery.

For the application-facing programming model, start with
[Learn](../learn/index.md#start-here). This page goes beneath that API to explain
the runtime structures that make the model work. Internal names describe the
current implementation, not additional supported application APIs.

## Runtime Layers {#runtime-layers}

The runtime separates application semantics, lifecycle ownership, rendering,
wire protocol, and transport. That prevents Phoenix frame shapes or ZIO HTTP
details from leaking into model transitions and tree evaluation.

These modules are internal compile and test boundaries, not separately published
coordinates. Scalive publishes and supports exactly two Scala coordinates:
`dev.scalive::scalive`, containing all production API, render, runtime, protocol,
and transport classes, and the optional test-support coordinate
`dev.scalive::scalive-testing`.

| Layer | Concrete module | Responsibility |
| --- | --- | --- |
| Application model | `scalive.api` | `LiveView`, `LiveComponent`, routing, lifecycle contexts, typed HTML, forms, streams, and uploads |
| Retained rendering | `scalive.render` | Compiled render programs, signal evaluation, semantic trees, bindings, and exact deltas |
| Shared runtime contracts | `scalive.runtime.contracts` | Typed identities, cleanup contracts, topology coordinates, and ordered outbound reservations |
| Candidate-owned state | `scalive.runtime.resources` and connection-owned topology support | Prepared resources, upload state, nested registrations, and commit-time activation plans |
| Lifecycle state machine | `scalive.runtime.kernel` | Serialized commands, provisional turns, committed revisions, rendering, and lifecycle state |
| Connection ownership | `scalive.runtime.connection` | Physical connection supervision, root and nested lifecycles, ingress, output, components, and upload workers |
| Client protocol | `scalive.protocol.phoenix` | Phoenix envelope parsing and projection of semantic trees and deltas into Phoenix payloads |
| HTTP and WebSocket transport | `scalive.transport.zio-http` | GET routes, bootstrap data, join admission, security checks, frame limits, and socket dispatch |

Input moves from transport toward a lifecycle kernel. Application callbacks and
render evaluation happen behind that kernel. Output then moves back through the
connection layer, Phoenix projection, a serialized physical writer, and finally
the browser. The kernel does not know whether a delta will be encoded as a
Phoenix payload, and the render engine does not know about WebSockets.

## Two Independent Lifetimes {#two-independent-lifetimes}

A routed page executes once to produce useful HTML and again when the browser
establishes a live connection. These executions may use the same route and
session inputs, but they do not share a model, render program, or resource scope.

@:diagram(runtime-ownership)

### Disconnected HTTP Render {#disconnected-http-render}

`Live.router(...)` produces a `LiveApplication`: a declarative set of typed
routes, live sessions, layouts, mount aspects, and a socket path.
`ZioHttp.routes(application, config)` validates that catalog and exposes ordinary
GET routes alongside the WebSocket endpoint.

For a GET, the selected route decodes typed path and query parameters, applies
session and route mount aspects, and creates a request-owned lifecycle with
`Connection.Disconnected`. A `DisconnectedRootTurn` runs mount, initial parameter
handling, hooks, flash, and redirect handling.

The render engine evaluates the resulting model, resolves disconnected
components, and produces the complete page inside the live and root layouts. The
response also carries CSRF metadata and signed session and static claims needed
to authenticate the later socket join.

Once the response is complete, the request closes its component, render, and
prepared-resource scopes, and the temporary model is no longer retained. The
rendered DOM, CSRF cookie and metadata, and signed bootstrap data remain with the
browser.

### Connected Bootstrap {#connected-bootstrap}

The browser's Phoenix `LiveSocket` opens the configured WebSocket endpoint and
sends a join. The socket route owns one physical writer, one
`ConnectionSupervisor`, and connection-local protocol registries. Text frames
are decoded by `PhoenixProtocol`; upload binary frames use bounded workers.

Before any application lifecycle is constructed, admission validates the root
or nested topic, signed session and static claims, canonical URL, route and live
session identity, layout and mount markers, and the CSRF cookie/token pair. A
nested join must also match an active topology registration prepared by its
parent. Invalid or stale joins are rejected without mounting application state.

After admission, the transport creates a new connected lifecycle and asks the
supervisor to start a `RootConnection`. Mount and initial parameter handling run
again with `Connection.Connected`, producing a fresh model. Its initial delta is
reconciled with the DOM left by the HTTP response; it does not resume the
disconnected render transaction.

State that must survive this boundary or a later reconnect belongs in signed
inputs, an injected service, or durable storage. A connected model itself is
in-memory and scoped to one lifecycle.

## One Connected Lifecycle {#connected-lifecycle}

A physical WebSocket may carry a root LiveView and multiple nested LiveViews.
The `ConnectionSupervisor` owns that topic topology and the scopes of all joined
lifecycles. Each lifecycle receives its own `RootConnection`, which adapts
connection events to the protocol-neutral kernel and adapts committed output
back toward the socket.

A `RootConnection` contains:

- bounded command ingress;
- one `SessionKernel` and its committed lifecycle state;
- an outbound loop that drains ordered reservations;
- component and nested-lifecycle coordination;
- upload control state and bounded chunk processing; and
- lifecycle-owned fibers, promises, and cleanup.

Browser events, typed application messages, parameter patches, async and
subscription completions, component work, and upload mutations all become
epoch-qualified `SessionCommand` values. The epoch prevents delayed work from an
older lifecycle generation from mutating a replacement lifecycle.

The kernel dequeues and processes one command at a time:

@:diagram(runtime-connected-turn)

1. The command is checked against the current lifecycle state and resolved to a
   root, component, navigation, upload, or managed-resource operation.
2. Connected-turn guards run before application callbacks. Success continues;
   a controlled halt, redirect, reload, or disconnect leaves the committed
   revision unchanged and skips hooks, handlers, rendering, and diff generation.
3. Hooks and the target handler produce a provisional `TurnDraft`: a proposed
   immutable model plus effects and journaled runtime operations.
4. The retained render program evaluates that model and prepares the root and
   component candidates.
5. The kernel stabilizes the component graph, validates stream and upload
   requirements, and prepares resources and nested topology.
6. It reserves ordered outbound capacity before state can commit.
7. After-render work and continuations run, then `TreeDiffer` computes the
   protocol-neutral delta from the previous committed tree.
8. The commit replaces framework-owned state, retires previous owners, activates
   prepared resources and topology, and publishes output into the reservation.
9. The connection's outbound loop drains that output through
   `PhoenixRenderedEncoder` and the serialized physical writer.

This is the same turn model whether the original input came from the browser or
from server-side work. The initiator changes, but state ownership and commit
rules do not.

## Retained Rendering {#retained-rendering}

`LiveView.view` describes a view graph rather than rendering a fresh HTML string
for every message. `RenderProgram.compile` invokes it once for a disconnected
request or connected lifecycle and retains the static template, signal graph,
binding definitions, and child structure.

`Signal[Model]` is the bridge from immutable model state into that graph. Pure
`map` and `zip` transformations derive the values needed by individual nodes.
During a turn, the render engine installs the proposed model as the signal
source, samples the graph, and reuses unchanged retained nodes.

Evaluation produces a `RenderCandidate` containing:

- an immutable `EvaluatedTree`;
- sampled signal revisions and binding tables;
- component, nested-LiveView, and stream requirements; and
- a candidate scope for resources discovered during rendering.

Signal revisions are an optimization hint, not equality proof. `TreeDiffer`
compares retained structure and exact identity before emitting text, attribute,
replacement, component, or stream changes. The result is a semantic
`RenderDelta`; Phoenix statics, dynamics, component CIDs, and stream references
do not appear until protocol encoding.

## Commit Is The State Boundary {#commit-boundary}

The `SessionKernel` is the sole owner of lifecycle state. Revision N remains the
committed state while a handler, render evaluation, resource preparation, and
delta calculation construct a possible revision N+1.

Prepared resources remain behind activation gates. Nested registrations remain
in immutable topology plans. Component candidates remain separate from the
committed component forest. If preparation fails or becomes stale, those
candidates are closed and revision N is never partially replaced. An unhandled
turn failure also closes the active owner and crashes the lifecycle rather than
continuing to process commands from revision N.

Only the interruption-masked commit tail installs N+1. It replaces the model,
render state, components, streams, uploads, resources, and topology as one
framework-owned transition. Previous owners are marked stale before finalizers
run, preventing late completions from re-entering the new revision.

Output encoding and network I/O deliberately happen after commit. Ordered
reservations preserve publication order and ensure capacity exists before the
state transition. If encoding or the physical write fails, the connection
closes; the runtime does not attempt to restore N after N+1 has become active.

This is framework-state atomicity, not database transactionality. A service
call, database write, or other external effect completed by an application
handler is not rolled back if later rendering or publication fails.

## Components And Nested LiveViews {#children}

Components and nested LiveViews both render inside a page, but they have
different ownership and concurrency models.

| | Stateful component | Nested LiveView |
| --- | --- | --- |
| State | Local props, model, hooks, bindings, and resources | Independent lifecycle model and committed revision |
| Command processing | Part of the parent kernel's turn | Own bounded ingress and `SessionKernel` |
| Commit | Commits with the complete parent component forest | Commits independently from its parent |
| Network identity | Phoenix CID assigned during projection | Separate Phoenix topic on the shared WebSocket |
| Lifetime | Parent render and component ownership | Supervisor child scope and topology registration |

`ComponentRuntime` repeatedly prepares component candidates until the complete
root and component graph is stable. The parent kernel then commits that forest
together. A Phoenix CID identifies the component in a wire payload; it is not a
runtime process or ownership identity.

A nested LiveView instead owns another `RootConnection`, kernel, ingress queue,
revision, and set of resources. Parent rendering declares a nested requirement,
and the topology subsystem prepares a registration tied to exact parent,
lifecycle, and epoch identities. The registration becomes joinable only after
the parent commit. Non-retained descendants are retired with their parent;
sticky descendants may detach and later attach to an eligible root.

## Bounded Work And Ordered Output {#bounded-work}

The runtime does not rely on unbounded work queues. `ConnectionConfig` defines
positive capacities for root ingress, kernel mailboxes, continuations, outbound
reservations, physical writers, and upload chunks. The ZIO HTTP assembly also
caps WebSocket and upload frame sizes.

These bounds are part of lifecycle semantics:

- External producers receive explicit saturated or closed results when their
  work cannot be admitted.
- Runtime-owned producers backpressure when dropping an item would lose a
  lifecycle transition.
- A reserved kernel path admits the matching browser patch acknowledgement even
  while ordinary commands are deferred behind navigation.
- `OutboundReservations` acquires a per-lifecycle publication position before
  commit, preserving accepted output order within that lifecycle.
- `SerialWriter` performs physical writes one at a time and turns writer failure
  into connection shutdown, including when multiple lifecycles share the socket.

While a live patch is awaiting browser acknowledgement, the kernel records the
navigation identity and lifecycle epoch, defers ordinary commands in a bounded
queue, and applies a timeout. Only the matching URL acknowledgement resumes
parameter handling. This prevents both stale acknowledgements and a full normal
mailbox from deadlocking navigation.

## Scoped Resources And Cleanup {#scoped-resources}

Runtime ownership follows nested scopes rather than global registries:

| Owner | Principal state and resources |
| --- | --- |
| WebSocket scope | `ConnectionSupervisor`, physical writer, joined topics, protocol registries, and upload joins |
| Supervisor child scope | One root or nested `RootConnection` and its linked descendants |
| Root connection scope | Kernel, ingress and outbound loops, promises, uploads, and retained render program |
| Committed revision | Active component and stream-row scopes plus resources activated by that revision |
| Candidate scope | Isolated prepared resources that activate at commit or close on rollback |
| Component owner | Component async tasks, subscriptions, streams, uploads, and child render scopes |

Managed async tasks and subscriptions capture the owner, lifecycle epoch, and
resource identity that created them. Their completions return through ordinary
command ingress and are ignored or rejected once that ownership is stale.
Uploads use the same lifecycle control plane while bounded workers handle binary
chunks outside the kernel's serialized transition loop.

Closing a scope shuts down queues and reservations, interrupts owned fibers,
retires uploads and managed resources, closes render programs, and revokes
topology registrations. `RuntimeCleanup` composes finalizers so one cleanup
failure does not prevent the remaining owners from being released.

A disconnect therefore ends the connected model and its connection-owned work.
A later join mounts a new lifecycle rather than recovering the old model object.

## Failure And Observation {#failure-and-observation}

Failures are handled according to which side of commit they occur on:

| Failure point | Runtime consequence |
| --- | --- |
| Admission | Reject the join without constructing application lifecycle state |
| Handler, render, validation, or preparation | Do not install the candidate; close its provisional and active owners, then crash the lifecycle |
| Commit defect | Treat the lifecycle as crashed because framework ownership may no longer be safely continued |
| Protocol encoding or socket write after commit | Close the connection without rolling back the committed revision |
| Disconnect or leave | Close the relevant lifecycle scopes and require a fresh mount on rejoin |

The runtime emits correlated internal events for command acceptance, turn start,
handler completion, candidate rendering, validation, diffing, commit,
publication, resource activation and retirement, failure, and termination.
Correlation carries connection, lifecycle, epoch, command, turn, revision,
navigation, and resource identities where they apply. Observation follows the
same boundaries as ownership; it does not participate in transition decisions.

## The Protocol Edge {#protocol-edge}

`PhoenixProtocol` strictly parses Phoenix channel envelopes and converts joins,
events, forms, patches, leaves, heartbeats, and upload operations into connection
inputs. The connection layer then resolves those protocol values into typed
runtime identities and commands before application code runs.

On output, `PhoenixRenderedEncoder` projects full trees and sparse deltas into
Phoenix statics, dynamics, component maps, stream operations, events, replies,
and navigation payloads. Component CIDs and stream references are
connection-local projection state, not identities used by the kernel to own
models or resources.

ZIO HTTP currently supplies the concrete HTTP and WebSocket transport, and the
browser uses the Phoenix LiveView JavaScript client. Long-poll transport and
cross-process migration of connected lifecycle state are not part of this
runtime. The protocol-neutral kernel and render boundaries keep those transport
choices outside application state transitions.

The central invariant is therefore simple: a connected lifecycle has one owner
and one committed revision. Every input proposes a complete next revision,
rendering and resources remain provisional until commit, and only committed
output crosses the protocol boundary to the browser.
