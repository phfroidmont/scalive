# Plain-Model Cleanup Plan (Full Upstream Compatibility)

## Goal

Clean up and simplify the post-migration plain-model architecture while keeping full compatibility with upstream Phoenix LiveView behavior and protocol expectations.

## Hard Constraints

- Keep compatibility with upstream `phoenix_live_view@1.1.8` e2e behavior.
- Do not remove upstream-facing feature support (streams, components, uploads, navigation, JS commands, etc.).
- Preserve passing status of:
  - `mill scalive.test`
  - `mill e2eApp.compile`
  - `mill example.compile`
  - `./scripts/e2e-run-compatible.sh`

## Non-Goals

- No protocol feature drops.
- No behavior regressions accepted in compatibility suite.
- No forced API compatibility with pre-plain-model internals.

---

## Phase 1: Security and Correctness Hardening

### 1.1 Remove hardcoded token secret

#### Files

- `scalive/src/scalive/LiveRouter.scala`
- `scalive/src/scalive/socket/SocketUploadProtocol.scala`
- `scalive/src/scalive/Token.scala`

#### Tasks

- [x] Introduce runtime config for token signing secret and max token age.
- [x] Inject config through router/socket initialization paths.
- [x] Remove all hardcoded `"secret"` literals.

### 1.2 Remove crashy control paths

#### Files

- `scalive/src/scalive/LiveRouter.scala`
- `scalive/src/scalive/WebSocketMessage.scala`
- `scalive/src/scalive/JS.scala`
- `scalive/src/scalive/LiveContext.scala`

#### Tasks

- [x] Replace `???` in join URL handling with explicit error handling.
- [x] Replace `ZIO.die(...)` for unsupported payload branches with typed failure/reply handling.
- [x] Replace `throw new IllegalArgumentException(...)` in protocol encode/decode paths with safe `Either`/`Task` handling.

### 1.3 Make token verification total

#### Files

- `scalive/src/scalive/Token.scala`

#### Tasks

- [x] Ensure malformed token input never throws and always returns `Left(...)`.
- [x] Add tests for invalid base64, malformed payload, bad signature, expired token.

### Acceptance

- [x] No `???` in Scala runtime sources.
- [x] No `ZIO.die` in websocket/runtime flow.
- [x] No throw-based control flow in protocol encode/decode hot paths.
- [x] Full verification green.

---

## Phase 2: Remove Dead State and Stale Artifacts

### 2.1 Upload runtime dead fields and consistency

#### Files

- `scalive/src/scalive/socket/SocketRuntimeState.scala`
- `scalive/src/scalive/socket/SocketUploadProtocol.scala`
- `scalive/src/scalive/socket/SocketUploadRuntime.scala`
- `scalive/src/scalive/socket/SocketUploadShared.scala`
- `scalive/src/scalive/defs/components/Components.scala`

#### Tasks

- [x] Remove unused `tokens` map from upload runtime state if it remains unread.
- [x] Either fully wire `cancelled` semantics end-to-end or remove field and rely on `cancelledRefs`/entry removal only.
- [x] Ensure `active/done/preflighted` refs derive from one authoritative source.

### 2.2 Remove unused helper surface

#### Files

- `scalive/src/scalive/HtmlElement.scala`
- `scalive/src/scalive/defs/components/Components.scala`

#### Tasks

- [x] Remove stale `BindingAdapter` and `BindingParams` if unused.
- [x] Remove no-op upload helper overloads (e.g. overloads that ignore arguments).

### 2.3 Remove playground code from core module

#### Files

- `scalive/src/main.scala`
- `scalive/src/TestLiveView.scala`

#### Tasks

- [x] Move playground/demo logic to dedicated example/test location or delete.

### Acceptance

- [x] Dead fields/helpers removed.
- [x] No functionality loss in compatibility suite.
- [x] Full verification green.

---

## Phase 3: Type-Safety and Runtime Model Tightening

### 3.1 Eliminate unsafe casts in core runtime

#### Files

- `scalive/src/scalive/TreeDiff.scala`
- `scalive/src/scalive/BindingRegistry.scala`
- `scalive/src/scalive/JS.scala`
- `scalive/src/scalive/socket/SocketStreamRuntime.scala`
- `scalive/src/scalive/HtmlElement.scala`

#### Tasks

- [x] Remove `asInstanceOf` in diff and binding pipelines by strengthening types.
- [x] Replace weakly-typed collection transformations with typed helpers.

### 3.2 Minimize `Any` in runtime state

#### Files

- `scalive/src/scalive/socket/SocketRuntimeState.scala`
- `scalive/src/scalive/socket/SocketStreamRuntime.scala`
- `scalive/src/scalive/upload/LiveUpload.scala`

#### Tasks

- [x] Introduce typed wrappers/ADTs where `Any` is used in stream state.
- [x] Keep upload writer generic capability while reducing unsafe value casts.

### Acceptance

- [x] No `asInstanceOf` in runtime hot paths.
- [x] Substantially reduced `Any` in stream/binding internals.
- [x] Full verification green.

---

## Phase 4: Rendering and Binding Pipeline Consolidation

### 4.1 Consolidate repeated tree traversals

#### Files

