{%
title = "Layouts, live sessions, and mount aspects"
description = "Compose application shells, group compatible routes, and derive typed context before LiveView mount."
order = 21
section = guides
group = "Routing and application structure"
%}

## Before You Start {#prerequisites}

You need at least two named Live route declarations and should understand that Scalive
mounts once for HTTP rendering and again for the live connection. Review
[Routes, parameters, and navigation](routes-and-navigation.md#name-route-declarations)
if your routes are still assembled from ad hoc strings.

## Choose The Right Boundary {#choose-the-right-boundary}

Scalive separates three application-structure concerns:

- a @:apiSymbol(trait:scalive.LiveRootLayout)`LiveRootLayout`@:@ renders the outer
  HTML document and identifies routes that can share live navigation;
- a @:apiSymbol(trait:scalive.LiveLayout)`LiveLayout`@:@ wraps rendered LiveView
  content inside that document; and
- a named live session groups routes that share mount policy, layouts, token
  settings, and a connected-navigation boundary.

Use the router for application-wide structure, a named session for one coherent
area such as authenticated account pages, and a route modifier for one page.
This keeps policy close to the broadest boundary that actually needs it.
Mount aspects decide whether a lifecycle may start and produce typed context;
connected-turn guards reuse that context when policy must run again before later
application work.

## Install Root And Ordinary Layouts {#install-root-and-ordinary-layouts}

The root layout owns `<html>`, `<head>`, assets, and `<body>`. Give it a stable
compatibility key:

```scala
val root = LiveRootLayout("application-root") { (content, pageTitle, _) =>
  htmlRootTag(
    headTag(liveTitle(pageTitle, default = "My application")),
    bodyTag(content)
  )
}
```

Routes with the same root key may navigate over one connected live session.
When the key changes, Scalive falls back to a fresh HTTP request instead of
trying to reuse an incompatible document shell.

Ordinary layouts wrap the LiveView inside the selected root:

```scala
val applicationShell = LiveLayout[Any, Any] { (content, _) =>
  div(
    headerTag(a(href := "/", "My application")),
    mainTag(content)
  )
}

val router = Live.router
  .withRootLayout(root)
  .withLayout(applicationShell)
```

Ordinary layouts are signal-backed view graphs. Their `params`, `request`, and
`currentUrl` context values are read-only `Signal`s, so route-dependent chrome
updates without reconstructing the layout:

```scala
val routeShell = LiveLayout[WorkspaceId, CurrentUser] { (content, ctx) =>
  div(
    dataAttr("workspace") := ctx.params.map(_.value),
    p("Signed in as ", ctx.context.name),
    mainTag(content)
  )
}
```

Root-layout context remains value-backed because the root document is rendered
only for the disconnected HTTP response; connected diffs patch the LiveView
inside it.

Router layouts are outermost, followed by session layouts and route layouts.
Within one level, registration order is preserved. Root layouts do not compose:
a route root overrides a session root, which overrides the router root.

## Group Routes In A Named Session {#group-routes-in-a-named-session}

A named session applies common modifiers to several routes and defines which
routes may use live navigation together:

```scala
val accountRoutes = Live
  .session("account")
  .withLayout(accountLayout)(
    (live / "account") -> AccountLiveView(),
    (live / "account" / "settings") -> SettingsLiveView()
  )

val routes = router(accountRoutes)
```

Session names must be unique in one router. Treat the name as application
structure, not as a browser session identifier or authentication record. A live
session groups route behavior; your service still owns login state, expiry, and
revocation.

## Derive Typed Context Before Mount {#derive-typed-context-before-mount}

A @:apiSymbol(class:scalive.LiveMountAspect)`LiveMountAspect`@:@ runs before both
the disconnected HTTP mount and the fresh connected mount. It can reject the
request or produce typed context required by the route:

```scala
val account = Live
  .session("account")
  .withMountAspect(currentUser)(
    (live / "account").context(AccountLiveView.apply)
  )
```

The `context` constructor receives the aspect result directly. The compiler
rejects a route whose constructor requires context that preceding aspects did
not provide.

Aspects compose from left to right with `++`. Each aspect receives the preceding
context and may append another typed value. Prefer a small domain value such as
`CurrentUser` over passing the complete request or a general service container.

## Treat Mount Phases Independently {#treat-mount-phases-independently}

The disconnected callback sees the browser's original HTTP request, including
cookies and headers. The connected callback receives a request synthesized from
the socket join URL; it does not retain those original cookies, headers, method,
or body.

An aspect therefore returns two values during disconnected mount:

- JSON-serializable claims that cross the phase boundary in the signed LiveView
  session; and
- context used only by that disconnected LiveView instance.

The connected callback decodes the claims and independently produces fresh
context. Claims are signed but not encrypted and remain visible to the client.
Never put passwords, cookie values, access tokens, or other secrets in them.
Transfer the smallest non-secret identifier and revalidate mutable authorization
state before connected mount.

Mount admission does not rerun while a socket remains connected. Append a route
@:apiSymbol(def:scalive.LiveRouteMountAspectBuilder.guardConnectedTurns)`guardConnectedTurns`@:@
or session
@:apiSymbol(def:scalive.LiveSessionBuilder.Admitted.guardConnectedTurns)`guardConnectedTurns`@:@
after the aspects or admission that produce its context when policy must be
checked before every later application turn. Session guards run before route
guards and are inherited by nested LiveViews. See
[Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards) for the complete
scope, ordering, and controlled outcomes.

## Use Failure Semantics Deliberately {#use-failure-semantics-deliberately}

Disconnected aspect failures are ordinary HTTP responses. Connected failures
use @:apiSymbol(enum:scalive.LiveMountFailure)`LiveMountFailure`@:@:

- redirect to a typed location when the visitor can recover elsewhere;
- reject as unauthorized when no navigation is appropriate; or
- report stale state when the browser should reload.

Build authentication with
@:apiSymbol(def:scalive.LiveMountAspect.fromRequest)`LiveMountAspect.fromRequest`@:@:
the disconnected callback validates the original request and returns minimal
signed claims, while the connected callback revalidates those claims and loads
fresh authorization context. Either callback can redirect to login using its
phase-specific failure type. Cookie validation, expiry, revocation, and claims
resumption remain application policy.

Continue with [Authentication](authentication.md#separate-http-login-from-live-authorization)
for a complete runnable flow.

## Related Tasks {#related-tasks}

- Add protected route context with [Authentication and sessions](authentication.md#prerequisites).
- Recheck connected policy with [Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards).
- Construct LiveViews from application dependencies with [Services and dependency injection](services-and-zlayer-injection.md#prerequisites).
- Check navigation across session boundaries in [Routes, parameters, and navigation](routes-and-navigation.md#respect-route-and-session-boundaries).
