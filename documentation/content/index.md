{%
title = "Live interfaces. Typed end to end."
description = "Build real-time server-rendered Scala applications with typed state and messages."
order = 0
section = home
%}

Scalive is a Scala 3 re-implementation of Phoenix LiveView. It keeps application
state and rendering on the server while browser events arrive as typed messages.

[Learn the fundamentals](learn/index.md#start-here) or [Explore the API](api/index.md).

```scala
import scalive.*

enum Message:
  case Increment
```

@:example(counter)

- Typed state and typed messages keep server transitions explicit.
- Scala's HTML DSL renders interfaces without a separate frontend component framework.
- Live diffs and effects update the browser while application state stays on the server.

## Why Scalive {#why-scalive}

The API combines a typed model, typed messages, ZIO effects, and an HTML DSL. A
LiveView describes its initial state, its state transitions, and its rendered
output without introducing a separate frontend component framework.

@:callout(info)

Scalive is currently alpha software. APIs may change when a clearer or safer
design is available.

@:@
