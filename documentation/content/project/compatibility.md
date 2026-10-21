{%
title = "Phoenix LiveView compatibility"
description = "Current Scalive coverage, intentional divergences, and known gaps relative to Phoenix LiveView."
order = 1
section = project
%}

## Compatibility target {#compatibility-target}

Scalive currently tracks Phoenix LiveView `v1.2.12`. This pinned version is the
reference point for the statuses below; behavior added to later Phoenix
LiveView releases is not implied to be supported. The published Scalive artifact
includes the corresponding Phoenix LiveView `1.2.12` and Phoenix `1.8.15`
packaged browser-global clients; applications may instead own a custom npm bundle
using those supported versions.

Compatibility means equivalent observable application and browser behavior
where that behavior applies to Scalive. It does not mean source compatibility,
identical public APIs, or internal implementation parity. Scalive deliberately
uses Scala 3 types and ZIO effects where copying an Elixir API would make the
user-facing API less safe or less clear.

This page describes the current source revision. A status applies only to the
behavior listed in its row. In particular, a passing browser scenario does not
prove that every server-side edge case in the same feature area is covered.

## Status legend {#status-legend}

| Status | Meaning |
| --- | --- |
| Browser coverage | The complete pinned browser suite passes with retries disabled. |
| Native parity covered | Scalive has native tests that mirror the relevant upstream runtime behavior. |
| Native coverage substantial | Core behavior is implemented and tested, but not every edge case has been compared with Phoenix. |
| Native coverage expanding | Implemented enough to use, with known parity gaps still being closed. |
| Partial | Some behavior exists, but the upstream feature area is not yet complete or fully mapped. |
| Intentional divergence | Scalive deliberately exposes a Scala-first typed API instead of copying Phoenix's untyped API shape. |
| Not implemented | No equivalent feature exists. |
| Not directly applicable | The upstream concept is specific to Phoenix or Elixir and should be replaced by a Scalive-native concept if needed. |

## Status matrix {#status-matrix}

### Runtime and routing {#runtime-and-routing}

| Area and upstream reference | Status | What works | Known gaps |
| --- | --- | --- | --- |
| Browser behavior (Phoenix LiveView browser suite, `test/e2e/tests`) | Browser coverage | All 207 scenarios of the pinned browser suite pass against Scalive with retries disabled. | None for the pinned version. |
| Wire protocol and diff encoding (`Phoenix.LiveView.Socket`, `Phoenix.LiveView.Diff`, JS client protocol) | Native coverage substantial | Message envelopes, component and stream projection, rendered deltas, events, redirects, joins, uploads, and the `v1.2.12` client protocol. | Exact error payloads, reconnect behavior, and stale cases have not all been compared with Phoenix. |
| Static HTTP render and connected bootstrap (`mount/3`, `handle_params/3`, `render/1`) | Native coverage substantial | Disconnected render, connected mount, initial parameter handling, bootstrap patch and redirect loops, static tracking, and root shell rendering. | Error, crash, and reconnect behavior has not all been compared with Phoenix. |
| Live routes (`Phoenix.LiveView.Router.live/4`) | Native coverage substantial | A typed route algebra: `live`, path codecs, GET-only routes, duplicate validation, typed route parameters, and typed environment inference. | No equivalent of `@live_action`, `:metadata`, or `:private`. |
| Live sessions (`Phoenix.LiveView.Router.live_session/3`) | Native coverage substantial | `Live.session`, duplicate name validation, connected-navigation boundaries, claim-bearing `LiveSessionMountAspect`s, admission, and named live-session layouts. | Session aspects are available only on named live-session builders; HTTP-only cookies and headers are consumed at session or document boundaries, and only minimal signed claims cross into connected mount. |
| Route-level `on_mount` (`Phoenix.LiveView.on_mount/1`) | Intentional divergence | Claimless `LiveRouteMountAspect`s receive typed path parameters and URL, compose in order, and run for disconnected HTTP, initial connected join, and navigation within a named live session. Their context is never serialized. `LiveRouteMountFailure` preserves redirect and 401/403/404 HTTP semantics while rejecting connected admission. | Route admission has no route-specific serialized claims. Invalid returns and contradictory continue/redirect states are unrepresentable. Mutable policy requires connected-turn guards and domain authorization. |
| Layouts and root layouts (`:layout`, `:root_layout`) | Native coverage substantial | Typed `LiveLayout` and `LiveRootLayout` compose at router, session, and route level, with root precedence and key-mismatch fallback. | A LiveView cannot change its layout at runtime; layouts come from route and session configuration. |
| `connected?/1` equivalent | Native parity covered | `LifecycleContext.connection` is `Connection.Disconnected` during HTTP rendering and `Connection.Connected(capabilities)` during connected lifecycle phases. | Connected-only capabilities are available only from the matched branch. |
| Connected lifecycle resources (`connected?/1`, `terminate/2`) | Intentional divergence | `ConnectedResources.acquireRelease` ties acquisition and exactly-once finalization to one connected LiveView lifecycle; root and nested LiveViews remain independent even on one WebSocket. | Phoenix has no general finalizer registry. Scalive cleanup starts after the server observes termination; externally visible ownership still needs leases for node loss and network partitions. |
| Connect params and connect info (`get_connect_params/1`, `get_connect_info/2`) | Partial | `ConnectedMetadata.connectParam` decodes untrusted join parameters through typed `ConnectParam` keys; names starting with `_` are reserved for Phoenix. `ConnectedMetadata.join` reports a first join or a rejoin from the client's `_mounts` counter. Session aspects receive a phase-specific request; route aspects receive typed path parameters and URL without original HTTP cookies or headers. The connected test harness owns `_mounts` and follows the client: it advances on reconnect and restarts on navigation. | No typed connect-info API for peer, header, user-agent, or equivalent server-derived transport data. Phoenix-owned keys are not readable as connect params. |
| LiveView model lifecycle (`mount`, event, info, async, params, signal-backed view graphs) | Intentional divergence | Routed views receive decoded parameters in typed `mount`; typed messages, subscriptions, parameter handling, async completion messages, and read-only model signals replace assigns and callback tuples. | Phoenix callback shapes are not copied; each maps to typed Scalive runtime behavior. |
| Process-style callbacks (`handle_call/3`, `handle_cast/2`, `terminate/2`, `transport_pid/1`, `put_private/3`) | Not implemented | Scalive instead uses ZIO fibers, streams, connected resources, and typed context capabilities. | Direct equivalents are unlikely. |
| Error shapes and crash/reconnect behavior (integration tests and protocol errors) | Native coverage expanding | Unauthorized, stale, and redirect joins; invalid route and session failures; duplicate IDs; hook-stage errors; and redirect loops. | Protocol errors, crash logging, stale joins, reconnect remounts, and transport failures have not been systematically compared with Phoenix. |
| Error pages and exception statuses (`ErrorHTML`, `Plug.Exception` `plug_status`, connected-mount reload) | Intentional divergence | A router `LiveErrorPage` renders Scalive's own 400, 401, 403, 404, and 500 page responses and unmatched `GET` requests inside the router's root layout. `LiveNotFound` from `mount` or `handleParams` answers 404; a failed join replies `reload` with a signed status token, and the reloaded page request answers that status, as Phoenix does for a 4xx `plug_status`. | Only `LiveNotFound` carries a status; other failures answer 500. There is no per-exception status protocol and no debug error page with a stack trace. |

