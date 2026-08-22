# User-Facing API Assessment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a current-state audit report for Scalive's user-facing API covering ergonomics, polish, and Phoenix LiveView `v1.1.28` feature parity.

**Architecture:** This is a documentation/audit deliverable. The implementation creates one report, `doc/user-facing-api-assessment.md`, backed by source, docs, examples, tests, and upstream compatibility references. Each task adds one coherent section group and verifies it with source reads/searches rather than software tests.

**Tech Stack:** Markdown, Scala 3 source inspection, existing Scalive docs, Mill-based project layout, Phoenix LiveView upstream compatibility docs and E2E fixture references.

## Global Constraints

- Scope is current-state assessment only; do not write a roadmap or implementation sequencing.
- Prefer Scala-first API quality over direct Elixir API shape when Scalive intentionally diverges.
- Treat behavior and feature-set parity as the compatibility target, not Phoenix internal implementation parity.
- Separate documentation drift from implementation gaps.
- Do not over-claim browser parity while tracked fixture gaps remain.
- Back every finding with file references.
- Do not modify production Scala code.
- Do not commit changes unless the user explicitly requests a commit.

---

## File Structure

- Create: `doc/user-facing-api-assessment.md`
  - Responsibility: final audit report, including summary, methodology, inventory, findings, parity state, docs/examples state, risks, and confidence notes.
- Read: `doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md`
  - Responsibility: approved scope and acceptance criteria.
- Read: `UPSTREAM_COMPATIBILITY.md`
  - Responsibility: upstream target, compatibility matrix, known parity priorities.
- Read: `doc/public-api-reference.md`
  - Responsibility: intended public API claims and possible documentation drift.
- Read: `doc/api-improvement-ideas.md`
  - Responsibility: existing known API concerns, used as evidence but not as roadmap.
- Read: `doc/e2e-fixture-parity-gaps.md`
  - Responsibility: browser fixture honesty gaps and parity caveats.
- Read: `scalive/src/scalive/**/*.scala`
  - Responsibility: actual user-facing API implementation.
- Read: `example/src/**/*.scala` and `e2eApp/src/**/*.scala`
  - Responsibility: beginner examples, advanced examples, and parity fixtures.
- Read: `scalive/test/src/scalive/**/*.scala`
  - Responsibility: native coverage evidence.

---

### Task 1: Create Report Skeleton And API Inventory

**Files:**
- Create: `doc/user-facing-api-assessment.md`
- Read: `doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md`
- Read: `scalive/src/scalive/Scalive.scala`
- Read: `scalive/src/scalive/LiveView.scala`
- Read: `scalive/src/scalive/LiveComponent.scala`
- Read: `scalive/src/scalive/LiveContext.scala`
- Read: `scalive/src/scalive/routing/LiveRouteDsl.scala`
- Read: `doc/public-api-reference.md`

**Interfaces:**
- Consumes: approved scope from `doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md`.
- Produces: `doc/user-facing-api-assessment.md` with stable report headings and a complete API surface inventory that later tasks append findings to.

- [ ] **Step 1: Re-read approved spec**

Run: `grep -n "^## \|^- \|^1\." doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md`

Expected: output includes `Purpose`, `Scope`, `Report Structure`, `Methodology`, `Assessment Principles`, and `Acceptance Criteria`.

- [ ] **Step 2: Inventory package exports and lifecycle traits**

Run: `grep -n "package object scalive\|trait LiveView\|trait RoutedLiveView\|trait LiveComponent\|trait LifecycleContext\|trait MountContext\|trait MessageContext\|trait ParamsContext\|trait ComponentMountContext\|class LiveRouteSeed" scalive/src/scalive/Scalive.scala scalive/src/scalive/LiveView.scala scalive/src/scalive/LiveComponent.scala scalive/src/scalive/LiveContext.scala scalive/src/scalive/routing/LiveRouteDsl.scala`

Expected: output identifies the core public API files and line numbers for exports, lifecycle traits, contexts, and routing entry points.

- [ ] **Step 3: Create the initial report**

Create `doc/user-facing-api-assessment.md` with this content, filling only facts directly verified in Steps 1 and 2:

