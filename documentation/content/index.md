{%
title = "Live interfaces. Typed end to end."
description = "Build real-time server-rendered Scala applications with typed state and messages."
order = 0
section = home
%}

Build real-time web applications entirely in Scala. Keep state and rendering on
the server, handle browser events as typed messages, and send efficient HTML
updates over the wire.

[Build your first LiveView](learn/quick-start.md) [Browse examples](examples/counter.md)

```scala
import scalive.*
import zio.ZIO

enum Msg:
  case Increment

def handleMessage(model: Int, ctx: MessageContext) =
  case Msg.Increment => ZIO.succeed(model + 1)
```

@:example(counter)

- **One typed model.** State, messages, transitions, and rendering remain explicit and compiler checked.
- **HTML from Scala.** Build interfaces with a typed HTML DSL instead of maintaining a separate frontend component tree.
- **Live over the wire.** Browser events reach the server as typed messages; Scalive sends efficient HTML diffs back.

## How it works {#how-it-works}

One event travels through a small, observable server-owned loop.

1. **Browser event.** A user interaction is sent over the live connection.
2. **Typed message.** The event is decoded into a message your
   @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ accepts.
3. **Server transition.** Your handler updates typed state and can run ZIO effects.
4. **HTML diff.** Scalive renders the next view and sends only the changes to the browser.

## Why Scalive {#why-scalive}

Scalive brings the Phoenix LiveView model to Scala 3 and ZIO. A
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@
describes its initial state, transitions, effects, and rendered output without
introducing a separate frontend component framework.

It is designed for Scala teams that want interactive applications while keeping
domain logic, UI state, and effect handling in one typed server-side system. Read
[why I built Scalive](project/why-i-built-scalive.md#choosing-a-stack) for the
journey that led to this design.

- Scala 3
- ZIO effects
- Server-owned state
- Typed HTML
- Phoenix LiveView semantics

## Start building {#start-building}

Follow the [guided introduction](learn/index.md#start-here), inspect the
[working examples](examples/index.md), or use the [API reference](api/index.md)
when you already know what you need.

@:callout(info)

Scalive is currently alpha software. Core concepts work today, but APIs may
change when a clearer or safer design is available.

@:@
