# Upstream Compatibility Matrix

Scalive tracks Phoenix LiveView behavior and feature coverage while keeping the Scala API ergonomic, typed, and robust. Compatibility targets behavior and feature-set parity, not internal implementation parity or direct copying of Elixir APIs.

The current upstream target is Phoenix LiveView `v1.1.28`, pinned by `flake.nix` and the websocket protocol version in `WebSocketMessage`.

## Status Legend

| Status | Meaning |
| --- | --- |
| Passing baseline | Covered by the upstream browser E2E harness, but not necessarily by a complete server-side parity suite. |
| Native parity covered | Scalive has native tests that mirror the relevant upstream runtime behavior. |
| Native coverage substantial | Core behavior is implemented and tested, but edge-case parity needs a dedicated upstream-suite audit. |
| Native coverage expanding | Implemented enough to use, with known parity gaps still being closed. |
| Partial | Some behavior exists, but the upstream feature area is not yet complete or fully mapped. |
| Intentional divergence | Scalive deliberately exposes a Scala-first typed API instead of copying Phoenix's untyped API shape. |
| Not implemented | No equivalent feature exists yet. |
| Not directly applicable | The upstream concept is specific to Phoenix/Elixir and should be replaced by a Scalive-native concept if needed. |

## Compatibility Matrix

