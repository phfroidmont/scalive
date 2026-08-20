# Runtime Parity Manifest

## Purpose

This manifest records the behavioral oracle for the greenfield runtime described in
[`runtime-target-implementation-plan.md`](runtime-target-implementation-plan.md). The legacy source
is not a compatibility dependency. It is executable evidence for classifying observable behavior
while the target runtime is built in the final modules and packages.

## Baseline

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

The upstream revision and source hash come from `flake.lock`. The worktree path is the local
location used for this baseline; the tag and commit are the portable oracle identity.

## Baseline Results

Run these commands from the detached oracle worktree inside `nix develop`.

| Command | Result |
| --- | --- |
| `mill --ticker false __.test` | Passed; all discovered tests passed with no failed or ignored tests |
| `./scripts/e2e-run-upstream.sh --list` | Discovered 172 Chromium tests in 54 files |
| `./scripts/e2e-run-upstream.sh` | Passed; 172 tests passed in 35.5 seconds with no retries |

The browser baseline produced debug reports of closed WebSocket channels during browser teardown.
They did not fail or retry any scenario and are not classified as oracle defects.

## Classifications

Every scenario must use exactly one classification:

- **Required behavior:** the target runtime must provide the same normalized observable behavior.
- **Intentional Scala divergence:** Scalive deliberately provides a different user-facing contract.
- **Out of scope:** the upstream behavior is not a target and the exclusion is explicit.
- **Known legacy defect:** the oracle has a reproducible mismatch that the target must not inherit.

The locked browser suite below contains 172 required-behavior scenarios. Every scenario passed the
legacy oracle. API-shape divergences and non-browser feature decisions are recorded separately in
the compatibility-area evidence table.

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

Each checked scenario inherits the **Required behavior** classification and a **Passed** oracle
result. The stable upstream identity is the spec path plus the complete Playwright title. Source line
numbers are intentionally omitted because they are not stable identifiers.

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
the current native evidence and target treatment. File references identify suites rather than
private implementation details; they will move to the target modules as scenarios are reimplemented.
Unqualified runtime suites are under `scalive/test/src/scalive`; compile-time API suites are under
`scalive/test/src/scaliveapi`; `DisconnectedRenderSpec.scala` is under
`scaliveTesting/test/src/scalive/testing`.

