{%
title = "Configuration"
description = "Distinguish Scalive framework settings from application-owned environment and currently unsupported deployment options."
order = 71
section = guides
group = "Assets and operations"
%}

## Prerequisites {#prerequisites}

Start from an application that can render disconnected HTML, load its browser
bundle, and connect the Phoenix client as described in the
[quick start](../learn/quick-start.md#start-the-server). Decide its public HTTP
origin, socket path, static mount, cookie requirements, token lifetime, and
listen port before constructing routes.

## Current Configuration Contract {#current-configuration-contract}

Scalive is a library, not a process-wide endpoint configuration system. Only
`TokenConfig.default` reads environment variables in framework code. Router,
cookie, and asset settings are explicit Scala values. Ports, bind addresses,
public origins, TLS, and application services belong to the application or ZIO
HTTP server layer.

| Setting | Owner and API | Current default | Contract |
| --- | --- | --- | --- |
| Token signing secret | Framework: `TokenConfig.default` reads `SCALIVE_TOKEN_SECRET` | Random process-local UUID when missing or exactly empty | A non-empty value is used verbatim, including whitespace. Use one stable, high-entropy value on every production replica. |
| Token maximum age | Framework: `TokenConfig.default` reads `SCALIVE_TOKEN_MAX_AGE_SECONDS` | Seven days | Only a positive whole `Long` in seconds is accepted. Missing, empty, whitespace-padded, non-numeric, overflowing, zero, and negative values use the default. |
| Explicit token configuration | Framework API: `TokenConfig(secret, maxAge)` or `Live.router.withTokenConfig(config)` | No validation on construction | Use a stable secret and a positive finite Scala duration. `withTokenConfig` preserves the router's cookie policy. |
| Complete security policy | Framework API: `LiveSecurity(tokenConfig, CookiePolicy(secure = ...))`, installed with `Live.router.withSecurity` | `CookiePolicy(secure = false)` | Controls token, CSRF, flash, and cookie policy together. Scalive never infers `Secure` from TLS or proxy headers. |
| Framework cookie attributes | Framework API: `CookiePolicy` | Host-only, path `/`, `HttpOnly`, `SameSite=Lax`; `Secure` is `false` through default security | Domain, path, `HttpOnly`, and same-site are fixed by this API. The exposed choice is `secure`; use `true` for browser-facing HTTPS. |
| Live socket mount | Framework API: `Live.router.withSocketPath(PathCodec[Unit])` | `/live` | The Phoenix client uses the mount path and upgrades its `/websocket` child; default upgrade path is `/live/websocket`. |
| Root and Live layouts | Framework API: `Live.router.withRootLayout` and `.withLayout` | Identity root layout and no ordinary layouts | A real application root must provide complete document structure, including `<head>` for CSRF metadata and client assets. |
| Static source and paths | Framework API: `StaticAssetConfig.classpath` or `.directory` | Mount `/static`; `serveOriginals = true` | Classpath assets require an explicit list; directory assets may discover files. `StaticAssets.load` builds the manifest and fails for missing configured files. |
| Static cache policy | Framework API: `StaticAssetConfig.cache` / `StaticAssetCache` | Digested: public, immutable, 31,536,000 seconds; original: `no-cache` | Values are emitted as response headers. Built-in routes do not add conditional or range handling. |
| Server port and bind address | Application/ZIO HTTP | No Scalive default | Construct and provide the ZIO HTTP `Server` layer. Any environment variable is application-owned. |
| Public origin | Application | No Scalive setting | Validate and use it where the application generates absolute URLs. It does not alter request scheme, routes, or cookies. |
| Logging | Application and ZIO runtime | ZIO runtime behavior selected by the application | Scalive emits selected ZIO logs but has no complete public telemetry or access-log configuration API. |

`TokenConfig.default` is a singleton resolved when it is initialized. Changing
the process environment afterward does not reload it. Signed values are
authenticated but readable by clients, and changing either the secret or the
accepted age can invalidate values already issued.

Prefer constructing one `LiveSecurity` and passing the same value to
`Live.router.withSecurity` and sibling HTTP handlers that validate forms, issue
flash redirects, or create application cookies:

```scala
val tokenConfig = TokenConfig.default
val security = LiveSecurity(
  tokenConfig,
  CookiePolicy(secure = true)
)

val liveRoutes = Live.router
  .withSecurity(security)
  .withSocketPath(PathCodec.empty / "live")
  .withRootLayout(RootLayout(assets))(
    Routes.home -> HomeLiveView()
  )
```

The JavaScript path must agree with the Scala mount:

```js
const liveSocket = new LiveSocket("/live", Socket, { params })
```

## Application-Owned Environment {#application-owned-environment}

Environment names outside the two token variables are conventions of a
particular runnable application, not portable Scalive configuration.

### Documentation Application {#documentation-application}

The current `documentation.site` entry point accepts:

| Variable | Default | Validation and effect |
| --- | --- | --- |
| `SCALIVE_SERVER_PORT` | `8080` | Must trim and parse as an integer from 1 through 65535. It is passed to `Server.defaultWithPort`. |
| `SCALIVE_PUBLIC_ORIGIN` | `http://localhost:<server-port>` | Must be an `http` or `https` origin with a host and without user information, path other than `/`, query, or fragment. A trailing `/` is removed. It is used for absolute documentation metadata URLs. |
| `SCALIVE_DOCS_REVISION` | Current full Git revision | Build-time input to documentation generation. It avoids the generator's `git rev-parse HEAD` lookup; it is not runtime server configuration. |

The documentation site also uses the framework-owned
`SCALIVE_TOKEN_SECRET` and `SCALIVE_TOKEN_MAX_AGE_SECONDS` through
`TokenConfig.default`. It hard-codes `CookiePolicy(secure = false)`; neither
`SCALIVE_PUBLIC_ORIGIN` nor a forwarded scheme changes that setting.

### Other Repository Applications {#other-repository-applications}

The quick-start fixture has no port environment variable and listens on `8080`.
The example catalog defines its own `SCALIVE_SERVER_PORT` parser: a parseable
integer is used and any missing or unparsable value falls back to `8080`; unlike
the documentation application, it does not enforce the 1 through 65535 range at
its parsing boundary. The catalog also owns `SCALIVE_SECURE_COOKIES`, accepting
only case-insensitive `true` or `false` and failing startup for any other set
value. These are examples of application policy, not framework guarantees.

For a new application, define names and strict validation appropriate to its
deployment rather than depending on an unrelated module's convention. Keep
secret values separate from non-secret endpoint configuration.

## Unsupported Or Not Yet Supplied {#unsupported-or-not-yet-supplied}

There is currently no Scalive configuration option for:

- TLS certificates, TLS termination, listen host, or trusted proxy ranges;
- interpreting `Forwarded`, `X-Forwarded-Proto`, `X-Forwarded-Host`, or similar
  headers;
- an automatic external URL prefix or automatic public-origin discovery;
- WebSocket timeouts, long-poll fallback, Phoenix endpoint hibernation, or
  transport selection;
- liveness or readiness endpoints;
- graceful connection draining, shutdown grace periods, or socket migration;
- cluster membership, distributed PubSub, a shared session store, or
  cross-replica LiveView state;
- complete metrics, tracing, telemetry events, or an access-log policy; or
- runtime environment reload of `TokenConfig.default`.

Configure supported ZIO HTTP concerns in the application's server layer and
edge, and implement application-specific routes and instrumentation as ordinary
application code. Do not treat proxy headers as a trusted secure-cookie signal:
select `CookiePolicy(secure = true)` from validated deployment configuration.

## Verify Configuration {#verify-configuration}

Validate production configuration before `StaticAssets.load`, router assembly,
and `Server.serve` so a bad instance fails startup rather than serving a partial
application.

Before promoting an instance, verify all of the following against its public
URL:

- startup fails for invalid required application configuration and missing
  declared assets;
- disconnected HTML contains the expected digested asset URLs and CSRF meta
  element;
- asset responses have the intended cache policy and unknown digests return
  `404`;
- the client reaches the configured `<socket-path>/websocket` through the edge
  with its query string and cookies intact;
- HTTPS responses set framework and authentication cookies with `Secure`;
- replicas share signing configuration and can accept a reconnect issued by
  another replica; and
- shutdown and restart produce the application's intended probe, reconnect,
  state-reload, and log behavior.

## Related Tasks {#related-tasks}

- Continue with [Deployment](deployment.md#put-an-http-edge-in-front) for the
  provider-neutral edge and scaling requirements.
- Use [Client setup and static assets](static-assets-and-client-setup.md) to
  align browser, socket, and asset paths.
- Use [Troubleshooting](troubleshooting.md#diagnose-socket-connections) when the
  HTTP render succeeds but the WebSocket does not join.