### Components and lifecycle {#components-and-lifecycle}

| Area and upstream reference | Status | What works | Known gaps |
| --- | --- | --- | --- |
| Root LiveView lifecycle hooks (`attach_hook/4`, `detach_hook/3`) | Native parity covered | Event, params, info, async, and after-render hooks support static declarations, named dynamic attach/detach, halt/continue, replies, and duplicate-ID checks. | Results use typed ADTs; after-render hooks are side-effect-only and cannot introduce model state absent from the completed render. |
| LiveComponent lifecycle hooks | Native parity covered | Stateful components support static and dynamic event, async, and side-effect-only after-render hooks, including detach. | Hooks use Scalive's typed component API; post-render model mutation is intentionally excluded. |
| Stateful LiveComponents (`Phoenix.LiveComponent`) | Native parity covered | Stable identities and CIDs, both mounts, add/update/remove, temporary removal and remount, duplicate rejection, local events/forms/uploads, targets, nesting, streams, async, flash, navigation, and client effects. | `render_component/2`, CID-based external updates, and module/id-less `send_update` are intentionally not copied. |
| Component update APIs (`send_update/3`, `send_update_after/4`, `update_many/1`) | Partial | The connected `ComponentUpdates` capability exposes typed `sendUpdate` overloads by component instance or by component definition and ID; missing targets are ignored. | No typed delayed-update or batch-update equivalent. |
| Nested LiveViews (`live_render/3`) | Native parity covered | Both mounts, dynamic children, recursive cleanup, duplicate rejection, sticky children, component nesting, and child navigation/redirect behavior. | None known. |

### Interaction and rendering {#interaction-and-rendering}

