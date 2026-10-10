{%
title = "Layouts"
description = "Render the HTML document with a root layout, wrap pages in shared navigation, and give one route its own chrome."
order = 21
section = guides
group = "Routing and application structure"
%}

## Before You Start {#prerequisites}

Start from an application with a few named routes, such as the
[quick start](../learn/quick-start.md) after
[URL state and navigation](../learn/url-state-and-navigation.md). This guide
adds a navigation bar to every page and a header to one group of pages.

Scalive has two kinds of layout:

- A @:apiSymbol(trait:scalive.LiveRootLayout)`LiveRootLayout`@:@ renders the
  HTML document: `<html>`, `<head>`, scripts, stylesheets, and `<body>`. Every
  page has exactly one.
- A @:apiSymbol(trait:scalive.LiveLayout)`LiveLayout`@:@ wraps the page inside
  the document, for example in a navigation bar. A page can have several.

Both are classes that extend a trait and implement one `view` method.

## Render The Document {#install-root-and-ordinary-layouts}

The quick start's root layout renders the document for every page:

@:sourceRegion(documentation/fixtures/quick-start/src/quickstart/RootLayout.scala, quick-start-root-layout)

The class takes the loaded assets in its constructor, so it can render their
versioned URLs. Its two type parameters are the route parameter type and the
mount context type the layout reads. A layout shared by every route reads
neither, so both are `Any`.

`view` is generic in `Msg` because the same layout wraps pages with different
message types. `content` is the page, and the layout places it in the body.
`pageTitle` is the page's
@:apiSymbol(def:scalive.LiveView.pageTitle)`pageTitle`@:@ at the first render.
Pass it to `liveTitle` so later title changes reach the browser; the `default`
applies when the page has no title.

Scalive calls a root layout's `view` only for the first HTTP response. Live
updates patch the page inside the document and never render the document again,
so `ctx` holds plain values: the route parameters, the request, the URL, and
the mount context.

Install the root layout on the router with `withRootLayout`, as the quick
start's `Main` does.

## Wrap Every Page In Shared Navigation {#wrap-pages-in-shared-navigation}

An ordinary layout renders the markup that pages share inside `<body>`. This
one adds a navigation bar and marks the link to the current section:

@:sourceRegion(documentation/fixtures/guides/src/layouts/AppLayout.scala, layouts-app-layout)

Install it on the router with `withLayout`, next to the root layout:

@:sourceRegion(documentation/fixtures/guides/src/layouts/Application.scala, layouts-application)

Scalive calls an ordinary layout's `view` once per page lifecycle, like a
LiveView's `view`. Its `ctx.params`, `ctx.request`, and `ctx.currentUrl` are
signals, so the layout follows live navigation and patches without being
rebuilt. Here `aria-current` moves to **Projects** when the visitor opens a
project, while the navigation bar itself stays in place.

