# Scalive Documentation Site Implementation Plan

**Goal:** Replace the standalone examples application with a comprehensive
Scalive-powered documentation site containing validated Markdown content,
generated API reference, isolated interactive examples, runtime X-ray
instrumentation, search, operational guidance, and a production-ready
application artifact.

**Architecture:** Three nested Mill modules under `documentation`: a shared
model, a build-time pipeline, and the Scalive site application.

**Core stack:** Scala 3.8.3, Mill, Scalive, ZIO, Laika 1.3.2, TASTy Query 1.8.0,
TASTy Inspector 3.8.3, ZIO JSON, Tailwind 4, minimal JavaScript hooks, Phoenix
LiveView client 1.1.28, and Playwright.

**Development commands:**

```bash
mill --ticker false documentation.check
mill --ticker false documentation.pipeline.generate
mill --ticker false documentation.site.bundle
```

Run the documentation site with `mill documentation.site.run`.

## Progress

- [x] Phase 0: Build graph and risk gates
- [x] Phase 1: Content model and Laika pipeline
- [x] Phase 2: Generated API reference
- [x] Phase 3: Documentation application shell
- [x] Phase 4: Search and metadata
- [x] Phase 5: Interactive example foundation
- [x] Phase 6: Runtime X-ray inspector
- [ ] Phase 7: Example migration
- [ ] Phase 8: Documentation content
- [ ] Phase 9: Visual and responsive design
- [ ] Phase 10: Operations and packaging
- [ ] Phase 11: Browser and performance verification
- [ ] Phase 12: Cutover and cleanup

## Global Constraints

- [x] Keep public content under `documentation/content`; never scan all of `docs`.
- [x] Keep internal specifications and plans under `docs`.
- [x] Use package-convention API inclusion for exact packages `scalive`,
  `scalive.codecs`, and `scalive.testing`.
- [x] Keep Laika and TASTy dependencies out of the deployed site classpath.
- [x] Render Laika AST through typed Scalive nodes; do not render generated HTML
  through `rawHtml`.
- [x] Reject raw HTML in Markdown.
- [x] Use nested LiveViews as the isolation boundary for embedded examples.
- [x] Add no arbitrary code-execution service.
- [ ] Preserve `e2eApp` as upstream parity infrastructure.
- [ ] Do not remove `examples` until its useful behavior and tests have migrated.
- [ ] Do not introduce publication-provider configuration or a deployment workflow.
- [x] Follow test-first development for runtime, parsing, validation, and example
  behavior changes.
- [x] Update this checklist as work is completed.

## Target Structure

```text
documentation/
├── content/
│   ├── learn/
│   ├── guides/
│   ├── examples/
│   ├── api/
│   └── project/
├── model/
│   ├── src/
│   └── test/src/
├── pipeline/
│   ├── src/
│   ├── test/src/
│   └── api/
├── site/
│   ├── src/
│   ├── test/src/
│   ├── assets/
│   ├── package.json
│   └── package-lock.json
└── fixtures/
```

Expected Mill targets:

```text
documentation.model
documentation.pipeline
documentation.site
documentation.check
documentation.checkExternalLinks
```

## Phase 0: Build Graph And Risk Gates

**Depends on:** Nothing.

- [x] Record the existing test, bundle, and upstream E2E baseline before changing
  modules.
- [x] Add nested `documentation.model`, `documentation.pipeline`, and
  `documentation.site` Mill modules.
- [x] Make `documentation.model` depend only on runtime-safe serialization
  libraries.
- [x] Make `documentation.pipeline` depend on the model, Laika IO 1.3.2, and TASTy
  Query 1.8.0.
- [x] Make `documentation.site` depend on Scalive and the model, but not Laika or
  TASTy Query.
- [x] Add nested ZIO Test modules for the model, pipeline, and site.
- [x] Generate one test resource in the pipeline and load it from the site
  classpath.
- [x] Verify generated resources and npm assets can coexist without duplicate
  paths.
