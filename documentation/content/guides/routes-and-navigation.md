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
[Quick start](../learn/quick-start.md#add-routes-and-layout) shows the minimal
router and layout wiring assumed here.

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

The documentation examples catalog is a real routed view. Its `topic` query
parameter controls a URL-addressable filter, and every topic link builds a typed
location before issuing a patch.

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

Prefer APIs that accept `LiveLocation`. Runtime `Navigation` string methods such
as `ctx.nav.pushNavigateUnsafe` accept raw destinations and give up route typing,
refactoring, and encoding checks, but the connected runtime still rejects
literal or percent-encoded control characters and schemes other than HTTP or
HTTPS before recording the navigation.

Mount-admission failures are different:
`LiveRouteMountFailure.redirectUnsafe` and `LiveSessionMountFailure.redirectUnsafe`
accept an unchecked `URL` and perform no same-origin or local-path validation.
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
