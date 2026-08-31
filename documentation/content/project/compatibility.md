{%
title = "Phoenix LiveView compatibility"
description = "Current Scalive coverage, intentional divergences, and known gaps relative to Phoenix LiveView."
order = 4
section = project
%}

## Compatibility Target {#compatibility-target}

Scalive currently tracks Phoenix LiveView `v1.2.10`. This pinned version is the
reference point for the statuses below; behavior added to later Phoenix
LiveView releases is not implied to be supported. The published Scalive artifact
includes the corresponding Phoenix LiveView `1.2.10` and Phoenix `1.8.9`
packaged browser-global clients; applications may instead own a custom npm bundle
using those supported versions.

Compatibility means equivalent observable application and browser behavior
where that behavior applies to Scalive. It does not mean source compatibility,
identical public APIs, or internal implementation parity. Scalive deliberately
uses Scala 3 types and ZIO effects where copying an Elixir API would make the
user-facing API less safe or less clear.

This page describes the current source revision. A status applies only to the
evidence named in its row. In particular, a passing browser scenario does not
prove that every server-side edge case in the same feature area is covered.

## Status Legend {#status-legend}

| Status | Meaning |
| --- | --- |
| Browser coverage | The complete pinned browser suite passes with retries disabled. |
| Native parity covered | Scalive has native tests that mirror the relevant upstream runtime behavior. |
| Native coverage substantial | Core behavior is implemented and tested, but edge-case parity needs a dedicated upstream-suite audit. |
| Native coverage expanding | Implemented enough to use, with known parity gaps still being closed. |
| Partial | Some behavior exists, but the upstream feature area is not yet complete or fully mapped. |
| Intentional divergence | Scalive deliberately exposes a Scala-first typed API instead of copying Phoenix's untyped API shape. |
| Not implemented | No equivalent feature exists. |
| Not directly applicable | The upstream concept is specific to Phoenix or Elixir and should be replaced by a Scalive-native concept if needed. |

## Status Matrix {#status-matrix}

### Runtime And Routing {#runtime-and-routing}

| Area and upstream reference | Status | Current evidence | Known gap or decision |
| --- | --- | --- | --- |
| Browser E2E behavior (`test/e2e/tests/**/*.spec.js`) | Browser coverage | All 207 pinned scenarios pass with retries disabled. `scripts/e2e-run-upstream.sh`, the revision-specific synchronization patch under `test/upstream-patches`, and `e2eApp/src` are the executable evidence; snapshot CI runs the complete suite. | Keep the complete suite green and update the synchronization patch when advancing the upstream pin. |
| Wire protocol and diff encoding (`Phoenix.LiveView.Socket`, `Phoenix.LiveView.Diff`, JS client protocol) | Native coverage substantial | `PhoenixProtocolSpec`, `PhoenixRenderedEncoderSpec`, `PhoenixUploadProtocolSpec`, and `TreeDifferSpec` cover envelopes, component and stream projection, rendered deltas, events, redirects, joins, uploads, and the `v1.2.10` client protocol advertisement. | Continue auditing exact error payloads, reconnect behavior, and stale cases. |
| Static HTTP render and connected bootstrap (`mount/3`, `handle_params/3`, `render/1`) | Native coverage substantial | Disconnected render, connected mount, initial parameter handling, bootstrap patch and redirect loops, static tracking, and root shell rendering are covered. | Expand upstream-aligned error, crash, and reconnect assertions. |
| Live routes (`Phoenix.LiveView.Router.live/4`) | Native coverage substantial | Typed route algebra covers `live`, path codecs, GET-only routes, duplicate validation, typed route parameters, and typed environment inference. | Decide whether route action and metadata equivalents are useful; document divergence from `@live_action`, `:metadata`, and `:private`. |
| Live sessions (`Phoenix.LiveView.Router.live_session/3`) | Native coverage expanding | `Live.session`, duplicate session-name validation, websocket navigation boundaries, and session-scoped mount aspects and layouts are covered. | Polish ergonomics and document the security boundary between plugs and typed mount aspects. |
| Route-level `on_mount` (`Phoenix.LiveView.on_mount/1`) | Intentional divergence | Typed `LiveMountAspect`s run before both mounts, sign claims, reload typed context on join, compose in order, and can halt before connected mount. | Invalid returns and contradictory continue/redirect states are unrepresentable; websocket halt payloads still need to remain aligned. |
| Layouts and root layouts (`:layout`, `:root_layout`) | Native coverage substantial | Typed `LiveLayout` and `LiveRootLayout` cover router, session, and route composition, root precedence, and key-mismatch fallback. | Decide whether runtime layout changes from a LiveView are needed or intentionally replaced by route and session configuration. |
| `connected?/1` equivalent | Native parity covered | `LifecycleContext.connection` is `Connection.Disconnected` during HTTP rendering and `Connection.Connected(capabilities)` during websocket lifecycle phases. | Connected-only capabilities are available only from the matched branch. |
| Connected lifecycle resources (`connected?/1`, `terminate/2`) | Intentional divergence | `ConnectedResources.acquireRelease` ties acquisition and exactly-once finalization to one connected LiveView lifecycle; root and nested LiveViews remain independent even on one WebSocket. | Phoenix has no general finalizer registry. Scalive cleanup starts after the server observes termination; externally visible ownership still needs leases for node loss and network partitions. |
| Connect params and connect info (`get_connect_params/1`, `get_connect_info/2`) | Partial | `ConnectedMetadata.connectParams` exposes untrusted JSON join parameters on connected capabilities, mount aspects receive the request in both mount phases, and route context can carry typed data. | Scalive does not expose a typed connect-info API for peer, header, user-agent, or equivalent server-derived transport data. Phoenix-owned parameter keys remain protocol metadata rather than a stable application contract. |
| LiveView model lifecycle (`mount`, event, info, async, params, signal-backed view graphs) | Intentional divergence | Routed views receive decoded parameters in typed `mount`; typed messages, subscriptions, parameter handling, async completion messages, and read-only model signals replace assigns and callback tuples. | Continue mapping Phoenix callback behavior to typed Scalive runtime behavior rather than copying callback shapes. |
| Process-style callbacks (`handle_call/3`, `handle_cast/2`, `terminate/2`, `transport_pid/1`, `put_private/3`) | Not implemented | Scalive instead uses ZIO fibers, streams, connected resources, and typed context capabilities. | Determine whether any remaining process capabilities need useful Scala/ZIO equivalents; direct API parity is unlikely. |
| Error shapes and crash/reconnect behavior (integration tests and protocol errors) | Native coverage expanding | Unauthorized, stale, and redirect joins; invalid route/session failures; duplicate IDs; hook-stage errors; and redirect loops are covered in slices. | Systematically audit protocol errors, crash logging, stale joins, reconnect remount behavior, and transport failures. |

