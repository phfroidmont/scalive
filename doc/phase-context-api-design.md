# Phase Context API Design

Status: implemented design

This document records the user-facing phase-context API that replaced the public global `LiveContext` helpers. It focuses on app-author ergonomics, rationale, and the behavior Scalive now exposes rather than implementation compatibility with the previous API.

## Locked Decisions

- Use explicit phase-specific context values in lifecycle callbacks.
- Use parameterized public context traits, hidden behind aliases on `LiveView` and `LiveComponent`, with runtime implementations kept internal.
- Keep `handleMessage(model, ctx): Msg => LiveIO[Model]` as the message handler shape.
- Keep `render(model)` pure and context-free.
- Expose runtime operations through domain facades such as `ctx.nav`, `ctx.flash`, `ctx.uploads`, and `ctx.hooks`.
- Keep dynamic hook attach and detach for upstream alignment, exposed through typed context facades.
- Expose declarative `def hooks` on both `LiveView` and `LiveComponent`; components use `ComponentLiveHooks`.
- Make public `LiveIO[A]` a transparent lifecycle effect alias backed by `Task[A]`; `Task[A]` values conform directly, while plain value lifting is opt-in with `import scalive.LiveIO.given`.
- Do not expose ZIO environment capabilities in app-author lifecycle signatures.
- Define public context traits at the `scalive` top level.
- Align navigation availability with upstream lifecycle semantics: root mount supports redirect/live navigate but not patch, component mount/update do not support navigation, and component message handling supports navigation.
- Expose client event operations during mount; disconnected mount client events are no-ops, connected mount client events are delivered with the initial connected diff.
- Do not expose stream introspection through public lifecycle contexts; app state that must be inspected should live in the model, while stream command recording belongs in testing/internal APIs.
- Expose every owner-supported dynamic hook stage from every non-render lifecycle context: root contexts support rawEvent, event, params, info, async, and afterRender hooks; component contexts support rawEvent, event, async, and afterRender hooks.
- Prefer rendered diff or DOM assertions for runtime/integration tests; a separate `scalive-test` module with recording contexts can be added later if direct command assertions become useful.
- Do not keep `LiveView.interceptEvent` as a standalone lifecycle callback; preserve raw client-event interception through the hooks API instead.
- Run raw event hooks before typed message decoding, then run typed event hooks before `handleMessage`.
- Keep query decode failures as a separate `handleParamsDecodeError` lifecycle callback with `ParamsContext`; the default behavior should fail the lifecycle.
- Do not keep `subscriptions(model)` as a dedicated lifecycle method; expose named, managed subscription message sources through phase contexts instead.
- Treat managed starts that require a connected LiveView process, such as subscriptions and async tasks, as safe no-ops during disconnected mount and normal starts during connected mount.
- Keep `Props` and `Model` separate for LiveComponents: props are parent-owned inputs, model is component-owned state, and component message handling and rendering receive both.
- Expose uploads in root and component lifecycle contexts; component upload state is component-scoped.
- Use domain facades with Phoenix-aligned verbs for context operation names, avoiding redundant domain prefixes inside each facade.
- Use explicit hook callback parameters with the phase context last; component hook callbacks receive both `Props` and `Model`.
- Use owner-wide hook attachment with stage-specific hook execution contexts; hook callbacks receive the execution-stage context, never the attach-time context.
- Expose page-title updates from root contexts only; components should request title changes through the owning root LiveView.
- Remove `InterceptResult` from the target public API; use `LiveEventHookResult` for reply-capable raw/typed event hooks and `LiveHookResult` for non-event hooks.
- Make `afterRender` hooks effectful `LiveIO[Model]` callbacks with no halt or reply result; the returned model is stored for the next lifecycle pass and does not affect the already-computed current diff.
- Expose limited afterRender contexts: root afterRender has `connected`, `staticChanged`, `client`, and `hooks`; component afterRender has `connected`, `staticChanged`, and `hooks`.
- Use fail-fast semantics for programmer or configuration errors, idempotent no-op semantics for cleanup and cancellation of missing targets, domain state for runtime/user failures, and phase-specific context types for unsupported operations.
- Define phase contexts as traits so runtime implementations and `scalive-test` recording contexts can implement the same public API.
- Define `ctx.async.start(name)(task)(toMsg)` with `toMsg: A => Msg` for successful task completion.
- Represent async hook execution with `LiveAsyncEvent[Msg]`, carrying `LiveAsyncResult.Succeeded(msg)` or `LiveAsyncResult.Failed(cause)`; failed tasks do not call `toMsg` or `handleMessage`.
- Treat representative context snippets as abbreviated unless explicitly marked as complete.

## Problem

The current `LiveContext` concept mixes multiple concerns:

- Lifecycle facts such as `connected` and `staticChanged`.
- Runtime commands such as navigation, redirects, flash, client events, and title updates.
- Runtime resources such as uploads, streams, and async tasks.
- Component operations such as `sendUpdate`.
- Lifecycle hook registration.
- Internal runtime and transport implementation details.

This is useful as an internal runtime abstraction, but it is not the best public API. App authors should not need to understand runtime capability traits or a generic context service to discover what they can do from a lifecycle callback.

## Design Goals

- Make available operations discoverable through autocomplete.
- Make unsupported lifecycle operations impossible to call where possible.
- Keep `LiveView[Msg, Model]` as the core mental model.
- Keep `render` pure and free of runtime context.
- Avoid Phoenix-style socket-as-state-bag APIs.
- Hide protocol, runtime, and transport concepts from app authors.
- Keep common operations concise without making the API magical.
- Preserve typed messages, typed components, typed forms, and typed streams.

