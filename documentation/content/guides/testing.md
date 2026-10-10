{%
title = "Testing LiveViews"
description = "Test interactions, forms, routes, and the first HTML response at the narrowest boundary that proves the behavior, and know when a real browser is needed."
order = 60
section = guides
group = "Testing and troubleshooting"
%}

## Before you start {#prerequisites}

This guide assumes a project with a test module, such as the quick-start
application after [Testing your LiveView](../learn/testing-your-liveview.md).
That lesson adds `dev.scalive::scalive-testing`, writes connected tests for a
counter, a form, and a service-backed page, and runs them with `mill app.test`.
This guide covers the next tests most applications need. The
[connected test reference](connected-test-reference.md#prerequisites) lists the
exact contracts of each helper.

## Choose the test boundary {#choose-the-test-boundary}

Scalive applications have three useful test boundaries:

- **Connected tests** use
  @:apiSymbol(object:scalive.testing.ConnectedRender)`ConnectedRender`@:@ to
  mount a LiveView and connect it through the same server-side protocol
  handling a browser uses, then interact through a typed
  @:apiSymbol(class:scalive.testing.ConnectedView)`ConnectedView`@:@. Most
  interaction, form, and navigation tests belong here.
- **Disconnected tests** run the finished ZIO HTTP routes and inspect the first
  HTML response, its status, and its cookies.
- **Browser tests** run the Phoenix LiveView JavaScript client against a real
  server. Scalive does not publish a browser fixture or Playwright library.

Use the narrowest boundary that proves the behavior. Use a real browser when the
claim depends on DOM patching, JavaScript hooks, focus, browser history,
reconnect timing, or network behavior.

@:callout(info)

`scalive.testing` is intentionally smaller than `Phoenix.LiveViewTest`. It
supports connected mount, typed server messages, semantic click and form
bindings, nested joins, hosted uploads, routed reconnects, live navigation, and
explicit local ordinary-form requests. It does not emulate browser
successful-control selection or execute JavaScript.

@:@

## Interact with a connected LiveView {#cover-connected-behavior}

@:apiSymbol(def:scalive.testing.ConnectedRender.join)`ConnectedRender.join`@:@
mounts one LiveView at `/`, renders its first HTML, and connects it. The
[first test](../learn/testing-your-liveview.md#write-a-first-test) uses it. Use
it when routing and mount policy are not part of the claim.

Interact through the view the way a visitor would:

- `clickButton(label)` clicks the button with that text, and `click(selector)`
  clicks the element a CSS selector finds.
- `changeForm(selector, fields)` and `submitForm(selector, fields)` send a
  form's change or submit event with the given fields.
- `send(message)` delivers a typed message directly, when the message itself is
  the behavior under test.

Each action waits for the server's reply. Then read the result with
`text(selector)` for one element or `html` for the whole rendered view.

## Wait for background updates {#wait-for-an-html-condition}

Work started with [asynchronous work](async-work.md#prerequisites) or a
[subscription](subscriptions.md#prerequisites) updates the page later, not as
the reply to an action. Wait for the HTML to reach the expected state with
@:apiSymbol(def:scalive.testing.ConnectedView.awaitHtml)`awaitHtml`@:@. This
test from the Learn path waits for the thermostat's first reading, then for the
refreshed one:

@:sourceRegion(documentation/fixtures/learn/test/src/quickstart/ThermostatLiveViewSpec.scala, testing-await-html)

`awaitHtml` checks the current HTML first, then again after each update, and
fails after the timeout with the last HTML it saw. `ZIOSpecDefault` replaces the
clock with a test clock that only moves when the test adjusts it, so the suite
uses `TestAspect.withLiveClock` for real timeouts.
[Wait for an HTML condition](connected-test-reference.md#wait-for-an-html-condition)
describes the deadline, clock, and concurrency rules.

## Test typed form behavior {#test-typed-form-behavior}

Use `changeForm` with a target and `_unused_*` markers to verify field-local
feedback. Use `submitForm` without unused markers to verify that submission
reveals all errors and produces a domain value only when valid. Query the
committed HTML after each action rather than asserting against an independently
decoded form. This spec tests the profile form from
[Typed forms and validation](typed-forms-and-validation.md#handle-change-and-submit-events):

@:sourceRegion(documentation/fixtures/guides/test/src/forms/ProfileLiveViewSpec.scala, forms-test)

Field names come from the definition, such as `Profile.Name.name`, so a renamed
field cannot leave the test behind.

For a form with a [typed submitter](form-controls.md#route-multiple-submit-actions),
pass the button's name and value explicitly. The harness appends the pair to the
submitted fields, as the Phoenix browser client does:

```scala fragment
view.submitForm(
  "#profile-form",
  validFields,
  submitter = Some(Profile.Submitter.raw(Profile.Intent.Preview))
)
```

This exercises the same decoder as a real button click. Use a browser test when
the claim also depends on native implicit submission, form association,
constraint validation, or JavaScript loading behavior.

[Repeated rows](repeated-form-rows.md#prerequisites) must submit every row's
presence control along with its fields. Exercise add, remove, and reorder
controls through the connected view, then assert stable row keys rather than
display indexes. Automatic form recovery after a reconnect runs in the Phoenix
JavaScript client, so verify it in a [browser test](#test-in-a-browser).

## Test a routed application {#open-a-routed-application-client}

When routes, mount aspects, layouts, or services are part of the claim, open the
application instead of one LiveView.
@:apiSymbol(def:scalive.testing.ConnectedRender.open)`ConnectedRender.open`@:@
takes a `LiveApplication`, a fixed `ZioHttpConfig`, and the request that renders
the route. It performs the HTTP request and returns a
@:apiSymbol(class:scalive.testing.ConnectedClient)`ConnectedClient`@:@ whose
@:apiSymbol(def:scalive.testing.ConnectedClient.join)`join`@:@ connects the
page. This test opens the reports route from
[Services and dependency injection](services-and-zlayer-injection.md#register-the-route)
and provides its service:

@:sourceRegion(documentation/fixtures/guides/test/src/reports/ReportsRouteSpec.scala, services-route-test)

`zio.test` and Scalive both define `Live`, so a test that builds a router imports
`zio.test.{Live as _, *}`. When a test also builds routes with `live / "..."`,
exclude `live` the same way: `import zio.test.{Live as _, live as _, *}`.

## Follow a navigation {#exercise-actions-and-navigation}

An action that navigates to another LiveView in the same live session returns
`ConnectedAction.LiveNavigation`. Call `follow` to connect the destination the
way the browser would, including its mount aspects. This test opens the account
page from [Live sessions and mount aspects](live-sessions-and-mount-aspects.md#group-routes-in-a-named-session)
and follows its **Open settings** button:

@:sourceRegion(documentation/fixtures/guides/test/src/sessions/SessionsSpec.scala, testing-follow-navigation)

The [action table](connected-test-reference.md#connected-actions) lists every
outcome an action can report.

## Test disconnected rendering {#test-disconnected-rendering}

Test the first HTML response when the claim is about what a visitor or crawler
receives before the page connects: content, status codes, redirects, or
cookies. The
@:apiSymbol(object:scalive.testing.DisconnectedRender)`DisconnectedRender.run`@:@
method accepts finished `Routes` and a ZIO HTTP `Request`. It runs the route,
reads the response body, and parses the HTML. Create
`app/test/src/quickstart/CounterPageSpec.scala`:

@:sourceRegion(documentation/fixtures/quick-start/test/src/quickstart/CounterPageSpec.scala, quick-start-disconnected-test)

This test builds the same router as the quick start's `Main`, without the root
layout, and turns it into ZIO HTTP routes with a fixed test
@:apiSymbol(class:scalive.ZioHttpConfig)`ZioHttpConfig`@:@. Use one fixed, valid
config when assertions depend on signed cookies or tokens; do not compare output
produced with independently constructed configurations. Even disconnected tests
must supply a non-empty origin allowlist because it is part of valid transport
configuration.

`zio.http.*` would import ZIO HTTP's `Routes`, which hides the quick start's
`Routes` object. Import only the ZIO HTTP names the test uses.

## Submit ordinary forms {#submit-ordinary-forms}

Use @:apiSymbol(def:scalive.testing.RenderedForm.submit)`RenderedForm.submit`@:@
when a rendered GET or POST form should execute an ordinary local HTTP route.
Supply the complete ordered @:apiSymbol(class:scalive.FormData)`FormData`@:@,
including the rendered CSRF field for a checked POST. This test signs in through
the login page from
[Ordinary HTTP forms and redirects](http-forms-and-redirects.md#render-a-reusable-http-form):

@:sourceRegion(documentation/fixtures/guides/test/src/httpforms/LoginFlowSpec.scala, testing-submit-form)

Here `routes` combines the Live routes and the HTTP handlers, built from one
fixed test config, and `login` is a GET request for `/login`. The handler needs
a `Sessions` service, so the effect is provided with a test layer. Cookies
returned by one response are carried into the next request, and redirects stay
explicit: `followSeeOther` follows a local `303 See Other`. The
[reference](connected-test-reference.md#submit-ordinary-forms) lists the exact
encoding and cookie rules.

## Test in a browser {#test-in-a-browser}

Run the same production-shaped server, browser assets, root layout, security
configuration, and socket path that users will receive. A browser smoke suite
should prove at least:

- the disconnected document contains meaningful content before the LiveView
  connects;
- `LiveSocket` reaches the connected state;
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

Application teams own the selectors, fixtures, server startup, and assertions
for their product.

## Know what each test proves {#know-what-each-test-proves}

A passing disconnected test does not prove that the LiveView can join. A passing
`ConnectedRender` test proves the server-side session but neither WebSocket
`Origin` admission nor that the Phoenix client can patch the browser DOM. A
browser test proves its scenario but may not isolate the failing lifecycle
stage. Keep at least one assertion at each boundary your application depends
on, and use [Troubleshooting](troubleshooting.md#separate-the-two-mounts) to
locate a failure before expanding the test suite.

## Related tasks {#related-tasks}

- Look up helper contracts, reconnect admission, and origin tests in the [Connected test reference](connected-test-reference.md#prerequisites).
- Supply deterministic dependencies with [Services and dependency injection](services-and-zlayer-injection.md#supply-a-test-implementation).
- Prepare the production-shaped browser assets with [Client setup and static assets](static-assets-and-client-setup.md#prerequisites).
- Locate the failing lifecycle stage with [Troubleshooting](troubleshooting.md#prerequisites).
