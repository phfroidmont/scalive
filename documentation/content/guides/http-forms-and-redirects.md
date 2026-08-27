{%
title = "Ordinary HTTP forms and redirects"
description = "Render checked GET and POST actions, decode bounded CSRF-protected bodies, hand Live validation back to HTTP, and redirect flash into Live routes."
order = 12
section = guides
group = "Interfaces and input"
%}

## Before You Start {#prerequisites}

Start with a typed form or ordinary HTML controls, a GET or POST `RoutePattern`
that can receive them, and one validated `ZioHttpConfig` shared by the Live and
sibling ZIO HTTP routes.

## Choose Ordinary HTTP Deliberately {#choose-ordinary-http-deliberately}

Use an ordinary browser form when submission should have normal HTTP semantics:
changing cookies, crossing an authentication boundary, downloading a response,
leaving LiveView, or applying Post/Redirect/Get. Keep purely interactive
validation and in-page updates as Live events.

Define the target as a ZIO HTTP `RoutePattern` and derive a checked
@:apiSymbol(class:scalive.FormAction)`FormAction`@:@:

```scala
val SearchRoute  = Method.GET / "search"
val SessionRoute = Method.POST / "session"

val searchAction  = FormAction.from(SearchRoute)
val sessionAction = FormAction.from(SessionRoute)
```

Checked actions accept only GET and POST, format typed path parameters, URL
encode the resulting path, and mark POST for CSRF injection. `FormAction.from`
throws `FormAction.EncodingException` for unsupported methods or path encoding
failure; use `FormAction.fromEither` when dynamic parameters should keep
`EncodeError` in an `Either`.

`FormAction.unsafe(method, href)` is an explicit escape hatch for an already
encoded or external URL. It performs no route, origin, or path validation and
never requests Scalive CSRF injection, even for POST. Prefer checked actions for
application routes.

## Render A Reusable HTTP Form {#render-a-reusable-http-form}

Use the rooted form's `http` method when controls and codecs already come from a
`FormDefinition`:

```scala
val loginForm = LoginForm.Definition.initial()
val email     = loginForm.field(LoginForm.Email)
val password  = loginForm.field(LoginForm.Password)

loginForm.http(FormAction.from(SessionRoute))(
  idAttr := "login-form",
  label(forId := email.id, "Email"),
  email.email(autoComplete := "username", required := true),
  label(forId := password.id, "Password"),
  password.password(autoComplete := "current-password", required := true),
  button(typ := "submit", "Sign in")
)
```

Use @:apiSymbol(def:scalive.Form.http)`Form.http`@:@ directly when no form model
is needed:

```scala
Form.http(FormAction.from(Method.POST / "session" / "reset"))(
  button(typ := "submit", "Sign out")
)
```

`Form.http` owns the `action` and `method` attributes and rejects direct
overrides. It does not add `phx-change`, `phx-submit`, or `phx-trigger-action`.
The rooted variant does not render controls automatically; pass them as
modifiers.

Both checked GET and POST actions render with native browser methods. GET is
appropriate only for safe, idempotent retrieval and puts successful controls in
the URL query. POST is appropriate for state changes. The current
`HttpFormDecoder` decodes a URL-encoded request body and always validates CSRF,
so it is intended for checked POST handlers, not GET query decoding. Decode and
bound GET query parameters at the route boundary with the query API chosen by
your application.

## Share Security And Inject CSRF {#share-security-and-inject-csrf}

Create one validated transport config and share it between Live transport routes
and the HTTP route owner:

```scala
val transportConfig = ZioHttpConfig(
  signingSecret = config.signingSecret,
  sessionMaxAge = java.time.Duration.ofMinutes(30),
  secureCookie = config.publicHttps
).fold(error => throw IllegalArgumentException(error.toString), identity)

val security = LiveSecurity(transportConfig)
val application = Live.router(liveRoutes*)
val routes = ZioHttp.routes(application, security) ++ httpRoutes(security)
```

A checked POST rendered through `Form.http` is marked for Scalive's hidden CSRF
token injection. Injection occurs only while rendering through transport routes
configured with that validated config. The matching browser cookie and submitted token are
validated later with `security.csrf`, derived from the same validated config.

Checked GET actions and all unsafe actions are not marked for token injection.
Do not treat `Form.http` alone as request protection: the HTTP handler must still
validate CSRF for state-changing requests. Set `secureCookie = true`
when the browser-facing origin uses HTTPS; Scalive does not infer that from
forwarding headers.

## Decode A Bounded POST Body {#decode-a-bounded-post-body}

Build one reusable decoder from the same typed codec used by the form:

```scala
private val FormMaxBytes = 4096L

private val loginDecoder = HttpFormDecoder.urlEncoded(
  LoginForm.Definition.codec,
  maxBytes = FormMaxBytes,
  csrf = security.csrf
)
```

