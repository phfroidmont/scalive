# Runtime Parity Manifest

## Purpose

This manifest separates two kinds of evidence:

1. the frozen legacy oracle used to classify normalized observable behavior; and
2. the current Milestone-11 implementation and tests in the final target modules.

The legacy source is not a compatibility dependency. Its historical results remain useful evidence,
but they do not report the current runtime's browser status. The complete upstream browser harness
is the current gate and must be run against the current tree for a current result.

## Frozen Legacy Baseline

| Item | Value |
| --- | --- |
| Scalive commit | `3c78310ad6dc28b8ed26c0c32b250a27e4998bc3` |
| Immutable tag | `runtime-legacy-baseline-2026-08-18` |
| Oracle worktree | `/home/phfroidmont/Projects/scalive-legacy`, detached at the baseline commit |
| Phoenix LiveView target | `v1.1.28` |
| Locked upstream commit | `df3e88c0abb8837c484f4cef033ff2490274af28` |
| Locked upstream source hash | `sha256-AIxPbxaHF4eC6H3AxpfKQmhw6RA33ektH6nmYI+Ev0I=` |
| Baseline date | 2026-08-18 |
| Platform | Linux `amd64` |
| Toolchain | Nix 2.34.8, Mill 1.1.2, Scala 3.8.3, Mill JVM 21.0.10, shell OpenJDK 21.0.12, Node 24.14.0, Playwright 1.58.2 |

The upstream revision and source hash came from `flake.lock` at freeze time. The worktree path is
the local location used for this historical baseline; the tag and commit are its portable identity.

## Historical Baseline Results

These commands were run from the detached oracle worktree inside `nix develop` on the baseline
date. They are not results for the current Milestone-11 tree.

| Command | Result |
| --- | --- |
| `mill --ticker false __.test` | Passed; all discovered tests passed with no failed or ignored tests |
| `./scripts/e2e-run-upstream.sh --list` | Discovered 172 Chromium tests in 54 files |
| `./scripts/e2e-run-upstream.sh` | Passed; 172 tests passed in 35.5 seconds with no retries |

The historical browser baseline produced debug reports of closed WebSocket channels during browser
teardown.
They did not fail or retry any scenario and are not classified as oracle defects.

## Current Milestone-11 Target

The active runtime is split across `scalive/api`, `scalive/render`, `scalive/runtime/kernel`,
`scalive/runtime/connection`, `scalive/protocol/phoenix`, and `scalive/transport/zio-http`, with
behavioral helpers in `scalive/testing`. This is the current implementation, not an unimplemented
future runtime.

`scalive/testing` now includes `ConnectedRender` and `ConnectedView`. They execute disconnected
bootstrap and signed route admission, start production connection supervision, dispatch click and
form bindings, send typed messages, wait for asynchronous output, join nested views, stream hosted
uploads, query current semantic HTML, and leave lifecycles. This is native connected-test evidence;
it is not a substitute for the browser gate.

The artifact boundaries in `build.mill` record the publication cutover to the target modules.
Maintained JMH fixtures under `benchmarks` cover target render, diff, encoding, and lifecycle paths;
benchmark results measure performance and are not parity evidence.

### Current Target Verification

The following commands were run against the current Milestone-11 tree on 2026-08-21:

| Command | Result |
| --- | --- |
| `mill --ticker false scalive.__.test` | Passed; all discovered Scalive module tests passed |
| `mill --ticker false __.test` | Passed; all repository tests passed |
| `mill --ticker false documentation.check` | Passed |
| `mill --ticker false __.reformat + __.fix` | Passed |
| `mill --ticker false scalive.__.publishArtifacts` | Passed for every publishable module |
| `mill --ticker false benchmarks.listJmhBenchmarks` | Passed; discovered all maintained benchmark groups |
| `mill --ticker false benchmarks.runJmh -wi 0 -i 1 -r 100ms -f 0 -foe true '.*'` | Passed as a non-threshold smoke run |
| `./scripts/e2e-run-root-slice.sh --retries=0` | Passed; 9 Chromium scenarios passed |
| `./scripts/e2e-run-upstream-cutover.sh` | Passed; three consecutive complete runs each passed all 172 scenarios with retries disabled, in 27.2, 26.8, and 26.3 seconds |

