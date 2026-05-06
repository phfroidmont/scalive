# E2E Fixture Parity Gaps

This document tracks local Scalive E2E fixtures that currently make upstream Phoenix LiveView browser tests pass without exercising the same behavior as the upstream fixture.

The problem is not that the upstream Playwright harness was changed. The problem is that some Scalive fixture implementations are too shallow, so a green run can overstate compatibility.

Do not claim full Phoenix LiveView `v1.1.28` upstream browser parity until every gap below is closed or explicitly reclassified with evidence.

## Upstream Target

| Item | Value |
| --- | --- |
| Phoenix LiveView version | `v1.1.28` |
| Pinned upstream revision | `df3e88c0abb8837c484f4cef033ff2490274af28` |
| Upstream test root | `.e2e-upstream/phoenix_live_view/<rev>/test/e2e` |
| Local Scalive fixtures | `e2eApp/src` |
| Upstream runner | `./scripts/e2e-run-upstream.sh` |

## Status Legend

| Status | Meaning |
| --- | --- |
| Gap | Local fixture is known to dodge the upstream behavior. |
| Fix planned | The target shape is known, but not implemented yet. |
| Needs runtime support | The fixture cannot be made honest without a Scalive runtime fix. |
| Fixed | Local fixture exercises the same behavior and targeted upstream specs pass. |

## Gaps