```markdown
# Scalive User-Facing API Assessment

## Executive Summary

Scalive exposes a compact Scala-first LiveView API centered on typed `LiveView[Msg, Model]`, typed `LiveComponent[Props, Msg, Model]`, an HTML DSL exported through `scalive.*`, and phase-specific lifecycle contexts. The strongest current API qualities are typed messages, typed models, route param decoding, and explicit capability facades. The main assessment risks are documentation drift, stringly typed escape points, and parity areas whose behavior is implemented but not fully mapped to upstream evidence.

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

### Phase Contexts

- Lifecycle contexts expose `connected`, `staticChanged`, and `connectParams`. Evidence: `scalive/src/scalive/LiveContext.scala:13`.
- Mount, message, params, after-render, and component contexts expose phase-specific capability facades. Evidence: `scalive/src/scalive/LiveContext.scala:18`.

### Routing

- `LiveRouteSeed` composes path codecs, params, query codecs, layouts, mount aspects, and LiveView builders. Evidence: `scalive/src/scalive/routing/LiveRouteDsl.scala:13`.

## Ergonomics Findings

## Polish And Discoverability Findings

## Upstream Parity Findings

## Documentation And Example Findings

## Risk Register

## Open Questions And Confidence Notes
```

- [ ] **Step 4: Verify skeleton headings**

Run: `grep -n "^## " doc/user-facing-api-assessment.md`

Expected: output lists exactly these top-level report sections: `Executive Summary`, `Scope And Methodology`, `API Surface Inventory`, `Ergonomics Findings`, `Polish And Discoverability Findings`, `Upstream Parity Findings`, `Documentation And Example Findings`, `Risk Register`, `Open Questions And Confidence Notes`.

---

### Task 2: Add Ergonomics Findings

**Files:**
- Modify: `doc/user-facing-api-assessment.md`
- Read: `doc/api-improvement-ideas.md`
- Read: `example/src/*.scala`
- Read: `scalive/src/scalive/Scalive.scala`
- Read: `scalive/src/scalive/LiveContext.scala`
- Read: `scalive/src/scalive/HtmlElement.scala`
- Read: `scalive/src/scalive/LiveAsync.scala`
- Read: `scalive/src/scalive/streams/LiveStream.scala`
- Read: `scalive/src/scalive/upload/LiveUpload.scala`

**Interfaces:**
- Consumes: `doc/user-facing-api-assessment.md` headings from Task 1.
- Produces: complete `Ergonomics Findings` section with severity, category, evidence, impact, and confidence for each finding.

- [ ] **Step 1: Search for known ergonomics evidence**

Run: `grep -n "typed outbound routes\|upload writer\|stream state\|Reduce boilerplate\|component targeting\|root layout\|@@\|typed wrappers\|withValue\|JS.push" doc/api-improvement-ideas.md`

Expected: output includes line references for typed outbound routes, upload writer state, stream state, simple LiveView boilerplate, component targeting, root layout ergonomics, broad `@@`, repeated string concepts, unsafe value bindings, and `JS.push(pageLoading)`.

- [ ] **Step 2: Verify implementation evidence for each finding**

Run: `grep -n "def navigate\|def patch\|def pushNavigate\|def pushPatch\|def component\|def withValue\|def withBoolValue\|case class LiveStream\|class LiveUploadWriterState\|def start\[A\](name: String)\|def allow(name: String" scalive/src/scalive/Scalive.scala scalive/src/scalive/LiveContext.scala scalive/src/scalive/HtmlElement.scala scalive/src/scalive/streams/LiveStream.scala scalive/src/scalive/upload/LiveUpload.scala`

Expected: output includes implementation references for string navigation, component helper overloads, throwing value extraction, stream state naming, private upload writer state, string async/upload names, and string upload names.

- [ ] **Step 3: Replace the empty ergonomics section**

In `doc/user-facing-api-assessment.md`, replace the empty `## Ergonomics Findings` section with this content, adjusting line numbers to match the command output from Steps 1 and 2:

