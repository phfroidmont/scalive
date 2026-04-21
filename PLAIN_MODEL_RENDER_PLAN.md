# Plain-Model LiveView Render Plan

This document captures the implementation plan for moving `scalive` from a public `Dyn`-based view API to a plain-model rendering API that supports arbitrary Scala in templates, while preserving compatibility with the upstream Phoenix LiveView client protocol.

## Goals

- Make `LiveView.view` ergonomic and unconstrained: accept plain `Model` and allow arbitrary Scala.
- Remove the public API footgun where the wrong `Dyn[...]` can be used in a view.
- Preserve protocol compatibility with upstream `phoenix_live_view@1.1.8`.
- Keep high-performance paths for keyed lists and streams.

## Non-Goals

- No macro-based template rewriting.
- No requirement to infer list identity automatically (keys and stream intent stay explicit).

## High-Level Strategy

1. Move dynamic detection from compile-time API types (`Dyn`) to runtime render-tree diffing.
2. Render `view(model)` to a normalized tree every update.
3. Diff previous vs current normalized tree and emit protocol diff (`s`, numeric slots, `k`, `stream`, etc.).
4. Implement upstream optimizations in the emitted diff and retained render state (`r`, `p`, `c`, keyed move/merge, stream ephemeral semantics).

## API Changes

## 1) Core `LiveView` signature

- Update `scalive/src/scalive/LiveView.scala`:
  - from: `def view(model: Dyn[Model]): HtmlElement`
  - to: `def view(model: Model): HtmlElement`

## 2) Remove public `Dyn` surface

- Remove (or temporarily deprecate/move to `scalive.legacy`) public `Dyn` entry points:
  - `scalive/src/scalive/Dyn.scala`
  - `Dyn` overloads in `scalive/src/scalive/HtmlElement.scala`
  - `dynStringToMod` in `scalive/src/scalive/Scalive.scala`

## 3) Collections and streams

- Keep keyed semantics explicit:
  - `splitBy(items, key)(render)`
  - `splitByIndex(items)(render)`
- Keep stream API explicit (`LiveStream` and runtime stream ops remain performance knob).

## 4) Component helpers

- Update helpers using `Dyn` in `scalive/src/scalive/defs/components/Components.scala` to plain model values.

## Render Engine Design

Introduce a new runtime render subsystem (suggested package: `scalive/src/scalive/render/`).

## 1) Render compilation

`RenderCompiler` converts `HtmlElement` into normalized nodes containing:

- structural fingerprint (tag + static boundaries + node kind)
- static fragment array
- ordered dynamic slot values
- keyed metadata for comprehensions
- stream metadata
- binding descriptors

## 2) Retained snapshot

`RenderSnapshot` stores prior normalized tree and any retained state needed for delta generation.

## 3) Tree diff

`RenderDiffer` compares old/new snapshots:

- same fingerprint -> emit changed slots only
- changed fingerprint -> emit full subtree (`s` + slots)
- keyed branches -> emit keyed move/merge protocol entries
- streams -> emit stream ops with upstream semantics

## 4) Binding dispatch

Replace tree-scanning event lookup with `BindingRegistry`:

- map `bindingId -> handler`
- update registry on each snapshot update
- keep IDs stable where possible to minimize churn

## Socket/Router Integration

Update runtime state and pipelines to use snapshot-based rendering.

- `scalive/src/scalive/socket/SocketRuntimeState.scala`
  - replace `(Var[Model], HtmlElement)` state with model + snapshot state
- `scalive/src/scalive/socket/SocketBootstrap.scala`
  - initial render snapshot + initial diff
- `scalive/src/scalive/socket/SocketModelRuntime.scala`
  - render+diff after model updates
- `scalive/src/scalive/socket/SocketInbound.scala`
  - event dispatch via binding registry
- `scalive/src/scalive/socket/SocketOutbound.scala`
  - unchanged semantics, but fed by new diff source
- `scalive/src/scalive/LiveRouter.scala`
  - initial disconnected HTML generation from plain-model `view`

## Upstream Optimizations and Protocol Features

Implement all protocol-relevant render/diff optimizations expected by upstream client behavior.

## 1) Root marker and skip optimization

