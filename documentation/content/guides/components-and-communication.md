{%
title = "Stateful components and communication"
description = "Isolate state in typed LiveComponents and communicate explicitly with their immediate owner."
order = 30
section = guides
group = "State and components"
%}

## Before you start {#prerequisites}

Start with a `LiveView` whose rendered model changes in response to a typed
message. If the component will own a form, first make that form work with
[typed validation](typed-forms-and-validation.md).

## Choose a stateful component {#choose-a-stateful-component}

Use a @:apiSymbol(trait:scalive.LiveComponent)`LiveComponent`@:@ when one reusable
piece of application UI needs its own model, messages, and lifecycle. Keep
ordinary markup in functions when it does not need isolated state. Every stateful
instance is identified by its component class and stable logical ID.

Use a [nested LiveView](nested-liveviews.md) instead when the child needs a
separate LiveView lifecycle rather than only isolated state.

The recipes on this page come from the
[voting components example](../examples/voting-components.md). Its parent
declares two instances of one component class:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-component-ids)

The ID is application identity within that component class. An
@:apiSymbol(opaque-type:scalive.ComponentRef)`ComponentRef`@:@ is an opaque,
type-safe semantic identity for the exact mounted component. Its representation
is deliberately hidden: do not treat it as a numeric CID or persist it as domain
state.

## Separate props, messages, model, and output {#separate-component-types}

The component APIs separate four independent roles:

- `Props` are values supplied by the owner.
- `Msg` values are inputs handled by the component.
- `Model` is state isolated to one component instance.
- `Output` values, when declared, report domain events to the immediate owner.

Only an output-producing component declares `Output`, through
@:apiSymbol(trait:scalive.LiveComponent.WithOutput)`LiveComponent.WithOutput`@:@.
An ordinary `LiveComponent[Props, Msg, Model]` has no output type parameter and
uses the simpler `render(props)` placement API.

The vote component counts votes locally and reports each change as an output.
Its model also keeps the parent's reset epoch, which
[Send props from parent to component](#send-props-to-components) explains:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-component-logic)

## Keep local events local {#keep-local-events-local}

Bindings rendered by a component accept its `Msg` values. Pair
`phx.target(self)` with `on.click.to(self)` so the Phoenix client sends the event
to that exact runtime component rather than the owning LiveView:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-local-binding)

Each stable instance owns a separate model. Voting in `scala-vote` therefore
does not modify `zio-vote`.

## Map component outputs {#map-component-outputs}

Emit only from `handleMessage`; mount, update, view construction, and after-render contexts
do not expose this capability. The placement maps every output into a message
accepted by its immediate owner:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-render-component)

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-output-mapping)

Scala rejects a mapper that returns another owner's message type, and an
output-producing component cannot be rendered without a mapper. A child nested
inside another component maps to that component's `Msg`; forwarding to a root
LiveView remains explicit at each boundary.

Output delivery is queued. The component finishes its current transition and
render first, then the owner handles the mapped message in a separate serialized
server-message turn. This matches Phoenix LiveView's mailbox behavior without
exposing untyped tuples or process IDs.

## Send props from parent to component {#send-props-to-components}

Changed props in an ordinary parent render invoke the component's `update`
lifecycle. For an explicit update to an already mounted instance, call
@:apiSymbol(def:scalive.ComponentUpdates.sendUpdate)`ctx.components.sendUpdate`@:@:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-send-update)

`update` receives the existing component model. Preserve it unless the props
represent a deliberate reset. The parent's **Reset** increments `resetEpoch` in
every instance's props, and the component starts over when the epoch it stored
no longer matches:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-component-update)

`sendUpdate` to an absent instance is ignored with a warning. Several explicit
updates queued before one render use the last props value.

## Use component-local capabilities {#use-component-local-capabilities}

A component is more than a model and message handler. Its lifecycle contexts
expose the same focused tools needed to implement a self-contained UI unit:

- typed form bindings rendered inside the component deliver component `Msg`
  values;
- `ctx.uploads`, `ctx.streams`, `ctx.async`, and `ctx.subscriptions` use
  namespaces scoped to that exact component instance;
- `ctx.client.push` and `ctx.client.exec` queue browser events or commands;
- `ctx.hooks` installs dynamic component hooks, while `hooks` declares static
  hooks for every instance.

Async completions return through the component's typed async hooks and then
`handleMessage`. Subscription values use the component's typed event hooks and
then `handleMessage`. Neither is routed through root info hooks. Upload, stream,
and subscription names may be reused by another component instance without
collision. Client effects, async work, and subscriptions are connected-only;
upload and rendered-stream configuration may also be created for disconnected
rendering.

