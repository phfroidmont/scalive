{%
title = "Embed independent nested LiveViews"
description = "Choose a nested LiveView when a child needs its own socket lifecycle, resources, crash policy, or sticky navigation identity."
order = 23
section = guides
group = "Routing and application structure"
%}

## Prerequisites {#prerequisites}

Start with the root lifecycle described in
[Lifecycle and connection behavior](../learn/lifecycle-and-connection-behavior.md),
then read
[Stateful components and communication](components-and-communication.md).

## Choose A Component Or A Nested LiveView {#choose-component-or-nested-liveview}

Use a `LiveComponent` when a child needs isolated model state but should remain
part of its owner's render, flash, navigation, and socket lifecycle. Components
also provide typed props and outputs for explicit parent-child communication.

Nested LiveViews solve a stronger ownership problem than components and should
not be the default way to split markup.

Use a nested `LiveView` when the embedded UI needs a full independent LiveView
lifecycle: its own model and messages, socket, flash, components, hooks, uploads,
streams, async tasks, and subscriptions. Typical cases are a persistent player,
chat panel, or independently failing embedded application. A nested LiveView
does not have component props or an output mapper; pass construction data into
the child definition and coordinate longer-lived shared data through an
application service.

Use an ordinary render function when neither isolated state nor a separate
lifecycle is needed.

## Render A Stable Child {#render-a-stable-child}

Embed the child with @:apiSymbol(def:scalive.liveView)`liveView`@:@:

```scala
def render(model: Model) =
  section(
    liveView(
      id = "support-chat",
      liveView = SupportChatLiveView(conversationId),
      sticky = true
    )
  )
```

The `id` is application identity and determines the child topic. Keep it stable
across parent rerenders, and derive repeated IDs from stable domain identity
rather than list position. Reusing an ID preserves the connected registration
and session; changing it creates a different child lifecycle.

Every nested LiveView ID in one parent render must be unique, even when the
children use different LiveView classes or are emitted through components and
streams. Rendering the same ID twice fails the parent render with
`IllegalArgumentException` and a `Duplicate nested LiveView id` message. Two
instances of the same child type are valid when their IDs differ.

The child argument is by-name and is evaluated when a disconnected or connected
child lifecycle starts. Construct the intended child there; do not depend on
side effects performed by the parent's render.

## Design For Two Mounts {#design-for-two-mounts}

During the initial HTTP request, Scalive mounts and renders the nested child so
its content is present in disconnected HTML. When LiveSocket connects, the
child joins separately and mounts again with `ctx.connected == true`. The
disconnected model, dynamic hooks, and resource registrations do not transfer
to the connected child.

Apply the same mount discipline as a root LiveView:

- initialize deterministic render state in both phases;
- start async tasks, subscriptions, and client effects only for the connected
  lifecycle;
- configure uploads and streams in each lifecycle that renders their handles;
- store durable state outside the socket when it must survive reconnects.

The child inherits the current page URL when it joins without an explicit URL,
but it is not independently routed by its placement. Routed patches still
belong to the browser's current root route.

## Keep State And Resources Independent {#keep-state-and-resources-independent}

Parent rerenders and patches do not reset a child whose ID remains stable. The
child handles its own events serially and owns its component tree and managed
resources. Equal async, subscription, stream, or upload keys in parent and child
therefore do not refer to the same registration.

This isolation also defines communication. Parent event bindings cannot target
components inside the child's socket, and the child has no typed component-style
output channel to its parent. Prefer a shared service, persisted state, or an
explicit browser-level interaction over coupling the two sockets through
runtime IDs.

## Choose Sticky Navigation Deliberately {#choose-sticky-navigation}

The default `sticky = false` makes the parent own the child. Leaving the parent
shuts down the child socket and its descendants. Use `sticky = true` only when
the same child identity should survive parent teardown during compatible live
navigation. On the destination render, the client can rejoin the existing
sticky socket, preserving its model and lifecycle-owned resources instead of
mounting a replacement.

Sticky is a live-navigation policy, not persistence. A full redirect, reload,
transport loss, incompatible live-session boundary, or process failure can
still require a fresh mount. A sticky child is detached from normal parent
ownership while navigating, so reserve it for UI that genuinely spans pages;
do not enable it as a general remount optimization.

## Set The Crash Boundary {#set-the-crash-boundary}

By default `linkParentOnCrash = false`. A child mount or connected lifecycle can
fail without crashing the parent socket; the failed child receives the normal
generic join or `phx_error` behavior and may rejoin independently.

Set `linkParentOnCrash = true` when the parent is invalid without that child:

```scala
liveView(
  id = "required-editor",
  liveView = RequiredEditorLiveView(documentId),
  linkParentOnCrash = true
)
```

Then a connected child join failure or later child crash also crashes the
parent, causing parent error and rejoin handling. This is failure propagation,
not supervision or recovery. Keep the default for optional panels and enable
the link only when restarting the whole page lifecycle is safer than leaving
the parent alive.

## Respect Navigation, Flash, And Title Boundaries {#respect-ui-boundaries}

A child may call its own `ctx.nav.pushNavigate`, `pushPatch`, replace variants,
or redirects. Those commands affect the containing browser, not only the child
subtree. A live patch is subsequently handled by the current routed root
LiveView, so keep URL ownership in the root even when a child initiates the
action.

Flash belongs to the socket that writes it. A child's `ctx.flash` and
`flash(kind)` rendering do not expose that message in the parent flash store.
Navigation may still carry the navigation-triggering lifecycle's flash according
to the normal flash rules.

Only the root LiveView owns the document title. Overriding `pageTitle` in a
nested child does not update `document.title`; render a local heading or send a
domain update through an application boundary when the root title must change.

## Clean Up Removed And Failed Children {#clean-up-children}

Stop rendering a non-sticky child when its UI is no longer needed. Once the
client leaves that child, Scalive shuts down its socket and lifecycle-owned
async tasks, subscriptions, uploads, streams, components, hooks, and nested
descendants. Leaving the parent performs the same recursive cleanup for its
non-sticky children. Sticky children intentionally survive that parent leave.

Do not retain child runtime IDs or handles in parent state. On a crash, assume
the failed socket's in-memory model and resources are gone and rebuild them in
the next connected mount from durable state. Test removal, parent navigation,
child failure, and rejoin separately; they exercise different ownership paths.

## Related Tasks {#related-tasks}

- Use [Stateful components and communication](components-and-communication.md)
  when isolated state does not require a separate socket.
- Review [Routes, parameters, and navigation](routes-and-navigation.md) before a
  child initiates page navigation.
- Design managed resources with
  [Asynchronous work and subscriptions](async-work-and-subscriptions.md) and
  [File uploads](uploads-and-consumption.md).
- Apply root title and flash rules from
  [Lifecycle feedback and page state](flash-title-and-lifecycle-ux.md).
- Verify both phases with [Testing LiveViews](testing.md).
