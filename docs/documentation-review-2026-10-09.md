# Documentation site review

Reviewed: 2026-10-09
Revision: `341a561` (documentation content is unchanged since `933c2a7`)

This review replaces the 2026-10-01 content review. Every finding from that
review that still applies has been rechecked and is included below.

## Summary

The site is in good shape in several ways. The quick start works. Examples are
embedded from compiled source. Every guide uses the same "Before you start" /
"Related tasks" frame. The docs are also unusually honest about limits. The
problems fall into three areas:

1. **Hand-written recipes no longer match the API.** Only the 32
   `@:sourceRegion` blocks are compiled. The 176 inline ```` ```scala ```` blocks
   are not. I compiled 18 representative inline snippets against the current
   classpath, and **10 fail**. They include the whole service-injection recipe,
   the first test in the testing guide, and the main rendering snippets for
   streams, uploads, typed forms, and HTTP forms.
2. **Everyday tasks are missing while edge cases are covered in depth.**
   Conditional rendering (`when` / `choose` / `option`) is never shown outside a
   Project essay. No page shows how to start work when a page connects.
   Route-level service injection (`.from` / `.context`) has no prose. Most event
   bindings never appear in prose. Meanwhile, the forms, layouts, hooks, async,
   authentication, and testing pages open with advanced contracts.
3. **Vocabulary and presentation are inconsistent.** Examples: "durable" for
   connection-local data, "reset" for logout, "socket" for a LiveView
   lifecycle, and `routes` / `liveRoutes` naming different types on different
   pages. Page titles use sentence case but headings use Title Case. Duplicate
   `order` values scramble the example sidebar.

Fixing (1) mechanically stops it from coming back. Fixing (2) is the biggest
gain for approachability.

## Decisions for implementation

The maintainer made these decisions on 2026-10-09:

1. **Fix the API first.** When a finding shows that the API is awkward or
   missing something, change the API and then document it. Change only the docs
   when the existing API is already the better shape. The implementing agent may
   reverse a decision during its session if the change turns out not to be a
   clear improvement or its impact is too large. Record the reason in this file.
2. **URLs may change freely.** No redirects are needed. Update the links in
   `README.md` (it links to `/project`, `/project/compatibility`,
   `/examples/counter`, `/learn/quick-start`, `/learn`, `/examples`, and
   `/api`).
3. **Build changes are allowed** for the recipe compile check (finding 7).
4. **Use only one way to declare a layout** (finding 16).

Candidates for decision 1. These are starting points, not settled designs:

| Finding | Problem | Options |
| --- | --- | --- |
| 1 | No clear way to give a route a service. The guide's `-> layer` doesn't exist. `.from((params, request, service) => …)` forces two ignored parameters. `.context((s: S) => …)` works, but its name suggests mount-aspect context. | Pick one way and make it read well: accept a layer in `->`, add a dedicated builder, or keep `.context` and document it. |
| 2 | Form bindings exist on `Definition` and on a plain `Form`, but not on `Signal[Form]`, and the docs mix all three. No Scala code in the repo calls the plain-`Form` versions. All 13 call sites use `Definition`. | Keep one way to attach form bindings, probably `Definition`, and remove or align the others. `Form.http` is separate and stays. |
| 3 | Importing both `zio.test.*` and `scalive.*` makes `Live` and `live` ambiguous. | Renaming would touch about 344 call sites. A fix on the testing side, such as a base spec or exports from `scalive.testing`, may be cheaper. Otherwise document `import zio.test.{Live as _, live as _, *}`, which compiles. |
| 4 | `LiveHooks.afterRender { … }` doesn't exist. Readers have to write `LiveHooks.empty[Msg, Model].afterRender`. | Add shortcuts on the companion object for each stage, with types inferred from `override def hooks: LiveHooks[Msg, Model]`. Do the same for `ComponentLiveHooks`. |
| 4 | A single-field form needs `Root.product[A](Tuple1(field))`. | Add a single-field overload. |
| 16 | There are three ways to declare a root layout and two for ordinary layouts. | See finding 16. |

## Implementation progress

### Phase 1 (2026-10-09): recipe compile check and findings 1–6

API decisions taken:

- **Finding 1:** added a one-argument `from(service => view)` to
  `LiveRouteBuilder` and `LiveRouteParamsBuilder`:
  `(live / "reports").from((reports: Reports) => ReportsLiveView(reports))`.
  A layer in `->` was rejected because Scalive would have to build a layer for
  every mount, which is a runtime change, and `.context` keeps its meaning of
  mount-aspect context. `from` takes one service type; the services guide says
  to group several services in one. The runtime could already support two
  (`LiveRouteContext.WithEnvironment`) if that becomes necessary.
- **Finding 2:** form bindings now live only on `FormDefinition`. Removed
  `Form.onChange`, both `Form.onSubmit` overloads, `Form.onRecover`,
  `Form.disableRecovery`, `Form.triggerHttpSubmitWhen`, and the instance
  `Form#http`. `Form.http` on the companion stays. The replacements are
  `phx.autoRecover := "ignore"` and `phx.triggerAction := model.map(...)`;
  the old `triggerHttpSubmitWhen` took a `Boolean`, so it could not follow the
  model inside `view` anyway. The plain-`Form` field APIs (`field`, `rows`,
  `errorFeedback`) stay, because constant forms and HTTP rendering need them.
- **Finding 3: decision reversed, docs only.** `Live` and `live` keep their
  names. Renaming the routing entry points (more than 400 call sites,
  including every `live / "..."`) to avoid a test-library clash costs more than
  it gains. A base spec in `scalive.testing` would add a `zio-test` dependency to
  `scalive-testing` and a class whose only purpose is to shadow two names. The
  first test in the testing guide (`ConnectedRender.join`) does not reference
  `Live`. Tests that build a router use `import zio.test.{Live as _, *}`, and the
  guide explains why.
- **Finding 4:** added one shortcut per stage on the `LiveHooks` and
  `ComponentLiveHooks` companions. Their types are inferred only when the
  shortcut is the whole `hooks` expression; a chain of stages still starts from
  `LiveHooks.empty[Msg, Model]`. Added single-field `product(field)` overloads to
  `FormRoot` and `RepeatedGroup`, and migrated the repository's `Tuple1` call
  sites.

Compile check (finding 7):

- `documentation.check` now runs `quickStartFixture.test` and
  `guideFixtures.test`. Guide recipes live in
  `documentation/fixtures/guides/src` and `documentation/fixtures/guides/test/src`;
  the quick-start tests live in `documentation/fixtures/quick-start/test/src`.
  `guideFixtures` depends on `quickStartFixture`, so recipes can extend the
  quick-start application.
- `@:sourceRegion` regions may now nest (inner markers are removed from the
  outer region) and are dedented. A guide can show one method of a compiled
  LiveView and then the whole file.
