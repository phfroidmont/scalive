{%
title = "Quick start"
description = "Create and run a standalone Scalive counter with Mill and the Phoenix LiveView client."
order = 1
section = learn
%}

## Before you begin {#before-you-begin}

Install JDK 17 or later and the Mill launcher. This quick start uses Scala
`3.10.0`, Mill `1.3.0`, and the
`dev.scalive::scalive:{{scaliveSnapshotVersion}}` artifact. The project's
`build.mill` pins the Mill version, so any recent launcher downloads the right
one. Verify that both tools are available before creating the project:

```bash
java -version
mill --version
```

Each command should print a version. Node.js, npm, and a separate browser build
are not required for this project.

@:callout(warning)

The revision in the version identifies the exact Scalive source for this page.
Maven Central retains snapshots for a limited time.

@:@

## Create the project {#create-the-project}

Create this project tree:

```text
scalive-quick-start/
├── build.mill
└── app/
    └── src/
        └── quickstart/
            ├── CounterLiveView.scala
            ├── Main.scala
            └── Routes.scala
```

Create `build.mill` at the project root:

```scala mill
//| mill-version: 1.3.0
package build

import mill.*, scalalib.*

object app extends ScalaModule:
  def scalaVersion = "3.10.0"
  def mainClass = Some("quickstart.Main")

  def repositories = Seq(
    "https://central.sonatype.com/repository/maven-snapshots"
  )

  def mvnDeps = Seq(
    mvn"dev.scalive::scalive:{{scaliveSnapshotVersion}}"
  )
end app
```

The `::` in the dependency selects the Scala 3 artifact. `dev.scalive::scalive`
is the whole library, and it brings ZIO and ZIO HTTP with it. Tests add one more
artifact, `dev.scalive::scalive-testing`, in
[Testing your LiveView](testing-your-liveview.md).

Scalive packages the Phoenix and Phoenix LiveView browser clients and connects
them for you, so this project needs no JavaScript, package manager, or bundler.

## Define the LiveView {#define-the-liveview}

Create `app/src/quickstart/CounterLiveView.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/CounterLiveView.scala, quick-start-live-view)

The `Int` is all state needed to render this interface. `Msg` lists every input
the view accepts. `mount` creates the state, `handleMessage` performs an
effectful transition, and `view` projects the state into typed HTML.

## Add a route {#add-a-route}

Create `app/src/quickstart/Routes.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/Routes.scala, quick-start-routes)

`live` is the site root, `/`. Routes live in one object so views and tests can
link to them by name.

## Start the server {#start-the-server}

Create `app/src/quickstart/Main.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/Main.scala, quick-start-main)

@:apiSymbol(val:scalive.Live.router)`Live.router`@:@ maps the route to the LiveView.
@:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@ turns it into CSRF-protected
ZIO HTTP routes, and the server listens on port `8080`.

Each page is a complete HTML document. Scalive adds the Phoenix and Phoenix
LiveView clients and a script that connects them to the page's `<head>`, and
serves those files itself. The connection uses the router's socket path, `/live`,
and the CSRF token Scalive also adds to the page. To add stylesheets, a `lang`
attribute, or shared navigation, install a
[root layout](../guides/layouts.md#install-root-and-ordinary-layouts).

@:apiSymbol(def:scalive.ZioHttpConfig.local)`ZioHttpConfig.local(port)`@:@ accepts live
connections only from pages served at `http://localhost:8080` and
`http://127.0.0.1:8080`. It signs session cookies with a random secret drawn at
startup, so restarting the server makes open pages reload.

@:callout(warning)

This configuration is for local development only. Before deploying, build it
with `ZioHttpConfig(...)` from your own settings: a stable secret,
`secureCookie = true` behind HTTPS, and every origin that browsers use to reach
the application. See
[Configuration](../guides/configuration.md#current-configuration-contract)
and [Deployment](../guides/deployment.md#put-an-http-edge-in-front).

@:@

## Run it {#run-it}

From the project root, run:

```bash
mill app.run
```

Open `http://localhost:8080/`, substituting the configured `port` value if you
changed it. The HTTP request first produces disconnected HTML. The client then
connects to `/live`, Scalive mounts an independent connected model, and button
events travel over the WebSocket as typed messages.

You are done when Mill compiles the application, the server listens on the
configured port, and the browser shows a counter starting at `0`. Both buttons
should update it without reloading the page.

Next, [Models, messages, and effects](models-and-messages.md) adds a Reset
button and explains how the counter's state changes.

## If something fails {#if-something-fails}

- If Mill cannot resolve the Scalive dependency, confirm the snapshot version
  and repository above, then check [startup troubleshooting](../guides/troubleshooting.md#diagnose-startup-failures).
- If one of the browser scripts below `/_scalive` fails to load, check
  [missing assets](../guides/troubleshooting.md#diagnose-missing-assets).
- If port `8080` is already in use, stop the other process or change the single
  `port` value in `Main.scala`; the server binding and local WebSocket origins
  derive from it. Open the replacement port in the browser. If the page loads
  but its buttons do not connect, check
  [WebSocket connections](../guides/troubleshooting.md#diagnose-websocket-connections).
