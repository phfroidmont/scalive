{%
title = "Authentication and sessions"
description = "Combine ordinary HTTP login and logout with opaque sessions and protected LiveView mounts."
order = 24
section = guides
group = "Routing and application structure"
%}

## Before you start {#prerequisites}

Start with an [ordinary HTTP route](http-forms-and-redirects.md) that can validate
a form, set a cookie, and redirect, plus a
[named live session](live-sessions-and-mount-aspects.md#group-routes-in-a-named-session)
that can run a mount aspect. The HTTP and Live routes must also be able to receive one shared
authentication service;
[service layers](services-and-zlayer-injection.md#provide-services-at-startup)
are one way to provide that capability.

## Separate HTTP login from Live authorization {#separate-http-login-from-live-authorization}

Use ordinary HTTP to submit credentials, change cookies, and redirect. Use a
session mount aspect to authorize each LiveView mount. Passwords then never
travel through WebSocket events, and the browser applies its normal cookie and
redirect rules. Cookies and original HTTP headers belong in ordinary HTTP
handlers or session mount aspects, not in route mount aspects.

The documentation authentication lab demonstrates the complete flow. The rest
of this guide follows it step by step:

1. `GET /examples/authentication/lab` renders a CSRF-protected login form.
2. **Sign in** submits `POST /examples/authentication/lab/session`, which
   validates the CSRF token, bounded form body, and fixed lab credentials.
3. A successful response sets an opaque `HttpOnly`, `SameSite=Lax` session cookie
   and redirects to the protected route.
4. The protected route authenticates that cookie during disconnected mount.
5. Connected mount receives only a public session ID in signed claims and checks
   the server-side session record again.
6. **Sign out and reset lab** submits `POST /examples/authentication/lab/reset`.
   Logout revokes only the current visitor's application session, closes every
   open tab that uses it, clears that visitor's login attempts, expires the
   cookie, and redirects to the login page.

@:lab(authentication)

The lab uses fixed credentials and stores no account changes. Its route is
intentionally excluded from the public content index because it is a standalone
HTTP and LiveView lab rather than a documentation page.

## Log in over ordinary HTTP {#keep-credentials-in-ordinary-http}

Render the login page with @:apiSymbol(def:scalive.Form.http)`Form.http`@:@ so
the browser posts credentials as an ordinary form. In the ZIO HTTP handler,
decode the body with
@:apiSymbol(def:scalive.HttpFormDecoder.urlEncodedValue)`HttpFormDecoder.urlEncodedValue`@:@:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-login-decoder)

The decoder rejects bodies over `FormMaxBytes` (4,096 bytes), validates the
framework CSRF token, and then decodes `LoginForm.Definition`. It succeeds only
with valid `LoginCredentials`, so the handler can pass them straight to the
authentication service:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-login-action)