- The content pipeline rejects a bare ```` ```scala ```` block. Inline Scala must
  be either ```` ```scala fragment ````, rendered with a **Fragment** caption, or
  the temporary ```` ```scala unverified ````, rendered as before. All 176
  existing blocks were marked `unverified`, and phase 1 converted or relabeled
  70 of them. Run `rg '```scala unverified' documentation/content` for the
  remaining 106. When none remain, delete the `unverified` case in
  `ContentPipeline.scalaFenceLanguage`.

Pages fixed: the services guide (rewritten around `from`, with a full `Main` and
route tests), the service-injection example page, UI label, and catalog entry
("service-backed"), streams, uploads (including finding 5), HTTP forms, typed
forms (main path, repeated rows, and a save workflow that uses
`ctx.async.start`), the HTML guide's attribute sentence, the testing guide (build
setup, first test, disconnected test, form submission, snapshots, typed-form
test, and routed client), the after-render hooks in the flash and lifecycle-hook
guides, navigation guards, and the homepage, which now shows the quick-start
counter. The quick-start counter dropped `typ := "button"` to stay compact.

Verification: `mill __.test`, `mill documentation.check`, and
`npm run test:xray` pass. The testing guide's `build.mill` was checked by hand
as an external project against the published `0.0.1-341a561ee032-SNAPSHOT`
with Mill 1.1.8, and `mill app.test` passed. Mill builds are not compiled by
`documentation.check`, so both `build.mill` blocks remain `unverified`.
`./scripts/e2e-run-upstream.sh` was not run, because no runtime behavior
changed.

Left for later phases:

- On the mount-aspect builders, `from` still means
  `(path, request, context) => view` and duplicates `apply` and `context`.
  Consider removing it in a later API pass.
- `examples/text-upload.md` still describes the wrong upload order
  (finding 17).

### Phase 2 (2026-10-10): Learn path and findings 8–11

The Learn path now builds one application from the quick-start counter, in the
order the recommended information architecture proposes. Every Scala block in
Learn is compiled except the two `build.mill` files. The application lives in
`documentation/fixtures/learn` (Mill module `documentation.learnFixture`), in the
reader's `quickstart` package. It does not depend on `quickStartFixture`, whose
classes it replaces, so it carries its own copy of `RootLayout`.
`documentation.check` runs its tests.

| Order | Page | Change |
| --- | --- | --- |
| 1 | Quick start | Mill pinned with `//| mill-version: 1.1.8`, JDK 17 or later named, production settings in one warning callout, artifact and WebSocket `Origin` asides removed, next step added. The root layout key is still unexplained; it waits for finding 16. |
| 2 | Models, messages, and effects | The counter gains a `Model` case class and `Reset`. The cart model and handler are compiled. The forms detour now links to the form lesson. |
| 3 | Rendering, bindings, and diffs | Finding 8 and 9: states that `model` is a `Signal` inside `view`; recipes for `when`, `option`, and `choose`; keyed rows; row-specific messages with `on.click(line.map(...))`; helpers that take signals. The tuple-flattening rules moved to Scaladoc on `Signal.combine`, `combineWith`, and `combineWithFn`. |
| 4 | Your first form (new) | Two fields, change, submit, errors, and reset after success. |
| 5 | Loading data and live updates (new) | Findings 10 and 11: a service registered with `from`, async work and a subscription started from the connected mount branch, and `AsyncValue` rendering. |
| 6 | URL state and navigation | The search example is compiled. |
| 7 | Lifecycle, state ownership, and reconnects | Uses the thermostat's mount; the `withAdmission` and connected-harness references are gone. |
| 8 | Testing your LiveView (new) | The test module setup and first test moved here from the testing guide, plus tests for the form and the service-backed page. |
| 9 | How the pieces fit | Renamed from Project anatomy (`/learn/project-anatomy` is now `/learn/how-the-pieces-fit`) and moved to the end. The asset-model paragraph is shorter. |
| 10 | Where to go next | Example names fixed; navigation guards and component subscriptions added. |

The thermostat page polls a stand-in sensor: it reads once the page connects,
then a subscription sends `Refresh` every ten seconds, and starting the same
`AsyncKey` replaces any read still running. This shows both APIs without a race.
Loading a snapshot and then subscribing to a feed of changes to the same data
can miss or reorder updates unless the service's stream starts with the current
value, which is too much for a first lesson. The page mentions push streams in
a short fragment and links to the async guide.

Other pages changed for these findings:

- **Async guide (finding 10):** now leads with "Start work when the page
  connects", which embeds the thermostat's mount. Connected resources and
  route/session initializers moved to the end. Its nine inline blocks became
  regions of the compiled examples.
- **Routes guide (finding 11):** new "Give a LiveView its services" section.
  The services guide says when to load in `mount` and when to load on connect.
- **Testing guide:** starts from the Learn lesson's test module.
- **HTML guide, typed forms guide, Phoenix orientation, dynamic HTML essay:**
  link to the new lessons; the essay uses `on.click(product.map(...))`.

API decision:

- **Added `Connection.isConnected`.** `LifecycleExample` and the e2e app wrote
  `ctx.connection.isInstanceOf[Connection.Connected[?]]` to read the phase. The
  greenfield API removed the old `ctx.connected: Boolean` because connected
  capabilities used to be no-ops during the disconnected render. The new
  method returns only the phase; capabilities are still reachable only by
  matching `Connection.Connected`, and a test checks that.

`rg '```scala unverified' documentation/content` now reports 86 blocks.

Verification: `mill __.test`, `mill documentation.check`, and
`npm run test:xray` pass. `./scripts/e2e-run-upstream.sh` was not run, because
no runtime behavior changed.

### Phase 3 (2026-10-10): finding 15, page splits, and finding 16

API decision (finding 16): layouts are declared only by implementing the
`LiveLayout` and `LiveRootLayout` traits.

- Removed `LiveLayout.apply`, `LiveRootLayout.apply`, and
  `LiveRootLayout.dynamic`. Their polymorphic function literals and explicit
  `[A, Ctx]` arguments were the hardest syntax in the API.
- Renamed `LiveRootLayout.render` to `view`, so both traits have one
  `view[Msg]` method.
- `LiveRootLayout.key` now defaults to the class name. Routes that share a root
  layout class share the document, which matches Phoenix comparing root layout
  modules. Override `key` when one class renders incompatible documents, as the
  per-language example does. The quick start no longer declares a key, and
  explains the `[Any, Any]` and `[Msg]` type parameters instead.
- Added Scaladoc to both traits and to `key`.
- About 40 call sites changed, mostly tests. `rootSliceApp` now uses one
  root-layout class with a key per body ID.

Pages split (recommended information architecture):

| Old page | New pages |
| --- | --- |
| Typed forms and validation | Typed forms and validation (define, model, change, submit, render, reset, then variants); Form controls; Form feedback and recovery; Repeated form rows; Saving forms |
| Layouts, live sessions, and mount aspects | Layouts; Live sessions and mount aspects |
| Asynchronous work, subscriptions, and connected resources | Asynchronous work; Subscriptions; Connected resources |
| Testing LiveViews | Testing LiveViews (connected first, then forms, routed apps, navigation, disconnected, browser); Connected test reference |

New URLs: `/guides/form-controls`, `/guides/form-feedback-and-recovery`,
`/guides/repeated-form-rows`, `/guides/saving-forms`, `/guides/layouts`,
`/guides/live-sessions-and-mount-aspects` (was
`/guides/layouts-sessions-and-mount-aspects`), `/guides/async-work` (was
`/guides/async-work-and-subscriptions`), `/guides/subscriptions`,
`/guides/connected-resources`, and `/guides/connected-test-reference`. The
README links none of them.

