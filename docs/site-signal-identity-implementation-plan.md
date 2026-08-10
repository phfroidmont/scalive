# Scalive Signal Identity Implementation Plan

**Status:** Approved for implementation on 2026-08-06.

**Selected direction:** Signal / Editorial, proposal 01.

**Goal:** Replace the documentation site's generic visual shell with a complete,
production-quality Scalive identity while preserving its server-rendered content,
LiveView behavior, accessibility foundations, generated-content architecture, and
runtime X-ray functionality.

**Primary implementation areas:**

- `documentation/site/src/scalive/docs/DocumentationLayouts.scala`
- `documentation/site/src/scalive/docs/DocumentationApplication.scala`
- `documentation/site/src/scalive/docs/DocumentationHome.scala`
- `documentation/site/src/scalive/docs/DocumentationRenderer.scala`
- `documentation/site/assets/css/app.css`
- `documentation/site/assets/js/app.js`
- `documentation/content/index.md`
- `documentation/site/package.json`
- `documentation/site/package-lock.json`
- `documentation/package.mill`
- `documentation/site/src/scalive/docs/DocumentationSite.scala`
- documentation pipeline and site tests
- `docs/site-specification.md`
- `docs/site-implementation-plan.md`

The temporary comparison gallery currently lives outside the repository at
`/tmp/opencode/scalive-identity-gallery` and is served at
`http://127.0.0.1:8090/`. It is a visual reference only. Do not copy its markup
wholesale or make the production site depend on it. The decisions in this plan
are authoritative if the gallery is no longer available.

## Approved Decisions

- [x] Implement the complete Signal / Editorial system, not only the header and
  homepage.
- [x] Use a lowercase `scalive` visual wordmark.
- [x] Continue using proper-case “Scalive” in prose, metadata, page titles, and
  accessible labels.
- [x] Preserve and refine the two-plane `S` mark with its central live-signal
  seam.
- [x] Use Instrument Sans for interface and prose typography.
- [x] Use JetBrains Mono for code, source labels, runtime records, and compact
  technical metadata.
- [x] Bundle all fonts and brand assets locally. Production must make no
  third-party runtime requests.
- [x] Keep light, dark, and system theme behavior.
- [x] Keep the current typed content model. Do not add a homepage-only public
  content type or Markdown directive.
- [x] Render `/` through a dedicated custom LiveView backed by validated authored
  homepage content.
- [x] Preserve existing runtime and test selectors while adding presentation
  hooks.

## Visual Contract

### Character

The site is a precise technical publication interrupted by one unmistakable
live signal. It should feel direct, assured, contemporary, and specific to
Scalive. Red indicates identity, action, or a meaningful live transition; it is
not general decoration.

The selected direction evolves the earlier “living technical notebook” brief
into an editorial technical publication. Source code, trace records, diagrams,
and runtime evidence still sit alongside prose, but the visual language is
cleaner and more graphic than a literal paper notebook.

### Mark

Use a `96 96` view box and the following normalized two-plane geometry:

```svg
<svg viewBox="0 0 96 96" aria-hidden="true">
  <path d="M12 52V28L84 4V28L36 44H48L40 52Z" />
  <path d="M56 44H84V68L12 92V68L60 52H48Z" />
</svg>
```

Construction invariants:

- Each ribbon is one connected, seven-vertex, non-self-intersecting polygon.
- The bottom ribbon is the exact 180-degree rotation of the top ribbon around
  `(48,48)`.
- All four exposed long edges are parallel with slope `-1/3`.
- All four end caps are vertical and 24 view-box units high.
- The center is transparent negative space, not a positive lightning fragment.
- The center seam is the parallelogram bounded by `(48,44)`, `(56,44)`,
  `(48,52)`, and `(40,52)`, centered on `(48,48)`.
- The seam remains eight view-box units wide along both horizontal edges.
- The ribbon paths do not overlap and require no SVG mask, clip-path, or reusable
  document ID.
