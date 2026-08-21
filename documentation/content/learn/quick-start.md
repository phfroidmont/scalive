{%
title = "Quick start"
description = "Create and run a standalone Scalive counter with Mill and the Phoenix LiveView client."
order = 1
section = learn
%}

## Before You Begin {#before-you-begin}

Install Java, Mill, and Node.js with npm. This quick start uses Scala `3.8.3`,
Mill, and the `dev.scalive::scalive:{{scaliveSnapshotVersion}}` artifact.

@:callout(warning)

Snapshots are retained by Maven Central for a limited time. The revision in the
version identifies the Scalive source used to produce that snapshot.

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
  def scalaVersion = "3.8.3"
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

The `::` in the dependency selects the Scala 3 artifact. Scalive's ZIO and ZIO
HTTP dependencies are supplied transitively.

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
    "phoenix_live_view": "1.1.28"
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
effectful transition, and `render` projects the state into typed HTML.

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
static asset routes, and listens on port `8080`. Deployed instances must set a
stable, secret `SCALIVE_TOKEN_SECRET`; the default generates a new secret when
the process starts.

## Run It {#run-it}

From the project root, run:

```bash
mill app.run
```

Open `http://localhost:8080/`. The HTTP request first produces disconnected
HTML. The client then connects to `/live`, Scalive mounts an independent
connected model, and button events travel over the socket as typed messages.

Next, read [Project anatomy](project-anatomy.md) to assign each file and runtime
step a clear owner.
