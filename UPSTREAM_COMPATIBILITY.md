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
| Stateful LiveComponents | `test/phoenix_live_view/integrations/live_components_test.exs` | Native parity covered | Scalive-native coverage now mirrors upstream component runtime behavior: `@myself`-style refs, disconnected/connected render, stable cids, additions/updates/removals, whole-root removals, nested LiveView/component removal combinations, removal races, local/form/upload events, nested components, selector/multiple `phx-target`, typed `sendUpdate`, missing-target handling, push-navigate/push-patch/redirect side effects, client effects, flash, streams, and async. Phoenix-only APIs such as module/id-less `send_update`, cid-based external updates, and `render_component/2` are intentionally not copied. | Highest |
| Nested LiveViews | `test/phoenix_live_view/integrations/nested_test.exs` | Native parity covered | Scalive-native coverage now mirrors the upstream nested integration suite: disconnected/connected render, dynamic children, recursive cleanup, multiple children of the same LiveView type, fresh constructor data, comprehensions, children inside components, duplicate id rejection, child push-navigate/push-patch/redirect, external redirect, and sticky child preservation. | High |
| Flash propagation | `test/phoenix_live_view/integrations/flash_test.exs` | Native parity covered | Scalive-native coverage now mirrors the upstream flash lifecycle behavior: keyed/all clear, built-in `lv:clear-flash`, stale flash exclusion for redirect/push-navigate/push-patch, client-side patch clearing, patch-redirect carryover, event and bootstrap patch persistence, mount redirect/push-navigate cookie propagation, hard-redirect cookie propagation, push-navigate token propagation, nested socket isolation, and nested child patch flash transfer to the root socket. Phoenix ConnTest/LiveViewTest helper assertions are intentionally not copied. | High |
| Async tasks | `test/phoenix_live_view/integrations/start_async_test.exs` | Native parity covered | Scalive-native coverage now mirrors the upstream start_async runtime behavior: root LiveViews and LiveComponents can start typed named async tasks from mount/update/events, receive success/failure/cancellation as normal messages, render completion diffs, trigger push-navigate/push-patch/redirect/flash side effects, cancel and restart tasks by name, keep existing tasks when requested, and deterministically interrupt socket/component-owned tasks on cancellation, socket shutdown, and confirmed component removal. Phoenix complex keys are represented by explicit string task names in Scalive's typed API. | Medium |
| Async assigns | `test/phoenix_live_view/integrations/assign_async_test.exs` | Native parity covered | Scalive-native coverage now mirrors the upstream assign_async runtime behavior through the typed field API: root LiveViews and LiveComponents can assign async fields from mount/update/events, store success/failure/cancellation, preserve or reset previous values while loading, renew after cancellation, and deterministically interrupt socket/component-owned assign tasks on shutdown or confirmed component removal. Phoenix's untyped map/list-key return validation and explicit Task.Supervisor option are intentionally not copied; Scalive uses typed direct `AsyncValue` field selectors and ZIO structured concurrency. | Medium |
| Lifecycle hooks | `test/phoenix_live_view/integrations/hooks_test.exs` | Native coverage expanding | Scalive now has mount-registered typed root hooks for event, params, info, async, and after-render stages plus stateful component event/after-render hooks. Route-level `onMount` is represented by typed `LiveMountAspect`s that run before disconnected and connected mount, sign claims into the root session token, reload typed context on websocket join, compose deterministically, and support redirect halts. Signed mount claims are tamper-proof but not encrypted, so they must not contain secrets. First-class `LiveSession` grouping exists. Live layouts compose across router/session/route scopes and can see typed layout context; root layouts are selected by precedence and keyed so websocket navigation falls back to a fresh HTTP render when the document shell changes. Live redirects that would reuse route-specific mount claims are also rejected for fresh HTTP render fallback. Remaining gaps are broader upstream error-shape assertions and smaller live-session ergonomics. | Medium |
| Test harness helpers | `lib/phoenix_live_view/test/*` and integration tests | Not directly applicable | Scalive may need its own testing API rather than direct Phoenix API parity. | Low |

