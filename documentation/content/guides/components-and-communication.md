{%
title = "Stateful components and communication"
description = "Isolate state in typed LiveComponents and communicate explicitly with their immediate owner."
order = 31
section = guides
group = "State, services, and components"
%}

## Prerequisites {#prerequisites}

Start with a `LiveView` that binds typed events and keeps rendered state in its
model. Read [Typed forms and validation](typed-forms-and-validation.md) before
moving a form into a component.

## Choose A Stateful Component {#choose-a-stateful-component}

Use a @:apiSymbol(trait:scalive.LiveComponent)`LiveComponent`@:@ when one reusable
piece of application UI needs its own model, messages, and lifecycle. Keep
ordinary markup in functions when it does not need isolated state. Every stateful
instance is identified by its component class and stable logical ID.

Use a [nested LiveView](nested-liveviews.md) instead when the child needs a
separate socket rather than only isolated state.

```scala
private val ScalaVote = component(VoteComponent, "scala-vote")
private val ZioVote   = component(VoteComponent, "zio-vote")
```

The ID is application identity within that component class. The runtime's
numeric @:apiSymbol(class:scalive.ComponentRef)`ComponentRef`@:@ is temporary and
should not enter domain messages or persisted state.

## Separate Props, Messages, Model, And Output {#separate-component-types}

A component has four independent roles:

- `Props` are values supplied by the owner.
- `Msg` values are inputs handled by the component.
- `Model` is state isolated to one component instance.
- `Output` values report domain events to the immediate owner.

Declare an output-producing component with
@:apiSymbol(trait:scalive.LiveComponent.WithOutput)`LiveComponent.WithOutput`@:@:

```scala
object VoteComponent
    extends LiveComponent.WithOutput[Props, Msg, Model, Output]:
  enum Msg:
    case Vote
    case Reset

  enum Output:
    case VoteChanged(id: String, votes: Int)
```

Use the ordinary three-parameter `LiveComponent` when the component has no
outputs. Its output type is `Nothing`, so it retains the simpler `render(props)`
placement API.

## Keep Local Events Local {#keep-local-events-local}

Bindings rendered by a component deliver its `Msg` values to that exact
component. Target `self` explicitly when a binding needs the current runtime
component reference:

```scala
button(on.click.to(self)(Msg.Vote), "Vote")
```

Each stable instance owns a separate model. Voting in `scala-vote` therefore
does not modify `zio-vote`.

## Map Component Outputs {#map-component-outputs}

Emit only from `handleMessage`; mount, update, view construction, and after-render contexts
do not expose this capability:

```scala
case Msg.Vote =>
  val updated = model.copy(votes = model.votes + 1)
  ctx.emit(Output.VoteChanged(props.id, updated.votes)).as(updated)
```

The placement maps every output into a message accepted by its immediate owner:

```scala
ScalaVote.render(
  scalaProps(model),
  output => output match
    case VoteComponent.Output.VoteChanged(id, votes) =>
      Msg.ComponentReported(id, votes)
)
```

Scala rejects a mapper that returns another owner's message type, and an
output-producing component cannot be rendered without a mapper. A child nested
inside another component maps to that component's `Msg`; forwarding to a root
LiveView remains explicit at each boundary.

Output delivery is queued. The component finishes its current transition and
render first, then the owner handles the mapped message in a separate serialized
server-message turn. This matches Phoenix LiveView's mailbox behavior without
exposing untyped tuples or process IDs.

## Send Props From Parent To Component {#send-props-to-components}

Changed props in an ordinary parent render invoke the component's `update`
lifecycle. For an explicit update to an already mounted instance, call
@:apiSymbol(def:scalive.ComponentUpdates.sendUpdate)`ctx.components.sendUpdate`@:@:

```scala
case Msg.UpdateScalaProps =>
  ctx.components.sendUpdate(ScalaVote, revisedProps).as(updatedParentModel)
```

`update` receives the existing component model. Preserve it unless the props
represent a deliberate reset:

