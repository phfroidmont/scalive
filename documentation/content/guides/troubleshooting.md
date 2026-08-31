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

## Use Lifecycle Metrics To Narrow The Failure {#use-lifecycle-metrics}

When the application has enabled the built-in adapter from
[Lifecycle observability](lifecycle-observability.md#publish-built-in-metrics),
start with the metric matching the boundary that failed:

| Symptom | Metrics to inspect | Interpretation |
| --- | --- | --- |
| Initial HTML fails or is slow | `scalive_disconnected_render_total`, `scalive_disconnected_render_duration_seconds`, `scalive_mount_total{phase="disconnected"}` | Separate disconnected mount failures from later render stages. |
| WebSocket reaches Scalive but does not join | `scalive_join_total{outcome="rejected"}`, `scalive_join_duration_seconds` | Inspect `target`, `failure`, and `stage`. An HTTP 403 Origin rejection happens before lifecycle observation and does not increment this metric. |
| A joined interaction fails | `scalive_turn_total{outcome="failed"}`, `scalive_handler_failures_total` | Group by `kind`, `failure`, and `stage`; one handler failure appears in both families. |
| Clients repeatedly reconnect | `scalive_join_total`, `scalive_lifecycle_terminations_total` | Compare positive `reconnect` or `retry` labels with termination reasons. A `false` reconnect label only means no positive valid client counter was observed. |
| Work is rejected under load | `scalive_queue_saturation_total`, `scalive_queue_depth` | Group by `queue`; use depth distributions to distinguish a sustained backlog from isolated saturation. |
| A connected lifecycle stops unexpectedly | `scalive_lifecycle_terminations_total` | Inspect `reason`, `failure`, and `stage`, then correlate the time window with application and edge logs. |

These families observe nested boundaries and are not independent incident
counts. Do not add handler, turn, mount, render, and join failures together.
Metrics contain stable classifications rather than exception messages or
runtime IDs, so use redacted application logs or traces for request-specific
correlation.

## Diagnose Startup Failures {#diagnose-startup-failures}

Check the startup path in order:

- Build any custom browser bundle before starting the JVM.
- Load
  @:apiSymbol(def:scalive.LiveViewClientAssets.load)`LiveViewClientAssets.load`@:@
  unless the application bundle includes both clients.
- Load every declared asset with @:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@.
- Validate one @:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@ and pass
  it to @:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@.
- Attach a complete root layout containing `<html>`, `<head>`, and `<body>`.
- Combine the Live routes with the packaged-client routes when used and with
  @:apiSymbol(val:scalive.StaticAssets.routes)`assets.routes`@:@.
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

If the HTML renders but remains disconnected, first verify that the packaged
client scripts or custom client bundle loaded successfully. Compare the response
with the canonical
[asset serving](static-assets-and-client-setup.md#serve-versioned-paths) and
[tracked tag](static-assets-and-client-setup.md#render-tracked-tags) setup, then
request the rendered versioned or final URL directly. The same
loaded asset value must render the script tag and serve its routes.

@:apiSymbol(def:scalive.StaticAssets.trackedScript)`trackedScript`@:@ and
@:apiSymbol(def:scalive.StaticAssets.trackedStylesheet)`trackedStylesheet`@:@ emit versioned or final paths and
`phx-track-static`. Ordinary assets use one immutable, one-year asset-set
namespace; original paths are disabled by default and use `no-cache` only when
explicitly enabled. Deployment entries use their declared cache policy. Do not
hard-code a generated path; render it through the loaded assets. If a reverse proxy adds a URL prefix,
make its routing agree with @:apiSymbol(val:scalive.StaticAssetConfig.mountPath)`StaticAssetConfig.mountPath`@:@ rather than rewriting
only the HTML.

Common asset failures are a missing Mill `resources` dependency on the bundle,
an incomplete output tree or ordinary classpath asset list, a classpath prefix
that does not match the packaged resource, or forgetting either
`clientAssets.routes` or @:apiSymbol(val:scalive.StaticAssets.routes)`assets.routes`@:@.
For deployment manifests, first verify the configured manifest filename and
location, schema version, and `immutable` or `revalidate` cache values. Then
check that every output appears as a `file` value, every final file exists, and
no path traversal or conflicting cache declaration is present.

An immutable file is pinned to the digest read at startup. Replacing it in place
makes its route return `404` until `StaticAssets` is loaded again; publish a new
content-addressed filename instead. A revalidating file may change at the same
path and receives a current `ETag`. Return to the
[quick-start asset wiring](../learn/quick-start.md#start-the-server) for a minimal
known shape.

## Diagnose Socket Connections {#diagnose-socket-connections}

The default packaged-client configuration is
`new LiveView.LiveSocket("/live", Phoenix.Socket, ...)`.
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
const liveSocket = new LiveView.LiveSocket("/socket", Phoenix.Socket, { params })
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
- connected resources, streams, and fibers are acquired through lifecycle
  capabilities so the old lifecycle can release them;
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
- lifecycle observation does not replace endpoint HTTP or infrastructure metrics;
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
- Configure and interpret operation metrics with
  [Lifecycle observability](lifecycle-observability.md#publish-built-in-metrics).
- Resolve missing startup dependencies with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
