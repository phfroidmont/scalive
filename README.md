# Scalive

Scalive is a Scala 3 implementation of the Phoenix LiveView programming model.
It keeps the LiveView mental model while using Scala features for typed messages,
typed models, typed route params, ZIO effects, and a Scala HTML DSL.

Scalive is currently alpha software. APIs may change while the project optimizes
for the best user-facing Scala API.

## Runtime Architecture

Scalive separates its public API, retained renderer, lifecycle state machines, Phoenix protocol,
ZIO HTTP transport, and testing support into internal compile and test modules. These are not
separately published coordinates. The
[runtime architecture](documentation/content/project/runtime-architecture.md) explains how HTTP
rendering, connected lifecycles, transactional turns, bounded work, protocol projection, and cleanup
fit together.

Scalive publishes and supports exactly two Scala coordinates:
`dev.scalive::scalive`, containing all production API, render, runtime, protocol, and transport
classes, and `dev.scalive::scalive-testing` for optional test support.

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
mill scalive.api.test
mill scalive.transport.zio-http.test
```

The project runs inside `nix develop`; `mill` is available there. Repository-wide verification uses
`mill __.test`, `mill documentation.check`, and the upstream browser
suite in `scripts/e2e-run-upstream.sh`. Use `scripts/e2e-run-upstream-strict.sh` when a change needs
three consecutive complete browser runs with retries disabled.

## Documentation

- Documentation home: `documentation/content/index.md`
- Learn Scalive: `documentation/content/learn/index.md`
- Guides: `documentation/content/guides/index.md`
- Interactive examples: `documentation/content/examples`
- Public API reference: `documentation/content/api/index.md`
- Runtime architecture: `documentation/content/project/runtime-architecture.md`
- Phoenix LiveView compatibility: `documentation/content/project/compatibility.md`
- Upstream parity fixtures: `e2eApp/src`

The parity fixtures are useful compatibility evidence, but they are not always
recommended application style. Prefer the documentation site and its embedded
examples for learning the normal Scalive API.

## Compatibility

Scalive aims to match Phoenix LiveView behavior and feature set where that makes
sense for Scala. It intentionally diverges when Scala-first APIs improve type
safety, robustness, or ergonomics.

Check the [compatibility matrix](documentation/content/project/compatibility.md) and the upstream
parity fixtures in `e2eApp/src` before relying on a Phoenix LiveView edge case.
