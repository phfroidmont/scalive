{%
title = "Client setup and static assets"
description = "Load ordinary versioned trees or manifest-defined final paths, render tracked links, and connect the Phoenix LiveView client."
order = 2
section = guides
group = "Setup and foundations"
%}

## Before You Start {#prerequisites}

Start with a Scalive application whose routed page renders a complete root
layout, and with Node.js and npm available to build its browser bundle. The
[Quick start](../learn/quick-start.md) provides that observable baseline.

## Choose An Asset Model {#choose-an-asset-model}

Choose one model before wiring asset loading:

| Model | Path and cache ownership | Choose it when |
| --- | --- | --- |
| Ordinary classpath or directory tree | Scalive owns one asset-set version namespace and its cache policy. | The build can preserve relative paths and package or deploy the complete output tree. |
| Deployment manifest | The external build owns exact final paths and the cache policy for each file. | The build already emits content-addressed names, generated files, chunks, or other outputs whose final paths must be preserved. |

Use the ordinary versioned tree unless the external build must define final
public paths or per-file cache policies. Generated or chunked output works with
either model: preserve the complete relative tree for the ordinary model, or
inventory every output in a deployment manifest.

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
    "phoenix": "1.8.9",
    "phoenix_live_view": "1.2.10"
  },
  "devDependencies": {
    "esbuild": "0.28.1"
  }
}
```

Generate and commit `package-lock.json`. This repository's `NpmAssets` Mill
trait runs `npm ci`, runs the package's `build` script, and copies the complete
`dist` tree into a `public` resource directory. The Quick Start does not use
that trait: its bundle is a single-output baseline, and its inline Mill task
performs the equivalent complete-tree copy. Keeping the whole tree preserves
chunks, workers, CSS, fonts, source maps, and relative references.

If your repository uses `NpmAssets`, include its output in the Scala module's
resources:

```scala
object myApp extends ScalaCommon with NpmAssets:
  def moduleDeps = Seq(scalive)

  def resources = Task {
    super.resources() :+ bundle()
  }
```

The equivalent standalone setup is shown in full in the
[quick start](../learn/quick-start.md#create-the-project).

## Connect LiveSocket {#connect-live-socket}

This section is the canonical browser bootstrap and CSRF setup. Create the
browser entry point imported by the bundle:

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

The browser adds the WebSocket `Origin` header automatically from the page's
HTTP or HTTPS origin. Do not add it to `params` or attempt to set it from
JavaScript. Instead, list that page origin in the server's
[WebSocket allowlist](configuration.md#current-configuration-contract).

Pass Phoenix options such as `hooks` in the final `LiveSocket` options object
when the application needs them. Register every option before calling
`connect()`; exposing the socket on `window` is optional and useful only for
browser-console debugging.

## Load An Ordinary Classpath Tree {#load-classpath-assets}

For packaged applications using the ordinary versioned tree, load the exact
resources that the build placed below a classpath prefix:

```scala
assets <- StaticAssets.load(
  StaticAssetConfig.classpath(
    resourcePrefix = "public",
    assets = Seq("app.css", "app.js")
  )
)
```

The @:apiSymbol(def:scalive.StaticAssetConfig.classpath)`StaticAssetConfig.classpath`@:@ source requires an explicit
asset list. Include every file in the output tree, not only the top-level script
and stylesheet, so relative imports and URLs remain available.
@:apiSymbol(def:scalive.StaticAssets.load)`StaticAssets.load`@:@ reads every configured asset and fails when one is
missing. The default mount path is `/static`, and original unversioned URLs are
disabled by default.

## Load An Ordinary Directory Tree {#load-a-directory}

Use @:apiSymbol(def:scalive.StaticAssetConfig.directory)`StaticAssetConfig.directory`@:@ when assets are deployed
outside the application classpath but still use the ordinary versioned tree:

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
Supplying a list limits the loaded tree to those relative paths. Configured
paths must be normalized relative paths; empty segments, `.`, `..`, and
backslashes are rejected, and symlinks cannot escape the configured root.

## Understand Ordinary Versioned Paths {#serve-versioned-paths}

Loading an ordinary classpath or directory tree computes one SHA-256 version for
the complete asset set. Filenames and relative directories remain unchanged
beneath that namespace: @:apiSymbol(def:scalive.StaticAssets.path)`StaticAssets.path("app.js")`@:@ returns a URL such as
`/static/<asset-set-digest>/app.js`. A tree entry such as `chunks/editor.js` is
served at `/static/<asset-set-digest>/chunks/editor.js`. Relative JavaScript
imports, worker URLs, CSS URLs, fonts, and source maps therefore resolve within
the same versioned namespace without rewriting file contents or inserting a
digest into each filename.

Ordinary versioned responses default to `public`, a one-year `max-age`, and
`immutable`. Their digests are pinned when assets load. An in-place file
mutation makes its immutable URL return `404` until the assets are loaded again.
Original paths return `404` by default; set `serveOriginals = true` only when
unversioned access is required. Originals serve current bytes with a current
`ETag` and `no-cache`.

## Load A Deployment Manifest {#load-a-deployment-manifest}

Use the deployment-manifest model when an external asset build owns exact final
public paths and per-file cache policy:

```scala
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

