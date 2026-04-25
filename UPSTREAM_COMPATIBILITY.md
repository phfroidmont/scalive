# Upstream Compatibility Plan

Scalive tracks Phoenix LiveView behavior and feature coverage while keeping the Scala API ergonomic, typed, and robust. The immediate goal is to move from browser-level upstream E2E parity toward broader upstream feature parity, including server-side integration behavior.

## Current Baseline

- The upstream browser E2E harness is available via `./scripts/e2e-run-upstream.sh`.
- The last recorded Playwright run in `test-results/.last-run.json` passed.
- Scalive already has protocol-level component diff support through `component(cid, element)`, `RenderSnapshot`, and `TreeDiff`.
- Scalive does not yet appear to have a user-facing stateful `LiveComponent` abstraction or component runtime lifecycle.

## Compatibility Parity Matrix

Track upstream parity by suite or feature area, not only by individual bugs. Status values should stay coarse until we have automated parity checks for each row.

| Area | Upstream Reference | Scalive Status | Notes | Priority |
| --- | --- | --- | --- | --- |
| Browser E2E behavior | `test/e2e/tests/**/*.spec.js` | Passing baseline | Covered by `./scripts/e2e-run-upstream.sh`; keep running as regression suite. | High |
| Stateful LiveComponents | `test/phoenix_live_view/integrations/live_components_test.exs` | Major gap | Protocol diff support exists, but no full component lifecycle/runtime API is visible. | Highest |
| Nested LiveViews | `test/phoenix_live_view/integrations/nested_test.exs` | Partial/gap | Several browser E2E nested/sticky scenarios pass, but full server-side nested lifecycle parity is broader. | High |
| Flash propagation | `test/phoenix_live_view/integrations/flash_test.exs` | Gap | Depends on navigation, patch, redirect, nested LiveView, and component boundaries. | High |
| Async tasks | `test/phoenix_live_view/integrations/start_async_test.exs` | Gap | Needs a Scala API design for task lifecycle, cancellation, failures, and navigation side effects. | Medium |
| Async assigns | `test/phoenix_live_view/integrations/assign_async_test.exs` | Gap | Should probably build on the async task model. | Medium |
| Lifecycle hooks | `test/phoenix_live_view/integrations/hooks_test.exs` | Gap/partial | Scalive has `interceptEvent`; upstream hooks cover mount, event, params, info, async, and render stages. | Medium |
| Test harness helpers | `lib/phoenix_live_view/test/*` and integration tests | Not directly applicable | Scalive may need its own testing API rather than direct Phoenix API parity. | Low |

## Recommended Next Step

Implement real stateful `LiveComponent` support first.

This is the highest-leverage next step because many later upstream features depend on a real component runtime: `phx-target`, `@myself`, `send_update`, component-local events, component streams, component async, nested LiveViews inside components, and component redirect/patch behavior.

## LiveComponent Implementation Sequence

1. Define a minimal user-facing `LiveComponent` API.

   Include lifecycle methods for initialization/update/render, stable component identity, and a component-local model. Prefer the smallest API that can express upstream behavior without copying Elixir internals.

2. Add component identity and runtime storage.

   Components need stable `cid` assignment from component type and id, preserved component-local state across parent renders, removal detection, and `data-phx-component` rendering.

3. Wire component event routing.

   Support `phx-target` so events can be delivered to component-local handlers instead of the parent `LiveView`. Preserve the existing parent message API where possible.

4. Add regression tests modeled after upstream `live_components_test.exs`.

   Start with disconnected render, connected render, additions/updates/removals, event delegation, `phx-target`, and multiple targets.

5. Add dependent component features.

   Continue with `send_update`, component streams, component navigation side effects, nested LiveViews inside components, component flash behavior, and component async.

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
