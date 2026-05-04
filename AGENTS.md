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

## Upstream Alignment

We aim to match upstream Phoenix LiveView behavior and feature set as closely as possible.
To track alignment, run upstream end-to-end tests against Scalive with:
`./scripts/e2e-run-upstream.sh`

## Decision Rule

If API quality and strict upstream parity conflict, prefer the best user-facing API.

## Scope of Compatibility

Compatibility targets behavior and feature set parity, not internal implementation parity.

## API Evolution (Alpha)

Scalive is currently in Alpha.
Until the project reaches a stable release, we optimize for the best API design with no backward-compatibility guarantees.
Breaking changes are expected when they improve clarity, safety, or developer experience.