- [x] Add an outer `documentation.check` task aggregating deterministic
  validation.
- [x] Preserve `e2eApp.bundle` behavior when extending shared asset tooling.
- [x] Document intended development commands in the plan header once confirmed.

**Baseline:** Recorded on 2026-08-04 at `48db44d`. All Scala tests,
`examples.bundle`, and `e2eApp.bundle` passed. The upstream E2E suite reported
171 passed tests and one test that passed on retry.

The TASTy Query compatibility probe confirmed that version 1.8.0 requires Scala
3.8.3 TASTy support, so the repository was upgraded from Scala 3.7.3 to 3.8.3.

**Verification:**

```bash
mill --ticker false documentation.model.test
mill --ticker false documentation.pipeline.test
mill --ticker false documentation.site.compile
mill --ticker false documentation.site.test
mill --ticker false documentation.site.bundle
mill --ticker false documentation.check
mill --ticker false e2eApp.bundle
```

**Completion gate:**

- [x] All three modules compile and the site reads a pipeline-generated classpath
  resource.

## Phase 1: Content Model And Laika Pipeline

**Depends on:** Phase 0.

- [x] Define JSON-serializable page, metadata, navigation, block, inline, code,
  directive, source-region, API-reference, and search-entry models.
- [x] Define required Laika HOCON metadata: title, description, order, and section.
- [x] Derive page routes from relative content paths.
- [x] Require explicit IDs for linkable section headings.
- [x] Configure Laika Markdown with GitHub-flavored tables and fenced code.
- [x] Keep Laika raw-content support disabled.
- [x] Convert supported Laika AST nodes into the shared typed content model.
- [x] Fail conversion for unsupported or raw HTML nodes.
- [x] Register fixed directives for examples, source regions, API symbols,
  compatibility entries, and callouts.
- [x] Reject unknown directives, attributes, and metadata properties.
- [x] Implement source markers using `// docs:start <name>` and
  `// docs:end <name>`.
- [x] Restrict source extraction to repository-relative whitelisted roots.
- [x] Reject escaped paths, symlinks outside the repository, missing markers,
  duplicate markers, reversed markers, and nested markers.
- [x] Preserve source line ranges for pinned repository links.
- [x] Use Laika's document tree to resolve internal links and fragment links.
- [x] Fail validation for duplicate routes, anchors, titles, or navigation
  positions.
- [x] Serialize output deterministically without timestamps or machine-specific
  paths.
- [x] Add parser fixtures covering valid pages and every validation failure.
- [x] Add a minimal homepage and Learn page as the first real content inputs.

**Verification:**

```bash
mill --ticker false documentation.pipeline.test
mill --ticker false documentation.pipeline.generate
mill --ticker false documentation.check
```

**Completion gate:**

- [x] A validated content tree containing Markdown, directives, links,
  highlighted code, and extracted source is loaded and rendered by a test
  consumer.

## Phase 2: Generated API Reference

**Depends on:** Phase 0. May proceed alongside Phase 1.

- [x] Prove TASTy Query and TASTy Inspector can inspect opaque types, extensions,
  overloads, exports, inherited members, source spans, and documentation comments.
- [x] Prove extraction works for generated DOM definitions.
- [x] Include exact packages `scalive`, `scalive.codecs`, and `scalive.testing`.
- [x] Include APIs exported through `import scalive.*`.
- [x] Exclude `private`, `private[scalive]`, synthetic, bridge, and
  implementation-only members.
- [x] Normalize stable symbol IDs and signatures.
- [x] Group overloads under one owner while retaining distinct signature IDs.
- [x] Generate a checked-in normalized public API snapshot.
- [x] Fail validation when visible symbols are added, removed, or change signature
  until the snapshot is explicitly updated.
- [x] Require new public symbols to have a generated or curated summary before
  validation passes.
- [x] Keep presentation grouping separate from API inclusion so grouping cannot
  hide public symbols.
- [x] Generate source links using an explicit documented repository revision.
- [x] Link generated DOM entries to the pinned `DomDefsGenerator.mill` revision
  and DOM Types version.
