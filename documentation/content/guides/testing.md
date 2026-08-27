{%
title = "Testing LiveViews"
description = "Test disconnected HTML, connected server behavior, and the real browser at the appropriate boundary."
order = 60
section = guides
group = "Testing and troubleshooting"
%}

## Before You Start {#prerequisites}

Start with one observable behavior or incident to reproduce, the application
routes that expose it, and a decision about whether it belongs to initial HTTP
rendering, the connected socket, or the browser.

## Choose The Test Boundary {#choose-the-test-boundary}

Scalive applications have three useful test boundaries:

- **Disconnected tests** execute the finalized ZIO HTTP routes and inspect the
  first HTML response. This boundary has public support in `scalive.testing`.
- **Connected tests** use
  @:apiSymbol(object:scalive.testing.ConnectedRender)`ConnectedRender`@:@ to join
  through production admission and supervision, then interact through a typed
  @:apiSymbol(class:scalive.testing.ConnectedView)`ConnectedView`@:@.
- **Browser tests** run the Phoenix LiveView JavaScript client against a real
  server. Scalive does not currently publish a browser fixture or Playwright
  library.

Use the narrowest boundary that proves the behavior. Keep most rendering,
routing, form, cookie, and initial lifecycle assertions disconnected. Use a real
browser when the claim depends on DOM patching, JavaScript hooks, focus, uploads,
navigation, transport loss, or reconnect behavior.

@:callout(info)

`scalive.testing` is intentionally smaller than `Phoenix.LiveViewTest`. It
supports connected mount, typed server messages, semantic click and form
bindings, nested joins, uploads, and explicit local ordinary-form requests. It
does not emulate browser successful-control selection or execute JavaScript.

@:@

## Add Testing Support {#add-testing-support}

Scalive publishes and supports exactly two Scala coordinates:
`dev.scalive::scalive`, containing all production API, render, runtime, protocol,
and transport classes, and `dev.scalive::scalive-testing` for optional test
support. Add the latter to the test module that already depends on your
application. Inside this repository, Mill modules use `moduleDeps = Seq(...,
scalive.testing)`. External snapshot consumers use the same snapshot repository
and revision as the application artifact:

```scala
def repositories = Seq(
  "https://central.sonatype.com/repository/maven-snapshots"
)

def mvnDeps = Seq(
  mvn"dev.scalive::scalive-testing:{{scaliveSnapshotVersion}}"
)
```

## Test Disconnected Rendering {#test-disconnected-rendering}

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
  private val config = ZioHttpConfig(
    signingSecret = "fixed-test-signing-secret-000000000000",
    sessionMaxAge = java.time.Duration.ofMinutes(30),
    secureCookie = false
  ).fold(error => throw IllegalArgumentException(error.toString), identity)

  private val application = Live.router(Routes.profile -> ProfileLiveView())
  private val routes = ZioHttp.routes(application, config)

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

The names `Routes.profile` and `ProfileLiveView` represent application code. Use
a fixed, valid test @:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@
when assertions depend on signed cookies or tokens; do not compare output
produced with independently constructed configurations.

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
they do not automatically choose submitted controls or dispatch a
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ event.

Use @:apiSymbol(class:scalive.FormPath)`FormPath`@:@ for generated nested names when the application uses a
@:apiSymbol(trait:scalive.FormCodec)`FormCodec`@:@. Values remain a `Vector` because repeated names, such as checkbox
groups, are valid.

## Submit Ordinary Forms {#submit-ordinary-forms}

Use @:apiSymbol(def:scalive.testing.RenderedForm.submit)`RenderedForm.submit`@:@
when a rendered GET or POST form should execute an ordinary local HTTP route.
Supply the complete ordered @:apiSymbol(class:scalive.FormData)`FormData`@:@,
including the rendered CSRF field for a checked POST:

```scala
for
  page <- DisconnectedRender.run(liveRoutes, Request.get(loginUrl))
  form <- ZIO.fromEither(page.form(FormQuery(method = Some(Method.POST))))
  csrf <- ZIO.fromOption(form.values(CsrfProtection.ParamName).headOption)
  redirect <- form.submit(
                httpRoutes,
                FormData(
                  Vector(
                    CsrfProtection.ParamName -> csrf,
                    LoginForm.Email.name      -> "ada@example.test"
                  )
                ),
                submitter = Some(FormSubmitter("sign-in", "yes"))
              )
  dashboard <- redirect.followSeeOther(liveRoutes)
yield assertTrue(dashboard.text.contains("Welcome"))
```

