{%
title = "Lifecycle hooks"
description = "Guard complete connected turns or intercept individual root and component lifecycle stages with ordered policy and lifecycle-owned cleanup."
order = 41
section = guides
group = "Async and lifecycle"
%}

## Before You Start {#prerequisites}

Start with a `LiveView` or `LiveComponent` whose ordinary messages already
reach `handleMessage` and update the rendered model.

## Use Hooks For Cross-Cutting Lifecycle Policy {#choose-hooks}

Use a hook when application behavior must wrap a lifecycle stage across several
messages: authorization, protocol interception, or observation that requires
stage-specific application context. Keep normal domain transitions in
`handleMessage`, route changes in `handleParams`, and one-off browser payloads in
typed event bindings or `onBrowserEvent`. A hook should not become a second,
hidden message handler.

Use @:apiSymbol(trait:scalive.LifecycleObserver)`LifecycleObserver`@:@ instead
for route-wide operational events and metrics. An observer receives structured
classifications, cannot alter an operation, and is isolated from lifecycle
failure. [Lifecycle observability](lifecycle-observability.md#choose-the-observation-boundary)
defines that boundary.

In the examples, lifecycle callbacks return `Task[A]`, and `ZIO.succeed(value)`
creates an effect that succeeds with `value`. Operators
such as `.as(value)` run an effect and replace its successful result.

Scalive provides these stages:

| Owner | Stage | Runs before or after |
| --- | --- | --- |
| Root | `rawEvent` | Before binding lookup and component routing |
| Root | `browserEvent` | After raw interception; consumes a matching named browser event |
| Root | `event` | After a root binding resolves to `Msg`, before `handleMessage` |
| Root | `params` | After URL decoding, before routed `handleParams` |
| Root | `info` | Before non-browser server-message handling, including subscriptions |
| Root | `async` | Before handling a root-owned managed task completion |
| Root | `afterRender` | After the complete root tree renders, before a connected diff is emitted |
| Component | `rawEvent` | After component routing, before typed component event handling |
| Component | `browserEvent` | After component raw interception; consumes a matching named browser event |
| Component | `event` | After a component binding resolves to `Msg`, before `handleMessage` |
| Component | `async` | Before handling that instance's managed task completion |
| Component | `afterRender` | After that component subtree renders |

Components do not have `params` or `info` stages. Root and component hook
registries are separate even when the stage names match.

## Guard Every Connected Application Turn {#connected-turn-guards}

Use
@:apiSymbol(def:scalive.LiveRouteMountAspectBuilder.guardConnectedTurns)`guardConnectedTurns`@:@
at a routing boundary when policy must run before all connected application
work, rather than at only one hook stage. Declare a route guard after the mount
aspects that produce its context. Succeed to continue; fail through the typed
control channel to stop the turn:

```scala
val accountRoute = (live / "account")
  .withMountAspect(loadAccountAccess)
  .guardConnectedTurns((access: AccountAccess) =>
    access.isActive.flatMap {
      case true  => ZIO.unit
      case false =>
        ZIO.fail(LiveConnectedTurnFailure.reload("account access changed"))
    }
  )(
    AccountLiveView()
  )
```

A named session can likewise declare guards after admission:

```scala
val authenticated = Live
  .session("authenticated")
  .withAdmission(authenticatedAdmission)(_.sessionId)
  .guardConnectedTurns((session: ConnectedSession) => session.revalidate)(
    accountRoute
  )
```

The callback returns `IO[LiveConnectedTurnFailure, Unit]`, using
@:apiSymbol(enum:scalive.LiveConnectedTurnFailure)`LiveConnectedTurnFailure`@:@
as its typed control channel. `ZIO.unit` means **Continue** and `ZIO.fail(...)`
selects one of these controlled outcomes:

- `LiveConnectedTurnFailure.halt`;
- `redirect(location)` for a typed `LiveLocation`;
- `redirectUnsafe(url)` for an explicitly unsafe `URL`;
- `reload` or `reload(reason)`; and
- `disconnect` or `disconnect(reason)`.

Session guards run before route guards. Within each boundary, guards run in
declaration order. All run before hooks, handlers, and diff generation. Nested
views inherit their parent declarations. If a nested view has guards, Scalive
makes an otherwise sticky nested view non-sticky so reconnect or navigation
safely remounts it under current policy.

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
**Disconnect** closes the physical socket and therefore every lifecycle sharing
it; the client then reconnects and mounts again. Reload and disconnect reasons
stay on the server and are not browser-facing error text.

Phoenix LiveView offers phase-specific hooks that can enforce policy at selected
callback stages. Connected-turn guards address the related need for one check
across Scalive's complete connected application-turn boundary. They complement
Scalive lifecycle hooks; this is a behavioral relationship, not a claim that
the callback APIs or every phase map one-to-one with upstream.

## Declare Static Root Hooks {#static-root-hooks}

Override `LiveView.hooks` for hooks that always belong to the view. Fluent calls
append hooks, so hooks in the same stage run in declaration order and each
continued model becomes the next hook's input:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks
    .empty[Msg, Model]
    .onEvent { (model, msg, _) =>
      ZIO.logDebug(s"message=$msg")
        .as(LiveHookResult.cont(model))
    }
    .onInfo { (model, msg, _) =>
      ZIO.logDebug(s"server message=$msg")
        .as(LiveHookResult.cont(model))
    }
```

Static root hooks are installed before both the disconnected HTTP lifecycle and
the connected socket lifecycle. Those lifecycles have independent models and
hook registries; dynamic changes made during disconnected rendering do not
carry into the socket.

## Declare Static Component Hooks {#static-component-hooks}

Override `LiveComponent.hooks` with `ComponentLiveHooks`. Component hooks also
receive the current props and are installed separately for every `(component
class, logical id)` instance:

```scala
override def hooks: ComponentLiveHooks[Props, Msg, Model] =
  ComponentLiveHooks
    .empty[Props, Msg, Model]
    .onEvent { (props, model, msg, _) =>
      authorize(props.user, msg)
        .as(LiveHookResult.cont(model))
    }
    .afterRender { (props, model, _) =>
      recordComponentRender(props.id, model)
    }
```

Static component hooks run before dynamic hooks for the same component stage.
The registry survives rerenders of that instance and is discarded when the
instance is removed. Rendering the same component class later with the same ID
creates a new instance and a new registry.

## Continue, Halt, And Reply {#continue-halt-reply}

Typed `event`, `params`, `info`, and `async` hooks use `LiveHookResult`:

- `cont(model)` passes that model to the next hook and then the normal callback.
- `halt(model)` stops later hooks, skips the normal callback, and renders that model.
- Typed event hooks cannot reply. Raw event hooks instead use
  `LiveEventHookResult`, whose `haltReply(model, json)` can return JSON in the
  browser event acknowledgement.

For example, a typed root event hook can consume a forbidden action before the
normal handler, but cannot reply to a JavaScript caller:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.empty[Msg, Model].onEvent { (model, msg, _) =>
    if allowed(model.user, msg) then
      ZIO.succeed(LiveHookResult.cont(model))
    else
      ZIO.succeed(LiveHookResult.halt(model))
  }
```

Named `browserEvent` handlers are a distinct stage: they return `Task[Model]`
and consume a matching, successfully decoded browser event without a
continue/halt result. A failed hook effect is different from `Halt`: failure
aborts the active lifecycle, prevents later hooks and the normal callback, and
does not provide a controlled model transition.

## Attach And Detach Dynamic Hooks {#dynamic-hooks}

Use `ctx.hooks.<stage>.attach(id)` when a hook is conditional for the current
lifecycle. Static hooks run first; dynamic hooks follow in successful attachment
order:

```scala
private val AuditHookId = "audit-events"

def mount(ctx: MountContext): Task[Model] =
  ctx.hooks.event
    .attach(AuditHookId) { (model, msg, _) =>
      audit(msg).as(LiveHookResult.cont(model))
    }
    .as(Model.initial)

def handleMessage(model: Model, ctx: MessageContext): Msg => Task[Model] =
  case Msg.StopAuditing =>
    ctx.hooks.event.detach(AuditHookId).as(model)
  case msg =>
    update(model, msg)
```

An ID must be unique among dynamic hooks in the same stage and owner. Attaching
a duplicate fails with `IllegalArgumentException`. The same text may identify a
hook in another stage or component instance because those registries are
independent. Detaching an absent ID succeeds, and dynamic detach cannot remove a
static hook.

Dispatch uses a snapshot of one stage's registry. Attaching or detaching while
hooks are running changes the next dispatch, not the one already in progress.
This also means an after-render hook that detaches itself still completes its
current invocation.

Inside a component lifecycle context, the same shape operates on only that
component instance and includes current props:

```scala
def mount(props: Props, ctx: MountContext): Task[Model] =
  ctx.hooks.event
    .attach("read-only-policy") { (currentProps, model, msg, _) =>
      if currentProps.readOnly && mutatesState(msg) then
        ZIO.succeed(LiveHookResult.halt(model))
      else
        ZIO.succeed(LiveHookResult.cont(model))
    }
    .as(Model.initial(props))
```

Detach it through `ctx.hooks.event.detach("read-only-policy")` from a component
update, message, or after-render context when the policy no longer applies.

## Advanced: Intercept Raw Events Sparingly {#raw-event-interception}

`onRawEvent` and `ctx.hooks.rawEvent.attach` receive `LiveEvent` before ordinary
typed handling. Its `kind`, `bindingId`, unmodified JSON `value`, normalized
string `params`, optional component `cid`, and optional JSON `meta` expose the
wire envelope:

```scala
import zio.json.ast.Json

override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.empty[Msg, Model].onRawEvent { (model, event, _) =>
    if blockedBindingIds.contains(event.bindingId) then
      ZIO.succeed(
        LiveEventHookResult.haltReply(
          model,
          Json.Obj("error" -> Json.Str("forbidden"))
        )
      )
    else
      ZIO.succeed(LiveEventHookResult.cont(model))
  }
```

A root raw hook sees root- and component-targeted envelopes, so inspect
`event.cid` when target ownership matters. If it continues, Scalive performs
binding lookup and routes a component target; the component's raw hooks then run
before its typed event hooks. Halting at the root prevents all later root and
component event processing.

Prefer `onEvent` once a rendered binding has produced a typed `Msg`. Prefer the
distinct `onBrowserEvent(BrowserToServerEvent[A])` stage for a named JavaScript
event because it matches the event name, decodes with `JsonDecoder[A]`, consumes
matching payloads, and rejects malformed matching payloads. Both roots and
components provide this stage. Raw interception is for policy that must act
before decoding or for protocol-level metadata.

## Keep After-Render Hooks Observational {#after-render-limits}

After-render hooks run in registration order, but every hook receives the same
rendered model; they return `Task[Unit]`, cannot thread a replacement model,
cannot halt, and cannot change the tree already rendered. A failure prevents
later after-render hooks and aborts the render.

Root `AfterRenderContext` exposes a phase-aware connection and dynamic root
hooks. The connected capabilities expose `client`; obtaining it by matching the
connection makes disconnected use impossible:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.afterRender { (model, ctx) =>
    ctx.connection match
      case Connection.Connected(capabilities) =>
        capabilities.client.push(RenderedEvent, Rendered(model.id))
      case Connection.Disconnected => ZIO.unit
  }
```

Component `ComponentAfterRenderContext` is intentionally narrower: it exposes
lifecycle metadata and that instance's dynamic hooks, but no client operations,
navigation, output emission, async work, uploads, or streams. Move state changes
and resource starts into mount, update, or normal message handling.

## Keep Ownership And Cleanup Local {#ownership-cleanup}

Dynamic root hooks live until detached or until their disconnected or connected
root lifecycle ends. Dynamic component hooks live until detached or until that
exact component instance is removed. There is no separate hook teardown
callback: lifecycle and component removal discard their registries.

Hook-managed external resources still need an owner. Prefer `ctx.async`,
`ctx.subscriptions`, connected resources, uploads, and streams where available
because Scalive cleans them up with their lifecycle. The connected-resource
capability is exposed during mount and should not be retained for later use; a
hook that changes ownership later must use a keyed managed API or an explicitly
scoped service rather than starting an unmanaged fiber. Use stable hook IDs
derived from the owner when attaching shared behavior, and detach a conditional
hook when its policy no longer applies.

## Related Tasks {#related-tasks}

- Apply guards to session revocation with [Authentication and sessions](authentication.md#revalidate-connected-turns).
- Use [Browser commands, events, and hooks](browser-integration.md) for JavaScript hooks and typed browser payloads.
- Own tasks, streams, and other acquired resources with [Asynchronous work, subscriptions, and connected resources](async-work-and-subscriptions.md).
- Handle component identity, updates, and outputs with [Stateful components and communication](components-and-communication.md).
