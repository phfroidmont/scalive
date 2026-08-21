# Upstream Compatibility Matrix

Scalive tracks Phoenix LiveView behavior and feature coverage while keeping the Scala API
ergonomic, typed, and robust. Compatibility means observable behavior and feature-set parity, not
internal implementation parity or an Elixir-shaped API.

The current upstream target is Phoenix LiveView `v1.1.28`, pinned by `flake.lock`. The Milestone-11
runtime is now the implementation under `scalive/api`, `scalive/render`, `scalive/runtime/*`,
`scalive/protocol/phoenix`, and `scalive/transport/zio-http`; it is no longer a planned replacement
for a legacy runtime. The `scalive/testing` module also exercises both disconnected and connected
lifecycles through production route admission and connection supervision.

The detached legacy baseline in [`docs/runtime-parity-manifest.md`](docs/runtime-parity-manifest.md)
remains a frozen historical oracle. Its passing results do not establish current target behavior.
The latest current-tree browser result is recorded below; the strict cutover command remains the
release gate for subsequent changes.

## Status Legend

| Status | Meaning |
| --- | --- |
| Target evidence | Implemented in the Milestone-11 modules and covered by native suites. |
| Target evidence, audit pending | Core target behavior has native coverage; exact upstream edge-case mapping still needs an audit. |
| Partial | Some behavior exists, but the upstream feature area is not complete or fully mapped. |
| Intentional divergence | Scalive deliberately exposes a Scala-first typed contract. |
| Out of scope | No direct equivalent is planned because the concept is Phoenix/Elixir-specific. |
| Browser gate | Current status is determined by `./scripts/e2e-run-upstream-cutover.sh`, which requires three complete consecutive runs with retries disabled. |

Paths below are repository-relative and identify representative evidence rather than every test.

## Compatibility Matrix

