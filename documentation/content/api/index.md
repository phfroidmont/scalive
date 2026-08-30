{%
title = "API"
description = "Generated reference for Scalive's supported public packages and import surface."
order = 0
section = api
%}

Browse Scalive's supported public API by package or use **Filter symbols** in the
API browser. Symbols inherited or exported through `import scalive.*` appear
under that public import path.

## Packages {#packages}

- `scalive` contains the core LiveView, component, HTML, form,
  routing, upload, and runtime APIs.
- `scalive.codecs` contains codecs used at browser and
  route boundaries.
- `scalive.testing` contains disconnected rendering,
  page queries, and typed form test utilities.

## Find a symbol {#find-a-symbol}

Use the API browser to explore packages and nested owners. Global documentation
search (`Ctrl K`) searches API declarations and members alongside guides and
examples.

## Core abstraction {#core-abstraction}

Start with @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@, the typed boundary for mounting
state, handling messages, and rendering HTML.

Lifecycle capabilities include
@:apiSymbol(trait:scalive.ConnectedResources)`ConnectedResources`@:@ for
non-message acquisition and finalization during connected mount. The
[lifecycle-owned work guide](../guides/async-work-and-subscriptions.md#choose-the-resource-by-shape)
compares it with managed async tasks and subscriptions.
