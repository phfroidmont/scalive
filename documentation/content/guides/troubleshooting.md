{%
title = "Troubleshooting"
description = "Diagnose Scalive startup, assets, sockets, CSRF failures, and reconnect behavior without hiding current limitations."
order = 61
section = guides
group = "Testing and troubleshooting"
%}

## Before You Start {#prerequisites}

Start with a reproducible URL whose initial HTTP status and HTML you can inspect,
plus server logs and the browser console and Network panel. Record whether the
failure appears before or after the LiveView socket connects.

## Separate The Two Mounts {#separate-the-two-mounts}

A @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ mounts once for the disconnected HTTP
response and again whenever its socket joins or rejoins. Start diagnosis by
deciding which phase failed:

1. Fetch the page with JavaScript disabled or inspect the document request. A
   non-200 response, missing document, or incorrect initial HTML is a
   disconnected startup or routing problem.
2. If the document is correct, inspect the browser console and Network panel.
   The Phoenix client should load and open a WebSocket below the configured
   socket path.
3. If the socket joins but an interaction fails, inspect the event binding,
   decoded message, handler failure, and returned diff rather than changing the
   HTTP route.

@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ must be repeatable. The disconnected model is not continued by the
connected socket, and a rejoin creates another connected model. Restrict
subscriptions and other connection-only effects to the
`Connection.Connected(capabilities)` branch of
@:apiSymbol(def:scalive.LifecycleContext.connection)`ctx.connection`@:@; persist
state outside the @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ when it must survive reconnects or process restarts.

## Diagnose Startup Failures {#diagnose-startup-failures}

Check the startup path in order:

- Build the browser bundle before starting the JVM.
- Load every declared asset with @:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@.
- Validate one @:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@ and pass
  it to @:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@.
- Attach a complete root layout containing `<html>`, `<head>`, and `<body>`.
- Combine the Live routes with @:apiSymbol(val:scalive.StaticAssets.routes)`assets.routes`@:@.
- Provide all ZIO environment requirements before calling `Server.serve`.

@:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@ fails startup when a configured classpath or directory asset
is absent. Route construction also validates invalid or duplicate Live route
configurations. Preserve those failures rather than replacing them with a
server that starts partially.

