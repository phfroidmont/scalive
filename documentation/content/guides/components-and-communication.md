{%
title = "Stateful components and communication"
description = "Isolate state in typed LiveComponents and communicate explicitly with their immediate owner."
order = 23
section = guides
group = "Building applications"
%}

## Choose A Stateful Component {#choose-a-stateful-component}

Use a @:apiSymbol(trait:scalive.LiveComponent)`LiveComponent`@:@ when one reusable
piece of application UI needs its own model, messages, and lifecycle. Keep
ordinary markup in functions when it does not need isolated state. Every stateful
instance is identified by its component class and stable logical ID.

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

Emit only from `handleMessage`; mount, update, render, and after-render contexts
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

## Test Identity And Both Directions {#test-component-communication}

Connected tests should prove that local state is isolated, output attribution
uses stable application identity, prop updates preserve local state, and reset
restores both parent and component models. Remount tests should also confirm that
component state does not leak between socket lifecycles.

The complete voting example is extracted from executable source:

@:sourceRegion(documentation/site/src/scalive/docs/examples/VotingComponentsExample.scala, voting-components-example)

Try both communication directions in the
[voting components example](../examples/voting-components.md).
