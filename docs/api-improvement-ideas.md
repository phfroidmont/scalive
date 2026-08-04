# Scalive API Improvement Ideas

This document collects backlog items for the current public API. It focuses on coherence, ergonomics, type safety, and discoverability.

The phase-specific context API is specified in `doc/phase-context-api-design.md`. Do not duplicate those design decisions here; keep this document focused on remaining implementation work and independent API improvements.

## Design Goals

- Keep `LiveView[Msg, Model]` as the core mental model.
- Preserve message-typed HTML, JS, and component APIs.
- Prefer Scala-first typed APIs over direct Phoenix callback tuple parity.
- Keep app-author APIs small and obvious.
- Move runtime/protocol details behind explicit internal APIs or separate test-support modules.

## Correctness Fixes With API Impact

### `JS.push(pageLoading)` encoding

Implemented: `pageLoading` is omitted when false and encoded when true, with regression coverage.

### Attribute value bindings

Implemented: optional variants preserve missing or invalid values; convenience variants use safe
defaults and do not throw.

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

Implemented:

- `LiveMountAspect.authenticated` handles named-cookie extraction, disconnected authentication, signed claims transfer, connected resumption, and typed redirects.
- The auth example uses the builder without explicit aspect type parameters.
- `make` and `fromRequest` remain available for advanced composition.

Remaining:

- Add request-context or route-parameter-specific builders only when concrete usage patterns justify them.

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
- `HttpFormDecoder` composes bounded body decoding, representation parsing, CSRF validation, and application decoding while preserving four exhaustive error categories.
- Typed `FormField` declarations share exact paths between rendering and application decoding.

Boundaries:

- `HttpFormDecoder` intentionally requires an explicit body bound and `CsrfProtection` capability.
- Multipart and GET query-form decoding remain separate future designs.

### Add an ordinary HTTP form mode

Implemented:

- `Form.http` renders an ordinary browser form from a `FormAction` and the existing typed field helpers.
- The helper owns `action` and `method` and adds no Live binding by default.
- `onChange`, `onSubmit`, and `triggerHttpSubmitWhen` remain explicit opt-ins.
- Raw HTML form construction remains the escape hatch.

Remaining:

- Add multipart and additional ordinary-form capabilities only when their transport semantics are designed.

### Add typed ordinary HTTP form actions

Implemented:

- `FormAction` derives a checked browser method and encoded root-relative path from a ZIO HTTP `RoutePattern`.
- Only GET and POST are representable through checked construction; path and method failures have an explicit error channel.
- `FormAction.unsafe` supports external URLs, fixed query strings, and unusual integrations explicitly.
- The auth example shares its session and logout route declarations between dispatch and rendered actions.

Boundaries:

- ZIO HTTP endpoints are not the baseline action abstraction because their inputs can also require bodies, headers, cookies, and authentication.
- Checked actions intentionally model paths only. Fixed query strings remain unsafe because GET form submission replaces the action query with successful controls.

### Support CSRF-protected ordinary HTTP forms

Implemented:

- `LiveSecurity` exposes one hardened `CookiePolicy` shared by application sessions, ordinary-form `CsrfProtection`, and HTTP flash redirects without exposing signing operations.
- Checked POST `FormAction`s receive an automatic `_csrf_token` field during disconnected and connected rendering; GET and unsafe actions remain unmanaged.
- Validation requires exactly one bounded submitted token, verifies the signed browser cookie and parameter purposes, and compares their secrets in constant time.
- The cookie is host-only, root-scoped, `HttpOnly`, `SameSite=Lax`, expiry-bounded, and explicitly configurable as `Secure`.
- The auth example validates framework CSRF before `FormCodec`, uses no pre-authentication cookie or login bootstrap context, and covers login-CSRF transfer between browsers.

Boundaries:

- Transport decoding remains separate so body, representation, CSRF, and application validation failures retain distinct error channels.
- Tokens are browser-bound and reusable until expiry, not consume-once application tokens.
- Scalive does not infer public HTTPS from forwarding headers and does not add a separate `Origin` or `Referer` policy.
- Multipart ordinary forms remain deferred until their transport semantics are designed.

### Bridge ordinary HTTP redirects into Live flash

Implemented:

- `LiveSecurity.flash` exposes typed `HttpFlash.seeOther(LiveLocation, values*)` and an explicit `seeOtherUnsafe(URL, values*)` escape hatch.
- HTTP and Live-originated redirects share the existing purpose-bound signed flash transport and one hardened cookie policy.
- The flash cookie is host-only, root-scoped, `HttpOnly`, `SameSite=Lax`, explicitly configurable as `Secure`, and expires after at most 60 seconds.
- Valid flash survives redirect chains, is embedded in the next rendered Live session, and the browser cookie is then expired; malformed, expired, and wrong-purpose cookies render nothing and are cleaned up.
- The auth example uses the bridge for generic invalid-login feedback and no longer transports state through `?invalid=true`.

Boundaries:

- Consume-once is enforced by normal browser cookie expiry, not server-side nonce storage; a copied signed token can be replayed until it expires.
- Flash values are signed but not encrypted and must not contain secrets.
- Redirects preserve incoming flash until a Live route renders successfully.

### Expand typed form helpers carefully

Current issue:

- The current form helper set covers common controls, but examples still use manual names and values for dynamic forms.

Ideas:

- Add helpers for number, date, radio groups, multi-select, and checked boolean fields as patterns stabilize.
- Add typed helpers for dynamic list fields.
- Add examples that use the typed form path API first, then show raw escape hatches.

### Improve `FormCodec` composition

Implemented:

- `FormCodec.zip` accumulates errors in field declaration order.
- `FormField.requiredString`, `optionalString`, and `strings` provide reusable path-bound decoders.
- `FormField.map` and `validate` support focused field transformations and validation.

Remaining:

- Add richer field types only as concrete forms require them.
- Avoid introducing a large validation framework unless usage proves it necessary.

### Make example forms lead with typed APIs

Implemented:

- The login and profile examples declare typed fields once and reuse them for decoding, names, IDs, rendering, errors, and used state.
- Raw string and `FormPath` helpers remain available as escape hatches and for dynamic forms.

Remaining:

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

### Expand stream configuration APIs

Current issue:

- Stream definitions contain name, DOM ID generation, and retention policy, but async stream parity is incomplete.

Ideas:

- Add further typed definition configuration only when a stable policy belongs to the whole stream.
- Expand tests for update-only, nested streams, and component-scoped streams.

### Add stream async APIs

Current issue:

- Stream async parity is listed as incomplete.

Ideas:

- Add a typed `streamAsync` if it fits the Scala model.
- Reuse `AsyncValue` or typed async keys where possible.

## Upload Improvements

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

Current state:

- The separate `scalive-testing` artifact can execute finalized routes through the disconnected lifecycle and query rendered forms, ordered named fields, values, and binding modes semantically.
- `LoginLiveView` has disconnected render coverage for its typed ordinary HTTP action, rooted fields, stable IDs, automatic framework CSRF, and consumed HTTP-to-Live invalid-login flash.
- Connected events, ordinary HTTP form submission, and following `phx-trigger-action` are not supported yet.

Ideas:

- Keep disconnected assertions semantic because transport IDs and signed tokens are intentionally opaque.
- Add connected mount and typed event submission only after the render API is stable.
- Support ordinary HTTP form submission and triggered HTTP submission as separate, explicit test paths.
- Design a Scala-native API around typed messages and models instead of copying Phoenix `LiveViewTest` function names.
