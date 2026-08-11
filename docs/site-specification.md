# Scalive Documentation Site Specification

## Purpose

The documentation site is the canonical public resource for learning and using
Scalive.

Its primary outcome is to help a Scala developer build and run their first
LiveView confidently. It combines concise explanations, compilable source code,
and embedded interactive examples to teach Scalive's programming model while
remaining useful as an ongoing reference.

The site must:

- provide a short path from setup to a working LiveView;
- introduce concepts when they become relevant;
- demonstrate Scalive through real examples powered by Scalive itself; and
- provide trustworthy guidance on APIs, capabilities, compatibility, and project
  maturity.

## Audience

The primary audience is Scala developers with basic web development knowledge
who are new to the LiveView programming model.

Readers are expected to understand Scala 3 fundamentals, basic HTML and HTTP,
and general server-side application development. Prior knowledge of Phoenix
LiveView or ZIO is not required. The documentation introduces the small subset
of ZIO needed by each example without attempting to teach ZIO comprehensively.

Secondary audiences are Phoenix LiveView developers evaluating Scalive,
existing Scalive users looking for guides and API reference material, and
developers evaluating Scalive's capabilities and maturity.

## Experience Principles

### Learn by building

The main learning path produces a working LiveView quickly, then introduces the
programming model through incremental, executable lessons.

### Concise first, depth on demand

Pages lead with the minimum explanation needed to proceed. Conceptual detail,
edge cases, full source files, raw traces, and API specifics remain available
without interrupting the main path.

### Examples as evidence

Examples use real, compilable Scalive code. Displayed source is extracted from
the implementation that runs wherever practical, rather than copied into
independent snippets.

### Optional runtime X-ray

Interactive examples remain approachable by default. Visitors may enable an
attached X-ray inspector to understand their actual runtime behavior.

### Dependable documentation

Core content and navigation remain usable without a live connection.
Interactivity enhances the documentation rather than gating access to it.

### Scala-first guidance

The site presents the recommended Scalive API and application style directly.
Phoenix terminology and comparisons appear only when they improve understanding.

### Honest project status

The site clearly documents Scalive's alpha maturity, supported capabilities,
intentional divergences, and known compatibility gaps on its project status
pages.

## Information Architecture

The primary navigation is:

- **Learn**
- **Guides**
- **Examples**
- **API**
- **Project**

### Homepage

The homepage explains Scalive's value, presents one small live example, and
leads directly to the quick start. It is an entry point to the documentation,
not a marketing site or exhaustive section index.

Its copy remains authored as a normal generated `Page` using the shared `Block`
model. Startup validates the expected homepage structure, then a dedicated
LiveView presents those typed blocks in a wide editorial shell with a real,
isolated counter example. The page remains meaningful without JavaScript.

The authored homepage contains, in order:

1. The rendered heading `Live interfaces. Typed end to end.` and a factual
   description of Scalive as a Scala 3 re-implementation of Phoenix LiveView.
2. Ordinary internal links to Learn and API.
3. One compact, verified Scala code block.
4. One `counter` example reference rendered as a compact nested LiveView.
5. Three principles covering typed state, Scala HTML rendering, and live
   diffs and effects.
6. A `Why Scalive` section with the stable `#why-scalive` anchor.
7. The alpha-software warning as an information callout.

The browser title for `/` remains `Scalive`. Homepage copy, links, code,
callouts, and example references remain canonical in
`documentation/content/index.md`; presentation code does not duplicate them.
The homepage structure is validated at startup with source-oriented errors for
missing, duplicated, reordered, or incorrectly typed blocks. Its compact
counter reuses the registered counter behavior with an isolated, route-scoped,
non-sticky instance rather than introducing homepage-specific behavior.

### Learn

Learn is a short ordered path:

1. A roughly ten-minute quick start that builds a counter.
2. Project anatomy and application startup.
3. Models, typed messages, and state transitions.
4. Rendering and DOM updates.
5. Lifecycle and connection behavior.

The quick start uses Mill as its primary build tool, provides a complete minimal
project to copy, and documents the current Node/npm client asset setup. Concise
dependency equivalents for other common Scala build tools may be included where
useful.

### Guides

Guides are task-oriented, with one focused task per page. They are grouped by
work rather than by skill level or a mirror of the package hierarchy:

- building interfaces and handling input;
- state, services, and data collections;
- routing, navigation, sessions, and application structure;
- asynchronous work, subscriptions, and lifecycle behavior;
- browser integration and client events;
- testing and troubleshooting; and
- assets, configuration, and deployment.