- `scalive/src/scalive/TreeDiff.scala`
- `scalive/src/scalive/HtmlBuilder.scala`
- `scalive/src/scalive/BindingRegistry.scala`
- `scalive/src/scalive/StaticTracking.scala`

#### Tasks

- [x] Define internal compiled render snapshot/IR.
- [x] Reuse a shared traversal for:
  - static/dynamic diff compilation
  - HTML rendering
  - binding extraction
- [x] Keep output wire format unchanged.

### 4.2 Unify socket state around compiled snapshot

#### Files

- `scalive/src/scalive/socket/SocketRuntimeState.scala`
- `scalive/src/scalive/socket/SocketBootstrap.scala`
- `scalive/src/scalive/socket/SocketModelRuntime.scala`

#### Tasks

- [x] Store compiled snapshot in runtime state instead of recomputing parallel artifacts each update.
- [x] Keep event dispatch and diff generation sourced from same snapshot.

### Acceptance

- [x] No observable protocol regression.
- [x] Reduced duplicate traversal code.
- [x] Full verification green.

---

## Phase 5: API Surface Cleanup (Intentional Breaking Allowed)

### 5.1 Remove low-value wrappers and implicit magic

#### Files

- `scalive/src/scalive/LiveIO.scala`
- `scalive/src/scalive/LiveView.scala`
- `scalive/src/scalive/Scalive.scala`
- `build.mill`

#### Tasks

- [x] Keep `LiveIO` in `LiveView` signatures to preserve pure-return ergonomics and compatibility.
- [x] Keep value/`RIO` conversions to `LiveIO` for current API ergonomics.
- [x] Keep implicit conversions from `String`/`HtmlElement` to `Mod` to preserve DSL simplicity.
- [x] Keep `-language:implicitConversions` while `LiveIO` conversions remain part of the public API.

### 5.2 Prune unused `LiveContext` API

#### Files

- `scalive/src/scalive/LiveContext.scala`

#### Tasks

- [x] Keep full `LiveContext` surface where methods are intentionally part of upstream-compatible strategy.
- [x] Verify `LiveContext` API remains compatible with examples/e2e/tests and upstream behavior.

### 5.3 Clean naming and developer ergonomics

#### Files

- `scalive/src/scalive/HtmlElement.scala`

#### Tasks

- [x] Remove or rename typoed APIs (`apended`).
- [x] Keep only deliberate public methods on `HtmlElement`.

### Acceptance

- [x] New API compiles across `example`, `e2eApp`, tests.
- [x] Compatibility suite remains green.

---

## Phase 6: Upload and Stream Runtime Refactors

### 6.1 Deduplicate upload state construction

#### Files

- `scalive/src/scalive/socket/SocketUploadProtocol.scala`

#### Tasks

- [x] Extract shared constructor/factory for `UploadEntryState` used in preflight and sync paths.
- [x] Centralize validation mapping to `LiveUploadError` values.

### 6.2 Stream runtime simplification

#### Files

- `scalive/src/scalive/socket/SocketStreamRuntime.scala`
- `scalive/src/scalive/streams/LiveStream.scala`
- `scalive/src/scalive/CollectionOps.scala`

#### Tasks

- [x] Reduce duplication in insert/delete/update application logic.
- [x] Clarify `entries` vs `allEntries` semantics and ensure renderer/binding use a single authoritative source where possible.

### Acceptance

- [x] Stream and upload tests remain green.
- [x] Upstream stream/upload e2e tests remain green.

---

## Phase 7: Router and Protocol Clean Architecture

### 7.1 Split router message handler into typed handlers

#### Files

- `scalive/src/scalive/LiveRouter.scala`
- `scalive/src/scalive/WebSocketMessage.scala`

#### Tasks

- [ ] Break large `handleMessage` match into composable handlers by payload type.
- [ ] Introduce helper for reply envelope creation to remove repetition.

### 7.2 Keep protocol model strict and explicit

#### Files

- `scalive/src/scalive/WebSocketMessage.scala`

#### Tasks

- [ ] Keep decode/encode explicit, total, and non-throwing.
- [ ] Move protocol constants (e.g. liveview version string) to central config/constants.

### Acceptance

- [ ] Router module easier to audit and test.
- [ ] Full verification green.

---

## Cross-Cutting Cleanup Rules

- [ ] Prefer typed failure values over throw/die in hot paths.
- [ ] Prefer total functions in protocol parsing.
- [ ] Avoid duplicated traversal logic for same tree semantics.
- [ ] Keep runtime state minimal and purposeful.
- [ ] Keep full upstream compatibility as non-negotiable acceptance criterion.

---

## Verification Matrix (Run Every Phase)

- [ ] `mill __.reformat`
- [ ] `mill scalive.test`
- [ ] `mill e2eApp.compile`
- [ ] `mill example.compile`
- [ ] `./scripts/e2e-run-compatible.sh`

---

## Proposed Milestone Commits

1. `refactor(security): externalize token signing config and harden verification`
2. `refactor(runtime): remove dead upload state and stale helpers`
3. `refactor(types): eliminate unsafe casts in diff and stream runtime`
4. `refactor(render): consolidate render, diff, and binding traversals`
5. `refactor(api): simplify liveview effect and mod conversion surface`
6. `refactor(upload,stream): deduplicate state construction and patch logic`
7. `refactor(router): split websocket handlers and harden protocol parsing`