- Ensure node-level root marker support (`"r": 1` semantics).
- Track root render freshness to support client-side `data-phx-id` / `data-phx-skip` behavior.

## 2) Top-level side channels

- Preserve and correctly separate top-level:
  - `"e"` pushed events
  - `"t"` title updates
  - `"r"` hook replies

## 3) Keyed comprehensions

- Emit all merge forms:
  - object entry (same-position diff)
  - numeric entry (move without diff)
  - `[old_idx, diff]` (move + merge)
- Enforce keyed count shrink semantics (`"kc"`).

## 4) Streams

- Preserve exact stream wire tuple shape and semantics:
  - `[ref, inserts, deleteIds, reset]`
  - insert tuple `[domId, at, limit, updateOnly]`
- Preserve ephemeral stream retained-state behavior after application.

## 5) Shared templates (`"p"`)

- Add static dedup table for repeated statics in a diff.
- Support numeric `"s"` references into `"p"`.

## 6) Components (`"c"` map)

- Add component cache diff map and cid slot handling.
- Support statics sharing rules:
  - positive cid `s`
  - negative cid `s`
- Implement component lifecycle protocol integration:
  - `cids_will_destroy`
  - `cids_destroyed`

## Performance Plan

- Structural fingerprint interning for fast shape equality checks.
- Subtree hash short-circuit to skip deep recursion when unchanged.
- Stable binding ID allocation strategy to reduce rebinding noise.
- Keep explicit keyed + stream APIs for high-churn collections.
- Optional `memo` helper for expensive subtrees if needed later.

## Migration Plan (Phased)

## Phase A - Parallel engine scaffold

- Add new render subsystem behind feature flag.
- Keep current `Dyn`-based path operational for parity checks.

## Phase B - Protocol parity in new path

- Implement core `Tag`/slot diff generation.
- Port keyed/stream semantics in new differ.
- Add top-level events/title/hook-reply side channels.

## Phase C - API migration

- Switch `LiveView.view` to plain model.
- Migrate example app and e2e app views.
- Introduce temporary compatibility shims only if strictly needed.

## Phase D - Upstream advanced optimizations

- Implement templates (`p`) and components (`c`) semantics.
- Implement component destroy messages.
- Remove legacy path after parity and stability are confirmed.

## Phase E - Cleanup

- Remove public `Dyn` API and old diff assumptions.
- Remove remaining runtime `???` gaps tied to legacy renderer.

## Validation and Test Plan

## 1) Unit tests

- Update/add tests in:
  - `scalive/test/src/scalive/LiveViewSpec.scala`
  - `scalive/test/src/scalive/SocketSpec.scala`
  - `scalive/test/src/scalive/HtmlBuilderSpec.scala`
- Add golden tests for:
  - top-level `e/t/r`
  - root marker behavior
  - keyed move/merge forms
  - stream tuple semantics
  - template table (`p`)
  - component map (`c`) rules

## 2) E2E compatibility

- Keep validating against upstream specs via:
  - `./scripts/e2e-run-compatible.sh`
- Expand allowlist only when protocol semantics are proven.

## 3) Regression checks

- Measure diff payload size on representative fixtures.
- Measure update latency and allocations for:
  - large keyed tables
  - stream-heavy pages
  - navigation and upload flows

## Risks and Mitigations

- Risk: larger diffs for frequent updates.
  - Mitigation: keyed/stream explicit APIs + subtree hashes + template sharing.
- Risk: binding ID instability causes event mismatch.
  - Mitigation: deterministic ID derivation and binding registry tests.
- Risk: protocol drift during migration.
  - Mitigation: golden fixtures + upstream e2e gate before switching default path.

## Delivery Checklist

- [x] Plain-model `LiveView.view` API merged.
- [x] New runtime render differ integrated in socket path.
- [x] Keyed and stream semantics preserved in new differ.
- [x] Top-level `e/t/r` semantics preserved.
- [x] Root marker semantics aligned with upstream behavior.
- [x] Shared templates (`p`) implemented.
- [x] Components (`c`) and cid destruction messages implemented.
- [x] Legacy `Dyn` public API removed (or explicitly isolated as legacy).
- [x] Unit + e2e compatibility suite green.
