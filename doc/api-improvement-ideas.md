# Scalive API Improvement Ideas

This document collects backlog items for the current public API. It focuses on coherence, ergonomics, type safety, and discoverability.

The phase-specific context API is specified in `doc/phase-context-api-design.md`. Do not duplicate those design decisions here; keep this document focused on remaining implementation work and independent API improvements.

## Design Goals

- Keep `LiveView[Msg, Model]` as the core mental model.
- Preserve message-typed HTML, JS, and component APIs.
- Prefer Scala-first typed APIs over direct Phoenix callback tuple parity.
- Keep app-author APIs small and obvious.
- Move runtime/protocol details behind explicit internal APIs or separate test-support modules.

## Highest Priority

### Implement the phase context API design

Current issue:

- The current public lifecycle API still exposes broad `LiveContext` operations and ZIO environment capabilities.
- The target API is now locked in `doc/phase-context-api-design.md`.

Ideas:

- Implement explicit phase-specific context values and domain facades.
- Keep `LiveIO[A]` as the public lifecycle effect without exposing ZIO environment capabilities.
- Move command-recording and mounted runtime test helpers to a separate `scalive-test` module.
- Update `doc/public-api-reference.md` once the implementation lands.

### Add typed outbound routes and locations

Current issue:

- Inbound routes use typed `PathCodec[A]`, but outbound links and navigation mostly use raw strings.
- `LiveQueryCodec` models only query strings but is accepted by full navigation and redirect methods.

Ideas:

- Introduce a typed `LiveLocation[A]` or `LiveRouteRef[A]` that can render full paths with route params and query params.
- Let route declarations expose typed link builders.
- Add overloads for `link.navigate`, `link.patch`, `ctx.nav.pushNavigate`, `ctx.nav.redirect`, `JS.navigate`, and `JS.patch` that accept typed locations.
- Keep string overloads for escape hatches, but make typed APIs the documented path.
- Separate query-only patching from full navigation in method names and accepted types.

### Fix the upload writer extension point

Current issue:

- `LiveUploadWriter.init` returns `LiveUploadWriterState`, but `LiveUploadWriterState` has a `private[scalive]` constructor.
- External users cannot implement custom upload writers cleanly.

Ideas:

- Make `LiveUploadWriter` type-parameterized by its state type.
- Alternatively expose safe `LiveUploadWriterState.apply(value)` and `valueAs[A]` helpers.
- Keep the in-memory writer as a built-in implementation.
- Add examples for filesystem, S3-compatible, and external upload writers.

### Clarify public stream state versus stream commands

Current issue:

- `LiveStream.entries` currently means pending insert entries, not current stream contents.
- The current snapshot exists internally as `snapshotEntries`.
- The phase context design keeps stream command recording out of app-author lifecycle contexts.

Ideas:

- Rename pending insert entries to `pendingInserts` or make them private.
- Keep durable business state in the app model, not in public stream runtime state.
- Expose stream command assertions only from the future `scalive-test` module.
- Update examples that use `model.items.entries.find(...)` as state lookup.

### Add first-class documentation

Current issue:

- There is no newcomer-facing README, installation guide, first LiveView tutorial, or public API guide.
- JS setup, socket path, static asset hashing, and routing setup are only discoverable from examples.

Ideas:

- Add a root `README.md` with quickstart, dependency setup, and a minimal LiveView.
- Add guides for routing/layouts, events, forms, streams, uploads, components, JS commands, static assets, and deployment configuration.
- Keep e2e fixtures separate from human-oriented examples.
- Add a compatibility guide that maps Phoenix concepts to Scalive concepts.

## Correctness Fixes With API Impact

### Fix `JS.push(pageLoading)` encoding

Current issue:

- `pageLoading = false` is serialized and `pageLoading = true` is omitted.

Ideas:

- Encode `pageLoading` only when true.
- Add a JS command encoding test.
- Add an example showing `JS.push(..., pageLoading = true)`.

### Make attribute value bindings safe

Current issue:

- `withValue` indexes the params map directly.
- `withBoolValue` can throw on missing or unexpected values.

Ideas:

- Add `withValueOption(f: Option[String] => Msg)`.
- Change `withValue` to use an empty string fallback or return a validation result.
- Add `withBooleanValueOption` or `withChecked` for checkbox-style values.
- Keep the existing methods only if their throwing behavior is intentional and documented.

### Preserve array form path segments

Current issue:

