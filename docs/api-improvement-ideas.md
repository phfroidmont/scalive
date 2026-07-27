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

### Fix the upload writer extension point

Current issue:

- `LiveUploadWriter.init` returns `LiveUploadWriterState`, but `LiveUploadWriterState` has a `private[scalive]` constructor.
- External users cannot implement custom upload writers cleanly.

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

## Ergonomics Improvements

### Add common mount aspect builders

Current issue:

- `LiveMountAspect` is expressive but type-heavy.

Ideas:

- Add builders for common auth/session/request-context cases.
- Provide aliases for common signatures.
- Add examples for route-level auth, session-level auth, and request-derived context.
- Keep the fully generic API available for advanced composition.

### Improve root layout ergonomics

Current issue:

- `LiveRootLayout` requires an explicit key, and examples manually hard-code it.

Ideas:

- Provide a default key based on layout identity when possible.
- Add a named constructor such as `LiveRootLayout.static("key")`.
- Document when root layout keys must change.

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

### Strengthen route params APIs

Current issue:

- Direct `LiveParamsCodec.custom` decoders are verbose for common single-query-param and path-mapping routes.

Ideas:

- Keep route-level `query[A]`, `query[A]("name")`, `queryOptional[A]("name")`, and `mapParams` as the documented path for common query-only and path-plus-query routes.
- Keep `custom` as an escape hatch for unusual cases.

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
