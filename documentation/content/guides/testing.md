{%
title = "Testing LiveViews"
description = "Test disconnected HTML today, and choose honest strategies for connected and browser behavior."
order = 60
section = guides
group = "Testing and troubleshooting"
%}

## Prerequisites {#prerequisites}

Assemble the application routes under test and identify whether the expected
behavior belongs to the initial HTTP render or the connected socket.

## Choose The Test Boundary {#choose-the-test-boundary}

Scalive applications have three useful test boundaries:

- **Disconnected tests** execute the finalized ZIO HTTP routes and inspect the
  first HTML response. This boundary has public support in `scalive.testing`.
- **Connected tests** join a @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ channel, send protocol events, and inspect
  committed HTML or diffs. Scalive does not currently publish a connected test
  harness.
- **Browser tests** run the Phoenix LiveView JavaScript client against a real
  server. Scalive does not currently publish a browser fixture or Playwright
  library.

Use the narrowest boundary that proves the behavior. Keep most rendering,
routing, form, cookie, and initial lifecycle assertions disconnected. Use a real
browser when the claim depends on DOM patching, JavaScript hooks, focus, uploads,
navigation, transport loss, or reconnect behavior.

@:callout(info)

`scalive.testing` is intentionally smaller than `Phoenix.LiveViewTest`. Connected
mount, typed event submission, and following an ordinary HTTP form action are
current testing API gaps, not undocumented helpers.

@:@

## Test Disconnected Rendering {#test-disconnected-rendering}

Add the `scalive-testing` module to the test module that already depends on your
application. Inside this repository, Mill modules use `moduleDeps = Seq(...,
scaliveTesting)`. There is not yet a verified public dependency coordinate, so a
consumer outside this checkout must use the dependency arrangement appropriate
to its Scalive build.

The @:apiSymbol(object:scalive.testing.DisconnectedRender)`DisconnectedRender.run`@:@ method accepts
finalized `Routes` and a ZIO HTTP `Request`. It runs the route, consumes the
response body once, restores a replayable body, and parses the HTML with jsoup:

The example uses `ZIOSpecDefault` for the test runtime, `suite` to group tests,
and `test` for an effectful assertion. `orDieWith` turns an unexpected typed
failure into a test defect with a useful assertion error.

```scala
import zio.*
import zio.http.*
import zio.test.*

import scalive.*
import scalive.testing.*

object ProfilePageSpec extends ZIOSpecDefault:
  private val routes =
    Live.router.withSecurity(testSecurity)(Routes.profile -> ProfileLiveView())

  def spec = suite("ProfilePageSpec")(
    test("renders the profile form") {
      for
        page <- DisconnectedRender.run(routes, Request.get(URL.root))
        profileForm <- ZIO
                         .fromEither(
                           page.form(
                             FormQuery(
                               action = Some("/profiles"),
                               method = Some(Method.POST)
                             )
                           )
                         )
                         .orDieWith(error => new AssertionError(error.toString))
      yield assertTrue(
        page.response.status == Status.Ok,
        page.text.contains("Profile"),
        profileForm.hasSubmitBinding,
        profileForm.values(FormPath("profile", "name")) == Vector("Alice")
      )
    }
  )
end ProfilePageSpec
```

The names `testSecurity`, `Routes.profile`, and `ProfileLiveView` represent
application code. Use a fixed test @:apiSymbol(class:scalive.TokenConfig)`TokenConfig`@:@ when assertions depend on
signed cookies or tokens; do not compare output produced with independently
constructed security configurations.

## Query Forms Semantically {#query-forms-semantically}

The @:apiSymbol(class:scalive.testing.RenderedPage)`RenderedPage`@:@ exposes the status and
headers through @:apiSymbol(val:scalive.testing.RenderedPage.response)`response`@:@, the exact body through
@:apiSymbol(val:scalive.testing.RenderedPage.html)`html`@:@, normalized document
text through @:apiSymbol(def:scalive.testing.RenderedPage.text)`text`@:@, and all forms through
@:apiSymbol(def:scalive.testing.RenderedPage.forms)`forms`@:@.
@:apiSymbol(def:scalive.testing.RenderedPage.form)`form`@:@ succeeds only when
exactly one form matches its optional action and method filters. Handle
`NotFound` and `MultipleMatches` instead of silently selecting the first form.

