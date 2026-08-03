# Scalive Examples Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the prototype `example` app with one runnable `examples` showcase covering Scalive's major application patterns.

**Architecture:** One Mill module owns a shared server, asset pipeline, typed route catalog, and application shell. Focused LiveViews live in feature packages; callback services are constructor-injected, while authentication is supplied through a session `LiveMountAspect`.

**Tech Stack:** Scala 3.7.3, ZIO 2, ZIO HTTP 3, ZIO JSON, ZIO Test, Mill, Tailwind 4, DaisyUI 5, Phoenix LiveView client 1.1.28

> **Current implementation note:** This plan is a historical execution record. The
> implemented auth flow now uses reusable browser-bound framework CSRF,
> `AuthHttpRoutes` and the authenticated Live routes share one `AuthService` route
> environment provided once at the server boundary, and logout is idempotent after
> valid CSRF while always expiring the cookie. Forms now prefer rooted
> `FormRoot`/`FormDefinition`/`RootedForm`; `Form.of` remains the low-level API. See
> [`examples/README.md`](../../../examples/README.md) and
> [`docs/public-api-reference.md`](../../public-api-reference.md) for current usage.

## Global Constraints

- Rename `example` to `examples`; do not retain a compatibility module or duplicate sources.
- Use named packages rooted at `scalive.examples` and public `scalive.*` APIs only.
- Keep one server, one asset pipeline, one root layout, and one categorized catalog.
- Keep examples focused and independent except for the shared shell and explicitly demonstrated services.
- Do not add a database, OAuth provider, browser tests, or JavaScript dependencies.
- Application services used by `LiveIO` callbacks are constructor-injected; authentication uses the route environment and `LiveMountAspect`.
- Authentication uses application CSRF tokens, a high-entropy opaque `HttpOnly` `SameSite=Lax` cookie, revocable in-memory sessions, and non-secret mount claims.
- Uploads use small limits and an application-owned temporary store with deletion and shutdown cleanup.
- Add unit tests only for authentication behavior and typed profile form validation.
- Keep exhaustive parity behavior, nested LiveViews, portals, crash/reconnect fixtures, and advanced upload/stream variants in `e2eApp`.
- Do not commit changes unless the user explicitly requests a commit.

---

### Task 1: Module Migration And Showcase Shell

**Files:**
- Modify: `build.mill`
- Delete: current Scala sources under `example/src`
- Move: `example/package.json`, `example/package-lock.json`, and `example/assets/**` to `examples/`
- Create: `examples/src/scalive/examples/ExamplesApp.scala`
- Create: `examples/src/scalive/examples/ExamplesRoutes.scala`
- Create: `examples/src/scalive/examples/ExampleCatalog.scala`
- Create: `examples/src/scalive/examples/ExamplesRootLayout.scala`
- Create: `examples/src/scalive/examples/ExamplesLayout.scala`
- Create: `examples/src/scalive/examples/HomeLiveView.scala`

**Interfaces:**
- `ExampleEntry(category: String, title: String, description: String, location: LiveLocation)` describes one catalog card.
- `ExamplesRoutes.home` is the root typed route.
- `ExamplesApp.liveRoutes` explicitly registers the home route under the root and application layouts.

- [ ] Rename the Mill object and directory from `example` to `examples`; keep `NpmAssets`, ZIO logging, resource bundling, and remove Monocle.
- [ ] Preserve the existing package manifest, lockfile, Tailwind source scanning, LiveSocket bootstrap, CSRF connection params, topbar, and tracked static assets.
- [ ] Implement a responsive document root, persistent categorized navigation layout, and eventless catalog home page.
- [ ] Run `mill --ticker false examples.compile + examples.bundle`; expect both targets to pass.
- [ ] Run `git diff --check`; expect no whitespace errors.

---

### Task 2: State, Services, Subscriptions, And Async Work

**Files:**
- Modify: `examples/src/scalive/examples/ExamplesApp.scala`
- Modify: `examples/src/scalive/examples/ExamplesRoutes.scala`
- Modify: `examples/src/scalive/examples/ExampleCatalog.scala`
- Create: `examples/src/scalive/examples/state/ShoppingCartLiveView.scala`
- Create: `examples/src/scalive/examples/services/Guestbook.scala`
- Create: `examples/src/scalive/examples/services/GuestbookLiveView.scala`
- Create: `examples/src/scalive/examples/processing/ClockLiveView.scala`
- Create: `examples/src/scalive/examples/processing/AsyncReportLiveView.scala`

