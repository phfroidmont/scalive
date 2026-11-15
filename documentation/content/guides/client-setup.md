{%
title = "Client setup"
description = "Understand how Scalive loads and connects the Phoenix LiveView client, pass LiveSocket options, build a custom client bundle, and read connect metadata."
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
connection. It then covers what the quick start leaves out: a custom client
bundle and connect metadata. If the application imports browser packages, use
the [custom client bundle](#build-the-client-bundle) instead of the packaged
clients.

Serving the application's own stylesheets, scripts, fonts, and images is the
subject of [Static assets](static-assets.md).

## Load the packaged clients {#load-the-packaged-clients}

`ZioHttp.routes` serves Scalive's own browser files below `/_scalive`, or the
router's [client path](configuration.md#configure-routes-and-assets), and adds
them to every page's `<head>`, as it adds the CSRF `<meta>` element. With the
default @:apiSymbol(enum:scalive.LiveClient)`LiveClient.Packaged`@:@, it adds:

- the [navigation-guard](navigation-guards.md#prerequisites) runtime and the
  upstream Phoenix `1.8.15` and Phoenix LiveView `1.2.12` browser-global builds,
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

## Build a custom client bundle {#build-the-client-bundle}

Use a custom browser build when the application needs package imports,
TypeScript, source maps, or other build-owned output. Install the same supported
Phoenix packages and bundle an application entry point into a single file:

```json
{
  "private": true,
  "type": "module",
  "scripts": {
    "build": "esbuild assets/js/app.js --bundle --platform=browser --format=iife --target=es2020 --outfile=dist/app.js"
  },
  "dependencies": {
    "phoenix": "1.8.15",
    "phoenix_live_view": "1.2.12"
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

This build does not put content hashes in file names, so load its output as an
[asset tree](static-assets.md#load-classpath-assets), which serves every file
below the prefix from URLs that Scalive versions. Tell Scalive that the bundle
includes the clients, so that pages do not also load and connect the packaged
copies:

```scala fragment
assets     <- StaticAssets.tree(StaticAssetRoot.classpath("public"))
liveRoutes <- ZioHttp.routes(application, config, client = LiveClient.Custom)
routes = liveRoutes ++ assets.routes
```

Load the script from the root layout's `<head>` with a
[tracked tag](static-assets.md#render-tracked-tags):

```scala fragment
assets.trackedScript("app.js", defer := true, typ := "text/javascript")
```

With `LiveClient.Custom`, Scalive still serves the navigation-guard runtime and
adds it before the root layout's first script, but adds no Phoenix client and
no connect script. The bundle reads the CSRF token from the `csrf-token` meta
element and connects to the router's socket path, `/live` by default.

This portable task requires Node.js and npm at build time but not at runtime.
Keeping the whole output tree preserves workers, CSS, fonts, source maps, and
relative references. Adjust the build tool integration when the application does
not use Mill, while preserving the same complete resource tree.

The `iife` format emits one file and cannot split code, which is enough until a
page should not download a library it does not use. A build that emits chunks
uses the `esm` format with content-hashed names and a deployment manifest; see
[Build a split bundle](static-assets.md#build-a-split-bundle).

## Read connect metadata {#read-connect-metadata}

Add small browser-derived values to the `params` option when mount needs them:

```js
window.liveSocketOptions = {
  params: { locale: document.documentElement.lang },
}
```

A [custom bundle](#build-the-client-bundle) adds the same key next to
`_csrf_token` in the `params` it passes to `LiveSocket`.

Read them in mount through a typed
@:apiSymbol(class:scalive.ConnectParam)`ConnectParam`@:@ key, declared once like an
`AsyncKey`:

@:sourceRegion(documentation/fixtures/guides/src/assets/LocaleLiveView.scala, assets-connect-params)

Connected capabilities implement
@:apiSymbol(trait:scalive.ConnectedMetadata)`ConnectedMetadata`@:@, whose
@:apiSymbol(def:scalive.ConnectedMetadata.connectParam)`connectParam`@:@ decodes the
value with the key's zio-json decoder. It returns `None` when the browser did not
send the key or the value does not decode, so a browser time zone, for example,
is `ConnectParam[java.time.ZoneId]("timeZone")`. No connected metadata exists
during disconnected HTTP rendering, so the example falls back to `None`. Values
are untrusted: use the signed session or server-side state for identity,
authorization, and other security decisions. Names starting with `_` are
reserved for Phoenix's own keys and rejected. Typed server-derived connect info
such as peer, headers, or user agent remains partial.

@:apiSymbol(def:scalive.ConnectedMetadata.join)`join`@:@ tells a page view's first join,
`ConnectedJoin.First`, from a rejoin after the connection dropped or the
LiveView crashed, `ConnectedJoin.Rejoin`. Live navigation to a view is a first
join. Use it to record a visit once or to skip an entrance animation; the
browser reports it, so never use it for security.

Connected capabilities also expose
@:apiSymbol(def:scalive.ConnectedMetadata.staticChanged)`staticChanged`@:@, the
result of [tracked tag](static-assets.md#render-tracked-tags) comparison. Ordinary layouts read
the same value from their context, which makes them the place to
[offer a reload after a deployment](layouts.md#offer-a-reload-after-a-deployment)
for every route at once. When a routed root LiveView
joins, the running server renders its root layout again and the result is `true`
when one of that document's tracked URLs, including the scripts Scalive adds, is
missing from the client's non-empty list. A page loaded before a deployment that
changed an asset URL therefore reports `true` when it reconnects. Query strings,
fragments, and URL origins are ignored during comparison, and tracked tags
inside LiveView content are not compared. Missing, malformed, or empty client
tracking metadata yields `false`, and the result remains stable for that
lifecycle. Therefore use
it as a deployment-change hint, not proof that assets loaded successfully or as
a security signal.

## Related tasks {#related-tasks}

- Serve the application's stylesheets, scripts, and other files with [Static assets](static-assets.md#choose-an-asset-model).
- Register hooks and keep a heavy one off the pages that do not use it with [Browser integration](browser-integration.md#load-a-heavy-hook-on-demand).
- Place bundle tags in the document shell with [Layouts](layouts.md#install-root-and-ordinary-layouts).
- Load the optional confirmation runtime with [Guard unsaved changes](navigation-guards.md#prerequisites).
- Diagnose a page that renders but never connects in [Troubleshooting](troubleshooting.md#diagnose-missing-assets).
- Verify the real client connection with [Testing LiveViews](testing.md#test-in-a-browser).
