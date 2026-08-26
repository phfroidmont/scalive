{%
title = "Quick start"
description = "Create and run a standalone Scalive counter with Mill and the Phoenix LiveView client."
order = 1
section = learn
%}

## Before You Begin {#before-you-begin}

Install a JDK, Mill, and Node.js `18` or newer with npm. This quick start uses
Scala `3.8.4` and the `dev.scalive::scalive:{{scaliveSnapshotVersion}}`
artifact. Verify that the tools are available before creating the project:

```bash
java -version
mill --version
node --version
npm --version
```

Each command should print a version; `node --version` must report `v18` or
newer.

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
    ├── assets/
    │   └── js/
    │       └── app.js
    ├── src/
    │   └── quickstart/
    │       ├── CounterLiveView.scala
    │       ├── Main.scala
    │       ├── RootLayout.scala
    │       └── Routes.scala
    ├── package-lock.json
    └── package.json
```

Create `build.mill` at the project root:

```scala
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

  def packageJson = Task.Source(moduleDir / "package.json")
  def packageLock = Task.Source(moduleDir / "package-lock.json")
  def assetSources = Task.Sources(moduleDir / "assets")

  def bundle = Task {
    val workDir = Task.dest / "work"
    val publicDir = Task.dest / "public"

    os.copy(packageJson().path, workDir / "package.json", createFolders = true)
    os.copy(packageLock().path, workDir / "package-lock.json")
    assetSources().foreach(source =>
      os.copy(source.path, workDir / source.path.last)
    )
    os.proc("npm", "ci").call(cwd = workDir)
    os.proc("npm", "run", "build").call(cwd = workDir)
    os.copy(
      workDir / "dist" / "app.js",
      publicDir / "app.js",
      createFolders = true
    )
    PathRef(publicDir)
  }

  def resources = Task {
    super.resources() :+ bundle()
  }
end app
```

The `::` in the dependency selects the Scala 3 artifact. Scalive publishes and
supports exactly two Scala coordinates: `dev.scalive::scalive`, which contains
all production API, render, runtime, protocol, and transport classes, and the
optional test-support coordinate `dev.scalive::scalive-testing`. Scalive's ZIO
and ZIO HTTP dependencies are supplied transitively.

Create `app/package.json`:

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

Generate and commit the lockfile:

```bash
npm install --package-lock-only --prefix app
```

Mill uses `npm ci` to reproduce this dependency graph and places the bundled
`app.js` in the application's classpath resources.

## Connect The Browser {#connect-the-browser}

Create `app/assets/js/app.js`:

@:sourceRegion(documentation/fixtures/quick-start/assets/js/app.js, quick-start-browser)

@:apiSymbol(val:scalive.Live.router)`Live.router`@:@ mounts its socket at `/live`
by default. The server injects the CSRF meta element into the root layout's
`<head>` and binds it to a cookie. The client returns the value as `_csrf_token`
when it opens the socket. Do not create or hard-code this token in JavaScript.

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
place for the CSRF meta element and loads the tracked browser bundle.

## Start The Server {#start-the-server}

Create `app/src/quickstart/Main.scala`:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/Main.scala, quick-start-main)

The server loads the browser bundle, builds CSRF-protected Live routes, adds the
static asset routes, and listens on port `8080`. For local HTTP, this fixture
uses a fixed development-only signing-secret fallback and sets
`secureCookie = false`. Do not deploy those settings unchanged: production must
require a stable, high-entropy `SCALIVE_TOKEN_SECRET` and set
`secureCookie = true` behind HTTPS. See
[Configuration](../guides/configuration.md#current-configuration-contract)
and [Deployment](../guides/deployment.md#put-an-http-edge-in-front).

## Run It {#run-it}

From the project root, run:

```bash
mill app.run
```

Open `http://localhost:8080/`. The HTTP request first produces disconnected
HTML. The client then connects to `/live`, Scalive mounts an independent
connected model, and button events travel over the socket as typed messages.

You are done when Mill completes the npm build, the server listens on port
`8080`, and the browser shows a counter starting at `0`. Both buttons should
update it without reloading the page.

## If Something Fails {#if-something-fails}

- If Mill cannot resolve the Scalive dependency, confirm the snapshot version
  and repository above, then check [startup troubleshooting](../guides/troubleshooting.md#diagnose-startup-failures).
- If `npm ci`, bundling, or `app.js` fails, check [missing assets](../guides/troubleshooting.md#diagnose-missing-assets).
- If port `8080` is already in use, stop the other process or change the port in
  `Main.scala`. If the page loads but its buttons do not connect, check
  [socket connections](../guides/troubleshooting.md#diagnose-socket-connections).
