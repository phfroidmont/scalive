{%
title = "Lifecycle and connection behavior"
description = "Understand disconnected and connected mounts, message renders, reconnects, and lifecycle cleanup."
order = 5
section = learn
%}

## Two Independent Mounts {#two-independent-mounts}

A routed LiveView first mounts during the ordinary HTTP request. Its
`MountContext.connected` value is `false`, and the resulting HTML must already
be useful. When the browser's LiveSocket joins, Scalive mounts a new lifecycle
with `connected` set to `true`.

These are two model instances, not two phases mutating one shared model. Both
mounts should produce valid state from their inputs. Use `ctx.connected` to
start work that requires a live socket, such as a subscription, but do not rely
on the disconnected model to carry data into the connected mount.

```scala
def mount(ctx: MountContext): LiveIO[Model] =
  for
    model <- loadInitialModel
    _ <- ctx.subscriptions
           .start(Updates)(updates)
           .when(ctx.connected)
  yield model
```

The lifecycle example records which mount created its model:

@:sourceRegion(documentation/site/src/scalive/docs/examples/LifecycleExample.scala, lifecycle-example)

## Handle Connected Updates {#handle-connected-updates}

After a connected event resolves to a typed message, `handleMessage` receives
the committed model and returns a proposed next model. Scalive renders that
model, computes a diff, and commits it after the render succeeds. The browser
then applies the patch to its existing DOM.

Async completions and subscription values enter the same typed message flow.
Keep durable application state in the model or an injected service; lifecycle
capabilities such as flash, subscriptions, async work, and navigation belong to
the context for the phase in which they are valid.

## Treat Reconnect As A New Lifecycle {#treat-reconnect-as-a-new-lifecycle}

If the transport reconnects and rejoins, the LiveView mounts again. Rebuild its
initial model from durable inputs, restart connection-scoped work from the new
mount, and expect the old socket's subscriptions, async tasks, and nested
LiveViews to be released.

Do not use a module-level mutable value as visitor state. A model is isolated to
one LiveView instance; a service injected through ZIO can deliberately outlive
that instance when the application needs shared or durable state.

## Render Connection State Declaratively {#render-connection-state-declaratively}

`connection.visibleWhenConnected` and `connection.visibleWhenDisconnected`
render Phoenix lifecycle bindings that update when the socket connects or
disconnects. They are useful for an offline label or for disabling controls
whose events cannot reach the server.

The server does not receive a normal application message merely because the
transport drops. Use these declarative bindings for immediate browser feedback,
and design reconnect behavior around remounting rather than an assumed
disconnect callback.

## Run Effects At The Right Time {#run-effects-at-the-right-time}

Static `LiveHooks.afterRender` hooks run after a successful render and return
only `Unit`. Use them for effects that observe completed rendered state, not to
create model state after the diff has already been built. Check `ctx.connected`
inside a hook when its effect should run only for the live lifecycle.

`pageTitle(model)` is another projection from committed model state. A routed
root LiveView owns the document title; nested LiveViews do not. The
[lifecycle UX guide](../guides/flash-title-and-lifecycle-ux.md#derive-the-page-title)
shows title and flash behavior in their application context.

This completes the ordered Learn path. Use the [Guides](../guides/index.md#solve-a-task)
for focused implementation tasks and the [Examples](../examples/index.md) to
exercise individual features.