```markdown
## Ergonomics Findings

### High - API Design - Outbound navigation is less typed than inbound routing

Evidence: `scalive/src/scalive/Scalive.scala:83`, `scalive/src/scalive/LiveContext.scala:86`, `doc/api-improvement-ideas.md:31`.

Inbound routes and route params use typed codecs, but `link.navigate`, `link.patch`, `ctx.nav.pushNavigate`, `ctx.nav.pushPatch`, redirects, and JS navigation accept raw strings. Application authors get strong safety when decoding incoming URLs but not when rendering links or navigation effects.

Impact: route refactors can silently break outbound links, patches, and redirects.

Confidence: High.

### High - Correctness - Attribute value bindings can throw for normal client payload variation

Evidence: `scalive/src/scalive/HtmlElement.scala:60`, `doc/api-improvement-ideas.md:103`.

`withValue` and `withBoolValue` read the event payload's `value` key directly or decode a narrow boolean shape. Missing values or unexpected values can become runtime failures instead of typed optional or validation results.

Impact: common form and input event patterns can fail at runtime in app code.

Confidence: High.

### High - API Design - Upload writer extension point is not externally implementable cleanly

Evidence: `scalive/src/scalive/upload/LiveUpload.scala:111`, `doc/api-improvement-ideas.md:46`.

`LiveUploadWriter.init` returns `LiveUploadWriterState`, but that state has a `private[scalive]` constructor. The public API advertises custom writer extensibility while making external implementations awkward or impossible without a public state construction path.

Impact: advanced upload integrations such as filesystem, S3-compatible, or streaming writers are blocked by API shape rather than runtime capability.

Confidence: High.

### Medium - API Design - Stream public state mixes durable state and pending commands

Evidence: `scalive/src/scalive/streams/LiveStream.scala:51`, `doc/api-improvement-ideas.md:60`.

`LiveStream.entries` is public but represents pending insert entries, not the full durable stream contents. That name invites application authors to treat stream runtime command state as business state.

Impact: users can write code that appears natural but depends on implementation details and becomes confusing under deletes, resets, and limits.

Confidence: High.

### Medium - API Design - Repeated string identifiers make invalid states easy

Evidence: `scalive/src/scalive/LiveContext.scala:98`, `scalive/src/scalive/LiveContext.scala:105`, `scalive/src/scalive/LiveContext.scala:134`, `doc/api-improvement-ideas.md:205`.

Flash kinds, upload names, stream definitions, async names, subscription names, client event names, selectors, hook IDs, and paths are largely represented as plain strings.

Impact: app code can accidentally mix unrelated identifiers, with failures appearing only at runtime or in the browser.

Confidence: Medium.

### Medium - Discoverability - Component rendering and component targeting share the same helper name

Evidence: `scalive/src/scalive/Scalive.scala:19`, `scalive/src/scalive/Scalive.scala:21`, `doc/api-improvement-ideas.md:169`.

`component(cid, element)` renders component diff content internally, while `component[C](message)` creates a component event target. The shared name hides two different concepts behind overloads.

Impact: app authors may struggle to discover the correct helper for component event routing.

Confidence: High.

### Medium - Discoverability - Route and session modifiers rely heavily on symbolic `@@`

Evidence: `scalive/src/scalive/routing/LiveRouteDsl.scala:63`, `scalive/src/scalive/routing/LiveRouteDsl.scala:75`, `doc/api-improvement-ideas.md:193`.

The `@@` operator composes mount aspects, live layouts, root layouts, socket mount configuration, and token configuration. This keeps declarations compact but makes the API harder to search and harder to learn without examples.

Impact: new users may need to read implementation or examples to understand route composition.

Confidence: Medium.

### Low - Ergonomics - Static and eventless views still require message-handler boilerplate

Evidence: `scalive/src/scalive/LiveView.scala:15`, `example/src/HomeLiveView.scala:14`, `doc/api-improvement-ideas.md:143`.

`LiveView` requires `handleMessage` even when a view has no meaningful server messages.

Impact: simple pages look heavier than their behavior requires.

Confidence: High.
```

- [ ] **Step 4: Verify every ergonomics finding has required fields**

Run: `grep -n "^### .* - .* - \|^Evidence:\|^Impact:\|^Confidence:" doc/user-facing-api-assessment.md`

Expected: every ergonomics finding has one severity heading, one `Evidence:`, one `Impact:`, and one `Confidence:` line.

---

### Task 3: Add Polish, Discoverability, And Documentation Findings

**Files:**
- Modify: `doc/user-facing-api-assessment.md`
- Read: `doc/public-api-reference.md`
- Read: `doc/api-improvement-ideas.md`
- Read: `example/src/*.scala`
- Read: `e2eApp/src/*.scala`
- Read: `scalive/src/scalive/LiveContext.scala`
- Read: `scalive/src/scalive/upload/LiveUpload.scala`
- Read: `scalive/src/scalive/JS.scala`

**Interfaces:**
- Consumes: report file with inventory and ergonomics findings from Tasks 1 and 2.
- Produces: complete `Polish And Discoverability Findings` and `Documentation And Example Findings` sections.

- [ ] **Step 1: Verify no root README is present**

Run: `ls README*`

