# Scalive User-Facing API Assessment

## Executive Summary

Scalive exposes a compact Scala-first LiveView API centered on typed `LiveView[Msg, Model]`, typed `LiveComponent[Props, Msg, Model]`, an HTML DSL exported through `scalive.*`, and phase-specific lifecycle contexts. The strongest current API qualities are typed messages, typed models, typed runtime identifiers, bidirectional route-derived locations, and explicit capability facades. The main remaining assessment risks are narrower documentation gaps and parity areas whose behavior is implemented but not fully mapped to upstream evidence.

This report is a current-state audit. It does not define implementation order or a roadmap.

## Scope And Methodology

Scope follows `doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md` and covers public APIs application authors use directly, examples, docs, and Phoenix LiveView `v1.1.28` parity evidence.

Method:

- Inventory public APIs from `scalive/src/scalive` and public docs.
- Compare documentation claims against implementation.
- Review examples for beginner ergonomics and recommended style.
- Review upstream compatibility and E2E fixture gap tracking.
- Separate proven gaps from suspected gaps.
- Avoid roadmap sequencing.

## API Surface Inventory

### Core Lifecycle

- `LiveView[Msg, Model]` defines `mount`, `handleMessage`, `render`, and optional `hooks`. Evidence: `scalive/src/scalive/LiveView.scala:6`.
- `RoutedLiveView[Msg, Model, Params]` adds `handleParams` and `handleParamsDecodeError`. Evidence: `scalive/src/scalive/LiveView.scala:19`.
- `LiveComponent[Props, Msg, Model]` defines typed props, typed messages, typed model lifecycle, and `ComponentRef[Msg]` rendering. Evidence: `scalive/src/scalive/LiveComponent.scala:5`.

### Package-Level DSL

- `scalive.*` exports generated tags, attrs, streams, uploads, components, HTML helpers, `link`, and `phx` bindings. Evidence: `scalive/src/scalive/Scalive.scala:12`.
- `liveComponent`, `liveView`, `flash`, `portal`, and implicit text/tag conversions are available as top-level helpers. Evidence: `scalive/src/scalive/Scalive.scala:18`, `scalive/src/scalive/Scalive.scala:19`, `scalive/src/scalive/Scalive.scala:26`, `scalive/src/scalive/Scalive.scala:36`, `scalive/src/scalive/Scalive.scala:49`, `scalive/src/scalive/Scalive.scala:191`.

### HTML DSL, Bindings, And JS Commands

- Generated HTML tags and attributes are mixed into the package object, while `HtmlElement`, `HtmlTag`, `HtmlAttr`, and `HtmlAttrBinding` define the public HTML model and message binding entry points. Evidence: `scalive/src/scalive/Scalive.scala:12`, `scalive/src/scalive/HtmlElement.scala:9`, `scalive/src/scalive/HtmlElement.scala:21`, `scalive/src/scalive/HtmlElement.scala:30`, `scalive/src/scalive/HtmlElement.scala:41`.
- `phx` exposes event, form, lifecycle, hook, upload, targeting, rate-limit, value, update, and static-tracking bindings. Evidence: `scalive/src/scalive/Scalive.scala:93`, `scalive/src/scalive/Scalive.scala:115`, `scalive/src/scalive/Scalive.scala:135`, `scalive/src/scalive/Scalive.scala:140`, `scalive/src/scalive/Scalive.scala:157`, `scalive/src/scalive/Scalive.scala:162`, `scalive/src/scalive/Scalive.scala:168`, `scalive/src/scalive/Scalive.scala:170`, `scalive/src/scalive/Scalive.scala:176`, `scalive/src/scalive/Scalive.scala:185`, `scalive/src/scalive/Scalive.scala:186`.
- JS commands are built from `JS`/`JSCommands.JSCommand` with operations for class changes, dispatch, exec, focus, show/hide, navigation, patching, push events, and attributes. Evidence: `scalive/src/scalive/JS.scala:6`, `scalive/src/scalive/JS.scala:8`, `scalive/src/scalive/JS.scala:57`, `scalive/src/scalive/JS.scala:61`, `scalive/src/scalive/JS.scala:79`, `scalive/src/scalive/JS.scala:88`, `scalive/src/scalive/JS.scala:100`, `scalive/src/scalive/JS.scala:125`, `scalive/src/scalive/JS.scala:128`, `scalive/src/scalive/JS.scala:137`, `scalive/src/scalive/JS.scala:171`, `scalive/src/scalive/JS.scala:174`.

### Forms

