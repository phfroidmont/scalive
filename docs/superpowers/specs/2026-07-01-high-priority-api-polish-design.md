# High-Priority API Polish Design

## Purpose

Address the highest-priority user-facing API assessment findings that are actionable without designing a new typed outbound routing system.

This slice improves immediate correctness, extension-point usability, and newcomer discoverability while keeping typed outbound routes as a separate follow-up design.

## Scope

In scope:

- Make attribute value binding helpers safe for normal client payload variation.
- Make custom upload writers externally implementable without relying on package-private state construction.
- Add a root newcomer README.
- Refresh stale public API reference sections that conflict with the current implementation or this slice.
- Add focused tests for the API behavior changed by this slice.

Out of scope:

- Typed outbound route/location APIs for `link`, `ctx.nav`, or `JS`.
- Broad string identifier wrappers for flash kinds, uploads, async names, selectors, or hooks.
- Stream public state renaming.
- Full public documentation overhaul beyond the stale sections touched here.

## Attribute Value Bindings

`HtmlAttrBinding.withValue` and `withBoolValue` currently read the event payload's `value` key in ways that can throw for missing or unexpected values. Client event payloads can vary across controls and browser behavior, so the default helpers should not crash application code for ordinary payload shapes.

The API will add optional variants:

```scala
def withValueOption[Msg](f: Option[String] => Msg): Mod.Attr[Msg]
def withBoolValueOption[Msg](f: Option[Boolean] => Msg): Mod.Attr[Msg]
```

`withValue` will delegate through `withValueOption` and use `""` for a missing value. `withBoolValue` will delegate through `withBoolValueOption` and use `false` for missing or unrecognized values. The optional boolean helper will return `Some(true)` for `on`, `yes`, and `true`, `Some(false)` for `off`, `no`, and `false`, and `None` for missing or unrecognized values.

This keeps the common API simple and non-throwing while preserving a precise API for callers that need to distinguish missing or invalid values.

## Upload Writer State

`LiveUploadWriter` is public, but its required state type is not constructible outside `scalive` because `LiveUploadWriterState` has a package-private constructor. This blocks clean external writer implementations.

The minimal fix is to keep the current untyped writer trait and expose safe state construction/access:

```scala
final case class LiveUploadWriterState(value: Any):
  def valueAs[A]: Option[A]
```

This avoids a larger type-parameterized writer redesign in this slice. It unblocks external writers and preserves the existing runtime storage shape. The `valueAs` helper gives implementations a safer alternative to unchecked casts while acknowledging that the current writer state boundary is dynamically typed.

## README

Add a root `README.md` that gives newcomers a direct entry point:

- What Scalive is.
- Minimal dependency/import guidance appropriate for the current repository state.
- A minimal `LiveView` example using typed messages and model state.
- Basic routing/server setup pointers with links to examples.
- JS socket/static asset setup pointers.
- Commands for running tests and the example where available.
- Links to `doc/public-api-reference.md`, `UPSTREAM_COMPATIBILITY.md`, and the example app.

The README should avoid over-claiming full Phoenix LiveView parity. It should describe Scalive as Scala-first and point to compatibility docs for current parity evidence.

## Public API Reference Refresh

Update only the stale or conflicting sections relevant to this slice and known high-priority doc drift:

- Document `withValueOption` and `withBoolValueOption`.
- Document the non-throwing behavior of `withValue` and `withBoolValue`.
- Document public `LiveUploadWriterState` construction and `valueAs`.
- Remove unsupported typed `link.patch(codec, value, ...)` and `link.patchReplace(codec, value, ...)` claims.
- Ensure `LifecycleContext` summaries include `connectParams`.
- Remove or clearly consolidate duplicate stale `LiveComponent[Props, Msg, Model]` guidance if the change is local and low-risk.

This refresh is intentionally narrow. Broader documentation restructuring remains future work.

## Tests

Add focused regression tests:

- `withValue` returns an empty string when `value` is missing.
- `withValueOption` receives `None` when `value` is missing and `Some(value)` when present.
- `withBoolValue` returns `false` for missing or unrecognized values and decodes accepted true/false strings.
- `withBoolValueOption` returns `None` for missing or unrecognized values and `Some(value)` for accepted values.
- An external-style `LiveUploadWriter` can construct, inspect, update, and close custom state through the public API.

Existing type-safety tests should continue to compile, proving the new helpers preserve message type checking.

## Implementation Notes

The smallest correct implementation is preferred:

- Keep all binding helper logic inside `HtmlAttrBinding`.
- Keep the upload writer trait shape unchanged.
- Avoid compatibility shims beyond keeping existing method names and making them safer.
- Do not introduce typed route/location abstractions in this slice.

## Success Criteria

- The new binding helpers compile and are covered by tests.
- Existing binding helpers no longer throw for missing or unrecognized `value` payloads.
- A custom upload writer can be implemented outside the package using public APIs only.
- Public docs no longer advertise unsupported typed `link.patch` overloads or package-private upload writer state construction.
- The repository has a root README that gives newcomers a usable starting path.
- The relevant test suite passes after the change.
