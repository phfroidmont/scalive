# User-Facing API Assessment Design

## Purpose

Assess the current state of Scalive's user-facing API before defining a roadmap.
The assessment should give a clear, evidence-backed view of ergonomics, polish,
and Phoenix LiveView feature parity without proposing implementation sequencing.

## Scope

The assessment covers APIs and materials application authors interact with:

- Public API exported through `scalive.*` and explicitly public subpackages.
- Core LiveView and LiveComponent lifecycle APIs.
- Phase contexts and runtime capability facades.
- HTML DSL, `phx-*` bindings, JS commands, forms, streams, uploads, navigation,
  routing, layouts, mount aspects, async tasks, subscriptions, flash, client
  events, static assets, and token/session configuration.
- Documentation and examples that teach or demonstrate the public API.
- Existing upstream compatibility evidence for Phoenix LiveView `v1.1.28`.

The assessment does not include:

- A roadmap or implementation plan.
- New API designs beyond concise observations about current friction.
- Internal runtime architecture unless it leaks into public API behavior.
- Direct implementation changes.

## Primary Output

Produce a single audit report focused on current state. The report should be
readable as a standalone design review and precise enough to support later
roadmap work.

Recommended location:

```text
doc/user-facing-api-assessment.md
```

## Report Structure

The audit report should use these sections:

1. Executive summary.
2. Scope and methodology.
3. API surface inventory.
4. Ergonomics findings.
5. Polish and discoverability findings.
6. Upstream parity findings.
7. Documentation and example findings.
8. Risk register.
9. Open questions and confidence notes.

Each finding should include:

- Severity: `Critical`, `High`, `Medium`, or `Low`.
- Category: API design, docs, parity, correctness, or discoverability.
- Evidence with file references.
- Current impact on application authors.
- Confidence level where the evidence is incomplete.

## Methodology

Use a read-mostly audit process:

1. Inventory public APIs from `scalive/src/scalive` and public docs.
2. Compare documentation claims against current implementation.
3. Review examples for beginner ergonomics and recommended usage patterns.
4. Review compatibility tracking and upstream E2E fixture gap documents.
5. Sample upstream parity evidence from existing native tests and E2E fixtures.
6. Record findings with precise references and avoid speculative fixes.

The report may mention obvious follow-up themes, but it should not rank or
sequence roadmap items. Roadmap decisions happen after the current-state report
is reviewed.

## Assessment Principles

- Prefer Scala-first API quality over direct Elixir API shape when the project
  has intentionally diverged.
- Treat behavior and feature-set parity as the compatibility target, not
  internal Phoenix implementation parity.
- Distinguish proven gaps from suspected gaps.
- Do not over-claim browser parity while tracked fixture gaps remain.
- Keep runtime internals out of scope unless they affect application authors.
- Favor concise, file-referenced findings over broad commentary.

## Initial Evidence Sources

Core docs:

- `UPSTREAM_COMPATIBILITY.md`
- `doc/public-api-reference.md`
- `doc/api-improvement-ideas.md`
- `doc/e2e-fixture-parity-gaps.md`
- `doc/phase-context-api-design.md`

Implementation areas:

- `scalive/src/scalive/Scalive.scala`
- `scalive/src/scalive/LiveView.scala`
- `scalive/src/scalive/LiveComponent.scala`
- `scalive/src/scalive/LiveContext.scala`
- `scalive/src/scalive/routing/LiveRouteDsl.scala`
- `scalive/src/scalive/forms`
- `scalive/src/scalive/streams`
- `scalive/src/scalive/upload`

Examples and fixtures:

- `example/src`
- `e2eApp/src`
- `scalive/test/src/scalive`
- `.e2e-upstream/phoenix_live_view/*/test/e2e` when present

## Expected Findings Style

Findings should be concrete and evidence-backed. Example format:

```text
High - API design - Outbound navigation remains stringly typed while inbound
routes are typed. Evidence: scalive/src/scalive/Scalive.scala:83,
scalive/src/scalive/LiveContext.scala:86, doc/api-improvement-ideas.md:31.
Impact: application authors can safely decode route params but cannot render
equally safe links or navigation effects.
Confidence: High.
```

## Acceptance Criteria

The assessment is complete when:

- The report covers all major public API areas listed in scope.
- Findings are backed by file references.
- Documentation drift is separated from implementation gaps.
- Intentional divergences from Phoenix are not reported as parity bugs unless
  they create user-facing feature loss.
- Known parity uncertainty is stated explicitly.
- No roadmap sequencing or implementation plan is included.
