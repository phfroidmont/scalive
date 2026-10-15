{%
title = "Guard unsaved changes"
description = "Confirm before browser-initiated navigation while server-rendered state is dirty."
order = 51
section = guides
group = "Browser integration"
%}

## Before you start {#prerequisites}

Start with a form or editor whose model can say whether leaving would discard
work, served by `ZioHttp.routes` as in the
[quick start](../learn/quick-start.md#start-the-server).

## The guard runtime {#load-the-runtime}

The guard needs a small browser runtime. `ZioHttp.routes` serves it below
`/_scalive/assets` and adds it to every page's `<head>`, before the Phoenix
clients and the root layout's own scripts, so it is installed before
`LiveSocket` connects. Applications with a
[custom client bundle](static-assets-and-client-setup.md#build-the-client-bundle)
get it too. Do not add its JavaScript to the application's asset tree or
deployment manifest.

The runtime script is marked `phx-track-static`. A runtime URL change therefore
participates in the same static-change metadata as the application bundle. The
script is external and same-origin, so a common `script-src 'self'` policy does
not require inline-script permission.

## Render a guard {#render-a-guard}

Derive dirty state from canonical typed form values and add
@:apiSymbol(def:scalive.navigation.guardWhen)`navigation.guardWhen`@:@ to the
element which owns it:

@:sourceRegion(documentation/fixtures/guides/src/guards/NoteLiveView.scala, guards-note-form)

@:sourceRegion(documentation/fixtures/guides/src/guards/NoteLiveView.scala, guards-liveview)

Here `Notes` is the application's persistence service. Because `as` advances
the `FormValues` baseline only after that effect succeeds, a failed save leaves
the guard active. `mount` builds the initial baseline from the values of the
initial form. A reset or deliberate discard should likewise replace the form
from that baseline. For screens that need reset and stale-save handling
together, use `NoteForm.Definition.workflow(form)` and derive dirty state from
`workflow.isDirty`.

The marker is absent while clean and contains the confirmation message while
dirty. A blank message is rejected. If several guards are active, the first one
in document order supplies the page-level message; prefer one combined guard
when several editors belong to the same page.

## Understand the coverage {#coverage}

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

## Account for server timing {#server-timing}

The dirty signal is server-rendered. A browser edit must reach the LiveView,
update the model, and apply the resulting diff before the marker becomes active.
Do not debounce the change which establishes dirty state when timely guarding
matters. There is still a round-trip window in which an immediate Back, reload,
or close can precede the marker.

Use local draft persistence or autosave when that window is unacceptable. The
framework does not infer dirty state from arbitrary input events because it
cannot know the saved baseline, whether a submit succeeded, or which edits are
safe to discard.

## Related tasks {#related-tasks}

- Build the model and validation flow with [Typed forms and validation](typed-forms-and-validation.md#prerequisites).
- Choose patch and navigation semantics with [Routes, parameters, and navigation](routes-and-navigation.md#prerequisites).
- Test native dialogs and history traversal with [Testing LiveViews](testing.md#test-in-a-browser).
