{%
title = "Client setup and static assets"
description = "Understand how Scalive loads and connects the Phoenix LiveView client, pass LiveSocket options, serve versioned application assets, and use deployment manifests."
order = 2
section = guides
group = "Setup and foundations"
%}

## Before you start {#prerequisites}

Start with a Scalive application such as the one in the
[quick start](../learn/quick-start.md). Its pages load the Phoenix and Phoenix
LiveView browser clients packaged in the Scalive artifact and connect them, with
no JavaScript or build of its own.

This guide explains that contract and how to pass options such as hooks to the
connection. It then covers what the quick start leaves out: application assets,
assets outside the classpath, tracked tags, a custom client bundle, connect
metadata, and deployment manifests. If the application imports browser
packages, use the [custom client bundle](#build-the-client-bundle) instead of
the packaged clients.

## Load the packaged clients {#load-the-packaged-clients}

`ZioHttp.routes` serves Scalive's own browser files below `/_scalive` and adds
them to every page's `<head>`, as it adds the CSRF `<meta>` element. With the
default @:apiSymbol(enum:scalive.LiveClient)`LiveClient.Packaged`@:@, it adds:

- the [navigation-guard](navigation-guards.md#prerequisites) runtime and the
  upstream Phoenix `1.8.9` and Phoenix LiveView `1.2.10` browser-global builds,
  just before the root layout's first `<script>`, or at the end of `<head>`
  when it has none;
- the CSRF `<meta>` element and a script that connects the clients, at the end
  of `<head>`.

Every script is deferred, tracked, and same-origin. Deferred classic scripts run
in document order, so the root layout's own scripts run after the clients,
and can use the `Phoenix` and `LiveView` globals, but before the connection.
Because the scripts are tracked, upgrading Scalive changes their URLs and
reports [`staticChanged`](#read-connect-metadata) to connected views.

## Connect LiveSocket {#connect-live-socket}

The connect script creates a `LiveSocket` with the router's socket path, `/live`
unless the router sets another, and the CSRF token from the page. It then
connects and exposes it as `window.liveSocket` for browser-console debugging.
Do not create or hard-code a CSRF token in JavaScript.

To pass `LiveSocket` options such as `hooks`, `uploaders`, or connection tuning,
set `window.liveSocketOptions` in a script the root layout renders:

```js
window.liveSocketOptions = {
  hooks: { BrowserInterop: createBrowserInteropHook() },
}
```

Set it when the script runs, not later in a callback: the connect script reads
it once. Scalive adds `_csrf_token` to the `params` option, whether it is an
object or a function. To send your own values to mount, add them to `params` as
shown in [Read connect metadata](#read-connect-metadata).

If `window.liveSocket` already exists when the connect script runs, it logs a
warning and does not connect. An application that connects its own
`LiveSocket` passes [`LiveClient.Custom`](#build-the-client-bundle) instead.

The browser sets the WebSocket `Origin` header itself, so do not add it to
`params`. List the page's origin in the server's
[WebSocket origin allowlist](configuration.md#admit-websocket-origins).

## Load a classpath tree {#load-classpath-assets}

Load application assets from the classpath when the build packages them with
the application. The [layouts guide's](layouts.md#install-root-and-ordinary-layouts)
root layout loads a single stylesheet this way. For a larger output, list every
file below the classpath prefix:

```scala fragment
assets <- StaticAssets.load(
  StaticAssetConfig.classpath(
    resourcePrefix = "public",
    assets = Seq("app.css", "app.js")
  )
)
```

The @:apiSymbol(def:scalive.StaticAssetConfig.classpath)`StaticAssetConfig.classpath`@:@
source requires an explicit asset list. Include every file in the output tree,
not only the top-level script and stylesheet, so relative imports and URLs
remain available.
@:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@ reads every
configured asset and fails when one is missing. The default mount path is
`/static`, and original unversioned URLs are disabled by default.

## Load a directory tree {#load-a-directory}

Use @:apiSymbol(def:scalive.StaticAssetConfig.directory)`StaticAssetConfig.directory`@:@
when assets are deployed outside the application classpath:

```scala fragment
import java.nio.file.Paths

assets <- StaticAssets.load(
  StaticAssetConfig.directory(
    root = Paths.get("/srv/my-app/public"),
    assets = Some(Seq("app.css", "app.js"))
  )
)
```

Set `assets = None` to discover all regular files recursively below the root.
Supplying a list limits the loaded tree to those relative paths. Configured
paths must be normalized relative paths; empty segments, `.`, `..`, and
backslashes are rejected, and symlinks cannot escape the configured root. The
mount path and originals defaults match the classpath tree.

## Understand versioned paths {#serve-versioned-paths}

Classpath and directory trees serve every file below one version namespace, so
render URLs from the loaded `StaticAssets` value rather than hard-coding them.

Loading a tree computes one SHA-256 version for the complete asset set.
Filenames and relative directories remain unchanged beneath that namespace:
@:apiSymbol(def:scalive.StaticAssets.path)`StaticAssets.path("app.js")`@:@
returns a URL such as `/static/<asset-set-digest>/app.js`. A tree entry such as
`chunks/editor.js` is served at `/static/<asset-set-digest>/chunks/editor.js`.
Relative JavaScript imports, worker URLs, CSS URLs, fonts, and source maps
therefore resolve within the same versioned namespace without rewriting file
contents or inserting a digest into each filename.

Versioned responses default to `public`, a one-year `max-age`, and `immutable`.
Their digests are pinned when assets load. An in-place file mutation makes its
immutable URL return `404` until the assets are loaded again. Original paths
return `404` by default; set `serveOriginals = true` only when unversioned
access is required. Originals serve current bytes with a current `ETag` and
`no-cache`.

## Add routes and resolve paths {#add-static-routes-and-resolve-paths}

Add @:apiSymbol(val:scalive.StaticAssets.routes)`StaticAssets.routes`@:@ to the
server routes next to the Live routes:

```scala fragment
liveRoutes <- ZioHttp.routes(application, config)
routes = liveRoutes ++ assets.routes
```

The routes serve `GET` and `HEAD` below the configured mount path, and query
strings do not affect lookup.

In a classpath or directory tree, `path` resolves a tree-relative name below the
asset-set version namespace. With a
[deployment manifest](#load-a-deployment-manifest), it resolves a logical alias
to its manifest-defined final path.

Use @:apiSymbol(def:scalive.StaticAssets.pathOption)`pathOption`@:@ when an
optional asset may be absent.
@:apiSymbol(def:scalive.StaticAssets.path)`path`@:@ and
@:apiSymbol(def:scalive.StaticAssets.entry)`entry`@:@ throw for a name outside
the loaded asset description. The entry's `cachePolicy` describes its versioned
path or manifest-defined final path, not an optional revalidating original.

@:apiSymbol(class:scalive.StaticAssetCache)`StaticAssetCache`@:@ exposes the
`immutable` and `revalidating` header policies. Replace it through
`config.copy(cache = ...)` when the application requires different headers.

## Render tracked tags {#render-tracked-tags}

Render the application's top-level script and stylesheet in the root layout's
`<head>`:

```scala fragment
headTag(
  metaTag(charset := "utf-8"),
  assets.trackedStylesheet("app.css"),
  assets.trackedScript("app.js", defer := true, typ := "text/javascript")
)
```

The tracked helpers
@:apiSymbol(def:scalive.StaticAssets.trackedStylesheet)`StaticAssets.trackedStylesheet`@:@
and @:apiSymbol(def:scalive.StaticAssets.trackedScript)`StaticAssets.trackedScript`@:@
use the versioned or manifest-defined final URL and add `phx-track-static`. The
untracked @:apiSymbol(def:scalive.StaticAssets.stylesheet)`stylesheet`@:@ and
@:apiSymbol(def:scalive.StaticAssets.script)`script`@:@ helpers still use those
URLs but omit that Phoenix marker.

Keep the top-level script and stylesheet selection explicit in the root layout;
inventory entries do not automatically become tags. Use tracked helpers for
application bundles whose change should be visible to the LiveView client. A
connected view reads the tracking result through
[`staticChanged`](#read-connect-metadata).

## Build a custom client bundle {#build-the-client-bundle}

Use a custom browser build when the application needs package imports,
TypeScript, source maps, generated chunks, or other build-owned output. Install
the same supported Phoenix packages and bundle an application entry point:

```json
{
  "private": true,
  "type": "module",
  "scripts": {
    "build": "esbuild assets/js/app.js --bundle --platform=browser --format=iife --target=es2020 --outfile=dist/app.js"
  },
  "dependencies": {
    "phoenix": "1.8.9",
    "phoenix_live_view": "1.2.10"
  },
  "devDependencies": {
    "esbuild": "0.28.1"
  }
}
```

The entry point imports, configures, and connects the clients itself:

```js
import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const params = csrfToken ? { _csrf_token: csrfToken } : {}

const liveSocket = new LiveSocket("/live", Socket, { params })
liveSocket.connect()
```

Generate and commit `package-lock.json`. Add the following members to the
application's `ScalaModule` to track the npm inputs, build in Mill's task output,
and add the complete `dist` tree below the `public` classpath prefix:

```scala mill
def packageJson = Task.Source(moduleDir / "package.json")
def packageLock = Task.Source(moduleDir / "package-lock.json")
def assetSources = Task.Sources(moduleDir / "assets")

def bundle = Task {
  val workDir = Task.dest / "work"
  val resourceRoot = Task.dest / "resources"

  os.copy(packageJson().path, workDir / "package.json", createFolders = true)
  os.copy(packageLock().path, workDir / "package-lock.json")
  assetSources().foreach(source =>
    os.copy(source.path, workDir / source.path.last)
  )

  os.proc("npm", "ci").call(cwd = workDir)
  os.proc("npm", "run", "build").call(cwd = workDir)
  os.copy(workDir / "dist", resourceRoot / "public", createFolders = true)

  PathRef(resourceRoot)
}

def resources = Task {
  super.resources() :+ bundle()
}
```

Load the result as a [classpath tree](#load-classpath-assets) that lists every
output file. When the build also writes a deployment manifest, load that
instead; see [Choose an asset model](#choose-an-asset-model).

Tell Scalive that the bundle includes the clients, so that pages do not also
load and connect the packaged copies:

```scala fragment
liveRoutes <- ZioHttp.routes(application, config, client = LiveClient.Custom)
routes = liveRoutes ++ assets.routes
```

With `LiveClient.Custom`, Scalive still serves the navigation-guard runtime and
adds it before the root layout's first script, but adds no Phoenix client and
no connect script. The bundle reads the CSRF token from the `csrf-token` meta
element and connects to the router's socket path, `/live` by default.

This portable task requires Node.js and npm at build time but not at runtime.
Keeping the whole output tree preserves chunks, workers, CSS, fonts, source
maps, and relative references. Adjust the build tool integration when the
application does not use Mill, while preserving the same complete resource tree.

## Read connect metadata {#read-connect-metadata}

Add small browser-derived values to the `params` option when mount needs them:

```js
window.liveSocketOptions = {
  params: { locale: document.documentElement.lang },
}
```

A [custom bundle](#build-the-client-bundle) adds the same key next to
`_csrf_token` in the `params` it passes to `LiveSocket`.

Read them in mount. Match the connection phase, then decode and validate the
expected shape:

@:sourceRegion(documentation/fixtures/guides/src/assets/LocaleLiveView.scala, assets-connect-params)

Connected capabilities implement
@:apiSymbol(trait:scalive.ConnectedMetadata)`ConnectedMetadata`@:@ and expose
@:apiSymbol(def:scalive.ConnectedMetadata.connectParams)`connectParams`@:@ as
`Map[String, zio.json.ast.Json]`. No connected metadata exists during
disconnected HTTP rendering, so the example falls back to `None`. During the
connected lifecycle, the map contains all browser join parameters as untrusted
JSON. Decode only application-owned keys and use the signed session or
server-side state for identity, authorization, and other security decisions.
Typed server-derived connect info such as peer, headers, or user agent remains
partial. Do not set or depend on Phoenix's internal keys such as `_mounts` and
`_track_static`; their exact values and reconnect behavior are protocol
metadata, not an application recovery contract.

Connected capabilities also expose
@:apiSymbol(def:scalive.ConnectedMetadata.staticChanged)`staticChanged`@:@, the
result of [tracked tag](#render-tracked-tags) comparison. Use it to replace
stale connected state or to initiate a full reload. When a routed root LiveView
joins, it is `true` when the client's non-empty list of tracked URLs differs from
the server-rendered list; query strings, fragments, and URL origins are ignored
during comparison. Missing, malformed, or empty client tracking metadata yields
`false`, and the result remains stable for that lifecycle. Therefore use
it as a deployment-change hint, not proof that assets loaded successfully or as
a security signal.

## Choose an asset model {#choose-an-asset-model}

Use a classpath or directory tree unless an external build must define final
public paths or per-file cache policies. In that case, use a deployment
manifest:

| Model | Path and cache ownership | Choose it when |
| --- | --- | --- |
| Classpath or directory tree | Scalive owns one asset-set version namespace and its cache policy. | The build can preserve relative paths and package or deploy the complete output tree. |
| Deployment manifest | The external build owns exact final paths and the cache policy for each file. | The build already emits content-addressed names, generated files, chunks, or other outputs whose final paths must be preserved. |

Generated or chunked output works with either model: preserve the complete
relative tree for a classpath or directory tree, or inventory every output in a
deployment manifest. The packaged Phoenix clients use their own fixed classpath
graph independently of this choice.

## Load a deployment manifest {#load-a-deployment-manifest}

Load the manifest with
@:apiSymbol(def:scalive.StaticAssetConfig.deploymentClasspath)`StaticAssetConfig.deploymentClasspath`@:@
from a classpath prefix, or with
@:apiSymbol(def:scalive.StaticAssetConfig.deploymentDirectory)`StaticAssetConfig.deploymentDirectory`@:@
from a directory root:

```scala fragment
import java.nio.file.Paths

assets <- StaticAssets.load(
  StaticAssetConfig.deploymentClasspath(
    resourcePrefix = "public"
  )
)

// Or for files outside the classpath:
assets <- StaticAssets.load(
  StaticAssetConfig.deploymentDirectory(
    root = Paths.get("/srv/my-app/public")
  )
)
```

Both constructors default to `assets-manifest.json` and `/static`; override the
`manifest` or `mountPath` argument when needed. Routes, path lookup, and tag
helpers work as [for trees](#add-static-routes-and-resolve-paths). Version 1 of
the neutral schema is:

```json
{
  "version": 1,
  "assets": {
    "app.js": { "file": "assets/app-K3M7.js", "cache": "immutable" },
    "app.css": { "file": "assets/app-P9Q2.css", "cache": "immutable" },
    "assets/chunk-R4T8.js": { "file": "assets/chunk-R4T8.js", "cache": "immutable" },
    "robots.txt": { "file": "robots.txt", "cache": "revalidate" }
  }
}
```

Keys are logical aliases used with `path`, `script`, and `stylesheet`. Each
`file` is the exact relative source path and manifest-defined final path Scalive
serves. Include aliases for top-level entries and include every deployable file
as a `file` value. Non-entry outputs such as chunks, fonts, maps, and workers can
use their final path as their logical alias. The deployment manifest is not
served unless it is also declared as an asset.

The build adapter contract is deliberately tool-neutral. It must copy or package
the full output tree, combine the bundler's metadata with a full output
inventory, and write the version 1 neutral deployment manifest. The resulting
manifest must contain logical aliases and every deployable file. Scalive only
consumes and validates that description; it does not generate the deployment
manifest or discover the output inventory or asset graph. No bundler-specific
adapter is part of the contract.

Scalive validates and hashes every declared file at startup. It rejects missing
files, path traversal or other non-normalized paths, unsupported cache values,
and conflicting cache policies for one final file. Declare `immutable` only for
content-addressed or otherwise stable final paths, and retain old outputs while
active clients or cached pages may request them, including through dynamic
imports. By default, `immutable` responses are public with a one-year `max-age`,
while `revalidate` responses use `no-cache`; both include a strong per-file
`ETag`. An immutable file is pinned to its startup digest and returns `404`
after an in-place mutation until assets are reloaded. A `revalidate` file serves
its current bytes with a current `ETag` at its stable final path.

## Related tasks {#related-tasks}

- Place bundle tags in the document shell with [Layouts](layouts.md#install-root-and-ordinary-layouts).
- Load the optional confirmation runtime with [Guard unsaved changes](navigation-guards.md#prerequisites).
- Cache static assets behind a CDN or edge with [Deployment](deployment.md#cache-static-assets).
- Diagnose a page that renders but never connects in [Troubleshooting](troubleshooting.md#diagnose-missing-assets).
- Verify the real client connection with [Testing LiveViews](testing.md#test-in-a-browser).