Compare startup values with the canonical
[configuration contract](configuration.md#current-configuration-contract).
Diagnostic deltas are usually a signing secret shorter than 32 UTF-8 bytes, a
non-positive session age, an empty WebSocket origin allowlist,
`secureCookie = true` on local HTTP, or different secrets across replicas.
Surface validation as startup failure; never hide it with a random process-local
fallback.

## Diagnose Missing Assets {#diagnose-missing-assets}

If the HTML renders but remains disconnected, first verify that the browser
client bundle loaded successfully. Compare the response with the canonical
[asset serving](static-assets-and-client-setup.md#serve-digested-paths) and
[tracked tag](static-assets-and-client-setup.md#render-tracked-tags) setup, then
request the rendered digested URL directly. The same
@:apiSymbol(object:scalive.StaticAssets)`StaticAssets`@:@ value must render the URL and serve its routes.

@:apiSymbol(def:scalive.StaticAssets.trackedScript)`trackedScript`@:@ and
@:apiSymbol(def:scalive.StaticAssets.trackedStylesheet)`trackedStylesheet`@:@ emit fingerprinted paths and
`phx-track-static`. By default, digested assets are cached as immutable for one
year, while configured original paths use `no-cache`. Do not hard-code a digest;
render it through the loaded manifest. If a reverse proxy adds a URL prefix,
make its routing agree with @:apiSymbol(val:scalive.StaticAssetConfig.mountPath)`StaticAssetConfig.mountPath`@:@ rather than rewriting
only the HTML.

Common asset failures are a missing Mill `resources` dependency on the bundle,
an output name omitted from `bundleOutputs`, a classpath prefix that does not
match the packaged resource, or forgetting @:apiSymbol(val:scalive.StaticAssets.routes)`assets.routes`@:@. Return to the
[quick-start asset wiring](../learn/quick-start.md#start-the-server) for a minimal
known shape.

## Diagnose Socket Connections {#diagnose-socket-connections}

The default browser configuration is `new LiveSocket("/live", Socket, ...)`.
The Phoenix client opens the WebSocket endpoint at `/live/websocket`. If the
router uses another mount, configure both sides:

```scala
import zio.http.codec.PathCodec

val liveRoutes = Live.router
  .withSocketPath(PathCodec.empty / "socket")(
    Routes.home -> HomeLiveView()
  )
```

```js
const liveSocket = new LiveSocket("/socket", Socket, { params })
```

A reverse proxy must forward the WebSocket upgrade on the resulting
`/socket/websocket` path. Check the actual request URL, upgrade response, proxy
timeouts, and browser console before debugging event handlers. The request must
carry exactly one valid `Origin` equal to a configured
`allowedWebSocketOrigins` entry. Browsers send the page's `http` or `https`
origin, not `ws` or `wss`; effective default ports normalize to `80` and `443`.

An HTTP 403 before upgrade commonly means the origin is missing, `null`,
malformed, duplicated, combined into one value, or mismatched. Configure exact
non-default ports with `WebSocketOrigin.http/https(host, port)`. Check the raw
header at the application boundary: Scalive never trusts `Host`, `Forwarded`,
`X-Forwarded-Host`, or `X-Forwarded-Proto` as a substitute for `Origin`.

Origin rejection currently has an empty response body and no reason-specific
Scalive log entry. Compare the browser Network panel's request `Origin` with the
effective `allowedWebSocketOrigins`, then inspect application access logs and edge
logs. Test the application endpoint directly when possible: a branded or non-empty
403 response, or a request absent from application access logs, usually indicates
that the proxy or edge rejected it first.

Scalive currently implements WebSocket transport. Phoenix Channels long-poll
fallback is not implemented, and endpoint options such as long-poll and
hibernation are not available. A deployment that blocks WebSockets therefore
cannot rely on transport fallback.

## Diagnose CSRF Rejections {#diagnose-csrf-rejections}

When `ZioHttp.routes` receives its validated config, the disconnected render injects a
`<meta name="csrf-token">` element into the root layout's `<head>` and issues the
matching `_scalive_csrf` cookie. First compare the page with the canonical
[CSRF and LiveSocket bootstrap](static-assets-and-client-setup.md#connect-live-socket),
then inspect the failing token/cookie pair rather than creating another token.

Check all parts of the pair:

- The root layout renders a real `<head>` so token injection has a target.
- The JavaScript reads the server-issued token rather than generating one.
- The browser stores and sends `_scalive_csrf` to the WebSocket endpoint.
- Cookie domain, path, `Secure`, and same-site behavior match the public origin.
- Every replica uses the same `SCALIVE_TOKEN_SECRET`.
- A proxy preserves the query string and cookie header on the upgrade request.

Missing, expired, transferred, or tampered token/cookie pairs are rejected. A
failure at this point can appear as a stale join rather than an HTTP form error.
Ordinary checked POST forms use the same protection and receive a hidden
`_csrf_token`; do not remove that generated field.

## Diagnose Reconnects And Crashes {#diagnose-reconnects-and-crashes}

On transport interruption, expect the Phoenix client to attempt reconnection and
the server to mount a fresh connected lifecycle. Verify that:

- @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ does not assume the disconnected model or an earlier socket still
  exists;
- connection-scoped streams and fibers are acquired through lifecycle
  capabilities so old resources can be released;
- durable user state is loaded from a shared service rather than only from the
  previous model;
- repeated mount side effects are idempotent or explicitly guarded;
- deployed replicas share the signing secret; and
- proxy idle timeouts are not repeatedly terminating healthy sockets.

The runtime emits a Phoenix-compatible `phx_error` when a joined root
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@
crashes, and native tests demonstrate that the topic can subsequently rejoin
with a new socket. Do not turn that result into a stronger guarantee: exact
protocol error payloads, stale cases, crash logging, and reconnect/remount parity
still need systematic upstream auditing. Write a browser test for the concrete
recovery behavior your application promises.

Tracked static assets and `ConnectedMetadata.staticChanged` are implemented.
`ConnectedMetadata.connectParams` exposes all browser join parameters as
untrusted JSON, while typed server-derived connect info remains partial. Decode
application-owned parameters when needed, but never base recovery on
Phoenix-owned protocol keys.

## Account For Current Limits {#account-for-current-limits}

Current operational and diagnostic limits include:

- no long-poll transport fallback;
- connected server-side testing does not cross the real browser DOM boundary;
- no complete telemetry or observability API, although selected runtime branches
  log warnings and errors;
- no reason-specific log entry for a rejected WebSocket Origin;
- untrusted JSON connect params, with only partial typed server-derived connect
  info;
- expanding rather than exhaustive protocol error and reconnect parity; and
- signed but not encrypted session claims and flash values.

These limits do not mean the corresponding core behavior is absent. They define
where Scalive does not yet promise a complete public API or audited upstream
matrix. Consult the [compatibility matrix](../project/compatibility.md#status-matrix) before relying
on an edge case, and use the boundary guidance in
[Testing LiveViews](testing.md#choose-the-test-boundary) to add evidence for your
application's requirements.

## Related Tasks {#related-tasks}

- Recheck browser and asset wiring in [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Add evidence at the failing boundary with [Testing LiveViews](testing.md#choose-the-test-boundary).
- Resolve missing startup dependencies with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