## Implemented Design

Scalive exposes explicit phase-specific context values in lifecycle callbacks. Each context provides small domain facades for the operations available in that phase.

The public API is shaped like this:

```scala
trait LiveView[Msg, Model]:
  type MountContext = scalive.MountContext[Msg, Model]
  type MessageContext = scalive.MessageContext[Msg, Model]
  type ParamsContext = scalive.ParamsContext[Msg, Model]

  def mount(ctx: MountContext): LiveIO[Model]

  def handleMessage(
    model: Model,
    ctx: MessageContext
  ): Msg => LiveIO[Model]

  def handleParams(
    model: Model,
    query: queryCodec.Out,
    url: URL,
    ctx: ParamsContext
  ): LiveIO[Model]

  def handleParamsDecodeError(
    model: Model,
    error: LiveQueryCodec.DecodeError,
    url: URL,
    ctx: ParamsContext
  ): LiveIO[Model]

  def render(model: Model): HtmlElement[Msg]
```

Typical usage:

```scala
def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model] =
  case Save =>
    for
      _ <- ctx.flash.put("info", "Saved")
      _ <- ctx.nav.pushPatch("/items")
    yield model.copy(saved = true)
```

This keeps Scalive's current handler-factory shape: given the current model and phase capabilities, return the typed message handler. It preserves concise pattern matching while making the available runtime operations explicit.

The public context traits are parameterized by message and model types. The type aliases on `LiveView` hide that noise from normal app code while preserving fully typed hooks and helper APIs.

Representative context snippets in this document are abbreviated unless explicitly marked as complete. They show the capability or typing point being discussed, not every facade available on that phase.

```scala
trait MessageContext[Msg, Model]:
  def nav: Navigation
  def flash: Flash
  def hooks: MessageHooks[Msg, Model]

trait ParamsContext[Msg, Model]:
  def nav: Navigation
  def flash: Flash
  def hooks: ParamsHooks[Msg, Model]
```

Generic helpers outside a `LiveView` can use the explicit parameterized form when needed:

```scala
def attachAudit[Msg, Model](ctx: scalive.MessageContext[Msg, Model]): LiveIO[Unit] =
  ctx.hooks.event.attach("audit") { (model, msg, event, ctx) =>
    ctx.client.pushEvent("audit", event.kind).as(LiveEventHookResult.cont(model))
  }
```

## Message Handler Shape

Prefer the curried handler shape:

```scala
def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model]
```

over a direct single-message method:

```scala
def handleMessage(model: Model, msg: Msg, ctx: MessageContext): LiveIO[Model]
```

The curried shape is a better fit for Scalive because `handleMessage` conceptually builds a typed message handler for the current model. It keeps the current public mental model, supports concise pattern matching, and still makes phase capabilities explicit through `ctx`.

The direct shape has one advantage: every input for one message is visible in a single parameter list. That symmetry is not enough to justify losing the current handler style.

## Context Facades

The context object should not be a flat collection of dozens of methods. It should expose domain-specific facades:

```scala
ctx.nav.pushPatch(...)
ctx.nav.replacePatch(...)
ctx.nav.pushNavigate(...)
ctx.nav.replaceNavigate(...)
ctx.nav.redirect(...)

ctx.flash.put(...)
ctx.flash.clear(...)
ctx.flash.get(...)
ctx.flash.snapshot

ctx.uploads.allow(...)
ctx.uploads.get(...)
ctx.uploads.cancel(...)
ctx.uploads.consumeCompleted(...)

ctx.streams.init(...)
ctx.streams.insert(...)
ctx.streams.delete(...)

ctx.async.start(...)
ctx.async.cancel(...)

ctx.subscriptions.start(...)
ctx.subscriptions.replace(...)
ctx.subscriptions.cancel(...)

ctx.client.pushEvent(...)
ctx.client.exec(JS...)

ctx.title.set(...)

ctx.components.sendUpdate(...)

ctx.hooks.event.attach(...)
ctx.hooks.event.detach(...)
```

This keeps autocomplete focused. Users can type `ctx.` to see broad areas, then `ctx.flash.` or `ctx.nav.` to discover concrete operations.

Use these public facade method names:

```scala
ctx.nav.pushPatch(...)
ctx.nav.replacePatch(...)
ctx.nav.pushNavigate(...)
ctx.nav.replaceNavigate(...)
ctx.nav.redirect(...)

ctx.flash.put(kind, message)
ctx.flash.clear(kind)
ctx.flash.clearAll
ctx.flash.get(kind)
ctx.flash.snapshot

ctx.uploads.allow(name, options)
ctx.uploads.disallow(name)
ctx.uploads.get(name)
ctx.uploads.cancel(name, entryRef)
ctx.uploads.consumeCompleted(name)
ctx.uploads.consume(entryRef)
ctx.uploads.drop(entryRef)

ctx.streams.init(definition, items, ...)
ctx.streams.insert(definition, item, ...)
ctx.streams.delete(definition, item)
ctx.streams.deleteByDomId(definition, domId)

ctx.async.start(name)(task)(toMsg: A => Msg)
ctx.async.cancel(name)

ctx.subscriptions.start(name)(stream)
ctx.subscriptions.replace(name)(stream)
ctx.subscriptions.cancel(name)

ctx.client.pushEvent(name, payload)
ctx.client.exec(js)

ctx.title.set(value)

ctx.components.sendUpdate[Component](id, props)

ctx.hooks.rawEvent.attach(...)
ctx.hooks.rawEvent.detach(...)
ctx.hooks.event.attach(...)
ctx.hooks.event.detach(...)
ctx.hooks.params.attach(...)
ctx.hooks.params.detach(...)
ctx.hooks.info.attach(...)
ctx.hooks.info.detach(...)
ctx.hooks.async.attach(...)
ctx.hooks.async.detach(...)
ctx.hooks.afterRender.attach(...)
ctx.hooks.afterRender.detach(...)
```

