{%
title = "Quick start"
description = "Run a complete minimal Scalive counter with Mill and the Phoenix LiveView client."
order = 1
section = learn
%}

## Before You Begin {#before-you-begin}

Scalive does not currently have a verified public dependency coordinate. This
quick start therefore creates an application module inside a Scalive source
checkout and depends on the repository's `scalive` module directly. It does not
claim that `phfroidmont::scalive:0.0.1` is available from a package repository.

Use the repository development environment, which supplies Mill, Java, and npm:

```bash
git clone https://github.com/phfroidmont/scalive.git
cd scalive
nix develop
```

## Create The Project {#create-the-project}

Create this application tree at the repository root:

```text
quickStart/
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

`package-lock.json` is generated from the complete `package.json` below. Keep it
in source control because the repository's Mill asset module uses `npm ci`.

Append this complete module definition to the checkout's existing `build.mill`:

```scala
object quickStart extends ScalaCommon with NpmAssets:
  def moduleDeps = Seq(scalive)

  override def bundleOutputs = Seq("app.js")

  def resources = Task {
    super.resources() :+ bundle()
  }
```

This reuses the checkout's current Scala version, compiler settings, npm asset
tasks, and local `scalive` module. It is intentionally a source dependency, not
an external Maven dependency.

Create `quickStart/package.json`:

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

Generate the lockfile:

```bash
npm install --package-lock-only --prefix quickStart
```

Tailwind is not needed for this minimal application. esbuild only bundles the
LiveView client into the classpath asset served by Scalive.

## Connect The Browser {#connect-the-browser}

Create `quickStart/assets/js/app.js`:

```js
import { Socket } from "phoenix"
import { LiveSocket } from "phoenix_live_view"

const csrfToken = document.querySelector("meta[name='csrf-token']")?.getAttribute("content")
const params = csrfToken ? { _csrf_token: csrfToken } : {}

const liveSocket = new LiveSocket("/live", Socket, { params })
liveSocket.connect()

window.liveSocket = liveSocket
```

`Live.router` mounts its socket at `/live` by default. The server injects the
`csrf-token` meta element into the root layout's `<head>` and binds it to a
cookie; the client returns the value as `_csrf_token` when it opens the socket.
Do not hard-code or generate this token in JavaScript.

## Define The LiveView {#define-the-liveview}

Create `quickStart/src/quickstart/CounterLiveView.scala`:

```scala
package quickstart

import zio.ZIO

import scalive.*

final class CounterLiveView extends LiveView[CounterLiveView.Msg, Int]:
  import CounterLiveView.Msg

  def mount(ctx: MountContext): LiveIO[Int] =
    ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg.Decrement => ZIO.succeed(model - 1)
    case Msg.Increment => ZIO.succeed(model + 1)

  def render(model: Int): HtmlElement[Msg] =
    mainTag(
      h1("Scalive counter"),
      button(typ := "button", on.click(Msg.Decrement), "Decrease"),
      outputTag(aria.live := "polite", model.toString),
      button(typ := "button", on.click(Msg.Increment), "Increase")
    )
end CounterLiveView

object CounterLiveView:
  enum Msg:
    case Decrement, Increment
```

The model is the current `Int`. Browser events decode directly to `Msg`, and a
successful handler result becomes the next model.

## Add Routes And Layout {#add-routes-and-layout}

Create `quickStart/src/quickstart/Routes.scala`:

```scala
package quickstart

import scalive.*

object Routes:
  val home = live
```

Create `quickStart/src/quickstart/RootLayout.scala`:

```scala
package quickstart

import scalive.*

final class RootLayout(assets: StaticAssets) extends LiveRootLayout[Any, Any]:
  def key(ctx: LiveLayoutContext[Any, Any]): String = "quick-start-root"

  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveLayoutContext[Any, Any]
  ): HtmlElement[Msg] =
    htmlRootTag(
      lang := "en",
      headTag(
        metaTag(charset := "utf-8"),
        metaTag(nameAttr := "viewport", contentAttr := "width=device-width, initial-scale=1"),
        liveTitle(pageTitle, default = "Scalive quick start"),
        assets.trackedScript("app.js", defer := true, typ := "text/javascript")
      ),
      bodyTag(content)
    )
end RootLayout
```

The root layout must render a complete HTML document with a `<head>`. That gives
Scalive a place to inject its CSRF meta element and gives the browser the bundled
client script.

## Start The Server {#start-the-server}

Create `quickStart/src/quickstart/Main.scala`:

```scala
package quickstart

import zio.*
import zio.http.Server

import scalive.*

object Main extends ZIOAppDefault:
  override val run =
    for
      assets <- StaticAssets.load(StaticAssetConfig.classpath("public", Seq("app.js")))
      security = LiveSecurity(TokenConfig.default)
      liveRoutes = Live.router
                     .withSecurity(security)
                     .withRootLayout(RootLayout(assets))(
                       Routes.home -> CounterLiveView()
                     )
      routes = liveRoutes ++ assets.routes
      _ <- Server.serve(routes).provide(Server.defaultWithPort(8080))
    yield ()
end Main
```

The server loads the bundled classpath asset, builds CSRF-protected Live routes,
adds the static asset routes, and listens on port 8080. For deployed instances,
set a stable, secret `SCALIVE_TOKEN_SECRET`; the default generates a new secret
when the process starts.

## Run It {#run-it}

From the repository root, run:

```bash
mill quickStart.run
```

Open `http://localhost:8080/`. The first request performs a disconnected HTML
render. The bundled client then connects to `/live`, mounts a connected
LiveView, and sends typed button messages over the socket.

Next, read [Project anatomy](project-anatomy.md) to understand why each file has
one distinct responsibility.
