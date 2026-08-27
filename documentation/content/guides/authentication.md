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
[named Live session](layouts-sessions-and-mount-aspects.md) that can run a mount
aspect. The HTTP and Live routes must also be able to receive one shared
authentication service;
[service layers](services-and-zlayer-injection.md#provide-services-at-startup)
are one way to provide that capability.

## Separate HTTP Login From Live Authorization {#separate-http-login-from-live-authorization}

Use ordinary HTTP for credential submission, cookie changes, and redirects.
Use a mount aspect to authorize the disconnected and connected LiveView
lifecycles. This division keeps password handling out of socket events and lets
the browser apply normal cookie and redirect semantics.

The documentation authentication lab demonstrates this complete flow:

1. `GET /examples/authentication/lab` renders a CSRF-protected login form.
2. `POST /examples/authentication/lab/session` validates the CSRF token, bounded
   form body, and fixed lab credentials.
3. A successful response sets an opaque `HttpOnly`, `SameSite=Lax` session cookie
   and redirects to the protected route.
4. The protected route authenticates that cookie during disconnected mount.
5. Connected mount receives only a public session ID in signed claims and checks
   the server-side session record again.
6. Reset revokes only the current visitor's session, disconnects every active tab
   using that session, clears that visitor's login attempts, expires the cookie,
   and redirects to the login page.

@:lab(authentication)

The lab uses fixed credentials and stores no account changes. Its route is
intentionally excluded from the public content index because it is a standalone
HTTP and LiveView lab rather than a documentation page.

## Keep Credentials In Ordinary HTTP {#keep-credentials-in-ordinary-http}

Render a normal form with @:apiSymbol(def:scalive.Form.http)`Form.http`@:@ and
decode it in a ZIO HTTP handler with
@:apiSymbol(def:scalive.HttpFormDecoder.urlEncoded)`HttpFormDecoder.urlEncoded`@:@.
The decoder applies a byte limit, validates the framework CSRF token, and then
decodes the rooted `FormCodec`:

```scala
private val loginDecoder =
  HttpFormDecoder.urlEncoded(LoginForm.Definition.codec, 4096L, security.csrf)
```

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

## Authenticate Both Mount Phases {#authenticate-both-mount-phases}

The protected session installs this executable mount aspect:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-mount-aspect)

During disconnected mount, `authenticate` validates the opaque cookie and
returns minimal `AuthClaims` plus a separate `CurrentUser`. During connected
mount, `resume` loads the session record again from the public claim ID and
rebuilds `CurrentUser`. Revocation or expiry between those phases therefore
prevents the socket from mounting. The public session ID remains in the signed
claim instead of becoming part of every route's context.

Install the aspect once as the named session's admission boundary and inject the
small context into only the LiveViews that use it:

```scala
val protectedRoutes = Live
  .session("authenticated")
  .withAdmission(AuthMountAspect.authenticated)(_.publicSessionId)(
    profile.context((currentUser: CurrentUser, accounts: Accounts) =>
      new ProfileLiveView(currentUser, accounts)
    ),
    status(StatusLiveView())
  )
```

`ProfileLiveView` receives immutable `CurrentUser` construction data while
`Accounts` comes from the application environment. The status view remains
authentication-agnostic even though the same admission protects it. Route-specific
authorization and sensitive mutations must still enforce their own domain rules.

Do not transfer the cookie token in claims. Signed claims are authenticated but
not encrypted. Read [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#treat-mount-phases-independently)
for the phase boundary in detail.

## Revoke Through A CSRF-Protected Reset {#revoke-through-a-csrf-protected-reset}

Logout changes server state and must not be a `GET`. The lab uses another
CSRF-protected ordinary form. Its handlers are extracted from the running
application:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-http-actions)

Reset is idempotent: a missing, stale, or already-revoked session still clears
the browser cookie. When invalidation succeeds, it returns to the login page. It
affects only the opaque session and attempt record associated with that visitor.
The handler first revokes the durable session record and then calls
`LiveConnections.disconnect(publicSessionId)`. Reversing this order could let a
reconnecting socket resume the session before revocation becomes authoritative.

`disconnect` promptly closes every local tab registered to that application
session. The browser reconnects and reruns connected admission, which rejects
the now-revoked claim. A stale signed bootstrap token therefore cannot restore
the session.

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

val profile = routes.profile.context((currentUser: CurrentUser, accounts: Accounts) =>
  new ProfileLiveView(currentUser, accounts)
)
```

Only `CurrentUser` is supplied by admission. `Accounts` is resolved from the ZIO
environment independently and is never serialized into mount claims or passed to
layouts.

Keep the service responsible for credentials, session authenticity, expiry,
revocation, capacity, and rate limits. Keep the mount aspect responsible for
translating that service decision into typed mount context or a redirect. The
protected LiveView then receives `CurrentUser` without reading cookies or
repeating authorization logic.

## Fan Out Disconnects Across Nodes {#fan-out-disconnects-across-nodes}

Multiple backend instances need a `LiveDisconnectBus[PublicSessionId]` adapter
whose subscription broadcasts every ID to every node. Wire the adapter into the
distributed layer:

```scala
val liveConnections =
  RedisDisconnectBus.layer[PublicSessionId] >>>
    LiveConnections.distributed[PublicSessionId]
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
- load the signing secret through validated production `ZioHttpConfig`;
- use a real password verifier and avoid logging submitted credentials;
- choose distributed session and rate-limit storage for multiple instances;
- define absolute expiry, renewal, revocation, and account-disable behavior;
- configure all nodes with compatible route/session identities and signing keys;
- choose a broadcast fanout adapter plus retry or outbox policy;
- preserve generic login failures and bounded request bodies; and
- test login, protected HTTP render, connected resumption, multi-tab logout,
  reconnect denial, expiry, throttling, and visitor isolation.

The lab is teaching code, not a production identity system.

## Related Tasks {#related-tasks}

- Review phase-safe claims in [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#treat-mount-phases-independently).
- Wire the shared session service with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
- Diagnose rejected joins and forms in [Troubleshooting](troubleshooting.md#diagnose-csrf-rejections).
