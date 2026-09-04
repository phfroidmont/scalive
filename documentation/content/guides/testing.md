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
  @:apiSymbol(object:scalive.testing.ConnectedRender)`ConnectedRender`@:@ to run
  an isolated root or routed application through signed bootstrap, Phoenix
  transport dispatch, and lifecycle supervision, then interact through a typed
  @:apiSymbol(class:scalive.testing.ConnectedView)`ConnectedView`@:@. Routed joins
  also exercise application routes and transport-owned `withAdmission`.
- **Browser tests** run the Phoenix LiveView JavaScript client against a real
  server. Scalive does not currently publish a browser fixture or Playwright
  library.

Use the narrowest boundary that proves the behavior. Keep most rendering,
routing, form, cookie, and initial lifecycle assertions disconnected. Connected
tests can cover server-side navigation, transport invalidation, and reconnect
admission. Use a real browser when the claim depends on DOM patching, JavaScript
hooks, focus, browser history, reconnect timing, or network behavior.

@:callout(info)

`scalive.testing` is intentionally smaller than `Phoenix.LiveViewTest`. It
supports connected mount, typed server messages, semantic click and form
bindings, nested joins, hosted uploads, routed reconnects, live navigation, and
explicit local ordinary-form requests. It does not emulate browser
successful-control selection or execute JavaScript.

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
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.http("localhost"))
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
produced with independently constructed configurations. Even disconnected tests
must supply a non-empty origin allowlist because it is part of valid transport
configuration.

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

### Join An Isolated LiveView {#join-an-isolated-liveview}

@:apiSymbol(def:scalive.testing.ConnectedRender.join)`ConnectedRender.join`@:@
finalizes a single LiveView at `/`, performs disconnected rendering, validates
its bootstrap credentials, and starts the connected lifecycle through the
production in-process Phoenix transport and supervision:

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

Use this overload when routing and reconnect admission are not part of the
claim. The name `CounterLiveView` represents application code.

### Open A Routed Application Client {#open-a-routed-application-client}

For routed behavior, use
@:apiSymbol(def:scalive.testing.ConnectedRender.open)`ConnectedRender.open`@:@
with a `LiveApplication`, a fixed validated `ZioHttpConfig`, and the request
that should render the route. It performs the disconnected request and returns a
@:apiSymbol(class:scalive.testing.ConnectedClient)`ConnectedClient`@:@ whose
@:apiSymbol(def:scalive.testing.ConnectedClient.join)`join`@:@ and
@:apiSymbol(def:scalive.testing.ConnectedClient.reconnect)`reconnect`@:@ operations
each create a distinct in-process transport:

```scala
ZIO.scoped {
  for
    client <- ConnectedRender.open(
                application,
                config,
                Request.get(URL.decode("/accounts/42").fold(throw _, identity))
              )
    view   <- client.join
    title  <- view.text("h1")
  yield assertTrue(title == "Account 42")
}
```

Here `application` and its `/accounts/42` route are application code. This form
exercises the actual route, mount aspects, layouts, signed bootstrap, request
URL, Phoenix event dispatcher, and any root admission installed with
@:apiSymbol(def:scalive.LiveSessionBuilder.withAdmission)`withAdmission`@:@.
That transport-owned boundary is sometimes called physical admission. It
revalidates every root join before installing its connected lifecycle, including
reconnects and followed same-session navigation; reconnect also creates a new
transport identity.

Optional `connectParams` are untrusted client JSON, not authenticated session or
request data. The harness owns `_mounts`, overwrites any supplied value, starts
it at `0`, and advances it for each root reconnect or followed navigation.
Applications should not treat Phoenix-owned keys as a stable contract.

### Exercise Actions And Navigation {#exercise-actions-and-navigation}

Use `view.send(message)` when a typed server message is the behavior under test.
Typed messages are an out-of-band test operation because arbitrary Scala values
have no Phoenix wire representation; their resulting lifecycle output still uses
the production transport sink.
Use `click`, `clickButton`, `changeForm`, and `submitForm` to resolve bindings
from the latest committed HTML and wait for the correlated lifecycle output.
`awaitDiff` waits for an uncorrelated async or subscription update, and
`awaitAction` waits for uncorrelated navigation or transport disconnect. The
`joinNested(instanceId)` method enters a nested lifecycle registered by the parent.
Actions return a @:apiSymbol(enum:scalive.testing.ConnectedAction)`ConnectedAction`@:@:

| Case | Meaning |
| --- | --- |
| @:apiSymbol(val:scalive.testing.ConnectedAction.Rendered)`Rendered`@:@ | The action completed without terminal navigation. |
| @:apiSymbol(enum:scalive.testing.ConnectedAction.LiveNavigation)`LiveNavigation(navigation)`@:@ | A same-session route replacement is available to follow explicitly. |
| @:apiSymbol(enum:scalive.testing.ConnectedAction.Redirect)`Redirect(to)`@:@ | A full redirect was emitted and the in-process transport closed. |
| @:apiSymbol(val:scalive.testing.ConnectedAction.Disconnected)`Disconnected`@:@ | The transport closed before the correlated reply arrived. |

### Test Typed Form Behavior {#test-typed-form-behavior}

Use `changeForm` with a target and `_unused_*` markers to verify field-local
feedback. Use `submitForm` without unused markers to verify that submission
reveals all errors and produces a domain value only when valid. Query the
committed HTML after each action rather than asserting against an independently
decoded form:

```scala
for
  view <- ConnectedRender.join(ProfileLiveView())
  _ <- view.changeForm(
         "#profile-form",
         Vector(
           Profile.Name.name        -> "",
           "profile[_unused_email]" -> "",
           Profile.Email.name       -> ""
         ),
         target = Some(Profile.Name.name)
       )
  changed <- view.html
  _ <- view.submitForm(
         "#profile-form",
         Vector(Profile.Name.name -> "", Profile.Email.name -> "invalid")
       )
  submitted <- view.html
yield assertTrue(
  changed.contains("Name is required"),
  !changed.contains("Enter a valid email"),
  submitted.contains("Enter a valid email")
)
```