- There are no floating center contours or additional interior zigzag points.
- The geometry must remain recognizable when both paths use the same fill.

Production treatments:

- Light top plane: `#ff334f`
- Light bottom plane: `#a70e29`
- Dark top plane: `#ff435c`
- Dark bottom plane: `#bd1230`
- No glow in normal navigation or body contexts.
- No photographic background, raster gradient, or generated mockup shadow.
- A restrained translucent or oversized treatment is allowed only in the
  homepage hero.
- The one-color mark must remain usable for print-like or constrained contexts.
- Verify legibility at 16, 24, 30, 40, and 96 CSS pixels.

Create one reusable Scala brand renderer for inline SVG lockups. Do not duplicate
the path data in every layout. The standalone favicon SVG may duplicate the path
because it is an independent static artifact.

### Wordmark

- Render the visible wordmark as lowercase `scalive`.
- Use Instrument Sans at approximately `700` weight with tight optical tracking.
- Give the home link an accessible name such as `Scalive home`; do not expose the
  decorative mark separately to assistive technology.
- Use proper-case “Scalive” everywhere the name occurs in prose or metadata.

### Typography

- Interface and prose: Instrument Sans Variable, normal style, weight range
  `400 700`.
- Code and technical metadata: JetBrains Mono Variable, normal style, weight
  range `100 800`.
- Fallbacks must remain usable while fonts load.
- Use `font-display: swap`.
- Bundle only the normal Latin variable WOFF2 files in this pass. Do not bundle
  unused italic, Cyrillic, Greek, or Vietnamese files.
- Do not synthesize bold or italic faces.

Font package versions used by the approved gallery:

```json
{
  "@fontsource-variable/instrument-sans": "5.2.8",
  "@fontsource-variable/jetbrains-mono": "5.2.8"
}
```

### Core Tokens

Use the following palette as the starting contract. Small adjustments are
allowed only when browser verification demonstrates a concrete contrast or
legibility issue.

Light theme:

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

Dark theme:

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

The expressive accent and accessible action pair are deliberately separate.
Do not place small white text directly on vivid signal red when contrast is
insufficient. The approved action pairs exceed a 4.5:1 contrast ratio.

Retain semantic connected, reconnecting, offline, warning, and error tokens.
Connection and trace meaning must remain understandable through labels and
structure, not color alone.

## Architectural Constraints

- Keep public content under `documentation/content`.
- Keep Laika and TASTy dependencies out of the deployed site classpath.
- Render all authored content through typed Scalive nodes.
- Do not introduce `rawHtml` for the logo, homepage, code controls, or icons.
- Do not change runtime tracing, serializer, nested LiveView, or protocol
  behavior as part of this work.
- Do not add a frontend framework or component library.
- DaisyUI is already absent from the documentation package. Do not add it.
- Keep the current ordinary-link fallbacks for all internal navigation.
- Keep examples visible but non-interactive while disconnected.
- Keep all required scripts, fonts, icons, and media local.
- Do not implement Phase 8 documentation content beyond the homepage material
  needed for this visual pass.
- Keep homepage copy and links canonical in `documentation/content/index.md`;
  do not duplicate them as Scala string constants.
- Do not publish the illustrative prototype command `mill scalive.quickstart`.
  It does not exist. Use only verified commands; otherwise omit an install-command
  pill until the real quick start is written.

## Stable DOM And Behavior Contracts

Preserve these IDs, classes, or data attributes unless the associated tests are
deliberately updated without weakening behavior coverage:

- `#docs-main`
- `#docs-page-metadata`
- `#docs-global-search`
- `#docs-global-search-input`
- `#docs-global-search-results`
- `#docs-connection-status`
- `#docs-theme-selector`
- `.docs-content`
- `.docs-prose`
- `.docs-example`
- `.docs-code-block`
- `.docs-code`
- `.docs-compiler-diagnostic`
- `[data-example]`
- `[data-example-controls]`
- `[data-example-disconnected]`
- `[data-disabled-by-connection]`
- `[data-xray-*]`
- `[data-xray-stage]`
- `[data-connection-state]`
- `[data-theme]`

