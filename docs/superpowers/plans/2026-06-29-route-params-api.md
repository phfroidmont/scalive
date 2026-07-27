# Route Params API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the route-declared params API redesign and verify it across library tests, examples, docs, and upstream e2e fixtures.

**Architecture:** `LiveView` remains the base lifecycle abstraction, while `RoutedLiveView[Msg, Model, Params]` owns URL params handling. Routes declare how path and query input is decoded through `LiveParamsCodec` and pass a `LiveRouteParamsRuntime` into HTTP and socket lifecycle execution.

**Tech Stack:** Scala 3, ZIO, ZIO HTTP, ZIO Schema, Mill, Scalive e2e fixtures.

## Global Constraints

- Scalive is alpha; prefer the best user-facing API over backward compatibility.
- Match Phoenix LiveView behavior and feature set where it does not conflict with API quality.
- Keep the change focused on inbound route params; route-safe outbound rendering remains future work.
- Use `mill --ticker false __.reformat + __.fix` for formatting/fixes.
- Use `mill --ticker false __.test` for full verification.
- Do not commit unless the user explicitly asks.

---

### Task 1: Finish Route Params API Surface

**Files:**
- Modify: `scalive/src/scalive/LiveView.scala`
- Modify: `scalive/src/scalive/LiveParamsCodec.scala`
- Modify: `scalive/src/scalive/LiveRouteParamsRuntime.scala`
- Modify: `scalive/src/scalive/routing/LiveRouteDsl.scala`
- Modify: `scalive/src/scalive/routing/LiveRoute.scala`
- Modify: `scalive/src/scalive/socket/SocketBootstrap.scala`
- Modify: `scalive/src/scalive/socket/SocketInbound.scala`
- Modify: `scalive/src/scalive/socket/SocketRuntimeState.scala`
- Test: `scalive/test/src/scalive/LiveRoutesLifecycleSpec.scala`
- Test: `scalive/test/src/scalive/SocketSpec.scala`

**Interfaces:**
- Consumes: existing `LiveView[Msg, Model]`, `PathCodec[A]`, `QueryCodec[A]`, `LiveMountPipeline`.
- Produces: `RoutedLiveView[Msg, Model, Params]`, `LiveParamsCodec[PathParams, Params]`, `LiveRouteParamsRuntime[A, Msg, Model]`, DSL methods `.params`, `.query`, `.queryOptional`, `.mapParams`.

- [ ] **Step 1: Inspect the current diff for API inconsistencies**

Run: `git diff --cached -- scalive/src/scalive scalive/test/src/scalive`

Expected: staged code consistently uses `RoutedLiveView` and `LiveParamsCodec`, with no remaining `queryCodec` lifecycle field.

- [ ] **Step 2: Search for obsolete API names**

Run: `rg "LiveQueryCodec|LiveViewParamsRuntime|queryCodec|pushPatch\([^\"]*[A-Z].*Codec|link\.patch\([^\"]*[A-Z].*Codec"`

Expected: no production usage remains. Any match should be either deleted, migrated, or documentation explicitly describing removed API.

- [ ] **Step 3: Compile the changed modules**

Run: `mill --ticker false scalive.compile e2eApp.compile example.compile`

Expected: compilation succeeds. If it fails, fix only route params API integration errors before moving to e2e fixture behavior.

---

### Task 2: Finish Fixture Migration And Nested LiveView Parity

**Files:**
- Modify: `e2eApp/src/E2EApp.scala`
- Modify: `e2eApp/src/FormLiveViews.scala`
- Modify: `e2eApp/src/*LiveView.scala`
- Modify: `scalive/src/scalive/NestedLiveView.scala`
- Modify: `scalive/src/scalive/routing/LiveChannel.scala`
- Modify: `scalive/src/scalive/routing/LiveRoutesRuntime.scala`
- Modify: `scalive/src/scalive/socket/SocketComponentRuntime.scala`
- Test: `scalive/test/src/scalive/LiveRoutesLifecycleSpec.scala`
- Test: upstream e2e fixtures through `./scripts/e2e-run-upstream.sh`

**Interfaces:**
- Consumes: route-level params DSL from Task 1 and existing nested LiveView registration flow.
- Produces: migrated e2e fixtures and `phx-loading` behavior for nested LiveViews on reconnect/mounted flows.

- [ ] **Step 1: Inspect unstaged nested LiveView and fixture changes**

Run: `git diff -- e2eApp/src/E2EApp.scala e2eApp/src/FormLiveViews.scala scalive/src/scalive/NestedLiveView.scala scalive/src/scalive/routing/LiveChannel.scala scalive/src/scalive/routing/LiveRoutesRuntime.scala scalive/src/scalive/socket/SocketComponentRuntime.scala`

Expected: unstaged changes are intentional parity fixes, not accidental debug edits.

- [ ] **Step 2: Search e2e fixtures for obsolete params patterns**

Run: `rg "LiveQueryCodec|queryCodec|handleParams\([^\n]*queryCodec|\.params\([^)]*Codec\)|\.query\([^)]*Codec\)" e2eApp example scalive/test/src/scalive`

Expected: remaining codec usages are `LiveParamsCodec` custom codecs or deliberate direct route codec calls.

- [ ] **Step 3: Compile e2e fixtures**

Run: `mill --ticker false e2eApp.compile example.compile`

Expected: compilation succeeds.

---

### Task 3: Polish Documentation And Public API Reference

**Files:**
- Modify: `doc/public-api-reference.md`
- Modify: `doc/phase-context-api-design.md`
- Modify: `doc/api-improvement-ideas.md`

**Interfaces:**
- Consumes: final API names from Tasks 1 and 2.
- Produces: docs that describe `RoutedLiveView`, `LiveParamsCodec`, route DSL helpers, and outbound navigation limitations.

- [ ] **Step 1: Search docs for obsolete public API names**

Run: `rg "LiveQueryCodec|queryCodec|LiveViewParamsRuntime|pushPatch\[|link\.patch\[" doc`

Expected: no stale references unless they explicitly mention removed/deprecated ideas.

- [ ] **Step 2: Verify docs describe the final API**

Run: `rg "RoutedLiveView|LiveParamsCodec|queryOptional|mapParams|route-safe outbound" doc/public-api-reference.md doc/phase-context-api-design.md doc/api-improvement-ideas.md`

Expected: docs include the final lifecycle split, route params codec API, DSL examples, and future outbound route rendering note.

---

### Task 4: Full Verification And Cleanup

**Files:**
- Modify only files required by compile/test failures.
- Review: all changed files from `git status --short`.

**Interfaces:**
- Consumes: completed API and fixture migration from Tasks 1-3.
- Produces: formatted, tested, reviewable working tree.

- [ ] **Step 1: Run formatter and fixes**

Run: `mill --ticker false __.reformat + __.fix`

Expected: command succeeds. If it changes files, inspect the resulting diff.

- [ ] **Step 2: Run full test suite**

Run: `mill --ticker false __.test`

Expected: all tests pass.

- [ ] **Step 3: Run upstream e2e parity suite**

Run: `./scripts/e2e-run-upstream.sh`

Expected: pass or known fixture gaps only. If failures occur, diagnose root cause before fixing.

- [ ] **Step 4: Final diff review**

Run: `git status --short && git diff --stat && git diff --cached --stat`

Expected: only intended files are changed. No temporary browser logs or generated artifacts should be included unless explicitly needed.
