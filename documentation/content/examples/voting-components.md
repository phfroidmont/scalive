{%
title = "Voting components"
description = "Stable component instances isolate local votes while typed outputs report changes to their parent LiveView."
order = 4
section = examples
%}

Two instances of one stateful component, each with its own vote count. Each
instance reports its votes to the parent LiveView through a typed output.

## Try It {#try-it}

1. Click **Vote** twice in the **Scala language** card. Its count shows 2, the
   **ZIO ecosystem** card stays at 0, and the parent reads "scala-vote reported
   2 votes."
2. Click **Parent updates Scala props**. The Scala card shows `props r1` while
   its vote count stays 2.
3. Click **Reset** in the Scala card. Its count returns to 0, and the parent
   reads "scala-vote reported 0 votes."

@:example(voting-components)

## How It Works {#how-it-works}

- `component(VoteComponent, "scala-vote")` names one instance. Its stable ID
  keeps its model across the parent's renders.
- The card's buttons combine `phx.target(self)` with
  `on.click.to(self)(Msg.Vote)`, so a click reaches this instance rather than
  the parent.
- `ctx.emit(Output.VoteChanged(...))` reports to the parent, which maps each
  output to its own `Msg.ComponentReported` where it renders the component.
- `ctx.components.sendUpdate` sends new props. The component's `update` keeps
  the vote count unless the props carry a new reset epoch.

## Related Guides {#related-guides}

- [Stateful components and communication](../guides/components-and-communication.md#map-component-outputs)
  covers outputs, targets, and props updates.