Add dedicated styling classes rather than styling primarily through runtime
protocol attributes.

## Asset Build Design

Keep the runtime asset list explicit and deterministic.

### Font CSS

Add the two Fontsource packages as development dependencies. Add a small Node
build script under `documentation/site/assets` that:

1. Reads these files from `node_modules`:

```text
@fontsource-variable/instrument-sans/files/instrument-sans-latin-wght-normal.woff2
@fontsource-variable/jetbrains-mono/files/jetbrains-mono-latin-wght-normal.woff2
```

2. Generates `dist/fonts.css` with two `@font-face` declarations and local
   base64 data URLs.
3. Uses `font-display: swap` and the Latin Unicode range from the Fontsource
   packages.
4. Copies each package's `LICENSE` file to deterministic top-level outputs:

```text
dist/instrument-sans-OFL.txt
dist/jetbrains-mono-OFL.txt
```

Inlining approximately 70 KB of WOFF2 source into a separately digested
`fonts.css` is intentional. It avoids adding undigested nested font URLs to the
current static-asset model while keeping the primary application CSS smaller.

### Brand Assets

Check in the standalone SVG source under
`documentation/site/assets/brand/scalive-mark.svg`. The Node build script should
copy it to `dist/favicon.svg`.

Add these output names to `documentation.site.bundleOutputs` in
`documentation/package.mill`:

```text
app.js
app.css
fonts.css
favicon.svg
instrument-sans-OFL.txt
jetbrains-mono-OFL.txt
```

Add the same runtime assets to `DocumentationSite.scala` and relevant test asset
lists. Load `fonts.css` before `app.css`. Add the digested favicon path to the
root layout.

No change to the shared `NpmAssets` implementation should be necessary if all
new outputs remain top-level files. Prefer this over expanding shared build
behavior for one site.

## Homepage Content Contract

Keep the standard `Page` and `Block` model. The homepage remains authored in
`documentation/content/index.md`.

Use this information architecture:

1. Metadata title and rendered H1: `Live interfaces. Typed end to end.`
2. Existing factual description of Scalive as a Scala 3 re-implementation of
   Phoenix LiveView.
3. Ordinary internal links to Learn and API, styled as primary and secondary
   actions.
4. One compact, accurate Scala code block. Do not invent framework APIs or build
   commands.
5. One `@:example(counter)` reference rendered as a compact real nested LiveView
   inside the homepage hero rather than as the full documentation example card.
6. A three-item principle list covering typed state, Scala HTML rendering, and
   live diffs/effects.
7. `Why Scalive` with the existing stable `#why-scalive` anchor.
8. The current alpha-software warning as an info callout.

The browser title for `/` remains `Scalive`, as it does today. The visual H1 may
use the marketing headline.

Use ordinary Markdown blocks for all copy, links, lists, code, callouts, and the
existing example directive. Do not add a homepage-only directive or hardcode the
body copy in Scala.

The homepage must use the wide shell without left section navigation or the
right in-page outline. Search keeps its existing wide results shell because it
has no generated `Page`, section navigation, or in-page outline. Generated
content, example, API, and project pages retain the desktop three-column
documentation shell.

### Custom Homepage View Architecture

Add `DocumentationHome.scala` containing two package-private types:

```scala
final private[docs] case class HomePageContent(/* extracted typed sections */)

final private[docs] class DocumentationHomeLiveView(
  page: Page,
  content: HomePageContent,
  application: DocumentationApplication,
  renderer: DocumentationRenderer
) extends LiveView.Eventless[Unit]
```

Names may vary if a smaller implementation is clearer, but preserve these
boundaries:

1. `HomePageContent.from(page)` returns `Either[String, HomePageContent]`.
2. It requires route `/`, section `Home`, and the expected authored block shape.
3. It extracts the introduction, CTA paragraph, code preview, `counter` example
   reference, three principles, `#why-scalive` section, and alpha callout.
4. It rejects missing, duplicated, reordered, or wrong-kind homepage blocks with
   a source-oriented error rather than failing later during render.
5. `DocumentationApplication.from` validates and stores the extracted homepage
   content during startup.
6. `DocumentationApplication.routes` routes `/` to
   `DocumentationHomeLiveView`; it excludes `/` from the generic
   `DocumentationPageLiveView` fragments.
7. Every other generated page continues through `DocumentationPageLiveView` and
   `DocumentationRenderer` unchanged.
8. The custom view reuses package-private inline, block, code, callout, and page
   link helpers from `DocumentationRenderer`. Do not duplicate link resolution,
   syntax token rendering, source-link generation, or issue-link generation.
9. The custom view renders the decorative mark, editorial hero, code panel,
   compact counter, principle strip, project statement, alpha note, and standard
   page links as explicit typed Scalive markup.
10. The compact counter uses `ExampleRegistry`, its route-scoped instance ID
    helpers, and a fresh non-sticky nested LiveView. Do not create separate
    counter behavior for the homepage.

Keeping the generated `Page` means the homepage remains covered by content
validation, internal-link resolution, search generation, navigation metadata,
canonical metadata, sitemap generation, and source-edit links. The dedicated
view changes presentation only.

## Implementation Sequence

Follow test-first development for markup, browser behavior, asset handling, and
responsive behavior changes.

### Phase 0: Baseline And Failing Tests

- [x] Inspect `git status`, existing diffs, and the running site. Do not revert
  unrelated work.
- [x] Record the current results of `documentation.pipeline.test`,
  `documentation.site.test`, JavaScript tests, X-ray browser test, and site
  bundle.
- [x] Extend `DocumentationApplicationSpec` with failing assertions for the
  lowercase visual lockup, accessible home label, favicon, font stylesheet,
  wide homepage shell, and preserved documentation sidebars.
- [x] Add focused `HomePageContent` tests for the valid block contract and each
  malformed-content failure.
- [x] Add application validation tests proving startup requires exactly one
  valid generated homepage.
- [x] Add disconnected and connected homepage tests for the custom hierarchy,
  decorative mark, ordinary CTA links, compact nested counter, page links, and
  code toolbar markup.
- [x] Extend `ClasspathResourcesSpec` with failing assertions for one copy of
  every new generated static asset.
- [x] Add browser tests for the mobile navigation disclosure, explicit theme
  persistence, code copying, code expansion, and local overflow containment.
- [x] Keep the existing counter X-ray journey unchanged as regression coverage.

Completion gate:

- [x] The intended new behavior is represented by failing tests before
  production implementation begins.

### Phase 1: Brand And Asset Pipeline

- [x] Add the normalized SVG brand source.
- [x] Add a package-private `DocumentationBrand` renderer using typed SVG nodes.
- [x] Render separate top and bottom paths so themes can color each plane.
- [x] Preserve the exact two-path center construction and eight-unit negative
  seam; do not reintroduce the discarded positive center fragments.
- [x] Add a geometry regression assertion for the two approved path strings.
- [x] Add Fontsource dependencies and update the npm lockfile with `npm install`.
- [x] Add the deterministic font/brand build script.
- [x] Add `fonts.css`, favicon, and font-license outputs to `bundleOutputs`.
- [x] Add all new files to the explicit runtime asset manifest.
- [x] Add the favicon link and font stylesheet to the root layout.
- [x] Verify the resulting browser makes no font, icon, or stylesheet request to
  a third-party origin.

Completion gate:

- [x] The bundle contains one digested local font stylesheet, one favicon, both
  OFL notices, and the existing application/search assets without duplicate
  classpath paths.

### Phase 2: Theme Foundation And Application Shell