The facade name supplies the domain, so method names should not repeat it. For example, prefer `ctx.flash.put(...)` over `ctx.flash.putFlash(...)`, and `ctx.uploads.allow(...)` over `ctx.uploads.allowUpload(...)`. Use `ctx.uploads.get(...)` rather than `current`, because it returns `Option[LiveUpload]`. Use `ctx.streams.init(...)` rather than `stream`, because `ctx.streams.stream(...)` is awkward and `init` better communicates defining or resetting a stream.

Navigation facades should be phase-specific. `MountContext` should expose only the operations valid during mount:

```scala
ctx.nav.pushNavigate(...)
ctx.nav.replaceNavigate(...)
ctx.nav.redirect(...)
```

`pushPatch` and `replacePatch` should be absent from `MountContext`, matching upstream Phoenix LiveView where `push_patch` during mount is rejected. Component mount and component update contexts should not expose `ctx.nav`. Component message handling should expose the full navigation facade, including patch, live navigate, and redirect, matching upstream component `handle_event` behavior.

Client event facades should be available from mount contexts. Calls during disconnected mount should be ignored because there is no connected client to receive them. Calls during connected mount should be delivered with the initial connected diff. App authors who only want connected behavior can branch on `ctx.connected`.

## Phase Types

Use separate public context traits for lifecycle phases and hook-only afterRender execution:

- `MountContext[Msg, Model]`
- `MessageContext[Msg, Model]`
- `ParamsContext[Msg, Model]`
- `AfterRenderContext[Msg, Model]`
- `ComponentMountContext[Props, Msg, Model]`
- `ComponentMessageContext[Props, Msg, Model]`
- `ComponentUpdateContext[Props, Msg, Model]`
- `ComponentAfterRenderContext[Props, Msg, Model]`

These traits should live at the `scalive` top level because phase contexts are core lifecycle API concepts, like `LiveView`, `LiveComponent`, and `LiveIO`.

The normal app-author API should not require writing those type parameters. `LiveView` and `LiveComponent` should define local aliases:

```scala
trait LiveView[Msg, Model]:
  type MountContext = scalive.MountContext[Msg, Model]
  type MessageContext = scalive.MessageContext[Msg, Model]
  type ParamsContext = scalive.ParamsContext[Msg, Model]
  type AfterRenderContext = scalive.AfterRenderContext[Msg, Model]

trait LiveComponent[Props, Msg, Model]:
  type MountContext = scalive.ComponentMountContext[Props, Msg, Model]
  type MessageContext = scalive.ComponentMessageContext[Props, Msg, Model]
  type UpdateContext = scalive.ComponentUpdateContext[Props, Msg, Model]
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]
```

This gives runtime implementations, test implementations, and advanced helper APIs ordinary parameterized traits, while lifecycle method signatures stay compact. The runtime's concrete context implementations should remain internal. The separate `scalive-test` module can implement the same public traits with recording contexts.

This is preferred over exposing type parameters directly in every callback:

```scala
def handleMessage(model: Model, ctx: MessageContext[Msg, Model]): Msg => LiveIO[Model]
```

It is also simpler than making contexts rely on abstract path-dependent type members. The `LiveView` alias is the user-facing anchor, and the underlying parameterized type remains easy to use from tests, generic helpers, and internal runtime code.

## Components

LiveComponents should preserve a clear separation between parent-owned `Props` and component-owned `Model`.

```scala
trait LiveComponent[Props, Msg, Model]:
  type MountContext = scalive.ComponentMountContext[Props, Msg, Model]
  type UpdateContext = scalive.ComponentUpdateContext[Props, Msg, Model]
  type MessageContext = scalive.ComponentMessageContext[Props, Msg, Model]
  type AfterRenderContext = scalive.ComponentAfterRenderContext[Props, Msg, Model]

  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty

  def mount(props: Props, ctx: MountContext): LiveIO[Model]

  def update(
    props: Props,
    model: Model,
    ctx: UpdateContext
  ): LiveIO[Model] =
    model

  def handleMessage(
    props: Props,
    model: Model,
    ctx: MessageContext
  ): Msg => LiveIO[Model]

  def render(
    props: Props,
    model: Model,
    self: ComponentRef[Msg]
  ): HtmlElement[Msg]
```

`mount` receives the initial props and creates the initial component model. The runtime should store the latest props separately from the model.

`update` is the parent/runtime props reconciliation callback. It should run every time the parent supplies props for an existing component identity, and every time `sendUpdate` supplies props. It should not depend on Scala equality checks between old and new props. Component-local messages should not call `update`.

`handleMessage` and `render` receive the latest props and current model. This avoids copying parent-owned props into the model solely so component events or rendering can see them.

## Params Decode Errors

Query decoding should keep the successful path fully typed while still giving applications an explicit recovery point for invalid user-controlled URLs.

`handleParams` should receive only successfully decoded query values:

```scala
def handleParams(
  model: Model,
  query: queryCodec.Out,
  url: URL,
  ctx: ParamsContext
): LiveIO[Model]
```

