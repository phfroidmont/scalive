# Upstream Diffing Optimization Checklist

Scope: server-side diffing optimizations present in upstream `phoenix_live_view@1.1.8` but missing or partial in this repository.

Priority legend:
- `P0`: highest impact + relatively low implementation risk
- `P1`: high impact, moderate effort
- `P2`: medium impact or requires prerequisite work
- `P3`: high complexity / architectural work

- [x] **P0 - Implemented**: template-table compression for comprehension statics (`s` template ref in keyed payloads).
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:540`, `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:752`
  - Current: `scalive/src/scalive/Diff.scala:17`, `scalive/src/scalive/Diff.scala:138`, `scalive/src/scalive/TreeDiff.scala:348`

- [x] **P1 - Implemented**: stream-specific render-print elision (skip storing keyed entry fingerprints for stream comprehensions).
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:739`
  - Current: `scalive/src/scalive/streams/LiveStream.scala:51`, `scalive/src/scalive/CollectionOps.scala:18`

- [x] **P1 - Implemented**: cross-component static reuse by component template fingerprint tree (`maybe_reuse_static` equivalent).
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:1022`
  - Current: `scalive/src/scalive/TreeDiff.scala:233`, `scalive/src/scalive/TreeDiff.scala:238`

- [x] **P2 - Implemented**: fingerprint-tree state for nested render identity and traversal reuse.
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:40`, `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:390`
  - Current: `scalive/src/scalive/socket/SocketRuntimeState.scala:122`

- [x] **P2 - Implemented**: runtime fingerprint-based change tracking to skip unchanged dynamic subtrees (`__changed__` equivalent).
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/engine.ex:204`
  - Current: `scalive/src/scalive/RenderSnapshot.scala:44`, `scalive/src/scalive/TreeDiff.scala:57`

- [x] **P2 - Implemented**: keyed-entry fingerprint delta tracking (`vars_changed` equivalent) for comprehension entries.
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:686`
  - Current: `scalive/src/scalive/RenderSnapshot.scala:29`, `scalive/src/scalive/TreeDiff.scala:170`

- [ ] **P3 - Missing**: compile-time static/dynamic render artifact generation (engine hoisting).
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/engine.ex:165`
  - Current: `scalive/src/scalive/RenderSnapshot.scala:44`, `scalive/src/scalive/socket/SocketModelRuntime.scala:118`

- [ ] **P3 - Missing**: stateful component diff scheduling and batching (`pending` + `update_many`).
  - Upstream: `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:800`, `.e2e-upstream/phoenix_live_view/48386116c3bfe18592aaa3dcc3238aaaf1524d3b/lib/phoenix_live_view/diff.ex:856`
  - Current: `scalive/src/scalive/HtmlElement.scala:70`


Recommended implementation order:
1. `P0`: comprehension static template-table compression.
2. `P1`: stream render-print elision and deeper cross-component static reuse.
3. `P2`: fingerprint-tree state, then `__changed__` and per-entry `vars_changed` tracking.
4. `P3`: compile-time render artifact generation and stateful component diff scheduler.
