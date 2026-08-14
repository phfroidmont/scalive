{%
title = "Project"
description = "Project status, direction, and resources for Scalive."
order = 0
section = project
%}

## Project Status {#project-status}

Scalive is alpha software under active development. It implements substantial
parts of the Phoenix LiveView programming model, but feature coverage and
edge-case behavior are still being expanded and audited. Do not assume complete
Phoenix LiveView parity or production maturity from the presence of a related
API.

The current documentation follows the current source revision. The standalone
[Quick start](../learn/quick-start.md#before-you-begin) uses the forthcoming
snapshot coordinate and clearly marks it as unavailable until publication.
Evaluate the framework against your application's requirements, especially
security, failure recovery, operations, and any Phoenix feature on which you
depend.

## Expect Breaking Changes {#expect-breaking-changes}

Scalive does not provide source or binary compatibility guarantees during the
alpha period. Public types, method signatures, package locations, and behavior
may change when a clearer, safer, or more idiomatic Scala API is available.
Breaking changes can land without a compatibility shim or deprecation cycle.

Pin the exact revision you use, review changes before upgrading, and expect to
update application code. The [Learn path](../learn/index.md#start-here) and
[API reference](../api/index.md#packages) describe the current API rather than a
future stable contract.

## Compatibility Scope {#compatibility-scope}

Scalive aims to reproduce useful Phoenix LiveView behavior and feature coverage
on Scala 3, ZIO, and ZIO HTTP. Compatibility means observable application and
browser behavior where that behavior applies to Scalive. It does not mean
internal implementation parity, direct source compatibility, or identical
public APIs.

Coverage varies by feature. Some areas are implemented and tested deeply, some
are partial, and some have no Scalive equivalent. Passing an upstream browser
scenario is evidence for that scenario, not proof that the whole feature area
is complete. The project will publish structured compatibility documentation in
a later documentation slice; until then, treat the current API, tests, and
working examples as evidence with limited scope.

For a conceptual translation rather than a support matrix, read the
[Phoenix LiveView concepts in Scalive](../guides/phoenix-live-view-orientation.md#start-with-the-programming-model).

## Intentional Scala-First Divergences {#intentional-scala-first-divergences}

Scalive preserves the LiveView programming model while changing API shapes to
use Scala's type system and ZIO's effect model:

- Typed immutable models replace socket assign maps.
- Typed message values replace stringly typed event dispatch in application
  handlers.
- `ZIO` effects and explicit result types replace Elixir callback tuples.
- Typed path and query codecs replace Phoenix route macros and atom actions.
- Scala HTML builders replace HEEx templates and component macros.
- Typed @:apiSymbol(class:scalive.LiveMountAspect)`LiveMountAspect`@:@ values replace module-and-atom `on_mount` callbacks.
- Scalive-native testing APIs replace direct copies of Phoenix test helpers.

These differences are design choices, not compatibility gaps by themselves.
When strict API similarity conflicts with type safety, robustness, or Scala
ergonomics, Scalive prefers the better Scala API.

## Non-Goals {#non-goals}

Scalive is not intended to:

- run Elixir, Phoenix applications, or HEEx templates on the JVM;
- provide source compatibility with Phoenix LiveView modules;
- reproduce Phoenix or BEAM internals when observable behavior can be provided
  idiomatically on ZIO HTTP;
- guarantee that every Phoenix option, callback, test helper, or transport has a
  direct Scalive equivalent;
- preserve alpha APIs solely to avoid migration work; or
- replace JavaScript for behavior that inherently depends on browser APIs.

## Report An Issue {#report-an-issue}

Use [GitHub issues](https://github.com/phfroidmont/scalive/issues) for bugs,
missing behavior, documentation errors, and focused feature requests. Before
opening an issue, check the current [Learn content](../learn/index.md#start-here)
and [API reference](../api/index.md#packages) so the report targets the current
revision.

Include the Scalive commit or revision, the smallest reproducible example,
expected behavior, actual behavior, and relevant logs or stack traces. For a
Phoenix compatibility report, link the upstream documentation or test that
defines the expected behavior and explain the user-facing impact. State whether
you need equivalent behavior or an identical API; Scalive generally targets the
former.

Do not include secrets, credentials, private keys, session tokens, or private
application data in a public issue.
