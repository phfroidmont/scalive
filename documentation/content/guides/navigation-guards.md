{%
title = "Guard unsaved changes"
description = "Confirm before browser-initiated navigation while server-rendered state is dirty."
order = 51
section = guides
group = "Browser integration"
%}

## Before You Start {#prerequisites}

Start with a form or editor whose model can say whether leaving would discard
work. Scalive currently targets the pinned Phoenix LiveView `v1.2.10` client.
The application must also use the complete root-layout and asset-route wiring
from the [quick start](../learn/quick-start.md#add-routes-and-layout); review
[Client setup and static assets](static-assets-and-client-setup.md#build-the-client-bundle)
when the application owns a custom browser bundle or asset tree.

The navigation guard is an optional framework asset loaded separately from the
application bundle. Do not add its JavaScript to the application's asset tree or
deployment manifest; the dedicated loader serves it independently.

## Load The Guard Runtime {#load-the-runtime}

Load @:apiSymbol(object:scalive.NavigationGuardAssets)`NavigationGuardAssets`@:@ at startup, pass it to the root
layout, and add its routes alongside the application assets:

```scala
for
  appAssets   <- StaticAssets.load(appAssetConfig)
  guardAssets <- NavigationGuardAssets.load()
  rootLayout   = RootLayout(appAssets, guardAssets)
  application  = Live.router.withRootLayout(rootLayout)(liveRoutes*)
  routes       =
    ZioHttp.routes(application, security) ++
      appAssets.routes ++
      guardAssets.routes
  _ <- Server.serve(routes)
yield ()
```

The guard runtime defaults to its own `/_scalive/assets` mount. Pass another
`zio.http.Path` to @:apiSymbol(def:scalive.NavigationGuardAssets.load)`NavigationGuardAssets.load`@:@ when that path conflicts with
application routing. The loaded value validates the packaged JavaScript at
startup and serves it below an immutable asset-set version.

## Install The Tracked Script {#install-the-script}

Render @:apiSymbol(def:scalive.NavigationGuardAssets.script)`NavigationGuardAssets.script`@:@ before the application bundle. Both scripts are
deferred, so document order installs the guard listener before `LiveSocket`
connects:

```scala
final class RootLayout(
  appAssets: StaticAssets,
  guardAssets: NavigationGuardAssets
) extends LiveRootLayout[Any, Any]:
  def key(ctx: LiveRootLayoutContext[Any, Any]) = "application-root"

  def render[Msg](
    content: HtmlElement[Msg],
    pageTitle: Option[String],
    ctx: LiveRootLayoutContext[Any, Any]
  ) =
    htmlRootTag(
      headTag(
        guardAssets.script,
        appAssets.trackedScript("app.js", defer := true),
        liveTitle(pageTitle, default = "Application")
      ),
      bodyTag(content)
    )
```

The runtime script is marked `phx-track-static`. A runtime URL change therefore
participates in the same static-change metadata as the application bundle. The
script is external and same-origin, so a common `script-src 'self'` policy does
not require inline-script permission.

## Render A Guard {#render-a-guard}

Derive dirty state from the model and add @:apiSymbol(def:scalive.navigation.guardWhen)`navigation.guardWhen`@:@ to the
element which owns it:

```scala
final case class Model(note: String, savedNote: String):
  def isDirty: Boolean = note != savedNote

enum Msg:
  case Validate(event: FormEvent[FormData])
  case Save

def handleMessage(model: Model, ctx: MessageContext) =
  case Msg.Validate(event) =>
    val note = event.raw.string("note").getOrElse("")
    ZIO.succeed(model.copy(note = note))
  case Msg.Save =>
    saveNote(model.note).as(model.copy(savedNote = model.note))

override def view(model: Signal[Model]) =
  val note = model.map(_.note)

  form(
    idAttr := "note-form",
    navigation.guardWhen(
      dirty = model.map(_.isDirty),
      message = "Discard unsaved changes?"
    ),
    on.change.form(FormCodec.formData)(Msg.Validate(_)),
    on.submit(Msg.Save),
    label(forId := "note-input", "Note"),
    input(idAttr := "note-input", nameAttr := "note", value := note),
    button(typ := "submit", "Save")
  )
```

Here `saveNote: String => Task[Unit]` is the application's persistence operation.
Because `as` updates `savedNote` only after that effect succeeds, a failed save
leaves the guard active. A reset or deliberate discard should likewise update
the model back to its clean baseline.

The marker is absent while clean and contains the confirmation message while
dirty. A blank message is rejected. If several guards are active, the first one
in document order supplies the page-level message; prefer one combined guard
when several editors belong to the same page.

## Understand The Coverage {#coverage}

The runtime builds on Phoenix's cancelable `phx:before-navigate` event and the
browser's `beforeunload` event:

| Trigger | Behavior |
| --- | --- |
| `link.pushNavigate` and `link.replaceNavigate` | Uses the configured confirmation message before browser-initiated live navigation. |
| `link.pushPatch` and `link.replacePatch` | Uses the configured confirmation message before the live patch. |
| Same-document LiveView Back and Forward | Uses the configured message; Phoenix restores the current history entry when rejected. |
| Cross-document Back and Forward, reload, ordinary navigation, and tab close | Requests the browser's generic unload confirmation. |
| `ctx.nav`, redirects, and server-issued patches | Bypass the configured confirmation because the server lifecycle may already have committed or ended. A resulting document unload may still request the browser's generic confirmation. |
| `JS.pushNavigate`, `JS.replaceNavigate`, `JS.pushPatch`, and `JS.replacePatch` | Bypass the configured confirmation. A resulting document unload may still request the browser's generic confirmation. |

Crossing an incompatible live-session or root-layout boundary may make an
accepted live link fall back to an HTTP request. The runtime carries that one
acceptance into a link-initiated fallback for up to ten seconds so an ordinary
fallback does not prompt twice. After that bound, unload protection is restored;
a stalled fallback may therefore show the browser's generic confirmation as a
second safety check. A LiveView Back or Forward traversal is reported only after
browser history has moved; if that traversal subsequently requires an HTTP
fallback, the browser may likewise show its generic unload confirmation.

`beforeunload` is attached only while a marker is active, which avoids its
Firefox back/forward-cache cost on clean pages. Browsers control the unload
dialog text, require prior user interaction, and may suppress it. Mobile app
termination may not dispatch `beforeunload` at all. Persist important drafts;
the guard is not a durable-storage mechanism.

## Account For Server Timing {#server-timing}

The dirty signal is server-rendered. A browser edit must reach the LiveView,
update the model, and apply the resulting diff before the marker becomes active.
Do not debounce the change which establishes dirty state when timely guarding
matters. There is still a round-trip window in which an immediate Back, reload,
or close can precede the marker.

Use local draft persistence or autosave when that window is unacceptable. The
framework does not infer dirty state from arbitrary input events because it
cannot know the saved baseline, whether a submit succeeded, or which edits are
safe to discard.

## Related Tasks {#related-tasks}

- Build the model and validation flow with [Typed forms and validation](typed-forms-and-validation.md#prerequisites).
- Choose patch and navigation semantics with [Routes, parameters, and navigation](routes-and-navigation.md#prerequisites).
- Test native dialogs and history traversal with [Testing LiveViews](testing.md#test-in-a-browser).
