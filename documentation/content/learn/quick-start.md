{%
title = "Quick start"
description = "Create and run a standalone Scalive counter with Mill and the Phoenix LiveView client."
order = 1
section = learn
%}

## Before You Begin {#before-you-begin}

Install JDK 17 or later and the Mill launcher. This quick start uses Scala
`3.8.4`, Mill `1.1.8`, and the
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

## Create The Project {#create-the-project}

Create this project tree:

```text
scalive-quick-start/
├── build.mill
└── app/
    ├── resources/
    │   └── public/
    │       └── app.js
    ├── src/
    │   └── quickstart/
    │       ├── CounterLiveView.scala
    │       ├── Main.scala
    │       ├── RootLayout.scala
    │       └── Routes.scala
```

Create `build.mill` at the project root:

```scala unverified
//| mill-version: 1.1.8
package build

import mill.*, scalalib.*

object app extends ScalaModule:
  def scalaVersion = "3.8.4"
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

Mill automatically places files under `app/resources` on the application's
classpath. Scalive packages the Phoenix and Phoenix LiveView browser clients,
so this project needs no package manager or bundler.

## Connect The Browser {#connect-the-browser}

Create `app/resources/public/app.js`:

@:sourceRegion(documentation/fixtures/quick-start/resources/public/app.js, quick-start-browser)

@:apiSymbol(val:scalive.Live.router)`Live.router`@:@ mounts its socket at `/live`
by default. The server injects the CSRF meta element into the root layout's
`<head>` and binds it to a cookie. The client returns the value as `_csrf_token`
when it opens the socket. Do not create or hard-code this token in JavaScript.
The packaged client scripts expose the `Phoenix` and `LiveView` globals used here.

## Define The LiveView {#define-the-liveview}

Create `app/src/quickstart/CounterLiveView.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/CounterLiveView.scala, quick-start-live-view)

The `Int` is all state needed to render this interface. `Msg` lists every input
the view accepts. `mount` creates the state, `handleMessage` performs an
effectful transition, and `view` projects the state into typed HTML.

## Add Routes And Layout {#add-routes-and-layout}

Create `app/src/quickstart/Routes.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/Routes.scala, quick-start-routes)

Create `app/src/quickstart/RootLayout.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/RootLayout.scala, quick-start-root-layout)

The root layout renders the complete document. Its `<head>` gives Scalive a
place for the CSRF meta element and loads Phoenix first, Phoenix LiveView second,
and the application bootstrap last. Keep this order because `app.js` uses both
client globals.

## Start The Server {#start-the-server}

Create `app/src/quickstart/Main.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/Main.scala, quick-start-main)

The server loads Scalive's packaged clients through
@:apiSymbol(class:scalive.LiveViewClientAssets)`LiveViewClientAssets`@:@, loads
`app.js` as an ordinary classpath asset, builds CSRF-protected Live routes, adds
both asset route sets, and listens on port `8080`. The root layout renders the
asset URLs, which include a version, so the plain `/static/app.js` path returns
`404`.

The configuration signs session cookies and accepts live connections only from
pages served at `http://localhost:8080` and `http://127.0.0.1:8080`.

@:callout(warning)

These settings are for local development only. Before deploying, read the
secret from your own configuration and fail when it is missing instead of
falling back to a fixed value, set `secureCookie = true` behind HTTPS, and list
every origin that browsers use to reach the application. See
[Configuration](../guides/configuration.md#current-configuration-contract)
and [Deployment](../guides/deployment.md#put-an-http-edge-in-front).

@:@

## Run It {#run-it}

From the project root, run:

```bash
mill app.run
```

Open `http://localhost:8080/`, substituting the configured `port` value if you
changed it. The HTTP request first produces disconnected HTML. The client then
connects to `/live`, Scalive mounts an independent connected model, and button
events travel over the socket as typed messages.

You are done when Mill compiles the application, the server listens on the
configured port, and the browser shows a counter starting at `0`. Both buttons
should update it without reloading the page.

Next, [Models, messages, and effects](models-and-messages.md) adds a Reset
button and explains how the counter's state changes.

## If Something Fails {#if-something-fails}

- If Mill cannot resolve the Scalive dependency, confirm the snapshot version
  and repository above, then check [startup troubleshooting](../guides/troubleshooting.md#diagnose-startup-failures).
- If `app.js` or either packaged client script fails to load, check
  [missing assets](../guides/troubleshooting.md#diagnose-missing-assets).
- If port `8080` is already in use, stop the other process or change the single
  `port` value in `Main.scala`; the server binding and local WebSocket origins
  derive from it. Open the replacement port in the browser. If the page loads
  but its buttons do not connect, check
  [socket connections](../guides/troubleshooting.md#diagnose-socket-connections).