Initial no-retry repetition reproduced synchronization boundaries directly. Issue 3530 observed a
missing asynchronous nested-hook log in 4/10 runs, while the nested LiveComponent disabled-fieldset
recovery case inspected its WebSocket frame list before the recovery event in 1/25 runs. Later
strict runs exposed equivalent immediate observations in issue 3529 remount text and error-scenario
console delivery.

`scripts/e2e-sync-upstream.sh` applies a patch named for the exact locked upstream revision. The
patch removes the unattended `page.pause()` and replaces only premature observations with polling
for the same exact form payload, remount text, console messages, and hook lifecycle counts. No test
or behavioral assertion is hidden, skipped, or weakened.

Crash repetitions also exposed two runtime defects. Failure-aware lifecycle retirement now emits
the root channel error needed for reconnect, while linked nested failures notify the child channel
before aborting the exact parent, preventing stale child rejoin and fallback HTTP reload. Native
supervisor regressions verify mount-time and runtime ordering. Focused no-retry runs passed linked
crash recovery 50/50, root crash recovery 25/25, issue 3530 50/50, issue 3529 25/25, and the five
disabled-fieldset recovery variants 125/125. The strict cutover command then passed three complete
unfiltered 172-test runs with retries forced to zero. Snapshot CI runs the complete suite once with
retries disabled before publication; the three-run command remains the explicit cutover gate.

## Classifications

Every scenario must use exactly one classification:

- **Required behavior:** the target runtime must provide the same normalized observable behavior.
- **Intentional Scala divergence:** Scalive deliberately provides a different user-facing contract.
- **Out of scope:** the upstream behavior is not a target and the exclusion is explicit.
- **Known legacy defect:** the oracle has a reproducible mismatch that the target must not inherit.

The locked browser suite below contains 172 required-behavior scenarios. Every scenario passed the
legacy oracle on 2026-08-18. The checkmarks below therefore describe that frozen run only. They do
not claim a current target run is green. API-shape divergences and non-browser feature decisions are
recorded separately in the compatibility-area evidence table.

## Comparison Rules

Oracle comparisons use normalized observables:

- HTTP status, redirect location, cookie semantics, and semantic DOM;
- Phoenix event kind, reply status, navigation, title, and normalized diff structure;
- resource results, ordering, cancellation, and cleanup; and
- trace kind, stage, correlation identity, and outcome.

The following values are never compared textually:

- tokens, signatures, and CSRF values;
- timestamps and durations;
- Phoenix CIDs and binding IDs; and
- map ordering.

## Browser Scenario Inventory

Each checked scenario inherits the **Required behavior** classification and a **Passed historical
oracle** result. The stable upstream identity is the spec path plus the complete Playwright title.
Source line numbers are intentionally omitted because they are not stable identifiers. Current
target status comes only from a fresh `./scripts/e2e-run-upstream.sh` run.

### `colocated.spec.js`

- [x] `colocated hooks works`
- [x] `colocated JS works`
- [x] `custom macro component works (syntax highlighting)`

### `components.spec.js`

- [x] `dropdown menu focus wrapping works correctly`
- [x] `simple focus container traps focus correctly`
- [x] `focus_wrap components have correct attributes`

### `errors.spec.js`

- [x] `exception handling > during HTTP mount > 500 error when dead mount fails`
- [x] `exception handling > during connected mount > reloads the page when connected mount fails`
- [x] `exception handling > during connected mount > rejoin instead of reload when child LV fails on connected mount`
- [x] `exception handling > during connected mount > abandons child remount if child LV fails multiple times`
- [x] `exception handling > after connected mount > page does not reload if child LV crashes (handle_event)`
- [x] `exception handling > after connected mount > page does not reload if main LV crashes (handle_event)`
- [x] `exception handling > after connected mount > parent crashes and reconnects when linked child LV crashes`