Together, the guides and API reference cover every supported public Scalive
feature. A dedicated Phoenix LiveView orientation guide is supplemented by brief
comparison callouts where they clarify a concept.

### Examples

Examples are owned by and integrated into the documentation application. The
same example may appear in a guide and in a browsable catalog with topic filters,
links to related guides, and source links. Examples may be changed freely from
the current examples application when doing so improves teaching.

The documentation application replaces the standalone `examples` application
as the canonical teaching application. Upstream parity fixtures remain separate
and are not presented as recommended application style.

### API

The API section combines curated, human-written guidance with generated symbol
documentation. Generated pages:

- cover only Scalive's intentional supported public boundary;
- use the same navigation, styling, URLs, and search as the rest of the site;
- link to definitions pinned to the exact documented repository commit; and
- do not expose implementation details merely because they are technically
  public in bytecode.

Scalive's maturity is described at the project level rather than through
per-symbol alpha or compatibility badges.

### Project

The Project section contains only:

- project status and alpha expectations; and
- a compatibility matrix of supported features, intentional divergences, and
  known gaps.

The compatibility matrix is curated, identifies a pinned Phoenix LiveView
release or commit as its baseline, and links to relevant test evidence where
available. Changelogs, roadmaps, contributor instructions, internal design
specifications, and implementation plans are not part of the initial public
site.

## Content Model

Narrative content is written in Markdown. A small fixed directive syntax embeds
components registered in Scala; Markdown cannot contain arbitrary Scala
expressions.

Documentation pages follow these conventions:

- use direct, explanatory prose without promotional language;
- address one task per guide page;
- show a focused extracted source region before offering the complete executable
  file; and
- use durable heading anchors, source-edit links, and prefilled issue links.

Markdown, directives, headings, internal links, extracted source regions, and
the search index are validated at build time. Invalid content fails the build.
External links are checked separately so network failures cannot make normal
builds flaky.

The homepage adds no public content type or Markdown directive. Its authored
block shape is validated by the site when loading the generated bundle, while
the shared pipeline continues to own links, search entries, navigation metadata,
source locations, and sitemap coverage.

Public API drift also fails validation. A change to the supported public boundary
must update affected generated or curated documentation in the same change.

Existing public material, including the public API reference and compatibility
matrix, moves into the documentation content tree and becomes canonical there.
Internal design history remains repository-only. Content is English-only, and
the initial architecture includes no localization abstraction.

The site documents the latest Scalive revision only. It does not preserve
versioned documentation during alpha. Page paths are generated from the content
tree; redirect stability is not a requirement when content is reorganized.

## Interactive Examples

Interactivity is used where behavior is clearer through use, not as a requirement
for every topic.

Each example must:

- be real executable Scalive code with behavior tests;
- isolate mutable state per connection;
- reset on page exit or explicit visitor request;
- avoid mutable state shared between visitors; and
- remain understandable when interaction is unavailable.

Authentication, uploads, and other sensitive flows may execute for real only
with fixed identities, strict resource limits, ephemeral isolated data, and
automatic cleanup. No example provides visitor accounts, durable storage, or an
arbitrary Scala execution environment.

Examples may expose deterministic preset variations for latency, failure,
cancellation, replacement, and reconnect behavior when these states are part of
the lesson. An example specifically teaching type safety may include an invalid
variant. Such variants are checked as expected compilation failures during the
build and show only the focused relevant compiler diagnostic.

### X-Ray Inspector

X-ray mode is attached to the example it observes and appears beside or below it
depending on available space. It captures actual end-to-end execution rather
than replaying a hand-authored animation:

1. browser event;
2. decoded typed message;
3. handler and lifecycle invocation;
4. model transition;
5. render result;
6. wire patch; and
7. DOM application.

The inspector leads with an annotated, human-readable trace. DOM changes are
shown as readable diffs, with the underlying protocol payload available on
demand. The summary exposes causal ordering across browser and server records
without implying that their independent producer sequences share a clock. Trace
history is bounded and scoped to one example. Raw records for the latest
interaction use protocol frames to expose causal browser-to-server and
server-to-browser handoffs; older or uncorrelated records retain only their
producer-local order.

Instrumentation is documentation-specific initially, but its boundary should
remain generic enough to inform a future Scalive developer-tools capability. It
must not expand the supported public framework API solely for the documentation
site.

## Navigation And Search

On desktop, pages use section navigation on the left, primary content in the
center, and an in-page outline on the right. Navigation collapses appropriately
on smaller screens, and examples and inspectors stack without horizontal page
overflow.

The homepage and search results are deliberate wide-shell exceptions. Generated
Learn, Guide, Example, API, and Project pages retain the three-column desktop
documentation shell.

