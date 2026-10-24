{%
title = "Troubleshooting"
description = "Diagnose Scalive startup, assets, WebSocket connections, CSRF failures, and reconnect behavior without hiding current limitations."
order = 62
section = guides
group = "Testing and troubleshooting"
%}

## Before you start {#prerequisites}

Start with a reproducible URL whose initial HTTP status and HTML you can inspect,
plus server logs and the browser console and Network panel. Record whether the
failure appears before or after the LiveView connects.

## Separate the two mounts {#separate-the-two-mounts}

A @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ mounts once for the
disconnected HTTP response and again each time it joins over the WebSocket,
including after a reconnect. Start diagnosis by deciding which phase failed:

1. Fetch the page with JavaScript disabled or inspect the document request. A
   non-200 response, missing document, or incorrect initial HTML is a
   disconnected startup or routing problem.
2. If the document is correct, inspect the browser console and Network panel.
   The Phoenix client should load and open a WebSocket below the configured
   socket path.
3. If the LiveView joins but an interaction fails, inspect the event binding,
   decoded message, handler failure, and returned diff rather than changing the
   HTTP route.

The [Lifecycle, state ownership, and reconnects](../learn/lifecycle-and-connection-behavior.md#treat-reconnect-as-a-new-lifecycle)
lesson explains why `mount` must be repeatable and where connection-only work
belongs.

## Diagnose startup failures {#diagnose-startup-failures}

Check the startup path in order:

- Build any custom browser bundle before starting the JVM, and pass
  `LiveClient.Custom` to `ZioHttp.routes` when it includes both clients.
- Load every declared asset with @:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@.
- Validate one @:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@ and pass
  it to @:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@.
- Make a custom root layout render `<html>`, `<head>`, and `<body>`.
- Combine the Live routes with
  @:apiSymbol(val:scalive.StaticAssets.routes)`assets.routes`@:@ when the application
  serves its own assets.
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
fallback. `ZioHttpConfig.local` draws a random secret on purpose, for local
development only.

## Diagnose missing assets {#diagnose-missing-assets}

If the HTML renders but remains disconnected, first verify that the scripts
Scalive adds below its client path, `/_scalive` by default, or the custom client bundle, loaded successfully. Compare the response
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
that does not match the packaged resource, or forgetting
@:apiSymbol(val:scalive.StaticAssets.routes)`assets.routes`@:@.
For deployment manifests, first verify the configured manifest filename and
location, schema version, and `immutable` or `revalidate` cache values. Then
check that every output appears as a `file` value, every final file exists, and
no path traversal or conflicting cache declaration is present.

Assets are served from the bytes read at startup. Replacing a file in place has
no effect until `StaticAssets` is loaded again; publish a new content-addressed
filename instead, so that browsers holding the old immutable file fetch the new
one. Return to the
[quick-start asset wiring](../learn/quick-start.md#start-the-server) for a minimal
known shape.

## Diagnose WebSocket connections {#diagnose-websocket-connections}

The packaged client connects to the router's socket path, `/live` by default, and
opens the WebSocket at `/live/websocket`. If the router uses another socket
path, check that a custom bundle
[uses the same one](configuration.md#configure-routes-and-assets) and that a
reverse proxy forwards the upgrade on that path. Check the actual
request URL, upgrade response, proxy timeouts, and browser console before
debugging event handlers.

An HTTP 403 before the upgrade means the request's `Origin` did not pass
[origin admission](configuration.md#admit-websocket-origins). The response body
is empty and Scalive logs no reason, so compare the `Origin` request header in
the browser's Network panel with the effective `allowedWebSocketOrigins`. A
missing non-default port and a proxy that rewrites or drops `Origin` are common
causes. Then inspect application access logs and edge logs. Test the
application endpoint directly when possible: a branded or non-empty 403
response, or a request absent from application access logs, usually means that
the proxy or edge rejected it first.

Scalive implements only the WebSocket transport. Phoenix Channels long-poll
fallback is not implemented, and endpoint options such as long-poll and
hibernation are not available. A deployment that blocks WebSockets therefore
cannot rely on transport fallback.

## Diagnose CSRF rejections {#diagnose-csrf-rejections}

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
- Every replica uses the same signing secret.
- A proxy preserves the query string and cookie header on the upgrade request.

Missing, expired, transferred, or tampered token/cookie pairs are rejected. A
failure at this point can appear as a stale join rather than an HTTP form error.
Ordinary checked POST forms use the same protection and receive a hidden
`_csrf_token`; do not remove that generated field.

## Diagnose an input that sends nothing {#diagnose-an-input-that-sends-nothing}

When typing in a control with `on.change` reaches no handler, the control
probably has no form. The Phoenix client sends a change binding only from a
form or from a control that belongs to one; for any other control it throws
"form events require the input to be inside a form" in the browser console on
every keystroke, and a `phx-change` on an element that is neither, such as a
wrapping `div`, is never read.

Scalive reports both. The disconnected render logs a warning, "A change binding
the browser never sends", with the route and the element. In tests,
`ConnectedRender` fails the join or the action whose render contains one. Put
the control in a `form`, give it a `form` attribute naming one, or use a
[key binding](html-dsl-and-event-bindings.md#limit-event-rates) for a
standalone input.

## Diagnose reconnects and crashes {#diagnose-reconnects-and-crashes}

On transport interruption, expect the Phoenix client to attempt reconnection and
the server to mount a fresh connected lifecycle. When a joined root
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ crashes, Scalive sends the
client a Phoenix `phx_error`, and the client rejoins with a new lifecycle.
Scalive logs each crash once at `ERROR` as `LiveView turn failed`, with the
failure's cause and the lifecycle's `connection_id`, `lifecycle_id`, `view`, and
`stage` annotations. Filter the server logs by `lifecycle_id` to see what the
LiveView did before it crashed; see
[Read Scalive's logs](lifecycle-observability.md#read-scalive-logs) for every
level and annotation.

Verify that:

- @:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ does not assume that the
  disconnected model or an earlier lifecycle still exists;
- connected resources, streams, and fibers are acquired through lifecycle
  capabilities so the old lifecycle can release them;
- state that must survive a reconnect is loaded from a service rather than only
  from the previous model;
- repeated mount side effects are idempotent or explicitly guarded;
- deployed replicas share the signing secret; and
- proxy idle timeouts are not repeatedly closing healthy WebSocket connections.

Write a browser test for the concrete recovery behavior your application
promises.

`ConnectedMetadata.staticChanged` reports whether the running server's root
layout tracks an asset URL the browser has not loaded, usually after a
deployment.
`ConnectedMetadata.connectParam` decodes one application-owned browser join
parameter as untrusted input, while typed server-derived connect info remains
partial. `ConnectedMetadata.join` tells a first join from a rejoin after a
reconnect; it is reported by the browser, so never base security on it.

## Use lifecycle metrics to narrow the failure {#use-lifecycle-metrics}

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

## Account for current limits {#account-for-current-limits}

Current operational and diagnostic limits include:

- no long-poll transport fallback;
- connected server-side testing does not cross the real browser DOM boundary;
- lifecycle observation does not replace endpoint HTTP or infrastructure metrics;
- no reason-specific log entry for a rejected WebSocket Origin;
- untrusted JSON connect params, with only partial typed server-derived connect
  info;
- expanding rather than exhaustive protocol error and reconnect parity; and
- signed but not encrypted session claims and flash values.

These limits do not mean the corresponding core behavior is absent. They mark
where Scalive does not yet promise a complete public API or full Phoenix
LiveView parity. Consult the [compatibility matrix](../project/compatibility.md#status-matrix) before relying
on an edge case, and use the boundary guidance in
[Testing LiveViews](testing.md#choose-the-test-boundary) to add evidence for your
application's requirements.

## Related tasks {#related-tasks}

- Recheck browser and asset wiring in [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Add evidence at the failing boundary with [Testing LiveViews](testing.md#choose-the-test-boundary).
- Configure and interpret operation metrics with
  [Lifecycle observability](lifecycle-observability.md#publish-built-in-metrics).
- Resolve missing startup dependencies with [Services and dependency injection](services-and-zlayer-injection.md#provide-services-at-startup).