| Area | Local Shortcut | Upstream Behavior To Exercise | Required Fix | Status |
| --- | --- | --- | --- | --- |
| `PortalLiveView` nested LiveView and LiveComponent cases | Flattens nested LiveView and LiveComponent portal scenarios into parent state, parent event handlers, global hooks, and component-like markup. | Portals host real nested LiveViews and LiveComponents, including teleported event routing, `pushEventTo`, streams, hook lifecycle, and cleanup owned by the originating view or component. | Add real nested LiveViews and LiveComponents under portals, including the teleported component button and nested teleported LiveView roots. | Fixed |
| `KeyedComprehensionLiveView` | All tabs render the same keyed Scala table shape; `count` starts at `10` and randomize never increments it. | `all_keyed`, `rows_keyed`, and `no_keyed` exercise different key placements while 10 randomizes mutate items and increment count from `0` to `10`. | Implement the three distinct keyed render paths and upstream randomize count/item mutations. | Fixed |
| `ErrorLiveView` | Previously simulated child crashes, rejoins, reloads, and console messages by mutating parent state and synthetic log payloads. | Real parent and child LiveViews crash, rejoin, give up, link, remount, and drive client error classes without synthetic log injection. | Added runtime crash/join error handling, linked nested LiveView crash propagation, connect params, and a real nested child fixture. | Fixed |
| `ColocatedLiveView` | Uses global hooks and hand-written highlighted HTML. | Colocated hook and JS assets plus a macro component provide the runtime hooks, JS exec handler, and syntax highlighting. | Add an honest colocated asset and macro-component fixture or equivalent Scalive runtime coverage instead of global/manual DOM. | Fixed |
| Form `?live-component` | The query flag is decoded but the form still renders through the root LiveView path. | The same form behavior runs inside a LiveComponent with `phx-target={@myself}` and component-local submit/recovery state. | Add a real `FormComponent` and route `?live-component` rendering through it, including portal mode. | Fixed |
| `/form/nested` | Wraps the root form fixture in a `#nested` div. | `/form/nested` renders a parent LiveView that hosts a separate nested child LiveView. Upstream helpers target `#nested` as the child LiveView root. | Add a parent nested-form LiveView and a child form LiveView, preserving the DOM target expected by upstream tests. | Fixed |
| `/form/nested?live-component` | Combines the two shortcuts above. | A nested LiveView renders the form through a LiveComponent and recovery/events route to the child/component correctly. | Compose the real nested LiveView fixture with the real form LiveComponent fixture. | Fixed |
| `Issue2965LiveView` | Uses a regular live upload input and renders every progress row as `100%`; no queued custom uploader hook or server reply/progress queue is exercised. | `QueuedUploaderHook` dedupes filenames with `upload_scrub_list`, calls `this.upload`, and server progress pushes `upload_send_next_file`. | Implement the custom queued upload hook, server reply, delayed writer/progress flow, and next-file push event. | Fixed |
| `Issue3026LiveView` | Renders the form inline and treats submit as an immediate return to loaded state. | A LiveComponent form is removed during loading, then re-added after delayed async load without stale component patches. | Render the form as a real component and model connected `:load`, delayed reload, submit, removal, and reinsertion. | Fixed |
| `Issue3047LiveView` | Keeps the stream in the route LiveView and preloads page B with reset items. | A sticky child LiveView in the layout keeps its stream state while navigating from page A to page B. | Add a sticky or persistent nested LiveView whose stream survives parent navigation. | Fixed |
| `Issue3117LiveView` | Renders static row divs directly. | Rows are LiveComponents with async assigns and a static function-component root that survive live navigation. | Recreate component rows with async update/assign behavior and nested static function-component content. | Fixed |
| `Issue3169LiveView` | Flattens the nested component tree into inline markup and direct selected-state rendering. | `send_update`, delayed load, dynamic record ids, nested LiveComponent CIDs, and magic-id change tracking update nested input values correctly. | Implement the nested component chain and asynchronous update/reload semantics. | Fixed |
| `Issue3194LiveView` | Submit uses client-side `JS.navigate`, while the server submit handler is a no-op. | Server-side submit performs `push_navigate` while a blur-debounced change from the old view may still be in flight. | Route submit through server event handling and perform server push navigation. | Fixed |
| `Issue3200LiveView` | Collapses tabs, message component, target form, and state into one LiveView with a selector-shaped `phx-target`. | Form recovery dispatches to a selector target owned by nested LiveComponents. | Restore the tab/message LiveComponents and selector-targeted form recovery path. | Fixed |
| `Issue3378LiveView` | Emits static stream-shaped DOM from one LiveView. | A nested Home/AppBar/Notifications LiveView chain contains the stream and rejoins cleanly after reconnect. | Implement nested LiveViews with the notifications stream in the deepest child. | Fixed |
| `Issue3496LiveView` | Renders the same non-sticky hook element for both pages. | The same hook id is reused between a sticky LiveView and a non-sticky page across navigation without stale hook lookup errors. | Add the sticky layout child and render the hook in sticky context on page A and non-sticky context on page B. | Fixed |
| `Issue3530LiveView` | Previously rendered the hook directly in the stream item. | Streamed children are nested LiveViews with hook mount/destroy behavior under reset and insert operations. | Restored the hook inside the streamed nested LiveView and fixed nested join delivery under rapid streamed child joins. | Fixed |
| `Issue3612LiveView` | Previously rendered normal navigate links directly in the page. | A sticky child LiveView remains connected while its own event handler issues `push_navigate` between pages. | Added a sticky child fixture whose click handlers perform server-side navigation. | Fixed |
| `Issue3647LiveView` | Previously marked an upload complete directly from the button click. | Hook-driven `uploadTo` starts an auto-upload while another input event locks a different form, including ignored upload input data-attribute merging. | Implemented the real upload fixture with an allowed upload, external form-owned file input, JS hook upload, and concurrent input event. | Fixed |
| `Issue3651LiveView` | Previously rendered a static hidden notice and total `0`. | Nested hooks with a changing DOM id, pushed events, destroyed cleanup, and ignored notice prevent ghost elements and event explosions. | Added the outer/inner hooks, dynamic id change, pushed client event loop, counter, and ignored notice behavior. | Fixed |
| `Issue3656LiveView` | Previously used a custom hook to manually clear the click-loading class after dispatch. | `phx-click-loading` is added and removed by LiveView itself for a normal link inside a sticky LiveView during navigation. | Removed the manual class clearing and used a sticky LiveView containing a normal navigate link. | Fixed |
| `Issue3658LiveView` | Previously placed the `phx-remove` element in the root view and used a no-op click. | A `phx-remove` element inside a sticky LiveView is not removed when the parent navigates. | Rendered `#foo` inside a sticky child and triggered real navigation from the parent link. | Fixed |
| `Issue3681LiveView` | Previously hard-coded the final stream DOM. | A sticky LiveView stream must not reset or collide when another LiveView initializes and resets a stream with the same name. | Implemented real sticky and away LiveViews with separate stream setup and reset behavior. | Fixed |
| `Issue3684LiveView` | Parent toggles one checkbox directly. | A LiveComponent form/radio input targeted at `@myself` patches nested cloned input state correctly. | Use a component fixture with form, fieldset/radio inputs, and targeted component event. | Fixed |
| `Issue3686LiveView` | Exercises server flash storage/transport across navigation and redirect. | Flash propagates through server `push_navigate`, server `redirect`, and another `push_navigate`. | Verified the existing server-side fixture against upstream. | Fixed |
| `Issue3709LiveView` | Renders rapid patch links and a route-keyed LiveComponent. | Many rapid patch navigations race with pending diffs while a LiveComponent id changes with the route id. | Added multiple patch links, a button that clicks them rapidly, and a component keyed by current route id. | Fixed |
| `Issue3814LiveView` | Server injects a hidden input for the submitter before `phx-trigger-action` submits. | Native triggered form submission includes the actual clicked submitter name/value without server compensation. | Remove hidden submitter injection and rely on the client/native submitter path. | Gap |
| `Issue3919LiveView` | Root view directly toggles style and text. | A defaulted component attr supplied via dynamic attrs is considered changed when added and then removed. | Render through a component/helper with an omitted/defaulted boolean attr path and toggle supplying/removing that attr. | Fixed |
| `Issue3941LiveView` | Root state directly renders or removes item divs. | Component-only patches inside a locked tree survive later stale locked-tree application across LiveComponents, nested headers, async assigns, and hook events. | Model items as real LiveComponents with nested header components, async result/loading state, and the hook/event path that locks the container. | Gap |
| `Issue3953LiveView` | Fakes a nested component with a root-rendered `data-phx-component` div. | Destroy messages for components inside a toggled nested child LiveView are scoped to the child, not the parent LiveView. | Implement an actual nested LiveView under `#nested_view` with its own component and toggle mounting/unmounting that child. | Gap |
| `Issue3979LiveView` | Parent directly sets all counters to `10`. | A parent mutates component DOM ids, then a delayed `send_update` updates each LiveComponent after cid/id churn. | Render real `LiveComponent`s and schedule delayed `ctx.components.sendUpdate` for each component. | Fixed |
| `Issue4027LiveView` | Root LiveView renders a keyed list directly. | Keyed comprehensions are merged inside LiveComponents, including a variant where the component receives an async result assign. | Move the keyed list into real LiveComponents and model load/remove through delayed async result state. | Fixed |
| `Issue4066LiveView` | Root LiveView renders the hooked input and halts the delayed event in a root raw-event hook. | A delayed hook `pushEventTo` targeted at a LiveComponent after that component/input disconnected is ignored without bubbling, crashing, or remounting the parent. | Render a real LiveComponent containing the hooked input, target the event to the component, remove it before the delayed event fires, and verify the disconnected event is ignored. | Gap |
| `Issue4078LiveView` | Renders a custom file input instead of using the upload helper. | `live_file_input` patches dynamic attrs like `disabled` and `class` while selected files remain preserved. | Use the real `liveFileInput(...)` helper with `disabled` and `class` mods instead of a hand-written input. | Fixed |
| `Issue4088LiveView` | Root hook/raw event updates root state. | A hook inside a locked LiveComponent container pushes repeated targeted events to `@myself`, and the component patches safely. | Implement the fixture as a real LiveComponent with a targeted hook event. Add runtime support if cid-targeted raw events without rendered bindings are not handled correctly. | Needs runtime support |
| `Issue4147LiveView` | Renders the hook element inside the LiveView root. | A colocated hook outside the LiveView root mounts once and survives disconnect/reconnect behavior without duplicate mounting. | Render the hook outside `[data-phx-main]`, likely through the E2E root layout for `/issues/4147`. | Gap |