**Interfaces:**
- `Guestbook.entries: UIO[Vector[Guestbook.Entry]]` and `Guestbook.add(author: String, message: String): UIO[Guestbook.Entry]` are backed by one `Ref` supplied through `Guestbook.live`.
- The clock uses one declared `SubscriptionKey`; the report uses one declared `AsyncKey[Report]` and `AsyncValue[Report]`.

- [ ] Add `/state/shopping-cart` with typed add/remove/clear messages, connection-local state, derived totals, and keyed rows.
- [ ] Add `/services/guestbook`; construct one `Guestbook` from a `ZLayer` in `ExamplesApp`, inject it into the view, and reload the UI snapshot after mutations.
- [ ] Add `/processing/subscriptions` with a clock stream and start, replace, and cancel controls; do not fork fibers manually.
- [ ] Add `/processing/async` with deterministic success/failure tasks, cancellation, retry/replacement, `AsyncValue`, typed success messages, and failure handling through `LiveHooks.async`.
- [ ] Register each route and catalog entry explicitly.
- [ ] Run `mill --ticker false examples.compile`; expect success.

---

### Task 3: Authentication Service And Protected Routes

**Files:**
- Modify: `build.mill`
- Modify: `examples/src/scalive/examples/ExamplesApp.scala`
- Modify: `examples/src/scalive/examples/ExamplesRoutes.scala`
- Modify: `examples/src/scalive/examples/ExampleCatalog.scala`
- Create: `examples/src/scalive/examples/auth/AuthService.scala`
- Create: `examples/src/scalive/examples/auth/AuthHttpRoutes.scala`
- Create: `examples/src/scalive/examples/auth/AuthMountAspect.scala`
- Create: `examples/src/scalive/examples/auth/LoginLiveView.scala`
- Create: `examples/src/scalive/examples/auth/ProfileLiveView.scala`
- Create: `examples/test/src/scalive/examples/auth/AuthServiceSpec.scala`

**Interfaces:**
- `AuthService.beginLogin`, `AuthService.login`, `AuthService.authenticate`, `AuthService.resume`, and `AuthService.logout` own CSRF, credentials, opaque cookie tokens, public session IDs, and revocation.
- `CurrentSession` contains the typed current user, public session ID, and logout CSRF token.
- `AuthClaims` derives `JsonCodec` and contains only the public session ID.

- [ ] Add a nested `examples.test` ZIO Test module using version `2.1.25`.
- [ ] Write failing tests for invalid credentials, single-use login CSRF, cookie authentication, claims-based resumption, invalid logout CSRF, revocation, and logout invalidation.
- [ ] Run `mill --ticker false examples.test.testOnly scalive.examples.auth.AuthServiceSpec`; verify the tests fail for missing behavior.
- [ ] Implement the minimal in-memory service using `Ref` and high-entropy random tokens; rerun the focused test and expect success.
- [ ] Render a public Live login form posting normally to `POST /auth/session`; add `POST /auth/logout`, cookie set/expiry, CSRF validation, generic invalid-login redirect, and typed success redirects.
- [ ] Protect `/auth/profile` with `Live.session("authenticated").withMountAspect(...)`; authenticate the cookie disconnected and resume from public claims connected.
- [ ] Run the focused auth tests and `mill --ticker false examples.compile`; expect success.

---

### Task 4: Typed Forms And Uploads

**Files:**
- Modify: `examples/src/scalive/examples/ExamplesApp.scala`
- Modify: `examples/src/scalive/examples/ExamplesRoutes.scala`
- Modify: `examples/src/scalive/examples/ExampleCatalog.scala`
- Create: `examples/src/scalive/examples/forms/ProfileFormLiveView.scala`
- Create: `examples/test/src/scalive/examples/forms/ProfileFormCodecSpec.scala`
- Create: `examples/src/scalive/examples/uploads/UploadStore.scala`
- Create: `examples/src/scalive/examples/uploads/DocumentUploadLiveView.scala`

**Interfaces:**
- `Profile(name: String, email: String, biography: String)` is decoded by one explicit `FormCodec[Profile]` that accumulates path-specific errors.
- `UploadStore.save`, `UploadStore.delete`, and `UploadStore.entries` own files beneath one scoped temporary directory.