- [x] Do not check generated DOM Scala sources into Git.
- [x] Generate typed API content nodes rather than standalone Scaladoc HTML.
- [x] Add API pages and symbols to the unified search index.
- [x] Add focused tests for source links, visibility filtering, package exports,
  overloads, generated definitions, and drift failures.

**Verification:**

```bash
mill --ticker false scalive.compile
mill --ticker false scaliveTesting.compile
mill --ticker false documentation.pipeline.test
mill --ticker false documentation.pipeline.checkApi
```

**Completion gate:**

- [x] The generated reference covers the package-convention boundary and a
  deliberate API change fails validation.

TASTy Query supplies the normalized semantic inventory and signatures, while
TASTy Inspector loads compiled documentation comments. Source revisions come
from `SCALIVE_DOCS_REVISION`, defaulting to the current full Git revision. The
initial checked-in snapshot contains 1,265 logical public symbols.

## Phase 3: Documentation Application Shell

**Depends on:** Phase 1.

- [x] Create the `scalive.docs` application package.
- [x] Port the useful server, CSRF, static-asset, and LiveSocket bootstrap from
  `ExamplesApp`.
- [x] Load generated content once during application startup.
- [x] Construct exact Live routes from the generated page manifest.
- [x] Test dynamic literal `PathCodec[Unit]` construction for every content route.
- [x] Ensure unknown paths reach a real HTTP 404 rather than a successful catch-all
  page.
- [x] Implement one page LiveView capable of rendering typed documentation
  content.
- [x] Implement typed renderers for all supported block and inline nodes.
- [x] Implement root and documentation layouts.
- [x] Add primary navigation for Learn, Guides, Examples, API, and Project.
- [x] Add left section navigation and a right in-page outline.
- [x] Render internal navigation as real HTTP links enhanced by Scalive
  navigation.
- [x] Add source-edit and prefilled issue links.
- [x] Add a subtle connected, reconnecting, and offline indicator.
- [x] Freeze examples and explain unavailable interaction during disconnects.
- [x] Add light, dark, and system theme handling with a local override.
- [x] Add disconnected-render tests for every generated page route.
- [x] Assert titles, descriptions, navigation, headings, canonical links, and
  meaningful page text without JavaScript.

**Verification:**

```bash
mill --ticker false documentation.site.test
mill --ticker false documentation.site.bundle
mill documentation.site.run
```

**Completion gate:**

- [x] Generated Markdown pages are readable, navigable, and correctly routed
  before the live connection starts.

Canonical links initially remained route-relative. Phase 4 moved the validated
port and public-origin slice of the application configuration forward so
canonical and sitemap URLs are absolute; Phase 10 will expand the same model.

## Phase 4: Search And Metadata

**Depends on:** Phases 1 through 3.

- [x] Generate one deterministic search corpus covering pages, headings,
  examples, API symbols, and compatibility entries.
- [x] Preserve Scala-oriented tokens such as `LiveView`, `scalive.LiveView`, and
  `handleMessage`.
- [x] Implement one shared deterministic ranking algorithm in Scala and
  JavaScript.
- [x] Add a server-rendered `/search?q=...` route.
- [x] Add an instant-search JavaScript enhancement using the digested static
  index.
- [x] Keep search usable through ordinary form submission without JavaScript.
- [x] Add keyboard and focus handling for the search interface.
- [x] Generate canonical metadata for all public pages.
- [x] Generate a sitemap from the page manifest.
- [x] Add a self-contained `robots.txt`.
- [x] Add search tests for symbols, headings, prose, aliases, empty queries, and
  no-result states.
- [x] Verify every search result targets a valid page and anchor.

**Verification:**

```bash
mill --ticker false documentation.pipeline.test
mill --ticker false documentation.site.test
npm test --prefix documentation/site
mill --ticker false documentation.check
```

**Completion gate:**

- [x] Instant and server-rendered search return valid results from the same
  generated corpus.

