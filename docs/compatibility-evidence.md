# Compatibility evidence

Contributor notes behind the public
[Phoenix LiveView compatibility](../documentation/content/project/compatibility.md)
page. The public page lists what works and what users should know; this file
records the tests that back each status and the open maintainer work. Update
both when a status changes.

## Runtime and routing

| Area | Evidence | Open work |
| --- | --- | --- |
| Browser E2E behavior (`test/e2e/tests/**/*.spec.js`) | All 207 pinned scenarios pass with retries disabled. `scripts/e2e-run-upstream.sh`, the revision-specific synchronization patch under `test/upstream-patches`, and `e2eApp/src` are the executable evidence; snapshot CI runs the complete suite. | Keep the complete suite green and update the synchronization patch when advancing the upstream pin. |
| Wire protocol and diff encoding (`Phoenix.LiveView.Socket`, `Phoenix.LiveView.Diff`, JS client protocol) | `PhoenixProtocolSpec`, `PhoenixRenderedEncoderSpec`, `PhoenixUploadProtocolSpec`, and `TreeDifferSpec` cover envelopes, component and stream projection, rendered deltas, events, redirects, joins, uploads, and the `v1.2.12` client protocol advertisement. | Continue auditing exact error payloads, reconnect behavior, and stale cases. |
| Static HTTP render and connected bootstrap (`mount/3`, `handle_params/3`, `render/1`) | Disconnected render, connected mount, initial parameter handling, bootstrap patch and redirect loops, static tracking, and root shell rendering are covered. | Expand upstream-aligned error, crash, and reconnect assertions. |
| Live routes (`Phoenix.LiveView.Router.live/4`) | Typed route algebra covers `live`, path codecs, GET-only routes, duplicate validation, typed route parameters, and typed environment inference. | Decide whether route action and metadata equivalents are useful; document divergence from `@live_action`, `:metadata`, and `:private`. |
| Live sessions (`Phoenix.LiveView.Router.live_session/3`) | `Live.session`, duplicate name validation, connected-navigation boundaries, claim-bearing `LiveSessionMountAspect`s, admission, and named live-session layouts are covered. | Session aspects are available only on named live-session builders; HTTP-only cookies and headers are consumed at session or document boundaries, and only minimal signed claims cross into connected mount. |
| Route-level `on_mount` (`Phoenix.LiveView.on_mount/1`) | Claimless `LiveRouteMountAspect`s receive typed path parameters and URL, compose in order, and run for disconnected HTTP, initial connected join, and navigation within a named live session. Their context is never serialized. `LiveRouteMountFailure` preserves redirect and 401/403/404 HTTP semantics while rejecting connected admission. | Route admission has no route-specific serialized claims. Invalid returns and contradictory continue/redirect states are unrepresentable. Mutable policy requires connected-turn guards and domain authorization. |
| Layouts and root layouts (`:layout`, `:root_layout`) | Typed `LiveLayout` and `LiveRootLayout` cover router, session, and route composition, root precedence, and key-mismatch fallback. | Decide whether runtime layout changes from a LiveView are needed or intentionally replaced by route and session configuration. |
| `connected?/1` equivalent | `LifecycleContext.connection` is `Connection.Disconnected` during HTTP rendering and `Connection.Connected(capabilities)` during connected lifecycle phases. | Connected-only capabilities are available only from the matched branch. |
| Connected lifecycle resources (`connected?/1`, `terminate/2`) | `ConnectedResources.acquireRelease` ties acquisition and exactly-once finalization to one connected LiveView lifecycle; root and nested LiveViews remain independent even on one WebSocket. | Phoenix has no general finalizer registry. Scalive cleanup starts after the server observes termination; externally visible ownership still needs leases for node loss and network partitions. |
| Connect params and connect info (`get_connect_params/1`, `get_connect_info/2`) | `ConnectedMetadata.connectParams` exposes untrusted JSON join parameters on connected capabilities. Session aspects receive a phase-specific request; route aspects receive typed path parameters and URL without original HTTP cookies or headers. The connected test harness owns and advances `_mounts`. | Scalive does not expose a typed connect-info API for peer, header, user-agent, or equivalent server-derived transport data. Phoenix-owned parameter keys, including `_mounts`, remain protocol metadata rather than a stable application contract. |
| LiveView model lifecycle (`mount`, event, info, async, params, signal-backed view graphs) | Routed views receive decoded parameters in typed `mount`; typed messages, subscriptions, parameter handling, async completion messages, and read-only model signals replace assigns and callback tuples. | Continue mapping Phoenix callback behavior to typed Scalive runtime behavior rather than copying callback shapes. |
| Process-style callbacks (`handle_call/3`, `handle_cast/2`, `terminate/2`, `transport_pid/1`, `put_private/3`) | Scalive instead uses ZIO fibers, streams, connected resources, and typed context capabilities. | Determine whether any remaining process capabilities need useful Scala/ZIO equivalents; direct API parity is unlikely. |
| Error shapes and crash/reconnect behavior (integration tests and protocol errors) | Unauthorized, stale, and redirect joins; invalid route/session failures; duplicate IDs; hook-stage errors; and redirect loops are covered in slices. | Systematically audit protocol errors, crash logging, stale joins, reconnect remount behavior, and transport failures. |