All internal destinations are ordinary HTTP links progressively enhanced by
Scalive navigation. Reading and navigation therefore continue when a live
connection is unavailable.

A subtle global indicator exposes connected, reconnecting, and offline states.
During disconnection, embedded examples keep their last rendered state visible,
disable unavailable actions, and explain reconnection. They reset only when a
remount requires it.

One site-wide search covers learning content, guides, examples, curated
reference, generated API symbols, compatibility notes, and project pages. A
build-generated index provides instant search, while URL-addressable,
server-rendered results provide a dependable fallback.

## Visual And Content Design

### Direction

The selected visual direction is Signal / Editorial: a precise technical
publication interrupted by one unmistakable live signal. It evolves the living
technical notebook into a cleaner editorial system for prose, source, traces,
and runtime evidence. Red is reserved for identity, action, and meaningful live
transitions rather than general decoration.

### Mark And Wordmark

The visible lockup uses a two-plane Scalive mark and a lowercase `scalive`
wordmark. Prose, metadata, titles, and accessible labels retain proper-case
“Scalive.” The home lockup has an accessible name such as `Scalive home`; its
decorative mark is not exposed separately to assistive technology.

The mark uses this normalized `96 96` view box and two-path geometry:

```svg
<svg viewBox="0 0 96 96" aria-hidden="true">
  <path d="M18 52V26L78 2V28L38 44H46L42 52Z" />
  <path d="M54 44H78V70L18 94V68L58 52H50Z" />
</svg>
```

The mark preserves these construction invariants:

- Each ribbon is one connected, seven-vertex, non-self-intersecting polygon.
- The bottom ribbon is the exact 180-degree rotation of the top ribbon around
  `(48,48)`.
- The complete visible bounds are 60 units wide by 92 units high; each ribbon
  is 60 units wide by 50 units high.
- The four exposed long edges are parallel with slope `-2/5`, and the four end
  caps are vertical and 26 units high.
- The center is transparent negative space. Its seam is the parallelogram
  bounded by `(46,44)`, `(54,44)`, `(50,52)`, and `(42,52)`, with eight-unit
  horizontal edges.
- The paths do not overlap and require no mask, clip path, or reusable document
  ID. There are no floating center contours or additional interior points.
- The geometry remains recognizable with a single fill and at 16, 24, 30, 40,
  and 96 CSS pixels.

The top and bottom planes use `#ff334f` and `#a70e29` in the light theme, and
`#ff435c` and `#bd1230` in the dark theme. Normal navigation and body contexts
use no glow, photographic background, raster gradient, or generated mockup
shadow. Restrained translucent or oversized treatments are limited to the
homepage hero. One reusable typed Scala renderer owns inline SVG lockups; an
independent favicon may duplicate the paths.

### Typography

Instrument Sans Variable is used for interface and prose at weights `400 700`.
JetBrains Mono Variable is used for code, source labels, runtime records, and
compact technical metadata at weights `100 800`. The visible wordmark uses
Instrument Sans at approximately weight `700` with tight optical tracking.

Only normal Latin variable WOFF2 files are bundled. Font declarations use
`font-display: swap`, retain usable system fallbacks, and do not synthesize bold
or italic faces. Fonts and their licenses are served locally through the
digested asset pipeline.

### Color Tokens

The light theme starts from this palette:

```css
--docs-bg: #f4f1eb;
--docs-surface: #ffffff;
--docs-surface-raised: #ffffff;
--docs-surface-subtle: #ece8e0;
--docs-text: #171719;
--docs-muted: #67645f;
--docs-border: #d8d2c7;
--docs-border-strong: #aca69c;
--docs-accent: #ee2946;
--docs-accent-strong: #9d1029;
--docs-action-bg: #9d1029;
--docs-action-text: #ffffff;
--docs-focus: #2a63d4;
--docs-code-bg: #15161a;
--docs-code-text: #efeee8;
--docs-trace-browser: #2a63d4;
--docs-trace-server: #ee2946;
```

The dark theme starts from this palette:

```css
--docs-bg: #111114;
--docs-surface: #19191d;
--docs-surface-raised: #1d1d21;
--docs-surface-subtle: #202025;
--docs-text: #f1efe9;
--docs-muted: #aaa7a1;
--docs-border: #34343a;
--docs-border-strong: #55555d;
--docs-accent: #ff3a55;
--docs-accent-strong: #ff8292;
--docs-action-bg: #ff4a62;
--docs-action-text: #111114;
--docs-focus: #77a5ff;
--docs-code-bg: #090a0d;
--docs-code-text: #f1f0ec;
--docs-trace-browser: #79a8ff;
--docs-trace-server: #ff526b;
```