### `forms.spec.js`

- [x] `restores disabled and readonly states > /form/nested - readonly state is restored after submits`
- [x] `restores disabled and readonly states > /form/nested - button disabled state is restored after submits`
- [x] `restores disabled and readonly states > /form/nested - non-form button (phx-disable-with) disabled state is restored after click`
- [x] `/form/nested live-component - form recovery > form state is recovered when socket reconnects`
- [x] `/form/nested live-component - form recovery > JS command in phx-change works during recovery`
- [x] `/form/nested live-component - form recovery > does not recover when form is missing id`
- [x] `/form/nested live-component - form recovery > does not recover when form is missing phx-change`
- [x] `/form/nested live-component - form recovery > phx-auto-recover`
- [x] `/form/nested live-component - form recovery > respects disabled state of a fieldset`
- [x] `/form/nested - form recovery > form state is recovered when socket reconnects`
- [x] `/form/nested - form recovery > JS command in phx-change works during recovery`
- [x] `/form/nested - form recovery > does not recover when form is missing id`
- [x] `/form/nested - form recovery > does not recover when form is missing phx-change`
- [x] `/form/nested - form recovery > phx-auto-recover`
- [x] `/form/nested - form recovery > respects disabled state of a fieldset`
- [x] `/form/nested - can submit form with button that has phx-click`
- [x] `/form/nested - loading and locked states with latency`
- [x] `restores disabled and readonly states > /form - readonly state is restored after submits`
- [x] `restores disabled and readonly states > /form - button disabled state is restored after submits`
- [x] `restores disabled and readonly states > /form - non-form button (phx-disable-with) disabled state is restored after click`
- [x] `/form live-component - form recovery > form state is recovered when socket reconnects`
- [x] `/form live-component - form recovery > JS command in phx-change works during recovery`
- [x] `/form live-component - form recovery > does not recover when form is missing id`
- [x] `/form live-component - form recovery > does not recover when form is missing phx-change`
- [x] `/form live-component - form recovery > phx-auto-recover`
- [x] `/form live-component - form recovery > respects disabled state of a fieldset`
- [x] `/form live-component - form recovery > navigation during recovery is properly handled by the client`
- [x] `/form - form recovery > form state is recovered when socket reconnects`
- [x] `/form - form recovery > JS command in phx-change works during recovery`
- [x] `/form - form recovery > does not recover when form is missing id`
- [x] `/form - form recovery > does not recover when form is missing phx-change`
- [x] `/form - form recovery > phx-auto-recover`
- [x] `/form - form recovery > respects disabled state of a fieldset`
- [x] `/form - form recovery > navigation during recovery is properly handled by the client`
- [x] `/form portal - form recovery > form state is recovered when socket reconnects`
- [x] `/form portal - form recovery > JS command in phx-change works during recovery`
- [x] `/form portal - form recovery > does not recover when form is missing id`
- [x] `/form portal - form recovery > does not recover when form is missing phx-change`
- [x] `/form portal - form recovery > phx-auto-recover`
- [x] `/form portal - form recovery > respects disabled state of a fieldset`
- [x] `/form portal - form recovery > navigation during recovery is properly handled by the client`
- [x] `/form - can submit form with button that has phx-click`
- [x] `/form - loading and locked states with latency`
- [x] `loading and locked states with latent clone`
- [x] `can dynamically add/remove inputs (ecto sort_param/drop_param)`
- [x] `can dynamically add/remove inputs using checkboxes`
- [x] `form recovery does not create duplicates of dynamically added fields`
- [x] `phx-no-feedback is applied correctly for backwards-compatible-shims`

### Issue regressions