| Area | Classification or target treatment | Current evidence |
| --- | --- | --- |
| Browser E2E behavior | Required behavior | `scripts/e2e-run-upstream.sh`, `test/playwright.upstream.config.js`, `e2eApp/src/E2EApp.scala`, `e2eApp/src/E2ERoutes.scala` |
| Wire protocol and diff encoding | Required behavior | `WebSocketMessageSpec.scala`, `TreeDiffSpec.scala`, `RenderSnapshotSpec.scala`, `ViewGraphSocketSpec.scala` |
| Static HTTP render and connected bootstrap | Required behavior | `SocketSpec.scala`, `LiveRoutesLifecycleSpec.scala`, `DisconnectedRenderSpec.scala` |
| Live routes, sessions, aspects, and layouts | Required behavior with Scala-first API shape | `LiveRoutesValidationSpec.scala`, `LiveRoutesTypeSafetySpec.scala`, `LiveRoutesLifecycleSpec.scala`, `LiveRoutesLayoutSpec.scala`, `LiveMountAspectSpec.scala` |
| Connection availability | Required lifecycle distinction with an intentional Scala API divergence: connected-only capabilities become explicitly unavailable instead of legacy no-ops | `LifecycleHookSpec.scala`, `LiveRoutesLifecycleSpec.scala` cover the legacy connected/disconnected distinction; target compile-time rejection remains unimplemented |
| Connect params and connect info | Required behavior; target API incomplete | Adjacent coverage in `LiveRoutesLifecycleSpec.scala`, `StaticTrackingSpec.scala`, and `CsrfProtectionSpec.scala` |
| Lifecycle hooks | Required behavior with typed hook results | `LifecycleHookSpec.scala`, `LiveComponentParitySpec.scala` |
| Stateful components and updates | Required behavior with semantic component targets | `LiveComponentParitySpec.scala`, `ViewGraphSocketSpec.scala`, `ComponentApiSpec.scala` |
| Nested LiveViews | Required behavior | `LiveRoutesLifecycleSpec.scala`, `ViewGraphSocketSpec.scala` |
| Navigation and flash | Required behavior with typed locations | `NavigationApiSpec.scala`, `LiveLocationSpec.scala`, `FlashSpec.scala`, `HttpFlashSpec.scala`, `SocketSpec.scala` |
| Forms | Required behavior with typed codecs | `FormDataSpec.scala`, `FormApiSpec.scala`, `FormActionSpec.scala`, `HttpFormDecoderSpec.scala`, `DisconnectedRenderSpec.scala` |
| Uploads | Required behavior with typed writers | `LiveUploadSpec.scala`, `SocketUploadSpec.scala`, `RuntimeIdentifierTypesSpec.scala` |
| Streams | Required behavior with declarative stream state | `StreamApiSpec.scala`, `StreamOpacitySpec.scala`, `TreeDiffSpec.scala`, `RenderSnapshotSpec.scala` |
| Async tasks | Required behavior with typed completion messages | `AsyncSpec.scala`, `LiveComponentParitySpec.scala`, `LifecycleHookSpec.scala` |
| Async assigns | Intentional Scala divergence; explicit typed async completion messages replace assign mutation | `AsyncSpec.scala` covers the replacement behavior; no direct `assign_async` helper exists |
| JS commands and client events | Required behavior with typed payloads | `BindingRegistrySpec.scala`, `ClientEventsSpec.scala`, `HtmlBuilderSpec.scala`, `HtmlMessageTypeSafetySpec.scala` |
| DOM bindings and static tracking | Required behavior | `HtmlBuilderSpec.scala`, `StaticTrackingSpec.scala`, `StaticAssetsSpec.scala` |
| Titles, portals, and focus wrap | Required behavior | `SocketSpec.scala`, `LiveRoutesLifecycleSpec.scala`, `HtmlBuilderSpec.scala`, `ViewGraphSocketSpec.scala` |
| Security, session tokens, and CSRF | Required behavior; opaque values are normalized | `TokenSpec.scala`, `CsrfProtectionSpec.scala`, `CookiePolicySpec.scala`, `SecurityApiSpec.scala`, `HttpFlashSpec.scala` |
| Error shapes and crash recovery | Required behavior | `SocketSpec.scala`, `ViewGraphSocketSpec.scala`, `LiveMountAspectSpec.scala`, `WebSocketMessageSpec.scala` |
| Process-style callbacks | Out of scope as direct API parity; ZIO fibers, scopes, and typed capabilities are the replacement | No direct native equivalent |
| HEEx and function-component macros | Intentional Scala divergence; typed Scala HTML is the replacement | `HtmlBuilderSpec.scala`, `HtmlMessageTypeSafetySpec.scala` |
| Verified-route macros | Intentional Scala divergence; typed codecs and locations are the replacement | `LiveRoutesTypeSafetySpec.scala`, `LiveLocationSpec.scala` |
| Long-poll transport fallback | Scope decision still required; no current target milestone implements it | No long-poll evidence; WebSocket coverage exists in `WebSocketMessageSpec.scala` and the browser suite |
| Phoenix endpoint process options | Out of scope as direct API parity; applicable ZIO HTTP configuration remains transport-owned | Socket path coverage in `LiveRoutesTypeSafetySpec.scala` |
| Observability | Required target behavior with a Scala/ZIO event model, not Phoenix telemetry API parity | `RuntimeObservabilitySpec.scala` covers correlated, ordered, payload-redacted runtime events and sink-defect isolation |
| Test harness helpers | Required Scalive-native harness, not Phoenix helper API parity | `DisconnectedRenderSpec.scala`; connected harness remains unimplemented |

## Maintenance Rules

- A target test replaces oracle evidence only when it asserts the same normalized behavior.
- New or changed upstream scenarios receive a new explicit classification before the compatibility
  target advances.
- Intentional divergences and out-of-scope entries include the user-facing rationale.
- A failed target scenario never changes a required behavior into a legacy defect.
- Oracle defects require a reproducible baseline failure; none are recorded for this baseline.