Expected: command exits non-zero with no matching README file in the repository root.

- [ ] **Step 2: Search for documentation drift evidence**

Run: `grep -n "LiveComponent\|RIO\[LiveContext\|JS.patch\|link.patch\|connectParams\|LiveEventHookResult\|LiveEventResult" doc/public-api-reference.md scalive/src/scalive/LiveComponent.scala scalive/src/scalive/LiveContext.scala scalive/src/scalive/upload/LiveUpload.scala scalive/src/scalive/JS.scala scalive/src/scalive/Scalive.scala`

Expected: output includes public docs references and implementation references sufficient to distinguish current API from stale or inconsistent documentation claims.

- [ ] **Step 3: Search examples for beginner-facing and advanced-only patterns**

Run: `grep -n "handleMessage\|link.navigate\|link.patch\|phx.onSubmit\|phx.onChange\|Form.of\|liveFileInput\|allowUpload\|sendUpdate\|LiveMountAspect\|rawEvent" example/src/*.scala e2eApp/src/*.scala`

Expected: output shows beginner examples in `example/src` and advanced/parity patterns in `e2eApp/src`.

- [ ] **Step 4: Replace the empty polish and docs sections**

In `doc/user-facing-api-assessment.md`, replace `## Polish And Discoverability Findings` and `## Documentation And Example Findings` with this content, adjusting line numbers to match Step 2 and Step 3 evidence:

```markdown
## Polish And Discoverability Findings

### High - Discoverability - There is no root README or newcomer path

Evidence: repository root has no `README.md`; `doc/api-improvement-ideas.md:75`.

The project has detailed reference material and examples, but no root-level introduction that explains installation, a first LiveView, router setup, JS socket setup, static assets, and how to run the example.

Impact: new users must reverse-engineer the intended setup from examples and implementation.

Confidence: High.

### Medium - Docs - Public API reference has stale or conflicting sections

Evidence: `doc/public-api-reference.md:77`, `doc/public-api-reference.md:610`, `scalive/src/scalive/LiveComponent.scala:5`.

The public API reference includes a current-looking `LiveComponent[Props, Msg, Model]` section and an older component section later in the document. Similar drift appears around upload callback effect types and documented typed navigation overloads.

Impact: users can copy API shapes that no longer match implementation.

Confidence: High.

### Medium - Docs - Phase context documentation omits or lags current context members

Evidence: `scalive/src/scalive/LiveContext.scala:13`, `doc/public-api-reference.md:127`.

`LifecycleContext` exposes `connectParams`, but the public reference's context availability snippet does not show it in the initial context summary.

Impact: users may miss available runtime data or distrust the reference as source of truth.

Confidence: High.

### Medium - Discoverability - Human examples and parity fixtures are not clearly separated

Evidence: `example/src/CounterLiveView.scala`, `example/src/TodoLiveView.scala`, `e2eApp/src/FormLiveViews.scala`, `e2eApp/src/IssueLiveViews.scala`.

`example/src` contains approachable examples, while `e2eApp/src` contains rich API coverage mixed with upstream parity fixture constraints. The repository does not clearly label which patterns are recommended app style and which exist to satisfy upstream fixture behavior.

Impact: users may learn from noisy parity fixtures and copy patterns that are appropriate for tests but not for applications.

Confidence: High.

### Low - Polish - Some examples demonstrate escape hatches before high-level APIs

Evidence: `example/src/TodoLiveView.scala:42`, `e2eApp/src/FormLiveViews.scala`.

Beginner examples use raw form maps and raw event handling in places where typed form helpers exist elsewhere in the codebase.

Impact: the typed API appears less complete than it is.

Confidence: Medium.

## Documentation And Example Findings

### High - Docs - Reference breadth is strong but needs freshness checks

Evidence: `doc/public-api-reference.md:1`, `doc/public-api-reference.md:13`, `doc/api-improvement-ideas.md:75`.

The public API reference covers a large portion of the intended API surface, including lifecycle, contexts, HTML, routing, forms, streams, uploads, hooks, assets, and tokens. Its main weakness is not breadth but drift from current implementation.

Impact: the project has enough raw material for good docs, but users need a trusted, current entry point.

Confidence: High.

### Medium - Docs - Compatibility guidance exists but is not a user-facing Phoenix migration guide

Evidence: `UPSTREAM_COMPATIBILITY.md:62`, `doc/api-improvement-ideas.md:87`.

Intentional divergences are listed for maintainers, but there is no guide that maps Phoenix concepts to Scalive concepts for users evaluating or migrating from Phoenix LiveView.

Impact: parity may be underestimated because Scala-first replacements are not explained from the user's perspective.

Confidence: Medium.

### Medium - Examples - Core examples demonstrate the mental model well

Evidence: `example/src/CounterLiveView.scala`, `example/src/Example.scala`, `example/src/RootLayout.scala`.

The examples show typed messages, typed models, `Task`, rendering, event bindings, routing, static assets, and root layout setup.

Impact: once discovered, the examples provide a useful starting point for the core API.

Confidence: High.
```

