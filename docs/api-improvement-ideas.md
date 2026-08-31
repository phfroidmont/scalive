# Scalive API Backlog

This document tracks unresolved public API work. Completed designs and implementations belong in
code, tests, and user documentation rather than this backlog.

## Design Principles

- Keep `LiveView[Msg, Model]` as the core mental model.
- Preserve message-typed HTML, JavaScript, component, form, stream, and upload APIs.
- Prefer Scala-first types over direct copies of Phoenix callback shapes.
- Keep application APIs small, explicit, and easy to debug.
- Add convenience APIs only after recurring usage demonstrates a stable pattern.

## Forms

### Preserve Array Path Segments

`FormPath.parse("users_sort[]")` currently drops the empty array segment even though
`FormPath("users_sort").array.name` produces `users_sort[]`.

- Represent array segments explicitly.
- Preserve them during parsing.
- Add round-trip coverage for dynamic nested forms.

### Support Multipart HTTP Forms

Ordinary GET and URL-encoded POST forms have typed decoding, bounded bodies, CSRF validation,
semantic test support, and redirect handling. Multipart transport semantics remain separate.

- Define bounded multipart decoding and upload ownership.
- Keep body, representation, CSRF, and application validation errors distinct.
- Add testing support only after browser successful-control and file semantics are explicit.

### Expand Typed Field Helpers Carefully

- Add number, date, radio-group, multi-select, checked-boolean, and dynamic-list helpers as concrete
  forms require them.
- Add richer reusable field codecs without introducing a separate validation framework.
- Keep raw names, paths, and HTML controls as explicit escape hatches.

## Lifecycle And Layouts

### Improve Root Layout Construction

`LiveRootLayout` requires an explicit key, and applications commonly use a fixed literal.

- Consider a named constructor such as `LiveRootLayout.static("key")`.
- Derive a default key only if its identity and invalidation behavior remain obvious.
- Document the cases where changing a root layout key is required.

### Add Typed Async Assignment Helpers

`AsyncValue` transitions currently require an explicit completion message and model update.

- Consider a typed field-level helper for common single-field work.
- Keep explicit result messages available for arbitrary workflows.
- Avoid hidden model mutation or selector macros.

## Components

### Add Delayed And Batch Updates

Typed `sendUpdate` exists, but delayed and batch updates are not exposed.

- Add `sendUpdateAfter` if lifecycle cancellation semantics can remain explicit.
- Add `updateMany` only when batching has a clear atomicity and ordering contract.
- Preserve the current missing-target behavior and make it observable in tests or diagnostics.

## Streams

### Complete Stream Configuration Coverage

- Expand coverage for update-only, nested, and component-scoped streams.
- Add definition-level options only when they describe stable policy for the complete stream.

### Add Typed Async Streams

- Add `streamAsync` if it fits the explicit typed model.
- Reuse existing async values or resource keys rather than creating a separate task system.
- Define replacement, cancellation, failure, and lifecycle cleanup before exposing the helper.

## Uploads

### Complete Edge-Case Coverage

- Audit auto-upload, external preflight failures, writer failures, postponed consumption,
  in-progress submit, reallow/disallow, progress callbacks, and cancellation.
- Add native tests and examples for every supported public behavior.
- Add convenience APIs only where the audited behavior exposes repeated application boilerplate.

## Testing

### Evaluate Trigger-Action Handoff Support

`DisconnectedRender` supports ordinary HTTP form flows and `ConnectedRender` supports typed
connected form events. Following `phx-trigger-action` from a connected projection remains a browser
boundary.

- Add a connected handoff helper only if recurring application tests need it.
- Do not emulate browser successful-control selection or JavaScript execution in the server-side
  harness.
