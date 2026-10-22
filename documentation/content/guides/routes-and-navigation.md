{%
title = "Routes, parameters, and navigation"
description = "Decode typed path and query parameters, build checked destinations, and choose patch, navigate, replace, or redirect semantics."
order = 20
section = guides
group = "Routing and application structure"
%}

## Before you start {#prerequisites}

Start with a URL whose routed `LiveView` mounts and renders, and a named route
declaration that decodes it. The
[Quick start](../learn/quick-start.md#add-a-route) shows the minimal
router wiring assumed here.

## Name route declarations {#name-route-declarations}

A route declaration both decodes URLs and builds them. Keep declarations as named
values so mounting, links, and navigation effects cannot drift onto different
paths or query names:

@:sourceRegion(documentation/fixtures/guides/src/routing/Search.scala, routing-declarations)

Path codecs decode path segments. `query`, `queryOptional`, and schema-derived
query codecs decode query values. A routed view receives the final value through
@:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ rather than reading raw request strings.

A route value only describes a URL. Layouts, mount aspects, connected-turn
guards, and connected resources belong to the registration, so add them where
the router or a session receives the route, in any order, before `->`, `from`, or
`context`:

@:sourceRegion(documentation/fixtures/guides/src/layouts/Application.scala, layouts-route-layout)

The result registers the route but builds no links and takes no more path
segments. To share setup between several routes, put them in a
[named live session](live-sessions-and-mount-aspects.md#group-routes-in-a-named-session).

A routed LiveView must take the route's parameters: `Routes.search` mounts a
`LiveView.Routed` of `Option[String]`. An ordinary `LiveView` fits any route.
The compiler reports a view whose parameters do not match the route.

## Map parameters into domain types {#map-parameters-into-domain-types}

Use @:apiSymbol(def:scalive.LiveRoute.mapParams)`mapParams`@:@ when the codec-facing shape is not the shape the
application should use. Supply both directions:

@:sourceRegion(documentation/fixtures/guides/src/routing/Search.scala, routing-map-params)

The reverse function builds links and patches from the domain type, so every
route can encode the parameters it decodes. When decoding normalizes a value,
such as trimming a search term, encode the normalized value.

## Keep page state in the query {#keep-page-state-in-the-query}

A page whose filters, date, or mode belong in the URL, so a reload or a
bookmark keeps them, declares them as a case class with defaults and adds it
with `query[A]`:

@:sourceRegion(documentation/fixtures/guides/src/routing/PageState.scala, routing-page-state)

Each field is a query parameter named after it. A list is a repeated name, as
in `?people=a&people=b`, and an enum whose cases carry no data is its case name,
renamed with zio-schema's `@caseName`. A field with a default, an `Option`, or a
list takes that fallback when its value is missing, and also when it is
malformed, so a stale bookmark or a hand-edited URL still opens the page:
`?date=2026-13-01&mode=year` shows this week, and a garbled id is dropped from
the list. Scalive logs each value it ignores at debug level. A field without a
default is required, and a URL that lacks it or garbles it does not decode.

Encoding leaves out every value equal to its default, so
`CalendarRoutes.calendar.location(CalendarView())` is the bare `/calendar`, and
changing one filter adds only that parameter. An empty `Option` or list whose
default is not empty is written as a blank value, as in `?limit=`, which reads
back as empty.

A case class field is encoded under its name, which is how a page carries the
way back to another one. An editor opened from the calendar keeps the
calendar's query, typed, and builds the link back with the calendar's route
after changing what it needs:

@:sourceRegion(documentation/fixtures/guides/src/routing/PageState.scala, routing-page-state-return)

A new entry for the default calendar is the bare `/calendar/new`; from the month
view it is `/calendar/new?back.mode=month`. Because the editor rebuilds the link
from the calendar's route rather than echoing a URL, a crafted `back` cannot
send the visitor to another site.

## Mount and react to parameters {#mount-and-react-to-parameters}

`mount(params, ctx)` receives typed parameters for the disconnected render and
again for the fresh connected lifecycle. @:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@ runs after mount and after
each successful live patch:

@:sourceRegion(documentation/fixtures/guides/src/routing/Search.scala, routing-eventless-view)

Choose `LiveView.Routed.Eventless` when the view reacts to typed route parameters
but renders no server-handled browser messages. Choose `LiveView.Eventless` for
the same compile-time restriction on an unrouted view. Both remove the message
type and `handleMessage` rather than asking you to invent an impossible message;
switch to the ordinary `LiveView` or `LiveView.Routed` form when rendered
bindings need to produce application messages.

Keep parameter-derived state in one function so disconnected render, connected
mount, browser back and forward, and patches agree. Use `handleParams` to
canonicalize a successfully decoded URL only when the canonicalization cannot
loop.

A URL whose parameters don't decode never mounts the view. A page request for it
gets `400 Bad Request`, with the [error page](#answer-missing-pages-and-errors) if
the router has one, and Scalive logs the route and the decode error at debug
level; live navigation to it falls back to that page request. A live patch to it
calls
@:apiSymbol(def:scalive.LiveView.Routed.handleParamsDecodeError)`handleParamsDecodeError`@:@
instead, which crashes the LiveView unless you override it. Only a required
value fails that way: `queryOptional` decodes a missing or malformed value to
`None`, and a schema-derived query gives such a field its fallback, as
[Keep page state in the query](#keep-page-state-in-the-query) shows.

The documentation examples catalog is a real routed view. Its `topic` query
parameter controls a URL-addressable filter, and every topic link builds a typed
location before issuing a patch.

## Accept optional and untrusted query values {#accept-optional-and-untrusted-query-values}

A view that needs one optional query value, such as a login page that sends the
visitor back where they came from, is a routed view on a route that adds that
value. Declare the plain path as well, and build ordinary links from it: a route
value only describes a URL, so adding a query to it leaves the plain path's
`location` unchanged. Check the untrusted value once, in `mapParams`. Here a
`returnTo` that leaves the site decodes to `None`, so the login page cannot be
used for an open redirect:

@:sourceRegion(documentation/fixtures/guides/src/routing/QueryValues.scala, routing-return-to)

Register only the route with the query. It matches every URL the plain path
matches, so registering both fails assembly as a duplicate path:

@:sourceRegion(documentation/fixtures/guides/src/routing/QueryValues.scala, routing-query-registration)

The view receives the value in `mount`. It needs no `handleParams` when it
doesn't react to patches, and registering it with `context` or `from` works as
for any view, so a login page inside a named live session keeps its session
context:

@:sourceRegion(documentation/fixtures/guides/src/routing/QueryValues.scala, routing-return-to-view)

Links that send the visitor nowhere afterwards use the plain path, and links
that do use the query route:

@:sourceRegion(documentation/fixtures/guides/src/routing/QueryValues.scala, routing-return-to-links)

A view whose links are built elsewhere, such as a password reset link sent by
email, must answer every URL itself, including one with a missing or malformed
value. Declare the values as optional, so a malformed one decodes to `None`, and
parse them in `mapParams` into a type with a case for a link that cannot be
used:

@:sourceRegion(documentation/fixtures/guides/src/routing/QueryValues.scala, routing-reset-link)

Every such URL now mounts the view: a bare `/reset-password`, a garbled UUID,
a repeated `id`, or a missing code decodes to `ResetLink.Unusable`, which the
view renders as its own message. `Unusable` encodes as no query at all, so code
that sends the visitor back after a failed reset can use
`ResetRoutes.resetPassword.location(ResetLink.Unusable)`.

## Give a LiveView its services {#give-a-liveview-its-services}

`->` pairs a route with a LiveView that needs nothing from the application. When
the LiveView needs services, register the route with
@:apiSymbol(def:scalive.LiveRouteSetup.from)`from`@:@ and an effect that reads
them from the environment and builds the LiveView:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Main.scala, learn-thermostat-registration)

The route adds the services the effect needs to the application's ZIO
environment, so the server must provide a layer for each. Scalive runs the effect
for the disconnected render and for each connected mount. Routes inside a named
live session read its context through `context`, or through
`from((user: CurrentUser) => ...)` when they also need services, as in the
[authentication guide](authentication.md#authenticate-both-mount-phases).

[Loading data and live updates](../learn/loading-data-and-live-updates.md#give-the-page-its-service)
walks through a complete example, and the
[services guide](services-and-zlayer-injection.md) covers several services,
layers that own resources, and tests.

## Authorize route mounts {#authorize-route-mounts}

Use @:apiSymbol(class:scalive.LiveRouteMountAspect)`LiveRouteMountAspect`@:@ to
load and authorize a decoded destination before its LiveView is constructed. It
can consume identity established by the named live session and emits fresh route
context or a @:apiSymbol(enum:scalive.LiveRouteMountFailure)`LiveRouteMountFailure`@:@.
Cookies and original HTTP headers instead belong at the document or
session-aspect boundary.

A live patch keeps the current lifecycle mounted and calls `handleParams`; it
does not rerun the route aspect. If patched parameters can select another
protected resource, reauthorize in `handleParams` and at the sensitive operation,
or use navigation so the destination reruns route admission.

See the canonical [session-versus-route comparison](live-sessions-and-mount-aspects.md#choose-the-right-boundary)
and [route-context pipeline](live-sessions-and-mount-aspects.md#derive-route-context).
The [combined example](live-sessions-and-mount-aspects.md#combine-session-and-route-context)
shows typed destination parameters consuming admitted `CurrentUser` context.

For resource registration rather than authorization or context loading, use
`withConnectedResources` on the route or named live-session builder. Initializers
run after all admission/context checks and before connected page mount, with
session callbacks before route callbacks and declaration order within each level.
They are additive, do not change context or the page model, and fail through
`Task` as mount failures, not controlled authorization rejections. See
[route and session resources](connected-resources.md#route-and-session-resources).

## Answer missing pages and errors {#answer-missing-pages-and-errors}

Scalive answers some page requests with an error itself: `400` when the
parameters do not decode, `401`, `403`, or `404` from a
[route mount aspect](live-sessions-and-mount-aspects.md), and `500` when a
LiveView fails to mount or render. Without more setup, those responses carry only
the status text, such as `Not Found`.
Give them a page that looks like the rest of the site with a
@:apiSymbol(trait:scalive.LiveErrorPage)`LiveErrorPage`@:@:

@:sourceRegion(documentation/fixtures/guides/src/routing/ErrorPages.scala, routing-error-page)

Install it on the router:

@:sourceRegion(documentation/fixtures/guides/src/routing/ErrorPages.scala, routing-error-page-router)

Scalive renders it inside the router's root layout, so it keeps the site's
stylesheets and header, as a static document with no LiveView in it. The root
layout receives `()` as its parameters and context, because a failed request may
have none; read the request instead, for example for its language. Show nothing
about why the request failed: Scalive logs the reason. A response given with
`LiveRouteMountFailure.custom` or by a session mount aspect, such as a redirect
to the login page, is sent as given.
Override `renders` to keep some statuses bare, for example a `500` that should not
depend on the root layout.

With an error page installed, a `GET` request that no route of the application
matches also answers `404` with it. Routes added next to Scalive's, such as
static assets or health checks, still win, whatever their order, because ZIO HTTP
prefers literal and parameter segments to a catch-all.

When the record a URL names does not exist, fail `mount` or `handleParams` with
@:apiSymbol(class:scalive.LiveNotFound)`LiveNotFound`@:@:

@:sourceRegion(documentation/fixtures/guides/src/routing/ErrorPages.scala, routing-live-not-found)

A page request for `/patients/2` then answers `404` with the error page instead
of rendering the view, and the reason is logged at debug level. Live navigation
to it ends on the same page: as in Phoenix, the failed join tells the browser to
reload with the status, and the reloaded request answers it. That also covers a
record deleted between the page request and the join, without a reload loop. A
failure after a live patch crashes the LiveView, whose rejoin then fails the
same way.

## Serve HTTP responses from routes {#serve-http-responses-from-routes}

Some URLs answer with something other than a page: a PDF download, an image, a
CSV export. Declare them as routes like any other, and register an ordinary HTTP
`GET` handler with
@:apiSymbol(def:scalive.LiveRouteSetup.http)`http`@:@ instead of a LiveView:

@:sourceRegion(documentation/fixtures/guides/src/routing/HttpRoutes.scala, routing-http-route)

Register it where its LiveViews are. Inside a named live session, the session's
mount aspects admit each request first, exactly as for a page request, and the
handler reads the context they produce, so authentication is written once:

@:sourceRegion(documentation/fixtures/guides/src/routing/HttpRoutes.scala, routing-http-route-registration)

The handler receives the decoded parameters, the context, and the request, and
its `Response` is sent as is: it sets its own headers, such as the content type
and `Content-Disposition`. A route mount aspect added with `withMountAspect`
runs before it too. A failed admission answers as for a page: the session
aspect's response, such as a redirect to sign in, or the route aspect's `401`,
`403`, or `404`. Parameters that do not decode answer `400`, `LiveNotFound`
answers `404`, and any other failure answers `500`, each with the
[error page](#answer-missing-pages-and-errors) when the router has one. An HTTP
route and a LiveView cannot share a path.

Because it is a route value, it builds its own links, with typed path and query
parameters:

@:sourceRegion(documentation/fixtures/guides/src/routing/HttpRoutes.scala, routing-http-route-link)

Live navigation to an HTTP route, through `link.pushNavigate` for example, falls
back to an ordinary page request, which is also what starts a download. HTTP
routes answer only `GET`; a form that changes state posts to a ZIO HTTP route,
as [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) shows.

## Build locations from route declarations {#build-locations-from-route-declarations}

Call @:apiSymbol(def:scalive.LiveRoute.location)`location(params)`@:@ to create a
@:apiSymbol(class:scalive.LiveLocation)`LiveLocation`@:@:

@:sourceRegion(documentation/fixtures/guides/src/routing/Search.scala, routing-location)

`location` is concise for total codecs and domain invariants. Use
`locationEither` when encoding can legitimately fail and the caller should
recover. `LiveLocation` is nominal and cannot be created from an arbitrary
string, so changing the route declaration forces callers back through its
encoder.

Add an in-page fragment after building the typed path and query:

@:sourceRegion(documentation/fixtures/guides/src/routing/Search.scala, routing-fragment)

Pass percent-encoded URI-fragment syntax without the leading `#`; the method
validates but does not encode decoded text, so a space must already be `%20`.
Use `withFragment` when an invalid application-owned fragment is a programming
error. Use `withFragmentEither` when a fragment comes from a recoverable
boundary; it returns `Left(LiveLocation.EncodeError.Fragment(...))` for invalid
syntax rather than throwing `LiveLocation.EncodingException`.

The [typed documentation navigation example](../examples/navigation.md) uses
the site's actual search route declaration. Its complete executable source is:

@:sourceRegion(documentation/site/src/scalive/docs/examples/NavigationExample.scala, navigation-example)

## Choose patch or navigate {#choose-patch-or-navigate}

Patches keep the current routed LiveView mounted and call `handleParams` with
the new URL. Use them for filters, pagination, tabs, and other URL state owned by
the current view:

- @:apiSymbol(def:scalive.Navigation.pushPatch)`pushPatch`@:@ adds a browser-history entry;
- @:apiSymbol(def:scalive.Navigation.replacePatch)`replacePatch`@:@ replaces the current entry.

A routed LiveView patches by its own parameters, which its route encodes:

@:sourceRegion(documentation/fixtures/guides/src/routing/Search.scala, routing-patch-params)

The turn that requests a patch commits like any other, with the async tasks,
subscriptions, and component updates it starts, once the browser acknowledges
the new URL. `handleParams` then receives that turn's model.

A patch must stay on the current route. When the router would mount another
LiveView for the patched URL, the patch stops the LiveView, as in Phoenix;
navigate there instead.

Navigation changes the routed LiveView:

- @:apiSymbol(def:scalive.MountNavigation.pushNavigate)`pushNavigate`@:@ adds a browser-history entry;
- @:apiSymbol(def:scalive.MountNavigation.replaceNavigate)`replaceNavigate`@:@ replaces the current entry.

The new connected root lifecycle reruns its route/session resource initializers
on navigation or reconnect. A patch or ordinary message retains the current
resources without rerunning those callbacks. Initializers are not inherited by
nested LiveViews and never imply shared ownership across roots on one WebSocket
connection or within one named live session.

Rendered links expose the same four choices through `link`. Prefer links for
destinations the user can activate directly: they retain an ordinary `href` for
disconnected rendering, opening in a new tab, and no-JavaScript fallback. Use
`ctx.nav` when navigation is the result of validation or another server-side
transition.

Push versus replace is a history decision, not a rendering optimization. Use
push when Back should return to the previous state. Use replace for
canonicalization and transient intermediate URLs that should not remain in
history.

## Respect route and named live session boundaries {#respect-route-and-session-boundaries}

Live navigation is enhanced only when the destination is compatible with the
current named live session and root layout. Crossing an incompatible boundary
falls back to an ordinary HTTP request. The destination still needs a real route
and must render correctly before JavaScript connects.

Redirects are also typed destinations, but they end the current lifecycle rather
than requesting a live patch or navigation. Choose redirects for mount-time
authorization, canonical HTTP responses, and completed ordinary HTTP actions.

To leave the application, for a payment page or an identity provider, redirect
to an @:apiSymbol(class:scalive.ExternalUrl)`ExternalUrl`@:@: an absolute HTTP or
HTTPS URL with a host. `ExternalUrl.parse` checks a URL received as text, such as
a checkout URL returned by a payment service:

@:sourceRegion(documentation/fixtures/guides/src/routing/ExternalRedirect.scala, routing-external-redirect)

The browser loads the destination with a full page request; during the
disconnected render the response is a `303 See Other` there. Mount aspect and
connected-turn guard failures accept an `ExternalUrl` in `redirect` too.
`ExternalUrl` checks the URL's shape, not its host: when any part of it comes
from user input, check the host against an allowlist first.

Prefer APIs that accept `LiveLocation`. Runtime `Navigation` string methods such
as `ctx.nav.pushNavigateUnsafe` accept raw destinations and give up route typing,
refactoring, and encoding checks, but the connected runtime still rejects
literal or percent-encoded control characters and schemes other than HTTP or
HTTPS before recording the navigation.

Mount-admission failures are different:
`LiveRouteMountFailure.redirectUnsafe` and `LiveSessionMountFailure.redirectUnsafe`
accept an unchecked `URL`, not even checked to be HTTP(S), and perform no same-origin or local-path validation.
Use them only for trusted destinations. Never concatenate untrusted input into
any unsafe destination; parse it and enforce an application allowlist first.

Connected route tests can explicitly follow navigation within a named live
session and verify that the destination reruns its route mount aspects. This
protects the mount, but connected-turn guards and authorization immediately
before sensitive domain operations remain necessary when policy can change
while the route stays mounted. See
[Exercise actions and navigation](testing.md#exercise-actions-and-navigation).
That harness behavior is distinct from browser reconnect timing: only a real
browser can prove when the JavaScript client retries after transport loss or
falls back to an ordinary HTTP request at an incompatible boundary.

## Related tasks {#related-tasks}

- Group compatible routes with [Live sessions and mount aspects](live-sessions-and-mount-aspects.md#choose-the-right-boundary).
- Protect route groups with [Authentication and sessions](authentication.md#bind-admission-to-physical-connections).
- Confirm before browser-initiated navigation with [Guard unsaved changes](navigation-guards.md#prerequisites).
- Test parameter decoding, routed joins, and navigation with
  [Testing LiveViews](testing.md#cover-connected-behavior).