- Forms expose lossless `FormData`, typed decoding through `FormCodec`, state/error tracking through `FormState`, and rendered controls through `Form` and `Form.Field`. Evidence: `scalive/src/scalive/forms/FormData.scala:7`, `scalive/src/scalive/forms/FormCodec.scala:3`, `scalive/src/scalive/forms/FormState.scala:5`, `scalive/src/scalive/forms/Form.scala:5`, `scalive/src/scalive/forms/Form.scala:142`.
- Form event bindings are available via both `phx.onChangeForm`/`phx.onSubmitForm` and `Form.onChange`/`Form.onSubmit`. Evidence: `scalive/src/scalive/Scalive.scala:142`, `scalive/src/scalive/Scalive.scala:144`, `scalive/src/scalive/Scalive.scala:146`, `scalive/src/scalive/Scalive.scala:148`, `scalive/src/scalive/forms/Form.scala:6`, `scalive/src/scalive/forms/Form.scala:9`.

### Streams

- Streams expose `LiveStreamDef`, `LiveStream`, `StreamAt`, and `StreamLimit`, with public operations through the `Streams` phase facade. Evidence: `scalive/src/scalive/streams/LiveStream.scala:6`, `scalive/src/scalive/streams/LiveStream.scala:18`, `scalive/src/scalive/streams/LiveStream.scala:28`, `scalive/src/scalive/streams/LiveStream.scala:51`, `scalive/src/scalive/LiveContext.scala:114`.
- Stream rendering integrates with keyed content state exposed by the HTML model. Evidence: `scalive/src/scalive/HtmlElement.scala:99`, `scalive/src/scalive/CollectionOps.scala:15-36`.

### Uploads

- Uploads expose accepted types, upload state, entries, errors, external upload callbacks, progress callbacks, writers, and options. Evidence: `scalive/src/scalive/upload/LiveUpload.scala:9`, `scalive/src/scalive/upload/LiveUpload.scala:16`, `scalive/src/scalive/upload/LiveUpload.scala:25`, `scalive/src/scalive/upload/LiveUpload.scala:64`, `scalive/src/scalive/upload/LiveUpload.scala:79`, `scalive/src/scalive/upload/LiveUpload.scala:92`, `scalive/src/scalive/upload/LiveUpload.scala:101`, `scalive/src/scalive/upload/LiveUpload.scala:105`, `scalive/src/scalive/upload/LiveUpload.scala:113`, `scalive/src/scalive/upload/LiveUpload.scala:119`, `scalive/src/scalive/upload/LiveUpload.scala:153`, `scalive/src/scalive/upload/LiveUpload.scala:156`.
- Upload lifecycle operations are available through `ctx.uploads`, and rendering helpers include `liveFileInput` and upload error helpers. Evidence: `scalive/src/scalive/LiveContext.scala:105`, `scalive/src/scalive/defs/components/Components.scala:21`, `scalive/src/scalive/defs/components/Components.scala:44`.

### Phase Contexts

- Lifecycle contexts expose `connected`, `staticChanged`, and `connectParams`. Evidence: `scalive/src/scalive/LiveContext.scala:13`.
- Mount, message, params, after-render, and component contexts expose phase-specific capability facades. Evidence: `scalive/src/scalive/LiveContext.scala:18`.
- Navigation, flash, uploads, streams, async, subscriptions, client events, title updates, component updates, and hooks are explicit context facades rather than ambient services. Evidence: `scalive/src/scalive/LiveContext.scala:18`, `scalive/src/scalive/LiveContext.scala:29`, `scalive/src/scalive/LiveContext.scala:41`, `scalive/src/scalive/LiveContext.scala:53`, `scalive/src/scalive/LiveContext.scala:57`, `scalive/src/scalive/LiveContext.scala:65`, `scalive/src/scalive/LiveContext.scala:73`.

### Navigation And Routing

- Safe navigation is available through route-derived `LiveLocation` values consumed by `link.navigate`, `link.patch`, `link.patchReplace`, `MountNavigation`, `Navigation`, and JS commands. Raw destinations remain available only through explicitly named unsafe methods. Evidence: `scalive/src/scalive/LiveLocation.scala:6`, `scalive/src/scalive/Scalive.scala:83`, `scalive/src/scalive/LiveContext.scala:86`, `scalive/src/scalive/JS.scala:125`.
- Routing starts from `Live.route`/`live`, composes ZIO HTTP `PathCodec` and query codecs, and can build plain, request-aware, params-aware, and context-aware routes. Evidence: `scalive/src/scalive/routing/LiveRouteDsl.scala:13`, `scalive/src/scalive/routing/LiveRouteDsl.scala:14`, `scalive/src/scalive/routing/LiveRouteDsl.scala:26`, `scalive/src/scalive/routing/LiveRouteDsl.scala:34`, `scalive/src/scalive/routing/LiveRouteDsl.scala:82`, `scalive/src/scalive/routing/LiveRouteDsl.scala:91`, `scalive/src/scalive/routing/LiveRouteDsl.scala:103`, `scalive/src/scalive/routing/LiveRouteDsl.scala:546`, `scalive/src/scalive/routing/LiveRouteDsl.scala:558`.