The @:apiSymbol(class:scalive.testing.RenderedForm)`RenderedForm`@:@ exposes its
@:apiSymbol(def:scalive.testing.RenderedForm.id)`id`@:@,
@:apiSymbol(def:scalive.testing.RenderedForm.action)`action`@:@,
@:apiSymbol(def:scalive.testing.RenderedForm.method)`method`@:@,
@:apiSymbol(def:scalive.testing.RenderedForm.fields)`fields`@:@, and
@:apiSymbol(def:scalive.testing.RenderedForm.values)`values`@:@. It also reports
@:apiSymbol(def:scalive.testing.RenderedForm.hasChangeBinding)`phx-change`@:@,
@:apiSymbol(def:scalive.testing.RenderedForm.hasSubmitBinding)`phx-submit`@:@, and
@:apiSymbol(def:scalive.testing.RenderedForm.triggersAction)`phx-trigger-action`@:@ presence.
@:apiSymbol(class:scalive.testing.RenderedField)`RenderedField`@:@ exposes tag
name, id, name, value, input type, and required state. These are HTML queries;
they do not submit the form or dispatch a @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ event.

Use @:apiSymbol(class:scalive.FormPath)`FormPath`@:@ for generated nested names when the application uses a
@:apiSymbol(trait:scalive.FormCodec)`FormCodec`@:@. Values remain a `Vector` because repeated names, such as checkbox
groups, are valid.

## Cover Connected Behavior {#cover-connected-behavior}

There is no public `scalive.testing` API for joining a socket or clicking a
connected binding. Until one exists, choose between these approaches:

1. Test pure model transitions and codecs directly when no connection capability
   is involved.
2. Test the disconnected route for initial mount, route decoding, layout, forms,
   cookies, and HTML.
3. Test connected behavior in a browser when protocol or DOM behavior is part of
   the requirement.
4. Build an application-owned integration harness only if browser tests are too
   coarse for an important server-side scenario. Treat it as private code tied
   to the current protocol, not as a Scalive compatibility promise.

This repository's
`documentation/site/test/src/scalive/docs/SiteLiveViewHarness.scala` is an
example of the fourth approach. It directly uses internal channel, socket, and
wire-message types to join, click, and inspect committed HTML. It is
`private[docs]`, lives under test sources, and is not part of `scalive.testing`.
Applications cannot rely on its API or copy it with an expectation of source
compatibility.

## Test In A Browser {#test-in-a-browser}

Run the same production-shaped server, asset bundle, root layout, security
configuration, and socket path that users will receive. A browser smoke suite
should prove at least:

- the disconnected document contains meaningful content before the socket joins;
- the LiveSocket reaches the connected state;
- one event updates the existing DOM;
- live patch or navigation preserves the expected URL and title;
- hooks and uploads work in a real browser when the application uses them; and
- a deliberately interrupted WebSocket exercises the application's reconnect
  expectations.

The Scalive repository runs the upstream Phoenix LiveView `v1.1.28` Playwright
suite against `e2eApp` with:

```bash
./scripts/e2e-run-upstream.sh
```

That script, its `test/playwright.upstream.config.js`, and the repository's site
Playwright suites are project regression infrastructure. They are evidence for
the compatibility matrix, not a distributed browser-testing API for Scalive
applications. Application teams should own selectors, fixtures, server startup,
and assertions for their product.

## Know What Each Test Proves {#know-what-each-test-proves}

A passing disconnected test does not prove a socket can connect. A private
protocol harness does not prove the Phoenix client can patch the browser DOM. A
browser test proves its scenario but may not isolate the failing lifecycle
stage. Keep at least one assertion at each boundary your application depends on,
and use [Troubleshooting](troubleshooting.md#separate-the-two-mounts) to locate a
failure before expanding the test suite.

## Related Tasks {#related-tasks}

- Build the production-shaped browser bundle with [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Locate the failing lifecycle stage with [Troubleshooting](troubleshooting.md#prerequisites).
- Supply deterministic dependencies with [Services and dependency injection](services-and-zlayer-injection.md#supply-a-test-implementation).