- [ ] **Step 5: Verify docs and examples sections have no roadmap sequencing**

Run: `grep -n "roadmap\|first,\|second,\|next,\|then implement\|priority order" doc/user-facing-api-assessment.md`

Expected: any matches are in the executive/methodology statements explaining that no roadmap is included, not in findings that rank implementation order.

---

### Task 4: Add Upstream Parity Findings

**Files:**
- Modify: `doc/user-facing-api-assessment.md`
- Read: `UPSTREAM_COMPATIBILITY.md`
- Read: `doc/e2e-fixture-parity-gaps.md`
- Read: `scripts/e2e-run-upstream.sh`
- Read: `test/playwright.upstream.config.js`
- Read: `e2eApp/src/E2EApp.scala`
- Read: `scalive/test/src/scalive/*.scala`

**Interfaces:**
- Consumes: report file with current ergonomics/docs findings.
- Produces: complete `Upstream Parity Findings` section that separates covered areas, partial areas, intentional divergences, and uncertainty.

- [ ] **Step 1: Read compatibility matrix status rows**

Run: `grep -n "| .* | .* | .* |" UPSTREAM_COMPATIBILITY.md`

Expected: output includes all matrix rows from Browser E2E behavior through Error shapes and crash/reconnect behavior.

- [ ] **Step 2: Read tracked E2E fixture gap status**

Run: `grep -n "Needs runtime support\|Gap\|Fixed\|Do not claim full Phoenix LiveView" doc/e2e-fixture-parity-gaps.md`

Expected: output includes the warning not to claim full parity and the remaining `Issue4088LiveView` gap.

- [ ] **Step 3: Verify upstream harness files and native test areas**

Run: `grep -n "playwright.upstream\|test/e2e/tests\|e2e-run-upstream\|LiveViewSpec\|FormApiSpec\|StreamApiSpec\|AsyncSpec\|LiveComponentParitySpec" scripts/e2e-run-upstream.sh test/playwright.upstream.config.js scalive/test/src/scalive/*.scala`

Expected: output shows the upstream browser test runner/config and representative native parity tests.

- [ ] **Step 4: Replace the empty upstream parity section**

In `doc/user-facing-api-assessment.md`, replace `## Upstream Parity Findings` with this content, adjusting line numbers to match Step 1 through Step 3 evidence:

```markdown
## Upstream Parity Findings

### High - Parity - Full upstream browser parity cannot be claimed while `Issue4088LiveView` remains tracked

Evidence: `doc/e2e-fixture-parity-gaps.md:7`, `doc/e2e-fixture-parity-gaps.md:66`.

The fixture gap document explicitly says not to claim full Phoenix LiveView `v1.1.28` upstream browser parity until every gap is closed or reclassified with evidence. The remaining tracked gap is `Issue4088LiveView`, involving a hook inside a locked LiveComponent container pushing repeated targeted events to `@myself`.

Impact: external compatibility claims need a caveat even if the upstream browser harness is broadly green.

Confidence: High.

### High - Parity - Compatibility evidence is strongest for browser E2E baseline and core native slices

Evidence: `UPSTREAM_COMPATIBILITY.md:24`, `scripts/e2e-run-upstream.sh`, `test/playwright.upstream.config.js`, `scalive/test/src/scalive/LiveComponentParitySpec.scala`.

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
```

- [ ] **Step 5: Verify parity section does not misclassify intentional divergence as a bug**

Run: `grep -n "Intentional divergence\|missing\|not implemented\|cannot be claimed\|partial\|expanding" doc/user-facing-api-assessment.md`

Expected: matches distinguish intentional API divergence from user-facing feature gaps and parity uncertainty.

---

### Task 5: Add Risk Register And Confidence Notes, Then Verify Acceptance Criteria

**Files:**
- Modify: `doc/user-facing-api-assessment.md`
- Read: `doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md`
- Read: `doc/user-facing-api-assessment.md`