Restructured in place (finding 15): lifecycle hooks (decision table, then
static hooks; guards, raw events, and after-render under "Advanced topics"),
authentication (log in → session → protect pages → revalidate → log out →
shared service, then admission, fanout, and checklist; "log out" replaces
"reset" outside code names and the lab's UI label), HTML and event bindings,
and client setup (packaged clients → `LiveSocket` → application assets →
custom bundle → connect metadata → asset models and manifests last).

The HTML guide also covers finding 12: a binding table with one row per family
and a compiled search page that uses a debounced `keyUp`, a filtered window key
binding, `clickAway`, and `viewportBottom`. The blur-debounce example is gone.
Viewport bindings, `connection.onConnect` / `onDisconnect`, `throttle`, and
`debounceOnBlur` are described from the Phoenix LiveView 1.2.10 client source;
Scalive has no end-to-end test for them yet.

New compiled fixtures in `documentation/fixtures/guides/src`: `layouts`,
`sessions`, `hooks`, `auth`, `assets`, and `bindings`, with tests for layouts
and sessions (including a followed live navigation, used by the testing guide).
New regions in the site examples cover the typed submitter, the
repeated-contacts row operations, and the per-language root layout. Every page
touched in this phase is free of `unverified` blocks except the Mill `bundle`
task in client setup. `rg '```scala unverified' documentation/content` now
reports 35 blocks.

Left for later phases:

- Mount-aspect `from` still duplicates `apply` and `context`.
- Finding 17 (example catalog), including `examples/text-upload.md`.
- Sentence-case headings (finding 19): the new pages keep Title Case to match
  the rest of the site.

Verification: `mill __.test`, `mill documentation.check`, and
`npm run test:xray` pass. `./scripts/e2e-run-upstream.sh` was not run: the
runtime change is the `render` to `view` rename, and the e2e app's routes all
share one root layout, so the new default key changes no navigation.

### Phase 4 (2026-10-10): findings 13 and 17

API decision: each route builder now has one way to say each thing. Removed
`from((path, request, context) => view)` from `LiveRouteMountAspectBuilder` and
`LiveRouteMountAspectParamsBuilder`, `apply((path, request, context) => view)`
from both, and the `apply((path, request, context) => view)` overload of
`LiveRouteBuilder`. Each duplicated a `context` overload. Builders now offer
`->` (or `apply`) for a LiveView that needs nothing, `from` for one built from a
service, and `context` for one built from session or mount-aspect context. Eight
test call sites, in `ZioHttpSpec` and `RoutedConstructionSpec`, moved to
`context`.

Scaladoc (finding 13):

- **Pipeline bug.** TASTy records some definitions under several symbols at one
  position, such as an enum and its companion. The extractor then found no
  unique record and dropped the comment, so `Connection`, `AsyncValue`,
  `LiveAsyncResult`, `HtmlAttrBinding`, and other documented types showed the
  fallback summary. Records that agree on comment and export now count as one.
- **Overloads.** A symbol's summary is the first paragraph its overloads share;
  when they differ, the reference falls back. Overloads of the documented
  members now share a first paragraph and put the differences in the second.
- **Guide links.** Scaladoc links to `https://scalive.dev/...` keep working in
  an IDE. The pipeline turns them into internal links, and the site checks
  their routes and anchors like page links.
- **Documented:** `LiveView` (all members, `Routed`, `Eventless`), the mount,
  message, and params contexts, `Connection`, `RootMountConnected`, `Async`,
  `Subscriptions`, `Navigation`, `MountNavigation`, `Flash`, `Client`,
  `ComponentUpdates`, `AsyncValue`, `LiveAsyncResult`, `LiveComponent`, the
  route, session, and router builders, `Live`, `live`, `LiveApplication`,
  `ZioHttp.routes`, `Signal`, `splitBy`, `renderIn`, `on`, `connection`,
  `HtmlAttrBinding`, `link`, `flash`, `liveTitle`, `component`, `rawHtml`,
  `FormDefinition`'s bindings, `FormRoot.product`, and `ConnectedRender.join`.
  Core types link to a guide.
- Fallback summaries fell from 1,328 of 2,203 symbols to 998 of 2,201.
  Most that remain are in `LifecycleObserver` events, hook traits, `Mod`
  cases, raw `phx` attributes, and advanced form types.
- **API overview.** `api/index.md` names the real UI controls (**Filter
  packages and types**, **Find a member**), links each package, mentions the
  connected test client, maps tasks to the core types, and moves lifecycle
  resources and observability out of Forms. `#core-abstraction` became
  `#core-types`.

Example catalog (finding 17):

- Every example has a unique `order`, grouped like the catalog and from simple
  to advanced within each group. The catalog lists cards in that order, and
  **User input** now precedes **Async and effects**, matching the Learn path.
- Example pages render the live result first and the complete source after it;
  long sources collapse to a preview.
- Every page follows one format: what it shows, **Try It** (steps with the
  expected result), the live result and source, **How It Works** (the key
  lines, in prose), and **Related Guides**. The key lines follow the source
  because the example directive renders both together.
- Text upload: the page describes the manual upload, the button reads **Upload
  and summarize** (was "Summarize completed file") and **Uploading...** while
  in flight, and the empty-submit notice asks for a valid file.
- Component subscriptions: the steps only ask for a reproducible removal; the
  quick-reinsertion case is explained instead.
- Navigation: the page says that its links leave the page and how Back
  behaves.
- Prose lists had no bullets, numbers, or indentation on any page, because the
  CSS reset removes list styles. Rendered Markdown lists now carry a
  `docs-list` class with markers; the homepage keeps its own list styling.

Left for later phases:

- Findings 18–20, including sentence-case headings, "durable" in the activity
  stream's labels, and the catalog's "Durable state" category description.
- The remaining fallback summaries listed above.
- 35 `scala unverified` blocks.

Verification: `mill __.test`, `mill documentation.check`, and
`npm run test:xray` pass. `./scripts/e2e-run-upstream.sh` was not run: the API
change removes overloads, and no runtime behavior changed.

### Phase 5 (2026-10-10): findings 18–20

API decision (finding 18, `ZioHttp.routes` overloads): `ZioHttp.routes` now
takes only the `ZioHttpConfig`. Removed `routes(application, security)` and
`routes(application, security, observer)`; they only unwrapped
`security.config`, and readers could not tell when to use which. Handlers that
need CSRF, flash, or cookies build `LiveSecurity(config)` from the same config.
The quick start, Learn, and services `Main` files no longer construct a
`LiveSecurity` they don't use, which also removed their `liveRoutes` value.

Terminology (finding 18):

- **Durable:** the activity stream's label is **Model history**, and its
  catalog description, reset description, trace label, and tests say the model
  keeps the history. The "State and rendering" category says "Model state".
  "Durable" now means only data kept in storage or a shared service.
- **Socket:** "socket" no longer stands for a LiveView lifecycle. Nested
  LiveViews, components, hooks, uploads, async work, and the guides index say
  "lifecycle"; transport text says "WebSocket" or "WebSocket connection". The
  nested guide says the child shares the page's WebSocket under its own topic.
  "Socket path" stays, because it names `withSocketPath`. Troubleshooting's
  `#diagnose-socket-connections` became `#diagnose-websocket-connections`, and
  the lifecycle example's label reads "Connected WebSocket mount".
- **`SCALIVE_TOKEN_SECRET`:** Configuration now shows a compiled loader that
  reads `SCALIVE_TOKEN_SECRET` and `SCALIVE_ALLOWED_ORIGINS` and fails when
  either is missing, and says these names are the application's. The quick
  start says Scalive reads no environment variables and that its `Main` falls
  back to a development secret. Deployment and troubleshooting no longer
  restate it.
- **`routes` / `liveRoutes`:** `application` names a `LiveApplication` and
  `routes` names ZIO HTTP routes everywhere; `liveRoutes` is gone from the
  content and fixtures.
- **Quick start:** lowercase in prose, "Quick start" only as a link title at
  the start of a sentence.
- Logout and example names were already fixed in phases 2 and 3.

Presentation (finding 19):

- **Sentence case:** every heading (468), both essay titles, and the guides
  index groups. Heading IDs are unchanged. Proper nouns, code names, and "Live"
  where it contrasts with HTTP keep their capitals.
- **Snippets that changed the model:** the components guide now embeds regions
  of the voting example, so `resetEpoch` is part of the model from the first
  snippet. The others (`saved`, `Model.Failed`, `workspaces.authorize`) were
  already compiled in phases 1 and 3.
- **Repeated text:** Configuration has a new "Admit WebSocket origins" section
  (`#admit-websocket-origins`) with the full Origin rules; troubleshooting,
  deployment, client setup, and the connected test reference link to it. The
  after-render contract lives only in the lifecycle hooks guide.
  Troubleshooting links to the Learn lifecycle lesson instead of restating the
  two mounts. The "two Scala coordinates" paragraph had already been reduced
  to the runtime essay.
- **Test evidence:** troubleshooting no longer cites native tests or upstream
  audits. The compatibility matrix's columns are now "What works" and "Known
  gaps"; spec class names, upstream test file names, and maintainer to-dos
  moved to `docs/compatibility-evidence.md`, along with the verification
  steps. The stale "typed URL builders may be exposed" gap now points to
  `location`.
- **Formatting glitches:** already gone after the phase 3 splits.

Contradictions (finding 20):

- The browser integration guide says that a decoded payload proves only its
  shape, that the server must validate and authorize it, and that JavaScript
  checks only help usability.
- The nested LiveView guide states the sticky exception: under connected-turn
  guards a child is never sticky. The hooks guide links to it instead of
  explaining it.

Also changed while editing these pages: troubleshooting puts lifecycle metrics
after the basic startup, asset, WebSocket, CSRF, and reconnect checks (P3).
Configuration's and lifecycle observability's inline blocks became regions of
a new `configuration` guide fixture, with a test for the origin parser.
`rg '```scala unverified' documentation/content` now reports 22 blocks.

Left for later:

- P3 smaller fixes other than the troubleshooting order.
- 22 `scala unverified` blocks: the dynamic HTML essay (7), browser integration
  (5), routes and navigation (5), nested LiveViews (2), and one each in client
  setup, the quick start, and the Learn testing lesson (the last two are
  `build.mill` files).
- About 1,000 fallback summaries in the API reference.

### Phase 6 (2026-10-10): P3 smaller fixes

No public API changed. Pages and decisions:

- **Phoenix orientation:** the page now says it is for Phoenix users and that
  Scalive itself needs no Elixir experience. A second table under "Translate
  state and effects" maps `handle_params`, `handle_info` with PubSub,
  `assign_async` / `start_async`, streams, uploads, `push_event`, `put_flash`,
  `push_patch` / `push_navigate` / `redirect`, and `LiveViewTest`, each with the
  guide section to read. The `handle_event` row now says that browser events,
  subscription values, and async results all arrive in `handleMessage`.
- **Flash guide:** renamed to "Flash messages, page titles, and connection
  status", and its URL to `/guides/flash-titles-and-connection-status` (was
  `/guides/flash-title-and-lifecycle-ux`). New compiled section "Disable
  controls while offline" (`feedback-offline-control`). Checked in Chromium
  against a root LiveView: the button is disabled in the static HTML, enabled on
  connect, disabled during an outage, enabled after reconnect, and stays enabled
  across server patches. A deliberate `liveSocket.disconnect()` does not run
  `phx-disconnected`, and nested LiveViews never run it, because the client
  tears them down first; the guide says to keep the bindings in the root
  LiveView. The site's lifecycle example is nested, so its "LiveSocket
  disconnected" label never appears during an outage; the docs shell's own
  status indicator does.