| Area and upstream reference | Status | What works | Known gaps |
| --- | --- | --- | --- |
| Live navigation (`push_patch/2`, `push_navigate/2`, `redirect/2`, `<.link>`) | Native coverage substantial | Typed patch, navigate, replace, and redirect operations with URL resolution, payloads, flash carryover, named live-session boundaries, history traversal, route-aspect reruns on navigation, and rejection of non-HTTP schemes and control-character bypasses in runtime string destinations. `redirect` to an `ExternalUrl` (an absolute HTTP(S) URL with a host) matches `redirect(socket, external: url)`. Destination route context is freshly derived for navigation; patches retain the mounted lifecycle. A turn that requests a second navigation fails, as Phoenix raises `socket already prepared to redirect`. | Client and server fallback across named live sessions and root-layout changes has not been compared with Phoenix. |
| Flash lifecycle (`put_flash/3`, `clear_flash/1`) | Native parity covered | Keyed and full clear, `lv:clear-flash`, stale exclusion, redirect carryover and cookies, nested isolation, and child patch transfer. | Phoenix ConnTest and LiveViewTest assertion helpers are intentionally not copied. |
| Forms and form events (form bindings, `Phoenix.Component.form/1`, `to_form/2`, recovery) | Native coverage substantial | Lossless payload parsing, typed `FormCodec`, change/submit bindings, used fields, definition-owned typed submitters, optional raw submitter metadata decoding, render helpers, component routing, dynamic-field recovery, and `phx-no-unused-field`. | Some `to_form` behavior, and anything Ecto-specific, has no equivalent. |
| Uploads (`Phoenix.LiveView.Upload`) | Native coverage substantial | Allow, cancel, disallow, consume, and drop APIs; selected-versus-total entry limits; metadata validation; correlated progress; preflight/chunks; component routing; external uploaders; writer error reasons; and file-input helpers. | Destination cleanup, external-service verification, and abrupt process failure have not all been compared with Phoenix. |
| Streams (`stream/4`, insert, delete, async, configure) | Partial | Typed streams with create, batch insert, reset, insert/delete/delete-by-DOM-ID, component scope, and Phoenix operations. A retained row keeps its binding ID while its events use the row's latest value. | No `stream_async` equivalent. Existing-stream batch and reset placement follows Phoenix; creation has no placement option, which Phoenix ignores there. |
| Async tasks (`start_async/3`) | Native coverage substantial | Typed named tasks for roots and components with success, typed failure, defect (Phoenix's `{:exit, reason}`), cancellation, hooks, replacement, side effects, and deterministic cleanup. | Complex Phoenix task keys map to explicit typed string declarations; there is no task-supervisor option. |
| Async assigns (`assign_async/3`) | Not implemented | `AsyncValue` represents empty, loading, success, typed failure, defect, cancellation, and preserved previous values; applications update it through typed completion messages. | No field-level helper that updates the model for you. |
| JS commands (`Phoenix.LiveView.JS`) | Native coverage substantial | Class, visibility, transition, dispatch, exec, focus, attribute, navigation, push, ignore-attribute, and server-pushed execution commands. | Command JSON matches the `v1.2.12` client only. |
| Client events and hooks (`push_event/3`, `handleEvent`, `phx-hook`) | Native coverage substantial | Directional typed payload contracts, root and component handlers, event diff payloads, required hook IDs, and raw-hook replies. | JavaScript subscription and emission remain string-based; browser hook behavior is largely delegated to the upstream JS client. |
| DOM bindings and patch attributes (`phx-*`) | Native coverage substantial | Typed event/form/JS bindings, targets, upload progress, stream/ignore/update attributes, `phx-patch-focused`, `phx-no-unused-field`, lifecycle bindings, and connected/disconnected visibility. An event from an element a recent update removed is still delivered. | Bindings match the `v1.2.12` client only. Scalive generates binding identities, so an event from an element removed more than 10 seconds earlier is ignored, where a Phoenix event name never expires. |
| Static asset tracking (`phx-track-static`, `_track_static`) | Native coverage substantial | On join, the browser's tracked `href` and `src` values are compared with the running server's root layout and feed `ConnectedMetadata.staticChanged`, which LiveViews and ordinary layouts (through `LiveLayoutContext.connection`, where Phoenix uses an `on_mount` assign) can read; application join parameters are read through typed `ConnectParam` keys; the packaged client graph is served as immutable assets. | Phoenix judges every tracked URL against the endpoint's static manifest; Scalive compares only root-layout tags and Scalive's own scripts. Exact `_track_static` metadata has not been compared with Phoenix; Phoenix-owned keys are not a stable application contract. |
| Title updates (`live_title`, `@page_title`) | Native coverage substantial | Typed `pageTitle(model)` drives disconnected HTML, connected diffs, fallback resets, and sticky rejoins; `liveTitle` supplies default, prefix, and suffix metadata. | Title ownership across navigation between routed root views has limited coverage. |
| Portals and focus wrap (`Phoenix.Component.portal/1`, `focus_wrap/1`) | Native coverage substantial | `portal` and `focusWrap` helpers. | None known. |
| HEEx templates and function components (`Phoenix.Component`, `~H`, `attr`, `slot`) | Intentional divergence | Typed Scala HTML builders and typed stateful components replace HEEx macros and assign maps. | HEEx will not be copied; use the [HTML and event bindings guide](../guides/html-dsl-and-event-bindings.md#build-an-html-tree) for Scalive patterns. |
| Verified routes and path helpers (`Phoenix.VerifiedRoutes`) | Intentional divergence | Typed route, path, and query codecs check URLs at compile time, and a route declaration's `location` builds a typed link destination. A route can also register an HTTP `GET` handler with `http`, admitted by its live session's mount aspects, where Phoenix would route to a controller in the same pipeline. | No `~p` sigil; build URLs from route declarations, as [Routes, parameters, and navigation](../guides/routes-and-navigation.md#build-locations-from-route-declarations) shows. |

### Security, transport, and tooling {#security-transport-and-tooling}

| Area and upstream reference | Status | What works | Known gaps |
| --- | --- | --- | --- |
| Security and session tokens (signing, session, flash, CSRF, connect params) | Native coverage expanding | HMAC tokens with max age, signed session-mount claims and flash tokens, claimless route admission, typed redirects, hardened runtime string destinations, root-layout session data, shared form and WebSocket CSRF, checked POST fields, and stale invalid-CSRF joins. | Token salt and MessagePack details are not settled. Session claims and flash values are signed, not encrypted; route-aspect output is never serialized. Unsafe mount redirects require an application-trusted destination. |
| Endpoint and socket configuration (`:live_view` config, socket path, hibernation) | Partial | `Live.router.withSocketPath(PathCodec[Unit])` configures the socket path; validated `ZioHttpConfig` configures signing, maximum age, secure cookies, and a non-empty exact WebSocket origin allowlist for `ZioHttp.routes`. | Hibernation and the other Phoenix endpoint options are not implemented. |
| WebSocket transport (`Phoenix.Socket.Transport.check_origin/5`) | Native coverage substantial | WebSocket transport and the upload WebSocket protocol. Upgrade admission requires exactly one valid configured HTTP or HTTPS `Origin` and never trusts host or forwarding headers. | Intentionally stricter than Phoenix: no host-only default, and a missing `Origin` is rejected rather than accepted. |
| Long-poll transport fallback | Not implemented | None. | A network that blocks WebSockets leaves pages disconnected. |
| Telemetry and observability (Phoenix telemetry and logger metadata) | Native coverage substantial | Public `LifecycleObserver` events for disconnected rendering, joins, mounts, connected turns, handler and subscription failures, queue pressure, and termination. `LifecycleMetrics` maps them to a fixed low-cardinality ZIO Metrics contract, with ordered events, redaction boundaries, correlation, and isolation from observer failures. | Event names are lifecycle-oriented rather than copies of Phoenix telemetry names. Endpoint requests, access logs, infrastructure metrics, and a tracing adapter remain application concerns. |
| Test harness helpers (`Phoenix.LiveViewTest`) | Native coverage substantial, intentional divergence | `scalive-testing` supports disconnected semantic HTML queries, explicit ordinary GET/POST submission and 303 following, plus an in-process production transport client for signed routed joins, transport-owned `withAdmission` checks, typed messages, bindings, forms, nested views, hosted uploads, invalidation, reconnects, and live navigation. | ConnTest and LiveViewTest are not cloned. WebSocket upgrade and framing need real-transport tests, and DOM patching, reconnect timing, history, external uploads, and JavaScript behavior need browser tests. |

## Intentional divergences {#intentional-divergences}

The following choices are not compatibility gaps by themselves:

- Typed immutable models and context capabilities replace socket assign maps.
- Typed message values replace stringly typed dispatch in application handlers.
- `ZIO` effects and explicit result ADTs replace callback tuples.
- The typed `pageTitle(model)` projection replaces the conventional
  `:page_title` assign.
- Static hooks are unnamed; dynamic hooks retain names only where attachment
  and detachment require identity.
- Typed claim-bearing `LiveSessionMountAspect`s and claimless
  `LiveRouteMountAspect`s replace module-and-atom `on_mount` callbacks.
- Typed path and query codecs replace route macros and atom actions.
- Scala HTML builders and component values replace HEEx and component macros.
- Scalive-native testing APIs replace direct copies of Phoenix test helpers.
- Exact configured WebSocket origins replace Phoenix's host-only default and
  missing-Origin acceptance.

When strict API similarity conflicts with type safety, robustness, or Scala
ergonomics, Scalive prefers the better Scala API. For a conceptual mapping, read
[Phoenix LiveView concepts in Scalive](../guides/phoenix-live-view-orientation.md#map-the-core-concepts).

## Report a compatibility difference {#report-a-compatibility-difference}

When evaluating a feature, check its row's status and known gaps rather than
relying on the existence of a similarly named API. For a compatibility bug,
follow the [issue reporting guidance](index.md#report-an-issue) and include the
upstream `v1.2.12` documentation or test that defines the expected behavior.