## Components and lifecycle

| Area | Evidence | Open work |
| --- | --- | --- |
| Root LiveView lifecycle hooks (`attach_hook/4`, `detach_hook/3`, `hooks_test.exs`) | Event, params, info, async, and after-render hooks support static declarations, named dynamic attach/detach, halt/continue, replies, and duplicate-ID checks. | Results use typed ADTs; after-render hooks are side-effect-only and cannot introduce model state absent from the completed render. |
| LiveComponent lifecycle hooks | Stateful components support static and dynamic event, async, and side-effect-only after-render hooks, including detach. | Hooks use Scalive's typed component API; post-render model mutation is intentionally excluded. |
| Stateful LiveComponents (`Phoenix.LiveComponent`, `live_components_test.exs`) | Stable identities and CIDs, both mounts, add/update/remove, temporary removal and remount, duplicate rejection, local events/forms/uploads, targets, nesting, streams, async, flash, navigation, and client effects are covered. | `render_component/2`, CID-based external updates, and module/id-less `send_update` are intentionally not copied. |
| Component update APIs (`send_update/3`, `send_update_after/4`, `update_many/1`) | The connected `ComponentUpdates` capability exposes typed `sendUpdate` overloads by component instance or by component definition and ID; missing targets are ignored. | Typed delayed-update and batch-update equivalents remain undecided. |
| Nested LiveViews (`nested_test.exs`) | Both mounts, dynamic children, recursive cleanup, duplicate rejection, sticky children, component nesting, and child navigation/redirect behavior are covered. | Keep aligned as component, route, and session behavior evolves. |

## Interaction and rendering

