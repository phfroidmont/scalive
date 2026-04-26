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
| Stateful LiveComponents | `test/phoenix_live_view/integrations/live_components_test.exs` | Partial | Core runtime exists: lifecycle, stable cid, local/nested component events, form events, upload progress, typed `sendUpdate`, selector/multiple `phx-target`, connected nested LiveViews inside components, removal cleanup, patch navigation, client effects, component flash, and component-scoped stream state. Remaining gaps include async and broader flash navigation propagation. | Highest |
| Nested LiveViews | `test/phoenix_live_view/integrations/nested_test.exs` | Partial | Connected nested LiveViews can be registered, joined, handle isolated events, clean up on parent/child leave, keep stable topics across parent re-renders/patches, defer render-removal cleanup until client confirmation, and emit child-scoped navigation. Remaining gaps include disconnected parity, sticky nested LiveViews, and broader navigation/lifecycle edge cases. | High |
| Flash propagation | `test/phoenix_live_view/integrations/flash_test.exs` | Partial | Socket-scoped flash state exists with root/component APIs, render helpers, keyed/all clear, built-in `lv:clear-flash`, and nested socket isolation. Remaining gaps include redirect propagation and broader patch/navigation parity. | High |
| Async tasks | `test/phoenix_live_view/integrations/start_async_test.exs` | Gap | Needs a Scala API design for task lifecycle, cancellation, failures, and navigation side effects. | Medium |
| Async assigns | `test/phoenix_live_view/integrations/assign_async_test.exs` | Gap | Should probably build on the async task model. | Medium |
| Lifecycle hooks | `test/phoenix_live_view/integrations/hooks_test.exs` | Gap/partial | Scalive has `interceptEvent`; upstream hooks cover mount, event, params, info, async, and render stages. | Medium |
| Test harness helpers | `lib/phoenix_live_view/test/*` and integration tests | Not directly applicable | Scalive may need its own testing API rather than direct Phoenix API parity. | Low |

## Recommended Next Step

Continue closing the remaining stateful `LiveComponent` gaps with small vertical slices.

The core runtime is in place, so the highest-leverage follow-up work is now targeted parity around flash navigation propagation, component async, and remaining nested LiveView disconnected/sticky lifecycle cases.

## LiveComponent Implementation Sequence

1. Define a minimal user-facing `LiveComponent` API. Done.

   Include lifecycle methods for initialization/update/render, stable component identity, and a component-local model. Prefer the smallest API that can express upstream behavior without copying Elixir internals.

2. Add component identity and runtime storage. Done.

   Components need stable `cid` assignment from component type and id, preserved component-local state across parent renders, removal detection, and `data-phx-component` rendering.

3. Wire component event routing. Done for component-local events and `@myself`-style targeting.

   Support `phx-target` so events can be delivered to component-local handlers instead of the parent `LiveView`. Component event, form, upload progress, nested component, selector/multiple-target, and mismatched-cid guard coverage exists.

4. Add regression tests modeled after upstream `live_components_test.exs`. In progress.

   Covered so far: connected render, stable ids, duplicate-id rejection, removals after `cids_destroyed`, event delegation, form events, upload progress, nested components, connected nested LiveViews inside components, selector/multiple `phx-target`, `sendUpdate`, streams, navigation, client effects, and flash. Remaining component tests should focus on async and broader flash navigation propagation.

5. Add dependent component features. In progress.

   `send_update`, component streams, component navigation side effects, component client effects, component flash behavior, and connected nested LiveViews inside components are covered. Continue with component async and broader flash navigation propagation.

## Suggested Work Order After Components

1. Complete nested LiveView lifecycle parity.
2. Implement flash propagation across redirect, push navigate, and push patch.
3. Design and implement async task support.
4. Build async assigns on top of async task support.
5. Revisit lifecycle hooks and align them with the final component, nested, and async runtime model.

## Verification Strategy

- Keep `./scripts/e2e-run-upstream.sh` green.
- Add Scalive-native tests for upstream integration behaviors that cannot be run directly as Elixir tests.
- For each parity matrix row, record the upstream files and the Scalive tests that cover equivalent behavior.
- Prefer small vertical slices that make one upstream scenario pass end-to-end over broad incomplete abstractions.
