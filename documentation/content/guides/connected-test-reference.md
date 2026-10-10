{%
title = "Connected test reference"
description = "Exact contracts of the scalive.testing helpers: connected actions, waits, HTML snapshots, rendered forms, reconnect admission, and origin checks."
order = 61
section = guides
group = "Testing and troubleshooting"
%}

## Before you start {#prerequisites}

This page is the reference for `scalive.testing`. Read
[Testing LiveViews](testing.md#prerequisites) first; it shows the common tests
and when to use each boundary.

## What the connected harness runs {#connected-harness-boundaries}

`ConnectedRender.join` and `ConnectedRender.open` perform the disconnected HTTP
render, validate the page's signed bootstrap credentials, and start the
connected lifecycle through the production in-process Phoenix transport and
supervision. `ConnectedRender.open` also runs the application's routes, mount
aspects, layouts, request URL, and any root admission installed with
@:apiSymbol(def:scalive.LiveSessionBuilder.withAdmission)`withAdmission`@:@.

The in-process transport executes the same join, admission, event,
hosted-upload, leave, and navigation handling as the WebSocket adapter. The
upload helper supports hosted uploads only, not external uploaders. The harness
does not call the WebSocket endpoint, serialize network frames, or validate the
upgrade request's `Origin` header.

`ConnectedView.html` is a semantic projection of the latest committed server
render, not a browser DOM. Connected tests do not execute the Phoenix JavaScript
client, patch a real document, run hooks, manage focus, schedule browser
reconnect attempts, or prove browser history behavior. Keep those claims at the
browser boundary.

## Connected actions {#connected-actions}

`click`, `clickButton`, `changeForm`, and `submitForm` resolve bindings from the
latest committed HTML and wait for the correlated lifecycle output.
`send(message)` delivers a typed message out of band, because arbitrary Scala
values have no Phoenix wire representation; its resulting lifecycle output still
uses the production transport sink. `awaitDiff` waits for an uncorrelated async
or subscription update, and `awaitAction` waits for uncorrelated navigation or
transport disconnect. `joinNested(instanceId)` enters a nested lifecycle
registered by the parent.

Actions return a @:apiSymbol(enum:scalive.testing.ConnectedAction)`ConnectedAction`@:@:

| Case | Meaning |
| --- | --- |
| @:apiSymbol(val:scalive.testing.ConnectedAction.Rendered)`Rendered`@:@ | The action completed without terminal navigation. |
| @:apiSymbol(enum:scalive.testing.ConnectedAction.LiveNavigation)`LiveNavigation(navigation)`@:@ | A same-session route replacement is available to follow explicitly. |
| @:apiSymbol(enum:scalive.testing.ConnectedAction.Redirect)`Redirect(to)`@:@ | A full redirect was emitted and the in-process transport closed. |
| @:apiSymbol(val:scalive.testing.ConnectedAction.Disconnected)`Disconnected`@:@ | The transport closed before the correlated reply arrived. |

A `LiveNavigation` contains a
@:apiSymbol(class:scalive.testing.ConnectedNavigation)`ConnectedNavigation`@:@.
Inspect its
@:apiSymbol(val:scalive.testing.ConnectedNavigation.destination)`destination`@:@
and @:apiSymbol(val:scalive.testing.ConnectedNavigation.replace)`replace`@:@ values,
then call
@:apiSymbol(def:scalive.testing.ConnectedNavigation.follow)`follow`@:@ to execute
the production redirect-join admission path.

Optional `connectParams` passed to `ConnectedRender.open` are untrusted client
JSON, not authenticated session or request data. The harness owns `_mounts`,
overwrites any supplied value, starts it at `0`, and advances it for each root
reconnect or followed navigation. Applications should not treat Phoenix-owned
keys as a stable contract.

## Wait for an HTML condition {#wait-for-an-html-condition}

@:apiSymbol(def:scalive.testing.ConnectedView.awaitHtml)`awaitHtml(description, timeout)(predicate)`@:@
returns a `Task[String]` containing the exact HTML that satisfied the
predicate; inspect that returned snapshot rather than reading `view.html` again.

It checks current HTML first, then rechecks after uncorrelated async diff
notifications. One overall deadline covers the wait: intermediate diffs do not
restart it, and it is not limited by `awaitDiff`'s five-second timeout. A
`java.util.concurrent.TimeoutException` includes the description, requested
duration, and last observed HTML. Predicate exceptions fail the task. Keep the
predicate quick and nonblocking. Retirement or disconnection fails the wait; it
never follows navigation to another view. When the predicate queries the HTML,
treat a missing element as "not ready" but let an invalid selector fail
immediately instead of hiding it until the timeout.

The deadline uses the current ZIO clock. In `ZIOSpecDefault` this is `TestClock`:
advance it explicitly for deterministic deadline tests, apply
`TestAspect.withLiveClock` to the suite, or wrap just the wait in
`zio.test.Live.live(view.awaitHtml(description, timeout)(predicate))` for a
wall-clock timeout without changing the clock used by application work already
running.

Only one waiter may consume a view's diff queue: do not run `awaitHtml` and
`awaitDiff` concurrently, or multiple `awaitHtml` calls on the same view. Finish
correlated click, send, and form actions before waiting. Each check observes the
latest semantic server projection, so transient states can be missed.

## Inspect retained HTML snapshots {#inspect-retained-html-snapshots}

Use @:apiSymbol(def:scalive.testing.RenderedHtml.parse)`RenderedHtml.parse(html)`@:@
to query any retained HTML string, including `view.html`, the result of `awaitHtml`,
or a disconnected page's body. A
@:apiSymbol(class:scalive.testing.RenderedHtml)`RenderedHtml`@:@ keeps the exact
input in `html`, not a reserialized document; `text` is normalized whole-document
text. Parse old strings directly rather than querying the current live view:

@:sourceRegion(documentation/fixtures/quick-start/test/src/quickstart/CounterSnapshotSpec.scala, testing-snapshots)

The counter starts at zero and has no `#missing` element. CSS queries use jsoup
selector syntax:

- @:apiSymbol(def:scalive.testing.RenderedHtml.selectOne)`selectOne(css)`@:@ returns
  `Either[HtmlQueryError, RenderedElement]` and requires exactly one match. Zero
  matches produce `NotFound(selector)`; more than one produces
  `MultipleMatches(selector, count)`.
- @:apiSymbol(def:scalive.testing.RenderedHtml.selectAll)`selectAll(css)`@:@ returns
  `Either[HtmlQueryError, Vector[RenderedElement]]`, including `Right(Vector.empty)`
  for no matches.
- Both return
  @:apiSymbol(enum:scalive.testing.HtmlQueryError)`HtmlQueryError`@:@'s
  `InvalidSelector(selector, message)` for malformed selectors. Assert on the
  `Either` or handle its errors explicitly rather than hiding failures in defaults.

Each @:apiSymbol(class:scalive.testing.RenderedElement)`RenderedElement`@:@ exposes
read-only `tagName`, Jsoup-normalized `text`, and `value`.
@:apiSymbol(def:scalive.testing.RenderedElement.attribute)`attribute(name)`@:@ is
case-insensitive and returns entity-decoded values: `None` means absent, while
`Some("")` means present but empty. Relative URL attributes stay relative; lookup
does not resolve them against a base URL. `value` follows the
`RenderedField` semantics: textarea text has outer whitespace trimmed but retains
internal whitespace; other elements return their own `value` attribute or `""`
if absent. It does not derive a `select`'s value from selected options or supply
a checkbox's browser-default `"on"` value.

Snapshots and query results are immutable and remain valid after later renders,
navigation, or harness closure. Every query uses an isolated working document,
so selector evaluation cannot change other queries or retained results. These
are semantic server-HTML assertions only: they do not execute JavaScript,
simulate successful-control selection, or prove browser DOM patching.

## Query rendered pages and forms {#query-forms-semantically}

The @:apiSymbol(class:scalive.testing.RenderedPage)`RenderedPage`@:@ returned by
`DisconnectedRender.run` exposes the status and headers through
@:apiSymbol(val:scalive.testing.RenderedPage.response)`response`@:@, the exact body
through @:apiSymbol(val:scalive.testing.RenderedPage.html)`html`@:@, normalized
document text through @:apiSymbol(def:scalive.testing.RenderedPage.text)`text`@:@,
and all forms through
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

## Submit ordinary forms {#submit-ordinary-forms}

@:apiSymbol(def:scalive.testing.RenderedForm.submit)`RenderedForm.submit`@:@
retains duplicate field names and appends the optional submitter. GET fields
replace the action query. POST fields become an
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

## Test reconnect admission {#test-reconnect-admission}

@:apiSymbol(class:scalive.testing.ConnectedClient)`ConnectedClient`@:@ retains
the page's signed bootstrap credentials, so
@:apiSymbol(def:scalive.testing.ConnectedClient.reconnect)`reconnect`@:@ does not
issue another disconnected GET. Each `join` and `reconnect` creates a distinct
in-process transport, and admission installed with `withAdmission` runs again
for each one, as it does for followed same-session navigation.

To prove that revocation takes effect, revoke durable authorization, retire the
admitted transport, wait until the view observes disconnection, and assert that
the next transport is rejected:

```scala fragment
for
  client <- ConnectedRender.open(admittedApplication, config, request)
  view   <- client.join
  _      <- authorization.revoke(sessionId)
  _      <- connections.disconnect(sessionId)
  _      <- view.awaitDisconnected
  result <- client.reconnect.either
yield assertTrue(result == Left(ConnectedJoinFailure.Unauthorized))
```

`admittedApplication`, `authorization.revoke`, `sessionId`, and the
`LiveConnections` value named `connections` are application test setup; the
[authentication guide](authentication.md#bind-admission-to-physical-connections)
shows the admission they exercise. Run the effect inside `ZIO.scoped` and
provide the layers the application requires.

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

## Test WebSocket origin admission {#test-websocket-origin-admission}

Exercise the finished ZIO HTTP routes when the upgrade policy itself is the
behavior under test. This boundary can prove that one configured origin produces
an upgrade response and a missing origin receives HTTP 403 without starting a
server or browser:

```scala fragment
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
`routes`. Test the other rejected header shapes that
[origin admission](configuration.md#admit-websocket-origins) lists in the same
way. Browser automation cannot normally manufacture them, so keep them as
transport or edge integration tests, along with proxy behavior.

## Related tasks {#related-tasks}

- Start with the common tests in [Testing LiveViews](testing.md#prerequisites).
- Bind physical connections to application sessions with [Authentication and sessions](authentication.md#bind-admission-to-physical-connections).
- Configure allowed WebSocket origins with [Configuration](configuration.md#admit-websocket-origins).