```scala
override def update(props: Props, model: Model, ctx: UpdateContext) =
  ZIO.succeed(
    if props.resetEpoch == model.resetEpoch then model
    else Model(votes = 0, resetEpoch = props.resetEpoch)
  )
```

`sendUpdate` to an absent instance is ignored with a warning. Several explicit
updates queued before one render use the last props value.

## Use Component-Local Capabilities {#use-component-local-capabilities}

A component is more than a model and message handler. Its lifecycle contexts
expose the same focused tools needed to implement a self-contained UI unit:

- typed form bindings rendered inside the component deliver component `Msg`
  values;
- `ctx.uploads`, `ctx.streams`, and `ctx.async` use namespaces scoped to that
  exact component instance;
- `ctx.client.push` and `ctx.client.exec` queue browser events or commands;
- `ctx.hooks` installs dynamic component hooks, while `hooks` declares static
  hooks for every instance.

Async completions return as component messages. Upload and stream names may be
reused by another component instance without collision. Client effects and
async work are connected-only; upload and stream configuration may also be
created for disconnected rendering.

These capabilities remain local only where the runtime owns local state.
Component flash uses the owning LiveView's `ctx.flash`, and navigation requested
through message-phase `ctx.nav` navigates or patches the owning socket. It does
not create a route or history boundary around the component.

## Target Deliberately {#target-deliberately}

Ordinary typed bindings in a component subtree are wrapped for the current
component automatically. Prefer that default. For an event rendered elsewhere:

- `on.click.to(instance)(message)` routes by stable component class and logical
  ID, without depending on a numeric client ID;
- `on.click.to(self)(message)` emits `phx-target` for the current runtime
  `ComponentRef`;
- `on.click.toComponent(Component)(message)` only fixes the accepted component
  class. Add `phx.target(self)` or a `DomSelector` to choose the actual client
  target according to Phoenix targeting semantics.

Targets are limited to mounted components in the owning LiveView socket. They
do not cross into another nested LiveView's socket, and a missing exact target
does not queue work for a future mount. Use typed outputs to communicate upward
instead of treating selectors or numeric component IDs as an application
message bus.

## Remove Components Cleanly {#remove-components-cleanly}

Stop rendering an instance to remove it. Once the browser confirms that its
component ID was destroyed, Scalive drops the instance and its dynamic hooks,
removes its upload and stream scopes, and interrupts its async tasks. A later
render of the same class and logical ID is therefore a fresh mount, not a
revival of the old model.

Do not retain `ComponentRef`, upload snapshots, or other runtime handles after
removal. Put durable data in the parent or an application service before hiding
the component. A failed component lifecycle fails the active render or message
lifecycle; model expected failures as component messages when the UI should
recover without taking down the owning socket.

## Choose Eventless When There Are No Messages {#choose-eventless-components}

Extend `LiveComponent.Eventless[Props, Model]` when a stateful component mounts,
updates from props, and renders but cannot receive server messages. Its `Msg` is
`Nothing`, so server event bindings are rejected and no unreachable
`handleMessage` implementation is required. Use an ordinary render function
instead when even component-local state and lifecycle capabilities are
unnecessary.

## Test Identity And Both Directions {#test-component-communication}

Connected tests should prove that local state is isolated, output attribution
uses stable application identity, prop updates preserve local state, and reset
restores both parent and component models. Remount tests should also confirm that
component state does not leak between socket lifecycles.

The complete voting example is extracted from executable source:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-components-example)

Try both communication directions in the
[voting components example](../examples/voting-components.md).

## Related Tasks {#related-tasks}

- Use [Nested LiveViews](nested-liveviews.md) for independent socket ownership,
  sticky navigation, and crash isolation.
- Build component forms with [Typed forms and validation](typed-forms-and-validation.md).
- Add component-owned files with [File uploads](uploads-and-consumption.md).
- Manage finite component work with
  [Asynchronous work and subscriptions](async-work-and-subscriptions.md).
- Compose client effects with
  [Browser commands, events, and hooks](browser-integration.md).