A layout cannot send messages of its own: its `view` must accept content of any
message type, so it has no message type to send. Use links, as above,
[nested LiveViews](nested-liveviews.md#prerequisites), or
[browser hooks](browser-integration.md#prerequisites) for interactive chrome.

## Give One Route Its Own Layout {#give-one-route-its-own-layout}

Install a layout on a route builder to wrap only that route. The registration
above adds a header to each project page:

@:sourceRegion(documentation/fixtures/guides/src/layouts/Application.scala, layouts-route-layout)

A route layout can read the route's decoded parameters. `ProjectLayout`
declares `String`, the type of the `project` path segment, so `ctx.params` is a
`Signal[String]`:

@:sourceRegion(documentation/fixtures/guides/src/layouts/ProjectLayout.scala, layouts-project-layout)

The compiler checks that a route layout's parameter type matches the route.
Install route layouts before `.params` or `.query`. When a route prefix with a
layout is extended with `/`, the layout keeps receiving the parameters of the
prefix it was installed on.

## Know Where Each Layout Applies {#know-where-each-layout-applies}

Layouts can be installed at three levels:

| Level | Install with | Wraps |
| --- | --- | --- |
| Router | `Live.router.withRootLayout(root).withLayout(layout)` | Every route |
| Named live session | `Live.session(name).withRootLayout(root).withLayout(layout)` | The session's routes |
| Route | `route.withRootLayout(root).withLayout(layout)` | One route |

Ordinary layouts nest: router layouts are outermost, then session layouts, then
route layouts. Within one level, they nest in installation order. Root layouts
do not nest: a route's root layout replaces its session's, which replaces the
router's. The router accepts one ordinary layout; sessions and routes accept
several. [Live sessions and mount aspects](live-sessions-and-mount-aspects.md#group-routes-in-a-named-session)
shows how to group routes in a named live session.

## Decide When Pages Share A Document {#decide-when-pages-share-a-document}

Live navigation replaces the page but keeps the document, including its
`<head>`, scripts, and `<body>` attributes. That is only correct when the
destination would render the same document. Scalive compares the two root
layouts' @:apiSymbol(def:scalive.LiveRootLayout.key)`key`@:@ values. When they
differ, the browser loads the destination with a full page request instead.

The default key is the root layout's class name. Routes that use the same root
layout class therefore share the document, and routes with different classes
do not. Override `key` when one class renders documents that cannot replace each
other. This root layout renders a different `lang` per context, so it includes
the language in its key:

@:sourceRegion(documentation/site/src/scalive/docs/examples/LayoutContextExample.scala, document-root-key)

Navigating from a French page to a German page then reloads the document,
instead of keeping `<html lang="fr">`. Equal keys do not bypass named live
session boundaries: navigation between sessions always loads a new document.

## Reuse Layouts Across Contexts {#reuse-layouts-across-contexts}

A layout installed on a named live session or behind a mount aspect can read the
typed context they produce, such as the signed-in user. Declare the context
type the layout needs as its second type parameter.

A shared layout should depend on a small context, not on a session's full
context. Use @:apiSymbol(def:scalive.LiveLayout.forContext)`forContext`@:@ to
adapt it where it is installed:

```scala fragment
// DocumentRoot reads DocumentContext; AccountContext contains .document.
val accountRoot = DocumentRoot.forContext[AccountContext](_.document)
```

When the selection is local to installation, pass the selector as a second
argument instead:

```scala fragment
Live.session("account")
  .withMountAspect(accountContext)
  .withRootLayout(DocumentRoot, _.document)
  .withLayout(DocumentLayout, _.document)
```

The builder supplies the selector's input type. These two-argument overloads
exist on named live session builders, including admitted ones, and on route
builders after a mount aspect, before `.params` or `.query`. The router accepts
only layouts whose context type is `Any`; install a context-dependent root
layout on a session or route.

Selection uses the context available **where the layout is installed**.
Appending another mount aspect afterward does not make the layout read the
final accumulated context. Only the layout's context value is adapted;
parameters, request, URL, content, and page title are unchanged. Selectors must
be pure and deterministic: a root layout applies its selector separately for
`key` and `view`. The identity root, the default when no root layout is
installed, stays unchanged and does not evaluate the selector.

This compiled example shares one document across public and account contexts,
keeps a layout through a later aspect, and overrides the root for one route.
Its fixed context aspects demonstrate composition, not authentication:

@:sourceRegion(documentation/site/src/scalive/docs/examples/LayoutContextExample.scala, shared-document-shell)

## Related Tasks {#related-tasks}

- Group routes and derive typed context with [Live sessions and mount aspects](live-sessions-and-mount-aspects.md#prerequisites).
- Set the page title and show connection status with [Lifecycle feedback and page state](flash-title-and-lifecycle-ux.md#prerequisites).
- Add stylesheets and scripts to the document with [Static assets and client setup](static-assets-and-client-setup.md#render-tracked-tags).