- `FormPath.parse("users_sort[]")` drops the empty array segment.
- `FormPath("users_sort").array.name` produces `users_sort[]`, so round-tripping is lossy.

Ideas:

- Represent array segments explicitly in `FormPath`.
- Preserve empty bracket segments during parsing.
- Add round-trip tests for dynamic nested forms.

### Make client event encoding failure observable

Current issue:

- Client event pushes log JSON encoding failures and return `Unit` in the current API.

Ideas:

- Make `ctx.client.pushEvent` fail its `LiveIO` when payload encoding fails.
- Add tests for encoding failures once client events move behind phase contexts.

## Ergonomics Improvements

### Reduce boilerplate for simple LiveViews

Current issue:

- Static or eventless views still define `handleMessage`.
- The current `subscriptions` boilerplate should go away when managed subscriptions move into phase contexts.

Ideas:

- Provide a default no-op `handleMessage` when `Msg = Unit` or `Msg = Nothing`.
- Add `StaticLiveView` or `SimpleLiveView` helpers.
- Consider a `NoMsg` type alias or object for views without server messages.

### Add common mount aspect builders

Current issue:

- `LiveMountAspect` is expressive but type-heavy.

Ideas:

- Add builders for common auth/session/request-context cases.
- Provide aliases for common signatures.
- Add examples for route-level auth, session-level auth, and request-derived context.
- Keep the fully generic API available for advanced composition.

### Make component targeting names clearer

Current issue:

- `component(cid, element)` renders component content.
- `component[C](message)` creates a routed component event target.

Ideas:

- Rename routed event helper to `toComponent[C](message)`, `componentMsg[C](message)`, or `targetComponent[C](message)`.
- Keep render helper names focused on rendering.

### Improve root layout ergonomics

Current issue:

- `LiveRootLayout` requires an explicit key, and examples manually hard-code it.

Ideas:

- Provide a default key based on layout identity when possible.
- Add a named constructor such as `LiveRootLayout.static("key")`.
- Document when root layout keys must change.

### Reconsider broad use of `@@`

Current issue:

- `@@` is used for mount aspects, live layouts, root layouts, socket mount, and token config.

Ideas:

- Keep `@@` for route/session modifiers if desired.
- Add named alternatives such as `.withLayout`, `.withRootLayout`, `.withMount`, `.socketAt`, `.withTokenConfig`.
- Use named alternatives in documentation to improve discoverability.

### Add typed wrappers for repeated string concepts

Current issue:

- Upload names, stream names, DOM IDs, selectors, event names, flash kinds, hook IDs, async task names, and paths are all strings.

Ideas:

- Introduce lightweight value classes where they prevent common mixups.
- Avoid wrapping every string by default.
- Start with the highest-risk concepts: async keys, stream names, upload names, and route locations.

### Add typed async keys

Current issue:

- Async start and cancel operations use string task names.
- `assignAsync` derives field-based string keys internally.

Ideas:

- Add `AsyncKey[A]` or `AsyncName[A]`.
- Keep string overloads for quick use.
- Expose field-derived assign async keys only through typed field selectors.

### Simplify `cancelAssignAsync`

Current issue:

- `cancelAssignAsync(model)(field, reason)` accepts `model`, but the macro only uses the selected field name and model type.

Ideas:

- Remove the model argument if the type can be inferred another way.
- Or use an explicit typed field key returned by `assignAsync`.
- Document the current field-selection rule if the method remains unchanged.

## Forms Improvements

### Expand typed form helpers carefully

Current issue:

- The current form helper set covers common controls, but examples still use manual names and values for dynamic forms.

Ideas:

- Add helpers for number, date, radio groups, multi-select, and checked boolean fields as patterns stabilize.
- Add typed helpers for dynamic list fields.
- Add examples that use the typed form path API first, then show raw escape hatches.

### Improve `FormCodec` composition

Current issue:

- `FormCodec` has basic `map` and `emap`, but complex forms currently require manual decoding.

Ideas:

- Add combinators for required/optional fields, repeated fields, nested objects, and validated values.
- Keep the underlying `FormData => Either[FormErrors, A]` constructor as the escape hatch.
- Avoid introducing a large validation framework unless usage proves it necessary.

### Make example forms lead with typed APIs

Current issue:

- Some examples use raw maps and manual names even where typed form APIs exist.

Ideas:

- Update beginner examples to use `Form.of`, `FormCodec`, and `FormEvent`.
- Keep raw payload examples in advanced or parity-focused docs.

