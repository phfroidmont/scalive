# Scalive Documentation Site Implementation Plan

[`site-specification.md`](site-specification.md) defines the documentation
site. This plan lists only the work that remains. The completed phases, which
built the content pipeline, generated API reference, application shell, search,
interactive examples, X-ray inspector, content, visual design, and the cutover
from the standalone examples application, are in Git history.

**Commands:**

```bash
mill --ticker false documentation.check
mill --ticker false documentation.checkExternalLinks
npm run test:xray --prefix documentation/site
```

Run the documentation site with `mill documentation.site.run`.

## Constraints

- Keep public content under `documentation/content` and internal specifications
  and plans under `docs`; the content pipeline never scans `docs`.
- Keep Laika and TASTy dependencies out of the deployed site classpath;
  `documentation.site.checkRuntimeClasspath` enforces this.
- Render Markdown through typed Scalive nodes. Reject raw HTML in Markdown and
  never render generated HTML through `rawHtml`.
- Embed every interactive example as a nested LiveView, and add no arbitrary
  code-execution service.
- Keep `e2eApp` as upstream parity infrastructure, not teaching material.
- Do not introduce publication-provider configuration or a deployment workflow.

## Content Coverage

- [ ] Add validation that maps every generated API group to Learn or guide
  coverage, or to an explicit reference-only classification. Every public
  definition already requires Scaladoc; this check covers the guide side of the
  specification's rule that guides and the API reference together cover every
  supported feature.

## Running The Site In Production

The public deployment and configuration guides are written. This work concerns
running the documentation site itself.

- [ ] Make the bind address configurable; the site binds to `127.0.0.1`. Add
  log level and shutdown timeout settings to `DocumentationConfig`.
- [ ] Add a readiness endpoint next to `/health`.
- [ ] Add structured startup and shutdown logs, and confirm that logs never
  contain cookies, tokens, CSRF values, or WebSocket query strings.
- [ ] Add appropriate security response headers.
- [ ] Document the site's environment variables (`SCALIVE_SERVER_PORT`,
  `SCALIVE_PUBLIC_ORIGIN`, and `SCALIVE_TOKEN_SECRET`, which a public origin
  requires) for whoever deploys it.
- [ ] Build one self-contained JVM artifact and smoke-test it with only a JRE
  and explicit environment variables, which also confirms that Node and npm are
  build-time-only dependencies.
- [ ] Exercise shutdown with active sockets, subscriptions, async tasks, and
  uploads, and confirm that example resources and temporary upload content are
  released.

## Browser And Performance Verification

The Playwright suite in `documentation/site/test/xray` runs in Chromium and
covers the site shell, themes, inline API references, diagrams, the example
catalog, X-ray traces, forms, browser integration, and one authentication case.

- [ ] Run the suite in Firefox and WebKit as well.
- [ ] Cover the journeys it lacks: the quick start page, instant and
  server-rendered search, reduced motion, disconnect and reconnect with frozen
  examples and remount resets, uploads (limits, summary, disposal, and
  cross-visitor isolation), the full authentication flow (login, protected
  route, logout, expiry, and rate limits), and representative pages with
  JavaScript disabled.
- [ ] Add a repeatable Lighthouse check against a production build of the
  homepage, a guide, an example, and an API page, and review the Core Web Vitals
  results.

## Maintainer Review

- [ ] Review keyboard use, contrast, semantic headings, and screen-reader
  announcements by hand, without claiming formal conformance.
- [ ] Review representative Learn, Guide, Example, API, Search, and Project
  pages for visual quality and accessibility on desktop and mobile.
- [ ] Review the finished site against `site-specification.md`.