The pipeline serializes the same sorted `SearchEntry` vector into the server
bundle and the digested browser index. Scala and JavaScript ranking use integer
scores, ASCII-stable tokenization, identifier aliases, and shared contract
fixtures. Example and compatibility labels and anchors are derived from their
validated stable IDs until later phases provide richer registry metadata.

## Phase 5: Interactive Example Foundation

**Depends on:** Phase 3.

- [x] Define a documentation-owned example registry keyed by stable example ID.
- [x] Keep LiveView factories, metadata, source regions, and trace projectors
  together in registry entries.
- [x] Convert `@:example` directives into registry references during generation.
- [x] Derive unique nested LiveView DOM and topic IDs from page and directive
  identity.
- [x] Render every interactive example as a non-sticky nested LiveView.
- [x] Define an explicit reset contract for each example.
- [x] Ensure page exit terminates the nested LiveView and its resources.
- [x] Add a counter example as the first complete vertical slice.
- [x] Extract the displayed counter source from its executable implementation.
- [x] Build a package-private site test harness over Scalive's internal socket
  runtime.
- [x] Support mount, join, binding dispatch, server messages, output collection,
  and rendered assertions in the harness.
- [x] Keep the harness inside site tests until its API proves suitable for
  `scalive-testing`.
- [x] Add expected-compilation-failure fixtures using `typeCheckErrors`.
- [x] Capture only focused compiler diagnostics in generated content.
- [x] Add registry validation ensuring every executable example has source and
  behavior tests.

**Verification:**

```bash
mill --ticker false documentation.site.test
mill --ticker false documentation.pipeline.test
mill --ticker false documentation.check
```

**Completion gate:**

- [x] The counter renders inline, resets correctly, displays extracted source,
  and passes connected behavior tests.

Pure example descriptors live in `documentation.model`, allowing the pipeline to
validate IDs, extract source, and build search entries without depending on the
site module. The site registry combines those descriptors with fresh non-sticky
LiveView factories, reset contracts, and explicit trace projectors. A
package-private rendered-HTML socket snapshot supports the site-local connected
test harness without expanding Scalive's public testing API.

## Phase 6: Runtime X-Ray Inspector

**Depends on:** Phase 5. Complete before bulk example migration.

- [x] Define a package-private, disabled-by-default runtime trace sink in Scalive.
- [x] Expose only a package-private router/runtime configuration path for
  installing the sink.
- [x] Put the disabled check before trace-object construction, model projection,
  serialization, clock access, or collection.
- [x] Verify by code review that disabled tracing performs only a predictable
  no-op branch.
- [x] Add tests proving disabled tracing never invokes a projector.
- [x] Add tests proving disabled tracing emits byte-for-byte identical HTML and
  protocol frames.
- [x] Instrument socket join, decoded events, binding resolution, typed messages,
  lifecycle callbacks, model proposals, render completion, tree diffs, model
  commits, final frames, and crashes.
- [x] Distinguish proposed, rendered, and committed models.
- [x] Add explicit per-example message and model projectors.
- [x] Default all unprojected values to type names and redacted content.
- [x] Never invoke generic `toString` on models or messages.
- [x] Add a test model whose `toString` throws.
- [x] Introduce trace session, connection epoch, socket epoch, topic, join
  reference, message reference, and operation sequence identifiers.
- [x] Publish server records to a bounded documentation-owned in-memory channel.
- [x] Run the inspector on a separate nested LiveView topic.
- [x] Exclude inspector traffic from observed traces.
- [x] Wrap the pinned Phoenix serializer to observe outbound and inbound frames
  without modifying them.
- [x] Use Phoenix patch callbacks and a scoped `MutationObserver` to observe final
  DOM changes.
- [x] Keep all Phoenix-internal integration inside one versioned JavaScript
  adapter.
- [x] Send browser trace records only to the inspector topic.
- [x] Preserve independent browser and server ordering instead of pretending
  clocks are synchronized.
