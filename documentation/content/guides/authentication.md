{%
title = "Authentication and sessions"
description = "Combine ordinary HTTP login and logout with opaque sessions and protected LiveView mounts."
order = 23
section = guides
group = "Routing and application structure"
%}

## Before You Start {#prerequisites}

Start with an [ordinary HTTP route](http-forms-and-redirects.md) that can validate
a form, set a cookie, and redirect, plus a
[named live session](layouts-sessions-and-mount-aspects.md#group-routes-in-a-named-session)
that can run a mount aspect. The HTTP and Live routes must also be able to receive one shared
authentication service;
[service layers](services-and-zlayer-injection.md#provide-services-at-startup)
are one way to provide that capability.

## Separate HTTP Login From Live Authorization {#separate-http-login-from-live-authorization}

Use ordinary HTTP for credential submission, cookie changes, and redirects.
Use a session mount aspect to authorize the disconnected and connected LiveView
lifecycles. This division keeps password handling out of socket events and lets
the browser apply normal cookie and redirect semantics. Cookies and original
HTTP headers belong at ordinary HTTP/document or session boundaries, not in a
route mount aspect.

The documentation authentication lab demonstrates this complete flow:

1. `GET /examples/authentication/lab` renders a CSRF-protected login form.
2. `POST /examples/authentication/lab/session` validates the CSRF token, bounded
   form body, and fixed lab credentials.
3. A successful response sets an opaque `HttpOnly`, `SameSite=Lax` session cookie
   and redirects to the protected route.
4. The protected route authenticates that cookie during disconnected mount.
5. Connected mount receives only a public session ID in signed claims and checks
   the server-side session record again.
6. Reset revokes only the current visitor's application session, disconnects
   every active tab using its ID, clears that visitor's login attempts, expires
   the cookie, and redirects to the login page.

@:lab(authentication)

The lab uses fixed credentials and stores no account changes. Its route is
intentionally excluded from the public content index because it is a standalone
HTTP and LiveView lab rather than a documentation page.

## Keep Credentials In Ordinary HTTP {#keep-credentials-in-ordinary-http}

Render a normal form with @:apiSymbol(def:scalive.Form.http)`Form.http`@:@ and
decode it in a ZIO HTTP handler with
@:apiSymbol(def:scalive.HttpFormDecoder.urlEncoded)`HttpFormDecoder.urlEncoded`@:@.
The decoder applies a byte limit, validates the framework CSRF token, and then
projects the rooted definition. Login only continues for valid domain output, so
use `urlEncodedValue`:

```scala
private val loginDecoder =
  HttpFormDecoder.urlEncodedValue(LoginForm.Definition, 4096L, security.csrf)
```

The `urlEncoded(LoginForm.Definition, ...)` variant instead returns a submitted
`LoginForm.Definition.Form` even when its `result` contains validation errors;
use that form when an HTTP response will render submitted values and feedback
again. Both definition-backed variants preserve typed form semantics after the
same body and CSRF checks.

Return one generic response for incorrect email and password values. Distinguish
protocol failures such as malformed encoding, an oversized body, or an
unsupported content type through ordinary HTTP status codes, but do not reveal
whether an account exists.

The lab limits each browser-bound visitor record to five failed attempts per
minute. Attempt records and sessions both have configured capacity bounds. A
production application should choose a policy suitable for its threat model and
deployment topology rather than copying these demonstration values blindly.

## Store Opaque, Bounded Sessions {#store-opaque-bounded-sessions}

Generate a cryptographically random cookie token and store only its hash as the
server-side lookup key. Keep a separate non-secret public session ID for claims.
The lab sessions:

- expire after 30 minutes;
- are capped at 1,024 records;
- evict the oldest record when full; and
- are revoked explicitly by reset.

The in-memory store is intentionally process-local and suitable only for this
single-instance lab. A production service normally persists sessions in a
bounded shared store when multiple application instances must accept the same
cookie.

## Bind Admission To Physical Connections {#bind-admission-to-physical-connections}

`withAdmission(aspect)(connectionId)` installs the named live session's one
connection-admission boundary. The projection extracts an application-owned ID
from the aspect's signed claims. During connected mount, Scalive registers the
physical connection under that ID before running the aspect's authoritative
connected callback. A later `LiveConnections.disconnect(id)` can therefore
promptly close every registered physical connection, including multiple tabs;
each reconnect must pass admission again.

Use plain `withMountAspect` when typed mount context is sufficient and the
application does not need connection lookup, prompt invalidation, or distributed
disconnect fanout by that claim-derived ID. Admission complements durable
application-session revocation; `LiveConnections` is not authentication truth.
See the [canonical combined pipeline](layouts-sessions-and-mount-aspects.md#combine-session-and-route-context)
for the generic and accumulated context shapes.

## Authenticate Both Mount Phases {#authenticate-both-mount-phases}

The protected named live session installs this executable
@:apiSymbol(class:scalive.LiveSessionMountAspect)`LiveSessionMountAspect`@:@:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-mount-aspect)

During disconnected mount, `authenticate` validates the opaque cookie and
returns minimal `AuthClaims` plus a separate `ConnectedAuth`. During connected
mount, `resume` loads the session record again from the public claim ID and
rebuilds `ConnectedAuth`. That capability contains immutable `CurrentUser`
construction data and an environment-free revalidation effect that captures the
same `AuthService` instance. Revocation or expiry between those phases therefore
prevents the socket from mounting. The public session ID remains in the signed
claim instead of becoming part of every route's context.

Install the aspect once as the named live session's admission boundary and
inject the small context into only the LiveViews that use it:

```scala
val protectedRoutes = Live
  .session("authenticated")
  .withAdmission(AuthMountAspect.authenticated)(_.publicSessionId)(
    profile.context((auth: ConnectedAuth, accounts: Accounts) =>
      new ProfileLiveView(auth.currentUser, accounts)
    ),
    status(StatusLiveView())
  )
```

`ProfileLiveView` projects immutable `CurrentUser` construction data from the
admitted capability while `Accounts` comes from the application environment.
The status view remains authentication-agnostic even though the same admission
protects it. Route-specific authorization and sensitive mutations must still
enforce their own domain rules.

Use a claimless @:apiSymbol(class:scalive.LiveRouteMountAspect)`LiveRouteMountAspect`@:@
after admission when access depends on destination parameters. It is the mount
boundary for loading a workspace membership or hiding an inaccessible record;
it is not a substitute for authorization immediately before a sensitive domain
operation. A live patch keeps the current lifecycle mounted and does not rerun
the route aspect, so reauthorize any patched resource identity in `handleParams`
or navigate instead. See [Derive fresh context for every route mount](layouts-sessions-and-mount-aspects.md#derive-route-context)
for the complete timing and failure semantics.

## Revalidate Connected Turns {#revalidate-connected-turns}

Mount admission decides whether a lifecycle may start. To recheck a session
before later connected application work, declare
@:apiSymbol(def:scalive.LiveSessionBuilder.Admitted.guardConnectedTurns)`guardConnectedTurns`@:@
after admission. The executable lab installs the guard and projects only the
immutable user into its view:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-protected-session)

Here `ConnectedAuth.revalidate` returns
`IO[LiveConnectedTurnFailure, Unit]`: `ZIO.unit` means **Continue**, while
`ZIO.fail(LiveConnectedTurnFailure.redirect(...))` stops the turn and redirects
to login. Other controlled failures may **Halt**, perform a trusted-destination
unsafe **Redirect**, **Reload**, or **Disconnect**. Keep authoritative mutable
application session or account state behind the admission-produced context
rather than relying on claims captured at mount. See
@:apiSymbol(enum:scalive.LiveConnectedTurnFailure)`LiveConnectedTurnFailure`@:@
for every outcome.

A guard runs when an application turn arrives, not while a physical connection
is idle. Continue to call `LiveConnections.disconnect` when revocation should
promptly close existing connections; the next admission then decides whether
they may reconnect. A turn guard narrows the revocation window, but it does not
replace domain authorization immediately before sensitive mutations. Recheck
the relevant record or capability where it is used to avoid time-of-check/time-of-use gaps.

Guard checks are on the hot path. Read authoritative mutable data, but bound the
cost and use caching only when its staleness is acceptable for the policy. A
failure reason supplied to `reload(reason)` or `disconnect(reason)` is
server-side diagnostic context, not a message sent to the browser.

Do not transfer the cookie token in claims. Signed claims are authenticated but
not encrypted. Read [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#treat-mount-phases-independently)
for the phase boundary in detail.

## Revoke Through A CSRF-Protected Reset {#revoke-through-a-csrf-protected-reset}

Logout changes server state and must not be a `GET`. The lab uses another
CSRF-protected ordinary form. Its handlers are extracted from the running
application:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-http-actions)

Reset is idempotent: a missing, stale, or already-revoked application session
still clears the browser cookie. When invalidation succeeds, it returns to the
login page. It affects only the opaque application session and attempt record
associated with that visitor.
The handler first revokes the durable session record and then calls
`LiveConnections.disconnect(publicSessionId)`. Reversing this order could let a
reconnecting socket resume the session before revocation becomes authoritative.

`disconnect` promptly closes every local tab registered to that application
session ID. The browser reconnects and reruns connected admission, which rejects
the now-revoked claim. A stale signed bootstrap token therefore cannot restore
the session.

Connected resources remain per-LiveView even when those tabs share one
application-session ID. Closing every matching physical connection finalizes each
lifecycle independently; a resource shared by the logical session instead
belongs in a service with explicit leases or reference counting. See
[Asynchronous work, subscriptions, and connected resources](async-work-and-subscriptions.md#connected-resources).

## Provide One Shared Authentication Service {#provide-one-shared-authentication-service}

The HTTP handlers and protected Live routes must use the same `AuthService`
instance. The admitted session also makes `LiveConnections[PublicSessionId]` a
visible application environment requirement. For one backend instance, provide
both layers once at `Server.serve`:

```scala
Server.serve(routes).provide(
  Server.default,
  AuthService.live,
  LiveConnections.local[PublicSessionId]
)
```

The same `.context` API can infer one application service from a typed
constructor without an explicit service type argument:

```scala
final class ProfileLiveView(currentUser: CurrentUser, accounts: Accounts)

val profile = routes.profile.context((auth: ConnectedAuth, accounts: Accounts) =>
  new ProfileLiveView(auth.currentUser, accounts)
)
```

Only `ConnectedAuth` is supplied by admission. The view projects `CurrentUser`
from it; `Accounts` is resolved from the ZIO environment independently and is
never serialized into mount claims or passed to layouts.

Keep the service responsible for credentials, session authenticity, expiry,
revocation, capacity, and rate limits. Keep the session mount aspect responsible
for translating that service decision into typed mount context or a redirect. The
protected LiveView then receives `CurrentUser` without reading cookies or
repeating authorization logic.

## Fan Out Disconnects Across Nodes {#fan-out-disconnects-across-nodes}

Multiple backend instances need an application-supplied
`LiveDisconnectBus[PublicSessionId]` adapter whose subscription broadcasts every
ID to every node. Scalive provides the interface and distributed connection
layer, not a Redis, NATS, or other transport adapter. Compose your adapter layer
like this:

```scala
def liveConnections(
  bus: ZLayer[Any, Throwable, LiveDisconnectBus[PublicSessionId]]
): ZLayer[Any, Throwable, LiveConnections[PublicSessionId]] =
  bus >>> LiveConnections.distributed[PublicSessionId]
```

Received events signal local transports without being republished. Duplicate or
out-of-order delivery is harmless. Publication failure is reported after local
connections have been signaled. The lab expires the browser cookie and returns
an error in that case; production logout code should retry from retained server
state or use an outbox.

The bus accelerates invalidation; it is not authentication truth. During a
network partition, an already-connected remote node may receive the notification
late. Shared durable revocation still rejects new mounts. Stricter guarantees
require an outbox or replay, leases, periodic revalidation, or authorization on
each sensitive operation.

## Production Checklist {#production-checklist}

Before adapting this lab for real users:

- set secure cookies whenever the public origin uses HTTPS;
- load the signing secret through validated production `ZioHttpConfig` and
  configure its non-empty exact WebSocket origin allowlist;
- use a real password verifier and avoid logging submitted credentials;
- choose distributed session and rate-limit storage for multiple instances;
- define absolute expiry, renewal, revocation, and account-disable behavior;
- configure all nodes with compatible route/session identities and signing keys;
- choose a broadcast fanout adapter plus retry or outbox policy;
- preserve generic login failures and bounded request bodies; and
- test login, protected HTTP render, connected resumption, multi-tab logout,
  turn revalidation, reconnect denial, expiry, throttling, and visitor isolation.

The lab is teaching code, not a production identity system.

## Related Tasks {#related-tasks}

- Review phase-safe claims in [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#treat-mount-phases-independently).
- Choose admission or a plain session aspect in [Bind admission to physical connections](#bind-admission-to-physical-connections).
- Apply the complete turn boundary from [Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards).
- Wire the shared session service with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
- Diagnose rejected joins and forms in [Troubleshooting](troubleshooting.md#diagnose-csrf-rejections).