## Routing and Navigation Improvements

### Strengthen query encoding APIs

Current issue:

- `LiveQueryCodec.custom` asks users to return a query string manually.

Ideas:

- Add builder helpers that URL-encode keys and values.
- Add `LiveQueryCodec.fromParams` or `LiveQueryCodec.query` constructors.
- Keep `custom` as an escape hatch for unusual cases.

### Adjust `LiveQueryCodec.none.href`

Current issue:

- `LiveQueryCodec.none.href(())` returns `?`.

Ideas:

- Consider returning an empty query href if that better matches link rendering expectations.
- Audit current patch behavior before changing it.

### Document initial navigation behavior

Current issue:

- Initial navigation and redirects during disconnected render need clear documentation.
- The phase context API omits patch operations from mount contexts.

Ideas:

- Document this lifecycle behavior.

## Components Improvements

### Add delayed and batch component update APIs

Current issue:

- `sendUpdate` exists, but delayed updates and batch updates are not exposed.

Ideas:

- Add typed `sendUpdateAfter`.
- Add typed `updateMany` or a batch update API if component usage requires it.
- Keep missing component targets as no-op and make the behavior visible in tests or logs.

## Streams Improvements

### Add stream configuration APIs

Current issue:

- Stream definitions contain name and DOM ID generation, but stream configuration parity is incomplete.

Ideas:

- Add typed equivalents for `stream_configure` if useful.
- Add a `LiveStreamDef` builder with optional configuration.
- Add tests for reset, limits, update-only, nested streams, and component-scoped streams.

### Add stream async APIs

Current issue:

- Stream async parity is listed as incomplete.

Ideas:

- Add a typed `streamAsync` if it fits the Scala model.
- Reuse `AsyncValue` or typed async keys where possible.

## Upload Improvements

### Simplify disconnected upload rendering

Current issue:

- Examples hand-build fallback `LiveUpload` values when upload runtime is unavailable during disconnected render.

Ideas:

- Add a helper that creates a disconnected placeholder from `LiveUploadOptions`.
- Make `ctx.uploads.allow` usable in disconnected mount without catch/fallback boilerplate if runtime semantics allow it.
- Document disconnected versus connected upload state.

### Complete upload edge-case API coverage

Ideas:

- Audit auto-upload, external preflight failures, writer failures, postponed consumption, in-progress submit, reallow/disallow, progress callbacks, and cancellation behavior.
- Add native tests and examples for each supported public behavior.

## Static Assets and Client Setup Improvements

### Document static asset behavior

Ideas:

- Document classpath and directory-backed asset sources.
- Document digested URLs, original-path serving, cache headers, and tracked static helpers.
- Document development and production asset behavior.

### Provide a client setup guide or helper

Current issue:

- Browser setup requires manually wiring Phoenix `Socket` and `LiveSocket`.

Ideas:

- Document the required JavaScript setup in the quickstart.
- Add a minimal generated JS snippet.
- Consider a helper package or template for common LiveSocket options.

## Testing Improvements

### Implement the `scalive-test` module

Current issue:

- The test API design is locked in `doc/phase-context-api-design.md`, but no separate test-support module exists yet.
- Current tests still use internal socket/protocol helpers directly.

Ideas:

- Add a `scalive-test` module that exposes `scalive.testing.*`.
- Provide recording phase contexts and a mounted LiveView harness.
- Keep tests typed around `Msg` and `Model` rather than string-only event helpers.
- Reuse the existing protocol runtime internally without exposing it as core app API.

## Documentation Work Queue

- Root README with install, first LiveView, run command, and browser setup.
- Core concepts guide for model, message, render, effects, and managed subscriptions.
- Routing guide for `Live.router`, `live`, sessions, layouts, root layouts, and mount aspects.
- Events guide for `phx` bindings, values, forms, JS push, and raw/typed event hooks.
- Forms guide for `FormData`, `FormCodec`, `FormEvent`, helpers, errors, used fields, and dynamic inputs.
- Components guide for props, model, `ComponentRef`, `liveComponent`, targets, and `sendUpdate`.
- Streams guide for definitions, inserts, deletes, reset, limits, and rendering.
- Uploads guide for allow/cancel/consume, validation, external upload, writers, and progress.
- JS guide for commands, transitions, dispatch, exec, patch, navigate, and push.
- Static assets guide for hashing, tracked static assets, root layouts, and middleware.
- Migration/parity guide mapping Phoenix LiveView concepts to Scalive concepts.
