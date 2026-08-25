{%
title = "Deployment"
description = "Run Scalive behind a provider-neutral HTTP and WebSocket edge while accounting for current packaging and operational limits."
order = 72
section = guides
group = "Assets and operations"
%}

## Prerequisites {#prerequisites}

A build job needs a JDK and Mill. Applications using the repository's
`NpmAssets` build also need Node.js and npm because Mill runs
`npm ci` and the package's build script before adding the declared outputs to
classpath resources. From this repository, `nix develop` supplies those tools.
The documentation generator also needs a Git checkout unless
`SCALIVE_DOCS_REVISION` is supplied. The assembled documentation application
needs only a compatible JRE at runtime.

Before deploying an application, complete these related tasks:

- [configure signing, cookies, socket paths, and application settings](configuration.md#current-configuration-contract);
- [bundle and serve the Phoenix client and static assets](static-assets-and-client-setup.md#build-the-client-bundle);
- test disconnected HTML, a real WebSocket connection, reconnects, and the
  application's authentication boundary; and
- decide which application-owned route, if any, represents liveness and
  readiness.

## Build And Run The Current Application {#build-and-run-the-current-application}

Enter the repository development shell:

```bash
nix develop
```

Build the self-contained executable JAR with an explicit source revision:

```bash
SCALIVE_DOCS_REVISION="$(git rev-parse HEAD)" \
mill --ticker false documentation.site.assembly
```

Run the result with only a JRE and explicit production configuration:

```bash
SCALIVE_TOKEN_SECRET='<replace-with-at-least-32-random-bytes>' \
SCALIVE_SERVER_PORT=8080 \
SCALIVE_PUBLIC_ORIGIN='https://docs.example.com' \
java -jar out/documentation/site/assembly.dest/out.jar
```

The assembly embeds generated documentation and browser assets. Node, npm,
Mill, and the source checkout are build-time dependencies only. For an
application following the source-backed quick start, the verified development
shape remains `mill app.run`.

`SCALIVE_SERVER_PORT` and `SCALIVE_PUBLIC_ORIGIN` above belong to the
documentation application, not to Scalive or ZIO HTTP. The documentation entry
point always binds to `127.0.0.1`; other applications must define their own
listen address, port, and public origin. The quick-start fixture instead fixes
port `8080` in `Server.defaultWithPort(8080)`.

## Put An HTTP Edge In Front {#put-an-http-edge-in-front}

Terminate browser-facing TLS at the application or at a reverse proxy/load
balancer. Scalive does not configure TLS and does not infer HTTPS from
`Forwarded`, `X-Forwarded-Proto`, or other proxy headers. When the public origin
is HTTPS, application construction must explicitly use:

```scala
val transportConfig = ZioHttpConfig(
  signingSecret = requiredSecret,
  sessionMaxAge = java.time.Duration.ofDays(7),
  secureCookie = true
).fold(error => throw IllegalArgumentException(error.toString), identity)

val security = LiveSecurity(transportConfig)
val routes = ZioHttp.routes(application, security)
```

Forward ordinary HTTP requests and WebSocket upgrade requests to the same
application routes. With the default `Live.router`, the browser configures
`new LiveSocket("/live", Socket, ...)` and upgrades `/live/websocket`. If the
router uses `.withSocketPath(PathCodec.empty / "socket")`, configure the client
with `/socket` and forward `/socket/websocket`. Preserve the query string and
cookie header on the upgrade request because the CSRF join uses both. Configure
the proxy's idle and request timeouts for long-lived WebSockets.

Scalive currently has WebSocket transport only. There is no long-poll fallback,
so an edge or network that blocks WebSocket upgrades leaves the page in its
disconnected state. Scalive exposes no framework option for trusted proxies,
forwarded-header processing, an external path prefix, or transport fallback.
If an edge adds a prefix, its routing must still expose the exact application
page, socket, and static mount paths; changing only generated HTML is not
sufficient.

The documentation application derives `secureCookie` from its validated public
origin. An HTTPS origin therefore emits secure cookies and requires an explicit
`SCALIVE_TOKEN_SECRET` containing at least 32 UTF-8 bytes. Forwarded headers do
not override either decision.

## Cache Static Assets {#cache-static-assets}

Render asset URLs through the same loaded `StaticAssets` value whose routes are
served. Digested responses default to `Cache-Control: public`, a one-year
`max-age`, and `immutable`; original paths default to `no-cache`. Both receive a
strong digest `ETag`. A CDN or reverse proxy may preserve those response
headers. Do not assign immutable caching to undigested application HTML or
asset paths.

The built-in asset routes do not implement conditional or range request
handling. Classpath and directory sources are reopened for requests, and the
manifest digest is calculated at startup, so keep the source bytes unchanged
for the lifetime of the process. For the exact source, mount, digest, and
`serveOriginals` contract, see
[Client setup and static assets](static-assets-and-client-setup.md#serve-digested-paths).

## Handle Secrets And Cookies {#handle-secrets-and-cookies}

Provide one stable, high-entropy signing secret of at least 32 UTF-8 bytes to
every production replica. Do not put the value in command history, images,
source control, logs, or browser-visible configuration; inject it through the
deployment platform's secret facility. Treat a missing secret as a startup
error. `ZioHttpConfig` also rejects a non-positive session age; do not replace
either validation failure with a random fallback in production.

Framework tokens are signed, not encrypted. Do not put passwords, access
tokens, cookie values, or other secrets in Live session claims or flash values.
The framework cookie policy is host-only, root-scoped, `HttpOnly`, and
`SameSite=Lax`; its `Secure` flag is exactly the application's configured
boolean. Authentication session storage, revocation, retention, and cookie
contents remain application responsibilities.

## Scale Horizontally {#scale-horizontally}

Each connected LiveView model, its tasks, subscriptions, and upload state live
in the serving process. An established WebSocket naturally remains attached to
that process. On reconnect, the client may reach another replica and receives a
fresh connected mount; durable state therefore must live in an application-owned
shared service rather than only in the previous model.

All replicas that can receive the same browser must use the same signing secret
and compatible token age, route, socket, cookie, and asset configuration.
Scalive does not currently provide a cluster membership API, distributed
PubSub, cross-node LiveView migration, shared session store, or load-balancer
configuration. Sticky routing can reduce replica changes but does not replace
shared durable state or idempotent mount effects.

## Operate And Stop Instances {#operate-and-stop-instances}

The current applications run `Server.serve` inside `ZIOAppDefault`. Scalive
cleans up framework-owned socket tasks, subscriptions, and upload resources when
a socket shuts down, but it exposes no application-level graceful-drain API,
shutdown timeout, or readiness transition. Treat termination grace periods,
stopping new traffic, and load-balancer deregistration as deployment- and
application-owned concerns. Verify the behavior under the actual process signal
and proxy rather than assuming active WebSockets migrate; clients reconnect and
mount fresh state elsewhere.

Scalive supplies no framework-level liveness or readiness endpoint. The
documentation application owns `GET /health`, which becomes available only
after its generated content and static assets load and returns the exact full
Git revision embedded during the build. Other applications should add ordinary
ZIO HTTP routes and define readiness against the dependencies they actually
need.

Runtime code logs selected warnings, errors, crashes, and some debug lifecycle
events through ZIO logging. There is no complete public telemetry, metrics,
tracing, access-log, or observability API. Configure loggers and request
instrumentation in the application and edge, avoid logging credentials and
signed values, and add application metrics around dependencies and domain work.

## Related Tasks {#related-tasks}

- Use [Configuration](configuration.md#current-configuration-contract) for the
  exact framework and application-owned settings.
- Use [Client setup and static assets](static-assets-and-client-setup.md) for
  bundle, digest, and cache behavior.
- Use [Testing LiveViews](testing.md#test-in-a-browser) to exercise the deployed
  browser and transport boundary.
- Use [Troubleshooting](troubleshooting.md#account-for-current-limits) for
  current diagnostic limits and reconnect checks.