## Recommended Next Step

Continue closing lifecycle hook gaps with small vertical slices.

The core component, nested LiveView, flash, async task, async assign, root hook, component hook, and route-level typed mount-aspect runtimes are covered, so the highest-leverage follow-up work is now broader lifecycle error-shape parity and smaller live-session ergonomics.

## LiveComponent Implementation Sequence

1. Define a minimal user-facing `LiveComponent` API. Done.

   Include lifecycle methods for initialization/update/render, stable component identity, and a component-local model. Prefer the smallest API that can express upstream behavior without copying Elixir internals.

2. Add component identity and runtime storage. Done.

   Components need stable `cid` assignment from component type and id, preserved component-local state across parent renders, removal detection, and `data-phx-component` rendering.

3. Wire component event routing. Done for component-local events and `@myself`-style targeting.

   Support `phx-target` so events can be delivered to component-local handlers instead of the parent `LiveView`. Component event, form, upload progress, nested component, selector/multiple-target, and mismatched-cid guard coverage exists.

4. Add regression tests modeled after upstream `live_components_test.exs`. Done for the upstream runtime-equivalent slice.

   Covered: connected/disconnected render, stable ids, duplicate-id rejection, additions/updates/removals, root replacement, removals after `cids_destroyed`, server/client removal races, event delegation, form events, upload progress, nested components, connected nested LiveViews inside components, selector/multiple `phx-target`, typed `sendUpdate`, streams, navigation/redirect side effects, client effects, flash, and async. Phoenix test-helper or untyped Elixir API cases are documented as intentionally not applicable.

5. Add dependent component features. Done for the first runtime-equivalent slice.

   `sendUpdate`, component streams, component navigation/redirect side effects, component client effects, component flash behavior, connected nested LiveViews inside components, and component async are covered.

## Async Task Implementation Sequence

1. Add root LiveView named async tasks. Done.

   `LiveContext.startAsync(name)(effect)(toMsg)` runs a socket-owned typed task and delivers completion as a normal LiveView message. Success, failure, event-started tasks, and patch navigation from completion are covered.

2. Add component-scoped async tasks. Done.

   Component completions dispatch to the originating component instance and component-owned tasks are interrupted on confirmed component removal.

3. Add lifecycle controls. Done.

   Cancellation, restart-by-name, keep-existing start mode, navigation/redirect/flash side effects, component update-started tasks, and deterministic socket/component cleanup are implemented and covered.

4. Add async assign helpers. Done.

   `LiveContext.assignAsync(model)(_.field)(effect)` derives a typed case-class field updater, stores loading state immediately, and applies completion to `AsyncValue` without requiring a user message. Cancellation uses `LiveContext.cancelAssignAsync(model)(_.field, reason)`. Root/component mount, update, event, cancellation/renewal, reset/preserve, failure, and cleanup behavior are covered.

## Suggested Work Order After Components

1. Complete nested LiveView lifecycle parity. Done for the upstream `nested_test.exs` equivalent slice.
2. Flash propagation across redirect, push navigate, and push patch. Done for the upstream `flash_test.exs` equivalent slice.
3. Design and implement async task support. Done for the first slice.
4. Build async assigns on top of async task support. Done for the first slice.
5. Revisit lifecycle hooks and align them with the final component, nested, and async runtime model.

## Verification Strategy

- Keep `./scripts/e2e-run-upstream.sh` green.
- Add Scalive-native tests for upstream integration behaviors that cannot be run directly as Elixir tests.
- For each parity matrix row, record the upstream files and the Scalive tests that cover equivalent behavior.
- Prefer small vertical slices that make one upstream scenario pass end-to-end over broad incomplete abstractions.
