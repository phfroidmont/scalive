{%
title = "Lifecycle hooks"
description = "Intercept root and component lifecycle stages with ordered hooks, guard complete connected turns, and keep cleanup with the lifecycle."
order = 43
section = guides
group = "Async and lifecycle"
%}

## Before you start {#prerequisites}

Start with a `LiveView` or `LiveComponent` whose ordinary messages already
reach `handleMessage` and update the rendered model.

In the examples, lifecycle callbacks return `Task[A]`, and `ZIO.succeed(value)`
creates an effect that succeeds with `value`. Operators such as `.as(value)` run
an effect and replace its successful result.

## Choose a hook, guard, or observer {#choose-hooks}

Pick the tool that matches what you want to do:

| You want to | Use | Declare it on |
| --- | --- | --- |
| Change the model in response to one message | `handleMessage` | The view or component |
| Apply one policy at a lifecycle stage across many messages: authorization, protocol interception, or logging that needs the model and message | A lifecycle hook | `hooks` on the view or component, or `ctx.hooks` |
| Check one policy before every connected application turn of a route or session, ahead of all hooks and handlers | A connected-turn guard | A route or live-session builder |
| Record route-wide operational events and metrics without changing the lifecycle | A `LifecycleObserver` | Route assembly with `ZioHttp.routes` |

Keep normal domain transitions in `handleMessage`, route changes in
`handleParams`, and events pushed from JavaScript in `browserEvents`, which turns
them into messages. A hook should not become a second, hidden message handler.

