# Scalive

Scalive is a Scala 3 implementation of the Phoenix LiveView programming model.
It keeps the LiveView mental model while using Scala features for typed messages,
typed models, typed route params, ZIO effects, and a Scala HTML DSL.

Scalive is currently alpha software. APIs may change while the project optimizes
for the best user-facing Scala API.

## Runtime Rewrite Status

The runtime is being rebuilt against the target architecture in
[`docs/runtime-target-implementation-plan.md`](docs/runtime-target-implementation-plan.md). The
independent `scalive-api` boundary and final Mill module graph are available; executable HTTP,
WebSocket, rendering, and lifecycle runtime behavior will return through the subsequent runtime
milestones.

The frozen legacy behavior oracle remains available at tag
`runtime-legacy-baseline-2026-08-18`. Its native and browser results are recorded in
[`docs/runtime-parity-manifest.md`](docs/runtime-parity-manifest.md).

## What A LiveView Looks Like

```scala
import scalive.*

import zio.*

object CounterLiveView extends LiveView[CounterLiveView.Msg, Int]:
  enum Msg:
    case Increment
    case Decrement

  def mount(ctx: MountContext): Task[Int] =
    ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg.Increment => ZIO.succeed(model + 1)
    case Msg.Decrement => ZIO.succeed(model - 1)

  def view(model: Signal[Int]): HtmlElement[Msg] =
    div(
      button(on.click(Msg.Decrement), "-"),
      span(model.map(count => s"Count: $count")),
      button(on.click(Msg.Increment), "+")
    )
```

## Routing And Server Setup

Routes start from `scalive.live` and are assembled with `Live.router`.
The [quick start](documentation/content/learn/quick-start.md) contains a complete
runnable setup, including static assets, routes, socket configuration, and root
layout wiring.

## Client Setup

Scalive uses a LiveView-compatible JavaScript client connection. See the
[static assets and client setup guide](documentation/content/guides/static-assets-and-client-setup.md)
for the expected socket path, root layout, and browser asset setup.

## Running The Project

```bash
mill --ticker false scalive.api.test
mill --ticker false scalive.transport.zio-http.test
```

The project runs inside `nix develop`; `mill` is available there. Repository-wide runtime,
documentation-site, and upstream browser gates resume as their corresponding greenfield modules are
implemented.

## Documentation

- Documentation content: `documentation/content`
- Interactive examples: `documentation/content/examples`
- Public API reference: `docs/public-api-reference.md`
- API improvement backlog: `docs/api-improvement-ideas.md`
- Phoenix LiveView compatibility notes: `UPSTREAM_COMPATIBILITY.md`
- Upstream parity fixtures: `e2eApp/src`

The parity fixtures are useful compatibility evidence, but they are not always
recommended application style. Prefer the documentation site and its embedded
examples for learning the normal Scalive API.

## Compatibility

Scalive aims to match Phoenix LiveView behavior and feature set where that makes
sense for Scala. It intentionally diverges when Scala-first APIs improve type
safety, robustness, or ergonomics.

Do not assume complete Phoenix LiveView parity without checking
`UPSTREAM_COMPATIBILITY.md` and the upstream parity fixtures in `e2eApp/src`.
