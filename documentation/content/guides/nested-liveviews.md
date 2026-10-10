{%
title = "Nested LiveViews"
description = "Choose a nested LiveView when a child needs its own lifecycle, resources, crash policy, or sticky navigation identity."
order = 31
section = guides
group = "State and components"
%}

## Before you start {#prerequisites}

Start with a root LiveView that renders in both disconnected and connected
phases, as described in
[Lifecycle and connection behavior](../learn/lifecycle-and-connection-behavior.md).
If the child only needs isolated state, compare
[stateful components](components-and-communication.md) before choosing a nested
view.

## Choose a component or a nested LiveView {#choose-component-or-nested-liveview}

Use a `LiveComponent` when a child needs isolated model state but should remain
part of its owner's render, flash, navigation, and lifecycle. Components
also provide typed props and outputs for explicit parent-child communication.

Nested LiveViews solve a stronger ownership problem than components and should
not be the default way to split markup.

Use a nested `LiveView` when the embedded UI needs a full independent LiveView
lifecycle: its own model and messages, flash, components, hooks, uploads, streams,
async tasks, and subscriptions. It shares the page's WebSocket connection with
the parent but joins it separately, under its own topic. Typical cases are a persistent player,
chat panel, or independently failing embedded application. A nested LiveView
does not have component props or an output mapper; pass construction data into
the child definition and coordinate longer-lived shared data through an
application service.

Use an ordinary function returning typed HTML when neither isolated state nor a
separate lifecycle is needed.

## Render a stable child {#render-a-stable-child}

Embed the child with @:apiSymbol(def:scalive.liveView)`liveView`@:@:

```scala unverified
def view(model: Signal[Model]) =
  sectionTag(
    liveView(
      id = "support-chat",
      value = SupportChatLiveView(conversationId),
      sticky = true
    )
  )
```

The `id` is application identity and determines the child topic. Keep it stable
across parent graph evaluations, and derive repeated IDs from stable domain
identity rather than list position. Reusing an ID preserves the connected
registration and session; changing it creates a different child lifecycle.

Every nested LiveView ID in one active parent graph must be unique, even when the
children use different LiveView classes or are emitted through components and
streams. Rendering the same ID twice fails the parent render with
`IllegalArgumentException` and a `Duplicate nested LiveView id` message. Two
instances of the same child type are valid when their IDs differ.

The child argument is by-name and is evaluated when a disconnected or connected
child lifecycle starts. Construct the intended child there; do not depend on
side effects performed while constructing the parent's view graph.

## Design for two mounts {#design-for-two-mounts}

During the initial HTTP request, Scalive mounts and renders the nested child so
its content is present in disconnected HTML. When LiveSocket connects, the
child joins separately and mounts again with
`Connection.Connected(capabilities)`. The
disconnected model, dynamic hooks, and resource registrations do not transfer
to the connected child.

Apply the same mount discipline as a root LiveView:

- initialize deterministic render state in both phases;
- start async tasks, subscriptions, connected resources, and client effects only
  for the connected lifecycle;
- configure uploads and streams in each lifecycle that renders their handles;
- keep state that must survive a reconnect in a service or storage, not in the
  child's model.

The child inherits the current page URL when it joins without an explicit URL,
but it is not independently routed by its placement. Routed patches still
belong to the browser's current root route.

## Keep state and resources independent {#keep-state-and-resources-independent}

Parent graph evaluations and patches do not reset a child whose ID remains stable. The
child handles its own events serially and owns its component tree and managed
resources. Parent and child also acquire connected resources independently.
Equal async, subscription, stream, or upload keys in parent and child therefore
do not refer to the same registration.

This isolation also defines communication. Parent event bindings cannot target
components inside the child, and the child has no typed component-style
output channel to its parent. Prefer a shared service, persisted state, or an
explicit browser-level interaction over coupling the two lifecycles through
runtime IDs.

## Choose sticky navigation deliberately {#choose-sticky-navigation}

The default `sticky = false` makes the parent own the child. Leaving the parent
shuts down the child lifecycle and its descendants. Use `sticky = true` only when
the same child identity should survive parent teardown during compatible live
navigation. On the destination render, the client can rejoin the existing
sticky child, preserving its model and lifecycle-owned resources instead of
mounting a replacement.

Sticky is a live-navigation policy, not persistence. A full redirect, reload,
transport loss, incompatible live-session boundary, or process failure can
still require a fresh mount. A sticky child is detached from normal parent
ownership while navigating, so reserve it for UI that genuinely spans pages;
do not enable it as a general remount optimization.

`sticky = true` has no effect when the parent's route or live session declares
[connected-turn guards](lifecycle-hooks.md#connected-turn-guards). The child
inherits those guards, so Scalive mounts it as a non-sticky child: navigation
and reconnect remount it under the current policy instead of keeping a
lifecycle that was admitted under an earlier one.

## Set the crash boundary {#set-the-crash-boundary}

By default `linkParentOnCrash = false`. A child mount or connected lifecycle can
fail without crashing the parent; the failed child receives the normal
generic join or `phx_error` behavior and may rejoin independently.

Set `linkParentOnCrash = true` when the parent is invalid without that child:

```scala unverified
liveView(
  id = "required-editor",
  value = RequiredEditorLiveView(documentId),
  linkParentOnCrash = true
)
```

Then a connected child join failure or later child crash also crashes the
parent, causing parent error and rejoin handling. This is failure propagation,
not supervision or recovery. Keep the default for optional panels and enable
the link only when restarting the whole page lifecycle is safer than leaving
the parent alive.

## Respect navigation, flash, and title boundaries {#respect-ui-boundaries}

A child may call its own `ctx.nav.pushNavigate`, `pushPatch`, replace variants,
or redirects. Those commands affect the containing browser, not only the child
subtree. A live patch is subsequently handled by the current routed root
LiveView, so keep URL ownership in the root even when a child initiates the
action.

Flash belongs to the lifecycle that writes it. A child's `ctx.flash` and
`flash(kind)` rendering do not expose that message in the parent flash store.
Navigation may still carry the navigation-triggering lifecycle's flash according
to the normal flash rules.

Only the root LiveView owns the document title. Overriding `pageTitle` in a
nested child does not update `document.title`; render a local heading or send a
domain update through an application boundary when the root title must change.

## Clean up removed and failed children {#clean-up-children}

Stop rendering a non-sticky child when its UI is no longer needed. Once the
client leaves that child, Scalive shuts down its lifecycle and the connected
resources, async tasks, subscriptions, uploads, streams, components,
hooks, and nested descendants it owns. Leaving the parent performs the same recursive
cleanup for its non-sticky children. Sticky children intentionally survive that
parent leave.

Do not retain child runtime IDs or handles in parent state. On a crash, assume
the failed child's in-memory model and resources are gone and rebuild them in
the next connected mount from services or storage. Test removal, parent navigation,
child failure, and rejoin separately; they exercise different ownership paths.

## Related tasks {#related-tasks}

- Use [Stateful components and communication](components-and-communication.md)
  when isolated state does not require a separate lifecycle.
- Review [Routes, parameters, and navigation](routes-and-navigation.md) before a
  child initiates page navigation.
- Design managed resources with
  [Asynchronous work](async-work.md),
  [Subscriptions](subscriptions.md),
  [Connected resources](connected-resources.md), and
  [File uploads](uploads-and-consumption.md).
- Apply root title and flash rules from
  [Flash messages, page titles, and connection status](flash-titles-and-connection-status.md).
- Verify both phases with [Testing LiveViews](testing.md).