Decode failures should call a separate lifecycle callback with the same phase context:

```scala
def handleParamsDecodeError(
  model: Model,
  error: LiveQueryCodec.DecodeError,
  url: URL,
  ctx: ParamsContext
): LiveIO[Model]
```

The default implementation should fail the lifecycle with the decode error. Applications that want forgiving behavior can override the callback to keep the current model, flash an error, redirect, or patch to canonical query parameters.

## Capability Matrix

The public phase contexts should expose this authoritative capability matrix:

| Feature                 | Root Mount                       | Root Message | Root Params | Component Mount                  | Component Update | Component Message | Render |
| ----------------------- | -------------------------------- | ------------ | ----------- | -------------------------------- | ---------------- | ----------------- | ------ |
| connected/staticChanged | yes                              | yes          | yes         | yes                              | yes              | yes               | no     |
| navigation              | redirect/navigate only           | full         | full        | no                               | no               | full              | no     |
| flash                   | yes                              | yes          | yes         | yes                              | yes              | yes               | no     |
| title                   | yes                              | yes          | yes         | no                               | no               | no                | no     |
| client events           | yes; disconnected no-op          | yes          | yes         | yes; disconnected no-op          | yes              | yes               | no     |
| uploads                 | yes                              | yes          | yes         | component scoped                 | component scoped | component scoped  | no     |
| streams                 | yes                              | yes          | yes         | component scoped                 | component scoped | component scoped  | no     |
| async                   | yes; disconnected start is no-op | yes          | yes         | yes; disconnected start is no-op | yes              | yes               | no     |
| subscriptions           | yes; disconnected start is no-op | yes          | yes         | no                               | no               | no                | no     |
| components.sendUpdate   | no                               | yes          | yes         | no                               | no               | yes               | no     |
| hooks                   | root-supported stages            | root stages  | root stages | component-supported stages       | component stages | component stages  | no     |

Unsupported operations should not appear on a phase context. If an operation is available but can fail due to runtime state, its failure behavior should follow the public failure semantics below.

## Failure Semantics

Facade operations should use a small set of public failure rules rather than returning result ADTs for every command:

```text
Unsupported operation -> absent from the phase context type
Invalid setup or impossible command -> fail LiveIO
Missing cleanup/cancel/detach target -> succeed as no-op
Runtime/user failures -> represented in domain state or callback results
```

Concrete semantics:

| Case                                            | Public Semantics                                                                                                                          |
| ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Unsupported phase operation                     | Not available on that context type                                                                                                        |
| Duplicate hook id                               | Fail `LiveIO`                                                                                                                             |
| Missing hook detach                             | Succeed as no-op                                                                                                                          |
| Invalid navigation target or options            | Fail `LiveIO`; do not enqueue navigation                                                                                                  |
| Navigation unsupported in phase                 | Not available on that context type                                                                                                        |
| Upload invalid options                          | Fail `LiveIO`                                                                                                                             |
| Duplicate upload name or active upload conflict | Fail `LiveIO`                                                                                                                             |
| Upload validation or client errors              | Store in upload state, not `LiveIO` failure                                                                                               |
| `uploads.get(name)` missing                     | Return `None`                                                                                                                             |
| Upload cancel unknown upload name               | Fail `LiveIO`                                                                                                                             |
| Upload cancel missing or already-gone entry ref | Succeed as no-op                                                                                                                          |
| Async task failure                              | Deliver `LiveAsyncEvent(name, LiveAsyncResult.Failed(cause))` to async hooks; do not call `toMsg` or `handleMessage`; lifecycle continues |
| Async cancel missing task                       | Succeed as no-op                                                                                                                          |
| Subscription `start` duplicate id               | Fail `LiveIO`                                                                                                                             |
| Subscription `replace` existing id              | Cancel old subscription and start new one                                                                                                 |
| Subscription `replace` missing id               | Start new subscription                                                                                                                    |
| Subscription `cancel` missing id                | Succeed as no-op                                                                                                                          |
| Disconnected mount managed starts               | Succeed as no-op                                                                                                                          |
| `sendUpdate` missing component                  | Succeed as no-op, preferably visible in tests or logs                                                                                     |

This keeps lifecycle code ergonomic while still surfacing real API misuse. It also fits the `LiveIO[A]` command style without pushing common facade operations toward explicit result plumbing.

## Hooks

Lifecycle hook registration should have two public shapes.

The ergonomic default should be declarative and attached to the owner definition:

```scala
trait LiveView[Msg, Model]:
  def hooks: LiveHooks[Msg, Model] = LiveHooks.empty
```

Example:

```scala
override def hooks: LiveHooks[Msg, Model] =
  LiveHooks
    .onEvent("audit") { (model, msg, event, ctx) =>
      ctx.client.pushEvent("audit", event.kind).as(LiveEventHookResult.cont(model))
    }
    .afterRender("metrics") { (model, ctx) =>
      ctx.client.pushEvent("rendered", model.id).as(model)
    }
```

LiveComponents should expose the same declarative shape with a component-specific hook type:

```scala
trait LiveComponent[Props, Msg, Model]:
  def hooks: ComponentLiveHooks[Props, Msg, Model] = ComponentLiveHooks.empty
```

`ComponentLiveHooks` should support the component hook stages that match upstream LiveComponent hook support: `rawEvent`, `event`, `async`, and `afterRender`.