- [x] `issues/2787.spec.js :: select is properly cleared on submit`
- [x] `issues/2965.spec.js :: can upload files with custom chunk hook`
- [x] `issues/3026.spec.js :: LiveComponent is re-rendered when racing destory`
- [x] `issues/3040.spec.js :: click-away does not fire when triggering form submit`
- [x] `issues/3040.spec.js :: does not close modal when moving mouse outside while held down`
- [x] `issues/3047.spec.js :: streams are not cleared in sticky live views`
- [x] `issues/3083.spec.js :: select multiple handles option updates properly`
- [x] `issues/3107.spec.js :: keeps value when updating select`
- [x] `issues/3117.spec.js :: LiveComponent with static FC root is not reset`
- [x] `issues/3169.spec.js :: updates which add cids back on page are properly magic id change tracked`
- [x] `issues/3194.spec.js :: does not send event to wrong LV when submitting form with debounce blur`
- [x] `issues/3200.spec.js :: phx-target='selector' is used correctly for form recovery`
- [x] `issues/3378.spec.js :: can rejoin with nested streams without errors`
- [x] `issues/3448.spec.js :: focus is handled correctly when patching locked form`
- [x] `issues/3496.spec.js :: hook is initialized properly when reusing id between sticky and non sticky LiveViews`
- [x] `issues/3529.spec.js :: forward and backward navigation is handled properly (replaceRootHistory)`
- [x] `issues/3530.spec.js :: hook is initialized properly when using a stream of nested LiveViews`
- [x] `issues/3612.spec.js :: sticky LiveView stays connected when using push_navigate`
- [x] `issues/3636.spec.js :: focus_wrap - focuses first element when entering focus from outside`
- [x] `issues/3647.spec.js :: upload works when input event follows immediately afterwards`
- [x] `issues/3651.spec.js :: locked hook with dynamic id is properly cleared`
- [x] `issues/3656.spec.js :: phx-click-loading is removed from links in sticky LiveViews`
- [x] `issues/3658.spec.js :: phx-remove elements inside sticky LiveViews are not removed when navigating`
- [x] `issues/3681.spec.js :: streams in nested LiveViews are not reset when they share the same stream ref`
- [x] `issues/3684.spec.js :: nested clones are correctly applied`
- [x] `issues/3686.spec.js :: flash is copied across fallback redirect`
- [x] `issues/3709.spec.js :: pendingDiffs don't race with navigation`
- [x] `issues/3719.spec.js :: target is properly decoded`
- [x] `issues/3814.spec.js :: submitter is sent when using phx-trigger-action`
- [x] `issues/3819.spec.js :: form recovery aborts early when form is empty`
- [x] `issues/3919.spec.js :: attribute defaults are properly considered as changed`
- [x] `issues/3941.spec.js :: component-only patch in locked tree works`
- [x] `issues/3953.spec.js :: component destroy messages respect the parent`
- [x] `issues/3979.spec.js :: components destroyed check works properly`
- [x] `issues/4027.spec.js :: keyed comprehensions are merged properly in LiveComponents - case first`
- [x] `issues/4027.spec.js :: keyed comprehensions are merged properly in LiveComponents - case second`
- [x] `issues/4066.spec.js :: events for disconnected elements are ignored`
- [x] `issues/4078.spec.js :: live_file_input respects disabled attribute changes`
- [x] `issues/4078.spec.js :: live_file_input respects class attribute changes`
- [x] `issues/4078.spec.js :: live_file_input preserves files when attributes change`
- [x] `issues/4088.spec.js :: locked LiveComponent container can be patched properly`
- [x] `issues/4094.spec.js :: no errors when handle_params redirects`
- [x] `issues/4095.spec.js :: events for disconnected elements are ignored`
- [x] `issues/4102.spec.js :: debounce works for inputs outside of the form`
- [x] `issues/4107.spec.js :: external form submission from teleported form is successful`
- [x] `issues/4121.spec.js :: stream teleported outside of LiveView can be reset`
- [x] `issues/4147.spec.js :: hook outside of liveview does works when reconnecting`

### `js.spec.js`

- [x] `toggle_attribute`
- [x] `set and remove_attribute`
- [x] `ignore_attributes`