- **Guides index:** every description now names the reader's task. Configuration
  moved to "Setup and foundations" (order 3) next to client setup, and "Assets
  and operations" became "Operations" (observability and deployment). The
  layouts guide's link label for client setup now matches its title.
- **HTTP forms:** explains Post/Redirect/Get in one sentence. Handler
  registration was already shown by the compiled fixture.
- **Deployment:** new "Example: nginx" section and a curl check for `101` and
  `403`. The config was run with nginx 1.30.4 and TLS in front of the quick
  start: the page returned 200, an allowed upgrade 101, an unlisted origin 403,
  and without the `Upgrade` / `Connection` headers the upgrade returned 400. An
  idle browser page stayed connected through nginx for 80 seconds, past the
  default 60-second `proxy_read_timeout`.
- **Lifecycle observability:** new "Export to Prometheus" section built from a
  compiled `observability` fixture that uses
  `zio-metrics-connectors-prometheus` 2.5.8 (ZIO 2.1.26; 2.6.0 requires Scala
  3.9). Its test renders a Live route and reads
  `scalive_disconnected_render_total` from `/metrics`. Only `guideFixtures`
  gains the dependency.
- **Project index:** status, breaking changes, compatibility scope, and
  non-goals come first; the three essays follow in one "Read the design essays"
  list. The Project sidebar lists compatibility before the essays. "Non-Goals"
  became "Non-goals".
- **External links:** `DomDefsGenerator.mill` points the generated `action` and
  `alt` attribute docs at MDN's `form` and `img` element pages, because the
  generic attribute URLs return 404.

Left for later:

- 22 `scala unverified` blocks, as listed in phase 5.
- About 1,000 fallback summaries in the API reference.

### Phase 7 (2026-10-11): the last unverified blocks

Every inline Scala block is now either compiled or labeled for what it is, and
the content pipeline no longer accepts `scala unverified`.

- **Compiled (12 blocks):**
  - Browser integration (5): the push event, the root browser-event hook, and
    the hook `DomRef` are regions of the site's `BrowserInteropExample`, whose
    companion now keeps each payload next to its event name. The dialog
    commands and the component hook come from new `browser` guide fixtures.
    The guide now writes `JS.push(Msg.ConfirmDialog)`.
  - Routes and navigation (5): a new `routing` fixture holds the route
    declaration, the `mapParams` route (with a `SearchTerm` it previously left
    undefined), the eventless routed view, and both locations. A test checks
    the `href` values the guide states.
  - Nested LiveViews (2): a new `nested` fixture renders the sticky and the
    crash-linked child.
- **Relabeled (10 blocks):** the seven dynamic HTML essay blocks are
  `scala fragment`; five of them show rejected prototypes that cannot compile.
  The two `build.mill` files and the client-setup `bundle` task use the new
  `scala mill` label, which renders like plain Scala. `documentation.check`
  cannot compile a separate Mill build; the quick-start build was checked by
  hand in phase 1.
- **Pipeline:** `ContentPipeline.scalaFenceLanguage` accepts `fragment` and
  `mill` and rejects every other label, including `unverified`. Pipeline tests
  cover both.

Verification: `mill documentation.check`, `mill __.checkFormat`, and
`npm run test:xray` pass. With `--no-daemon`, Mill finished every task of
`documentation.check` (its profile records the result) but the process did not
exit; this happened twice and not in daemon mode.

Left for later:

- About 1,000 fallback summaries in the API reference.

### Phase 8 (2026-10-11): Scaladoc for every public definition

Every public definition now has Scaladoc, and `documentation.check` fails when
one does not. The generated fallback sentence is gone.

- **Hidden internals** (`feat(api)!`): the `Mod.Attr` and `Mod.Content` cases,
  `ComponentSpec`, `NestedViewSpec`, `ComponentDispatch`, and
  `HtmlElement.attrMods`/`contentMods` are private to `scalive` (132 symbols).
  `Mod.Input` now accepts elements and collections of elements, so the docs
  site and e2eApp no longer wrap elements in `Mod.Content.Tag`.
- **Pipeline:**
  - Classes, objects, enums, and opaque types exported from `scalive.upload`,
    `scalive.streams`, and `scalive.render` are documented as those
    definitions, with their members. `LiveUploadDef`, `LiveStreamDef`,
    `UploadRef`, and the `StaticHtmlError` cases used to show a bare alias with
    no members.
  - A field takes its owner's `@param` text, presented as a sentence. The 25
    curated summaries in `reference.json` moved into Scaladoc, and the
    mechanism is gone.
  - Undocumented `equals`/`hashCode`/`toString` overrides, `derives`
    instances, and companions without public members are left out. Signatures
    no longer show `X$package.` or the `streams.`/`upload.` prefixes.
  - `ApiSnapshot.validateDocumentation` requires Scaladoc on every signature
    except packages, and reports overloads whose first paragraphs differ.
- **Scaladoc:** 790 symbols in 51 files, including the lifecycle events, hook
  traits, `phx` attributes, forms, security, static assets, routing, and
  testing types. The pipeline's API index and search use the summaries
  directly.
- **Guide fixes found while writing:**
  - Lifecycle hooks: attaching an existing dynamic hook ID replaces that hook
    in place; it does not fail with `IllegalArgumentException`.
  - Form recovery: `phx.autoRecover := "ignore"` on a form that uses
    `onChange` renders a duplicate `phx-auto-recover` and fails. The guide now
    binds change events with `on.change.form` instead.
  - The HTML guide says a collection of elements is a valid child.

Verification: `mill __.test` (1,227 passed) after `mill clean`,
`mill __.compile`, `mill documentation.check`, `mill __.checkFormat`, and
`npm run test:xray` (32 passed) pass.

Fixed in a follow-up (`feat(api)!`): `dom.onMount`, `dom.onRemove`,
`connection.onConnect`, and `connection.onDisconnect` accepted messages, but the
Phoenix client only runs `JS` commands for these attributes. A message rendered
as a bare binding ID such as `b1:1`, and `JS.exec` then called `exec_null`,
which throws in the browser. They now return a `JSCommandBinding`, which
accepts only `JS` commands.

Found while writing, not fixed:

- `ConnectedLifecycleContext.epoch` is always 1, and a root LiveView keeps its
  `lifecycleId` across reconnects.
- Fixed: `ctx.components.sendUpdate[C](id, props)` found the component through
  `MODULE$` by reflection, so for a component defined as a class it failed the
  handler with `NoSuchFieldException` and the session ended. It is replaced by
  `sendUpdate(definition, id, props)`, which addresses the definition value the
  view rendered.
- `upload.onProgress` ignores its receiver.
- `LiveUploadError.fromReason` never produces `Custom`, so a custom error comes
  back as `Unknown` after a JSON round trip.
- A `LiveSession` can be passed into another `Live.session`, which silently
  drops the inner session's name.

## How this was verified

- I read all 56 authored pages and every embedded example source they
  reference.
