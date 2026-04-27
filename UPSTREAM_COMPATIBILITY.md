# Upstream Compatibility Plan

Scalive tracks Phoenix LiveView behavior and feature coverage while keeping the Scala API ergonomic, typed, and robust. The immediate goal is to move from browser-level upstream E2E parity toward broader upstream feature parity, including server-side integration behavior.

## Current Baseline

- The upstream browser E2E harness is available via `./scripts/e2e-run-upstream.sh`.
- The last recorded Playwright run in `test-results/.last-run.json` passed.
- Scalive already has protocol-level component diff support through `component(cid, element)`, `RenderSnapshot`, and `TreeDiff`.
- Scalive now has a user-facing stateful `LiveComponent` abstraction with stable identity, component-local state, nested components, event/form/upload routing, typed `sendUpdate`, confirmed removal cleanup, component-scoped streams, and component-side navigation/client-effect/flash coverage.

## Compatibility Parity Matrix

Track upstream parity by suite or feature area, not only by individual bugs. Status values should stay coarse until we have automated parity checks for each row.

| Area | Upstream Reference | Scalive Status | Notes | Priority |
| --- | --- | --- | --- | --- |
| Browser E2E behavior | `test/e2e/tests/**/*.spec.js` | Passing baseline | Covered by `./scripts/e2e-run-upstream.sh`; keep running as regression suite. | High |
| Stateful LiveComponents | `test/phoenix_live_view/integrations/live_components_test.exs` | Partial | Core runtime exists: lifecycle, stable cid, local/nested component events, form events, upload progress, typed `sendUpdate`, selector/multiple `phx-target`, connected nested LiveViews inside components, removal cleanup, patch/navigation/redirect side effects, client effects, component flash, component-scoped stream state, and component-scoped async tasks. Remaining gaps are broader upstream lifecycle and edge-case coverage. | Highest |
| Nested LiveViews | `test/phoenix_live_view/integrations/nested_test.exs` | Native parity covered | Scalive-native coverage now mirrors the upstream nested integration suite: disconnected/connected render, dynamic children, recursive cleanup, multiple children of the same LiveView type, fresh constructor data, comprehensions, children inside components, duplicate id rejection, child push-navigate/push-patch/redirect, external redirect, and sticky child preservation. | High |
| Flash propagation | `test/phoenix_live_view/integrations/flash_test.exs` | Partial | Socket-scoped flash state exists with root/component APIs, render helpers, keyed/all clear, built-in `lv:clear-flash`, patch navigation persistence, bootstrap patch-loop persistence, nested socket isolation, push-navigate token propagation, and hard-redirect cookie propagation. Remaining gaps are broader upstream lifecycle edge cases. | High |
| Async tasks | `test/phoenix_live_view/integrations/start_async_test.exs` | Partial | Root LiveViews and LiveComponents can start typed named async tasks from mount/update/events, receive success/failure/cancellation as normal messages, render completion diffs, trigger patch navigation, cancel tasks, restart tasks by name, and clean up component/socket-owned tasks. Remaining gaps include broader upstream lifecycle edge cases. | Medium |
| Async assigns | `test/phoenix_live_view/integrations/assign_async_test.exs` | Partial | `LiveContext.assignAsync(model)(_.field)(effect)` updates typed `AsyncValue` model fields directly without user completion messages. Success, failure, reset/preserve loading behavior, cancellation, and component-scoped assigns are covered. Remaining gaps include broader upstream lifecycle edge cases and any multi-key convenience API. | Medium |
| Lifecycle hooks | `test/phoenix_live_view/integrations/hooks_test.exs` | Gap/partial | Scalive has `interceptEvent`; upstream hooks cover mount, event, params, info, async, and render stages. | Medium |
| Test harness helpers | `lib/phoenix_live_view/test/*` and integration tests | Not directly applicable | Scalive may need its own testing API rather than direct Phoenix API parity. | Low |

## Recommended Next Step

Continue closing the remaining stateful `LiveComponent` gaps with small vertical slices.

The core runtime is in place, so the highest-leverage follow-up work is now targeted parity around component lifecycle edge cases, async lifecycle edge cases, and lifecycle hooks.

## LiveComponent Implementation Sequence

1. Define a minimal user-facing `LiveComponent` API. Done.

   Include lifecycle methods for initialization/update/render, stable component identity, and a component-local model. Prefer the smallest API that can express upstream behavior without copying Elixir internals.

2. Add component identity and runtime storage. Done.

   Components need stable `cid` assignment from component type and id, preserved component-local state across parent renders, removal detection, and `data-phx-component` rendering.

3. Wire component event routing. Done for component-local events and `@myself`-style targeting.

   Support `phx-target` so events can be delivered to component-local handlers instead of the parent `LiveView`. Component event, form, upload progress, nested component, selector/multiple-target, and mismatched-cid guard coverage exists.

4. Add regression tests modeled after upstream `live_components_test.exs`. In progress.

   Covered so far: connected render, stable ids, duplicate-id rejection, removals after `cids_destroyed`, event delegation, form events, upload progress, nested components, connected nested LiveViews inside components, selector/multiple `phx-target`, `sendUpdate`, streams, navigation/redirect side effects, client effects, flash, and async. Remaining component tests should focus on broader upstream lifecycle edge cases.

5. Add dependent component features. In progress.

   `send_update`, component streams, component navigation/redirect side effects, component client effects, component flash behavior, connected nested LiveViews inside components, and component async are covered. Continue with broader upstream lifecycle edge cases.

## Async Task Implementation Sequence

1. Add root LiveView named async tasks. Done.

   `LiveContext.startAsync(name)(effect)(toMsg)` runs a socket-owned typed task and delivers completion as a normal LiveView message. Success, failure, event-started tasks, and patch navigation from completion are covered.

2. Add component-scoped async tasks. Done.

   Component completions dispatch to the originating component instance and component-owned tasks are interrupted on confirmed component removal.

3. Add lifecycle controls. Done for the first slice.

   Cancellation, restart-by-name, keep-existing start mode, and shutdown cleanup are implemented. Remaining work is broader upstream edge cases around navigation/remount.

4. Add async assign helpers. Done for the first slice.

   `LiveContext.assignAsync(model)(_.field)(effect)` derives a typed case-class field updater, stores loading state immediately, and applies completion to `AsyncValue` without requiring a user message. Cancellation uses `LiveContext.cancelAssignAsync(model)(_.field, reason)`.

## Suggested Work Order After Components

1. Complete nested LiveView lifecycle parity. Done for the upstream `nested_test.exs` equivalent slice.
2. Flash propagation across redirect, push navigate, and push patch. Done for the first slice.
3. Design and implement async task support. Done for the first slice.
4. Build async assigns on top of async task support. Done for the first slice.
5. Revisit lifecycle hooks and align them with the final component, nested, and async runtime model.

## Verification Strategy

- Keep `./scripts/e2e-run-upstream.sh` green.
- Add Scalive-native tests for upstream integration behaviors that cannot be run directly as Elixir tests.
- For each parity matrix row, record the upstream files and the Scalive tests that cover equivalent behavior.
- Prefer small vertical slices that make one upstream scenario pass end-to-end over broad incomplete abstractions.
