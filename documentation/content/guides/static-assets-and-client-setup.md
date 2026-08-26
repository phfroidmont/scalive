{%
title = "Client setup and static assets"
description = "Load classpath or directory assets, render digested links, and connect the Phoenix LiveView client."
order = 70
section = guides
group = "Assets and operations"
%}

## Prerequisites {#prerequisites}

Have a Scalive application that can start from Mill, plus Node.js and npm for
the browser bundle. Complete the [Quick start](../learn/quick-start.md) first if
you do not yet have routes and a root layout.

## Build The Client Bundle {#build-the-client-bundle}

Install the Phoenix JavaScript packages and bundle a browser entry point. The
current Scalive quick-start fixture uses these versions:

```json
{
  "private": true,
  "type": "module",
  "scripts": {
    "build": "esbuild assets/js/app.js --bundle --platform=browser --format=iife --target=es2020 --outfile=dist/app.js"
  },
  "dependencies": {
    "phoenix": "1.7.21",
    "phoenix_live_view": "1.2.10"
  },
  "devDependencies": {
    "esbuild": "0.28.1"
  }
}
```

Generate and commit `package-lock.json`. The repository's `NpmAssets` Mill trait
runs `npm ci`, runs the package's `build` script, and copies the declared
`bundleOutputs` from `dist` into a `public` resource directory. If your build
uses that trait, include its bundle output in the Scala module's resources:

```scala
object myApp extends ScalaCommon with NpmAssets:
  def moduleDeps = Seq(scalive)

  override def bundleOutputs = Seq("app.js")

  def resources = Task {
    super.resources() :+ bundle()
  }
```