- I compiled snippets with `scala-cli` (Scala 3.8.4) against
  `documentation.site.test.runClasspath` from this checkout. I added only the
  minimal enclosing context each page implies, such as a `LiveView` with
  `view(model: Signal[Model])`.
- I ran the quick start as a standalone external project against the published
  `0.0.1-341a561ee032-SNAPSHOT`, using Mill 1.1.8 and JDK 21.
  - Results: the dependency resolves, the app compiles, all three scripts
    return 200, and unversioned `/static/app.js` returns 404 as documented. In a
    real browser the page connected and **Increase** updated the counter.
  - One local deviation: NixOS needed `//| mill-jvm-version: system`, because
    by default Mill downloads a JDK that won't run there. This is specific to
    this environment.
- I measured API reference coverage from the generated `content.json`.
- I rechecked the four external links that `documentation.checkExternalLinks`
  reported as failing on 2026-10-01.
- **Not done:** I did not run `mill documentation.check`, replay every
  interactive example, or audit each generated API page by hand.

| Snippet | Location | Result |
| --- | --- | --- |
| Homepage handler | `index.md:14-23` | ✗ `Missing type parameter for scalive.MessageContext`; pattern-function parameter type cannot be inferred |
| Layer-backed route and startup | `guides/services-and-zlayer-injection.md:114-151` | ✗ `Found: (LiveRouteSeed[Unit], URLayer[Reports, ReportsLiveView]) Required: LiveRouteFragment[Any]` |
| Stream container | `guides/streams-and-collection-updates.md:155-162` | ✗ `value activityStream is not a member of Signal[Model]` |
| Upload controls | `guides/uploads-and-consumption.md:68-82` | ✗ `value upload is not a member of Signal[Model]` |
| Form bindings via the form value | `guides/typed-forms-and-validation.md:188-194` | ✗ `value onChange is not a member of Signal[Form[...]]` |
| Field error feedback | `guides/typed-forms-and-validation.md:466-470` | ✗ `value message is not a member of Signal[FormError[...]]` |
| Live validation, then HTTP | `guides/http-forms-and-redirects.md:236-244` | ✗ `value form is not a member of Signal[Model]` |
| First disconnected test | `guides/testing.md:77-94` | ✗ `Reference to Live is ambiguous` (`zio.test.Live` vs `scalive.Live`) |
| After-render hook | `guides/flash-title-and-lifecycle-ux.md:93-98`, `guides/lifecycle-hooks.md:307-313` | ✗ `value afterRender is not a member of object scalive.LiveHooks` |
| Single-field form | `guides/navigation-guards.md:102` | ✗ parse error at `(NoteText,)` |
| URL state example | `learn/url-state-and-navigation.md:12-56` | ✓ |
| Signal combination | `learn/rendering-and-dom-updates.md:39-77` | ✓ |
| Component with output | `guides/components-and-communication.md:53-93` | ✓ |
| Root and ordinary layout factories | `guides/layouts-sessions-and-mount-aspects.md:52-80` | ✓ |
| Static hooks | `guides/lifecycle-hooks.md:134-146` | ✓ |
| Routed eventless view | `guides/routes-and-navigation.md:72-81` | ✓ |
| Nested LiveView | `guides/nested-liveviews.md:42-51` | ✓ |
| Typed browser event hook | `guides/browser-integration.md:98-101` | ✓ |

Every failure is in an inline block. Every embedded `@:sourceRegion` I checked
is correct.

## P1 — Recipes that fail or mislead

### 1. The service-injection guide documents an API that doesn't exist

`guides/services-and-zlayer-injection.md` relies on registering routes with a
layer, but `LiveRouteBuilder.->` only accepts a `LiveView`
(`scalive/api/src/scalive/routing/LiveRouting.scala:460`). As a result, the
following parts are wrong:

- The four-step plan at `:26-31`: "derive a LiveView layer with
  `ZLayer.fromFunction`".
- `(live / "reports") -> ReportsLiveView.layer` at `:126-133`, along with the
  claim that "the route operator adds `Reports` to the route environment".
- Startup at `:145-150`, which passes a `LiveApplication` straight to
  `Server.serve` and skips `ZioHttp.routes`, security, and the client assets.