Dynamic attach and detach should also remain supported for upstream alignment. Phoenix LiveView exposes `attach_hook` and `detach_hook`, and documents hooks that detach themselves after handling a specific event. Scalive should keep that capability, but expose it as a typed secondary API through phase contexts rather than through a global `LiveContext`.

Raw client-event interception should also be hook-based rather than exposed as a separate `LiveView.interceptEvent` callback. This keeps the primary LiveView lifecycle focused on typed messages while preserving the advanced escape hatch needed for JS hook events, event replies, and low-level integrations.

Client event handling should run in this order:

```text
raw client event
-> raw event hooks
-> typed message decoding
-> typed event hooks
-> handleMessage
-> render/diff
-> afterRender hooks
```

Raw event hooks receive every client event as a `LiveEvent`. They can continue, halt, or halt with a reply. If raw hooks continue, Scalive decodes the event into the app's typed `Msg`. If decoding succeeds, typed event hooks run before `handleMessage`. If the raw event is unbound or cannot decode, typed event hooks and `handleMessage` do not run.

Hook attachment should be owner-wide, but hook execution should be stage-specific. A hook attached from any non-render context receives the context for the lifecycle stage where it eventually runs, never the attach-time context. This matches upstream `attach_hook(socket, name, stage, fun)`, where the hook is stored by stage and invoked with the current socket for that stage.

Root hook execution contexts:

| Hook Stage    | Callback Context                 | Result                       |
| ------------- | -------------------------------- | ---------------------------- |
| `rawEvent`    | `MessageContext[Msg, Model]`     | `LiveEventHookResult[Model]` |
| `event`       | `MessageContext[Msg, Model]`     | `LiveEventHookResult[Model]` |
| `params`      | `ParamsContext[Msg, Model]`      | `LiveHookResult[Model]`      |
| `info`        | `MessageContext[Msg, Model]`     | `LiveHookResult[Model]`      |
| `async`       | `MessageContext[Msg, Model]`     | `LiveHookResult[Model]`      |
| `afterRender` | `AfterRenderContext[Msg, Model]` | `LiveIO[Model]`              |

Component hook execution contexts:

| Hook Stage    | Callback Context                                 | Result                       |
| ------------- | ------------------------------------------------ | ---------------------------- |
| `rawEvent`    | `ComponentMessageContext[Props, Msg, Model]`     | `LiveEventHookResult[Model]` |
| `event`       | `ComponentMessageContext[Props, Msg, Model]`     | `LiveEventHookResult[Model]` |
| `async`       | `ComponentMessageContext[Props, Msg, Model]`     | `LiveHookResult[Model]`      |
| `afterRender` | `ComponentAfterRenderContext[Props, Msg, Model]` | `LiveIO[Model]`              |

Public hook callbacks should use explicit function parameters, with the execution-stage context last.

```scala
ctx.hooks.rawEvent.attach("audit") {
  (model: Model, event: LiveEvent, ctx: MessageContext) =>
    LiveEventHookResult.cont(model)
}

ctx.hooks.event.attach("audit") {
  (model: Model, msg: Msg, event: LiveEvent, ctx: MessageContext) =>
    LiveEventHookResult.cont(model)
}

ctx.hooks.params.attach("auth") {
  (model: Model, url: URL, ctx: ParamsContext) =>
    LiveHookResult.cont(model)
}

ctx.hooks.info.attach("pubsub") {
  (model: Model, msg: Msg, ctx: MessageContext) =>
    LiveHookResult.cont(model)
}

ctx.hooks.async.attach("load") {
  (model: Model, event: LiveAsyncEvent[Msg], ctx: MessageContext) =>
    LiveHookResult.cont(model)
}

ctx.hooks.afterRender.attach("metrics") {
  (model: Model, ctx: AfterRenderContext) =>
    ctx.client.pushEvent("rendered", model.id).as(model)
}
```

Component hook signatures should receive the latest `Props` as well as the component `Model`:

```scala
ctx.hooks.rawEvent.attach("audit") {
  (props: Props, model: Model, event: LiveEvent, ctx: ComponentMessageContext) =>
    LiveEventHookResult.cont(model)
}

ctx.hooks.event.attach("audit") {
  (props: Props, model: Model, msg: Msg, event: LiveEvent, ctx: ComponentMessageContext) =>
    LiveEventHookResult.cont(model)
}

ctx.hooks.async.attach("load") {
  (props: Props, model: Model, event: LiveAsyncEvent[Msg], ctx: ComponentMessageContext) =>
    LiveHookResult.cont(model)
}

ctx.hooks.afterRender.attach("metrics") {
  (props: Props, model: Model, ctx: ComponentAfterRenderContext) =>
    model
}
```

Params hooks receive the current `URL`, not `queryCodec.Out`, because they run before query decoding. The decoded query value belongs to `handleParams`.

Hook result types should be split by reply capability:

```scala
enum LiveEventHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[Json] = None)

object LiveEventHookResult:
  def cont[Model](model: Model): LiveEventHookResult[Model]
  def halt[Model](model: Model): LiveEventHookResult[Model]
  def haltReply[Model](model: Model, value: Json): LiveEventHookResult[Model]

enum LiveHookResult[+Model]:
  case Continue(model: Model)
  case Halt(model: Model)

object LiveHookResult:
  def cont[Model](model: Model): LiveHookResult[Model]
  def halt[Model](model: Model): LiveHookResult[Model]

final case class LiveAsyncEvent[+Msg](
  name: String,
  result: LiveAsyncResult[Msg]
)

enum LiveAsyncResult[+Msg]:
  case Succeeded(message: Msg)
  case Failed(cause: Throwable)
```

