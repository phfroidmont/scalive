{%
title = "Deployment"
description = "Build and operate a Scalive JVM application behind HTTPS and a WebSocket-capable edge."
order = 73
section = guides
group = "Operations"
%}

## Before you start {#prerequisites}

You need a runnable Scalive application with validated production
[configuration](configuration.md#current-configuration-contract) and its
browser resources. The quick start uses only the browser clients packaged
with Scalive; applications with a custom browser build must build its
[bundle](static-assets-and-client-setup.md#build-the-client-bundle).
The commands use the `app` module from the [quick start](../learn/quick-start.md)
as an example; substitute your application's module name.

@:callout(warning)

Scalive is alpha software without source, binary, or complete Phoenix LiveView
compatibility guarantees. Review the current [project status](../project/index.md#project-status),
pin the exact version you test, and validate deployment behavior for that
version.

@:@

## Prepare production settings {#prepare-production-settings}

The quick start deliberately uses a development signing-secret fallback, fixed
port `8080`, and `secureCookie = false`. Do not deploy those settings unchanged.
Before packaging, load the settings so that startup fails when one is missing,
as the [configuration loader](configuration.md#current-configuration-contract)
does: require a stable high-entropy secret, use `secureCookie = true` for
browser-facing HTTPS, list every browser-facing page origin in
`allowedWebSocketOrigins`, and validate application-owned server and service
settings. Make the browser bootstrap, Live router, static mounts, and edge use
matching paths.

Inject real secret values through the deployment platform's secret facility, not
source control, an image, logs, or shell history.

## Build and run the application {#build-and-run-the-current-application}

A quick-start build job needs only a JDK and Mill. A richer custom browser build
may additionally require Node.js, npm, or other tools described by the
[custom bundle guide](static-assets-and-client-setup.md#build-the-client-bundle). Build an
executable JAR from the project root:

```bash
mill --ticker false app.assembly
```

The resulting artifact is:

```text
out/app/assembly.dest/out.jar
```

Have the runtime environment provide the application's required settings, then
start it with a compatible JRE:

```bash
java -jar out/app/assembly.dest/out.jar
```

The assembly includes the application's JVM dependencies, Scalive's packaged
Phoenix clients, and every file under `app/resources`. Mill and the source tree are
build-time requirements, not runtime requirements; custom browser tooling is
also build-time only when used. If the application uses a different Mill module
name, substitute that name in the task and output path.

That single-JAR layout applies to ordinary classpath trees and classpath
deployment manifests. An application using `deploymentDirectory` must ship the
complete external output tree alongside the JAR, including its deployment
manifest, and configure `root` to that release path. Publish the JAR, manifest,
and final files as one release; do not make the instance ready until
`StaticAssets.load` validates them. Retain prior content-addressed outputs for as
long as active clients may request them.

Scalive does not prescribe a container image, service manager, database
migration command, or publication workflow. Package and launch the JAR and any
external asset tree according to the application's infrastructure while keeping
configuration and secrets outside the artifact.

## Put an HTTP edge in front {#put-an-http-edge-in-front}

Terminate browser-facing TLS at a reverse proxy or load balancer, or configure
TLS directly through ZIO HTTP. In either case, set `secureCookie = true` when the
browser uses HTTPS. Scalive does not infer HTTPS from `Forwarded`,
`X-Forwarded-Proto`, or similar headers.

The edge must route all of the following to the application:

- ordinary page and form requests;
- static requests below the client path and every configured asset mount; and
- WebSocket upgrade requests below the configured socket path.

With the default router, the upgrade endpoint is `/live/websocket`. Scalive's
own browser scripts are served below the client path, `/_scalive` by default;
that static mount is separate from the socket path. If the router
[uses another socket or client path](configuration.md#configure-routes-and-assets),
forward those instead.

Preserve the upgrade request's query string, cookie header, and the browser's
single `Origin` header: CSRF validation, the LiveView join, and
[origin admission](configuration.md#admit-websocket-origins) use them. The edge
must not synthesize, rewrite, or combine `Origin`. Allow long-lived WebSocket
connections and choose edge and server idle timeouts that do not close healthy
ones.

Scalive supports only the WebSocket transport. There is no long-poll fallback,
so a network or edge that blocks WebSocket upgrades leaves the page in its
disconnected state. Scalive also has no external-path-prefix setting. To serve
the application below a prefix, declare its routes below it and
[move the socket and client paths there](configuration.md#configure-routes-and-assets);
an edge that adds a prefix itself must still expose page, WebSocket, and static
paths exactly as the application renders them.

### Example: nginx {#example-nginx}

This nginx server terminates TLS for `app.example.com` and forwards every path,
including the WebSocket upgrade, to an application listening on port `8080`.
Both blocks belong in nginx's `http` context, for example in a file that the
main configuration includes:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl;
    server_name app.example.com;

    ssl_certificate     /etc/ssl/app.example.com/fullchain.pem;
    ssl_certificate_key /etc/ssl/app.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
    }
}
```

`proxy_pass` without a path forwards the request URI and query string
unchanged, and nginx passes the `Cookie` and `Origin` headers through by
default. The `Upgrade` and `Connection` headers turn the WebSocket request into
an upgrade; without them nginx sends an ordinary HTTP request and the page
stays disconnected. The Phoenix client sends a heartbeat every 30 seconds, so
nginx's default 60-second `proxy_read_timeout` does not close an idle page.

The application's `allowedWebSocketOrigins` must contain
`WebSocketOrigin.https("app.example.com")`, the origin the browser sees, not
the address nginx forwards to.

## Cache static assets {#cache-static-assets}

Render asset URLs and serve asset routes from the same loaded `StaticAssets`
value. Ordinary classpath and directory sources place the unchanged output tree
beneath one asset-set SHA-256 namespace. Those versioned responses default to
public immutable caching for one year; originals are disabled by default and
use `no-cache` when explicitly enabled. Deployment manifests instead serve each
declared final path with its declared `immutable` or `revalidate` policy.

A CDN or edge may preserve those response headers, but must not apply immutable
caching to application HTML or mutable asset URLs. Scalive marks its pages
`private` or `no-store`, following the `pageCache` setting in
[Configuration](configuration.md#current-configuration-contract), so a shared
cache that respects `Cache-Control` never keeps one. Immutable manifest entries
must have content-addressed or otherwise stable URLs. An immutable file replaced
in place returns `404` until the application reloads its asset description;
publish a new final path instead. See
[Client setup and static assets](static-assets-and-client-setup.md#serve-versioned-paths)
for versioned trees and
[deployment manifests](static-assets-and-client-setup.md#load-a-deployment-manifest)
for final-path behavior.

### Know what a deployment invalidates {#know-what-a-deployment-invalidates}

A server serves only the assets it loaded at startup. A page opened before a
deployment keeps the files it already loaded, but files it requests later go to
the new server: lazily imported chunks, and fonts or images that its CSS
references. A URL the new server no longer serves returns `404`.

Which URLs survive depends on the asset model:

| Model | After a deployment |
| --- | --- |
| Classpath or directory tree | Changing any file changes the asset-set digest, so every URL moves, including those of unchanged fonts and images. Browsers download the unchanged files again. In exchange, relative references between files resolve without rewriting. |
| Deployment manifest with content-hashed names | Unchanged files keep their URLs. Only files whose bytes changed move. |

Prefer a bundler that emits content-hashed names, loaded through a
[deployment manifest](static-assets-and-client-setup.md#load-a-deployment-manifest),
when open pages request assets after their first load or when assets are large.
To keep a moved URL working, the deployment must still provide its old file: list
it in the manifest, or serve an archive of previous outputs at the edge.

During a rolling deployment, a page rendered by a new replica can request a new
asset from a replica that does not have it yet. Serve assets from storage every
version shares, or route a browser to one replica for the duration of the
rollout.

Pages that keep running on assets from before the deployment can
[offer a reload](layouts.md#offer-a-reload-after-a-deployment) from a layout.

## Scale and roll out {#scale-horizontally}

Each connected LiveView model, along with its tasks, subscriptions, and upload
state, lives in the serving process. An established WebSocket remains attached
to that process. After a disconnect, restart, or rollout, the client may reach
another replica and performs a fresh connected mount. Its LiveView renders the
new markup, but its `<head>` keeps the assets it loaded before the rollout; an
ordinary layout can [offer a reload](layouts.md#offer-a-reload-after-a-deployment)
when they changed.

Persist state that must survive reconnects or process loss in an
application-owned shared service. Make mount effects repeatable, and keep route,
socket path, cookie, asset, token-age, and signing configuration compatible
across replicas. A random process-local signing secret prevents replicas from accepting
one another's values. Sticky load-balancer routing can reduce replica changes
but does not replace shared state or idempotent mount behavior.

Scalive supplies no cluster membership, general distributed PubSub, shared
LiveView model store, or cross-node migration of WebSocket connections.
Applications that need cross-node authentication disconnects can provide a
`LiveDisconnectBus` adapter as described in [Authentication and sessions](authentication.md#fan-out-disconnects-across-nodes);
that adapter does not distribute LiveView state. Run the same tested Scalive
version across replicas unless a mixed-version rollout has been verified.

## Operate and stop instances {#operate-and-stop-instances}

Scalive does not add liveness or readiness endpoints. Implement them as ordinary
ZIO HTTP routes with application-specific semantics. Liveness should report
local process health without checking external dependencies. Readiness should
become true only after required configuration, assets, and services are
available, and should become false before an instance stops accepting traffic
when the deployment platform supports that transition.

ZIO HTTP's `Server.Config` owns the graceful-shutdown timeout and other server
limits. Coordinate that timeout with edge deregistration and the platform's
termination grace period. Scalive releases lifecycle-owned tasks, subscriptions,
connected resources, and uploads when their LiveView lifecycles close, but
active WebSockets do not migrate to another process; clients reconnect and
mount fresh state. Keep acquisition and finalization bounded so lifecycle
cleanup fits within the platform's termination grace period.

Scalive exposes structured lifecycle events and a fixed, low-cardinality ZIO
Metrics adapter for LiveView operations. Configure it through
[Lifecycle observability](lifecycle-observability.md#publish-built-in-metrics).
Lifecycle observation does not replace request metrics, access logs,
infrastructure telemetry, or application-domain metrics. Configure those at the
application and edge, and avoid recording credentials or signed values.

## Verify the deployment {#verify-the-deployment}

Start with two requests against the public URL. The first sends a WebSocket
upgrade with the page's origin and must print `101`; the second sends an
unlisted origin and must print `403`:

```bash
for origin in https://app.example.com https://unlisted.example; do
  curl --http1.1 -s -o /dev/null -w '%{http_code}\n' --max-time 5 \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    -H "Origin: $origin" \
    'https://app.example.com/live/websocket?vsn=2.0.0'
done
```

curl holds the upgraded connection open until `--max-time` expires, so the
`101` appears after five seconds. A `101` followed by a `403` shows that the
edge forwards upgrades and passes `Origin` through unchanged; a `400` usually
means the edge dropped the `Upgrade` or `Connection` header. Any other status means the edge or the origin list
needs attention; [Troubleshooting](troubleshooting.md#diagnose-websocket-connections)
lists the common causes. Then open a page in a browser and check that it
connects and responds.

Before promoting an instance, also verify:

- the WebSocket upgrade reaches `<socket-path>/websocket` with query parameters
  and cookies plus exactly one allowed browser `Origin` intact;
- invalid and unlisted origins receive HTTP 403 rather than an upgrade;
- HTTPS responses set framework and authentication cookies with `Secure`;
- the configured deployment manifest validates before readiness, when used;
- disconnected HTML loads its ordinary versioned tree or manifest-defined final
  paths with the intended cache policy;
- old immutable outputs remain available for active clients and dynamic imports;
- liveness, readiness, termination, and reconnect behavior work under the
  platform's actual proxy and process signals;
- the configured metrics connector exports a disconnected render, join, and
  connected mount from a real browser session; and
- a browser can reconnect through another replica without losing state the
  application promises to preserve.

## Related tasks {#related-tasks}

- Use [Configuration](configuration.md#current-configuration-contract) for the
  exact framework and application-owned settings.
- Use [Client setup and static assets](static-assets-and-client-setup.md) for
  bundle, versioning, deployment-manifest, and cache behavior.
- Configure and verify operation metrics with
  [Lifecycle observability](lifecycle-observability.md#publish-built-in-metrics).
- Use [Testing LiveViews](testing.md#test-in-a-browser) to exercise the deployed
  browser and transport boundary.
- Use [Troubleshooting](troubleshooting.md#diagnose-websocket-connections) for asset,
  CSRF, WebSocket, and reconnect failures.