Start a required component subscription from the `Connection.Connected` branch
of mount or update via
@:apiSymbol(def:scalive.ComponentConnected.subscriptions)`connected.subscriptions`@:@.
Use @:apiSymbol(def:scalive.ComponentMessageContext.subscriptions)`ctx.subscriptions`@:@
for local start, replacement, and cancellation controls. Do not also start the
same key unconditionally from an initial update after mount. A dormant
registration still occupies its key, so `start` rejects it as a duplicate. Use
`replace` when changed props deliberately change the stream.

A subscription source must acquire per-run handles inside `ZStream.scoped` or
`ZStream.unwrapScoped`. Do not capture a previously acquired handle: replacement
and revival run the stored stream again after the previous run's scope has been
released.

These capabilities remain local only where the runtime owns local state.
Component flash uses the owning LiveView's `ctx.flash`, and navigation requested
through message-phase `ctx.nav` navigates or patches the owning LiveView. It does
not create a route or history boundary around the component.

## Target deliberately {#target-deliberately}

For a component-local browser event, specify `phx.target(self)` alongside the
typed binding. The client target and the Scala message target are distinct:

- `on.click.to(instance)(message)` routes by stable component class and logical
  ID, without depending on a numeric client ID;
- `on.click.to(self)(message)` selects the current runtime `ComponentRef` for
  typed dispatch; `phx.target(self)` separately emits the client's `phx-target`;
- `on.click.toComponent(Component)(message)` only fixes the accepted component
  class. Add `phx.target(self)` or a `DomSelector` to choose the actual client
  target according to Phoenix targeting semantics.

Targets are limited to mounted components in the owning LiveView. They do not
cross into a nested LiveView, and a missing exact target
does not queue work for a future mount. Use typed outputs to communicate upward
instead of treating selectors or numeric component IDs as an application
message bus.

## Remove components cleanly {#remove-components-cleanly}

Stop rendering an instance to request removal. Before the browser confirms that
its component ID was destroyed, Scalive keeps the exact instance dormant: its
model and scoped registrations are retained, while active async and subscription
workers are interrupted. If rendering reintroduces that instance first, its
model returns and an active retained subscription automatically reruns its stored
`ZStream` with fresh internal tokens. Component async tasks do not restart.
Old pending subscription values are discarded, and interruption provides no
replay or lossless-delivery guarantee.

Once the browser confirms destruction, Scalive drops the instance and its
dynamic hooks, removes its upload, rendered-stream, and subscription scopes, and
retires its async tasks. A later render of the same class and logical ID is then
a fresh mount, not a revival of the old model; its mount may register entirely
new subscriptions. Cancelled, completed, or defective retained registrations
are not automatically restarted when a dormant instance returns.

Do not retain a `ComponentRef`, upload snapshots, or other runtime handles after
removal. Put durable data in the parent or an application service before hiding
the component. A failed component lifecycle fails the active render or message
lifecycle; model expected failures as component messages when the UI should
recover without taking down the owning LiveView.

## Choose `Eventless` when there are no messages {#choose-eventless-components}

Extend `LiveComponent.Eventless[Props, Model]` when a stateful component mounts,
updates from props, and renders but cannot receive server messages. Its `Msg` is
`Nothing`, so server event bindings are rejected and no unreachable
`handleMessage` implementation is required. Use an ordinary render function
instead when even component-local state and lifecycle capabilities are
unnecessary.

## Test identity and both directions {#test-component-communication}

Connected tests should prove that local state is isolated, output attribution
uses stable application identity, prop updates preserve local state, and reset
restores both parent and component models. Remount tests should also confirm that
component state does not leak between LiveView lifecycles.

The complete voting example is extracted from executable source:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-components-example)

The component subscription example demonstrates exact-instance namespaces and
the parent-controlled visibility boundary:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ComponentSubscriptionsExample.scala, component-subscriptions-example)

Try both communication directions in the
[voting components example](../examples/voting-components.md), then compare
fresh destruction with brief dormancy in the
[component-owned subscriptions example](../examples/component-subscriptions.md).

## Related tasks {#related-tasks}

- Use [Nested LiveViews](nested-liveviews.md) for an independent lifecycle,
  sticky navigation, and crash isolation.
- Build component forms with [Typed forms and validation](typed-forms-and-validation.md).
- Add component-owned files with [File uploads](uploads-and-consumption.md).
- Manage component work with [Asynchronous work](async-work.md) and
  [Subscriptions](subscriptions.md).
- Compose client effects with
  [Browser commands, events, and hooks](browser-integration.md).