`LiveEventHookResult` should be used for `rawEvent` and typed `event` hooks because those stages can reply to client events. `LiveHookResult` should be used for `params`, `info`, and `async` hooks because those stages can halt but cannot reply. `LiveAsyncEvent` should carry the typed async message on success and the failure cause on failure. `afterRender` hooks should return `LiveIO[Model]` directly because upstream `after_render` hooks cannot halt or reply. `InterceptResult` should not remain in the target public API because standalone `interceptEvent` is replaced by raw event hooks.

Example:

```scala
import scalive.LiveIO.given

def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model] =
  case EnableAudit =>
    ctx.hooks.event.attach("audit") { (model, msg, event, ctx) =>
      ctx.client.pushEvent("audit", event.kind).as(LiveEventHookResult.cont(model))
    }.as(model)

  case DisableAudit =>
    ctx.hooks.event.detach("audit").as(model)
```

The `ctx.hooks` facade is typed by the context's `Msg` and `Model`, but app authors normally get that typing through the `LiveView` context aliases. They should not need to write `attachEventHook[Msg, Model]` manually.

Dynamic hook access should be owner-wide rather than phase-local. Every non-render root context should expose all root-supported dynamic stages: `rawEvent`, `event`, `params`, `info`, `async`, and `afterRender`. Every non-render component context should expose all component-supported dynamic stages: `rawEvent`, `event`, `async`, and `afterRender`. This matches upstream's split, where root LiveViews support `handle_event`, `handle_params`, `handle_info`, `handle_async`, and `after_render` hooks, while LiveComponents support only `after_render`, `handle_event`, and `handle_async` hooks. Scalive splits upstream `handle_event` hook behavior into `rawEvent` before typed decoding and `event` after typed decoding.

The attachment context only determines which owner receives the hook. It does not determine the callback context. For example, a `params` hook attached during `mount` still runs later with `ParamsContext`, and an `afterRender` hook attached during `handleMessage` still runs later with `AfterRenderContext`.

Mount hooks should not be exposed as dynamic hooks. They should remain a separate declarative API, equivalent to upstream `on_mount`.

Missing detach should be a no-op, matching upstream `detach_hook` behavior.

## AfterRender Hooks

`afterRender` hooks should follow upstream `:after_render` semantics. They run after render and diff calculation, cannot halt, cannot reply, and must return the next owner state directly. Updates to the returned model must not trigger a second render or change the already-computed diff; they become visible to the next lifecycle pass. Root client events pushed from an afterRender hook may still be appended to the current response payload without triggering another render.

Root `afterRender` hooks should be effectful callbacks:

```scala
ctx.hooks.afterRender.attach("metrics") {
  (model: Model, ctx: AfterRenderContext) =>
    ctx.client.pushEvent("rendered", model.id).as(model)
}
```

Component `afterRender` hooks should receive the latest props and current component model:

```scala
import scalive.LiveIO.given

ctx.hooks.afterRender.attach("metrics") {
  (props: Props, model: Model, ctx: ComponentAfterRenderContext) =>
    model
}
```

The root `AfterRenderContext` should expose only:

- `connected`
- `staticChanged`
- `client`
- `hooks`

The component `ComponentAfterRenderContext` should expose only:

- `connected`
- `staticChanged`
- `hooks`

Do not expose `nav`, `flash`, `title`, `uploads`, `streams`, `async`, `subscriptions`, or `components.sendUpdate` from afterRender contexts. Those operations either belong before diff calculation, would be ambiguous after rendering, or are not supported by upstream `after_render` semantics.

## Navigation

Navigation availability should follow upstream lifecycle semantics rather than exposing one uniform navigation capability everywhere.

Root LiveView mount may redirect or live navigate, but must not patch. Phoenix LiveView rejects `push_patch` during mount because a LiveView cannot be mounted while issuing a patch to itself. Scalive should make that unsupported operation impossible by omitting patch operations from `MountContext.nav`.

Root message and params contexts should expose the full navigation facade:

```scala
ctx.nav.pushPatch(...)
ctx.nav.replacePatch(...)
ctx.nav.pushNavigate(...)
ctx.nav.replaceNavigate(...)
ctx.nav.redirect(...)
```

Component mount should not expose navigation. Upstream `Phoenix.LiveComponent.mount/1` is component-local initialization and receives no assigns; incoming assigns arrive later through `update/2`. Component update should also not expose navigation, matching upstream behavior where redirects from `update/2` are rejected. Component message handling should expose the full navigation facade, matching upstream `handle_event/3` support for component-triggered `push_patch`, `push_navigate`, and `redirect`.

## Client Events

Mount contexts should expose `ctx.client`, matching upstream `push_event` availability during mount. Client events pushed during disconnected mount should be ignored as no-ops because static rendering has no connected client event channel. Client events pushed during connected mount should be delivered with the initial connected diff.

This keeps mount code simple while preserving an explicit escape hatch:

```scala
def mount(ctx: MountContext): LiveIO[Model] =
  for
    _ <- if ctx.connected then ctx.client.pushEvent("mounted", Map.empty) else LiveIO.succeed(())
  yield Model.empty
```

## Title

`ctx.title.set` is available from root mount, message, and params contexts. Component contexts do not expose title updates because the document title is owned by the root LiveView; components that need a title change should communicate intent to the root and let the root decide whether to update it.

## Async Tasks

Async starts should map successful task results into typed messages with `toMsg: A => Msg`:

```scala
ctx.async.start("load")(loadData) { data =>
  Msg.Loaded(data)
}
```

Successful async completions should run in this order:

```text
async task succeeds
-> toMsg
-> async hooks
-> handleMessage
-> render/diff
-> afterRender hooks
```

Successful completions call `toMsg`, then async hooks receive `LiveAsyncEvent(name, LiveAsyncResult.Succeeded(msg))`. If hooks continue, the resulting `Msg` goes directly to `handleMessage`. If an async hook halts, `handleMessage` is skipped and Scalive renders from the hook-updated model.

Async completions should not run `info` hooks. This follows upstream Phoenix LiveView, where async task results invoke the separate `handle_async` stage rather than `handle_info`.

Failed async completions should run in this order:

```text
async task fails
-> async hooks
-> render/diff
-> afterRender hooks
```

Failed completions should not call `toMsg`, because no `Msg` exists. Async hooks receive `LiveAsyncEvent(name, LiveAsyncResult.Failed(cause))`. After hooks run, Scalive should render from the hook-updated model without calling `handleMessage`, keeping the LiveView lifecycle alive.

## Subscriptions

Subscriptions should not be a dedicated `LiveView` lifecycle method. They should be explicit, named runtime resources managed through phase contexts:

```scala
ctx.subscriptions.start("clock")(
  ZStream.tick(1.second).as(Msg.Tick)
)

ctx.subscriptions.replace("room")(
  roomEvents(roomId).map(Msg.RoomEvent(_))
)

ctx.subscriptions.cancel("room")
```

Each subscription emits typed `Msg` values into the normal message pipeline:

```text
subscription emits Msg
-> info hooks
-> handleMessage
-> render/diff
-> afterRender hooks
```

This makes subscriptions the stream-shaped counterpart to async tasks: `ctx.async.start` maps one successful completion into a message, while `ctx.subscriptions.start` handles many messages over time. Runtime effects should still happen in `handleMessage`; subscription streams should only produce messages.

Managed starts that require a connected LiveView process should be safe no-ops during disconnected mount and normal starts during connected mount. This applies to both `ctx.subscriptions.start` and `ctx.async.start`, so app authors can write one mount body without branching on `ctx.connected` for these managed features.

```scala
def mount(ctx: MountContext): LiveIO[Model] =
  for
    _ <- ctx.subscriptions.start("clock")(
           ZStream.tick(1.second).as(Msg.Tick)
         )
    _ <- ctx.async.start("load")(loadData)(Msg.Loaded(_))
  yield Model.empty
```

## Streams

Public stream facades should expose mutation operations, not stream introspection:

```scala
ctx.streams.init(...)
ctx.streams.insert(...)
ctx.streams.delete(...)
```

There should be no public `ctx.streams.current` on lifecycle contexts. This aligns with upstream Phoenix LiveView, where streams are temporary render/diff machinery rather than durable application state. If app authors need to inspect business state, that state should live explicitly in the model. If tests need to assert stream behavior, testing APIs can expose recorded stream commands without making protocol-shaped stream state part of the app-author lifecycle API.

## Effect Shape

`LiveIO` should remain the public lifecycle return type, but it should no longer expose a ZIO environment parameter. Runtime capabilities are available through explicit phase contexts, not through the effect environment.

`LiveIO` should be a transparent alias over `Task`, not an opaque type. This keeps normal `Task` operations such as `map`, `flatMap`, and `as` available in app-author lifecycle code while still removing ZIO environment capabilities from public signatures.

Target shape:

```scala
type LiveIO[+A] = Task[A]

object LiveIO:
  def succeed[A](value: A): LiveIO[A] =
    ZIO.succeed(value)

  def fromTask[A](task: Task[A]): LiveIO[A] =
    task

  given [A]: Conversion[A, LiveIO[A]] =
    succeed(_)
```

`Task[A]` values already conform to `LiveIO[A]` because `LiveIO` is a type alias.

This keeps pure lifecycle branches concise:

```scala
def mount(ctx: MountContext): LiveIO[Model] =
  Model.empty

def handleMessage(model: Model, ctx: MessageContext): Msg => LiveIO[Model] =
  case Noop =>
    model

  case Save =>
    for
      _ <- ctx.flash.put("info", "Saved")
    yield model.copy(saved = true)
```

Context operations should also return `LiveIO[A]` where they are part of lifecycle code:

```scala
ctx.flash.put("info", "Saved"): LiveIO[Unit]
ctx.nav.pushPatch("/items"): LiveIO[Unit]
ctx.uploads.consumeCompleted("avatar"): LiveIO[List[LiveUploadedEntry]]
```

Internally, Scalive may still use `RIO`, services, or other runtime machinery. Those details should not appear in app-author callback signatures.

## Future Testing Model

Explicit phase contexts create a better native test API, but dedicated testing APIs do not need to live in the core library. If command-recording contexts or a mounted test harness become public API, they should live in a separate `scalive-test` module that depends on core Scalive and exposes helpers from `scalive.testing.*`.

The core `scalive` module contains only the app-author and runtime API. A future `scalive-test` module can contain public test-support APIs, recording contexts, mounted runtime harnesses, test command ADTs, and rendered DOM or diff assertions.

| Module         | Public Surface                                                                                           |
| -------------- | -------------------------------------------------------------------------------------------------------- |
| `scalive`      | Core app/runtime API only                                                                                |
| `scalive-test` | `scalive.testing.*`, recording contexts, mounted runtime harness, test command ADTs, DOM/diff assertions |