### `keyed-comprehension.spec.js`

- [x] `renders correctly - all_keyed`
- [x] `renders correctly - rows_keyed`
- [x] `renders correctly - no_keyed`

### `navigation.spec.js`

- [x] `can navigate between LiveViews in the same live session over websocket`
- [x] `handles live redirect loops`
- [x] `popstate`
- [x] `patch with replace replaces history`
- [x] `falls back to http navigation when navigating between live sessions`
- [x] `restores scroll position after navigation`
- [x] `does not restore scroll position on custom container after navigation`
- [x] `scrolls hash el into view`
- [x] `scrolls hash el into view after live navigation (issue #3452)`
- [x] `restores scroll position when navigating from dead view`
- [x] `navigating all the way back works without remounting (only patching)`
- [x] `back and forward navigation types are tracked`

### `portal.spec.js`

- [x] `renders modal inside portal location`
- [x] `teleported element is removed properly`
- [x] `events are routed to correct LiveView`
- [x] `streams work in teleported LiveComponent`
- [x] `tooltip example`
- [x] `teleported hook works correctly`
- [x] `nested portals render and work correctly`
- [x] `nested portals cleanup and re-render correctly`
- [x] `click-away is portal aware`

### `select.spec.js`

- [x] `select shows error when invalid option is selected`

### `streams.spec.js`

- [x] `renders properly`
- [x] `elements can be updated and deleted (LV)`
- [x] `elements can be updated and deleted (LC)`
- [x] `move-to-first moves the second element to the first position (LV)`
- [x] `stream reset removes items`
- [x] `stream reset properly reorders items`
- [x] `stream reset updates attributes`
- [x] `Issue #2656 > stream reset works when patching`
- [x] `Issue #2994 > can filter and reset a stream`
- [x] `Issue #2994 > can reorder stream`
- [x] `Issue #2994 > can filter and then prepend / append stream`
- [x] `Issue #2982 > can reorder a stream with LiveComponents as direct stream children`
- [x] `Issue #3023 > can bulk insert items at a specific index`
- [x] `stream limit - issue #2686 > limit is enforced on mount, but not dead render`
- [x] `stream limit - issue #2686 > removes item at front when appending and limit is negative`
- [x] `stream limit - issue #2686 > removes item at back when prepending and limit is positive`
- [x] `stream limit - issue #2686 > does nothing if appending and positive limit is reached`
- [x] `stream limit - issue #2686 > does nothing if prepending and negative limit is reached`
- [x] `stream limit - issue #2686 > arbitrary index`
- [x] `any stream insert for elements already in the DOM does not reorder`
- [x] `stream nested in a LiveComponent is properly restored on reset`
- [x] `phx-remove is handled correctly when restoring nodes`
- [x] `issue #3129 - streams asynchronously assigned and rendered inside a comprehension`
- [x] `issue #3260 - supports non-stream items with id in stream container`
- [x] `JS commands are applied when re-joining`
- [x] `update_only`
- [x] `empty text nodes are pruned`

### `uploads.spec.js`

- [x] `can upload a file`
- [x] `can drop a file`
- [x] `can upload multiple files`
- [x] `shows error when there are too many files`
- [x] `shows error for invalid mimetype`
- [x] `auto upload`
- [x] `issue 3115 - cancelled upload is not re-added`
- [x] `submitting invalid form multiple times doesn't crash`
- [x] `auto upload - can submit files after fixing too many files error`

## Compatibility-Area Evidence

This table maps the broader areas in [`UPSTREAM_COMPATIBILITY.md`](../UPSTREAM_COMPATIBILITY.md) to
current native evidence and target treatment. All paths are repository-relative. File references
identify representative suites rather than every assertion. The latest browser result is recorded
above; native evidence alone must not be read as a claim about later current-tree browser status.