### Components And Lifecycle {#components-and-lifecycle}

| Area and upstream reference | Status | Current evidence | Known gap or decision |
| --- | --- | --- | --- |
| Root LiveView lifecycle hooks (`attach_hook/4`, `detach_hook/3`, `hooks_test.exs`) | Native parity covered | Event, params, info, async, and after-render hooks support static declarations, named dynamic attach/detach, halt/continue, replies, and duplicate-ID checks. | Results use typed ADTs; after-render hooks are side-effect-only and cannot introduce model state absent from the completed render. |
| LiveComponent lifecycle hooks | Native parity covered | Stateful components support static and dynamic event, async, and side-effect-only after-render hooks, including detach. | Hooks use Scalive's typed component API; post-render model mutation is intentionally excluded. |
| Stateful LiveComponents (`Phoenix.LiveComponent`, `live_components_test.exs`) | Native parity covered | Stable identities and CIDs, both mounts, add/update/remove, temporary removal and remount, duplicate rejection, local events/forms/uploads, targets, nesting, streams, async, flash, navigation, and client effects are covered. | `render_component/2`, CID-based external updates, and module/id-less `send_update` are intentionally not copied. |
| Component update APIs (`send_update/3`, `send_update_after/4`, `update_many/1`) | Partial | The connected `ComponentUpdates` capability exposes typed `sendUpdate` overloads by component instance or component class and ID; missing targets are ignored. | Typed delayed-update and batch-update equivalents remain undecided. |
| Nested LiveViews (`nested_test.exs`) | Native parity covered | Both mounts, dynamic children, recursive cleanup, duplicate rejection, sticky children, component nesting, and child navigation/redirect behavior are covered. | Keep aligned as component, route, and session behavior evolves. |

### Interaction And Rendering {#interaction-and-rendering}

