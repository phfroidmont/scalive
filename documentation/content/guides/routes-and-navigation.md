{%
title = "Routes, parameters, and navigation"
description = "Decode typed path and query parameters, build checked destinations, and choose patch, navigate, replace, or redirect semantics."
order = 20
section = guides
group = "Routing and application structure"
%}

## Prerequisites {#prerequisites}

Start with a `LiveView` that mounts and renders. The
[Quick start](../learn/quick-start.md#add-routes-and-layout) shows the minimal
router and layout wiring assumed here.

## Name Route Declarations {#name-route-declarations}

A route declaration is both an inbound decoder and, when every transformation is
reversible, an outbound location builder. Keep important declarations as named
values so mounting, links, and navigation effects cannot drift onto different
paths or query names:

```scala
object Routes:
  val search =
    (live / "search").queryOptional[String]("q")

val routes = Live.router(
  Routes.search -> SearchLiveView()
)
```

Path codecs decode path segments. `query`, `queryOptional`, and schema-derived
query codecs decode query values. A routed view receives the final value through
@:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ rather than reading raw request strings.

## Map Parameters Into Domain Types {#map-parameters-into-domain-types}

Use @:apiSymbol(def:scalive.LiveEncodableRouteParamsBuilder.mapParams)`mapParams`@:@ when the codec-facing shape is not the shape the
application should use. Supply both directions:

```scala
final case class SearchParams(query: Option[SearchTerm])

val search =
  (live / "search")
    .queryOptional[String]("q")
    .mapParams(raw => SearchParams(raw.flatMap(SearchTerm.from)))(
      params => params.query.map(_.value)
    )
```

The reverse function preserves outbound location construction. If a
transformation is genuinely irreversible, use `mapParamsDecodeOnly`; the
resulting builder remains mountable but cannot call `location`. That compile-time
restriction prevents a route from claiming it can safely reconstruct information
that it discarded.

## Mount And React To Parameters {#mount-and-react-to-parameters}

`mount(params, ctx)` receives typed parameters for the disconnected render and
again for the fresh connected lifecycle. @:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@ runs after mount and after
each successful live patch:

```scala
final class SearchLiveView
    extends LiveView.Routed.Eventless[Model, Option[String]]:

  def mount(params: Option[String], ctx: MountContext) =
    ZIO.succeed(search(params))

  override def handleParams(model: Model, params: Option[String], url: URL, ctx: ParamsContext) =
    ZIO.succeed(search(params))
```

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

## Build Locations From Route Declarations {#build-locations-from-route-declarations}

Call @:apiSymbol(def:scalive.LiveEncodableRouteParamsBuilder.location)`location(params)`@:@ to create a
@:apiSymbol(class:scalive.LiveLocation)`LiveLocation`@:@:

```scala
val destination = Routes.search.location(Some("LiveView"))
// destination.href == "/search?q=LiveView"
```

`location` is concise for total codecs and domain invariants. Use
`locationEither` when encoding can legitimately fail and the caller should
recover. `LiveLocation` is nominal and cannot be created from an arbitrary
string, so changing the route declaration forces callers back through its
encoder.

The [typed documentation navigation example](../examples/navigation.md) uses
the site's actual search route declaration. Its complete executable source is:

@:sourceRegion(documentation/site/src/scalive/docs/examples/NavigationExample.scala, navigation-example)

## Choose Patch Or Navigate {#choose-patch-or-navigate}

Patches keep the current routed LiveView mounted and call `handleParams` with
the new URL. Use them for filters, pagination, tabs, and other URL state owned by
the current view:

- @:apiSymbol(def:scalive.Navigation.pushPatch)`pushPatch`@:@ adds a browser-history entry;
- @:apiSymbol(def:scalive.Navigation.replacePatch)`replacePatch`@:@ replaces the current entry.

Navigation changes the routed LiveView:

- @:apiSymbol(def:scalive.MountNavigation.pushNavigate)`pushNavigate`@:@ adds a browser-history entry;
- @:apiSymbol(def:scalive.MountNavigation.replaceNavigate)`replaceNavigate`@:@ replaces the current entry.

Rendered links expose the same four choices through `link`. Prefer links for
destinations the user can activate directly: they retain an ordinary `href` for
disconnected rendering, opening in a new tab, and no-JavaScript fallback. Use
`ctx.nav` when navigation is the result of validation or another server-side
transition.

Push versus replace is a history decision, not a rendering optimization. Use
push when Back should return to the previous state. Use replace for
canonicalization and transient intermediate URLs that should not remain in
history.

## Respect Route And Session Boundaries {#respect-route-and-session-boundaries}

Live navigation is enhanced only when the destination is compatible with the
current live session and root layout. Crossing an incompatible boundary falls
back to an ordinary HTTP request. The destination still needs a real route and
must render correctly before JavaScript connects.

Redirects are also typed destinations, but they end the current lifecycle rather
than requesting a live patch or navigation. Choose redirects for mount-time
authorization, canonical HTTP responses, and completed ordinary HTTP actions.

Safe APIs accept `LiveLocation`. Explicit `Unsafe` methods accept raw strings for
external URLs, dead routes, or deliberately query-only patches. Keep those calls
at a narrow boundary: raw strings give up route refactoring and encoding checks.

## Related Tasks {#related-tasks}

- Group compatible routes with [Layouts, live sessions, and mount aspects](layouts-sessions-and-mount-aspects.md#prerequisites).
- Protect route groups with [Authentication and sessions](authentication.md#prerequisites).
- Test parameter decoding and initial routes with [Testing LiveViews](testing.md#test-disconnected-rendering).