| Area | Classification or target treatment | Current evidence |
| --- | --- | --- |
| Browser E2E behavior | Required behavior | `scripts/e2e-run-upstream.sh`, `test/playwright.upstream.config.js`, `e2eApp/src/E2EApp.scala`, `e2eApp/src/E2ERoutes.scala` |
| Wire protocol and diff encoding | Required behavior | `scalive/render/test/src/scalive/render/{TreeDifferSpec,RenderProgramSpec,NestedRenderingSpec}.scala`; `scalive/protocol/phoenix/test/src/scalive/protocol/phoenix/{PhoenixProtocolSpec,PhoenixRenderedEncoderSpec,PhoenixProtocolFuzzSpec}.scala` |
| Static HTTP render and connected bootstrap | Required behavior | `scalive/transport/zio-http/test/src/scalive/{ZioHttpSpec,ZioHttpSecuritySpec}.scala`; `scalive/testing/test/src/scalive/testing/{DisconnectedRenderSpec,ConnectedRenderSpec}.scala` |
| Live routes, sessions, aspects, and layouts | Required behavior with Scala-first API shape | `scalive/api/test/src/scaliveapi/RoutedConstructionSpec.scala`; `scalive/transport/zio-http/test/src/scalive/{ZioHttpApiSpec,ZioHttpSpec}.scala` |
| Connection availability | Required lifecycle distinction with an intentional Scala API divergence: connected-only capabilities require explicit `Connection.Connected` handling | `scalive/api/test/src/scaliveapi/ConnectionCapabilitiesSpec.scala` includes compile-time rejection and valid connected capability use; `scalive/testing/test/src/scalive/testing/ConnectedRenderSpec.scala` exercises the connected phase |
| Connect params and static-change metadata | Required behavior with typed metadata/capabilities | `scalive/runtime/connection/test/src/scalive/runtime/connection/RootConnectionSpec.scala`; `scalive/transport/zio-http/test/src/scalive/{StaticAssetsSpec,ZioHttpSpec}.scala`; connected join parameters in `scalive/testing/src/scalive/testing/ConnectedRender.scala` |
| Lifecycle hooks | Required behavior with typed hook results | `scalive/api/src/scalive/lifecycle/Hooks.scala`; `scalive/runtime/connection/src/scalive/runtime/connection/RootHookRuntime.scala`; `RootConnectionSpec.scala` and `ComponentRuntimeSpec.scala` in that module's test tree |
| Stateful components and updates | Required behavior with semantic component targets | `scalive/api/test/src/scaliveapi/ComponentApiSpec.scala`; `scalive/runtime/kernel/test/src/scalive/runtime/kernel/ComponentKernelSpec.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/{ComponentRuntimeSpec,DisconnectedComponentRendererSpec}.scala` |
| Nested LiveViews | Required behavior | `scalive/render/test/src/scalive/render/NestedRenderingSpec.scala`; `scalive/runtime/kernel/test/src/scalive/runtime/kernel/NestedTopologyKernelSpec.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/NestedTopologyRuntimeSpec.scala`; `ConnectedRenderSpec.scala` |
| Navigation, flash, and titles | Required behavior with typed locations and lifecycle output | `scalive/runtime/kernel/test/src/scalive/runtime/kernel/SessionKernelSpec.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/RootConnectionSpec.scala`; `scalive/transport/zio-http/test/src/scalive/ZioHttpSpec.scala` |
| Forms | Required behavior with typed definitions/codecs | `scalive/api/test/src/scaliveapi/FormDefinitionApiSpec.scala`; `scalive/transport/zio-http/src/scalive/HttpFormDecoder.scala`; `scalive/testing/test/src/scalive/testing/{DisconnectedRenderSpec,ConnectedRenderSpec}.scala` |
| Uploads | Required behavior with typed writers | `scalive/api/test/src/scalive/upload/LiveUploadSpec.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/{UploadContextSpec,UploadWorkerSpec}.scala`; `scalive/protocol/phoenix/test/src/scalive/protocol/phoenix/PhoenixUploadProtocolSpec.scala`; `scalive/transport/zio-http/test/src/scalive/ZioHttpUploadSpec.scala` |
| Streams | Required behavior with opaque public state and lifecycle ownership | `scalive/api/test/src/scaliveapi/StreamOpacitySpec.scala`; `scalive/render/test/src/scalive/render/StreamRenderingSpec.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/{StreamStoreSpec,ManagedStreamsSpec}.scala` |
| Async tasks and subscriptions | Required behavior with typed keys, completion messages, and scoped cleanup | `scalive/api/test/src/scaliveapi/{ConnectionCapabilitiesSpec,RuntimeIdentifierTypesSpec}.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/{ManagedAsyncSpec,ManagedSubscriptionsSpec}.scala` |
| Async assigns | Intentional Scala divergence; explicit typed async completion messages replace assign mutation | `ManagedAsyncSpec.scala` and `ConnectionCapabilitiesSpec.scala` cover the replacement behavior; no direct field-mutation helper is claimed |
| JS commands, client events, and DOM bindings | Required behavior with typed payloads | `scalive/api/src/scalive/{JS,BrowserEvent}.scala`; `scalive/render/test/src/scalive/render/BindingTableSpec.scala`; `PhoenixRenderedEncoderSpec.scala`; connected dispatch in `ConnectedRenderSpec.scala` |
| Static asset tracking | Required behavior | `scalive/transport/zio-http/test/src/scalive/{StaticAssetsSpec,ZioHttpSpec}.scala` |
| Security, session credentials, and CSRF | Required behavior; opaque values are normalized | `scalive/transport/zio-http/test/src/scalive/{HttpSecuritySpec,ZioHttpSecuritySpec,ZioHttpSpec}.scala`; validated `ZioHttpConfig` and `LiveSecurity` public configuration |
| Error shapes, supervision, and observability | Required behavior | `scalive/runtime/kernel/test/src/scalive/runtime/kernel/RuntimeObservabilitySpec.scala`; `scalive/runtime/connection/test/src/scalive/runtime/connection/{ConnectionSupervisorSpec,SerialWriterSpec}.scala`; `PhoenixProtocolFuzzSpec.scala` |
| Process-style callbacks | Out of scope as direct API parity; ZIO fibers, scopes, and typed capabilities are the replacement | No direct native equivalent |
| HEEx and function-component macros | Intentional Scala divergence; typed Scala HTML is the replacement | Public HTML definitions under `scalive/api/src/scalive`; render evidence in `HtmlRendererSpec.scala` and `RenderProgramSpec.scala` |
| Verified-route macros | Intentional Scala divergence; typed routing codecs and locations are the replacement | `scalive/api/src/scalive/routing/LiveRouting.scala`; `RoutedConstructionSpec.scala`; `ZioHttpApiSpec.scala` |
| Long-poll transport fallback | Scope decision still required | No long-poll evidence; websocket protocol and transport coverage exists in the Phoenix protocol and ZIO HTTP module suites |
| Phoenix endpoint process options | Out of scope as direct API parity; applicable ZIO HTTP configuration remains transport-owned | Route assembly and configurable websocket path coverage in `ZioHttpApiSpec.scala` |
| Observability | Required target behavior with a Scala/ZIO event model, not Phoenix telemetry API parity | `RuntimeObservabilitySpec.scala` covers correlated, ordered, payload-redacted runtime events and sink-defect isolation |
| Test harness helpers | Required Scalive-native harness, not Phoenix helper API parity | `scalive/testing/test/src/scalive/testing/{DisconnectedRenderSpec,ConnectedRenderSpec}.scala`; connected capabilities include production admission/supervision, semantic queries, bindings, forms, typed messages, async waits, nested joins, hosted uploads, and leave |

## Maintenance Rules

- A target test replaces oracle evidence only when it asserts the same normalized behavior.
- New or changed upstream scenarios receive a new explicit classification before the compatibility
  target advances.
- Intentional divergences and out-of-scope entries include the user-facing rationale.
- A failed target scenario never changes a required behavior into a legacy defect.
- Oracle defects require a reproducible baseline failure; none are recorded for this baseline.
