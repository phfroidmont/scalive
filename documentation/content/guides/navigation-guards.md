{%
title = "Guard unsaved changes"
description = "Confirm before browser-initiated navigation while server-rendered state is dirty."
order = 51
section = guides
group = "Browser integration"
%}

## Before You Start {#prerequisites}

Start with a form or editor whose model can say whether leaving would discard
work. Scalive supplies its pinned Phoenix LiveView `v1.2.10` client. The
application must also use the complete root-layout and asset-route wiring from
the [quick start](../learn/quick-start.md#add-routes-and-layout); review
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
  clientAssets <- LiveViewClientAssets.load()
  appAssets    <- StaticAssets.load(appAssetConfig)
  guardAssets  <- NavigationGuardAssets.load()
  rootLayout    = RootLayout(clientAssets, appAssets, guardAssets)
  application  = Live.router.withRootLayout(rootLayout)(liveRoutes*)
  routes       =
    ZioHttp.routes(application, security) ++
      clientAssets.routes ++
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

Render
@:apiSymbol(def:scalive.NavigationGuardAssets.script)`NavigationGuardAssets.script`@:@
before the packaged clients and application bootstrap. Every script shown below
is deferred, so document order installs the guard listener before `LiveSocket`
connects:

```scala
final class RootLayout(
  clientAssets: LiveViewClientAssets,
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
        clientAssets.phoenixScript,
        clientAssets.liveViewScript,
        appAssets.trackedScript("app.js", defer := true),
        liveTitle(pageTitle, default = "Application")
      ),
      bodyTag(content)
    )
```

The packaged client scripts may be omitted when `app.js` is a custom bundle
which already includes Phoenix and Phoenix LiveView. In either setup, keep the
guard before the script that constructs `LiveSocket`.

The runtime script is marked `phx-track-static`. A runtime URL change therefore
participates in the same static-change metadata as the application bundle. The
script is external and same-origin, so a common `script-src 'self'` policy does
not require inline-script permission.

## Render A Guard {#render-a-guard}

Derive dirty state from canonical typed form values and add
@:apiSymbol(def:scalive.navigation.guardWhen)`navigation.guardWhen`@:@ to the
element which owns it:

```scala
final case class Note(value: String)

object NoteForm:
  val Root       = FormRoot("note")
  val NoteText   = Root.text("value")
  val Definition = Root.product[Note]((NoteText,))

final case class Model(
  form: NoteForm.Definition.Form,
  baseline: NoteForm.Definition.Values
):
  def isDirty: Boolean = form.values != baseline

enum Msg:
  case Validate(event: NoteForm.Definition.Event)
  case Save(event: NoteForm.Definition.Event)

def handleMessage(model: Model, ctx: MessageContext) =
  case Msg.Validate(event) =>
    ZIO.succeed(model.copy(form = event.form))
  case Msg.Save(event) =>
    event.form.result match
      case Right(note) =>
        saveNote(note.value).as(
          model.copy(form = event.form, baseline = event.form.values)
        )
      case Left(_) =>
        ZIO.succeed(model.copy(form = event.form))

override def view(model: Signal[Model]) =
  val noteForm  = model.map(_.form)
  val noteField = noteForm.field(NoteForm.NoteText)

  form(
    idAttr := "note-form",
    navigation.guardWhen(
      dirty = model.map(_.isDirty),
      message = "Discard unsaved changes?"
    ),
    NoteForm.Definition.onChange(Msg.Validate(_)),
    NoteForm.Definition.onSubmit(Msg.Save(_)),
    label(forId := noteField.id, "Note"),
    noteField.text(noteField.validationAttributes),
    button(typ := "submit", "Save")
  )
```

Here `saveNote: String => Task[Unit]` is the application's persistence operation.
Because `as` advances the `FormValues` baseline only after that effect succeeds,
a failed save leaves the guard active. Construct the initial baseline from
`val form = NoteForm.Definition.initial(...)` and `form.values`. A reset or
deliberate discard should likewise replace the form from that baseline. For
screens that need reset and stale-save handling together, use
`NoteForm.Definition.workflow(form)` and derive dirty state from
`workflow.isDirty`.

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