@:apiSymbol(def:scalive.HttpFormDecoder.urlEncoded)`urlEncoded`@:@ accepts only
`application/x-www-form-urlencoded`, honors its declared charset with UTF-8 as
the default, and reads at most `maxBytes + 1` bytes to detect overflow. It
preserves duplicate fields in wire order, validates CSRF, and only then runs the
application `FormCodec`.

The decoder does not check the request method or route, authenticate or
authorize a user, rate-limit requests, sanitize strings, or make an unsafe
target secure. Keep those checks in the route handler and service layer. Choose
a route-specific bound; the maximum applies to the encoded body, not decoded
string sizes or downstream work.

## Map Decoder Errors To Responses {#map-decoder-errors-to-responses}

ZIO HTTP handlers commonly return `UIO[Response]`, an effect with no typed
failure, or a wider `ZIO[R, E, Response]` when services and failures remain in
the environment and error channels. `HttpFormDecoder.respond` lets the success
callback keep that wider effect while mapping form rejection to responses.

Call `decode(request)` when the handler needs to pattern match the typed error
channel directly. For the common case, `respond` runs an effect only after every
stage succeeds:

```scala
private def createSession(request: Request): UIO[Response] =
  loginDecoder.respond(
    request,
    onValidation = _ => Status.UnprocessableEntity.toResponse,
    onRejected = error => ZIO.logWarning(s"login form rejected code=${error.code}")
  ) { credentials =>
    sessions.create(credentials).map { session =>
      Dashboard.location.seeOther.addCookie(
        security.cookies.make("session", session.token)
      )
    }
  }
```

Failures remain distinct:

- `Error.Body` covers oversized or unreadable bodies.
- `Error.Representation` covers content type, URL encoding, and unsupported field representations.
- `Error.Csrf` covers missing or invalid browser-bound tokens.
- `Error.Validation` carries the typed form's `FormErrors`.

The default mapping is 413 for oversized bodies, 415 for invalid content type,
400 for other body or representation failures, and 403 for CSRF rejection.
`onValidation` chooses the application response for validation errors.
`onRejected` observes all failures but cannot replace the response; log the
stable low-cardinality `error.code`, not raw bodies, credentials, tokens, or
cookies.

## Validate Live, Then Trigger HTTP {#validate-live-then-trigger-http}

Sometimes a form should provide Live validation first, then perform an ordinary
POST only after the typed value is valid. Keep that transition explicit in the
model:

```scala
final case class Model(form: LoginForm.Definition.Form, submitHttp: Boolean = false)

case Msg.Submit(event) =>
  event.value match
    case Right(_) =>
      ZIO.succeed(model.copy(
        form = LoginForm.Definition.from(event),
        submitHttp = true
      ))
    case Left(_) =>
      ZIO.succeed(model.copy(
        form = LoginForm.Definition.from(event),
        submitHttp = false
      ))
```

Render an ordinary action, a Live submit binding, and the conditional trigger on
the same form:

```scala
model.form.http(FormAction.from(SessionRoute))(
  idAttr := "login-form",
  model.form.onSubmit(Msg.Submit(_)),
  model.form.triggerHttpSubmitWhen(model.submitHttp),
  // controls
  button(typ := "submit", submission.replaceTextWith("Signing in..."), "Sign in")
)
```

The first submit is a Live event. When the next patch renders
`phx-trigger-action`, Phoenix hands the form back to the browser for normal HTTP
submission. The POST handler must decode, validate CSRF, validate the typed
fields, authenticate, and authorize again. Live validation is user feedback,
not a security boundary, and the HTTP request can be forged or changed.

Only set the trigger after the intended successful transition. Clear it on
invalid events or when returning to editable state. The trigger requires the
ordinary `action` and `method` supplied by `Form.http`; it is never added
automatically.

## Redirect HTTP Flash Into A Live Route {#redirect-http-flash-into-a-live-route}

For a failed POST followed by a Live page, issue a 303 with a signed flash
cookie through the shared security value:

```scala
val LoginError = FlashKind("error")

private def invalidLogin: Response =
  security.flash.seeOther(
    Login.location,
    LoginError -> "The sign-in request was invalid. Please try again."
  )
```

Render the same kind in the destination LiveView:

```scala
flash(LoginError)(message =>
  div(role := "alert", message)
)
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

## Related Tasks {#related-tasks}

- Use [Authentication and sessions](authentication.md) for a complete login, cookie, protected mount, and logout workflow.
- Use [Typed forms and validation](typed-forms-and-validation.md) to define reusable codecs and Live feedback.
- Use [Lifecycle feedback and page state](flash-title-and-lifecycle-ux.md) for flash behavior during Live navigation.
- Use [Testing](testing.md) to cover CSRF, body bounds, validation mapping, trigger-action, cookies, and redirects.
