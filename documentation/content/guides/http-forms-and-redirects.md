{%
title = "Ordinary HTTP forms and redirects"
description = "Render checked GET and POST actions, decode bounded CSRF-protected bodies, hand Live validation back to HTTP, and redirect flash into Live routes."
order = 16
section = guides
group = "Interfaces and input"
%}

## Before you start {#prerequisites}

Start with a typed form or ordinary HTML controls, a GET or POST `RoutePattern`
that can receive them, and one validated `ZioHttpConfig` shared by the Live and
sibling ZIO HTTP routes.

## Choose ordinary HTTP deliberately {#choose-ordinary-http-deliberately}

Use an ordinary browser form when submission should have normal HTTP semantics:
changing cookies, crossing an authentication boundary, downloading a response,
leaving LiveView, or applying Post/Redirect/Get. Keep purely interactive
validation and in-page updates as Live events.

Define the target as a ZIO HTTP `RoutePattern`. This guide uses one login
form, two Live pages, and two POST routes:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginForm.scala, http-forms-definition)

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginForm.scala, http-forms-routes)

Derive a checked @:apiSymbol(class:scalive.FormAction)`FormAction`@:@ from a route
pattern with `FormAction.from(SessionRoute)`.

Checked actions accept only GET and POST, format typed path parameters, URL
encode the resulting path, and mark POST for CSRF injection. `FormAction.from`
throws `FormAction.EncodingException` for unsupported methods or path encoding
failure; use `FormAction.fromEither` when dynamic parameters should keep
`EncodeError` in an `Either`.

`FormAction.unsafe(method, href)` is an explicit escape hatch for an already
encoded or external URL. It performs no route, origin, or path validation and
never requests Scalive CSRF injection, even for POST. Prefer checked actions for
application routes.

## Render a reusable HTTP form {#render-a-reusable-http-form}

Render the form with @:apiSymbol(def:scalive.Form.http)`Form.http`@:@. When the
form does not depend on the model, as on this login page, build a plain form
value with `Definition.initial()` and read its fields directly. The controls and
codecs still come from `LoginForm.Definition`:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginLiveView.scala, http-forms-static-form)

A form without fields uses `Form.http` the same way:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginLiveView.scala, http-forms-sign-out)

ZIO HTTP also defines `Form`. In a file that imports `zio.http.*`, write
`scalive.Form.http`, or import only the ZIO HTTP names you use.

`Form.http` owns the `action` and `method` attributes and rejects direct
overrides. It does not add `phx-change`, `phx-submit`, or `phx-trigger-action`,
and it does not render controls automatically; pass them as modifiers.

Both checked GET and POST actions render with native browser methods. GET is
appropriate only for safe, idempotent retrieval and puts successful controls in
the URL query. POST is appropriate for state changes. The current
`HttpFormDecoder` decodes a URL-encoded request body and always validates CSRF,
so it is intended for checked POST handlers, not GET query decoding. Decode and
bound GET query parameters at the route boundary with the query API chosen by
your application.

## Share security and inject CSRF {#share-security-and-inject-csrf}

Build the HTTP routes' `LiveSecurity` from the same validated config that serves
the Live routes:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginHttpRoutes.scala, http-forms-assembly)

A checked POST rendered through `Form.http` is marked for Scalive's hidden CSRF
token injection. Injection occurs only while rendering through transport routes
configured with that validated config. The matching browser cookie and submitted token are
validated later with `security.csrf`, derived from the same validated config.

Checked GET actions and all unsafe actions are not marked for token injection.
Do not treat `Form.http` alone as request protection: the HTTP handler must still
validate CSRF for state-changing requests. Set `secureCookie = true`
when the browser-facing origin uses HTTPS; Scalive does not infer that from
forwarding headers.

## Decode a bounded POST body {#decode-a-bounded-post-body}

Build one reusable decoder from the same typed definition used by the form, and
register the handlers as ordinary ZIO HTTP routes:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/LoginHttpRoutes.scala, http-forms-handler)

@:apiSymbol(def:scalive.HttpFormDecoder.urlEncoded)`urlEncoded`@:@ accepts only
`application/x-www-form-urlencoded`, honors its declared charset with UTF-8 as
the default, and reads at most `maxBytes + 1` bytes to detect overflow. It
preserves duplicate fields in wire order, validates CSRF, and only then runs the
application form projection. Given a `FormDefinition`, `urlEncoded` always
returns a submitted `Definition.Form` after transport and CSRF checks, even when
its `result` contains domain validation errors. This is useful when the HTTP
handler will render the submitted values and visible feedback again.

`urlEncodedValue` instead requires a valid domain result. It returns the decoded
domain value on success and maps invalid form output to `Error.Validation`.
Choose it when the handler should run only for valid input. The separate
`urlEncoded(FormCodec, ...)` overload remains the explicit low-level escape hatch
for applications intentionally decoding a raw codec rather than a definition.