- [x] Replace the current palette with the approved Signal tokens.
- [x] Add a tiny head bootstrap that reads `scalive.docs.theme` before first
  paint and applies only `light`, `dark`, or the system default.
- [x] Keep the existing `ThemeSelector` hook synchronized with the bootstrap.
- [x] Ensure storage failures still leave a working system theme.
- [x] Replace the text-only brand with the inline mark and lowercase wordmark.
- [x] Restructure the header so one native `details` disclosure owns the primary
  navigation and existing action controls on mobile.
- [x] Hide the disclosure summary and present the same controls inline on
  desktop. Do not duplicate search IDs or hooks.
- [x] Style search, connection state, theme control, footer, and skip link.
- [x] Keep the header compact and editorial; do not reproduce the prototype's
  gallery chrome.
- [x] Preserve ordinary links and keyboard operation without JavaScript.

Completion gate:

- [x] The disconnected shell has the selected brand, flash-free theme, ordinary
  links, keyboard-accessible mobile navigation, and no duplicate controls.

### Phase 3: Homepage

- [x] Rewrite `documentation/content/index.md` to the approved content contract.
- [x] Preserve the `#why-scalive` anchor and meaningful no-JavaScript content.
- [x] Add `HomePageContent.from` and validate the authored homepage shape during
  `DocumentationApplication.from`.
- [x] Route `/` to `DocumentationHomeLiveView` and keep all other pages on the
  generic page LiveView.
- [x] Reuse package-private content-rendering helpers rather than duplicating
  Markdown, code, link, callout, or page-link behavior.
- [x] Make `DocumentationLayout` use the wide shell and omit sidebars for the
  home section only.
- [x] Implement the asymmetric editorial hero, code preview, CTA treatment,
  principle strip, project statement, and alpha note.
- [x] Render the existing registered counter as a compact non-sticky nested
  LiveView in the hero, with route-scoped identity and disconnected behavior.
- [x] Make the hero a single-column reading order on mobile.
- [x] Ensure the DOM order remains logical without CSS.
- [x] Ensure the page does not claim an unavailable quick-start command.

Completion gate:

- [x] `/` communicates the framework proposition, offers dependable Learn/API
  paths, demonstrates one real live interaction, and remains fully readable
  without JavaScript or loaded fonts.

### Phase 4: Documentation Components

- [x] Restyle heading hierarchy, paragraphs, links, lists, rules, blockquotes,
  and inline code.
- [x] Style the Laika syntax token classes found in generated content rather
  than guessing a separate highlighter vocabulary.
- [x] Add code header, language, source, copy, and expansion controls while
  preserving `.docs-code-block`, `.docs-code`, and source links.
- [x] Differentiate info, tip, warning, and error callouts through labels,
  borders, and icons as well as color.
- [x] Style tables with local horizontal scrolling.
- [x] Style API symbols, kinds, signatures, exported/inherited labels, and source
  links.
- [x] Style server-rendered and instant search results consistently.
- [x] Style page edit/report links and revision metadata.
- [x] Keep the reading measure near 48 rem and avoid oversized prose text.

Completion gate:

- [x] Representative Learn, API, Search, and Project pages share one coherent
  editorial system and remain usable with JavaScript disabled.

### Phase 5: Examples And Runtime X-Ray

- [x] Apply the Signal component treatment to example framing and controls.
- [x] Keep disconnected messaging visibly textual and not color-only.
- [x] Give browser and server X-ray lanes distinct structural treatments.
- [x] Use red for server/live transitions and blue for browser-side observation.
- [x] Preserve stage, operation, topic, epoch, and message-reference attributes.
- [x] Preserve all enable, clear, reconnect, bounded-history, and sanitization
  behavior.
- [x] Verify the compact homepage counter and full Examples-page counter have
  independent route-scoped instances and reset behavior.
- [x] Ensure long trace values wrap or scroll locally without widening the page.
- [x] Ensure long source and compiler diagnostic blocks scroll locally.