This repository-local build setup is shown in full in the
[quick start](../learn/quick-start.md#create-the-project).

## Connect LiveSocket {#connect-live-socket}

Create the browser entry point imported by the bundle:

```js
import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const params = csrfToken ? { _csrf_token: csrfToken } : {}

const liveSocket = new LiveSocket("/live", Socket, { params })
liveSocket.connect()

window.liveSocket = liveSocket
```

@:apiSymbol(val:scalive.Live.router)`Live.router`@:@ uses `/live` as its current default socket path. Scalive injects
the `csrf-token` meta element into the root layout's `<head>` and associates it
with the CSRF cookie. Return that value as `_csrf_token`; do not create or
hard-code a token in JavaScript.

Pass Phoenix options such as `hooks` in the final `LiveSocket` options object
when the application needs them. The documentation application uses the same
CSRF setup, registers its hooks, calls `connect()`, and exposes the socket for
browser-console debugging.

## Read Connect Metadata {#read-connect-metadata}

Add small browser-derived values to the `params` object when mount needs them:

```js
const params = {
  ...(csrfToken ? { _csrf_token: csrfToken } : {}),
  locale: document.documentElement.lang
}
```

Connected capabilities implement
@:apiSymbol(trait:scalive.ConnectedMetadata)`ConnectedMetadata`@:@ and expose
@:apiSymbol(def:scalive.ConnectedMetadata.connectParams)`connectParams`@:@ as
`Map[String, zio.json.ast.Json]`. Match the phase, then decode and validate the
expected shape:

```scala
val locale = ctx.connection match
  case Connection.Connected(capabilities) =>
    capabilities.connectParams.get("locale").collect {
      case Json.Str(value) => value
    }
  case Connection.Disconnected => None
```

The map is empty during disconnected HTTP rendering and contains the browser's
join parameters during the connected lifecycle. Treat every value as untrusted
client input: use the signed session or server-side state for identity,
authorization, and other security decisions. Do not set or depend on Phoenix's
internal keys such as `_mounts` and `_track_static`; their exact values and
reconnect behavior are protocol metadata, not application state.

## Load Classpath Assets {#load-classpath-assets}

For packaged applications, load the exact resources that the build placed below
a classpath prefix:

```scala
assets <- StaticAssets.load(
  StaticAssetConfig.classpath(
    resourcePrefix = "public",
    assets = Seq("app.css", "app.js")
  )
)
```

The @:apiSymbol(def:scalive.StaticAssetConfig.classpath)`StaticAssetConfig.classpath`@:@ source requires an
explicit asset list. @:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@ reads every
configured asset and fails when one is missing. The default mount path is
`/static`.

## Load A Directory {#load-a-directory}

Use @:apiSymbol(def:scalive.StaticAssetConfig.directory)`StaticAssetConfig.directory`@:@ when assets are deployed
outside the application classpath:

```scala
import java.nio.file.Paths

assets <- StaticAssets.load(
  StaticAssetConfig.directory(
    root = Paths.get("/srv/my-app/public"),
    assets = Some(Seq("app.css", "app.js"))
  )
)
```

Set `assets = None` to discover all regular files recursively below the root.
Supplying a list limits the manifest to those relative paths. Configured paths
must be normalized relative paths; empty segments, `.`, `..`, and backslashes
are rejected. Keep directory contents unchanged for the lifetime of the loaded
manifest: digests are calculated at load time while response bodies are read
from the source when requested.

## Serve Digested Paths {#serve-digested-paths}

Add @:apiSymbol(val:scalive.StaticAssets.routes)`StaticAssets.routes`@:@ to the application routes:

```scala
val routes = liveRoutes ++ assets.routes
```

The routes serve `GET` and `HEAD` below the configured mount path. Loading an
asset calculates a SHA-256 digest and inserts the full digest before its file
extension. @:apiSymbol(def:scalive.StaticAssets.path)`StaticAssets.path("app.js")`@:@ therefore
returns a URL such as `/static/app-<digest>.js`; an unknown digest returns `404`.

Current defaults serve digested responses with `public`, a one-year `max-age`,
and `immutable`, and serve original paths with `no-cache`. Both forms include a
strong `ETag` containing the digest. Set `serveOriginals = false` to make the
undigested path return `404`. Query strings do not affect asset lookup.

Use @:apiSymbol(def:scalive.StaticAssets.pathOption)`pathOption`@:@ when an optional asset may be absent.
@:apiSymbol(def:scalive.StaticAssets.path)`path`@:@ and
@:apiSymbol(def:scalive.StaticAssets.entry)`entry`@:@ throw
for a name outside the loaded manifest.

## Render Tracked Tags {#render-tracked-tags}

Pass @:apiSymbol(class:scalive.StaticAssets)`StaticAssets`@:@ to the root layout and render bundle tags in `<head>`:

```scala
headTag(
  metaTag(charset := "utf-8"),
  assets.trackedStylesheet("app.css"),
  assets.trackedScript("app.js", defer := true, typ := "text/javascript")
)
```

The tracked helpers @:apiSymbol(def:scalive.StaticAssets.trackedStylesheet)`StaticAssets.trackedStylesheet`@:@ and
also @:apiSymbol(def:scalive.StaticAssets.trackedScript)`StaticAssets.trackedScript`@:@ use the digested URL and
add `phx-track-static`. The untracked
@:apiSymbol(def:scalive.StaticAssets.stylesheet)`stylesheet`@:@ and
@:apiSymbol(def:scalive.StaticAssets.script)`script`@:@ helpers still use
digested URLs but omit that Phoenix marker. Use tracked helpers for the
application bundles whose change should be visible to the LiveView client.

Connected capabilities expose
@:apiSymbol(def:scalive.ConnectedMetadata.staticChanged)`staticChanged`@:@ for
reacting to that tracking result, commonly by replacing stale connected state or
initiating a full reload. No connected metadata exists during disconnected
rendering. On a routed root socket join, it is `true` when the client's non-empty list
of tracked URLs differs from the server-rendered list; query strings, fragments,
and URL origins are ignored during comparison. Missing, malformed, or empty
client tracking metadata yields `false`, and the result remains stable for that
socket lifecycle. Therefore use it as a deployment-change hint, not proof that
assets loaded successfully or as a security signal.

The complete root layout and startup wiring are available in the
[quick start](../learn/quick-start.md#add-routes-and-layout).

## Related Tasks {#related-tasks}

- Place bundle tags in the document shell with [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#prerequisites).
- Diagnose a page that renders but never connects in [Troubleshooting](troubleshooting.md#diagnose-missing-assets).
- Verify the real client connection with [Testing LiveViews](testing.md#test-in-a-browser).
