{%
title = "Typed documentation navigation"
description = "Named route builders encode real documentation search parameters into checked LiveLocation values."
order = 15
section = examples
%}

A LiveView that builds a checked destination from the documentation site's own
search route, then navigates to it. Both navigation links receive the same
@:apiSymbol(class:scalive.LiveLocation)`LiveLocation`@:@; they differ only in
browser history.

## Try It {#try-it}

1. Click **Streams**. **Typed query** shows `streams`, and **Encoded
   destination** shows `/search?q=streams`.
2. Click **Open search**. This leaves the example for the documentation search,
   with "streams" searched. Use the browser's Back button to return here.
3. Click **Open and replace** instead. The search replaces this page in the
   browser history, so Back skips this page and returns to the one before it.

@:example(navigation)

## How It Works {#how-it-works}

- `SearchRouteBuilder` is the site's real route declaration,
  `(live / "search").queryOptional[String]("q")`. The example builds each
  destination with its `location(Some(query))`, so links cannot drift from the
  route.
- `link.pushNavigate` and `link.replaceNavigate` render ordinary links with an
  `href`, which work before JavaScript connects and open in a new tab. Once
  connected, they navigate live and add or replace a history entry.

## Related Guides {#related-guides}

- [Routes, parameters, and navigation](../guides/routes-and-navigation.md#build-locations-from-route-declarations)
  explains route declarations, locations, and patch or navigate.