A successful login sets the session cookie, described in the
[next section](#store-opaque-bounded-sessions), and redirects to the profile
page. Invalid form input and wrong credentials both redirect back to the login
page with the same flash message, so the response does not reveal whether an
account exists. Protocol failures, such as an oversized body, an unsupported
content type, malformed encoding, or a CSRF rejection, keep their ordinary HTTP
status codes. `logMalformedLogin` logs only the stable error code of body and
representation failures, never the submitted values.

The `urlEncoded(LoginForm.Definition, ...)` variant instead returns a submitted
`LoginForm.Definition.Form` even when its `result` contains validation errors.
Use it when the HTTP response renders the submitted values and feedback again.
Both definition-backed variants run the same body and CSRF checks first; see
[Decode a bounded POST body](http-forms-and-redirects.md#decode-a-bounded-post-body).

The lab limits each browser-bound visitor record to five failed attempts per
minute. Attempt records and sessions both have configured capacity bounds. A
production application should choose a policy suitable for its threat model and
deployment topology rather than copying these demonstration values.

## Store opaque, bounded sessions {#store-opaque-bounded-sessions}

On a successful login, the service creates a session and the handler stores its
token in a cookie. Keep that token opaque:

- generate a cryptographically random cookie token;
- store only its hash as the server-side lookup key; and
- keep a separate, non-secret public session ID for signed claims.

`security.cookies.make` sets `HttpOnly`, `SameSite=Lax`, and path `/`, and sets
`Secure` when `ZioHttpConfig.secureCookie` is enabled.

The lab sessions:

- expire after 30 minutes;
- are capped at 1,024 records;
- evict the oldest record when full; and
- are revoked explicitly at logout.

The in-memory store is process-local and suitable only for this single-instance
lab. A production service normally persists sessions in a bounded shared store
when multiple application instances must accept the same cookie.

## Authenticate both mount phases {#authenticate-both-mount-phases}

To protect live pages, check the session in both mounts. A LiveView mounts once
for the HTTP response and again when the browser connects
([Treat each mount as independent](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)),
and only the first mount sees the cookie. The protected named live session
installs this
@:apiSymbol(class:scalive.LiveSessionMountAspect)`LiveSessionMountAspect`@:@:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-mount-aspect)

During disconnected mount, `authenticate` validates the opaque cookie and
returns minimal `AuthClaims` plus a separate `ConnectedAuth`. A missing or
unknown cookie redirects to the login page. During connected mount, `resume`
loads the session record again from the public session ID in the claims and
rebuilds `ConnectedAuth`. A session revoked or expired between the two mounts
therefore cannot complete the connected mount; the connected callback redirects to login
instead.

`ConnectedAuth` holds immutable `CurrentUser` construction data and a
`revalidate` effect. That effect needs no environment because it captures the
same `AuthService` instance. [Revalidate connected turns](#revalidate-connected-turns)
uses it.

Install the aspect once on the named live session, and pass its context only
to the LiveViews that use it:

@:sourceRegion(documentation/fixtures/guides/src/auth/AuthApp.scala, auth-protected-routes)

`withAdmission(aspect)(_.publicSessionId)` installs the aspect and records which
public session ID each connection belongs to, so logout can close open tabs.
[Bind admission to physical connections](#bind-admission-to-physical-connections)
explains that contract. The public session ID stays in the signed claims instead
of becoming part of every route's context.

`ProfileLiveView` projects `CurrentUser` from the admitted `ConnectedAuth`. Its
`Accounts` parameter comes from the application environment; see
[Provide one shared authentication service](#provide-one-shared-authentication-service).
The status view is authentication-agnostic even though the same admission
protects it.

Signed claims are authenticated but not encrypted. Never put the cookie token in
them. Read [Treat mount phases independently](live-sessions-and-mount-aspects.md#treat-mount-phases-independently)
for the phase boundary in detail.

Mount checks decide only whether a lifecycle may start. Route-specific
authorization and sensitive mutations must still enforce their own domain rules.
When access depends on destination parameters, add a claimless
@:apiSymbol(class:scalive.LiveRouteMountAspect)`LiveRouteMountAspect`@:@ after
admission, for example to load a workspace membership or hide an inaccessible
record. A route aspect is still a mount check, not a substitute for
authorization immediately before a sensitive domain operation. A live patch
keeps the current lifecycle mounted and does not rerun the route aspect, so
reauthorize any patched resource identity in `handleParams` or navigate instead.
See [Derive fresh context for every route mount](live-sessions-and-mount-aspects.md#derive-route-context)
for the complete timing and failure semantics.

## Revalidate connected turns {#revalidate-connected-turns}

The mount aspect runs only when a lifecycle starts. To recheck the session
before later connected work, such as events and server messages, declare
@:apiSymbol(def:scalive.LiveSessionBuilder.Admitted.guardConnectedTurns)`guardConnectedTurns`@:@
after admission. The lab installs the guard and projects only the immutable
user into its view:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-protected-session)

`ConnectedAuth.revalidate` returns `IO[LiveConnectedTurnFailure, Unit]`.
`ZIO.unit` means **Continue**, while
`ZIO.fail(LiveConnectedTurnFailure.redirect(...))` stops the turn and redirects
to login. Other controlled failures may **Halt**, perform a trusted-destination
unsafe **Redirect**, **Reload**, or **Disconnect**. See
@:apiSymbol(enum:scalive.LiveConnectedTurnFailure)`LiveConnectedTurnFailure`@:@
for every outcome. Keep authoritative mutable application session or account
state behind the admission-produced context rather than relying on claims
captured at mount.

A guard runs when an application turn arrives, not while a physical connection
is idle. Logout should still call `LiveConnections.disconnect` to close existing
connections promptly, as the [next section](#revoke-through-a-csrf-protected-reset)
shows; the next admission then decides whether they may reconnect. A turn
guard narrows the revocation window, but it does not replace domain
authorization immediately before sensitive mutations. Recheck the relevant
record or capability where it is used to avoid time-of-check/time-of-use gaps.

Guard checks are on the hot path. Read authoritative mutable data, but bound the
cost, and cache only when the staleness is acceptable for the policy. A failure
reason supplied to `reload(reason)` or `disconnect(reason)` is server-side
diagnostic context, not a message sent to the browser.

## Log out through a CSRF-protected POST {#revoke-through-a-csrf-protected-reset}

Logout changes server state, so it must be a CSRF-protected `POST`, never a
`GET`. The lab's **Sign out and reset lab** button submits another ordinary
form. Its handler is named `reset` because it also clears the visitor's login
attempts:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-logout-action)

`resetDecoder` is `HttpFormDecoder.urlEncoded(FormCodec.formData, FormMaxBytes, security.csrf)`.
It checks the body bound and the CSRF token but decodes no fields. A rejected
request leaves the cookie in place.

The handler first revokes the server-side session record and then calls
`LiveConnections.disconnect(publicSessionId)`. Keep this order: reversing it
could let a reconnecting client resume the session before revocation takes
effect.

`disconnect` promptly closes every tab connected to this node with that
application session ID. Each browser reconnects and reruns connected admission,
which rejects the now-revoked claim. A stale signed bootstrap token therefore
cannot restore the session.

Logout is idempotent: a missing, stale, or already-revoked application session
still clears the browser cookie and redirects to the login page. It affects
only the opaque application session and attempt record of that visitor.
`disconnect` fails only when a [multi-node fanout](#fan-out-disconnects-across-nodes)
cannot publish. The lab then logs the error, still expires the cookie, and
returns `500 Internal Server Error`.

Connected resources remain per-LiveView even when several tabs share one
application session ID. Closing every matching physical connection finalizes
each lifecycle independently; a resource shared by the logical session instead
belongs in a service with explicit leases or reference counting. See
[Connected resources](connected-resources.md#connected-resources).

## Provide one shared authentication service {#provide-one-shared-authentication-service}

The HTTP login and logout handlers and the protected Live routes must use the
same `AuthService` instance; otherwise the mount aspect cannot find sessions
created at login. The admitted session also makes
`LiveConnections[PublicSessionId]` a visible application environment
requirement. For one backend instance, provide each layer once at
`Server.serve`:

@:sourceRegion(documentation/fixtures/guides/src/auth/AuthApp.scala, auth-provide-local)

Here `routes` combines `ZioHttp.routes(application, config)` with the login and
logout routes, as the lab does with `AuthHttpRoutes(security).routes`.

`Accounts.live` provides the second parameter of the
[profile route's factory](#authenticate-both-mount-phases),
`(auth: ConnectedAuth, accounts: Accounts) => ...`. The `.context` API infers
that one application service from the typed factory without an explicit
service type argument. Only `ConnectedAuth` is supplied by admission. `Accounts`
is resolved from the ZIO environment independently and is never serialized into
mount claims or passed to layouts.

Keep the service responsible for credentials, session authenticity, expiry,
revocation, capacity, and rate limits. Keep the session mount aspect responsible
for translating that service decision into typed mount context or a redirect.
The protected LiveView then receives `CurrentUser` without reading cookies or
repeating session checks.

This completes the login-to-logout flow. The remaining sections explain the
admission contract behind logout disconnects, deployments with several backend
instances, and what to change before production.

## Bind admission to physical connections {#bind-admission-to-physical-connections}

Read this section to choose between `withAdmission` and `withMountAspect`, or
to understand how logout closes every open tab.

@:apiSymbol(def:scalive.LiveSessionBuilder.withAdmission)`withAdmission`@:@
installs the named live session's one connection-admission boundary. Its second
argument, `connectionId`, extracts an application-owned ID from the aspect's
signed claims; the lab uses the public session ID. During connected mount,
Scalive registers the physical connection under that ID before running the
aspect's authoritative connected callback. A later
`LiveConnections.disconnect(id)` can therefore promptly close every registered
physical connection, including multiple tabs; each reconnect must pass
admission again. Because registration comes first, a logout that revokes and
then disconnects cannot miss a connection that is mounting at the same time:
either the callback sees the revoked session, or the disconnect finds the
registered connection.

Use plain
@:apiSymbol(def:scalive.LiveSessionBuilder.withMountAspect)`withMountAspect`@:@
when typed mount context is sufficient and the application does not need
connection lookup, prompt invalidation, or distributed disconnect fanout by that
claim-derived ID. Admission complements revoking the server-side session record;
@:apiSymbol(class:scalive.LiveConnections)`LiveConnections`@:@ is not
authentication truth. See the
[canonical combined pipeline](live-sessions-and-mount-aspects.md#combine-session-and-route-context)
for the generic and accumulated context shapes.

## Fan out disconnects across nodes {#fan-out-disconnects-across-nodes}

With multiple backend instances, a logout handled by one node must also close
tabs connected to the others. Supply a
@:apiSymbol(trait:scalive.LiveDisconnectBus)`LiveDisconnectBus[PublicSessionId]`@:@
adapter whose subscription broadcasts every ID to every node, and build the
connection layer from it:

@:sourceRegion(documentation/fixtures/guides/src/auth/AuthApp.scala, auth-provide-distributed)

Provide `liveConnections(bus)` in place of `LiveConnections.local[PublicSessionId]`.
Scalive provides the interface and the distributed connection layer, not a
Redis, NATS, or other transport adapter. A subscription must be ready to receive
notifications when `subscribe` returns. Competing-consumer delivery, where only
one node receives each ID, is not sufficient.

Received events signal local transports without being republished. Duplicate or
out-of-order delivery is harmless. Publication failure is reported after local
connections have been signaled. The lab's logout handler only expires the cookie
and returns an error in that case; production logout code should retry from
retained server state or use an outbox.

The bus accelerates invalidation; it is not authentication truth. During a
network partition, an already-connected remote node may receive the notification
late. Revocation in the shared session store still rejects new mounts. Stricter
guarantees require an outbox or replay, leases, periodic revalidation, or
authorization on each sensitive operation.

## Production checklist {#production-checklist}

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

## Related tasks {#related-tasks}

- Review phase-safe claims in [Live sessions and mount aspects](live-sessions-and-mount-aspects.md#treat-mount-phases-independently).
- Choose admission or a plain session aspect in [Bind admission to physical connections](#bind-admission-to-physical-connections).
- Apply the complete turn boundary from [Lifecycle hooks](lifecycle-hooks.md#connected-turn-guards).
- Wire the shared session service with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
- Diagnose rejected joins and forms in [Troubleshooting](troubleshooting.md#diagnose-csrf-rejections).
