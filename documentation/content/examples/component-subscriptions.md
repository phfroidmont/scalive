{%
title = "Component-owned subscriptions"
description = "Two component instances reuse one local subscription key while independently starting, replacing, cancelling, and surviving brief removal."
order = 13
section = examples
%}

Two instances of one component, each running its own clock stream under the
same @:apiSymbol(opaque-type:scalive.SubscriptionKey)`SubscriptionKey`@:@. Each
registration belongs to its exact component instance, so the instances never
interfere.

## Try it {#try-it}

1. Watch both tickers. They count up once a second from the moment the page
   connects.
2. Click **Replace local ticks** in **First ticker**. It counts four times per
   second, while **Second ticker** keeps its pace.
3. Click **Cancel local ticks** in **Second ticker**. It stops, while the first
   keeps counting.
4. Click **Remove first ticker**, wait a few seconds, then click **Reinsert
   first ticker**. It starts again from 0 ticks, once a second: the component
   mounted afresh.

@:example(component-subscriptions)

## How it works {#how-it-works}

- The component's `mount` starts its stream from the `Connection.Connected`
  branch, so each instance subscribes as soon as it mounts in a connected page.
- Both instances use the key `component-local-ticks`. Subscription keys are
  scoped to the exact component instance, so the registrations do not collide.
- When the parent stops rendering an instance, Scalive keeps it dormant until
  the browser confirms its removal. Reinserted before that confirmation, the
  instance would keep its model and restart its stream; after it, as in step 4,
  the instance mounts afresh. The confirmation usually arrives within a moment,
  so the first case is hard to reproduce by hand.
- The parent's `Reset` message changes a reset epoch in the props. Each
  instance's `update` sees it, replaces its stream, and clears its count. An
  ordinary update leaves the mount-started stream alone, which avoids a
  duplicate `start`.

## Related guides {#related-guides}

- [Subscriptions](../guides/subscriptions.md#component-subscriptions) explains
  component-owned streams and dormancy.
- [Stateful components and communication](../guides/components-and-communication.md#use-component-local-capabilities)
  covers the other component-local capabilities.