| Area | Upstream Reference | Scalive Status | Scalive Coverage | Remaining Work / Decision | Priority |
| --- | --- | --- | --- | --- | --- |
| Browser E2E behavior | `test/e2e/tests/**/*.spec.js` | Passing baseline | Covered by `./scripts/e2e-run-upstream.sh`; keep running as the browser regression gate. | Keep the upstream harness green after protocol/runtime changes. | High |
| Wire protocol and diff encoding | `Phoenix.LiveView.Socket`, `Phoenix.LiveView.Diff`, JS client protocol | Native coverage substantial | `WebSocketMessage`, `RenderSnapshot`, `TreeDiff`, component diffs, keyed comprehensions, stream payloads, events, redirects, joins, upload messages. | Add protocol matrix rows for exact error payloads, reconnect/stale cases, and any protocol additions beyond `v1.1.28`. | High |
| Static HTTP render and connected bootstrap | `Phoenix.LiveView` lifecycle docs; `mount/3`, `handle_params/3`, `render/1` | Native coverage substantial | Disconnected render, connected socket mount, initial `handleParams`, bootstrap patch/redirect loops, static tracking, root shell rendering. | Expand error/crash/reconnect assertions against upstream integration behavior. | High |
| Live routes | `Phoenix.LiveView.Router.live/4` | Native coverage substantial | Typed route algebra with `live`, path codecs, GET-only live routes, duplicate route validation, typed route params, typed environment inference. | Decide whether route action/metadata equivalents are useful in Scala API; document any intentional divergence from `@live_action`, `:metadata`, and `:private`. | Medium |
| Live sessions | `Phoenix.LiveView.Router.live_session/3` | Native coverage expanding | First-class `Live.session`, duplicate session-name validation, websocket navigation boundaries, session-scoped mount aspects/layouts. | Polish live-session ergonomics and document exact security model for plugs vs typed mount aspects. | High |
| Route-level `on_mount` | `Phoenix.LiveView.on_mount/1`; `live_session :on_mount` | Intentional divergence | Represented by typed `LiveMountAspect`s that run before disconnected and connected mount, sign claims, reload typed context on join, compose in order, and support redirect/unauthorized/stale halts before connected mount. | Invalid returns and contradictory continue/redirect states are unrepresentable in the typed API; keep websocket halt payloads aligned. | High |
| Layouts and root layouts | `:layout`, `:root_layout`, mount/layout options | Native coverage substantial | Typed `LiveLayout` and `LiveRootLayout`, router/session/route composition, root-layout precedence, root-layout key mismatch fallback to fresh render. | Decide whether LiveView-returned layout changes are needed or intentionally replaced by route/session layout configuration. | Medium |
| `connected?/1` equivalent | `Phoenix.LiveView.connected?/1` | Native parity covered | `LiveContext.connected` is `false` for disconnected render and `true` for connected websocket mount. | Consider adding docs/examples after public API settles. | Medium |
| Connect params and connect info | `get_connect_params/1`, `get_connect_info/2` | Partial | Mount aspects receive the `Request` on disconnected and connected phases; route context can carry typed data. | Add explicit typed APIs for websocket connect params/info if needed: `_mounts`, `_track_static`, `_live_referer`, user-agent, peer/header data. | Medium |
| LiveView model lifecycle | `mount/3`, `handle_params/3`, `handle_event/3`, `handle_info/2`, `handle_async/3`, `render/1` | Intentional divergence | Scalive uses typed `mount`, `handleMessage`, typed `subscriptions`, `handleParams`, and typed async completion messages instead of socket assigns/callback tuples. | Keep documenting typed equivalents; add parity rows when Phoenix callback behavior maps to Scalive runtime behavior. | High |
| Process-style callbacks | `handle_call/3`, `handle_cast/2`, `terminate/2`, `transport_pid/1`, `put_private/3` | Not implemented | No direct public equivalent; Scalive uses ZIO fibers, streams, scoped resources, and typed context capabilities. | Decide which process APIs have useful Scala/ZIO equivalents; likely not direct API parity. | Low |
| Lifecycle hooks, root LiveViews | `test/phoenix_live_view/integrations/hooks_test.exs`; `attach_hook/4`, `detach_hook/3` | Native parity covered | Root event, params, info, async, and after-render hooks; attach during mount or after connected mount; detach; halt/continue; event replies; duplicate-id checks. | Exact hook error wording uses Scalive-native API names; invalid hook returns are replaced by typed result ADTs instead of runtime tuple validation. | High |
| Lifecycle hooks, LiveComponents | `Phoenix.LiveView.attach_hook/4` component limitations | Native parity covered | Stateful components support event and after-render hooks, including detach; params/info/async stages fail with Scalive-native unsupported-component errors. | Component after-render is supported as a Scala-first extension alongside upstream-supported event hooks. | Medium |
| Stateful LiveComponents | `test/phoenix_live_view/integrations/live_components_test.exs`; `Phoenix.LiveComponent` | Native parity covered | Stable identity/cids, disconnected/connected render, additions/updates/removals, duplicate-id rejection, component-local events/forms/uploads, selector/multiple targets, nested components, streams, async, flash, navigation, client effects. | Phoenix-only APIs such as `render_component/2`, cid-based external updates, and module/id-less `send_update` are intentionally not copied. | Highest |
| Component update APIs | `send_update/3`, `send_update_after/4`, `update_many/1` | Partial | Typed `LiveContext.sendUpdate[C](id, props)` updates mounted components and ignores missing targets. | Decide on typed `sendUpdateAfter` and `updateMany` equivalents. | Medium |
| Nested LiveViews | `test/phoenix_live_view/integrations/nested_test.exs` | Native parity covered | Disconnected/connected render, dynamic children, recursive cleanup, duplicate id rejection, sticky children, nested children inside components, child navigation/redirect behavior. | Keep aligned as component and route/session behavior evolves. | High |
| Live navigation | `push_patch/2`, `push_navigate/2`, `redirect/2`, `<.link>` | Native coverage substantial | Typed `LiveContext.pushPatch`, `replacePatch`, `pushNavigate`, `replaceNavigate`, `redirect`; live patch URL resolution; navigation payloads; flash carryover; live-session boundary checks. | Audit exact client/server fallback behavior for cross-session navigation, root-layout changes, and route-specific mount claims. | High |
| Flash lifecycle | `test/phoenix_live_view/integrations/flash_test.exs` | Native parity covered | Keyed/all clear, `lv:clear-flash`, stale flash exclusion, patch redirect carryover, push-navigate/redirect cookies, nested socket isolation, nested child patch flash transfer. | Phoenix ConnTest/LiveViewTest helper assertions intentionally not copied. | High |
| Forms and form events | Phoenix form bindings, `Phoenix.Component.form/1`, `to_form/2`, recovery behavior | Native coverage substantial | Lossless form payload parsing, typed `FormCodec`, change/submit bindings, used fields, submitter metadata, render-side helpers, form event routing to components. | Complete upstream form recovery/autorecover matrix and decide which `to_form`/Ecto-specific behavior is out of scope. | Medium |
| Uploads | `Phoenix.LiveView.Upload`, `live_file_input`, upload integration tests | Native coverage substantial | `allowUpload`, cancel/disallow/consume/drop APIs, validation errors, upload preflight/progress/chunks, component upload routing, external uploader and writer abstractions, live file input helper. | Add a full upstream upload edge-case matrix: auto-upload, external failures, postponed consumption, in-progress submit behavior, reallow/disallow, progress callback side effects. | Medium |
| Streams | `Phoenix.LiveView.stream/4`, `stream_insert`, `stream_delete`, `stream_async`, `stream_configure` | Partial | Typed `LiveStreamDef`, `stream`, insert/delete/delete-by-dom-id, reset/limit/update-only options, component-scoped streams, stream diff payloads. | Add or intentionally exclude typed equivalents for `stream_configure` and `stream_async`; expand upstream stream edge-case coverage. | Medium |
| Async tasks | `test/phoenix_live_view/integrations/start_async_test.exs` | Native parity covered | Typed named async tasks for roots/components; success/failure/cancellation messages; restart/keep-existing; navigation/flash/client side effects; deterministic cleanup on socket shutdown and component removal. | Phoenix complex task keys represented by explicit string names. | Medium |
| Async assigns | `test/phoenix_live_view/integrations/assign_async_test.exs` | Native parity covered | Typed field-level `assignAsync`; success/failure/cancellation; reset/preserve previous values; renewal after cancellation; root/component cleanup behavior. | Phoenix untyped map/list-key return validation and explicit `Task.Supervisor` option intentionally not copied. | Medium |
| JS commands | `Phoenix.LiveView.JS` | Native coverage substantial | `JS` command builder supports class changes, show/hide/toggle/transition, dispatch, exec, focus, focus stack, attrs, patch/navigate, push, ignore attributes, server-pushed `js:exec`. | Audit command JSON against upstream for any missing command/options in `v1.1.28`. | Medium |
| Client events and hooks | `push_event/3`, JS hook `handleEvent`, `phx-hook` | Native coverage substantial | Server `pushEvent`, component pushEvent, client-event diff payloads, hook name attrs, event replies via intercept/hook halt replies. | Browser-level hook behavior is mostly delegated to upstream JS client; add native tests for edge protocol behavior as needed. | Medium |
| DOM bindings and patch attributes | `phx-click`, `phx-submit`, `phx-target`, `phx-update`, `phx-mounted`, `phx-remove`, `phx-connected`, `phx-disconnected` | Native coverage substantial | Typed event bindings, form bindings, JS bindings, targets, upload progress, stream/ignore/update attrs, lifecycle binding attrs are renderable. | Audit all `phx-*` attributes and JS-client behavior against upstream docs. | Medium |
| Static asset tracking | `phx-track-static`, `_track_static`, static changed behavior | Native coverage substantial | Collects tracked static href/src values and computes `staticChanged`; exposed through `LiveContext.staticChanged`. | Add exact connect-param parity for `_track_static` once connect params are modeled explicitly. | Medium |
| Title updates | `live_title`, `@page_title`, title diff metadata | Partial | `LiveContext.putTitle` sends title updates in diffs. | Decide whether to add a typed `liveTitle` helper with prefix/suffix semantics. | Low |
| Portals and focus wrap | `Phoenix.Component.portal/1`, `focus_wrap/1` | Native coverage substantial | `portal` helper and `focusWrap` component helper exist and are covered. | Keep aligned with browser E2E expectations. | Low |
| HEEx templates and function components | `Phoenix.Component`, `~H`, `attr`, `slot`, `embed_templates` | Intentional divergence | Scalive uses typed Scala HTML builders and typed stateful components instead of HEEx macros and assigns maps. | Do not copy HEEx; document Scala component patterns when docs are written. | Low |
| Verified routes and path helpers | `Phoenix.VerifiedRoutes`, route helpers | Intentional divergence | Typed route/path/query codecs provide compile-time URL construction for Scalive APIs. | Decide whether to expose more ergonomic typed URL builders from `LiveRouteSeed`/route declarations. | Low |
| Security and session tokens | LiveView signing salt, session token, flash token, CSRF/connect params | Native coverage expanding | HMAC-signed tokens with max age, signed mount claims, signed flash tokens, root-layout key in session payload, CSRF meta/cookie emission, and stale joins for invalid websocket CSRF. | Token format still has TODOs around salt/messagepack. Claims are signed but not encrypted. | High |
| Endpoint/socket configuration | `:live_view` endpoint config, socket path, `hibernate_after`, long-poll options | Partial | `Live.socketAt(PathCodec[Unit])` configures socket mount path; `TokenConfig` configures secret/maxAge. | Decide which endpoint options matter in ZIO HTTP; hibernation/long-poll are not implemented. | Low |
| Transport support | Phoenix Channels websocket and long-poll fallback | Partial | WebSocket transport and upload websocket protocol are implemented. | Long-poll fallback is not implemented; decide if browser/client parity requires it. | Low |
| Telemetry and observability | Phoenix telemetry events, logger metadata | Not implemented | Some runtime warnings/errors are logged with context. | Add a Scalive/ZIO telemetry story if operational parity is required. | Low |
| Test harness helpers | `Phoenix.LiveViewTest` | Not directly applicable | Current coverage uses Scalive-native ZIO tests plus upstream browser E2E harness. | Design a Scalive-native test API rather than copying Phoenix ConnTest/LiveViewTest helpers. | Low |
| Error shapes and crash/reconnect behavior | Upstream integration tests and protocol errors | Native coverage expanding | Join unauthorized/stale/redirect responses, invalid route/session failures, duplicate ids, lifecycle hook stage errors, redirect loop errors are covered in slices. | Systematically audit protocol error payloads, crash logging, stale joins, and reconnect remount behavior; keep Scala API errors idiomatic. | High |