- [x] Sanitize tokens, CSRF values, cookies, passwords, form secrets, claims, and
  upload bytes.
- [x] Bound trace history by record count and byte size.
- [x] Implement readable model, lifecycle, wire-diff, and DOM-diff views.
- [x] Allow expansion to sanitized protocol structure.
- [x] Add the counter X-ray browser journey.
- [x] Test empty diffs, failed renders, reconnects, async completion, components,
  streams, and title-only updates.

**Verification:**

```bash
mill --ticker false scalive.test
mill --ticker false documentation.site.test
npm run test:xray --prefix documentation/site
```

**Completion gate:**

- [x] A counter click is correlated from browser event through committed model
  and final DOM application without exposing secrets or changing observed
  protocol behavior.

## Phase 7: Example Migration

**Depends on:** Phases 5 and 6.

Each migrated example must include extracted source, a behavior test, reset
behavior, isolated state, catalog metadata, related-guide links, and X-ray
support where it improves the lesson.

- [x] Migrate the shopping cart example.
- [x] Replace the guestbook with a deterministic read-only ZLayer service example.
- [x] Migrate the subscription clock with instance-scoped keys.
- [x] Migrate the async report with deterministic success, failure, replacement,
  retry, and cancellation controls.
- [x] Migrate the typed profile form.
- [x] Migrate the activity stream.
- [x] Migrate voting components with instance-scoped component IDs.
- [x] Migrate browser interop with instance-scoped hook and DOM IDs.
- [x] Migrate lifecycle, flash, title, and connection behavior.
- [x] Adapt navigation examples to real documentation routes and search
  parameters.
- [x] Replace persistent document storage with a bounded real upload that
  summarizes text and immediately discards consumed content.
- [x] Verify upload bytes never enter trace history.
- [ ] Add a dedicated authentication lab using standalone HTTP and protected Live
  routes.
- [ ] Add fixed credentials, bounded sessions, rate limits, expiry, logout, and
  explicit reset behavior to the authentication lab.
- [ ] Keep authentication session records opaque and isolated between visitors.
- [ ] Add deterministic latency and failure controls only where they teach
  relevant behavior.
- [x] Build the topic-filtered example catalog from registry metadata.
- [ ] Preserve applicable existing auth, form, and routing tests during
  migration.

**Verification:**

```bash
mill --ticker false documentation.site.test
mill --ticker false documentation.check
```

**Completion gate:**

- [ ] Every catalog example is isolated, executable, source-backed,
  behavior-tested, and safe for public use.

## Phase 8: Documentation Content

**Depends on:** Phases 2, 4, and 7.

### Learn

- [x] Write the Mill-based ten-minute counter quick start.
- [x] Include the complete minimal project and current npm asset setup.
- [x] Write project anatomy and application startup.
- [x] Write models, typed messages, and state transitions.
- [x] Write rendering and DOM updates.
- [x] Write lifecycle and connection behavior.

### Guides

- [x] Write HTML DSL, attributes, bindings, and keyed rendering guides.
- [x] Write typed forms and validation guides.
- [x] Write components and parent/component communication guides.
- [x] Write JS commands, browser events, and hook integration guides.
- [x] Write flash, title, and lifecycle UX guides.
- [x] Write services and ZLayer injection guides.
- [x] Write streams and collection update guides.
- [x] Write uploads and consumption guides.
- [x] Write routes, typed parameters, patching, and navigation guides.
- [ ] Write layouts, sessions, mount aspects, and authentication guides.
- [x] Write async work, subscriptions, cancellation, and failure guides.
- [x] Write static assets and client setup guides.
- [x] Write disconnected, connected, and browser testing guides.
- [x] Write troubleshooting guides for startup, assets, sockets, CSRF, and
  reconnects.
- [x] Write the Phoenix LiveView orientation and migration guide.
- [x] Add brief Phoenix comparison callouts only where useful.

### API And Project

- [ ] Split the current public API reference into curated API landing and concept
  pages.