### Layouts And Mount Aspects

- Layouts are public `LiveLayout`, `LiveRootLayout`, and `LiveLayoutContext` APIs and can be applied at route, session, or router scope. Evidence: `scalive/src/scalive/routing/LiveLayouts.scala:6`, `scalive/src/scalive/routing/LiveLayouts.scala:12`, `scalive/src/scalive/routing/LiveLayouts.scala:27`, `scalive/src/scalive/routing/LiveRouteDsl.scala:75`, `scalive/src/scalive/routing/LiveRouteDsl.scala:78`, `scalive/src/scalive/routing/LiveRouteDsl.scala:429`, `scalive/src/scalive/routing/LiveRouteDsl.scala:438`, `scalive/src/scalive/routing/LiveRouteDsl.scala:509`, `scalive/src/scalive/routing/LiveRouteDsl.scala:512`.
- Mount aspects expose disconnected and connected mount phases, signed claims, failure outcomes, composition, and request-derived context helpers. Evidence: `scalive/src/scalive/LiveMountAspect.scala:8`, `scalive/src/scalive/LiveMountAspect.scala:16`, `scalive/src/scalive/LiveMountAspect.scala:37`, `scalive/src/scalive/LiveMountAspect.scala:44`, `scalive/src/scalive/LiveMountAspect.scala:49`, `scalive/src/scalive/LiveMountAspect.scala:56`, `scalive/src/scalive/LiveMountAspect.scala:114`, `scalive/src/scalive/LiveMountAspect.scala:120`.

### Async, Subscriptions, Flash, And Client Events

- Async tasks are exposed through `AsyncValue`, `LiveAsyncEvent`, `LiveAsyncResult`, and the `ctx.async` facade. Evidence: `scalive/src/scalive/LiveAsync.scala:7`, `scalive/src/scalive/LiveAsync.scala:61`, `scalive/src/scalive/LiveAsync.scala:65`, `scalive/src/scalive/LiveContext.scala:134`.
- Subscriptions are exposed through `ctx.subscriptions.start`, `replace`, and `cancel`. Evidence: `scalive/src/scalive/LiveContext.scala:138`, `scalive/src/scalive/SubscriptionRuntime.scala:6`.
- Flash is available through the `ctx.flash` facade and the render-time `flash(kind)` helper. Evidence: `scalive/src/scalive/LiveContext.scala:98`, `scalive/src/scalive/Scalive.scala:43`.
- Typed client events are exposed as `ctx.client.push`, and client JS execution is exposed as `ctx.client.exec`. Evidence: `scalive/src/scalive/ClientEvent.scala:3`, `scalive/src/scalive/LiveContext.scala:148`, `scalive/src/scalive/ClientEventRuntime.scala:6`.

### Static Assets And Token/Session Configuration

- Static assets expose classpath and directory configuration, manifest entries, digested path helpers, tracked stylesheet/script helpers, and asset routes. Evidence: `scalive/src/scalive/StaticAssets.scala:12`, `scalive/src/scalive/StaticAssets.scala:18`, `scalive/src/scalive/StaticAssets.scala:32`, `scalive/src/scalive/StaticAssets.scala:71`, `scalive/src/scalive/StaticAssets.scala:78`, `scalive/src/scalive/StaticAssets.scala:96`, `scalive/src/scalive/StaticAssets.scala:102`, `scalive/src/scalive/StaticAssets.scala:111`, `scalive/src/scalive/StaticAssets.scala:114`, `scalive/src/scalive/StaticAssets.scala:117`, `scalive/src/scalive/StaticAssets.scala:120`, `scalive/src/scalive/StaticAssets.scala:123`.
- Token/session configuration is public through `TokenConfig`, `Live.session`, `Live.socketAt`, `Live.tokenConfig`, and router/session `@@` modifiers. Evidence: `scalive/src/scalive/protocol/Token.scala:22`, `scalive/src/scalive/protocol/Token.scala:24`, `scalive/src/scalive/routing/LiveRouteDsl.scala:449`, `scalive/src/scalive/routing/LiveRouteDsl.scala:500`, `scalive/src/scalive/routing/LiveRouteDsl.scala:501`, `scalive/src/scalive/routing/LiveRouteDsl.scala:517`, `scalive/src/scalive/routing/LiveRouteDsl.scala:520`, `scalive/src/scalive/routing/LiveRouteDsl.scala:549`, `scalive/src/scalive/routing/LiveRouteDsl.scala:552`, `scalive/src/scalive/routing/LiveRouteDsl.scala:555`.
- Signed live session payloads carry session name, flash, mount claims, route mount claim metadata, and root layout key. Evidence: `scalive/src/scalive/routing/LiveSessionPayload.scala:6`, `scalive/src/scalive/routing/LiveSessionPayload.scala:23`, `scalive/src/scalive/routing/LiveSessionPayload.scala:38`.

