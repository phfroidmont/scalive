{%
title = "URL state and navigation"
description = "Decode typed URL state, build checked destinations, and choose how a LiveView changes the browser URL."
order = 6
section = learn
%}

## Declare the route once {#declare-the-route-once}

URLs are an application boundary as well as navigation history. Declare a route
once, let Scalive decode its parameters, and use that same declaration to build
destinations. Add a search route with an optional `q` query parameter to
`Routes.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Routes.scala, learn-search-route)

Create `app/src/quickstart/SearchLiveView.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SearchLiveView.scala, learn-search)

Register it in `Main.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Main.scala, learn-search-registration)

`Routes.search` is typed and named. It decodes inbound query state to
`Option[String]`, which is why the view extends
@:apiSymbol(trait:scalive.LiveView.Routed)`LiveView.Routed`@:@ with that
parameter type. `location` encodes the same type into a checked
@:apiSymbol(class:scalive.LiveLocation)`LiveLocation`@:@. A `LiveLocation`
cannot be assembled from an arbitrary string, so route changes remain
compiler-visible at call sites.

Run `mill app.run` and open `http://localhost:8080/search?q=zio`. Select
**Search for Scala**: the address bar and the page both change without a reload.
Select Back to return to `zio`, then **Clear** to remove the query.

## Treat decoded parameters as untrusted {#treat-decoded-parameters-as-untrusted}

Typed decoding establishes shape, not trust. Route parameters still came from a
browser. Apply domain validation and bounds, and perform authorization against
server-owned identity and data. The example trims and bounds the query before it
enters the model; a decoded identifier would still need an authorized lookup.

@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ receives decoded parameters during each disconnected and connected mount.
@:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@ receives them after mount and after every successful patch, which keeps
Back, Forward, and in-page URL changes synchronized with the model.

## Choose the navigation semantics {#choose-the-navigation-semantics}

Use a rendered `link` when the user can activate a destination directly. It
keeps a real `href`, so opening a new tab and disconnected navigation still
work. Use `ctx.nav` when a server-side transition decides the destination, as
the clear action does after handling a message.

- **Patch** keeps the current routed LiveView mounted and calls `handleParams`.
  It suits filters, tabs, and pagination owned by that view.
- **Navigate** changes to another routed LiveView while preserving live
  navigation where route and session boundaries permit it.
- **Redirect** ends the current lifecycle. Use it for mount-time decisions or
  completed ordinary HTTP flows rather than in-view URL state.
- **Push** adds a history entry, so Back returns to the previous state.
- **Replace** overwrites the current history entry, which suits
  canonicalization or transient state.

Rendered links and `ctx.nav` provide the corresponding patch and navigate
choices. Prefer their checked `LiveLocation` APIs; raw-string `Unsafe` variants
give up route and encoding checks.

For parameter mappings, session boundaries, redirects, and every navigation
variant, continue with the full
[routes and navigation guide](../guides/routes-and-navigation.md). Next,
[Lifecycle, state ownership, and reconnects](lifecycle-and-connection-behavior.md)
explains what happens to each page's state when the connection drops.
