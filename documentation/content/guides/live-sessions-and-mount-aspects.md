{%
title = "Live sessions and mount aspects"
description = "Group routes that share navigation and mount policy, and derive typed context such as the current user before a LiveView mounts."
order = 22
section = guides
group = "Routing and application structure"
%}

## Before you start {#prerequisites}

You need named route declarations, as in
[Routes, parameters, and navigation](routes-and-navigation.md#name-route-declarations),
and you should know that Scalive mounts a LiveView once for the HTTP response
and again for the live connection
([Lifecycle, state ownership, and reconnects](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)).

This guide builds an account area: every page in it needs the signed-in user,
shares a header, and one page also checks access to a workspace. The examples
use these application types:

@:sourceRegion(documentation/fixtures/guides/src/sessions/Sessions.scala, sessions-domain)

## Group routes in a named live session {#group-routes-in-a-named-session}

A named live session groups routes that share layouts and mount policy. Live
navigation between its routes keeps the WebSocket connection; navigation to a
route outside the session loads a new page. The account area is one session:

@:sourceRegion(documentation/fixtures/guides/src/sessions/Sessions.scala, sessions-account)

`Live.session("account")` names the group. Modifiers installed on the session
builder, here a mount aspect and a layout, apply to every route passed to it.
Pass the session to `Live.router` like a route.

Live session names must be unique in one router. A named live session is
application structure, not a browser session or a login record; your
application still owns login state, expiry, and revocation.

## Derive session context before mount {#derive-typed-context-before-mount}

A @:apiSymbol(class:scalive.LiveSessionMountAspect)`LiveSessionMountAspect`@:@
runs before each mount of a route in its session. It can reject the visitor or
produce typed context, such as the current user:

@:sourceRegion(documentation/fixtures/guides/src/sessions/Sessions.scala, sessions-current-user)

The first function runs for the HTTP request. It authenticates the browser's
cookies and returns two values: signed claims that travel to the live
connection, and the context for this mount. The second function runs for the
connected mount. It receives the claims and loads fresh context from them.
[Treat mount phases independently](#treat-mount-phases-independently) explains
why the work is split this way.

Routes in the session receive the context through `context`, as in
`Routes.account.context((user: CurrentUser) => AccountLiveView(user))`. The
compiler rejects a route that needs context the session does not provide, and
reports a route that reads session context but is passed straight to
`Live.router`.
Layouts installed on the session can read the context too:

@:sourceRegion(documentation/fixtures/guides/src/sessions/Sessions.scala, sessions-account-layout)

Session aspects can be installed only on `Live.session` builders, through
`withMountAspect` or as the aspect passed to `withAdmission`. Aspects compose
from left to right with `++`: each receives the preceding context and may add
another typed value. Prefer a small domain value such as `CurrentUser` over the
complete request or a general service container.

## Derive fresh context for every route mount {#derive-route-context}

A @:apiSymbol(class:scalive.LiveRouteMountAspect)`LiveRouteMountAspect`@:@
checks or loads something for one destination. The workspace route checks that
the user may open the requested workspace:

@:sourceRegion(documentation/fixtures/guides/src/sessions/Sessions.scala, sessions-workspace-access)

It receives a @:apiSymbol(class:scalive.LiveRouteMountRequest)`LiveRouteMountRequest`@:@
with the route's decoded path values and URL, plus the context produced so far,
here the session's `CurrentUser`. Install it on the route's registration with
`withMountAspect`:

@:sourceRegion(documentation/fixtures/guides/src/sessions/Sessions.scala, sessions-workspace-route)

The aspect runs for the HTTP render, the initial connected mount, and every
live navigation to the route. Its result is derived again each time and is
never serialized into a token.

A live patch is different: it keeps the current LiveView mounted and calls
`handleParams`, so it does not rerun the route aspect. If a patch can change the
protected resource, authorize it again in `handleParams` and at each sensitive
operation, or navigate instead so the destination runs its route aspect.

## Choose the right boundary {#choose-the-right-boundary}

Use the router for application-wide structure, a named live session for one
coherent area such as the account pages, and a route modifier for one page.
This keeps policy at the broadest boundary that needs it.

| Boundary | `LiveSessionMountAspect` | `LiveRouteMountAspect` |
| --- | --- | --- |
| Installation scope | A named live session | One route declaration |
| Inputs | Phase-specific request and preceding session context | Decoded destination path values, destination URL, and preceding context |
| Timing | Disconnected mount, initial connected mount, and connected navigation within the named live session | Every disconnected or connected mount of that route, including connected navigation |
| Serialization | Minimal signed claims cross from HTTP to the connected lifecycle | No claims; output is never serialized |
| Failure boundary | HTTP `Response` when disconnected; `LiveSessionMountFailure` when connected | `LiveRouteMountFailure` defines both HTTP and connected outcomes |
| Intended use | Identity and policy shared by a route group | Destination-specific loading, admission, and authorization |

Both kinds decide whether a lifecycle may start and produce typed context.
[Connected-turn guards](lifecycle-hooks.md#connected-turn-guards) reuse that
context when policy must run again before later application work.
[Connected resource initializers](#connected-resource-initialization) attach
cleanup-owned side effects after admission; they do not decide admission or
produce context.

## Treat mount phases independently {#treat-mount-phases-independently}

The disconnected callback sees the browser's original HTTP request, including
cookies and headers. The connected callback receives a request synthesized from
the WebSocket join URL; it does not retain those cookies, headers, method, or body.

A session aspect therefore returns two values during disconnected mount:

- JSON-serializable claims that cross the phase boundary in the signed LiveView
  session; and
- context used only by that disconnected LiveView instance.

The connected callback decodes the claims and independently produces fresh
context for the initial join and connected navigation within the named live
session. Claims are signed but not encrypted and remain visible to the client.
Never put passwords, cookie values, access tokens, or other secrets in them.
Transfer the smallest non-secret identifier and revalidate mutable authorization
state before connected mount. HTTP-only cookies and headers must be consumed at
an ordinary HTTP boundary or by a session aspect during disconnected mount;
route aspects intentionally cannot read them.

## Combine session and route context {#combine-session-and-route-context}

Context accumulates from left to right. In the account session, the session
aspect produces `CurrentUser` and the route aspect adds `WorkspaceAccess`, so
the workspace route's factory receives `(CurrentUser, WorkspaceAccess)` as two
parameters. One more aspect would produce
`(CurrentUser, WorkspaceAccess, NextContext)`.

Positions are fine for one or two parts, but adding an aspect changes every
consumer's tuple. Once an area has several aspects, name each part instead:
when every aspect produces a named tuple, the context is one named tuple, read
by name:

@:sourceRegion(documentation/fixtures/guides/src/sessions/NamedContexts.scala, sessions-named-contexts)

`map` names the output of an existing aspect. The workspace route's context is
`(user: CurrentUser, access: WorkspaceAccess)`, so its factory reads
`context.access`, and a later aspect adds a field without changing code that
doesn't read it. Two aspects producing the same name fail to compile. A route
without its own aspects is built before the session sees it, so its factory
annotates the session's context type, here the `Account` alias. Name every part
or none: an unnamed part and a named one combine into an ordinary pair.

The default `ContextAppend` discards only the initial `Any` identity context,
merges named tuples, and appends other outputs to one flat tuple. Define a
custom `ContextAppend` only when a domain-specific accumulated type is clearer
and its `left` projection can recover the preceding context.

Mount admission does not rerun merely because another application turn arrives
while a view remains mounted. Append a route
@:apiSymbol(def:scalive.LiveRouteSetup.WithContext.guardConnectedTurns)`guardConnectedTurns`@:@
or session
@:apiSymbol(def:scalive.LiveSessionBuilder.guardConnectedTurns)`guardConnectedTurns`@:@
after the aspects or admission that produce its context when policy must be
checked before every later application turn. Session guards run before route
guards and are inherited by nested LiveViews. See
[Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards) for the complete
scope, ordering, and controlled outcomes.

To bind each physical connection to the application session, so a logout can
close every open tab, install the session aspect with `withAdmission` instead of
`withMountAspect`. The [authentication guide](authentication.md#bind-admission-to-physical-connections)
shows the complete flow.

## Annotate logs with the context {#annotate-logs}

Logs from a page usually need to say whose page it was. Rather than annotating
each log call, let the aspect that produces the context annotate every log of
the pages it admits with
@:apiSymbol(def:scalive.LiveSessionMountAspect.logAnnotated)`logAnnotated`@:@:

@:sourceRegion(documentation/fixtures/guides/src/sessions/LogAnnotations.scala, sessions-log-annotations)

The function receives the aspect's context and returns a ZIO aspect that adds
annotations: `ZIOAspect.annotated(key, value)`, or a typed `zio-logging`
`LogAnnotation` applied to a value. Scalive applies it around the page render,
around every connected turn of the LiveView and the fibers that turn starts
(`mount`, handlers, hooks, async work, and subscriptions), and around
[HTTP route](routes-and-navigation.md#serve-http-responses-from-routes)
handlers. Session and route aspects both contribute. A live navigation2019s
destination is admitted again, so it carries the annotations of its own
context, such as another workspace. Logs written while the aspects themselves
run do not carry them yet.

Use it only for log annotations. It wraps all of that work, so an aspect that
changed behavior, such as a timeout, would change every turn.

## Use failure semantics deliberately {#use-failure-semantics-deliberately}

Disconnected session-aspect failures are ordinary HTTP responses. Connected
session failures use @:apiSymbol(enum:scalive.LiveSessionMountFailure)`LiveSessionMountFailure`@:@:

- redirect to a typed location when the visitor can recover elsewhere;
- reject as unauthorized when no navigation is appropriate; or
- report stale state when the browser should reload.

Build session authentication with
@:apiSymbol(def:scalive.LiveSessionMountAspect.fromRequest)`LiveSessionMountAspect.fromRequest`@:@:
the disconnected callback validates the original request and returns minimal
signed claims, while the connected callback revalidates those claims and loads
fresh authorization context. Either callback can redirect to login using its
phase-specific failure type. Cookie validation, expiry, revocation, and claims
resumption remain application policy.

Route aspects fail with
@:apiSymbol(enum:scalive.LiveRouteMountFailure)`LiveRouteMountFailure`@:@. Its
standard outcomes preserve HTTP semantics while producing a controlled connected
rejection:

- `redirect` uses a typed location or an `ExternalUrl` in both phases;
  `redirectUnsafe` accepts an unchecked URL and is only for trusted
  destinations;
- `unauthorized`, `forbidden`, and `notFound` render HTTP 401, 403, and 404
  respectively, with the router's
  [error page](routes-and-navigation.md#answer-missing-pages-and-errors) when it
  has one, while all reject connected admission as unauthorized; and
- `custom` supplies an explicit disconnected `Response` and connected
  `LiveSessionMountFailure` for uncommon policies.

Use `notFound` when hiding record existence is part of policy, as the workspace
aspect's service does, `forbidden` when the HTTP distinction is useful, and
`unauthorized` for missing authentication. These mount outcomes do not replace
per-turn guards or domain authorization at the operation that reads or changes
protected state.

## Attach connected resource initialization {#connected-resource-initialization}

Install `withConnectedResources` on the named live-session builder for every
root mounted in that group, or on a route registration for one destination.
Each callback receives the context available where it is installed and
`ConnectedResources`, returning `Task[Result]` with `Result <: Unit`.

Install after the aspect that supplies the callback's required context. Before
any aspect, the context is `Any`; a later `.context(factory)` is not a context declaration
for an earlier initializer. If another aspect or admission is appended afterward,
the initializer retains its installation-time context via projection. Its
execution still waits for **all** session and route admission/context checks,
including those declared after it. It cannot provide context to later aspects
or return a handle to the page model. Capture services or use the supplied typed
context; the callback's `Task` does not introduce another environment requirement.

Modifiers compose additively: session initializers run first, then route
initializers, in declaration order within each boundary. They run lazily before
connected page mount, never during disconnected rendering or rejected admission.
Page factories and render compilation may already have run. Callback failures
are mount failures rather than controlled authentication rejections; execution
stops and the same connected resource scope releases prior successful acquisitions.

Even a session-installed initializer belongs to each connected root lifecycle,
not the session name, login session, or WebSocket. It runs anew on reconnect or
navigation, not on patches or ordinary messages, and is not inherited by nested
LiveViews. Use a service for shared session ownership. See
[route and session resources](connected-resources.md#route-and-session-resources)
for the executable example, bounded acquisition/finalization requirements, and
when to keep acquisition directly in the page's mount.

## Related tasks {#related-tasks}

- Add a complete login and logout flow with [Authentication and sessions](authentication.md#authenticate-both-mount-phases).
- Wrap a session's pages in shared markup with [Layouts](layouts.md#know-where-each-layout-applies).
- Recheck connected policy with [Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards).
- Construct LiveViews from application dependencies with [Services and dependency injection](services-and-zlayer-injection.md#prerequisites).
- Check navigation across named live-session boundaries in [Routes, parameters, and navigation](routes-and-navigation.md#respect-route-and-session-boundaries).