Small token adjustments require a demonstrated contrast or legibility need.
Expressive accent colors and accessible action color pairs remain separate;
small white text is not placed on signal red when contrast is insufficient.
Connected, reconnecting, offline, warning, and error states remain semantic
tokens. Connection and trace meaning is also communicated through labels and
structure rather than color alone.

### Components And Enhancement

Prose, code, callouts, search, API reference, examples, and X-ray traces share
one editorial component language. The reading measure stays near 48 rem. Tables,
source code, API signatures, compiler diagnostics, examples, and traces contain
their own overflow rather than widening the document.

Code blocks provide progressively enhanced copy controls. Long source-backed
blocks may become collapsible only after JavaScript enhancement; without
JavaScript, their complete source remains visible. Expansion preserves one code
node and reports state through `aria-expanded`. Copy status is announced without
moving focus, and reduced-motion preferences apply to presentation changes.

The desktop shell retains left section navigation, central content, and a right
outline for generated documentation pages. The homepage and search use the wide
shell. One native disclosure owns navigation and action controls on mobile so
IDs and hooks are not duplicated. Internal navigation remains ordinary links,
and examples remain visible but non-interactive while disconnected.

The site uses the existing utility-CSS asset pipeline with project-specific
design tokens, typography, and documentation components. It supports light and
dark themes, defaults to the operating-system preference, and remembers an
explicit visitor override. The stored preference is applied before first paint
when storage is available; storage failures fall back to the system theme.

Accessibility is a best-effort quality requirement rather than a formal
conformance claim. Semantic structure, keyboard operation, visible focus,
contrast, reduced-motion preferences, and understandable live-region updates
are expected throughout.

Production pages are self-contained. Required scripts, styles, fonts, and media
are served by the documentation application rather than third-party CDNs or
embedded services.

## High-Level Architecture

The site is a Mill module in the Scalive repository and is itself a Scalive
application. Its main boundaries are:

- a build-time content pipeline for Markdown, directives, source extraction,
  generated API material, validation, and search indexing;
- server-rendered documentation routes that provide readable initial HTML;
- documentation-owned LiveViews and components for examples and X-ray traces;
- a shared shell for navigation, search, themes, metadata, and connection state;
  and
- minimal framework-compatible JavaScript hooks for browser-only concerns such
  as instant search, source presentation, and client-side trace correlation.

The site does not introduce Scala.js or a separate frontend component framework.
Detailed module types, directive grammar, routes, build tasks, and tracing
interfaces belong in the later implementation plan.

## Operations And Quality

The public site supports current Chrome, Firefox, Safari, and Edge on desktop and
mobile. Public pages provide indexable server-rendered HTML, canonical metadata,
and a sitemap, including generated API pages.

The reading experience and initial HTML must meet Core Web Vitals. Live
connection establishment and enhanced interactions must not delay access to
page content.

Verification includes:

- compilation and behavior tests for every executable example;
- build validation for expected compilation failures;
- automated browser tests for critical journeys, including the quick start,
  navigation, search, examples, X-ray mode, reconnect behavior, themes, and
  responsive layouts; and
- maintainer review of content quality, visual coherence, and comprehensive
  public-feature coverage.

Screenshot regression tests are not required.

The site collects no usage analytics. It has no offline or installable PWA mode.
Only essential application state and the visitor's local theme preference may be
retained.

## Deployment Documentation

Deployment guidance is provider-neutral and assumes readers will adapt it to
their own hosting environment. It fully documents a single runnable JVM
application behind TLS and a WebSocket-capable reverse proxy.

The operations material includes:

- building and running the JVM artifact;
- static asset serving and cache policy;
- WebSocket forwarding and proxy headers;
- ports, environment variables, cookie security, logging, and graceful shutdown;
- troubleshooting connection and asset failures; and
- the additional state, routing, and coordination concerns introduced by
  horizontal scaling, without presenting a complete clustered deployment recipe.

A dedicated configuration reference defines these operational expectations as
an explicit contract.

## Non-Goals

The initial site does not provide:

- arbitrary editable Scala compilation or execution;
- visitor accounts, durable example state, or cross-visitor mutable demos;
- comprehensive ZIO instruction;
- inline Phoenix comparisons throughout every page;
- versioned documentation during alpha;
- translations or localization infrastructure;
- analytics, offline browsing, or PWA installation;
- public internal design and implementation-plan archives;
- formal accessibility certification;
- screenshot regression testing; or
- a publication workflow or provider-specific hosting recipe.

Publication workflow and detailed technical implementation are intentionally
deferred to separate decisions and an implementation plan.
