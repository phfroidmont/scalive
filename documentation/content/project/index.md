{%
title = "Project"
description = "Project status, direction, and resources for Scalive."
order = 0
section = project
%}

## Why Scalive Exists {#why-scalive-exists}

Scalive grew out of building and maintaining a production Scala.js application,
then searching for a simpler boundary between browser and server. Read
[why I built Scalive](why-i-built-scalive.md#choosing-a-stack) for the personal
and technical journey behind the project.

## Follow The Render Design {#follow-the-render-design}

Deciding how typed HTML should identify dynamic values was Scalive's central API
design problem. [Designing Dynamic HTML](dynamic-html-identity.md#the-question)
explores the competing approaches, what each one made easy or difficult, and why
Scalive ultimately returned to explicit staging with a deliberately restricted
`Signal` type.

## Understand The Runtime {#understand-the-runtime}

The [runtime architecture](runtime-architecture.md#runtime-at-a-glance) explains
how Scalive moves from disconnected rendering to socket bootstrap, serialized
lifecycle turns, retained rendering, tree diffs, resource ownership, protocol
projection, and cleanup. It introduces the concrete implementation only after
establishing the runtime concepts; internal names are not additional supported
application APIs.

## Project Status {#project-status}

Scalive is alpha software under active development. It implements substantial
parts of the Phoenix LiveView programming model, but feature coverage and
edge-case behavior are still being expanded and audited. Do not assume complete
Phoenix LiveView parity or production maturity from the presence of a related
API.

Compatibility is currently assessed against Phoenix LiveView `v1.2.10`. Read
the [compatibility status](compatibility.md#compatibility-target) for the current
feature matrix, known gaps, intentional Scala-first divergences, and verification
guidance.

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
is complete. The [public compatibility matrix](compatibility.md#status-matrix)
states the evidence and remaining work for each tracked area.

For a conceptual translation rather than a support matrix, read the
[Phoenix LiveView concepts in Scalive](../guides/phoenix-live-view-orientation.md#start-with-the-programming-model).

## Non-Goals {#non-goals}

Scalive is not intended to:

- run Elixir, Phoenix applications, or HEEx templates on the JVM;
- provide source compatibility with Phoenix LiveView modules;
- reproduce Phoenix or BEAM internals when observable behavior can be provided
  idiomatically on ZIO HTTP;
- guarantee that every Phoenix API has the same shape in Scalive;
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