**Interfaces:**
- Consumes: completed report sections from Tasks 1 through 4.
- Produces: final current-state assessment report that satisfies the accepted spec.

- [ ] **Step 1: Add risk register and confidence notes**

Replace empty `## Risk Register` and `## Open Questions And Confidence Notes` sections with this content:

```markdown
## Risk Register

### High - Compatibility claims can outrun evidence

Evidence: `doc/e2e-fixture-parity-gaps.md:7`, `UPSTREAM_COMPATIBILITY.md:81`.

The upstream browser harness is valuable, but fixture honesty and native coverage mapping still matter. The project should avoid public claims of complete Phoenix LiveView parity until every tracked gap is closed or reclassified with evidence.

Impact: over-claiming parity can create user trust issues when edge cases fail.

Confidence: High.

### High - Documentation drift can make the API feel less polished than it is

Evidence: `doc/public-api-reference.md`, `scalive/src/scalive/LiveComponent.scala:5`, `scalive/src/scalive/LiveContext.scala:13`.

When reference docs show stale signatures or omit current context members, users cannot reliably distinguish intended API from old design notes.

Impact: users waste time trying APIs that do not compile or miss APIs that already exist.

Confidence: High.

### Medium - Stringly typed concepts concentrate runtime risk in otherwise typed APIs

Evidence: `scalive/src/scalive/LiveContext.scala:98`, `scalive/src/scalive/LiveContext.scala:105`, `scalive/src/scalive/LiveContext.scala:134`, `scalive/src/scalive/Scalive.scala:83`.

The core model is strongly typed, but many boundary concepts remain plain strings.

Impact: the API can feel inconsistent: safe in lifecycle modeling, less safe in navigation and runtime identifiers.

Confidence: Medium.

### Medium - E2E fixtures are useful evidence but poor teaching material

Evidence: `e2eApp/src`, `doc/e2e-fixture-parity-gaps.md`.

The E2E app necessarily mirrors upstream test shapes and edge cases. Without separation from recommended examples, it can make normal Scalive code look more complex than necessary.

Impact: users may overestimate API complexity.

Confidence: High.

## Open Questions And Confidence Notes

- High confidence: core public API inventory, missing README, typed inbound versus string outbound navigation, stale component docs, remaining `Issue4088LiveView` parity caveat.
- Medium confidence: severity of repeated string identifiers; some strings may be acceptable escape hatches, and wrappers should be justified by concrete user errors.
- Medium confidence: exact completeness of JS command, form recovery, upload, and stream edge-case parity; the compatibility matrix identifies these areas, but this audit does not execute every upstream scenario.
- Open question: which intentional Phoenix divergences need user-facing migration documentation first is outside this report's scope because that becomes roadmap sequencing.
- Open question: whether Scalive should expose direct typed equivalents for every partial Phoenix feature is outside this report's scope because API quality can override direct shape parity.
```

- [ ] **Step 2: Verify no empty report sections remain**

Run: `grep -n "^## " doc/user-facing-api-assessment.md`

Expected: every `##` section listed has substantive content before the next `##` heading.

- [ ] **Step 3: Verify every finding has file references**

Run: `grep -n "^Evidence:" doc/user-facing-api-assessment.md`

Expected: every `Evidence:` line includes at least one repository path or an explicitly verified repository-root observation.

- [ ] **Step 4: Verify roadmap scope is excluded**

Run: `grep -n "roadmap\|implementation order\|sequencing\|first implement\|next implement\|should be built" doc/user-facing-api-assessment.md`

Expected: matches only state that roadmap or implementation sequencing is out of scope.

- [ ] **Step 5: Verify acceptance criteria against the spec**

Run: `grep -n "The assessment is complete when\|covers all major public API areas\|file references\|Documentation drift\|Intentional divergences\|Known parity uncertainty\|No roadmap" doc/superpowers/specs/2026-06-29-user-facing-api-assessment-design.md && grep -n "^## API Surface Inventory\|^## Ergonomics Findings\|^## Polish And Discoverability Findings\|^## Upstream Parity Findings\|^## Documentation And Example Findings\|^## Risk Register\|^## Open Questions" doc/user-facing-api-assessment.md`

Expected: first command output shows all acceptance criteria; second command output shows matching report sections.

- [ ] **Step 6: Review git status without committing**

Run: `git status --short`

Expected: output shows the new or modified documentation files only, unless unrelated pre-existing user changes are present.