Completion gate:

- [x] The counter interaction and X-ray journey remain behaviorally identical
  while visually matching the selected identity.

### Phase 6: Code Enhancements

- [x] Add a progressively enhanced copy button to every rendered code block.
- [x] Copy exactly the code text, not line labels, source captions, or hidden
  status text.
- [x] Announce copy success or failure without moving focus.
- [x] Keep code fully visible when JavaScript is unavailable.
- [x] Add collapsible presentation only to long source-backed blocks.
- [x] Apply collapse only after JavaScript enhancement so no-JavaScript readers
  receive the complete source.
- [x] Toggle `aria-expanded`, preserve the same code node, and avoid duplicating
  source in the DOM.
- [x] Respect reduced-motion preferences when changing max height or affordance
  state.

Suggested collapse condition:

```text
more than 24 lines OR more than 1,600 characters
```

Completion gate:

- [x] Copy and expansion pass focused browser tests, keyboard use, and
  no-JavaScript fallback review.

### Phase 7: Responsive And Accessibility Review

- [x] Verify desktop at approximately 1440 × 1000.
- [x] Verify mobile at approximately 390 × 844.
- [x] Verify both dimensions in explicit light and dark themes.
- [x] Verify system theme and persisted overrides.
- [x] Verify visible keyboard focus through the complete header, navigation,
  search, code controls, examples, X-ray controls, and footer.
- [x] Verify heading order and landmark names.
- [ ] Verify the mark and wordmark at small sizes.
- [x] Verify tables, source, API signatures, examples, and X-ray records do not
  create document-level overflow.
- [x] Verify reduced motion.
- [x] Verify connection and trace meaning remains understandable without color.
- [x] Verify browser console output is clean.
- [x] Verify no third-party runtime requests.
- [ ] Perform a manual screen-reader announcement review before checking that
  item in the main implementation plan.

Completion gate:

- [ ] Homepage, documentation, API, search, example, and X-ray views pass visual
  and accessibility review in representative desktop and mobile states.

### Phase 8: Documentation And Cleanup

- [x] Update `docs/site-specification.md` to record Signal / Editorial as the
  selected interpretation of the technical-publication direction.
- [x] Update Phase 9 in `docs/site-implementation-plan.md` to name the selected
  identity.
- [x] Mark DaisyUI removal complete with a note that the documentation package
  was already DaisyUI-free before this implementation.
- [x] Mark only tasks actually completed and verified.
- [x] Do not claim formal accessibility conformance.
- [ ] Stop only the temporary gallery server on port 8090. Do not stop the user's
  documentation server on port 8080.
- [ ] Remove `/tmp/opencode/scalive-identity-gallery` after final visual approval.
- [x] Confirm the repository contains no Playwright screenshots or temporary
  snapshot metadata.

Completion gate:

- [ ] The active specification and checklist reflect the chosen identity, the
  temporary gallery is gone, and only intended repository changes remain.

## Expected File Changes