| Area | Status | Current target evidence | Remaining gate / decision |
| --- | --- | --- | --- |
| Browser E2E behavior | Browser gate | On 2026-08-21 the pinned suite completed three consecutive 172-test runs with no retries in 27.2, 26.8, and 26.3 seconds. `scripts/e2e-run-upstream-cutover.sh`, the revision-specific synchronization patch under `test/upstream-patches`, and `e2eApp/src` are the executable evidence. | Run the complete suite once in snapshot CI, keep the strict cutover command for explicit cutover verification, and update the synchronization patch when advancing the upstream pin. |
| Public lifecycle and connection capabilities | Target evidence | `scalive/api/src/scalive/lifecycle`, `scalive/api/test/src/scaliveapi/ConnectionCapabilitiesSpec.scala`, `RoutedConstructionSpec.scala`, and `ComponentApiSpec.scala` cover typed mount/message contexts, explicit `Connection.Disconnected`/`Connection.Connected`, routes, components, and capability gating. | Continue mapping callback edge cases without weakening compile-time capability boundaries. |
| Rendering and semantic diffs | Target evidence | `scalive/render/src/scalive/render` with `TreeDifferSpec.scala`, `RenderProgramSpec.scala`, `HtmlRendererSpec.scala`, `StreamRenderingSpec.scala`, and `NestedRenderingSpec.scala`. | Audit exact browser merge behavior for every upstream regression scenario. |
| Phoenix channel and rendered protocol | Target evidence, audit pending | `scalive/protocol/phoenix/src/scalive/protocol/phoenix` with `PhoenixProtocolSpec.scala`, `PhoenixRenderedEncoderSpec.scala`, `PhoenixUploadProtocolSpec.scala`, and `PhoenixProtocolFuzzSpec.scala` covers frames, refs, rendered projections, two-phase CID destruction/reintroduction, uploads, and malformed input. | Keep exact errors, reconnect generations, and protocol additions aligned with `v1.1.28`. |
| Static HTTP render and connected bootstrap | Target evidence | `scalive/transport/zio-http/test/src/scalive/ZioHttpSpec.scala`, `ZioHttpSecuritySpec.scala`, `scalive/testing/test/src/scalive/testing/{DisconnectedRenderSpec,ConnectedRenderSpec}.scala`, and the upstream error scenarios cover disconnected bootstrap, signed admission, connected mount, semantic HTML projection, crash recovery, and reconnect without fallback reload. | Preserve exact crash, reconnect, and stale-page behavior in the browser gate. |
| Routes, sessions, aspects, and layouts | Target evidence | `scalive/api/src/scalive/routing`, `RoutedConstructionSpec.scala`, `scalive/transport/zio-http/test/src/scalive/ZioHttpApiSpec.scala`, and `ZioHttpSpec.scala` cover typed declarations, validation, session boundaries, mount claims, and layout composition. | Document intentional differences from route actions, metadata, and private assigns. |
| Lifecycle hooks | Target evidence | `scalive/api/src/scalive/lifecycle/Hooks.scala`, `scalive/runtime/connection/src/scalive/runtime/connection/RootHookRuntime.scala`, `RootConnectionSpec.scala`, and `ComponentRuntimeSpec.scala`. | Audit all upstream hook stages and halt/error payloads. |
| Stateful components and updates | Target evidence | `ComponentApiSpec.scala`, `scalive/runtime/kernel/test/src/scalive/runtime/kernel/ComponentKernelSpec.scala`, `scalive/runtime/connection/test/src/scalive/runtime/connection/ComponentRuntimeSpec.scala`, and `DisconnectedComponentRendererSpec.scala`. | Decide whether delayed and batched typed update helpers improve the API. |
| Nested LiveViews | Target evidence | `NestedRenderingSpec.scala`, `NestedTopologyKernelSpec.scala`, `NestedTopologyRuntimeSpec.scala`, `ConnectionSupervisorSpec.scala`, and connected harness/browser coverage verify independent, sticky, and parent-linked child lifecycles. | Keep sticky/rejoin, linked-failure ordering, and browser cleanup behavior aligned. |
| Navigation, flash, and titles | Target evidence, audit pending | Typed navigation lives in `scalive/api`; lifecycle output is covered by `SessionKernelSpec.scala`, `RootConnectionSpec.scala`, `ZioHttpSpec.scala`, and `ConnectedRenderSpec.scala`. | Audit history, cross-session fallback, scroll, title ownership, and flash carryover in the browser gate. |
| Forms | Target evidence, audit pending | `scalive/api/src/scalive/forms`, `FormDefinitionApiSpec.scala`, `scalive/transport/zio-http/src/scalive/HttpFormDecoder.scala`, and both testing-module render suites cover typed decoding and connected change/submit dispatch. | Complete browser recovery, auto-recover, locked-state, and ordinary-action coverage. |
| Uploads | Target evidence, audit pending | `LiveUploadSpec.scala`, `UploadContextSpec.scala`, `UploadWorkerSpec.scala`, `PhoenixUploadProtocolSpec.scala`, `ZioHttpUploadSpec.scala`, and `ConnectedRender.upload`. | Audit auto-upload, external writer failure, cancellation, progress, and submit edge cases. |
| Streams | Target evidence, audit pending | `StreamOpacitySpec.scala`, `StreamRenderingSpec.scala`, `StreamStoreSpec.scala`, and `ManagedStreamsSpec.scala` cover opaque public state, rendering, mutation, limits, and lifecycle ownership. | Complete the pinned browser stream regression matrix and decide on an async convenience API. |
| Async tasks and subscriptions | Target evidence | `ManagedAsyncSpec.scala`, `ManagedSubscriptionsSpec.scala`, `SessionKernelSpec.scala`, and `ConnectionCapabilitiesSpec.scala` cover typed keys, replacement, delivery, and scoped cleanup. | A field-level async-assign helper remains an API decision, not missing runtime execution. |
| JS commands, client events, and DOM bindings | Target evidence, audit pending | `scalive/api/src/scalive/JS.scala`, `BrowserEvent.scala`, generated HTML definitions, `BindingTableSpec.scala`, `PhoenixRenderedEncoderSpec.scala`, and connected binding dispatch in `ConnectedRenderSpec.scala`. | Audit command JSON and all client-maintained DOM states against the pinned browser suite. |
| Static asset tracking and connect metadata | Target evidence | `StaticAssetsSpec.scala`, `ZioHttpSpec.scala`, `RootConnectionSpec.scala`, and the typed `ConnectedMetadata`/connection capability APIs cover tracked assets, connect params, and static-change metadata. | Expand public documentation and browser assertions for reconnect metadata. |
| Security, session tokens, and CSRF | Target evidence | `ZioHttpConfig`, `LiveSecurity`, and purpose-bound credentials are covered by `HttpSecuritySpec.scala`, `ZioHttpSecuritySpec.scala`, and `ZioHttpSpec.scala`. | Tokens are authenticated rather than encrypted; continue security review and browser stale-admission testing. |
| Error shapes, crash recovery, and observability | Target evidence, audit pending | `RuntimeObservabilitySpec.scala`, `ConnectionSupervisorSpec.scala`, `SerialWriterSpec.scala`, `PhoenixProtocolFuzzSpec.scala`, transport suites, and upstream error scenarios cover correlated events, supervision, ordered linked-child failure reporting, crash recovery, sink failures, malformed frames, and admission failures. | Continue auditing normalized error payloads while preserving the verified browser reload/rejoin behavior. |
| Transport support | Partial | ZIO HTTP static and websocket routes, channel frames, uploads, security, and backpressure are covered by `scalive/transport/zio-http/test/src/scalive`. | Long-poll fallback is not implemented; decide whether it is required. |
| Test harness helpers | Target evidence | `scalive/testing/src/scalive/testing` provides disconnected semantic queries plus `ConnectedRender`/`ConnectedView` joins, clicks, forms, typed messages, async waits, nested joins, hosted uploads, leave, and latest-HTML queries; both render suites exercise these paths. | Extend helpers only for recurring user-facing tests; do not clone Phoenix helper internals. |
| Process-style callbacks and HEEx macros | Out of scope / intentional divergence | ZIO fibers and scopes replace process callbacks; typed Scala HTML, signals, models, codecs, and components replace assigns maps and HEEx macros. | Document equivalents when a concrete user-facing need appears. |