Both constructors load a deployment manifest from either the classpath prefix or
directory root. They default to `assets-manifest.json` and `/static`; override
the `manifest` or `mountPath` argument when needed. Version 1 of the neutral
schema is:

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

## Add Routes And Resolve Paths {#add-static-routes-and-resolve-paths}

Both models expose the same route and lookup API. Add
@:apiSymbol(val:scalive.StaticAssets.routes)`StaticAssets.routes`@:@ to the application routes:

```scala
val routes = liveRoutes ++ assets.routes
```

The routes serve `GET` and `HEAD` below the configured mount path, and query
strings do not affect lookup. In an ordinary versioned tree, `path` resolves a
tree-relative name below the asset-set version namespace. With a deployment
manifest, it resolves a logical alias to its manifest-defined final path.
@:apiSymbol(class:scalive.StaticAssetCache)`StaticAssetCache`@:@ exposes the `immutable` and `revalidating` header
policies; replace it through `config.copy(cache = ...)` when the application
requires different headers.

Use @:apiSymbol(def:scalive.StaticAssets.pathOption)`pathOption`@:@ when an optional asset may be absent.
@:apiSymbol(def:scalive.StaticAssets.path)`path`@:@ and
@:apiSymbol(def:scalive.StaticAssets.entry)`entry`@:@ throw for a name outside the loaded asset description. The
entry's `cachePolicy` describes its ordinary versioned path or manifest-defined
final path, not an optional revalidating original.

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
@:apiSymbol(def:scalive.StaticAssets.trackedScript)`StaticAssets.trackedScript`@:@ use the ordinary versioned or
manifest-defined final URL and add `phx-track-static`. The untracked
@:apiSymbol(def:scalive.StaticAssets.stylesheet)`stylesheet`@:@ and
@:apiSymbol(def:scalive.StaticAssets.script)`script`@:@ helpers still use
those URLs but omit that Phoenix marker. Keep the top-level script and
stylesheet selection explicit in the root layout; inventory entries do not
automatically become tags. Use tracked helpers for application bundles whose
change should be visible to the LiveView client.

Connected capabilities expose
@:apiSymbol(def:scalive.ConnectedMetadata.staticChanged)`staticChanged`@:@ for
reacting to that tracking result, commonly by replacing stale connected state
or initiating a full reload. No connected metadata exists during disconnected
rendering. On a routed root socket join, it is `true` when the client's
non-empty list of tracked URLs differs from the server-rendered list; query
strings, fragments, and URL origins are ignored during comparison. Missing,
malformed, or empty client tracking metadata yields `false`, and the result
remains stable for that socket lifecycle. Therefore use it as a
deployment-change hint, not proof that assets loaded successfully or as a
security signal.

The complete root layout and startup wiring are available in the
[quick start](../learn/quick-start.md#add-routes-and-layout).

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

The map is empty during disconnected HTTP rendering and contains all browser
join parameters as untrusted JSON during the connected lifecycle. Decode only
application-owned keys and use the signed session or server-side state for
identity, authorization, and other security decisions. Typed server-derived
connect info such as peer, headers, or user agent remains partial. Do not set or
depend on Phoenix's internal keys such as `_mounts` and `_track_static`; their
exact values and reconnect behavior are protocol metadata, not an application
recovery contract.

## Related Tasks {#related-tasks}

- Place bundle tags in the document shell with [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#prerequisites).
- Load the optional confirmation runtime with [Guard unsaved changes](navigation-guards.md#prerequisites).
- Diagnose a page that renders but never connects in [Troubleshooting](troubleshooting.md#diagnose-missing-assets).
- Verify the real client connection with [Testing LiveViews](testing.md#test-in-a-browser).