- "Register its constructor-derived layer" at `:183-185`.
- `ReportsLiveView.layer.provide(testReports)` at `:201`.
- `examples/service-injection.md:10`, which mentions `ReportsExample.layer`.
  That value doesn't exist. Its link label also doesn't match the UI ("Open the
  layer-backed route" vs "Open layer-backed route").

The supported API is `.from((params, request, service) => ...)` (`:464`) or
`.context((service: S) => ...)`. Both compile. The guide's own embedded example
(`ReportsExample.route`) uses `.from`, but the prose never explains it.

The same page also has these problems:

- It introduces `Model.Failed` (`:168`) without adding it to the enum
  (`:110-111`).
- The view's partial `model.map { case Model.Loaded(...) => ... }` (`:87-89`)
  would throw at runtime once `Failed` exists.
- It declares `object ReportsLiveView` twice (`:106`, `:119`).

**Fix:** Rewrite the guide around `.from` / `.context`. Show `Main` with
`ZioHttp.routes(application, security)` plus the asset routes, exactly as the
quick start does. Keep the model and companion object consistent from start to
finish. Remove "layer-backed" from the example page and UI, or make it true.

### 2. Main rendering recipes treat the model as a plain value inside `view`

The documented view signature is `view(model: Signal[Model])`, but these
recipes read fields directly from `model` or a form value:

- Streams: `model.activityStream.renderIn(...)` at
  `guides/streams-and-collection-updates.md:156`.
- Uploads: `model.upload.dropTarget` and `liveFileInput(model.upload, ...)` at
  `guides/uploads-and-consumption.md:68-82`.
- HTTP forms: `model.form.http(...)` and `model.form.onSubmit(...)` at
  `guides/http-forms-and-redirects.md:237-240`.
- Typed forms, in three places:
  - `profileForm.onChange` / `onSubmit` at `:191-192`, `:361-362`, and
    `paymentForm.disableRecovery` at `:382`. These are plain-`Form` methods with
    no `Signal[Form]` counterpart. The executable example uses
    `Profile.Definition.onChange` instead, and so does `:326`, a few
    paragraphs later.
  - `contactForm.rows(...).map { row => ... }` at `:418-421`.
  - `emailField.errorFeedback { error => messages(error.message) }` at
    `:466-470`. This only works on a plain form, but the signal version follows
    without explaining when to use which.
- HTML guide prose: `disabled := model.lines.isEmpty` at
  `guides/html-dsl-and-event-bindings.md:113`.

The underlying cause is that no page says plainly: "Inside `view`, `model` is a
`Signal`. Use `model.map(_.field)` and pass signals to helpers." The typed
forms guide also never explains when to bind through `Definition` and when
through a form value.

**Fix:** Every recipe that touches `model` should show the enclosing `view`
signature. In forms, use `Definition.onChange` / `onSubmit` everywhere and say
when the plain `Form` API applies (for example, HTTP rendering outside a
LiveView).

### 3. The testing guide's first examples fail for anyone following the docs

- **The imports at `guides/testing.md:78-83` break the example.**
  `zio.test.*` together with `scalive.*` makes `Live` ambiguous, so
  `Live.router(...)` on `:93` does not compile. The repository's own tests work
  around this with `scalive.Live.router` (for example
  `scalive/testing/test/src/scalive/testing/DisconnectedRenderSpec.scala:62`).
- **`zio.http.*` shadows the quick start's `Routes` object.** A wildcard import
  outranks a same-package object defined in another file, so `Routes.profile`
  resolves to `zio.http.Routes`.
- **The connected example doesn't match either documented counter.** At
  `:208-216` it calls `view.clickButton("Increment")` and reads `#count`. Both
  documented counters label the button **Increase**, and neither has a `#count`
  element (the quick start renders an `<output>`).
- **There is no complete test-module setup.** `:47-65` repeats the
  artifact-coordinates paragraph but never shows
  `object test extends ScalaTests with TestModule.ZioTest`, `zioTestVersion`, or a
  working command such as `mill app.test`. The line "Inside this repository,
  Mill modules use `moduleDeps = Seq(..., scalive.testing)`" is contributor
  information.

**Fix:** First decide whether to fix the `Live` / `live` clash in the API (see
"Decisions for implementation"). Then start the guide with a complete, compiled
test of the quick-start counter: the build changes, the file location, the
imports, and `mill app.test`. If the API keeps the clash, these imports compile
against the current classpath:

```scala
import zio.http.{Routes as _, *}
import zio.test.{Live as _, live as _, *}

import scalive.*
import scalive.testing.*
```

### 4. Two more invalid recipes

- **`LiveHooks.afterRender { ... }`** (`guides/flash-title-and-lifecycle-ux.md:94`,
  `guides/lifecycle-hooks.md:308`). The companion object has no such method.
  Use `LiveHooks.empty[Msg, Model].afterRender { ... }`, as
  `LifecycleExample.scala:12` does.
- **`Root.product[Note]((NoteText,))`** (`guides/navigation-guards.md:102`).
  This is a syntax error; the repository uses `Tuple1(NoteText)` everywhere.
  The forms guide never says that a single-field form needs `Tuple1`, which is
  surprising enough to deserve a sentence.

### 5. The external-upload recipe can report completion before success

The XHR progress listener at `guides/uploads-and-consumption.md:307-311` sends
`Math.round(loaded / total * 100)`. That reaches 100 before the `load` handler
has checked the response status, and the Phoenix client treats 100 as done.

**Fix:** Cap progress at 99 and send 100 only in the successful `load` branch.

### 6. The homepage snippet is not valid Scala as shown

`index.md:14-23` is a top-level `def` with no enclosing `LiveView`, so its
`MessageContext` alias and the parameter type of the pattern function cannot be
resolved. This is the first code most visitors see.

**Fix:** Show a compact, complete LiveView that includes the `on.click`
binding. The page advertises the browser-to-server loop, so the snippet should
show it.

### 7. Systemic cause: 176 inline Scala blocks are never compiled

Pages with the most unchecked blocks: typed forms (21), HTML (13), testing (12),
uploads (11), HTTP forms (10).

The quick-start pattern works: real files with `docs:start` / `docs:end`
regions, rendered with `@:sourceRegion` and compiled by `documentation.check`.

**Fix:** Move every recipe a reader is expected to copy into compiled fixtures,
for example `documentation/fixtures/guides/<guide>/...`. Allow inline blocks
only for short fragments labeled as such. During alpha this is the only way to
keep up with API churn.

## P2 — Everyday knowledge that is missing

### 8. Conditional rendering is never taught

`when`, `choose`, and `option` are only named
(`learn/rendering-and-dom-updates.md:34-35`,
`guides/html-dsl-and-event-bindings.md:35-36`). `.when(` does not appear in any
content page.

The one explanation of why `if model.x` doesn't work in a view, and what to do
instead, is in the Project essay `project/dynamic-html-identity.md:222-242`.
Meanwhile the Learn rendering page spends `:46-78` on tuple-flattening rules
and `.tupled`.

**Fix:** Replace that space with three short recipes: show a section when a
flag is set, render an optional value, and switch between two layouts. Move
the tuple semantics to Scaladoc on `Signal.combine` / `combineWith`.

### 9. Messages carrying a row's current value, and helpers that take signals

Two patterns that real apps need are not explained anywhere:

- **Row-specific messages.** The shopping cart builds row messages with
  `on.click(line.map(line => Msg.Remove(line.product)))`
  (`ShoppingCartExample.scala:99`). The essay shows another form,
  `on.click(product)((value, _) => ...)` (`dynamic-html-identity.md:184`).
  Neither is explained, and the Learn `splitBy` snippet (`:130-137`) has no
  binding at all.
- **Reusable helpers.** No page explains how to write a helper that takes a
  `Signal[A]` rather than an `A`.

### 10. No page shows how to start work when a page connects

This is the most common LiveView pattern: Phoenix's
`if connected?(socket), do: subscribe(...)` and `assign_async`.
`RootMountConnected` exposes `async` and `subscriptions`
(`scalive/api/src/scalive/lifecycle/Connection.scala:15-21`), yet no page calls
`connected.async.start(...)` or `connected.subscriptions.start(...)` from a
root `mount`. Every example starts work from a button.

The async guide also opens with `ConnectedResources` and route/session
initializers (`guides/async-work-and-subscriptions.md:52-160`), before its
first finite task.

**Fix:** Lead with "load data when the page connects" and "subscribe to live
updates when the page connects". Move connected resources to an advanced
section.

### 11. Route-level service injection is undocumented

Apart from the broken layer section (finding 1), no prose covers
`.from((params, request, service) => ...)` or `.context((s: S) => ...)`. The
authentication guide shows `.context` with a service (`:234-243`), but you have
to already know to look there.

### 12. Most event bindings are missing from prose

None of these appear in any content page: `keyUp` / `keyDown` (with key
filtering), `clickAway`, the window variants, `focus`, `viewportTop` /
`viewportBottom` (infinite scroll), `throttle`, `debounceOnBlur`, and
`connection.onConnect` / `connection.onDisconnect`. All of them are defined in
`scalive/api/src/scalive/Scalive.scala`, in `object on` and `object connection`.
`throttle` and `debounceOnBlur` are on `HtmlAttrBinding`
(`scalive/api/src/scalive/HtmlElement.scala:153-158`).

The HTML guide's only rate-limiting example debounces a **blur** event
(`:231`, `:247`), which is a confusing choice. Add a compact binding table with
one example per family.

### 13. The API reference rarely explains anything

Of 2,210 symbols, **1,347** carry the generator's fallback summary ("The
`scalive.X.y` method."). Central entries such as `LiveView.mount`,
`LiveView.view`, `LiveView.pageTitle`, `LiveView.hooks`,
`LiveRouteBuilder.->` / `.from` / `.context`, `Live.session`, every
`LiveComponent` lifecycle method, and `Form` / `FormDefinition.onChange` all
have the fallback. Every `@:apiSymbol` link in the guides that points at them
leads nowhere useful.

The overview page (`api/index.md`) also has these issues:

- It says **Filter symbols**, which doesn't match the UI label.
- It describes `scalive.testing` without mentioning the connected client.
- It lists lifecycle resources and observability under **Forms**.
- Its package names are not links.

**Fix:** Write Scaladoc for the roughly 40 symbols a first application uses
before anything else.

## P2 — Structure: advanced material comes first

### 14. The Learn path

- **Order and placement:** URL state (order 4) comes before rendering
  (order 5) (`learn/index.md:57-60`). Project anatomy is the second page, yet
  it covers asset models and deployment manifests (`:59-70`).
- **The quick start mixes production advice into the first run:** secret,
  cookie, and origin policy (`:130-143`), artifact internals (`:69-73`), and a
  WebSocket `Origin` aside (`:157-159`). It never explains what the root
  layout's `key` is for, or its `[Any, Any]` / `[Msg]` type parameters.
- **The lifecycle page assumes advanced knowledge:** it sends Learn readers to
  `withAdmission` and the connected harness (`:111-112`).
- **Models and messages takes a detour** through forms examples (`:53-55`)
  before finishing its own example.
- **Missing steps:** there is no path from the counter to a first form, a
  first service-backed page, or a first test.

### 15. Guides lead with contracts instead of the common case

| Page | What comes first today | What should come first |
| --- | --- | --- |
| Typed forms | Blur-feedback policy (`:221-274`) sits between the change binding and the submit handler. Basic field rendering first appears at `:454`, after repeated rows and the Phoenix adapter. | One continuous path: define → mount → change → submit → render fields and errors → reset. Then variants. |
| Layouts, live sessions, and mount aspects | A table comparing session and route mount aspects (`:16-45`) before any layout is shown | Root layout → ordinary layout → named sessions. Aspects and context selection on a separate page. |
| Lifecycle hooks | Connected-turn guards (`:53-126`), a routing feature | A decision table (handler / hook / guard / observer), then static hooks |
| Async work | Connected resources and route initializers | Connected mount → async → subscriptions → resources |
| Authentication | Physical-connection admission (`:94-109`) before both-phase authentication (`:111`) | The login → protected page → logout journey, then admission and fanout |
| Testing | WebSocket origin tests as a top-level section (`:537-563`); following a navigation sits inside the typed-forms subsection (`:454-475`) | First test, then connected interactions, then forms, then reference material |
| HTML and bindings | Static HTML rendering, portals, and focus wrap come before keyed lists | Tree → attributes → bindings → conditionals and lists → helpers → advanced |
| Client setup and static assets | "Choose an asset model" (manifest vs tree) is the first section | Packaged clients → `LiveSocket` → application assets. Manifests last. |

### 16. Too many ways to declare a layout

Root layouts can be declared in three ways
(`scalive/api/src/scalive/routing/LiveLayouts.scala`):

- **Extend the `LiveRootLayout` trait** and implement `key` and `render[Msg]`.
  Used by the quick start, the documentation site, the e2e app, and the
  navigation-guards guide (`:57-79`).
- **The factory** `LiveRootLayout[A, Ctx]("key")([Msg] => (content, pageTitle, ctx) => …)`.
  Used by the layouts guide (`:53-59`), `rootSliceApp`, and tests.
- **`LiveRootLayout.dynamic[A, Ctx](ctx => key)([Msg] => …)`.** Used by
  `LayoutContextExample` and tests.

Ordinary layouts can be declared in two ways: extend `LiveLayout` and implement
`view`, or call `LiveLayout[A, Ctx]([Msg] => …)`. The two traits also name
their method differently (`view` and `render`). Only the layouts guide explains
what the root key is for.

**Decision:** keep only one way.

**Recommendation:** keep the trait, implemented by a class, and remove the
`apply` and `dynamic` factories for both root and ordinary layouts:

- Every real root layout in the repository is already a class, because it
  needs loaded assets, and those fit naturally in a constructor.
- `key(ctx)` handles both fixed and context-dependent keys. The factories need
  two separate functions for that.
- The factories need a polymorphic function literal (`[Msg] => …`), plus
  explicit `[A, Ctx]` type arguments. That is the hardest syntax in the API for
  a newcomer.

The class form is verbose: it spells out the full `render[Msg](...)` signature
and must define `key` even when the key never changes. Reduce that as part of
the change if possible, for example with a simpler way to declare a fixed key.
Also give the methods of the two traits the same name. Roughly 20 factory call
sites change, mostly in tests. The docs for the remaining form must explain the
key.

### 17. Example catalog order and format

- **Duplicate `order` values** (2, 5, 7, 11) and an outlier (`text-upload.md`:
  110) produce this sidebar: Counter, **Browser integration**, Shopping cart,
  Lifecycle, Profile form, Activity stream, Repeated contacts, and so on. An
  advanced example ends up second.
- **Pages lack "try this" guidance.** Example pages put the full source before
  the live result. The counter page is a single sentence.
- **`examples/text-upload.md:8` gives the wrong order.** It says to submit
  "after the upload completes", but uploads are manual: submitting starts the
  transfer. The button label "Summarize completed file"
  (`TextUploadExample.scala:82`) reinforces the mistake.
- **`examples/component-subscriptions.md:13-20` depends on timing.** It asks
  readers to remove and reinsert a component before the browser confirms
  destruction, which can't be reproduced reliably by hand.
- **`examples/navigation.md:8-11` doesn't warn that its buttons leave the
  page.** They navigate to the site's search route. Say so before the action
  and explain how to come back with Back.

Standardize each example page as: what it shows → try these steps → what to
expect → key lines → full source → related guide.

## P2 — Consistency

### 18. Terminology drift

| Term | Problem | Where |
| --- | --- | --- |
| durable | Used for an in-memory `Vector` rebuilt on every mount, contradicting Learn's definition | `examples/activity-stream.md:3,9`; `ExampleCatalog.scala:27,31`; UI label `ActivityStreamExample.scala:45`; `guides/streams-and-collection-updates.md:3,119,139`; `guides/index.md:60` |
| logout / reset | The title and description say "logout"; the guides index says "reset"; a section is titled "Revoke Through A CSRF-Protected Reset" | `guides/authentication.md:3,192`; `guides/index.md:51` |
| socket | Means "LiveView lifecycle" in some places and "WebSocket" in others. Nested LiveViews share one WebSocket. | `guides/components-and-communication.md:23`; `guides/nested-liveviews.md:28`; `guides/index.md:58` |
| `SCALIVE_TOKEN_SECRET` | The quick start says production "must require" it, while Configuration says Scalive reads no environment variables. Only Deployment clarifies it is an application convention. | `learn/quick-start.md:135`; `guides/configuration.md:17`; `guides/troubleshooting.md:182`; `guides/deployment.md:38-41` |
| `ZioHttp.routes(application, config)` vs `(application, security)` | Both overloads appear with no explanation of when to use which | `guides/configuration.md:32` vs `:105`; `guides/testing.md:94` |
| `routes` / `liveRoutes` | Each name refers to different types on different pages. `routes` is a `LiveApplication` in some places and ZIO HTTP routes in others. `liveRoutes` is ZIO HTTP routes in the quick start but route declarations passed to `Live.router` in navigation guards. | `learn/url-state-and-navigation.md:53`; `guides/routes-and-navigation.md:28`; `guides/layouts-sessions-and-mount-aspects.md:186`; `guides/navigation-guards.md:33` |
| Example names | "Lifecycle and connection phases" and "Validated profile form" don't match the actual titles ("Lifecycle and connection state", "Typed profile form") | `learn/where-to-go-next.md:44,48` |
| Quick start | Written as "Quick Start", "quick start", and "Quick start" | `guides/deployment.md:13,16,30,39,46` and elsewhere |

### 19. Presentation

- **Heading case:** 337 of 342 multi-word H2/H3 headings capitalize every word,
  articles included ("Take The Ordered Path", "Know Which Side Owns What").
  Page titles use sentence case. The Project essays don't ("Why I Built
  Scalive", "Designing Dynamic HTML"). The guides index uses "Setup And
  Foundations" while the sidebar group says "Setup and foundations".
  Recommendation: sentence case everywhere.
- **Snippets change the model mid-recipe:** `saved` (`typed-forms…:144`),
  `resetEpoch` (`components…:137`), `Model.Failed` (`services…:168`). Two
  snippets on the same page give `workspaces.authorize` different signatures
  (`layouts…:254` vs `:299-302`).
- **Snippets call operations that are never defined:** the save workflow ends
  with `install(nextModel)` and `submission.foreach(enqueueSave)`
  (`typed-forms…:641-643`). Neither exists, so the reader never sees the
  actual `handleMessage` case that commits the model and starts the save with
  `ctx.async.start`.
- **Repeated text:**
  - The "exactly two Scala coordinates" paragraph appears verbatim in three
    places (`quick-start.md:69-73`, `testing.md:49-52`,
    `runtime-architecture.md:51-55`).
  - WebSocket `Origin` rules are restated in six pages.
  - After-render limits are explained twice.
  - The two-mount explanation appears in six pages.

  Keep one canonical explanation of each and link to it from the others.
- **Test evidence in user guides:** "Current tests cover…"
  (`html-dsl…:335-338`), protocol-auditing notes (`troubleshooting.md:205-211`),
  and test names in the compatibility matrix. Move these to contributor-facing
  material.
- **Formatting glitches:**
  - `testing.md:177` is misindented, and `testing.md:423` is not indented at
    all.
  - In `typed-forms…:110-111`, a ZIO aside sits between "store … in the model:"
    and its code block.

### 20. Pages that contradict each other on safety and ownership

- **Browser input validation is placed on the client.**
  `guides/browser-integration.md:105-107` says JavaScript is not trustworthy,
  then says to "validate shape, length, permissions, and browser failures before
  calling `this.pushEvent`". That reads as advice to validate in JavaScript.
  Say clearly that the server must validate and authorize every decoded
  payload, and that client-side checks are only for usability and
  browser-error handling.
- **The nested LiveView guide leaves out a sticky exception.**
  `guides/lifecycle-hooks.md:100-102` says a nested view with connected-turn
  guards becomes non-sticky. `guides/nested-liveviews.md:103-116`, where readers
  decide whether to use `sticky = true`, doesn't mention this.

## P3 — Smaller fixes

- **Toolchain:** the quick start names no Mill or JDK version. The build uses
  Mill 1.x syntax (`mvn"..."`), which fails on Mill 0.12. Add a
  `//| mill-version: 1.1.8` header; that combination works, as verified above.
  The "dependency resolution" link (`quick-start.md:167-168`) points to a
  troubleshooting section with no dependency-resolution advice.
- **Phoenix orientation:** it opens with "No Elixir or Phoenix experience is
  required" (`:11`) on a page meant for Phoenix users. The mapping table lacks
  `handle_params`, `handle_info` / PubSub, `assign_async` / `start_async`,
  streams, uploads, `push_event`, `put_flash`, `push_patch` / `push_navigate`,
  and `LiveViewTest`.
- **Flash guide:** "Lifecycle feedback and page state" is hard to find by
  search; consider "Flash messages, page titles, and connection status". The
  promised offline-control pattern (`:83-86`) has no example.
- **Guides index:** the descriptions describe mechanisms rather than tasks.
  "Assets and operations" contains no asset guide. "Where to go next" skips
  Navigation guards and the component-subscriptions example.
- **Troubleshooting:** it starts with lifecycle metrics (`:38-57`) before basic
  startup, asset, and socket checks.
- **HTTP forms:** it names Post/Redirect/Get (`:19`) without explaining it. It
  also defines a `createSession` handler (`:170`) and an `httpRoutes(security)`
  value (`:101`) but never shows how the handler is registered on
  `SessionRoute`.
- **Deployment:** it has no concrete reverse-proxy configuration and no
  end-to-end success check for one.
- **Lifecycle observability:** the exporter setup is left to the reader
  (`:84-87`). One concrete connector setup, such as Prometheus, would make the
  verification steps on `:89-93` something a reader can actually follow.
- **Project index:** it leads with essays (`:8-31`) before status and
  compatibility (`:32+`).
- **Compatibility matrix:** the "Known gap or decision" column mixes
  maintainer to-dos with support status. One entry is stale: "More ergonomic
  typed URL builders may be exposed" (`compatibility.md:88`), even though the
  guides already teach `location`.
- **External links:** two MDN URLs in the generated API reference return 404
  (`/Web/HTML/Attributes/action`, `/Web/HTML/Attributes/alt`). On 2026-10-01
  the link checker reported `osteoview.app` (404) and the dev.to article (403)
  as failing. Both returned 200 today with a browser user agent, so no change
  is needed.

## Recommended information architecture

Keep the five top-level sections. Change what each one teaches.

**Learn: build one application step by step**

1. Quick start: pinned toolchain; production settings moved into one callout.
2. Models, messages, and effects: extend the counter with Reset.
3. Rendering views: `map`, `when` / `choose` / `option`, `splitBy` with
   row-specific messages, and helpers that take signals.
4. Your first form: two fields, change, submit, errors, reset.
5. Loading data and live updates: a service, async work on connect, and a
   subscription.
6. URL state and navigation.
7. Lifecycle and reconnects.
8. Testing your LiveView: a complete test module for the counter.
9. How the pieces fit: today's Project anatomy, moved to the end as a recap.

**Guides: task first, reference second.** Split these pages:

- **Typed forms:** build and validate; feedback and recovery; richer controls;
  repeated rows; save workflow.
- **Layouts:** layouts; live sessions and mount aspects.
- **Async:** async work; subscriptions; connected resources.
- **Testing:** testing basics; connected-harness reference.
- **New:** an event-bindings reference.

Each page should open with one minimal working recipe before variants and
contracts.

**Examples:** order them within each topic from simple to advanced and use the
standard page format from finding 17.

**API:** write Scaladoc for the core types first. Turn the overview page into a
topic map with links to each package.

**Project:** status and compatibility first, essays after.

## Editorial standard

1. Open with the reader's outcome, then the shortest working recipe.
2. Use one model per recipe. If a snippet uses `model`, show the `view`
   signature around it.
3. Recipes come from compiled files. Inline fragments are labeled as fragments.
4. Use one name per concept: `application` for `LiveApplication`, `routes` for
   ZIO HTTP routes, and the terms below.
5. Use sentence case for every title and heading. Quote UI labels exactly.
6. Explain each cross-cutting rule once, in one place, and link to it.
7. Keep test evidence and maintainer decisions out of user guides.
8. End each page with a way to check that it worked and one next step.

| Term | Use it for |
| --- | --- |
| Model | Immutable state owned by one LiveView or component lifecycle |
| Durable state | Data that outlives the connection or process because it is kept in external storage or a longer-lived service |
| WebSocket connection | The physical browser-to-server transport |
| LiveView lifecycle | One independently mounted root or nested view, with its own topic on the shared WebSocket |
| Named live session | A group of routes that share navigation and mount policy |
| Application session | Login state managed by the application |
| Subscription | A message-producing `ZStream` managed by a lifecycle |
| Rendered stream | A collection updated by ID through `LiveStream` |
| Browser hook | JavaScript attached to an element's browser lifecycle |
| Lifecycle hook | Server-side interception or observation of a lifecycle stage |

## Notes for implementers

- **Work on one phase per session.** Each phase in "Suggested order of work"
  is a reasonable unit of work. The whole review is too much for one session.
- **Line numbers refer to revision `341a561`.** They shift after the first
  edits, so search for the quoted text instead.
- **Commands:**
  - `mill --ticker false documentation.check` runs the content pipeline,
    internal link and anchor checks, `@:sourceRegion` extraction, the
    quick-start fixture compile, the model, pipeline, and site tests, and the JS
    unit tests.
  - `npm run test:xray`, run in `documentation/site`, runs the Playwright site
    tests. `documentation.check` does not run them.
  - `mill --ticker false documentation.checkExternalLinks` checks external links.
  - `mill --ticker false __.test`, and `./scripts/e2e-run-upstream.sh` when
    runtime behavior changes, after any API change.
  - `mill --ticker false __.reformat + __.fix` before committing.
- **Compiled snippets:** mark a region in a source file with
  `// docs:start <name>` and `// docs:end <name>`, then embed it with
  `@:sourceRegion(<path>, <name>)`. The pipeline reads only from the roots in
  `allowedSourceRoots` (`documentation/package.mill:42-49`). A new fixtures
  directory needs an entry there, plus a compiled Mill module added to
  `documentation.check`, as `quickStartFixture` is.
- **Things that must change together:**
  - **Example pages:** an example page's route must be `/examples/<id>`, where
    `<id>` comes from `ExampleCatalog.scala`. Its front-matter `title` and
    `description` must match the catalog entry exactly. Otherwise
    `DocumentationApplication.from` rejects the generated documentation and the
    site won't load (`validateCanonicalExamples`,
    `DocumentationApplication.scala:305`). The `order` value in the front matter
    controls the sidebar.
  - **Example UI labels:** these are defined in
    `documentation/site/src/scalive/docs/examples/*.scala`. Unit tests in
    `documentation/site/test/src` and the Playwright specs in
    `documentation/site/test/xray` may assert them.
  - **Other pages:** a page's URL comes from its file path under
    `documentation/content`.
