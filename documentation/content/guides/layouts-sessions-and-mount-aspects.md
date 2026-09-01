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

Use the router for application-wide structure, a named live session for one
coherent area such as authenticated account pages, and a route modifier for one page.
This keeps policy close to the broadest boundary that actually needs it.
Session and route mount aspects decide whether a lifecycle may start and produce
typed context. Session aspects carry claims across the HTTP-to-socket boundary;
route aspects derive fresh, claimless context for each route mount.
Connected-turn guards reuse that context when policy must run again before later
application work.

| Boundary | `LiveSessionMountAspect` | `LiveRouteMountAspect` |
| --- | --- | --- |
| Installation scope | A named live session | One route declaration |
| Inputs | Phase-specific request and preceding session context | Typed destination parameters, destination URL, and preceding context |
| Timing | Disconnected mount, initial connected mount, and connected navigation within the named live session | Every disconnected or connected mount of that route, including connected navigation |
| Serialization | Minimal signed claims cross from HTTP to the connected lifecycle | No claims; output is never serialized |
| Failure boundary | HTTP `Response` when disconnected; `LiveMountFailure` when connected | `LiveRouteMountFailure` defines both HTTP and connected outcomes |
| Intended use | Identity and policy shared by a route group | Destination-specific loading, admission, and authorization |

## Install Root And Ordinary Layouts {#install-root-and-ordinary-layouts}

The root layout owns `<html>`, `<head>`, assets, and `<body>`. Give it a stable
compatibility key:

```scala
val root = LiveRootLayout[Any, Any]("application-root")([Msg] =>
  (content, pageTitle, _) =>
    htmlRootTag(
      headTag(liveTitle(pageTitle, default = "My application")),
      bodyTag(content)
    )
)
```

Routes with the same root key may use connected navigation without replacing the
document. When the key changes, Scalive falls back to a fresh HTTP request
instead of trying to reuse an incompatible document shell.

Ordinary layouts wrap the LiveView inside the selected root:

```scala
val applicationShell = LiveLayout[Any, Any]([Msg] =>
  (content, _) =>
    div(
      headerTag(a(href := "/", "My application")),
      mainTag(content)
    )
)

val router = Live.router
  .withRootLayout(root)
  .withLayout(applicationShell)
```

Ordinary layouts are signal-backed view graphs. Their `params`, `request`, and
`currentUrl` context values are read-only `Signal`s, so route-dependent chrome
updates without reconstructing the layout:

```scala
val routeShell = LiveLayout[WorkspaceId, CurrentUser]([Msg] =>
  (content, ctx) =>
    div(
      dataAttr("workspace") := ctx.params.map(_.value),
      p("Signed in as ", ctx.context.name),
      mainTag(content)
    )
)
```

Root-layout context remains value-backed because the root document is rendered
only for the disconnected HTTP response; connected diffs patch the LiveView
inside it.

Router layouts are outermost, followed by session layouts and route layouts.
Within one level, registration order is preserved. Root layouts do not compose:
a route root overrides a session root, which overrides the router root.

## Group Routes In A Named Live Session {#group-routes-in-a-named-session}

A named live session applies common modifiers to several routes and defines which
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

Live-session names must be unique in one router. Treat the name as application
structure, not as a browser session identifier or authentication record. A
named live session groups route behavior; your service still owns login state,
expiry, and revocation.

## Derive Session Context Before Mount {#derive-typed-context-before-mount}

A @:apiSymbol(class:scalive.LiveSessionMountAspect)`LiveSessionMountAspect`@:@
runs before both the disconnected HTTP mount and the fresh connected mount. It
can reject the request or produce typed context required by routes in the named
session:

```scala
val account = Live
  .session("account")
  .withMountAspect(currentUser)(
    (live / "account").context(AccountLiveView.apply)
  )
```

