# Scalive

Scalive is a Scala 3 implementation of the Phoenix LiveView programming model.
It keeps the LiveView mental model while using Scala features for typed messages,
typed models, typed route params, ZIO effects, and a Scala HTML DSL.

Scalive is currently alpha software. APIs may change while the project optimizes
for the best user-facing Scala API.

## What A LiveView Looks Like

```scala
import scalive.*
import scalive.LiveIO.given

import zio.*

object CounterLiveView extends LiveView[CounterLiveView.Msg, Int]:
  enum Msg:
    case Increment
    case Decrement

  def mount(ctx: MountContext): LiveIO[Int] =
    ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg.Increment => ZIO.succeed(model + 1)
    case Msg.Decrement => ZIO.succeed(model - 1)

  def render(model: Int): HtmlElement[Msg] =
    div(
      button(on.click(Msg.Decrement), "-"),
      span(s"Count: $model"),
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
mill documentation.site.run
mill --ticker false documentation.check
mill --ticker false scalive.test
mill --ticker false __.test
```

The project runs inside `nix develop`; `mill` is available there.

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