The decoder does not check the request method or route, authenticate or
authorize a user, rate-limit requests, sanitize strings, or make an unsafe
target secure. Keep those checks in the route handler and service layer. Choose
a route-specific bound; the maximum applies to the encoded body, not decoded
string sizes or downstream work.

## Map decoder errors to responses {#map-decoder-errors-to-responses}

ZIO HTTP handlers commonly return `UIO[Response]`, an effect with no typed
failure, or a wider `ZIO[R, E, Response]` when services and failures remain in
the environment and error channels. `HttpFormDecoder.respond` lets both the
success and semantic-validation callbacks keep that wider effect while mapping
transport and security rejections to fixed responses.

Call `decode(request)` when the handler needs to pattern match the typed error
channel directly. For the common case, `respond` runs the success callback only
after every stage succeeds. In `createSession` above, a semantic validation
failure returns the signed flash redirect described
[below](#redirect-http-flash-into-a-live-route).

Failures remain distinct:

- `Error.Body` covers oversized or unreadable bodies.
- `Error.Representation` covers content type, URL encoding, and unsupported field representations.
- `Error.Csrf` covers missing or invalid browser-bound tokens.
- `Error.Validation` carries the typed form's `FormErrors`.

The default mapping is 413 for oversized bodies, 415 for invalid content type,
400 for other body or representation failures, and 403 for CSRF rejection.
`onValidation` chooses the application response for semantic validation errors
through an effect. For a plain response, use
`onValidation = _ => ZIO.succeed(Status.UnprocessableEntity.toResponse)`.

`onRejected` observes every decoder rejection exactly once and must complete
before the rejection response is produced. It cannot choose a different response;
log the stable low-cardinality `error.code`, not raw bodies, credentials, tokens,
or cookies. Transport and CSRF rejections never invoke `onValidation` or the
success callback. Successful decoding invokes neither rejection callback.
An observer defect or interruption aborts response production; `respond` does
not substitute a fallback HTTP response.

Callbacks are deferred until the returned effect runs. Environment requirements
and typed failures from either response callback remain in that effect; they are
not decoder rejections and do not cause another `onRejected` invocation. Map
application failures to HTTP responses at the appropriate application boundary.

## Validate Live, then trigger HTTP {#validate-live-then-trigger-http}

Sometimes a form should provide Live validation first, then perform an ordinary
POST only after the typed value is valid. Keep that transition explicit in the
model, and render an ordinary action, the Live bindings, and the conditional
trigger on the same form:

@:sourceRegion(documentation/fixtures/guides/src/httpforms/ValidatedLoginLiveView.scala, http-forms-trigger)

The change and submit bindings come from `LoginForm.Definition`. The form value
lives in the model, so `view` reads it through `model.map(_.form)`, and
`phx.triggerAction` follows `model.map(_.submitHttp)`.

The first submit is a Live event. When the next patch renders
`phx-trigger-action`, Phoenix hands the form back to the browser for normal HTTP
submission. The POST handler must decode, validate CSRF, validate the typed
fields, authenticate, and authorize again. Live validation is user feedback,
not a security boundary, and the HTTP request can be forged or changed.

Only set the trigger after the intended successful transition. Clear it on
invalid events or when returning to editable state. The trigger requires the
ordinary `action` and `method` supplied by `Form.http`; it is never added
automatically.

## Redirect HTTP flash into a Live route {#redirect-http-flash-into-a-live-route}

For a failed POST followed by a Live page, issue a 303 with a signed flash
cookie through the shared security value, as `invalidLogin` does above:

```scala fragment
val LoginError = FlashKind("error")

private def invalidLogin: UIO[Response] =
  security.flash.seeOther(
    Pages.login.location,
    LoginError -> "The sign-in request was invalid. Please try again."
  )
```

Render the same kind in the destination LiveView, as `LoginLiveView` does:

```scala fragment
flash(LoginError)(message => div(role := "alert", message))
```

`HttpFlash.seeOther` accepts a typed `LiveLocation`; use `seeOtherUnsafe` only
for an independently validated URL. Flash values are signed but not encrypted,
so never put secrets in them. The next successful disconnected Live render
transfers valid values into the signed Live session and expires the browser
cookie. This is browser consume-on-render behavior, not server-side replay
prevention for copied cookies.

Use fixed, user-safe messages in authentication flows. Log diagnostic context
separately so redirects do not disclose whether an account exists or expose
low-level decoder details.

## Related tasks {#related-tasks}

- Use [Authentication and sessions](authentication.md) for a complete login, cookie, protected mount, and logout workflow.
- Return to [Typed forms and validation](typed-forms-and-validation.md#continue-through-input) to define reusable schemas and Live feedback.
- Use [Lifecycle feedback and page state](flash-title-and-lifecycle-ux.md) for flash behavior during Live navigation.
- Continue to [Testing](testing.md#submit-ordinary-forms) to cover CSRF, body bounds, validation mapping, trigger-action, cookies, and redirects.