| Area and upstream reference | Status | Current evidence | Known gap or decision |
| --- | --- | --- | --- |
| Live navigation (`push_patch/2`, `push_navigate/2`, `redirect/2`, `<.link>`) | Native coverage substantial | Typed patch, navigate, replace, and redirect operations cover URL resolution, payloads, flash carryover, live-session boundaries, history traversal, and rejection of non-HTTP schemes and control-character bypasses. | Audit client/server fallback behavior across sessions, root-layout changes, and route-specific mount claims. |
| Flash lifecycle (`flash_test.exs`) | Native parity covered | Keyed and full clear, `lv:clear-flash`, stale exclusion, redirect carryover and cookies, nested isolation, and child patch transfer are covered. | Phoenix ConnTest and LiveViewTest assertion helpers are intentionally not copied. |
| Forms and form events (form bindings, `Phoenix.Component.form/1`, `to_form/2`, recovery) | Native coverage substantial | Lossless payload parsing, typed `FormCodec`, change/submit bindings, used fields, submitter metadata, render helpers, component routing, dynamic-field recovery, and `phx-no-unused-field` are covered. | Decide which `to_form` and Ecto-specific behavior is out of scope. |
| Uploads (`Phoenix.LiveView.Upload` and upload integration tests) | Native coverage substantial | Allow, cancel, disallow, consume, and drop APIs; selected-versus-total entry limits; metadata validation; correlated progress; preflight/chunks; component routing; external uploaders; writer error reasons; and file-input helpers are covered. | Continue auditing destination cleanup, external-service verification, and abrupt process failure. |
| Streams (`stream/4`, insert, delete, async, configure) | Partial | Typed streams cover create, batch insert, reset, insert/delete/delete-by-DOM-ID, component scope, and Phoenix operations. `StreamRenderingSpec` verifies that a retained row keeps its binding ID while dispatch uses the row's newly committed value. | Add or intentionally exclude `stream_async`; expand edge-case coverage. Existing-stream batch/reset placement follows Phoenix, while creation omits Phoenix's ignored placement option. |
| Async tasks (`start_async_test.exs`) | Native coverage substantial | Typed named tasks for roots and components cover success, failure, cancellation, hooks, replacement, side effects, and deterministic cleanup. | Complex Phoenix task keys map to explicit typed string declarations; there is no task-supervisor option. |
| Async assigns (`assign_async_test.exs`) | Not implemented | `AsyncValue` represents empty, loading, success, failure, cancellation, and preserved previous values; applications update it through typed completion messages. | Add a typed field-level helper only if it improves the explicit flow without hiding model transitions. |
| JS commands (`Phoenix.LiveView.JS`) | Native coverage substantial | The command builder covers class, visibility, transition, dispatch, exec, focus, attribute, navigation, push, ignore-attribute, and server-pushed execution commands. | Audit command JSON when advancing beyond `v1.2.10`. |
| Client events and hooks (`push_event/3`, `handleEvent`, `phx-hook`) | Native coverage substantial | Directional typed payload contracts, root and component handlers, event diff payloads, required hook IDs, and raw-hook replies are covered. | JavaScript subscription and emission remain string-based; browser hook behavior is largely delegated to the upstream JS client. |
| DOM bindings and patch attributes (`phx-*`) | Native coverage substantial | Typed event/form/JS bindings, targets, upload progress, stream/ignore/update attributes, `phx-patch-focused`, `phx-no-unused-field`, lifecycle bindings, and connected/disconnected visibility are covered. | Audit all `phx-*` attributes and corresponding JS-client behavior when the client target advances. |
| Static asset tracking (`phx-track-static`, `_track_static`) | Native coverage substantial | Tracked static `href` and `src` values feed `ConnectedMetadata.staticChanged`; raw join parameters are available through connected `connectParams`; `LiveViewClientAssetsSpec` covers the pinned immutable client graph and the root-slice browser suite connects through it. | Audit exact `_track_static` metadata and reconnect parity without exposing Phoenix-owned keys as stable application state. |
| Title updates (`live_title`, `@page_title`) | Native coverage substantial | Typed `pageTitle(model)` drives disconnected HTML, connected diffs, fallback resets, and sticky rejoins; `liveTitle` supplies default, prefix, and suffix metadata. | Broaden navigation coverage for title ownership across routed root views. |
| Portals and focus wrap (`Phoenix.Component.portal/1`, `focus_wrap/1`) | Native coverage substantial | `portal` and `focusWrap` helpers are covered. | Keep aligned with browser E2E expectations. |
| HEEx templates and function components (`Phoenix.Component`, `~H`, `attr`, `slot`) | Intentional divergence | Typed Scala HTML builders and typed stateful components replace HEEx macros and assign maps. | HEEx will not be copied; use the [HTML and event bindings guide](../guides/html-dsl-and-event-bindings.md#build-an-html-tree) for Scalive patterns. |
| Verified routes and path helpers (`Phoenix.VerifiedRoutes`) | Intentional divergence | Typed route, path, and query codecs provide compile-time URL construction for Scalive APIs. | More ergonomic typed URL builders may be exposed from route declarations. |

### Security, Transport, And Tooling {#security-transport-and-tooling}

| Area and upstream reference | Status | Current evidence | Known gap or decision |
| --- | --- | --- | --- |
| Security and session tokens (signing, session, flash, CSRF, connect params) | Native coverage expanding | HMAC tokens with max age, signed mount claims and flash tokens, hardened redirects, root-layout session data, shared form/socket CSRF, checked POST fields, and stale invalid-CSRF joins are covered. | Token salt and MessagePack details remain open. Claims and flash values are signed, not encrypted. |
| Endpoint/socket configuration (`:live_view` config, socket path, hibernation) | Partial | `Live.router.withSocketPath(PathCodec[Unit])` configures the socket path; validated `ZioHttpConfig` configures signing, maximum age, secure cookies, and a non-empty exact WebSocket origin allowlist for `ZioHttp.routes`. | Decide which remaining endpoint options matter on ZIO HTTP; hibernation is not implemented. |
| WebSocket transport support (`Phoenix.Socket.Transport.check_origin/5`) | Native coverage substantial | WebSocket transport and the upload WebSocket protocol are implemented. Upgrade admission requires exactly one valid configured HTTP or HTTPS `Origin` and never trusts host or forwarding headers. | Scalive is intentionally stricter than Phoenix's host-only default and rejects a missing Origin rather than accepting it. Preserve this security divergence as transport support evolves. |
| Long-poll transport fallback | Not implemented | Long-poll is not implemented. | Implement when long-poll becomes a supported transport. |
| Telemetry and observability (Phoenix telemetry and logger metadata) | Native coverage substantial | The runtime emits correlated, ordered events for command acceptance, lifecycle turns, rendering, commits, publication, resources, failures, and termination; `RuntimeObservabilitySpec` covers ordering, redaction, correlation, and sink-defect isolation. | The event model is runtime-internal. Decide which stable Scalive/ZIO telemetry integration should become public rather than copying Phoenix telemetry names. |
| Test harness helpers (`Phoenix.LiveViewTest`) | Native coverage substantial, intentional divergence | `scalive-testing` supports disconnected semantic HTML queries, explicit ordinary GET/POST submission and 303 following, plus `ConnectedRender`/`ConnectedView` for production-admitted joins, typed messages, bindings, forms, nested views, and uploads. | Retain browser tests for real DOM patching and JavaScript behavior rather than cloning ConnTest or LiveViewTest. |

## Intentional Divergences {#intentional-divergences}

The following choices are not compatibility gaps by themselves:

- Typed immutable models and context capabilities replace socket assign maps.
- Typed message values replace stringly typed dispatch in application handlers.
- `ZIO` effects and explicit result ADTs replace callback tuples.
- The typed `pageTitle(model)` projection replaces the conventional
  `:page_title` assign.
- Static hooks are unnamed; dynamic hooks retain names only where attachment
  and detachment require identity.
- Typed `LiveMountAspect`s replace module-and-atom `on_mount` callbacks.
- Typed path and query codecs replace route macros and atom actions.
- Scala HTML builders and component values replace HEEx and component macros.
- Scalive-native testing APIs replace direct copies of Phoenix test helpers.
- Exact configured WebSocket origins replace Phoenix's host-only default and
  missing-Origin acceptance.

When strict API similarity conflicts with type safety, robustness, or Scala
ergonomics, Scalive prefers the better Scala API. For a conceptual mapping, read
[Phoenix LiveView concepts in Scalive](../guides/phoenix-live-view-orientation.md#map-the-core-concepts).

## Evidence And Verification {#evidence-and-verification}

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
  against the pinned [Phoenix LiveView v1.2.10 tests](https://github.com/phoenixframework/phoenix_live_view/tree/v1.2.10/test/e2e/tests);
- native [kernel tests](https://github.com/phfroidmont/scalive/blob/master/scalive/runtime/kernel/test/src/scalive/runtime/kernel/SessionKernelSpec.scala)
  and [connection supervision tests](https://github.com/phfroidmont/scalive/blob/master/scalive/runtime/connection/test/src/scalive/runtime/connection/ConnectionSupervisorSpec.scala);
- native [render tests](https://github.com/phfroidmont/scalive/blob/master/scalive/render/test/src/scalive/render/RenderProgramSpec.scala),
  [stream-row binding tests](https://github.com/phfroidmont/scalive/blob/master/scalive/render/test/src/scalive/render/StreamRenderingSpec.scala),
  and [Phoenix projection tests](https://github.com/phfroidmont/scalive/blob/master/scalive/protocol/phoenix/test/src/scalive/protocol/phoenix/PhoenixRenderedEncoderSpec.scala);
- transport [ZIO HTTP tests](https://github.com/phfroidmont/scalive/blob/master/scalive/transport/zio-http/test/src/scalive/ZioHttpSpec.scala)
  and the production-admitted [connected test harness suite](https://github.com/phfroidmont/scalive/blob/master/scalive/testing/test/src/scalive/testing/ConnectedRenderSpec.scala).

When evaluating a feature, check its row's status, evidence, and known gap rather
than relying on the existence of a similarly named API. For a compatibility bug,
follow the [issue reporting guidance](index.md#report-an-issue) and include the
  upstream `v1.2.10` documentation or test that defines the expected behavior.
