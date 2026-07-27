# Named Route And Session Modifiers Design

## Goal

Replace the overloaded symbolic `@@` routing modifier with named methods that expose each operation through autocomplete and documentation search.

This is an Alpha-stage source-breaking API improvement. The change preserves existing runtime behavior and type safety while establishing one canonical modifier vocabulary.

## Public API

Route seeds, route builders, and route params builders expose:

```scala
route
  .withMountAspect(aspect)
  .withLayout(layout)
  .withRootLayout(rootLayout)
```

Session seeds and session builders expose the same modifier names:

```scala
Live.session("admin")
  .withMountAspect(aspect)
  .withLayout(layout)
  .withRootLayout(rootLayout)
```

The router exposes only router-wide configuration:

```scala
Live.router
  .withLayout(layout)
  .withRootLayout(rootLayout)
  .withSocketPath(path)
  .withTokenConfig(tokenConfig)
```

`withSocketPath` accepts `PathCodec[Unit]` directly. `withTokenConfig` accepts `TokenConfig` directly.

## Removed API

Remove all route, session, and router `@@` overloads. Do not retain aliases or add deprecations.

Remove the modifier wrapper types and their constructors because named router methods no longer need them:

- `LiveSocketMount`
- `LiveTokenConfig`
- `Live.socketAt`
- `Live.tokenConfig`

This avoids two equivalent vocabularies and makes the named API the only style shown by autocomplete, examples, and reference documentation.

## Behavior And Type Safety

Each named method performs the same immutable builder transition as its current `@@` counterpart.

The change preserves:

- mount aspect ordering
- environment intersections across composed aspects and routes
- `ContextAppend` context accumulation
- projection of earlier layout contexts after later aspects extend the context
- live layout ordering
- the existing last-applied root layout behavior
- route and session inheritance
- router defaults for root layout, socket path, and token configuration

The method names do not introduce a new configuration model. Routes and sessions remain typed, incrementally composed builders.

## Migration

Update all project source, tests, examples, E2E fixtures, and public documentation in the same change.

Representative rewrites:

```scala
// Before
(Live.router @@ Live.tokenConfig(tokenConfig) @@ rootLayout)(routes*)

// After
Live.router
  .withTokenConfig(tokenConfig)
  .withRootLayout(rootLayout)(routes*)
```

```scala
// Before
(Live.session("admin") @@ authAspect @@ adminLayout)(routes*)

// After
Live.session("admin")
  .withMountAspect(authAspect)
  .withLayout(adminLayout)(routes*)
```

```scala
// Before
((live / "orgs") @@ orgAspect @@ orgLayout)(OrgLiveView())

// After
(live / "orgs")
  .withMountAspect(orgAspect)
  .withLayout(orgLayout)(OrgLiveView())
```

Compilation errors are the migration signal. No compatibility layer or runtime migration is needed.

## Verification

Existing behavioral tests continue to verify modifier order, lifecycle behavior, layout rendering, context propagation, token configuration, and socket routing after call sites move to named methods.

Compile-time API coverage must exercise every supported builder stage:

- route seed
- route builder after one or more mount aspects
- route params builder
- session seed
- session builder after one or more mount aspects
- router

Negative compile checks should confirm that router-only settings are unavailable on routes and sessions.

Verification consists of:

1. Run formatter and fix commands on the project.
2. Run routing, layout, lifecycle, and type-safety test suites.
3. Run the full project test suite.
4. Search production Scala, tests, examples, E2E fixtures, and current public documentation for remaining `@@`, `Live.socketAt`, `Live.tokenConfig`, `LiveSocketMount`, and `LiveTokenConfig` references.

Historical design specifications and implementation plans may retain references to the API they documented. Outside those historical records, any remaining `@@` occurrence must be unrelated to this routing API or removed.

## Non-Goals

- Changing route matching, session grouping, layout rendering, or mount execution semantics.
- Introducing a static router or session configuration object.
- Renaming route application or `->` view construction syntax.
- Redesigning `LiveMountAspect`, layouts, token configuration, or socket transport behavior.
