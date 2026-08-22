{%
title = "Lifecycle hooks"
description = "Intercept root and component lifecycle stages with ordered static or dynamic hooks, explicit continuation, and lifecycle-owned cleanup."
order = 41
section = guides
group = "Async and lifecycle"
%}

## Prerequisites {#prerequisites}

Start with a `LiveView` or `LiveComponent` that handles messages through
`handleMessage`.

## Use Hooks For Cross-Cutting Lifecycle Policy {#choose-hooks}

Use a hook when behavior must wrap a lifecycle stage across several messages:
authorization, instrumentation, protocol interception, or observing every
render. Keep normal domain transitions in `handleMessage`, route changes in
`handleParams`, and one-off browser payloads in typed event bindings or
`onBrowserEvent`. A hook should not become a second, hidden message handler.

In the examples, lifecycle callbacks return `Task[A]`, and `ZIO.succeed(value)`
creates an effect that succeeds with `value`. Operators
such as `.as(value)` run an effect and replace its successful result.

Scalive provides these stages:

| Owner | Stage | Runs before or after |
| --- | --- | --- |
| Root | `rawEvent` | Before binding lookup and component routing |
| Root | `event` | After a root binding resolves to `Msg`, before `handleMessage` |
| Root | `params` | After URL decoding, before routed `handleParams` |
| Root | `info` | Before non-browser server-message handling, including subscriptions |
| Root | `async` | Before handling a root-owned managed task completion |
| Root | `afterRender` | After the complete root tree renders, before a connected diff is emitted |
| Component | `rawEvent` | After component routing, before typed component event handling |
| Component | `event` | After a component binding resolves to `Msg`, before `handleMessage` |
| Component | `async` | Before handling that instance's managed task completion |
| Component | `afterRender` | After that component subtree renders |

Components do not have `params` or `info` stages. Root and component hook
registries are separate even when the stage names match.

## Declare Static Root Hooks {#static-root-hooks}

Override `LiveView.hooks` for hooks that always belong to the view. Fluent calls
append hooks, so hooks in the same stage run in declaration order and each
continued model becomes the next hook's input:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks
    .empty[Msg, Model]
    .onEvent { (model, msg, event, _) =>
      ZIO.logDebug(s"event=${event.kind} message=$msg")
        .as(LiveEventHookResult.cont(model))
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
    .onEvent { (props, model, msg, _, _) =>
      authorize(props.user, msg)
        .as(LiveEventHookResult.cont(model))
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

Event stages use `LiveEventHookResult`; all model-threading non-event stages use
`LiveHookResult`:

- `cont(model)` passes that model to the next hook and then the normal callback.
- `halt(model)` stops later hooks, skips the normal callback, and renders that model.
- `LiveEventHookResult.haltReply(model, json)` also returns JSON in the browser event acknowledgement.

For example, a root event hook can consume a forbidden action before the normal
handler and reply to a JavaScript `pushEvent` caller:

```scala
import zio.json.ast.Json

override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.empty[Msg, Model].onEvent { (model, msg, _, _) =>
    if allowed(model.user, msg) then
      ZIO.succeed(LiveEventHookResult.cont(model))
    else
      ZIO.succeed(
        LiveEventHookResult.haltReply(
          model,
          Json.Obj("error" -> Json.Str("forbidden"))
        )
      )
  }
```

Replies exist only for raw and typed event hooks. `params`, `info`, and `async`
hooks use `LiveHookResult` and cannot reply. A failed hook effect is different
from `Halt`: failure aborts the active lifecycle, prevents later hooks and the
normal callback, and does not provide a controlled model transition.

## Attach And Detach Dynamic Hooks {#dynamic-hooks}

Use `ctx.hooks.<stage>.attach(id)` when a hook is conditional for the current
lifecycle. Static hooks run first; dynamic hooks follow in successful attachment
order:

```scala
private val AuditHookId = "audit-events"

def mount(ctx: MountContext): Task[Model] =
  ctx.hooks.event
    .attach(AuditHookId) { (model, msg, event, _) =>
      audit(msg, event).as(LiveEventHookResult.cont(model))
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
    .attach("read-only-policy") { (currentProps, model, msg, _, _) =>
      if currentProps.readOnly && mutatesState(msg) then
        ZIO.succeed(LiveEventHookResult.halt(model))
      else
        ZIO.succeed(LiveEventHookResult.cont(model))
    }
    .as(Model.initial(props))
```

Detach it through `ctx.hooks.event.detach("read-only-policy")` from a component
update, message, or after-render context when the policy no longer applies.

## Intercept Raw Events Sparingly {#raw-event-interception}

`onRawEvent` and `ctx.hooks.rawEvent.attach` receive `LiveEvent` before ordinary
typed handling. Its `kind`, `bindingId`, unmodified JSON `value`, normalized
string `params`, optional component `cid`, and optional JSON `meta` expose the
wire envelope:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks.empty[Msg, Model].onRawEvent { (model, event, _) =>
    if blockedBindingIds.contains(event.bindingId) then
      ZIO.succeed(LiveEventHookResult.halt(model))
    else
      ZIO.succeed(LiveEventHookResult.cont(model))
  }
```

A root raw hook sees root- and component-targeted envelopes, so inspect
`event.cid` when target ownership matters. If it continues, Scalive performs
binding lookup and routes a component target; the component's raw hooks then run
before its typed event hooks. Halting at the root prevents all later root and
component event processing.

Prefer `onEvent` once a rendered binding has produced a typed `Msg`. Prefer
`onBrowserEvent(BrowserToServerEvent[A])` for a named JavaScript event because it
matches the event name, decodes with `JsonDecoder[A]`, consumes matching payloads,
and rejects malformed matching payloads. Raw interception is for policy that
must act before decoding or for protocol-level metadata.

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
`ctx.subscriptions`, uploads, and streams where available because Scalive cleans
them up with their lifecycle. If a hook acquires some other resource, manage its
scope explicitly rather than starting an unmanaged fiber. Use stable hook IDs
derived from the owner when attaching shared behavior, and detach a conditional
hook when its policy no longer applies.

## Related Tasks {#related-tasks}

Use [Browser commands, events, and hooks](browser-integration.md) for JavaScript
hooks and typed browser payloads. Use
[Asynchronous work and subscriptions](async-work-and-subscriptions.md) for
lifecycle-owned tasks and streams, and
[Stateful components and communication](components-and-communication.md) for
component identity, updates, and outputs.
