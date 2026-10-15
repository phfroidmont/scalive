# AGENTS

## Project Intent

Scalive is a Scala 3 re-implementation of Phoenix LiveView.

Our primary design goal is the best possible user-facing API.
When we diverge from upstream, it is only to leverage Scala features and improve the balance of:

- ergonomics
- type safety
- robustness

## Tools

You are running inside nix develop which gives you access to `mill` directly.
Some example commands you can use:

- `mill --ticker false __.test`
- `mill --ticker false __.reformat + __.fix`
- `mill --ticker false documentation.check` (includes the public API check)
- `mill --ticker false documentation.pipeline.updateApiSnapshot` (after public API changes)
- `./scripts/e2e-run-upstream.sh` and `./scripts/e2e-run-e2e-app.sh` (after browser-visible changes)

When an API change breaks callers, migrate all of them in the same change: repository code,
Scaladoc, the guides in `documentation/content`, fixtures, examples, and the API snapshot.

## Upstream Alignment

We aim to match upstream Phoenix LiveView behavior and feature set as closely as possible.
To track alignment, run upstream end-to-end tests against Scalive with:
`./scripts/e2e-run-upstream.sh`

## API Strengths To Preserve

Do not regress these when changing the API:

- Typed messages end to end. Components have isolated `Msg` types, owners map outputs explicitly,
  and layouts cannot send messages because they are generic in `Msg`.
- During mount, connected-only capabilities (async work, subscriptions, the browser client) are
  reachable only by matching `ctx.connection`.
- Typed `location` builds links from the route definition, and every escape hatch is named
  `*Unsafe`. Opaque keys carry invariant payload types (`AsyncKey[A]`, `BrowserToServerEvent[A]`,
  `ServerToBrowserEvent[A]`, `FlashKind`, `DomRef`).
- A route that reads session context compiles only inside a session that produces it.
- The `Signal` staging design (`documentation/content/project/dynamic-html-identity.md`): no
  sampling and no mutation. Extend it rather than replace it.
- Forms: definition-owned path-dependent types, raw input kept for invalid values, accumulated
  errors, and the token-based `FormWorkflow`.
- Secure defaults: validated configuration, redacted secrets and form values, signed claims,
  origin checks.
- Type-level guarantees are tested with `typeCheckErrors`. Add such a test for every type-level fix.

## Decision Rule

If API quality and strict upstream parity conflict, prefer the best user-facing API.

## Scope of Compatibility

Compatibility targets behavior and feature set parity, not internal implementation parity.

## API Evolution (Alpha)

Scalive is currently in Alpha.
Until the project reaches a stable release, we optimize for the best API design with no backward-compatibility guarantees.
Breaking changes are expected when they improve clarity, safety, or developer experience.