- [x] Link curated pages to generated owners and members.
- [x] Migrate project status and alpha expectations.
- [ ] Migrate the compatibility matrix.
- [ ] Pin the compatibility matrix to the current upstream LiveView revision.
- [ ] Add links from compatibility entries to Scalive or upstream test evidence.
- [ ] Add coverage validation mapping every generated API group to curated
  guidance or an intentional reference-only classification.
- [x] Add source-edit and issue-report links to every authored page.
- [x] Run the full internal-link and API-drift checks.

**Verification:**

```bash
mill --ticker false documentation.check
mill --ticker false documentation.checkExternalLinks
```

**Completion gate:**

- [ ] Every supported public Scalive feature has generated reference coverage and
  appropriate learning or guide coverage.

## Phase 9: Visual And Responsive Design

**Depends on:** Phases 3 and 6. May overlap with content work.

**Selected direction:** Signal / Editorial with a lowercase visual wordmark, as
defined by the production design contract in
[`site-specification.md`](site-specification.md#visual-and-content-design).

- [x] Confirm the documentation package remains DaisyUI-free; DaisyUI was already
  absent before this implementation.
- [x] Keep Tailwind utilities and define Scalive-specific CSS variables and
  tokens.
- [x] Implement the selected Signal / Editorial visual language for prose, code,
  callouts, traces, and diagrams.
- [x] Bundle licensed Instrument Sans Variable and JetBrains Mono Variable fonts
  locally, with usable system fallbacks.
- [x] Implement system-default light and dark themes.
- [x] Prevent theme flash before application startup.
- [x] Implement desktop left navigation, central content, and right outline.
- [x] Implement native mobile navigation disclosure and stacked examples.
- [x] Ensure code, tables, examples, and traces do not cause document-level
  overflow.
- [x] Add copy controls and complete-source expansion to code blocks.
- [x] Add visible keyboard focus and skip navigation.
- [x] Respect reduced-motion preferences.
- [x] Ensure connection and trace updates remain understandable without relying
  only on color.
- [ ] Perform manual keyboard, contrast, semantic-heading, and screen-reader
  announcement review.
- [x] Verify all required fonts, icons, scripts, and media are served locally.

**Completion gate:**

- [ ] Representative Learn, Guide, Example, API, Search, and Project pages pass
  maintainer visual and accessibility review on desktop and mobile.

## Phase 10: Operations And Packaging

**Depends on:** Phases 3 and 7.

- [ ] Define one validated application configuration model.
- [ ] Configure bind address, port, public origin, secure cookies, token secret,
  log level, and shutdown timeout.
- [ ] Fail production startup for missing or invalid security-sensitive
  configuration.
- [ ] Add health and readiness endpoints.
- [ ] Add structured startup and shutdown logs without secrets.
- [ ] Redact cookies, tokens, CSRF values, and WebSocket query strings.
- [ ] Add appropriate security response headers.
- [ ] Verify static asset digests, cache headers, MIME types, `GET`, `HEAD`, and
  traversal rejection.
- [ ] Produce one self-contained runnable JVM artifact.
- [ ] Verify Node and npm are build-time-only dependencies.
- [ ] Smoke-test the artifact with only a JRE and explicit environment variables.
- [ ] Exercise shutdown with active sockets, subscriptions, async tasks, and
  uploads.
- [ ] Confirm example resources and temporary upload content are released.
- [ ] Write the provider-neutral single-instance deployment guide.
- [ ] Document TLS and WebSocket reverse-proxy requirements.
- [ ] Write the explicit configuration reference.
- [ ] Document static caching, logging, health, readiness, and graceful shutdown.
- [ ] Document multi-instance concerns without providing a complete clustered
  recipe.
- [ ] Verify the application performs no analytics requests and loads no
  third-party runtime resources.

**Verification:**

```bash
mill --ticker false documentation.site.assembly
java -jar <documentation-artifact>
```

**Completion gate:**

- [ ] The site runs from a standalone JVM artifact and its deployment
  requirements are completely documented.

## Phase 11: Browser And Performance Verification

**Depends on:** Phases 4 through 10.

- [ ] Add a documentation-specific Playwright configuration.
- [ ] Keep it separate from `playwright.upstream.config.js`.
- [ ] Run critical journeys in Chromium, Firefox, and WebKit.
- [ ] Add desktop and mobile viewport projects.
- [ ] Test homepage, quick start, primary navigation, in-page navigation, and
  ordinary-link fallback.
- [ ] Test instant and server-rendered search.
- [ ] Test theme selection, system defaults, persistence, and reduced motion.
- [ ] Test example interaction, reset, latency, errors, cancellation, and cleanup.
- [ ] Test X-ray correlation, sanitized raw payloads, bounded history, and disabled
  mode.
- [ ] Test disconnect, reconnect, frozen state, and remount resets.
- [ ] Test upload limits, summarization, disposal, and cross-visitor isolation.
- [ ] Test authentication login, protected route, logout, expiry, rate limits, and
  isolation.
- [ ] Test representative pages with JavaScript disabled.
- [ ] Keep screenshots and traces only as failure diagnostics.
- [ ] Add a repeatable Lighthouse check against a production-built homepage,
  guide, example, and API page.
- [ ] Review Core Web Vitals results before considering the site complete.
- [ ] Run manual accessibility review; do not claim formal conformance.
- [ ] Run the upstream LiveView E2E suite after runtime tracing changes.

**Verification:**

```bash
npm run test:e2e --prefix documentation/site
npm run lighthouse --prefix documentation/site
./scripts/e2e-run-upstream.sh
```

**Completion gate:**

- [ ] Critical documentation journeys pass in all three browser engines and
  representative pages pass the agreed Lighthouse review.

## Phase 12: Cutover And Cleanup

**Depends on:** All previous phases.

- [ ] Confirm every useful current example has migrated, been replaced, or been
  intentionally retired.
- [ ] Move applicable example tests into the documentation site.
- [ ] Remove the standalone `examples` Mill module.
- [ ] Remove the old examples shell, routes, layouts, catalog, and README.
- [ ] Remove the process-shared guestbook and upload store.
- [ ] Update npm lockfile identity and repository ignore rules.
- [ ] Move the public API reference into canonical site content.
- [ ] Move the compatibility matrix into canonical site content.
- [ ] Leave short repository pointers at old public-document locations if they
  remain useful to GitHub readers.
- [ ] Update the root README to use the documentation-site commands and content
  locations.
- [ ] Update stale links in API improvement notes without rewriting historical
  plans.
- [ ] Keep `e2eApp` explicitly described as parity evidence rather than teaching
  material.
- [ ] Run formatter and Scalafix across changed modules.
- [ ] Run every Scala test.
- [ ] Run documentation generation and validation from a clean build.
- [ ] Build both documentation and E2E assets.
- [ ] Run documentation Playwright tests.
- [ ] Run upstream parity tests.
- [ ] Run Lighthouse verification.
- [ ] Run `git diff --check`.
- [ ] Inspect the final worktree and confirm only intended changes remain.
- [ ] Complete a final maintainer review against `docs/site-specification.md`.
- [ ] Mark all phase and completion-gate checkboxes complete.

**Final verification:**

```bash
mill --ticker false __.reformat + __.fix
mill --ticker false __.test
mill --ticker false documentation.check
mill --ticker false documentation.site.bundle
mill --ticker false e2eApp.bundle
npm run test:e2e --prefix documentation/site
npm run lighthouse --prefix documentation/site
./scripts/e2e-run-upstream.sh
git diff --check
git status --short
```

## Deferred Work

- [ ] Publication provider and deployment workflow
- [ ] Versioned documentation
- [ ] Localization
- [ ] Analytics
- [ ] Offline or PWA support
- [ ] General public Scalive devtools API
- [ ] Promotion of the site-local connected test harness into `scalive-testing`
- [ ] Horizontally scaled deployment recipe
