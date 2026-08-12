{%
title = "Project anatomy"
description = "Understand the startup, routes, layouts, assets, and LiveView boundaries in a Scalive application."
order = 2
section = learn
%}

## Follow The Startup Path {#follow-the-startup-path}

The [quick start](quick-start.md) keeps startup explicit. Read it from the
outside in:

1. Mill compiles the application and bundles browser assets into classpath
   resources.
2. `Main` loads those resources as `StaticAssets`.
3. `Main` creates `LiveSecurity`, configures `Live.router`, and attaches the root
   layout.
4. The router pairs each typed route with a LiveView.
5. ZIO HTTP serves the Live routes and static asset routes together.

`Main` is the process boundary. It owns configuration, resource loading,
dependency layers, route composition, and the server lifetime. Keep rendering
and per-connection state out of it.

## Separate The Boundaries {#separate-the-boundaries}

`Routes.scala` is the URL boundary. Routes begin with `live`, decode path or
query data when needed, and are paired with LiveViews by `Live.router`. Keeping
route values separate also gives navigation code one typed source of URLs.

`RootLayout.scala` is the document boundary. A `LiveRootLayout` renders the
outer `<html>`, `<head>`, and `<body>` around the current LiveView. It owns global
scripts, stylesheets, metadata, and the fallback title. With security enabled,
Scalive injects the browser-bound CSRF meta token into its `<head>`.

`CounterLiveView.scala` is the connection boundary. `mount` creates a model for
the disconnected HTTP render and independently for the connected socket.
`handleMessage` changes connected state, and `render` turns the current model
into typed HTML. A LiveView should not start the HTTP server or locate its own
asset files.

`assets/js/app.js` is the browser boundary. It creates the Phoenix
`LiveSocket`, passes the server-issued CSRF token, and connects to the server's
socket path. Add hooks here only for behavior that truly requires browser APIs;
application state and ordinary event handling stay in Scala.

`package.json` and the Mill asset task are the build boundary. npm resolves and
bundles browser modules; Mill places declared outputs on the JVM classpath.
`StaticAssets` fingerprints those outputs, renders tracked URLs, and serves both
the digested and configured original paths.

## Understand Both Mounts {#understand-both-mounts}

A page starts as an ordinary HTTP response. Scalive mounts and renders the
LiveView so the browser receives useful HTML before a socket exists. The root
layout wraps that render and security adds the CSRF token and cookie.

The JavaScript client then opens `/live`. Scalive validates the returned token
and mounts a new connected lifecycle. Events affect this connected model; they
do not continue the model instance created for the disconnected render. Treat
`mount` as repeatable, and use `ctx.connected` when work belongs only to the
connected phase.

## Grow The Project {#grow-the-project}

Keep the same boundaries as the application expands:

- Add route values before wiring new LiveViews into the router.
- Add a `LiveLayout` for shared markup inside the document; reserve
  `LiveRootLayout` for the complete document shell.
- Add ZIO layers at startup for services required by routed LiveViews.
- Add npm packages or CSS tooling only when browser-side behavior or styling
  needs them.
- Continue with [Models and messages](models-and-messages.md) for immutable state
  and typed message design.

Return to the [quick-start run command](quick-start.md#run-it) whenever you need
the complete minimal wiring in one place.