## Ergonomics Findings

### Medium - Discoverability - Route and session modifiers rely heavily on symbolic `@@`

Evidence: `scalive/src/scalive/routing/LiveRouteDsl.scala:63`, `scalive/src/scalive/routing/LiveRouteDsl.scala:75`, `scalive/src/scalive/routing/LiveRouteDsl.scala:79`, `scalive/src/scalive/routing/LiveRouteDsl.scala:517`, `scalive/src/scalive/routing/LiveRouteDsl.scala:521`, `doc/api-improvement-ideas.md:193`.

The `@@` operator composes mount aspects, live layouts, root layouts, socket mount configuration, and token configuration. This keeps declarations compact but makes the API harder to search and harder to learn without examples.

Impact: new users may need to read implementation or examples to understand route composition.

Confidence: Medium.

### Low - Ergonomics - Static and eventless views still require message-handler boilerplate

Evidence: `scalive/src/scalive/LiveView.scala:15`, `example/src/HomeLiveView.scala:14`, `doc/api-improvement-ideas.md:143`.

`LiveView` requires `handleMessage` even when a view has no meaningful server messages.

Impact: simple pages look heavier than their behavior requires.

Confidence: High.

## Polish And Discoverability Findings

### Medium - Docs - Newcomer README stops short of a complete installation quickstart

Evidence: `README.md:1`, `README.md:10`, `README.md:39`, `README.md:46`, `README.md:60`.

The root README now introduces Scalive, shows a first LiveView, points to server and client setup examples, gives project test commands, and distinguishes human examples from parity fixtures. It does not provide dependency coordinates or a minimal application setup that a new user can follow without consulting the example and build files.

Impact: users can understand the project and find the right source material, but creating a new application still requires reverse-engineering repository setup.

Confidence: High.

### Low - Polish - Some examples demonstrate escape hatches before high-level APIs

Evidence: `example/src/TodoLiveView.scala:42`, `README.md:65`, `doc/api-improvement-ideas.md:268`, `doc/api-improvement-ideas.md:272`.

The README now distinguishes human examples from parity fixtures, but the beginner Todo example still uses a raw form map where typed form APIs could demonstrate the recommended application style.

Impact: the typed API appears less complete than it is.

Confidence: Medium.

## Upstream Parity Findings

### High - Parity - Full upstream browser parity cannot be claimed while `Issue4088LiveView` remains tracked

Evidence: `doc/e2e-fixture-parity-gaps.md:7`, `doc/e2e-fixture-parity-gaps.md:66`.

The fixture gap document explicitly says not to claim full Phoenix LiveView `v1.1.28` upstream browser parity until every gap is closed or reclassified with evidence. The remaining tracked gap is `Issue4088LiveView`, involving a hook inside a locked LiveComponent container pushing repeated targeted events to `@myself`.

Impact: external compatibility claims need a caveat even if the upstream browser harness is broadly green.

Confidence: High.

### High - Parity - Compatibility evidence is strongest for browser E2E baseline and core native slices

Evidence: `UPSTREAM_COMPATIBILITY.md:24`, `scripts/e2e-run-upstream.sh:6`, `scripts/e2e-run-upstream.sh:114`, `test/playwright.upstream.config.js:15`, `test/playwright.upstream.config.js:25`, `scalive/test/src/scalive/LiveComponentParitySpec.scala:16`, `scalive/test/src/scalive/LiveComponentParitySpec.scala:147`.

The project has an upstream Playwright harness against Scalive fixtures and native tests for core runtime areas. This gives meaningful evidence for behavior, but the compatibility matrix correctly distinguishes baseline browser coverage from exhaustive server-side parity.

Impact: parity confidence is real but uneven by feature area.

Confidence: High.

### High - Parity - Several high-priority runtime areas still need systematic parity audit