| File | Intended change |
| --- | --- |
| `documentation/site/src/scalive/docs/DocumentationBrand.scala` | New reusable typed SVG mark and lockup renderer |
| `documentation/site/src/scalive/docs/DocumentationLayouts.scala` | Favicon, font stylesheet, theme bootstrap, branded header, mobile disclosure, home-wide shell, footer |
| `documentation/site/src/scalive/docs/DocumentationApplication.scala` | Validate/extract the generated homepage and route `/` to its dedicated LiveView |
| `documentation/site/src/scalive/docs/DocumentationHome.scala` | Typed homepage content extractor and custom homepage LiveView |
| `documentation/site/src/scalive/docs/DocumentationRenderer.scala` | Package-private reusable content helpers, code toolbar, progressive expansion markup, component classes |
| `documentation/site/src/scalive/docs/DocumentationSite.scala` | Explicit new static asset names |
| `documentation/site/assets/brand/scalive-mark.svg` | Standalone normalized mark used for favicon generation |
| `documentation/site/assets/build-brand-assets.mjs` | Deterministic local font CSS, license, and favicon build |
| `documentation/site/assets/css/app.css` | Complete Signal design system and responsive behavior |
| `documentation/site/assets/js/app.js` | Theme synchronization, copy, expansion, and disclosure cleanup if needed |
| `documentation/site/package.json` | Fontsource dependencies and build command |
| `documentation/site/package-lock.json` | Locked font packages |
| `documentation/package.mill` | Documentation-site bundle outputs |
| `documentation/content/index.md` | Selected homepage content and structure |
| `documentation/site/test/src/scalive/docs/DocumentationApplicationSpec.scala` | Shell, custom homepage route, favicon, navigation, and code markup regression tests |
| `documentation/site/test/src/scalive/docs/DocumentationApplicationValidationSpec.scala` | Homepage structure and startup validation failures |
| `documentation/site/test/src/scalive/docs/DocumentationHomeSpec.scala` | Extracted content and custom disconnected/connected homepage behavior |
| `documentation/site/test/src/scalive/docs/ClasspathResourcesSpec.scala` | New asset uniqueness checks |
| `documentation/site/test/xray/site-shell.spec.js` | Theme, mobile navigation, copy, expansion, and overflow browser tests |
| `docs/site-specification.md` | Record selected visual identity |
| `docs/site-implementation-plan.md` | Update Phase 9 wording and completed checkboxes |

Use fewer files if the same behavior remains clearer. Do not add abstraction
layers solely to mirror the temporary gallery.

## Verification Commands

Run focused tests during implementation, then run the complete sequence:

```bash
mill --ticker false __.reformat + __.fix
mill --ticker false documentation.model.test
mill --ticker false documentation.pipeline.test
mill --ticker false documentation.pipeline.generate
mill --ticker false documentation.site.test
npm test --prefix documentation/site
npm run test:xray --prefix documentation/site
mill --ticker false documentation.site.bundle
mill --ticker false documentation.check
git diff --check
git status --short
```

Also inspect the production site manually with `mill documentation.site.run`.
Test representative pages at minimum:

```text
/
/learn
/examples
/api
/api/scalive/live-view
/search?q=scalive.LiveView
/project
```

## Final Acceptance Criteria

- [x] The visible brand uses the approved two-plane mark and lowercase
  `scalive` wordmark.
- [x] The mark consists of two connected simple ribbons with one continuous
  transparent center seam and no floating or overlapping center fragments.
- [x] Prose and metadata continue to use proper-case “Scalive.”
- [x] The homepage matches the Signal / Editorial direction and contains no
  invented command or API.
- [x] `/` is served by the dedicated custom homepage LiveView while all other
  generated pages retain the generic renderer.
- [x] Homepage copy remains authored and searchable from `index.md`; malformed
  homepage structure fails application startup clearly.
- [x] The compact homepage counter is a real isolated nested LiveView backed by
  the existing counter registry entry.
- [x] All public pages remain meaningful server-rendered HTML.
- [x] Fonts, favicon, scripts, styles, and media are served locally.
- [x] No DaisyUI or third-party runtime resource is present.
- [x] Explicit light, dark, and system themes work without first-paint flash.
- [x] Desktop and mobile navigation work with keyboard and ordinary links.
- [x] Code copy and expansion are progressively enhanced and tested.
- [x] Search, API, examples, disconnected state, and X-ray share one coherent
  component language.
- [x] Runtime tracing and nested example behavior are unchanged.
- [x] Representative desktop and mobile pages have no document-level overflow.
- [x] Required contrast, focus visibility, reduced motion, and textual status
  cues are preserved.
- [x] All listed Scala, JavaScript, browser, generation, and bundle checks pass.
- [x] The main specification and Phase 9 checklist reflect only verified work.
- [ ] The temporary gallery and all diagnostic artifacts are removed after
  approval.
