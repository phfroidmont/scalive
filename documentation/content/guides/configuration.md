{%
title = "Configuration"
description = "Configure Scalive signing, token lifetime, cookies, WebSocket origins, routes, assets, lifecycle observation, and application-owned server settings."
order = 3
section = guides
group = "Setup and foundations"
%}

## Before you start {#prerequisites}

You need to know whether browsers will use HTTP or HTTPS and which WebSocket and
asset paths the application will expose. The [quick start](../learn/quick-start.md#start-the-server)
provides a complete baseline if you do not yet have an assembled application.

## Current configuration contract {#current-configuration-contract}

Scalive reads no environment variables and owns no process-wide endpoint
configuration. The application loads and validates its settings, then
constructs one @:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@ and
passes it to @:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@. This
loader fails when a setting is missing instead of falling back to a default:

@:sourceRegion(documentation/fixtures/guides/src/configuration/Configuration.scala, configuration-load)

`SCALIVE_TOKEN_SECRET` and `SCALIVE_ALLOWED_ORIGINS` are names this application
chose. A rejected setting is a
@:apiSymbol(enum:scalive.ZioHttpConfig.Error)`ZioHttpConfig.Error`@:@, an exception
with a readable message, so `ZIO.fromEither(ZioHttpConfig(...))` can fail a
`Task` directly.

For local development, the quick start uses
@:apiSymbol(def:scalive.ZioHttpConfig.local)`ZioHttpConfig.local(port)`@:@ instead.
It admits `http://localhost:port` and `http://127.0.0.1:port`, sends cookies
without `Secure`, signs with a random secret drawn at startup, and enables
[`reloadOnRestart`](#reload-pages-after-a-restart). Never deploy it.

The first four values are required:

| Setting | Meaning and validation | Production guidance |
| --- | --- | --- |
| `signingSecret` | Signs framework-issued LiveView and CSRF values. Construction rejects secrets shorter than 32 UTF-8 bytes. Values are authenticated, not encrypted. | Load a stable, high-entropy secret through the application's secret facility. Every replica must use the same value. Changing it invalidates outstanding signed values. |
| `sessionMaxAge` | Maximum accepted age for framework-issued LiveView and CSRF values. Construction rejects zero or negative durations. HTTP flash values have an independent 60-second lifetime. | Choose an explicit policy. This setting does not define authentication-session retention or make a browser cookie persistent. |
| `secureCookie` | Controls the `Secure` attribute on framework cookies and cookies created through `LiveSecurity.cookies`. Scalive does not infer it from TLS or proxy headers. | Use `true` whenever the browser-facing origin is HTTPS. Local plain HTTP normally requires `false`. |
| `allowedWebSocketOrigins` | Non-empty set of exact browser origins admitted for WebSocket upgrades. Construction rejects a set with no usable origin. | List every browser-facing page origin that may connect. See [Admit WebSocket origins](#admit-websocket-origins). |
| `reloadOnRestart` | Optional, `false` by default. When `true`, a page rendered by an earlier server process reloads when it reconnects. | Development only. See [Reload pages after a restart](#reload-pages-after-a-restart). |
| `pageCache` | Optional, @:apiSymbol(enum:scalive.PageCache)`PageCache.Revalidate`@:@ by default: `private, max-age=0, must-revalidate`, as Phoenix sends. `PageCache.NoStore` sends `no-store`. It sets `Cache-Control` on pages, error pages, redirects, and HTTP route responses without their own; Scalive's browser files keep their immutable headers. | Use `NoStore` when pages hold sensitive data. No setting lets a shared cache keep a page, since it carries the CSRF token and signed session. |

Construct the value once and reuse it. `ZioHttp.routes` also validates the
assembled Live route catalog: its effect fails with
@:apiSymbol(class:scalive.ZioHttp.AssemblyException)`ZioHttp.AssemblyException`@:@ for
duplicate live-session names, duplicate rendered paths, or a route that matches
one of Scalive's browser files, before the server starts.

## Reload pages after a restart {#reload-pages-after-a-restart}

A development loop that rebuilds the application on change, such as
`mill -w app.runBackground`, restarts the server for every change. Open pages
reconnect to the new process, but a reconnect only re-renders the LiveView: the
`<head>` keeps the previous stylesheets and scripts, and the root layout stays
as it was. With a stable signing secret, the old session is still valid, so
nothing reloads the page.

Set `reloadOnRestart` in the development configuration:

@:sourceRegion(documentation/fixtures/guides/src/configuration/Configuration.scala, configuration-reload-on-restart)

Each server process signs its identity into the pages it renders. When a page
from an earlier process joins, Scalive rejects the join and the Phoenix client
loads the page again, so changed assets, root layouts, and views all appear.
Pages rendered by the current process join normally.

Keep it `false` in deployments. Every deployment would reload every open page,
discarding form input that LiveView otherwise restores after a reconnect, and
with several replicas a page rendered by one would not join another. Use
[`staticChanged`](static-assets-and-client-setup.md#read-connect-metadata) to
react to changed assets in production instead.

## Admit WebSocket origins {#admit-websocket-origins}

Every WebSocket upgrade must carry exactly one valid `Origin` header that matches
an entry in `allowedWebSocketOrigins`. A missing, `null`, malformed, duplicate,
combined, or mismatched value receives HTTP 403 before the upgrade, with an empty
body and no reason-specific log entry. The browser sets the header from the
page's `http` or `https` origin, never the WebSocket's `ws` or `wss` URL, so list
page origins.

Scalive never derives origin trust from `Host`, `Forwarded`,
`X-Forwarded-Host`, `X-Forwarded-Proto`, or an external URL prefix. Do not use
those headers to decide whether cookies are secure either. A proxy must forward
the browser's `Origin` unchanged.

Use the throwing @:apiSymbol(def:scalive.WebSocketOrigin.http)`WebSocketOrigin.http`@:@ and
@:apiSymbol(def:scalive.WebSocketOrigin.https)`WebSocketOrigin.https`@:@ constructors for trusted
source literals, and pass a port when it isn't the default, as in
`WebSocketOrigin.https("example.com", 8443)`. Parse deployment input through
@:apiSymbol(def:scalive.WebSocketOrigin.parse)`WebSocketOrigin.parse`@:@ so an
invalid value stays in the startup error channel. The loader above parses a
comma-separated list and reports the failing entry by position rather than
logging the untrusted value:

@:sourceRegion(documentation/fixtures/guides/src/configuration/Configuration.scala, configuration-parse-origins)

When configuration supplies a trusted scheme separately from a dynamic host or
port, use @:apiSymbol(def:scalive.WebSocketOrigin.httpEither)`httpEither`@:@ or
@:apiSymbol(def:scalive.WebSocketOrigin.httpsEither)`httpsEither`@:@ instead.

The policy is a finite exact set. Wildcard hosts, suffix matching, request-derived
origins, and custom predicates are intentionally unsupported. Unicode host input
is rejected; configure the ASCII/punycode host serialized by the browser. Origins
compare by canonical scheme, host, and effective port, so scheme and host case and
an omitted default port do not create separate origins. The `http` and `https`
constructors also canonicalize equivalent IP literal spellings;
`WebSocketOrigin.parse` requires a browser-serialized origin.

## Share security with HTTP handlers {#share-security-with-http-handlers}

Ordinary HTTP handlers that need the same CSRF, flash, or cookie policy as Live
routes build a @:apiSymbol(class:scalive.LiveSecurity)`LiveSecurity`@:@ from the
same config:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginHttpRoutes.scala, http-forms-assembly)

Cookies produced by these helpers are host-only, root-scoped, `HttpOnly`, and
`SameSite=Lax`. Their `Secure` attribute comes from `secureCookie`. The current
API does not configure another cookie domain, path, same-site value, or
`HttpOnly` policy.

Do not place passwords, access tokens, cookie values, or other secrets inside
signed session claims or flash values: a browser can read signed content.

## Configure routes and assets {#configure-routes-and-assets}

The remaining framework choices are made while assembling the application:

| Concern | API and default | Requirement |
| --- | --- | --- |
| WebSocket path | `Live.router.withSocketPath`; default `/live` | The packaged client connects to it; a custom bundle must use the same mount. The WebSocket upgrade is the mount's `/websocket` child, `/live/websocket` by default. |
| Scalive browser files | `Live.router.withClientPath`; default `/_scalive` | Scalive serves its navigation-guard runtime below it with either client, and the packaged Phoenix clients too. No Live route may match one of these files. Keep application static mounts outside it: `ZioHttp.routes` cannot see them, so it cannot report a collision. |
| Root and Live layouts | `Live.router.withRootLayout` and `.withLayout`; `LiveRootLayout.default` and no ordinary layouts by default | A custom root renders a complete `<html>`, `<head>`, and `<body>` with the application assets. Scalive inserts its CSRF metadata and browser scripts into the `<head>`. |
| Phoenix clients | The `client` argument of `ZioHttp.routes`; default `LiveClient.Packaged`, served below the client path | Pass `LiveClient.Custom` when the application bundle imports both npm clients and connects its own `LiveSocket`. |
| Static assets | `StaticAssetConfig.classpath`, `.directory`, `.deploymentClasspath`, or `.deploymentDirectory`; default mount `/static` | Load assets before startup, add `assets.routes`, and render versioned or final URLs from the same loaded value. |

Use `classpath` or `directory` when Scalive should version one complete relative
output tree. Use `deploymentClasspath` or `deploymentDirectory` when an external
build owns exact final filenames and cache policy through a deployment manifest.

This router serves its WebSocket below `/socket`:

@:sourceRegion(documentation/fixtures/guides/src/configuration/Configuration.scala, configuration-socket-path)

The packaged client then opens the WebSocket at `/socket/websocket`, and a
reverse proxy must forward the upgrade on that path. A custom bundle passes the
same `/socket` to its `LiveSocket`.

An application served below a prefix declares its routes below it and moves the
WebSocket and Scalive's browser files there too, so the edge forwards one path:

@:sourceRegion(documentation/fixtures/guides/src/configuration/Configuration.scala, configuration-prefixed-paths)

Every page then loads Scalive's scripts below `/app/_scalive` and connects to
`/app/live/websocket`.

Use [Client setup and static assets](static-assets-and-client-setup.md) for asset
sources, versioned paths, deployment manifests, cache policy, and complete
browser wiring.

## Observe lifecycle operations {#observe-lifecycle-operations}

Pass one @:apiSymbol(trait:scalive.LifecycleObserver)`LifecycleObserver`@:@ as the
third argument of `ZioHttp.routes`. The built-in adapter records fixed,
low-cardinality ZIO metrics for disconnected renders, joins, mounts, connected
turns, failures, queue pressure, and lifecycle termination:

@:sourceRegion(documentation/fixtures/guides/src/configuration/Configuration.scala, configuration-metrics-observer)

Use [Lifecycle observability](lifecycle-observability.md#choose-the-observation-boundary)
for safe custom observers, the complete metric and label contract, exporter
verification, cardinality rules, and the boundary between lifecycle and endpoint
instrumentation.

## Configure the server separately {#configure-the-server-separately}

The application and ZIO HTTP own environment-variable names, bind address,
port, direct TLS, server request handling, idle timeouts, response compression,
and graceful-shutdown timeout. Configure those through ZIO HTTP's
`Server.Config` or the application's preferred configuration library; they are
not fields of `ZioHttpConfig`.

Public URL generation and trusted-proxy policy remain application concerns.
Validate a public URL when the application needs to generate absolute URLs.

Load and validate all application settings once at startup, before
`StaticAssets.load`, route assembly, and `Server.serve`. Missing secrets, invalid
ports, and absent configured assets should stop the process rather than leave a
partially configured instance serving traffic.

Scalive supplies no central setting for health routes, cluster membership,
shared LiveView state, or transport fallback. Add endpoint-level HTTP
instrumentation as application middleware and use `LifecycleObserver` for
LiveView operations. Scalive supports WebSocket transport only; deployment and
scaling consequences are covered in
[Deployment](deployment.md#put-an-http-edge-in-front).

## Related tasks {#related-tasks}

- Continue with [Deployment](deployment.md#build-and-run-the-current-application)
  to package and operate the application.
- Use [Client setup and static assets](static-assets-and-client-setup.md) to
  configure browser, WebSocket, and asset paths.
- Publish and interpret LiveView metrics with
  [Lifecycle observability](lifecycle-observability.md#publish-built-in-metrics).
- Use [Troubleshooting](troubleshooting.md#diagnose-websocket-connections) when the
  HTTP render succeeds but the WebSocket does not join.