An @:apiSymbol(trait:scalive.LifecycleObserver)`LifecycleObserver`@:@ receives
structured classifications, cannot alter an operation, and is isolated from
lifecycle failure. [Lifecycle observability](lifecycle-observability.md#choose-the-observation-boundary)
defines that boundary. Connected-turn guards are a routing feature;
[Guard every connected application turn](#connected-turn-guards) covers them
after the hook sections.

## Declare static LiveView hooks {#static-liveview-hooks}

To run code at one lifecycle stage for every message a view receives, override
`LiveView.hooks`. This view logs each browser message and each server message
before its handler runs:

@:sourceRegion(documentation/fixtures/guides/src/hooks/BoardLiveViews.scala, hooks-static-liveview)

Start from `LiveHooks.empty[Msg, Model]` and chain one call per hook. Each hook
here returns `LiveHookResult.cont(model)` so processing continues;
[Continue, halt, and reply](#continue-halt-reply) covers the alternatives.
Fluent calls append hooks, so hooks in the same stage run in declaration order
and each continued model becomes the next hook's input.

For a single hook, the @:apiSymbol(object:scalive.LiveHooks)`LiveHooks`@:@
companion has one shortcut per stage, such as `LiveHooks.onEvent { ... }` or
`LiveHooks.afterRender { ... }`. A shortcut takes its types from the declared
`hooks` type.

Static LiveView hooks are installed before both the disconnected HTTP lifecycle and
the connected lifecycle. Those lifecycles have independent models and hook
registries; dynamic changes made during disconnected rendering do not carry into
the connected lifecycle.

## Declare static component hooks {#static-component-hooks}

To run code at one stage for every message a component instance receives,
override `LiveComponent.hooks` with
@:apiSymbol(trait:scalive.ComponentLiveHooks)`ComponentLiveHooks`@:@.
Component hooks also receive the current props:

@:sourceRegion(documentation/fixtures/guides/src/hooks/CardComponent.scala, hooks-static-component)

Chaining and single-stage shortcuts work as they do for LiveView hooks. Scalive
installs the declarations separately for every `(component class, logical id)`
instance. The registry survives rerenders of that instance and is discarded
when the instance is removed. Rendering the same component class later with the
same ID creates a new instance and a new registry. Static component hooks run
before dynamic hooks for the same component stage.

## Pick a stage {#hook-stages}

Each hook runs at one stage, so pick the stage by when the hook must run. Static
declarations add an `on` prefix to the stage name, such as `onRawEvent` for
`rawEvent`; `afterRender` keeps its name. Dynamic hooks use
`ctx.hooks.<stage>`.

| Owner | Stage | Runs before or after |
| --- | --- | --- |
| Root | `rawEvent` | Before the event becomes a message, and before component routing |
| Root | `event` | After a root binding or declared browser event becomes `Msg`, before `handleMessage` |
| Root | `params` | After mount and each URL change, before routed `handleParams` |
| Root | `info` | Before non-browser server-message handling, including subscriptions |
| Root | `async` | Before handling a root-owned managed task completion |
| Root | `afterRender` | After the complete root tree renders, before a connected diff is emitted |
| Component | `rawEvent` | After component routing, before typed component event handling |
| Component | `event` | After a component binding or declared browser event becomes `Msg`, before `handleMessage` |
| Component | `async` | Before handling that instance's managed task completion |
| Component | `afterRender` | After that component subtree renders |

Components do not have `params` or `info` stages. Root and component hook
registries are separate even when the stage names match. `params` hooks run on
every LiveView, not only `LiveView.Routed` ones, and a nested LiveView sees the
URL of its page.

## Continue, halt, and reply {#continue-halt-reply}

To stop a message before its handler runs, return a halt result. This typed
event hook consumes messages the current user may not send:

@:sourceRegion(documentation/fixtures/guides/src/hooks/BoardLiveViews.scala, hooks-halt-event)

`canSend` is the application's rule for which messages a user may send. Typed
`event`, `params`, `info`, and `async` hooks return `LiveHookResult`:

- `cont(model)` passes that model to the next hook and then the normal callback.
- `halt(model)` stops later hooks, skips the normal callback, and renders that model.

Typed event hooks cannot reply to a JavaScript caller. Raw event hooks return
`LiveEventHookResult` instead, whose `haltReply(model, json)` can return JSON in
the browser event acknowledgement; see
[Intercept raw events sparingly](#raw-event-interception).

A failed hook effect is different from `Halt`: failure aborts the active
lifecycle, prevents later hooks and the normal callback, and does not provide a
controlled model transition. Use `halt` to refuse a message, and let the effect
fail only on errors.

## Attach and detach dynamic hooks {#dynamic-hooks}

To install a hook for only part of a lifecycle, attach it with
`ctx.hooks.<stage>.attach(id)` and remove it with `detach(id)`. This view audits
messages from mount until the user stops auditing:

@:sourceRegion(documentation/fixtures/guides/src/hooks/BoardLiveViews.scala, hooks-dynamic-root)

Root mount, params, message, and after-render contexts expose `ctx.hooks`.
Static hooks run first; dynamic hooks follow in successful attachment order.

A component context works the same way on only that component instance, and its
hooks receive the current props. This card refuses edits while its props are
read-only:

@:sourceRegion(documentation/fixtures/guides/src/hooks/CardComponent.scala, hooks-dynamic-component)

`edits` says whether a message changes the card. Detach the hook with
`ctx.hooks.event.detach("read-only-policy")` from a component update, message,
or after-render context when the policy no longer applies.

An ID identifies one dynamic hook in a stage and owner. Attaching an ID that is
already attached there replaces that hook in its position. The same text may
identify a hook in another stage or component instance because those registries
are independent. Detaching an absent ID succeeds, and dynamic detach cannot
remove a static hook.

Dispatch uses a snapshot of one stage's registry. Attaching or detaching while
hooks are running changes the next dispatch, not the one already in progress.
This also means an after-render hook that detaches itself still completes its
current invocation.

## Advanced topics {#advanced-topics}

The sections below cover policy that runs outside hooks, protocol-level event
interception, the limits of after-render hooks, and ownership of what hooks
start.

### Guard every connected application turn {#connected-turn-guards}

To run one policy check before all connected application work, rather than at
one hook stage, declare
@:apiSymbol(def:scalive.LiveRouteSetup.WithContext.guardConnectedTurns)`guardConnectedTurns`@:@
on a route registration, after the mount aspects that produce its context. Succeed to
continue; fail through the typed control channel to stop the turn:

@:sourceRegion(documentation/fixtures/guides/src/hooks/AccountRoutes.scala, hooks-route-guard)

`loadAccountAccess` is a route mount aspect that produces `AccountAccess`, and
`isActive` reads the current account state on every turn.

A named session declares
@:apiSymbol(def:scalive.LiveSessionBuilder.guardConnectedTurns)`guardConnectedTurns`@:@
after admission. The authentication lab revalidates the signed-in user this way:

@:sourceRegion(documentation/site/src/scalive/docs/auth/AuthLab.scala, authentication-protected-session)

The callback returns `IO[LiveConnectedTurnFailure, Unit]`, using
@:apiSymbol(enum:scalive.LiveConnectedTurnFailure)`LiveConnectedTurnFailure`@:@
as its typed control channel. It cannot require a ZIO environment, so read
services through its context. `ZIO.unit` means **Continue** and `ZIO.fail(...)`
selects one of these controlled outcomes:

- `LiveConnectedTurnFailure.halt`;
- `redirect(location)` for a typed `LiveLocation`, or `redirect(url)` for an
  `ExternalUrl` outside the application;
- `redirectUnsafe(url)` for an explicitly unsafe `URL`;
- `reload` or `reload(reason)`; and
- `disconnect` or `disconnect(reason)`.

Session guards run before route guards. Within each boundary, guards run in
declaration order. All run before hooks, handlers, and diff generation. Nested
views inherit their parent declarations, and a sticky child keeps them across
navigation; see
[Choose sticky navigation deliberately](nested-liveviews.md#choose-sticky-navigation).

The guarded scope is exactly connected application turns: root and component
events (including upload-progress events), params, server messages and
continuations, async and subscription delivery, and component updates. Guards
also run before an accepted upload progress report can update state and invoke
`LiveUploadProgress`; the callback remains serialized in that turn after the
progress state commits. They do not run for bootstrap or connected mount,
disconnected rendering, upload bookkeeping that cannot invoke application
callbacks, after-render hooks, or framework cleanup.

**Halt** leaves the last committed model in place and schedules no render or
after-render work. The transport may still send the protocol acknowledgement
needed to settle the incoming operation. **Reload** performs a full HTTP
navigation to the URL governing the turn. During patch acknowledgement that is
the pending destination; otherwise it is the lifecycle's committed URL.
**Disconnect** closes the WebSocket connection and therefore every lifecycle
sharing it; the client then reconnects and mounts again. Reload and disconnect reasons
stay on the server and are not browser-facing error text.

Phoenix LiveView offers phase-specific hooks that can enforce policy at selected
callback stages. Connected-turn guards address the related need for one check
across Scalive's complete connected application-turn boundary. They complement
Scalive lifecycle hooks; this is a behavioral relationship, not a claim that
the callback APIs or every phase map one-to-one with upstream.

### Intercept raw events sparingly {#raw-event-interception}

Use a raw event hook only for policy that must act before decoding or that needs
protocol-level metadata. This hook rejects two named JavaScript events from a
user who cannot edit, and replies with an error the JavaScript caller can read:

@:sourceRegion(documentation/fixtures/guides/src/hooks/BoardLiveViews.scala, hooks-raw-event)

`Json` is `zio.json.ast.Json`. `onRawEvent` and `ctx.hooks.rawEvent.attach`
receive a `LiveEvent` before ordinary typed handling. Its `kind`, `bindingId`,
unmodified JSON `value`, normalized string `params`, optional component `cid`,
and optional JSON `meta` expose the wire envelope. For an event pushed from
JavaScript, `bindingId` is the event name.

A root raw hook sees root- and component-targeted envelopes, so inspect
`event.cid` when target ownership matters. If it continues, Scalive turns the
event into a message, from a declared browser event or by binding lookup, and
routes a component target; the component's raw hooks then run
before its typed event hooks. Halting at the root prevents all later root and
component event processing.

Prefer `onEvent` once the event has become a typed `Msg`, whether from a
rendered binding or a named JavaScript event declared in `browserEvents`.

### Keep after-render hooks observational {#after-render-limits}

To act once a render completes, for example to push the rendered state to the
browser, add an `afterRender` hook. The LiveView's `AfterRenderContext` exposes a
phase-aware connection and its lifecycle hooks. The connected capabilities
expose `client`; obtaining it by matching the connection makes disconnected use
impossible:

@:sourceRegion(documentation/fixtures/guides/src/feedback/DocumentLiveView.scala, feedback-after-render)

`RenderedEvent` is a `ServerToBrowserEvent[Rendered]`. The example uses the
single-stage `LiveHooks.afterRender` shortcut from
[Declare static LiveView hooks](#static-liveview-hooks).

After-render hooks run in registration order, but every hook receives the same
rendered model; they return `Task[Unit]`, cannot thread a replacement model,
cannot halt, and cannot change the tree already rendered. A failure prevents
later after-render hooks and aborts the render.

Component `ComponentAfterRenderContext` is intentionally narrower: it exposes
lifecycle metadata and that instance's dynamic hooks, but no client operations,
navigation, output emission, async work, uploads, or streams. Move state changes
and resource starts into mount, update, or normal message handling.

### Keep ownership and cleanup local {#ownership-cleanup}

Hooks need no teardown callback: lifecycle end and component removal discard
their registries. A LiveView's dynamic hooks live until detached or until its
disconnected or connected lifecycle ends. Dynamic component hooks live
until detached or until that exact component instance is removed.

Hook-managed external resources still need an owner. Prefer `ctx.async`,
`ctx.subscriptions`, connected resources, uploads, and streams where available
because Scalive cleans them up with their lifecycle. The connected-resource
capability is exposed during mount and should not be retained for later use; a
hook that changes ownership later must use a keyed managed API or an explicitly
scoped service rather than starting an unmanaged fiber. Use stable hook IDs
derived from the owner when attaching shared behavior, and detach a conditional
hook when its policy no longer applies.

## Related tasks {#related-tasks}

- Apply guards to session revocation with [Authentication and sessions](authentication.md#revalidate-connected-turns).
- Record lifecycle events and metrics with [Lifecycle observability](lifecycle-observability.md).
- Use [Browser commands, events, and hooks](browser-integration.md) for JavaScript hooks and typed browser payloads.
- Own tasks, streams, and other acquired resources with [Asynchronous work](async-work.md), [Subscriptions](subscriptions.md), and [Connected resources](connected-resources.md).
- Handle component identity, updates, and outputs with [Stateful components and communication](components-and-communication.md).