Submission retains duplicate field names and appends the optional submitter.
GET fields replace the action query. POST fields become an
`application/x-www-form-urlencoded` body while the action query is retained.
Other POST encodings fail explicitly. Relative actions honor the document's
first `base[href]`; same-origin absolute actions are accepted, while
cross-origin actions fail because the serverless harness executes only the
supplied routes.

Cookies returned by one response are carried by name into the next request, and
zero-`Max-Age` cookies are removed. This intentionally supports Scalive's
root-scoped test flows rather than simulating browser domain, path, Secure, or
SameSite policy. Redirects remain explicit:
@:apiSymbol(def:scalive.testing.RenderedPage.followSeeOther)`followSeeOther`@:@
accepts only a local `303 See Other` with a `Location` header.

## Cover Connected Behavior {#cover-connected-behavior}

@:apiSymbol(def:scalive.testing.ConnectedRender.join)`ConnectedRender.join`@:@
finalizes a single LiveView at `/`, performs disconnected rendering, validates
its bootstrap credentials, and starts the connected lifecycle through production
route admission and supervision:

```scala
test("increments after a connected click") {
  ZIO.scoped {
    for
      view  <- ConnectedRender.join(CounterLiveView())
      _     <- view.clickButton("Increment")
      count <- view.text("#count")
    yield assertTrue(count == "1")
  }
}
```

Use `view.send(message)` when a typed server message is the behavior under test.
Use `click`, `clickButton`, `changeForm`, and `submitForm` to resolve bindings
from the latest committed HTML and wait for the correlated lifecycle output.
`awaitDiff` waits for an uncorrelated async or subscription update, and
`joinNested(instanceId)` enters a nested lifecycle registered by the parent.

For routed applications, use the overload accepting `LiveApplication`, a fixed
validated `ZioHttpConfig`, a ZIO HTTP `Request`, and optional untrusted connect
parameters. That overload exercises the actual route, mount aspects, layouts,
security bootstrap, and request URL rather than mounting a synthetic root.

`ConnectedView.html` is a semantic projection of the latest committed server
render, not a browser DOM. Connected tests do not execute the Phoenix JavaScript
client, patch a real document, run hooks, manage focus, or prove browser history
behavior. Keep those claims at the browser boundary.

## Test In A Browser {#test-in-a-browser}

Run the same production-shaped server, asset bundle, root layout, security
configuration, and socket path that users will receive. A browser smoke suite
should prove at least:

- the disconnected document contains meaningful content before the socket joins;
- the LiveSocket reaches the connected state;
- one event updates the existing DOM;
- live patch or navigation preserves the expected URL and title;
- hooks and uploads work in a real browser when the application uses them;
- controls expose semantic elements, visible labels, and accessible names;
- keyboard-only interaction follows the intended focus order and visibly shows focus;
- validation, status, and other live feedback is announced when appropriate,
  with only intentional focus moves such as focusing an error summary; and
- a deliberately interrupted WebSocket exercises the application's reconnect
  expectations.

The Scalive repository runs the upstream Phoenix LiveView `v1.2.10` Playwright
suite against `e2eApp` with:

```bash
./scripts/e2e-run-upstream.sh
```

Changes to the runtime, protocol, transport, or synchronized fixtures can use
`./scripts/e2e-run-upstream-strict.sh` to require three complete consecutive
runs with retries disabled.

These scripts, their `test/playwright.upstream.config.js`, and the repository's site
Playwright suites are project regression infrastructure. They are evidence for
the compatibility matrix, not a distributed browser-testing API for Scalive
applications. Application teams should own selectors, fixtures, server startup,
and assertions for their product.

## Know What Each Test Proves {#know-what-each-test-proves}

A passing disconnected test does not prove a socket can connect. A passing
`ConnectedRender` test does not prove the Phoenix client can patch the browser
DOM. A browser test proves its scenario but may not isolate the failing lifecycle
stage. Keep at least one assertion at each boundary your application depends on,
and use [Troubleshooting](troubleshooting.md#separate-the-two-mounts) to locate a
failure before expanding the test suite.

## Related Tasks {#related-tasks}

- Build the production-shaped browser bundle with [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Locate the failing lifecycle stage with [Troubleshooting](troubleshooting.md#prerequisites).
- Supply deterministic dependencies with [Services and dependency injection](services-and-zlayer-injection.md#supply-a-test-implementation).