Evidence: `UPSTREAM_COMPATIBILITY.md:25`, `UPSTREAM_COMPATIBILITY.md:26`, `UPSTREAM_COMPATIBILITY.md:55`, `UPSTREAM_COMPATIBILITY.md:60`.

Wire protocol and diff encoding, static render/bootstrap, security/session tokens, and error/crash/reconnect behavior are marked substantial or expanding rather than fully mapped. Remaining work calls out exact error payloads, reconnect/stale cases, protocol additions, crash logging, and recovery behavior.

Impact: these areas affect user-visible reliability and production confidence more than surface syntax.

Confidence: High.

### Medium - Parity - Feature areas with partial or expanding coverage are clearly identified

Evidence: `UPSTREAM_COMPATIBILITY.md:32`, `UPSTREAM_COMPATIBILITY.md:38`, `UPSTREAM_COMPATIBILITY.md:42`, `UPSTREAM_COMPATIBILITY.md:43`, `UPSTREAM_COMPATIBILITY.md:44`, `UPSTREAM_COMPATIBILITY.md:47`, `UPSTREAM_COMPATIBILITY.md:49`, `UPSTREAM_COMPATIBILITY.md:51`, `UPSTREAM_COMPATIBILITY.md:56`, `UPSTREAM_COMPATIBILITY.md:57`, `UPSTREAM_COMPATIBILITY.md:58`.

Connect params/info, component update APIs, forms, uploads, streams, JS commands, DOM bindings, title updates, endpoint configuration, transport fallback, and telemetry have incomplete mapping or known feature decisions remaining.

Impact: users can build substantial applications, but some Phoenix feature expectations require checking Scalive-specific support.

Confidence: High.

### Medium - Parity - Intentional divergences are well framed but need user-facing explanations

Evidence: `UPSTREAM_COMPATIBILITY.md:62`, `UPSTREAM_COMPATIBILITY.md:66`, `UPSTREAM_COMPATIBILITY.md:70`, `UPSTREAM_COMPATIBILITY.md:71`.

Typed models, typed messages, ZIO effects, mount aspects, typed routing, Scala HTML builders, and future Scalive-native test helpers are intentional replacements for Phoenix concepts.

Impact: these are API strengths, but without user-facing mapping they can look like missing parity to Phoenix users.

Confidence: High.

## Documentation And Example Findings

### Medium - Docs - Compatibility guidance exists but is not a user-facing Phoenix migration guide

Evidence: `UPSTREAM_COMPATIBILITY.md:62`, `UPSTREAM_COMPATIBILITY.md:66`, `UPSTREAM_COMPATIBILITY.md:70`, `doc/api-improvement-ideas.md:87`.

Intentional divergences are listed for maintainers, but there is no guide that maps Phoenix concepts to Scalive concepts for users evaluating or migrating from Phoenix LiveView.

Impact: parity may be underestimated because Scala-first replacements are not explained from the user's perspective.

Confidence: Medium.

### Medium - Examples - Core examples demonstrate the mental model well

Evidence: `example/src/CounterLiveView.scala:7`, `example/src/CounterLiveView.scala:9`, `example/src/CounterLiveView.scala:22`, `example/src/CounterLiveView.scala:30`, `example/src/Example.scala:33`, `example/src/Example.scala:43`, `example/src/RootLayout.scala:13`, `example/src/RootLayout.scala:20`.

The examples show typed messages, typed models, `LiveIO`, rendering, event bindings, routing, static assets, and root layout setup.

Impact: once discovered, the examples provide a useful starting point for the core API.

Confidence: High.

## Risk Register

### High - Compatibility claims can outrun evidence

Evidence: `doc/e2e-fixture-parity-gaps.md:7`, `UPSTREAM_COMPATIBILITY.md:81`.

The upstream browser harness is valuable, but fixture honesty and native coverage mapping still matter. The project should avoid public claims of complete Phoenix LiveView parity until every tracked gap is closed or reclassified with evidence.

Impact: over-claiming parity can create user trust issues when edge cases fail.

Confidence: High.

## Open Questions And Confidence Notes

- High confidence: core public API inventory and the `Issue4088LiveView` parity caveat.
- Medium confidence: exact completeness of JS command, form recovery, upload, and stream edge-case parity; the compatibility matrix identifies these areas, but this audit does not execute every upstream scenario.
- Open question: which intentional Phoenix divergences need user-facing migration documentation first is outside this report's scope because that becomes roadmap sequencing.
- Open question: whether Scalive should expose direct typed equivalents for every partial Phoenix feature is outside this report's scope because API quality can override direct shape parity.
