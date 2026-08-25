{%
title = "Project anatomy"
description = "Understand the startup path and the boundaries between routes, layouts, LiveViews, services, assets, and the browser."
order = 2
section = learn
%}

## Follow The Startup Path {#follow-the-startup-path}

The [quick start](quick-start.md) keeps composition explicit. Read it from the
process toward the browser:

1. Mill compiles Scala and bundles browser assets into JVM resources.
2. `Main` loads configuration, security, static assets, and application services.
3. `Main` pairs typed routes with LiveViews and starts ZIO HTTP.
4. A route selects a LiveView, and its layouts wrap the rendered page.
5. The browser loads `app.js`, connects, forwards events, and applies updates.

## Understand Both Mounts {#understand-both-mounts}

A Live route mounts once to produce the initial HTTP response and again when the
browser establishes its live connection. These mounts create independent models;
the HTTP model is not passed into the connected lifecycle. The
[lifecycle page](lifecycle-and-connection-behavior.md#two-independent-mounts)
explains the consequences for state, resources, failures, and reconnects.

## Separate The Application Boundaries {#separate-the-application-boundaries}

`Main.scala` is the process boundary. It owns configuration, resource loading,
dependency layers, route composition, and server lifetime. Per-visitor state and
HTML rendering do not belong there.

`Routes.scala` is the URL boundary. Routes begin with
@:apiSymbol(val:scalive.live)`live`@:@, decode path or query data when needed,
and are paired with @:apiSymbol(trait:scalive.LiveView)`LiveViews`@:@ by
@:apiSymbol(val:scalive.Live.router)`Live.router`@:@. Named route values also
give navigation code one typed source of URLs.

`RootLayout.scala` is the document boundary. A
@:apiSymbol(trait:scalive.LiveRootLayout)`LiveRootLayout`@:@ renders the outer
`<html>`, `<head>`, and `<body>`. It owns global scripts, stylesheets, metadata,
and the fallback title. When served through `ZioHttp.routes` with validated
configuration, Scalive injects the browser-bound CSRF meta token into its
`<head>`.

`CounterLiveView.scala` is the interactive-page boundary.
@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ creates connection-local
state, @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@ changes
state, and @:apiSymbol(def:scalive.LiveView.view)`view`@:@ describes typed HTML
and event bindings. A LiveView does not start the HTTP server or locate its own
asset files.

`assets/js/app.js` is the browser boundary. It creates `LiveSocket`, passes the
server-issued CSRF token, and connects to the socket path. Add hooks here only
for behavior that requires browser APIs. Application state and ordinary event
handling stay in Scala.

`package.json` and the Mill asset task are the build boundary. npm resolves and
bundles browser modules; Mill places outputs on the JVM classpath.
@:apiSymbol(class:scalive.StaticAssets)`StaticAssets`@:@ fingerprints those
outputs, renders tracked URLs, and serves them.

## Keep Dependencies Pointing Inward {#keep-dependencies-pointing-inward}

The process constructs infrastructure and passes dependencies to routed
LiveViews. A growing application normally follows this direction:

```text
Main
├── StaticAssets
├── LiveSecurity
├── application services
└── Live.router
    ├── typed routes
    ├── root and live layouts
    └── LiveViews
        ├── immutable models
        ├── typed messages
        └── rendered HTML

Browser
└── LiveSocket
    ├── DOM events to the server
    └── server patches to the DOM
```

LiveViews may use injected services, but services should not depend on a
particular LiveView or browser connection. This keeps durable domain behavior
separate from connection-local presentation state.

## Grow The Project {#grow-the-project}

Keep these boundaries as the application expands:

- Add route values before wiring new LiveViews into the router.
- Use a @:apiSymbol(trait:scalive.LiveLayout)`LiveLayout`@:@ for shared markup
  inside the document; reserve `LiveRootLayout` for the complete shell.
- Build ZIO layers at startup for services required by routed LiveViews.
- Add browser packages and hooks only for browser-side behavior.
- Keep durable state in services or storage rather than a LiveView model.