- [ ] Write failing form codec tests for blank fields, malformed email, oversized biography, accumulated errors, and valid input.
- [ ] Run `mill --ticker false examples.test.testOnly scalive.examples.forms.ProfileFormCodecSpec`; verify expected failures.
- [ ] Implement `/forms/profile` with `Form.of`, typed change/submit events, used-field error display, and saved-success state; rerun the codec tests and expect success.
- [ ] Add a scoped `UploadStore` layer that deletes its process directory on release.
- [ ] Implement `/uploads/documents` for `.txt` and `.md`, at most two files, a 1 MiB per-file limit, progress, validation, cancellation, completed-entry consumption, stored metadata, and deletion.
- [ ] Render a connecting state during disconnected mount and call `ctx.uploads.allow` only when connected.
- [ ] Run `mill --ticker false examples.test + examples.compile`; expect success.

---

### Task 5: Navigation, Streams, And Components

**Files:**
- Modify: `examples/src/scalive/examples/ExamplesApp.scala`
- Modify: `examples/src/scalive/examples/ExamplesRoutes.scala`
- Modify: `examples/src/scalive/examples/ExampleCatalog.scala`
- Create: `examples/src/scalive/examples/navigation/SearchLiveView.scala`
- Create: `examples/src/scalive/examples/collections/ActivityStreamLiveView.scala`
- Create: `examples/src/scalive/examples/components/VoteComponent.scala`
- Create: `examples/src/scalive/examples/components/ComponentsLiveView.scala`

**Interfaces:**
- `SearchParams(query: Option[String], page: Option[Int]) derives Schema` is the final typed route parameter domain.
- The activity model stores queryable `Vector[Activity]` separately from its opaque `LiveStream[Activity]` handle.
- Components demonstrate self-targeted local events, `toComponent` parent messages, and `sendUpdate`; no child-to-parent callback is implied.

- [ ] Add `/navigation/search` with typed query locations, patch, replace-patch, navigate, and `handleParams`; avoid unsafe query-only paths.
- [ ] Add `/collections/activity` with `LiveStreamDef.byId`, insert, delete, reset, and `StreamLimit.KeepLast`; keep durable items in ordinary state.
- [ ] Render streams under `phx.onUpdate := "stream"` and use every supplied DOM ID.
- [ ] Add `/components/voting` with reusable stateful components, stable IDs, local events, typed parent-to-component messages, and prop updates.
- [ ] Run `mill --ticker false examples.compile`; expect success.

---

### Task 6: Browser Interop And Lifecycle UX

**Files:**
- Modify: `examples/assets/js/app.js`
- Modify: `examples/src/scalive/examples/ExamplesApp.scala`
- Modify: `examples/src/scalive/examples/ExamplesRoutes.scala`
- Modify: `examples/src/scalive/examples/ExampleCatalog.scala`
- Create: `examples/src/scalive/examples/interop/BrowserInteropLiveView.scala`
- Create: `examples/src/scalive/examples/lifecycle/NotificationsLiveView.scala`

**Interfaces:**
- One `ClientEvent[CopyRequest]` carries a typed server-to-client payload; a named raw hook event reports the browser result to Scala.
- The lifecycle page uses connected state, flash, title, and a side-effect-only after-render hook.

- [ ] Register one focused JavaScript hook in `LiveSocket`; keep the current CSRF, topbar, and debug setup.
- [ ] Add `/interop/browser` with a composed show/hide command, typed client push, JavaScript `handleEvent`, hook `pushEvent`, and `LiveHooks.rawEvent` result handling.
- [ ] Add `/lifecycle/notifications` with connected-state display, keyed flash put/clear, title changes, and a side-effect-only after-render hook.
- [ ] Preserve a static fallback `<title>` in the root layout and avoid `JS.push` page-loading options.
- [ ] Run `mill --ticker false examples.compile + examples.bundle`; expect success.

---

### Task 7: Documentation And Final Verification

**Files:**
- Create: `examples/README.md`
- Modify: `README.md`
- Modify: any examples source required by formatter, compiler, or final integration fixes

- [ ] Document `mill examples.run`, `SCALIVE_SERVER_PORT`, demo credentials, every catalog route and source file, in-memory data lifetime, temporary upload cleanup, auth scope, and deferred advanced examples.
- [ ] Add a manual smoke checklist covering every catalog interaction.
- [ ] Update root README references from `example` to `examples`, document the run command, and fix current `doc/...` links to `docs/...`.
- [ ] Run `mill --ticker false __.reformat + __.fix`; expect success.
- [ ] Run `mill --ticker false examples.compile + examples.bundle + examples.test`; expect success.
- [ ] Run `mill --ticker false __.test`; expect all repository tests to pass.
- [ ] Run `git diff --check`; expect no whitespace errors.
- [ ] Inspect `git status --short` and ensure only intended source, asset, documentation, and build changes remain.