Repeated forms must submit every row's presence control along with its fields.
Exercise add, remove, and reorder controls through the connected view, then
assert stable row keys rather than display indexes. The
[repeated contacts example](../examples/repeated-contacts-form.md) demonstrates
the complete interaction. An isolated `ConnectedView` is enough for ordinary
change and submit behavior. `ConnectedClient.reconnect` proves server-side
transport admission and lifecycle behavior, but it does not run Phoenix
JavaScript or replay browser form values. Verify automatic form recovery in a
[browser test](#test-in-a-browser), or dispatch an explicit recovery payload at
a lower protocol boundary when that protocol behavior is the claim.

A `LiveNavigation` contains a
@:apiSymbol(class:scalive.testing.ConnectedNavigation)`ConnectedNavigation`@:@.
Inspect its
@:apiSymbol(val:scalive.testing.ConnectedNavigation.destination)`destination`@:@
and @:apiSymbol(val:scalive.testing.ConnectedNavigation.replace)`replace`@:@ values,
then call
@:apiSymbol(def:scalive.testing.ConnectedNavigation.follow)`follow`@:@ to execute
the production redirect-join admission path:

```scala
for
  action <- view.click("[data-open-settings]")
  settings <- action match
                case ConnectedAction.LiveNavigation(navigation) =>
                  navigation.follow
                case other =>
                  ZIO.fail(new AssertionError(s"Expected live navigation, got $other"))
  heading <- settings.text("h1")
yield assertTrue(heading == "Settings")
```

The selector and destination route in this example belong to the application.

### Test Reconnect Admission {#test-reconnect-admission}

@:apiSymbol(class:scalive.testing.ConnectedClient)`ConnectedClient`@:@ retains
the page's signed bootstrap credentials, so reconnect tests do not issue another
disconnected GET. Revoke durable authorization, retire the admitted transport,
wait until the view observes disconnection, and assert that the next transport
is rejected:

```scala
val request = Request.get(
  URL.decode("/accounts/42?session=test-session").fold(throw _, identity)
)

ZIO.scoped {
  for
    client <- ConnectedRender.open(admittedApplication, config, request)
    view   <- client.join
    _      <- authorization.revoke(sessionId)
    _      <- connections.disconnect(sessionId)
    _      <- view.awaitDisconnected
    result <- client.reconnect.either
  yield assertTrue(result == Left(ConnectedJoinFailure.Unauthorized))
}
```

The `/accounts/42` route, `admittedApplication`, `authorization.revoke`,
`sessionId`, and the `LiveConnections` value named `connections` are application
test setup. The application installs its admission with `withAdmission`; the
example does not define an application authorization API. Provide the layers
required by `admittedApplication` around this scoped effect as described in
[Services and dependency injection](services-and-zlayer-injection.md#supply-a-test-implementation).

Join and follow failures use
@:apiSymbol(enum:scalive.testing.ConnectedJoinFailure)`ConnectedJoinFailure`@:@:

| Case | Meaning |
| --- | --- |
| @:apiSymbol(val:scalive.testing.ConnectedJoinFailure.Unauthorized)`Unauthorized`@:@ | Signed join authorization or connected mount admission rejected the join. |
| @:apiSymbol(val:scalive.testing.ConnectedJoinFailure.Stale)`Stale`@:@ | The server requires a fresh disconnected render and bootstrap. |
| @:apiSymbol(val:scalive.testing.ConnectedJoinFailure.Disconnected)`Disconnected`@:@ | The transport closed while the join was pending. |
| @:apiSymbol(enum:scalive.testing.ConnectedJoinFailure.Redirect)`Redirect(to)`@:@ | Connected mount requested a redirect instead of installing the view. |
| @:apiSymbol(enum:scalive.testing.ConnectedJoinFailure.Transport)`Transport(error)`@:@ | The in-process transport failed outside a protocol-visible join result. |

This proves server-side admission is rerun for a fresh transport. It does not
prove when or how often a browser retries after network loss.

### Keep Connected Harness Boundaries Explicit {#connected-harness-boundaries}

The in-process transport executes the same transport-owned join, admission,
event, hosted-upload, leave, and navigation state as the WebSocket adapter. The
upload helper supports hosted uploads only, not external uploaders. The harness
does not call the WebSocket endpoint, serialize network frames, or validate the
upgrade request's `Origin` header.

`ConnectedView.html` is a semantic projection of the latest committed server
render, not a browser DOM. Connected tests do not execute the Phoenix JavaScript
client, patch a real document, run hooks, manage focus, schedule browser
reconnect attempts, or prove browser history behavior. Keep those claims at the
browser boundary.

## Test WebSocket Origin Admission {#test-websocket-origin-admission}

Exercise the finalized ZIO HTTP routes when the upgrade policy itself is the
behavior under test. This boundary can prove that one configured origin produces
an upgrade response and a missing origin receives HTTP 403 without starting a
server or browser:

```scala
val socketUrl = URL.decode("/live/websocket").fold(throw _, identity)
val allowedUpgrade = Request
  .get(socketUrl)
  .addHeader(Header.Custom("origin", "http://localhost"))

test("admits only a configured websocket origin") {
  for
    admitted <- ZIO.scoped(routes.runZIO(allowedUpgrade))
    rejected <- ZIO.scoped(routes.runZIO(Request.get(socketUrl)))
  yield assertTrue(
    admitted.status == Status.SwitchingProtocols,
    rejected.status == Status.Forbidden
  )
}
```

The request origin must match the `allowedWebSocketOrigins` used to construct
`routes`. Use transport or edge integration tests for duplicate physical headers
and proxy behavior; those shapes are not reliably produced by browser automation.

## Test In A Browser {#test-in-a-browser}

Run the same production-shaped server, browser assets, root layout, security
configuration, and socket path that users will receive. A browser smoke suite
should prove at least:

- the disconnected document contains meaningful content before the socket joins;
- the LiveSocket reaches the connected state;
- the server admits the page's exact configured HTTP or HTTPS `Origin`;
- one event updates the existing DOM;
- live patch or navigation preserves the expected URL and title;
- hooks and uploads work in a real browser when the application uses them;
- controls expose semantic elements, visible labels, and accessible names;
- keyboard-only interaction follows the intended focus order and visibly shows focus;
- validation, status, and other live feedback is announced when appropriate,
  with only intentional focus moves such as focusing an error summary; and
- a deliberately interrupted WebSocket exercises the application's reconnect
  expectations.

At the transport request boundary, separately verify that missing, `null`,
malformed, duplicate, combined, and mismatched `Origin` values receive HTTP 403
before upgrade. Browser automation cannot normally manufacture all of those
forbidden header shapes, so keep these as edge or transport integration tests.

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

A passing disconnected test does not prove a socket can join. A passing
`ConnectedRender` test proves the server-side transport session but neither
WebSocket Origin admission nor that the Phoenix client can patch the browser DOM.
A browser test proves its scenario but may not isolate the failing lifecycle
stage. Keep at least one assertion at each boundary your application depends on, and use
[Troubleshooting](troubleshooting.md#separate-the-two-mounts) to locate a failure
before expanding the test suite.

## Related Tasks {#related-tasks}

- Prepare the production-shaped browser assets with [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Locate the failing lifecycle stage with [Troubleshooting](troubleshooting.md#prerequisites).
- Supply deterministic dependencies with [Services and dependency injection](services-and-zlayer-injection.md#supply-a-test-implementation).