## Intentional Divergences

These are not gaps unless a concrete user-facing need appears:

- Socket assigns are replaced by typed models and typed context capabilities.
- Phoenix callback tuples are replaced by typed `ZIO` effects and explicit result ADTs where needed.
- `on_mount` is represented by typed `LiveMountAspect`s instead of module/atom callbacks.
- Route declarations use typed path/query codecs instead of Phoenix macros and atom route actions.
- HEEx/component macros are replaced by typed Scala HTML builders and component values.
- Phoenix test helpers should become Scalive-native testing helpers, not direct API clones.

## Immediate Work Queue

1. Keep lifecycle hook behavior aligned with `hooks_test.exs` as the typed API evolves.
2. Audit uploads and streams against upstream integration behavior and add missing rows/tests for uncovered edge cases.
3. Decide typed APIs for connect params/info, stream async/configuration, delayed component updates, and component batch updates.
4. Add protocol/error/reconnect parity tests for stale joins, invalid payloads, transport errors, and recovery behavior.
5. Keep `./scripts/e2e-run-upstream.sh` green while expanding native server-side parity suites.

## Verification Strategy

- Keep `./scripts/e2e-run-upstream.sh` green.
- Add Scalive-native tests for upstream integration behaviors that cannot be run directly as Elixir tests.
- For each matrix row, record the upstream files and the Scalive tests that cover equivalent behavior.
- Prefer small vertical slices that make one upstream scenario pass end-to-end over broad incomplete abstractions.