The route factory receives the aspect result. The compiler rejects a route whose
factory requires context that preceding aspects did not provide. The
[combined example](#combine-session-and-route-context) shows this context flowing
through session and route admission.

Session aspects are claim-bearing and can be installed only on `Live.session`
builders through `withMountAspect` or as the aspect passed to `withAdmission`.
Aspects compose from left to right with `++`. Each aspect receives the preceding
context and may append another typed value. Prefer a small domain value such as
`CurrentUser` over passing the complete request or a general service container.

## Treat Mount Phases Independently {#treat-mount-phases-independently}

The disconnected callback sees the browser's original HTTP request, including
cookies and headers. The connected callback receives a request synthesized from
the socket join URL; it does not retain those original cookies, headers, method,
or body.

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
an ordinary HTTP/document boundary or by a session aspect during disconnected
mount; route aspects intentionally cannot read them.

## Derive Fresh Context For Every Route Mount {#derive-route-context}

A @:apiSymbol(class:scalive.LiveRouteMountAspect)`LiveRouteMountAspect`@:@ is
claimless. It receives a @:apiSymbol(class:scalive.LiveRouteMountRequest)`LiveRouteMountRequest`@:@
containing the route's typed path parameters and URL, plus any typed context supplied
by its named live session or a preceding route aspect. Install it only on a
route builder through `withMountAspect`:

```scala
val authorizeWorkspace =
  LiveRouteMountAspect.make[Any, String, CurrentUser, WorkspaceAccess] {
    (request, currentUser) =>
      workspaces.authorize(currentUser, WorkspaceId(request.params))
        .mapError(_ => LiveRouteMountFailure.notFound("workspace unavailable"))
  }

val workspace =
  (live / "workspaces" / PathCodec.string("id"))
    .withMountAspect(authorizeWorkspace)
    .context(WorkspaceLiveView.apply)
```

The aspect runs for disconnected HTTP rendering, the initial connected join,
and every connected navigation to the route within the named live session. Its
result is freshly derived each time and is never serialized into a token or
trusted across route changes.

A live patch is different: it keeps the current lifecycle mounted and calls
`handleParams`, so it does not rerun the route aspect. Use route aspects to admit
a mounted destination. If a patch can change the protected resource identity,
reauthorize that identity in `handleParams` and again at each sensitive operation,
or navigate instead so the destination receives fresh route admission.

## Combine Session And Route Context {#combine-session-and-route-context}

This complete pipeline authenticates the application session at the named live
session's boundary, binds each physical connection to its public ID, and then
authorizes the typed destination:

```scala
import scalive.*
import zio.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.json.*

final case class SessionId(value: String) derives JsonCodec
final case class AuthClaims(sessionId: SessionId) derives JsonCodec
final case class CurrentUser(id: Long, name: String)
final case class AuthenticatedSession(sessionId: SessionId, currentUser: CurrentUser)
final case class WorkspaceAccess(workspaceId: String, canEdit: Boolean)

trait Authentication:
  def authenticate(request: Request): IO[Response, AuthenticatedSession]
  def resume(sessionId: SessionId): IO[LiveMountFailure, CurrentUser]

trait Workspaces:
  def authorize(
    user: CurrentUser,
    workspaceId: String
  ): IO[LiveRouteMountFailure, WorkspaceAccess]

final class WorkspaceLiveView(user: CurrentUser, access: WorkspaceAccess)
    extends LiveView.Eventless[Unit]:
  def mount(ctx: MountContext) = ZIO.unit
  def view(model: Signal[Unit]) = div(s"${user.name}: ${access.workspaceId}")

val currentUser =
  LiveSessionMountAspect.fromRequest[Authentication, AuthClaims, CurrentUser](
    request =>
      ZIO.serviceWithZIO[Authentication](_.authenticate(request.request)).map { authenticated =>
        AuthClaims(authenticated.sessionId) -> authenticated.currentUser
      },
    (claims, _) =>
      ZIO.serviceWithZIO[Authentication](_.resume(claims.sessionId))
  )

val workspaceAccess =
  LiveRouteMountAspect.make[Workspaces, String, CurrentUser, WorkspaceAccess] {
    (destination, user) =>
      ZIO.serviceWithZIO[Workspaces](_.authorize(user, destination.params))
  }

val workspaceRoute =
  (live / "workspaces" / PathCodec.string("workspaceId"))
    .withMountAspect(workspaceAccess)
    .context((context: (CurrentUser, WorkspaceAccess)) =>
      new WorkspaceLiveView(context._1, context._2)
    )

val accountRoutes = Live
  .session("account")
  .withAdmission(currentUser)(_.sessionId)(workspaceRoute)
```

The default `ContextAppend` discards only the initial `Any` identity context.
After that it accumulates each output as a pair without flattening: here the
route factory receives `(CurrentUser, WorkspaceAccess)`; one more aspect would
produce `((CurrentUser, WorkspaceAccess), NextContext)`. Define a custom
`ContextAppend` only when a domain-specific accumulated type is clearer and its
`left` projection can recover the preceding context.

Mount admission does not rerun merely because another application turn arrives
while a view remains mounted. Append a route
@:apiSymbol(def:scalive.LiveRouteMountAspectBuilder.guardConnectedTurns)`guardConnectedTurns`@:@
or session
@:apiSymbol(def:scalive.LiveSessionBuilder.Admitted.guardConnectedTurns)`guardConnectedTurns`@:@
after the aspects or admission that produce its context when policy must be
checked before every later application turn. Session guards run before route
guards and are inherited by nested LiveViews. See
[Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards) for the complete
scope, ordering, and controlled outcomes.

## Use Failure Semantics Deliberately {#use-failure-semantics-deliberately}

Disconnected session-aspect failures are ordinary HTTP responses. Connected
session failures use @:apiSymbol(enum:scalive.LiveMountFailure)`LiveMountFailure`@:@:

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

- `redirect` uses a typed location in both phases; `redirectUnsafe` accepts an
  unchecked URL and is only for trusted destinations;
- `unauthorized`, `forbidden`, and `notFound` render HTTP 401, 403, and 404
  respectively, while all reject connected admission as unauthorized; and
- `custom` supplies an explicit disconnected `Response` and connected
  `LiveMountFailure` for uncommon policies.

Use `notFound` when hiding record existence is part of policy, `forbidden` when
the HTTP distinction is useful, and `unauthorized` for missing authentication.
These mount outcomes do not replace per-turn guards or domain authorization at
the operation that reads or changes protected state.

Continue with [Authentication](authentication.md#separate-http-login-from-live-authorization)
for a complete runnable flow.

## Related Tasks {#related-tasks}

- Add protected route context with [Authentication and sessions](authentication.md#authenticate-both-mount-phases).
- Recheck connected policy with [Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards).
- Construct LiveViews from application dependencies with [Services and dependency injection](services-and-zlayer-injection.md#prerequisites).
- Check navigation across named live-session boundaries in [Routes, parameters, and navigation](routes-and-navigation.md#respect-route-and-session-boundaries).
