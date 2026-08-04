{%
title = "Scalive"
description = "Build real-time server-rendered Scala applications with typed state and messages."
order = 0
section = home
%}

Scalive is a Scala 3 re-implementation of Phoenix LiveView. It keeps application
state and rendering on the server while browser events arrive as typed messages.

Continue with [Learn](learn/index.md#start-here) for the shortest path into the
framework.

## Why Scalive {#why-scalive}

The API combines a typed model, typed messages, ZIO effects, and an HTML DSL. A
LiveView describes its initial state, its state transitions, and its rendered
output without introducing a separate frontend component framework.

```scala
import scalive.*

enum Message:
  case Increment
```

@:callout(info)

Scalive is currently alpha software. APIs may change when a clearer or safer
design is available.

@:@
