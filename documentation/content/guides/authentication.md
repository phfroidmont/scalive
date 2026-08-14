{%
title = "Authentication and sessions"
description = "Combine ordinary HTTP login and logout with opaque sessions and protected LiveView mounts."
order = 22
section = guides
group = "Routing and application structure"
%}

## Prerequisites {#prerequisites}

This guide builds on [Ordinary HTTP forms and redirects](http-forms-and-redirects.md),
[named live sessions and mount aspects](layouts-sessions-and-mount-aspects.md),
and [service layers provided at startup](services-and-zlayer-injection.md#provide-services-at-startup).

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
6. Reset revokes only the current visitor's session, clears that visitor's login
   attempts, expires the cookie, and redirects to the login page.

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
returns minimal `AuthClaims` plus `CurrentSession`. During connected mount,
`resume` loads `CurrentSession` again from the public claim ID. Revocation or
expiry between those phases therefore prevents the socket from mounting.

Do not transfer the cookie token in claims. Signed claims are authenticated but
not encrypted. Read [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#treat-mount-phases-independently)
for the phase boundary in detail.

## Revoke Through A CSRF-Protected Reset {#revoke-through-a-csrf-protected-reset}

Logout changes server state and must not be a `GET`. The lab uses another
CSRF-protected ordinary form. Its handlers are extracted from the running
application:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-http-actions)

Reset is idempotent: a missing, stale, or already-revoked session still clears
the browser cookie and returns to the login page. It affects only the opaque
session and attempt record associated with that visitor.

## Provide One Shared Authentication Service {#provide-one-shared-authentication-service}

The HTTP handlers and protected Live routes must use the same `AuthService`
instance. In a normal application, combine both route sets and provide the layer
once at `Server.serve`, as described in [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).

Keep the service responsible for credentials, session authenticity, expiry,
revocation, capacity, and rate limits. Keep the mount aspect responsible for
translating that service decision into typed mount context or a redirect. The
protected LiveView then receives `CurrentSession` without reading cookies or
repeating authorization logic.

## Production Checklist {#production-checklist}

Before adapting this lab for real users:

- set secure cookies whenever the public origin uses HTTPS;
- load token secrets from validated production configuration;
- use a real password verifier and avoid logging submitted credentials;
- choose distributed session and rate-limit storage for multiple instances;
- define absolute expiry, renewal, revocation, and account-disable behavior;
- preserve generic login failures and bounded request bodies; and
- test login, protected HTTP render, connected resumption, logout, expiry,
  throttling, and visitor isolation.

The lab is teaching code, not a production identity system.

## Related Tasks {#related-tasks}

- Review phase-safe claims in [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#treat-mount-phases-independently).
- Wire the shared session service with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
- Diagnose rejected joins and forms in [Troubleshooting](troubleshooting.md#diagnose-csrf-rejections).