## Fixture References

| Area | Local Fixture | Upstream Fixture | Upstream Spec |
| --- | --- | --- | --- |
| `PortalLiveView` | `e2eApp/src/PortalLiveView.scala` | `test/e2e/support/portal.ex` | `test/e2e/tests/portal.spec.js` |
| `KeyedComprehensionLiveView` | `e2eApp/src/KeyedComprehensionLiveView.scala` | `test/e2e/support/keyed_comprehension_live.ex` | `test/e2e/tests/keyed-comprehension.spec.js` |
| `ErrorLiveView` | `e2eApp/src/ErrorLiveView.scala` | `test/e2e/support/error_live.ex` | `test/e2e/tests/errors.spec.js` |
| `ColocatedLiveView` | `e2eApp/src/ColocatedLiveView.scala` | `test/e2e/support/colocated_live.ex` | `test/e2e/tests/colocated.spec.js` |
| Forms | `e2eApp/src/FormLiveViews.scala` | `test/e2e/support/form_live.ex` | `test/e2e/tests/forms.spec.js` |
| `Issue2965LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_2965.ex` | `test/e2e/tests/issues/2965.spec.js` |
| `Issue3026LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3026.ex` | `test/e2e/tests/issues/3026.spec.js` |
| `Issue3047LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3047.ex` | `test/e2e/tests/issues/3047.spec.js` |
| `Issue3117LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3117.ex` | `test/e2e/tests/issues/3117.spec.js` |
| `Issue3169LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3169.ex` | `test/e2e/tests/issues/3169.spec.js` |
| `Issue3194LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3194.ex` | `test/e2e/tests/issues/3194.spec.js` |
| `Issue3200LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3200.ex` | `test/e2e/tests/issues/3200.spec.js` |
| `Issue3378LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3378.ex` | `test/e2e/tests/issues/3378.spec.js` |
| `Issue3496LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3496.ex` | `test/e2e/tests/issues/3496.spec.js` |
| `Issue3530LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3530.ex` | `test/e2e/tests/issues/3530.spec.js` |
| `Issue3612LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3612.ex` | `test/e2e/tests/issues/3612.spec.js` |
| `Issue3647LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3647.ex` | `test/e2e/tests/issues/3647.spec.js` |
| `Issue3651LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3651.ex` | `test/e2e/tests/issues/3651.spec.js` |
| `Issue3656LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3656.ex` | `test/e2e/tests/issues/3656.spec.js` |
| `Issue3658LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3658.ex` | `test/e2e/tests/issues/3658.spec.js` |
| `Issue3681LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3681.ex` | `test/e2e/tests/issues/3681.spec.js` |
| `Issue3684LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3684.ex` | `test/e2e/tests/issues/3684.spec.js` |
| `Issue3686LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3686.ex` | `test/e2e/tests/issues/3686.spec.js` |
| `Issue3709LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3709.ex` | `test/e2e/tests/issues/3709.spec.js` |
| `Issue3814LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3814.ex` | `test/e2e/tests/issues/3814.spec.js` |
| `Issue3919LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3919.ex` | `test/e2e/tests/issues/3919.spec.js` |
| `Issue3941LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3941.ex` | `test/e2e/tests/issues/3941.spec.js` |
| `Issue3953LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3953.ex` | `test/e2e/tests/issues/3953.spec.js` |
| `Issue3979LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_3979.ex` | `test/e2e/tests/issues/3979.spec.js` |
| `Issue4027LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_4027.ex` | `test/e2e/tests/issues/4027.spec.js` |
| `Issue4066LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_4066.ex` | `test/e2e/tests/issues/4066.spec.js` |
| `Issue4078LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_4078.ex` | `test/e2e/tests/issues/4078.spec.js` |
| `Issue4088LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_4088.ex` | `test/e2e/tests/issues/4088.spec.js` |
| `Issue4147LiveView` | `e2eApp/src/IssueLiveViews.scala` | `test/e2e/support/issues/issue_4147.ex` | `test/e2e/tests/issues/4147.spec.js` |

## Acceptance Criteria

| Check | Required Result |
| --- | --- |
| Fixture shape | Local fixtures exercise the same runtime feature as the upstream fixtures, not only the same DOM assertions. |
| Targeted upstream specs | Each affected upstream spec passes when run through `./scripts/e2e-run-upstream.sh`. |
| Native tests | Any runtime behavior discovered while fixing a fixture has a Scalive-native regression test. |
| Full upstream browser suite | `./scripts/e2e-run-upstream.sh` passes after all fixes. |
| Compatibility docs | `UPSTREAM_COMPATIBILITY.md` only claims parity for rows that have honest fixture coverage or native coverage. |

## Genuine Coverage Not Tracked Here

The earlier audit identified some additions as genuine behavior coverage rather than shortcuts: `4094`, `4095`, `4102`, `4107`, `4121`, and portal cases not listed above. Those can still have bugs, but they are not part of this shortcut cleanup unless later evidence shows otherwise.