- **Heading anchors:** every heading has an explicit `{#id}` anchor. Changing a
  heading's text or case is safe; changing its ID breaks links, and the internal
  link check reports them.
- **API changes:** Scaladoc-only edits don't affect `pipeline.checkApi`, because
  `documentation/pipeline/api/public-api.snapshot.json` records signatures
  only. After a signature change, run
  `mill --ticker false documentation.pipeline.updateApiSnapshot`. Scalive is in
  alpha, so no compatibility shims or deprecations are needed (`AGENTS.md`).

## Suggested order of work

1. Add a compile check for recipes (finding 7) and fix findings 1–6. Make API
   changes first, as described in "Decisions for implementation".
2. Fill the Learn gaps (findings 8–11) and add first-form and first-test
   lessons.
3. Restructure the guides that lead with contracts (finding 15), split the four
   largest pages, and settle on one way to declare layouts (finding 16).
4. Write Scaladoc for the core API (finding 13). Fix the example catalog order
   and page format (finding 17).
5. Make an editorial pass for terminology, headings, repetition, and
   contradictions between pages (findings 18–20).

The work is done when:

- A Scala developer new to Scalive can follow the quick start to a running
  counter without guessing tool versions or missing files.
- Every recipe a reader is expected to copy compiles in `documentation.check`.
  Anything else is clearly labeled as a fragment.
- A reader can build a basic form, a service-backed page, and a connected test
  without piecing together advanced sections.
- Prose, catalog cards, UI labels, and example source agree about how long state
  lives, the order of actions, and what is supported.
- Each long guide offers a short recommended path before alternatives and exact
  contracts.
- Core API entries say when to call the method, what it returns, and what can
  fail, and they link to a guide.
- External links pass, or failures are confirmed to be access restrictions.
