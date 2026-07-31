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

### Add typed async assignments

Current issue:

- `AsyncValue` transitions currently require an explicit completion message and model update.

Ideas:

- Consider a typed field-level helper for the common single-field case.
- Keep the explicit result-message API available for arbitrary async work.
- Avoid selector macros or hidden model mutation unless they are clearly safer and simpler than explicit messages.

## Forms Improvements

### Decode ordinary HTTP forms with `FormCodec`

Implemented foundation:

- `FormData.fromUrlEncoded` reports malformed encoding and preserves ordered repeated and nested fields.
- `FormData.fromUrlEncodedBody` enforces content type and an explicit byte limit without using ZIO HTTP's lossy query-form conversion.
- `FormData.fromZioHttpForm` preserves existing textual fields and rejects binary or streaming fields explicitly.
- Websocket form payloads use the same checked parser and report malformed payloads as binding failures.
- The auth example composes the transport decoder with a rooted `LoginForm.codec` and keeps transport errors distinct from `FormErrors`.

Remaining work belongs to the ordinary HTTP form mode:

- Decide how typed form declarations expose transport decoding without hiding its error channel.
- Do not add a one-line `FormCodec` HTTP convenience until the ordinary form API can keep body, representation, and validation failures explicit.

### Add an ordinary HTTP form mode

Current issue:

- `Form` provides typed rendering helpers and LiveView event bindings, but it does not model a normal browser form that submits directly to an HTTP handler.
- Applications manually keep rendered field names, HTTP decoding, method, and action in sync.

Ideas:

- Build the ordinary mode on `Form`, `FormPath`, `FormData`, and `FormCodec` rather than creating a second form system.
- Let one form definition drive rendered names and IDs plus HTTP body decoding.
- Do not add a `phx-submit` binding by default; direct HTTP submission must remain the simplest path for session-mutating operations.
- Keep `phx.triggerAction` as an explicit opt-in for applications that need LiveView validation before the final HTTP submission.
- Preserve raw HTML form construction as an escape hatch.

### Add typed ordinary HTTP form actions

Current issue:

- `LiveLocation` gives Live routes checked outbound locations, but ordinary form actions such as `POST /auth/session` remain raw strings.
- Route matching and outbound action construction can drift apart.

Ideas:

- Introduce or adapt a small typed representation containing the HTTP method and an encodable relative location.
- Investigate adapting ZIO HTTP endpoints before adding a parallel general-purpose HTTP routing DSL.
- Make the ordinary form helper derive its `action` and `method` from that representation.
- Keep an explicitly unsafe string/URL action for external targets and unusual integrations.

### Support CSRF-protected ordinary HTTP forms

Current issue:

- Scalive's built-in CSRF implementation protects the LiveSocket connection and is private to the routing runtime.
- Applications that mutate cookies or sessions through ordinary HTTP must build their own CSRF token, cookie, hidden input, and validator.
- The auth example needs a pre-authentication context and two extra redirects primarily to establish this protection.

Ideas:

- Provide a public ordinary-form CSRF capability integrated with the Live route render and ZIO HTTP handler boundary.
- Generate the hidden input automatically for non-GET same-origin actions unless explicitly disabled.
- Validate missing, malformed, expired, transferred, and mismatched tokens before application form decoding.
- Ensure token rendering remains stable across disconnected render and connected mount.
- Keep token internals and signing secrets private, and document cookie, origin, host, expiry, and `Secure` semantics.
- Test login CSRF specifically; preventing state changes is not sufficient if an attacker can log a victim into the attacker's account.

### Bridge ordinary HTTP redirects into Live flash

Current issue:

- `ctx.flash` is public inside LiveView lifecycle callbacks, but an ordinary HTTP handler cannot produce a flash consumed by the next Live route.
- Applications fall back to ad hoc query parameters such as `?invalid=true`.

Ideas:

- Add a public helper or service that attaches typed flash values to an ordinary redirect response.
- Accept `LiveLocation` for redirects and preserve an explicit unsafe URL escape hatch.
- Reuse the existing signed flash transport without exposing `TokenConfig` secrets to application code.
- Preserve consume-once and stale-cookie cleanup behavior across HTTP-to-Live and Live-to-Live navigation.
- Apply appropriate `HttpOnly`, `SameSite`, `Secure`, path, and expiry policy to the flash cookie.

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

### Migrate the authentication example incrementally

Current issue:

- The auth example correctly uses an ordinary HTTP boundary, but raw query, action, and field strings obscure which gaps belong to the example and which belong to Scalive.

Ideas:

- First, declare the invalid-login query through the existing typed query route API and use `LiveView.Routed.Eventless`.
- Decode login submissions into a valid ADT with `FormCodec`; do not construct empty token wrappers for missing input.
- Add bounded credential and token inputs before hashing or comparison.
- Add stable form/input IDs and render assertions while retaining direct HTTP submission.
- After ordinary-form CSRF and flash bridges exist, remove the bootstrap round-trip, custom login context, and `invalid=true` transport.
- Keep the example explicitly educational; do not turn these API improvements into a general authentication framework.

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

## Testing Improvements

### Add Scalive-native LiveView form test support

Current issue:

- Scalive has no supported application-facing test API for mounting a LiveView, selecting a form, inspecting rendered fields, or following a triggered HTTP action.
- The auth tests cover services, HTTP routes, cookies, and mount aspects but do not render `LoginLiveView` or exercise the complete browser flow.

Ideas:

- Start with deterministic disconnected rendering and semantic HTML/form queries.
- Add helpers to inspect form action, method, names, values, and event bindings without string-fragment assertions.
- Add connected mount and typed event submission only after the render API is stable.
- Support ordinary HTTP form submission and `phx.triggerAction` as separate, explicit test paths.
- Design a Scala-native API around typed messages and models instead of copying Phoenix `LiveViewTest` function names.

## Suggested Login API Work Order

1. [x] Add checked, bounded HTTP-to-`FormData` decoding and decode the rooted login submission with `FormCodec`.
2. [x] Move the invalid-login marker to the existing typed query route API.
3. Add minimal disconnected render and form-query test support, then cover `LoginLiveView`.
4. Design typed ordinary HTTP actions and the ordinary form mode together.
5. Add ordinary-form CSRF generation and validation, then remove the login bootstrap context.
6. Add the HTTP-to-Live flash bridge, then remove the invalid-login query marker.