Such a module should support two complementary testing layers.

Lifecycle and unit tests should be able to call callbacks directly with recording contexts:

```scala
import scalive.testing.*

val ctx = TestContext.message[Msg, Model]()
val next <- liveView.handleMessage(model, ctx)(Save)

assertTrue(ctx.commands.flash == List(FlashCommand.Put("info", "Saved")))
assertTrue(ctx.commands.nav == List(NavCommand.PushPatch("/items")))
```

This makes lifecycle effects precise and cheap to assert without forcing tests through a socket runtime when the behavior under test is local to a callback.

Recording contexts should implement the same public phase context interfaces and record commands for flash, navigation, client events, title updates, streams, uploads, async starts and cancels, subscriptions, component updates, and hook attach or detach operations. The command ADTs should live in `scalive.testing`, not the normal app-author API.

Runtime and integration tests should assert rendered diffs, DOM output, navigation effects, pushed events, and other observable behavior through a mounted LiveView harness. These tests catch renderer, socket, protocol, and lifecycle-ordering regressions that command-recording tests cannot see.

```scala
import scalive.testing.*

val view <- LiveViewTest.mount(liveView, url = "/items")
val initial <- view.rendered

assertTrue(initial.text("#count") == "0")

val result <- view.send(Increment)

assertTrue(result.text("#count") == "1")
assertTrue(result.effects.nav == List(NavCommand.PushPatch("/items")))
```

The mounted harness should keep typed messages as the primary interaction API and add selector-based helpers for binding and form integration tests:

```scala
object LiveViewTest:
  def mount[Msg, Model](
    liveView: LiveView[Msg, Model],
    url: String = "/",
    connected: Boolean = true
  ): LiveIO[TestLiveView[Msg, Model]]

trait TestLiveView[Msg, Model]:
  def rendered: LiveIO[TestRender]
  def send(msg: Msg): LiveIO[TestRender]
  def click(selector: String): LiveIO[TestRender]
  def change(selector: String, data: FormData): LiveIO[TestRender]
  def submit(selector: String, data: FormData): LiveIO[TestRender]
```

Rendered assertions should default to stable DOM or HTML queries while still allowing targeted diff assertions:

```scala
trait TestRender:
  def html: String
  def text(selector: String): String
  def exists(selector: String): Boolean
  def attr(selector: String, name: String): Option[String]
  def effects: TestEffects
  def diff: Option[TestDiff]
```

Observable effects should be grouped in a test-only value:

```scala
trait TestEffects:
  def flash: List[FlashCommand]
  def nav: List[NavCommand]
  def clientEvents: List[ClientEventCommand]
  def title: Option[String]
  def streams: List[StreamCommand]
  def componentUpdates: List[ComponentUpdateCommand]
```

`Socket`, `WebSocketMessage`, outbox queues, and protocol payloads should remain internal implementation details. The test module may use them internally, but public tests should interact through recording contexts, typed messages, selector helpers, `TestEffects`, and `TestDiff`.

Some operations need real runtime semantics, especially uploads, streams, and async tasks. Tests can provide either lightweight fakes or an in-memory runtime depending on the scenario. Command assertions should be ergonomic, but they should complement rather than replace black-box rendered output tests.

## Alternatives Considered

### Keep Global `LiveContext` Functions

Example:

```scala
LiveContext.putFlash("info", "Saved")
LiveContext.pushPatch("/items")
```

Pros:

- Minimal lifecycle signatures.
- Maps well to the current ZIO environment implementation.
- Capability traits can model availability.

Cons:

- Weak discoverability.
- Error messages expose capability internals.
- Users must learn a generic context service.
- Unsupported operations can become runtime failures or no-ops.

### Implicit Phase Contexts

Example:

```scala
def handleMessage(model: Model)(using ctx: MessageContext): Msg => LiveIO[Model]
```

Pros:

- Less parameter passing in helper methods.
- Can feel idiomatic in advanced Scala 3 code.

Cons:

- Less explicit at call sites and in generated documentation.
- Implicit-resolution failures can be confusing.
- It is less obvious where operations come from.

This can be an optional advanced style later, but it should not be the primary API.

### Phoenix-Style Socket Object

Example:

```scala
def mount(socket: LiveSocket[Model]): LiveIO[LiveSocket[Model]] =
  socket.assign(Model.empty).putFlash("info", "Welcome")
```

Pros:

- Familiar to Phoenix LiveView users.
- Compact and fluent.

Cons:

- Weakens the `LiveView[Msg, Model]` mental model.
- Encourages socket-as-state-bag thinking.
- Makes model ownership less clear.
- Pushes Scalive toward Phoenix parity instead of Scala-first ergonomics.

### Return Commands With The Model

Example:

```scala
LiveUpdate(model)
  .putFlash("info", "Saved")
  .pushPatch("/items")
```

Pros:

- Very testable.
- Effects are inspectable values.
- Pure message handlers are possible.

Cons:

- Awkward for operations that need runtime results, such as consuming uploads.
- Async, streams, and uploads become more complex.
- Risks turning the API into an Elm-like command architecture.

This is useful as an internal testing or recording model, but not sufficient as the main public API.

### Capability-Specific Parameters

Example:

```scala
def handleMessage(model: Model, nav: Navigation, flash: Flash): Msg => LiveIO[Model]
```

Pros:

- Very explicit dependencies.
- Small focused types.

Cons:

- Callback signatures become noisy.
- Adding a capability is disruptive.
- Less pleasant than grouped access through `ctx.nav` and `ctx.flash`.
