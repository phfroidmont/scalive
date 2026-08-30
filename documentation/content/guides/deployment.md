{%
title = "Deployment"
description = "Build and operate a Scalive JVM application behind HTTPS and a WebSocket-capable edge."
order = 72
section = guides
group = "Assets and operations"
%}

## Before You Start {#prerequisites}

You need a runnable Scalive application with validated production
[configuration](configuration.md#current-configuration-contract) and a built
[browser bundle](static-assets-and-client-setup.md#build-the-client-bundle).
The commands use the `app` module from the [quick start](../learn/quick-start.md)
as an example; substitute your application's module name.

@:callout(warning)

Scalive is alpha software without source, binary, or complete Phoenix LiveView
compatibility guarantees. Review the current [project status](../project/index.md#project-status),
pin the exact version you test, and validate deployment behavior for that
version.

@:@

## Prepare Production Settings {#prepare-production-settings}

The Quick Start deliberately uses a development signing-secret fallback, fixed
port `8080`, and `secureCookie = false`. Do not deploy those settings unchanged.
Before packaging, require a stable high-entropy secret, use
`secureCookie = true` for browser-facing HTTPS, and validate application-owned
server and service settings at startup. Configure a non-empty
`allowedWebSocketOrigins` with every exact browser-facing page origin. Make the
Phoenix client, Live router, static mount, and edge use matching paths.

Environment-variable names are conventions of the application, not Scalive.
The Quick Start already reads `SCALIVE_TOKEN_SECRET`; applications may retain
that name or define their own validated configuration contract. Inject real
secret values through the deployment platform's secret facility, not source
control, an image, logs, or shell history.

## Build And Run The Application {#build-and-run-the-current-application}

A build job needs a JDK and Mill. The Quick Start asset task also needs Node.js
and npm because the application resources depend on its browser bundle. Build an
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

The assembly includes the application's JVM dependencies and the browser bundle
added by its Mill `resources` task. Mill, Node.js, npm, and the source tree are
build-time requirements, not runtime requirements. If the application uses a
different Mill module name, substitute that name in the task and output path.

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

## Put An HTTP Edge In Front {#put-an-http-edge-in-front}

Terminate browser-facing TLS at a reverse proxy or load balancer, or configure
TLS directly through ZIO HTTP. In either case, set `secureCookie = true` when the
browser uses HTTPS. Scalive does not infer HTTPS from `Forwarded`,
`X-Forwarded-Proto`, or similar headers.

The edge must route all of the following to the application:

- ordinary page and form requests;
- static requests below the configured asset mount; and
- WebSocket upgrade requests below the configured Live socket mount.

With the default router and `new LiveSocket("/live", Socket, ...)`, the upgrade
endpoint is `/live/websocket`. If the router uses
`.withSocketPath(PathCodec.empty / "socket")`, configure the client with
`/socket` and forward `/socket/websocket` instead. Preserve the upgrade request's
query string, cookie header, and single browser `Origin` header because transport
and LiveView join admission use them. The allowed origin uses the page's `http`
or `https` origin, not the WebSocket's `ws` or `wss` URL. Scalive rejects an
invalid or unlisted Origin with HTTP 403 according to the
[configured exact policy](configuration.md#current-configuration-contract).
Default ports are normalized; configure a non-default public port explicitly. Allow
long-lived WebSocket connections and choose edge and server idle timeouts that
do not terminate healthy sockets.

Scalive does not derive origin trust from `Host`, `Forwarded`,
`X-Forwarded-Host`, `X-Forwarded-Proto`, or similar headers. Configure the
browser-facing origins directly, and ensure the edge preserves rather than
synthesizes or combines `Origin`.

Scalive currently supports WebSocket transport only. There is no long-poll
fallback, so a network or edge that blocks WebSocket upgrades leaves the page in
its disconnected state. Scalive also has no external-path-prefix setting; an
edge that adds a prefix must still expose page, socket, and static paths exactly
as the application renders them.

## Cache Static Assets {#cache-static-assets}

Render asset URLs and serve asset routes from the same loaded `StaticAssets`
value. Ordinary classpath and directory sources place the unchanged output tree
beneath one asset-set SHA-256 namespace. Those versioned responses default to
public immutable caching for one year; originals are disabled by default and
use `no-cache` when explicitly enabled. Deployment manifests instead serve each
declared final path with its declared `immutable` or `revalidate` policy.

A CDN or edge may preserve those response headers, but must not apply immutable
caching to application HTML or mutable asset URLs. Immutable manifest entries
must have content-addressed or otherwise stable URLs, and deployments must retain
old outputs while active clients and dynamic imports can still request them. An
immutable file replaced in place returns `404` until the application reloads its
asset description; publish a new final path instead. See
[Client setup and static assets](static-assets-and-client-setup.md#serve-versioned-paths)
for versioned trees and
[deployment manifests](static-assets-and-client-setup.md#load-a-deployment-manifest)
for final-path behavior.

## Scale And Roll Out {#scale-horizontally}

Each connected LiveView model, along with its tasks, subscriptions, and upload
state, lives in the serving process. An established WebSocket remains attached
to that process. After a disconnect, restart, or rollout, the client may reach
another replica and performs a fresh connected mount.

Persist state that must survive reconnects or process loss in an
application-owned shared service. Make mount effects repeatable, and keep route,
socket, cookie, asset, token-age, and signing configuration compatible across
replicas. A random process-local signing secret prevents replicas from accepting
one another's values. Sticky routing can reduce replica changes but does not
replace durable state or idempotent mount behavior.

Scalive supplies no cluster membership, general distributed PubSub, shared
LiveView model store, or cross-node socket migration. Applications that need
cross-node authentication disconnects can provide a `LiveDisconnectBus` adapter
as described in [Authentication and sessions](authentication.md#fan-out-disconnects-across-nodes);
that adapter does not distribute LiveView state. Run the same tested Scalive
version across replicas unless a mixed-version rollout has been verified.

## Operate And Stop Instances {#operate-and-stop-instances}

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

Runtime code emits selected lifecycle failures and diagnostics through ZIO
logging. Scalive does not currently expose a complete metrics, tracing,
telemetry, or access-log API. Configure request logging and instrumentation in
the application and edge, avoid recording credentials or signed values, and add
domain and dependency metrics where they are actionable.

## Verify The Deployment {#verify-the-deployment}

Before promoting an instance, verify its public URL end to end:

- the WebSocket upgrade reaches `<socket-path>/websocket` with query parameters
  and cookies plus exactly one allowed browser `Origin` intact;
- invalid and unlisted origins receive HTTP 403 rather than an upgrade;
- HTTPS responses set framework and authentication cookies with `Secure`;
- the configured deployment manifest validates before readiness, when used;
- disconnected HTML loads its ordinary versioned tree or manifest-defined final
  paths with the intended cache policy;
- old immutable outputs remain available for active clients and dynamic imports;
- liveness, readiness, termination, and reconnect behavior work under the
  platform's actual proxy and process signals; and
- a browser can reconnect through another replica without losing state the
  application promises to preserve.

## Related Tasks {#related-tasks}

- Use [Configuration](configuration.md#current-configuration-contract) for the
  exact framework and application-owned settings.
- Use [Client setup and static assets](static-assets-and-client-setup.md) for
  bundle, versioning, deployment-manifest, and cache behavior.
- Use [Testing LiveViews](testing.md#test-in-a-browser) to exercise the deployed
  browser and transport boundary.
- Use [Troubleshooting](troubleshooting.md#diagnose-socket-connections) for asset,
  CSRF, WebSocket, and reconnect failures.