## Milestone-11 Cutover Evidence

- Published-module boundaries are defined in `build.mill` for the public API, renderer, runtime
  contracts/resources/topology/kernel/connection, Phoenix protocol, ZIO HTTP transport, and testing
  artifacts. This records the publication cutover; it does not assert that a release was published.
- Maintained JMH fixtures under `benchmarks` cover render evaluation, diffing, protocol encoding, and
  lifecycle turns. They are performance diagnostics, not compatibility proof.
- The frozen oracle's 172 passing Chromium scenarios remain historical comparison evidence. Current
  browser compatibility is established only by a fresh run of the browser gate.
- Initial no-retry repetition reproduced client-observation races: issue 3530 missed an asynchronous
  nested-hook log in 4/10 runs, nested LiveComponent form recovery inspected WebSocket frames before
  the recovery event in 1/25 runs, issue 3529 compared remount text before navigation settled, and
  error scenarios could inspect console delivery immediately after the recovered DOM appeared.
- The revision-specific patch under `test/upstream-patches` removes the unattended debugger pause
  and polls for the same exact payloads, remount text, console messages, and hook lifecycle counts.
  It skips no scenario and weakens no behavioral assertion.
- Separate crash repetitions exposed runtime defects rather than harness races. Failure-aware
  lifecycle retirement now delivers root channel errors, and a linked child reports its mount or
  runtime failure before aborting its parent, preventing stale child rejoin and fallback HTTP reload.
  Native regressions verify this ordering. Focused no-retry runs passed linked crash recovery 50/50,
  root crash recovery 25/25, issue 3530 50/50, issue 3529 25/25, and all five disabled-fieldset
  recovery variants 125/125.
- `scripts/e2e-run-upstream-cutover.sh` runs the complete unfiltered suite three times and forces
  retries to zero. On 2026-08-21 all three runs passed all 172 scenarios in 27.2, 26.8, and 26.3
  seconds. Snapshot CI runs the same complete suite once with retries disabled before publication;
  the three-run command remains the explicit cutover gate.

## Intentional Divergences

- Socket assigns and callback tuples are replaced by typed models, contexts, effects, and result
  types.
- Connection-only operations are exposed through explicit connected capabilities rather than
  phase-dependent no-ops.
- Mount aspects, routes, paths, queries, forms, component references, and resource keys are typed.
- Static hooks need no identity; dynamically attached hooks retain names for detach semantics.
- HEEx/component macros and Phoenix test-helper internals are not copied.

## Verification Strategy

1. Run `./scripts/e2e-run-upstream.sh` as the browser compatibility gate; do not infer current status
   from the historical oracle.
2. Keep native suites at the module owning each behavior and record representative paths here.
3. Compare HTTP, semantic DOM, protocol output, resource cleanup, and observability using the
   normalization rules in the runtime parity manifest.
4. Prefer complete vertical scenarios over broad claims based only on isolated units.
