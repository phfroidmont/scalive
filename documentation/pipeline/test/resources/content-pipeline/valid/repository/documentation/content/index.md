{%
title = "Home"
description = "Scalive documentation"
order = 0
section = home
%}

Read the [installation guide](learn/index.md#install), visit the [external docs](https://example.com/docs), and use `inline code` with **strong**, *emphasized*, and ~~deleted~~ text.
This follows a line break.

## Overview with *emphasis* {#overview}

### Details {#details}

```scala
enum Answer:
  case Value

val answer: Int = 42
```

- first
- second

1. first ordered
2. second ordered

> A useful quote.

| Feature | Status |
| ------- | ------ |
| Links   | Ready  |

---

@:example(counter)

@:lab(authentication)

@:sourceRegion(examples/Sample.scala, greeting)

Use @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ and its @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ member.

@:compatibility(server-navigation)

@:callout(info)

Information.

@:@

@:callout(tip)

A useful tip.

@:@

@:callout(warning)

A warning.

@:@

@:callout(error)

An error callout.

@:@