| Area | Evidence | Open work |
| --- | --- | --- |
| Live navigation (`push_patch/2`, `push_navigate/2`, `redirect/2`, `<.link>`) | Typed patch, navigate, replace, and redirect operations cover URL resolution, payloads, flash carryover, named live-session boundaries, history traversal, route-aspect reruns on navigation, and rejection of non-HTTP schemes and control-character bypasses in runtime string destinations. Destination route context is freshly derived for navigation; patches retain the mounted lifecycle. | Audit client/server fallback behavior across named live sessions and root-layout changes. |
| Flash lifecycle (`flash_test.exs`) | Keyed and full clear, `lv:clear-flash`, stale exclusion, redirect carryover and cookies, nested isolation, and child patch transfer are covered. | Phoenix ConnTest and LiveViewTest assertion helpers are intentionally not copied. |
| Forms and form events (form bindings, `Phoenix.Component.form/1`, `to_form/2`, recovery) | Lossless payload parsing, typed `FormCodec`, change/submit bindings, used fields, definition-owned typed submitters, optional raw submitter metadata decoding, render helpers, component routing, dynamic-field recovery, and `phx-no-unused-field` are covered. | Decide which `to_form` and Ecto-specific behavior is out of scope. |
| Uploads (`Phoenix.LiveView.Upload` and upload integration tests) | Allow, cancel, disallow, consume, and drop APIs; selected-versus-total entry limits; metadata validation; correlated progress; preflight/chunks; component routing; external uploaders; writer error reasons; and file-input helpers are covered. | Continue auditing destination cleanup, external-service verification, and abrupt process failure. |
| Streams (`stream/4`, insert, delete, async, configure) | Typed streams cover create, batch insert, reset, insert/delete/delete-by-DOM-ID, component scope, and Phoenix operations. `StreamRenderingSpec` verifies that a retained row keeps its binding ID while dispatch uses the row's newly committed value. | Add or intentionally exclude `stream_async`; expand edge-case coverage. Existing-stream batch/reset placement follows Phoenix, while creation omits Phoenix's ignored placement option. |
| Async tasks (`start_async_test.exs`) | Typed named tasks for roots and components cover success, typed failure, defect (Phoenix's `{:exit, reason}`), cancellation, hooks, replacement, side effects, and deterministic cleanup; `ManagedAsyncSpec` covers a failure that also dies, which arrives as the typed failure. | Complex Phoenix task keys map to explicit typed string declarations; there is no task-supervisor option. |
| Async assigns (`assign_async_test.exs`) | `AsyncValue` represents empty, loading, success, typed failure, defect, cancellation, and preserved previous values; applications update it through typed completion messages. | Add a typed field-level helper only if it improves the explicit flow without hiding model transitions. |
| JS commands (`Phoenix.LiveView.JS`) | The command builder covers class, visibility, transition, dispatch, exec, focus, attribute, navigation, push, ignore-attribute, and server-pushed execution commands. | Audit command JSON when advancing beyond `v1.2.12`. |
| Client events and hooks (`push_event/3`, `handleEvent`, `phx-hook`) | Directional typed payload contracts, root and component handlers, event diff payloads, required hook IDs, and raw-hook replies are covered. | JavaScript subscription and emission remain string-based; browser hook behavior is largely delegated to the upstream JS client. |
| DOM bindings and patch attributes (`phx-*`) | Typed event/form/JS bindings, targets, upload progress, stream/ignore/update attributes, `phx-patch-focused`, `phx-no-unused-field`, lifecycle bindings, and connected/disconnected visibility are covered. Events from elements a recent update removed are delivered for 10 seconds, at the root and in components (`RootConnectionSpec`, `ComponentRuntimeSpec`, `BindingTableSpec`). | Audit all `phx-*` attributes and corresponding JS-client behavior when the client target advances. |
| Static asset tracking (`phx-track-static`, `_track_static`) | Tracked static `href` and `src` values feed `ConnectedMetadata.staticChanged`; raw join parameters are available through connected `connectParams`; `BrowserFilesSpec` covers the pinned, content-hashed, immutable client files that `ZioHttp.routes` serves and tracks by default, and the root-slice browser suite connects through them. | Audit exact `_track_static` metadata and reconnect parity without exposing Phoenix-owned keys as stable application state. |
| Title updates (`live_title`, `@page_title`) | Typed `pageTitle(model)` drives disconnected HTML, connected diffs, fallback resets, and sticky rejoins; `liveTitle` supplies default, prefix, and suffix metadata. | Broaden navigation coverage for title ownership across routed root views. |
| Portals and focus wrap (`Phoenix.Component.portal/1`, `focus_wrap/1`) | `portal` and `focusWrap` helpers are covered. | Keep aligned with browser E2E expectations. |
| HEEx templates and function components (`Phoenix.Component`, `~H`, `attr`, `slot`) | Typed Scala HTML builders and typed stateful components replace HEEx macros and assign maps. | HEEx will not be copied; use the [HTML and event bindings guide](../guides/html-dsl-and-event-bindings.md#build-an-html-tree) for Scalive patterns. |
| Verified routes and path helpers (`Phoenix.VerifiedRoutes`) | Typed route, path, and query codecs provide compile-time URL construction for Scalive APIs. | More ergonomic typed URL builders may be exposed from route declarations. |

## Security, transport, and tooling

| Area | Evidence | Open work |
| --- | --- | --- |
| Security and session tokens (signing, session, flash, CSRF, connect params) | HMAC tokens with max age, signed session-mount claims and flash tokens, claimless route admission, typed redirects, hardened runtime string destinations, root-layout session data, shared form/socket CSRF, checked POST fields, and stale invalid-CSRF joins are covered. | Token salt and MessagePack details remain open. Session claims and flash values are signed, not encrypted; route-aspect output is never serialized. Unsafe mount redirects require an application-trusted destination. |
| Endpoint/socket configuration (`:live_view` config, socket path, hibernation) | `Live.router.withSocketPath(PathCodec[Unit])` configures the socket path; validated `ZioHttpConfig` configures signing, maximum age, secure cookies, and a non-empty exact WebSocket origin allowlist for `ZioHttp.routes`. | Decide which remaining endpoint options matter on ZIO HTTP; hibernation is not implemented. |
| WebSocket transport support (`Phoenix.Socket.Transport.check_origin/5`) | WebSocket transport and the upload WebSocket protocol are implemented. Upgrade admission requires exactly one valid configured HTTP or HTTPS `Origin` and never trusts host or forwarding headers. | Scalive is intentionally stricter than Phoenix's host-only default and rejects a missing Origin rather than accepting it. Preserve this security divergence as transport support evolves. |
| Long-poll transport fallback | Long-poll is not implemented. | Implement when long-poll becomes a supported transport. |
| Telemetry and observability (Phoenix telemetry and logger metadata) | Public `LifecycleObserver` events cover disconnected rendering, joins, mounts, connected turns, handler and subscription failures, queue pressure, and termination. `LifecycleMetrics` maps them to a fixed low-cardinality ZIO Metrics contract; runtime and API tests cover ordering, redaction boundaries, correlation, labels, and sink-defect isolation. | The public contract is lifecycle-oriented rather than a copy of Phoenix telemetry names. Endpoint requests, access logs, infrastructure metrics, and a tracing adapter remain application concerns. |
| Test harness helpers (`Phoenix.LiveViewTest`) | `scalive-testing` supports disconnected semantic HTML queries, explicit ordinary GET/POST submission and 303 following, plus an in-process production transport client for signed routed joins, transport-owned `withAdmission` checks, typed messages, bindings, forms, nested views, hosted uploads, invalidation, reconnects, and live navigation. | Retain real-transport tests for WebSocket upgrade/framing and browser tests for DOM patching, reconnect timing, history, external uploads, and JavaScript behavior rather than cloning ConnTest or LiveViewTest. |

## Verification

Compatibility claims should be backed by the narrowest relevant evidence:

1. Run `./scripts/e2e-run-upstream.sh` as the browser regression gate.
2. Run `./scripts/e2e-run-upstream-strict.sh` when runtime, protocol, transport, or fixture changes
   need three consecutive complete runs with retries disabled.
3. Add Scalive-native tests for upstream integration behavior that cannot run
   directly as Elixir tests.
4. Record the upstream file or documented behavior and the Scalive test that
   demonstrates its equivalent for each audited scenario.
5. Prefer a small vertical slice that passes end to end over a broad but
   incomplete abstraction.

The repository evidence behind the broadest claims includes:

- the [upstream browser runner](https://github.com/phfroidmont/scalive/blob/master/scripts/e2e-run-upstream.sh)
  against the pinned [Phoenix LiveView v1.2.12 tests](https://github.com/phoenixframework/phoenix_live_view/tree/v1.2.12/test/e2e/tests);
- native [kernel tests](https://github.com/phfroidmont/scalive/blob/master/scalive/runtime/kernel/test/src/scalive/runtime/kernel/SessionKernelSpec.scala)
  and [connection supervision tests](https://github.com/phfroidmont/scalive/blob/master/scalive/runtime/connection/test/src/scalive/runtime/connection/ConnectionSupervisorSpec.scala);
- native [render tests](https://github.com/phfroidmont/scalive/blob/master/scalive/render/test/src/scalive/render/RenderProgramSpec.scala),
  [stream-row binding tests](https://github.com/phfroidmont/scalive/blob/master/scalive/render/test/src/scalive/render/StreamRenderingSpec.scala),
  and [Phoenix projection tests](https://github.com/phfroidmont/scalive/blob/master/scalive/protocol/phoenix/test/src/scalive/protocol/phoenix/PhoenixRenderedEncoderSpec.scala);
- transport [ZIO HTTP tests](https://github.com/phfroidmont/scalive/blob/master/scalive/transport/zio-http/test/src/scalive/ZioHttpSpec.scala)
  and the production-admitted [connected test harness suite](https://github.com/phfroidmont/scalive/blob/master/scalive/testing/test/src/scalive/testing/ConnectedRenderSpec.scala).
