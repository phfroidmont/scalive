{%
title = "Configuration"
description = "Configure Scalive signing, token lifetime, cookies, WebSocket origins, routes, assets, and application-owned server settings."
order = 71
section = guides
group = "Assets and operations"
%}

## Before You Start {#prerequisites}

You need to know whether browsers will use HTTP or HTTPS and which socket and
static paths the application will expose. The [quick start](../learn/quick-start.md#start-the-server)
provides a complete baseline if you do not yet have an assembled application.

## Current Configuration Contract {#current-configuration-contract}

Scalive does not read environment variables or own process-wide endpoint
configuration. The application loads and validates its settings, then constructs
one @:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@ for the LiveView
transport:

```scala
import java.time.Duration

val transportConfig = ZioHttpConfig(
  signingSecret = requiredSecret,
  sessionMaxAge = Duration.ofDays(7),
  secureCookie = true,
  allowedWebSocketOrigins = Set(WebSocketOrigin.https("example.com"))
).fold(error => throw IllegalArgumentException(error.toString), identity)

val liveRoutes = ZioHttp.routes(application, transportConfig)
```

All four values are required:

| Setting | Meaning and validation | Production guidance |
| --- | --- | --- |
| `signingSecret` | Signs framework-issued LiveView and CSRF values. Construction rejects secrets shorter than 32 UTF-8 bytes. Values are authenticated, not encrypted. | Load a stable, high-entropy secret through the application's secret facility. Every replica must use the same value. Changing it invalidates outstanding signed values. |
| `sessionMaxAge` | Maximum accepted age for framework-issued LiveView and CSRF values. Construction rejects zero or negative durations. HTTP flash values have an independent 60-second lifetime. | Choose an explicit policy. This setting does not define authentication-session retention or make a browser cookie persistent. |
| `secureCookie` | Controls the `Secure` attribute on framework cookies and cookies created through `LiveSecurity.cookies`. Scalive does not infer it from TLS or proxy headers. | Use `true` whenever the browser-facing origin is HTTPS. Local plain HTTP normally requires `false`. |
| `allowedWebSocketOrigins` | Non-empty set of exact canonical browser origins admitted for WebSocket upgrades. Construction rejects a set with no usable origin. @:apiSymbol(object:scalive.WebSocketOrigin)`WebSocketOrigin`@:@ normalizes scheme and host case, default ports, and IP literals. | List every browser-facing page origin that may connect. Browser origins use `http` or `https`, not the socket URL's `ws` or `wss` scheme. Add explicit non-default ports, such as `WebSocketOrigin.https("example.com", 8443)`. |

Use the throwing @:apiSymbol(def:scalive.WebSocketOrigin.http)`WebSocketOrigin.http`@:@ and
@:apiSymbol(def:scalive.WebSocketOrigin.https)`WebSocketOrigin.https`@:@ constructors for trusted
source literals. Parse deployment input through
@:apiSymbol(def:scalive.WebSocketOrigin.parse)`WebSocketOrigin.parse`@:@ so an
invalid value remains in the startup error channel. For example, load a
comma-separated set without logging the untrusted value:

```scala
def parseOrigins(value: String): Either[String, Set[WebSocketOrigin]] =
  val entries = value.split(",", -1).iterator.map(_.trim).toVector
  if entries.isEmpty || entries.exists(_.isEmpty) then
    Left("SCALIVE_ALLOWED_ORIGINS must contain only non-empty origins")
  else
    entries.zipWithIndex.foldLeft[Either[String, Set[WebSocketOrigin]]](Right(Set.empty)) {
      case (validated, (entry, index)) =>
        for
          origins <- validated
          origin <- WebSocketOrigin.parse(entry).left.map(error =>
                      s"SCALIVE_ALLOWED_ORIGINS entry ${index + 1}: ${error.message}"
                    )
        yield origins + origin
    }

val transportConfig =
  (for
    rawOrigins <- sys.env
                    .get("SCALIVE_ALLOWED_ORIGINS")
                    .toRight("SCALIVE_ALLOWED_ORIGINS is required")
    origins <- parseOrigins(rawOrigins)
    config <- ZioHttpConfig(
                signingSecret = requiredSecret,
                sessionMaxAge = Duration.ofDays(7),
                secureCookie = true,
                allowedWebSocketOrigins = origins
              ).left.map(_.toString)
  yield config).fold(error => throw IllegalArgumentException(error), identity)
```

When configuration supplies a trusted scheme separately from a dynamic host or
port, use @:apiSymbol(def:scalive.WebSocketOrigin.httpEither)`httpEither`@:@ or
@:apiSymbol(def:scalive.WebSocketOrigin.httpsEither)`httpsEither`@:@ instead.

The policy is a finite exact set. Wildcard hosts, suffix matching, request-derived
origins, and custom predicates are intentionally unsupported. Unicode host input
is rejected; configure the ASCII/punycode host serialized by the browser. Origins
compare by canonical scheme, host, and effective port, so scheme and host case and
an omitted default port do not create separate origins. Programmatic `http` and
`https` constructors also canonicalize equivalent IP literal spellings;
`WebSocketOrigin.parse` requires a browser-serialized origin.

Construct the value once and reuse it. @:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@ also validates the
assembled Live route catalog, rejecting duplicate live-session names and duplicate
rendered paths before the server starts.

## Share Security With HTTP Handlers {#share-security-with-http-handlers}

Construct @:apiSymbol(class:scalive.LiveSecurity)`LiveSecurity`@:@ when ordinary
HTTP handlers need the same CSRF, flash, or cookie policy as Live routes:

```scala
val security = LiveSecurity(transportConfig)
val liveRoutes = ZioHttp.routes(application, security)
```

Cookies produced by these helpers are host-only, root-scoped, `HttpOnly`, and
`SameSite=Lax`. Their `Secure` attribute comes from `secureCookie`. The current
API does not configure another cookie domain, path, same-site value, or
`HttpOnly` policy.

Use the same `LiveSecurity` value in checked form handlers and HTTP-to-Live flash
redirects. Do not place passwords, access tokens, cookie values, or other secrets
inside signed session claims or flash values: a browser can read signed content.

## Configure Routes And Assets {#configure-routes-and-assets}

The remaining framework choices are made while assembling the application:

| Concern | API and default | Requirement |
| --- | --- | --- |
| Live socket | `Live.router.withSocketPath`; default `/live` | Configure the Phoenix client with the same mount. The WebSocket upgrade is the mount's `/websocket` child, `/live/websocket` by default. |
| Root and Live layouts | `Live.router.withRootLayout` and `.withLayout`; identity root and no ordinary layouts by default | Supply a complete `<html>`, `<head>`, and `<body>` root with the application assets. Scalive inserts its CSRF metadata into the `<head>`. |
| Static assets | `StaticAssetConfig.classpath` or `.directory`; default mount `/static` | Load the manifest before startup, add `assets.routes`, and render URLs from the same loaded value. |

The Scala and JavaScript socket paths must agree:

```scala
val application = Live.router
  .withSocketPath(PathCodec.empty / "socket")(
    Routes.home -> HomeLiveView()
  )
```

```js
const liveSocket = new LiveSocket("/socket", Socket, { params })
```

Use [Client setup and static assets](static-assets-and-client-setup.md) for asset
sources, digested paths, cache policy, and complete browser wiring.

## Configure The Server Separately {#configure-the-server-separately}

The application and ZIO HTTP own environment-variable names, bind address,
port, direct TLS, server request handling, idle timeouts, response compression,
and graceful-shutdown timeout. Configure those through ZIO HTTP's
`Server.Config` or the application's preferred configuration library; they are
not fields of `ZioHttpConfig`.

Public URL generation and trusted-proxy policy remain application concerns.
Validate a public URL when the application needs to generate absolute URLs.
WebSocket origin admission is instead transport configuration: every upgrade
must contain exactly one valid `Origin` matching `allowedWebSocketOrigins`.
Missing, `null`, malformed, duplicate, combined, or mismatched values receive
HTTP 403 before upgrade. Scalive never derives origin trust from `Host`,
`Forwarded`, `X-Forwarded-Host`, `X-Forwarded-Proto`, or an external URL prefix;
do not use those untrusted headers to decide whether cookies are secure either.

Load and validate all application settings once at startup, before
`StaticAssets.load`, route assembly, and `Server.serve`. Missing secrets, invalid
ports, and absent configured assets should stop the process rather than leave a
partially configured instance serving traffic.

Scalive currently supplies no central setting for health routes, telemetry,
cluster membership, shared LiveView state, or transport fallback. Add health and
instrumentation as application routes and middleware. Scalive supports WebSocket
transport only; deployment and scaling consequences are covered in
[Deployment](deployment.md#put-an-http-edge-in-front).

## Related Tasks {#related-tasks}

- Continue with [Deployment](deployment.md#build-and-run-the-current-application)
  to package and operate the application.
- Use [Client setup and static assets](static-assets-and-client-setup.md) to
  configure browser, socket, and asset paths.
- Use [Troubleshooting](troubleshooting.md#diagnose-socket-connections) when the
  HTTP render succeeds but the WebSocket does not join.
