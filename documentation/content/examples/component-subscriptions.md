{%
title = "Component-owned subscriptions"
description = "Two component instances reuse one local subscription key while independently starting, replacing, cancelling, and surviving brief removal."
order = 7
section = examples
%}

Both ticker cards use the same @:apiSymbol(opaque-type:scalive.SubscriptionKey)`SubscriptionKey`@:@,
but each registration belongs to its exact runtime component instance. Cancel or
replace one ticker and the other continues independently.

The parent visibility control also exposes the component removal boundary. Leave
the first ticker absent long enough for the browser to confirm its destruction,
then reinsert it to get a fresh model and mount-started stream. Remove and
reinsert it very quickly, before that confirmation reaches the server, and the
retained model returns while its stored stream starts again with a fresh runtime
token. Tick delivery is `Lossless` while a worker is running so the visible
counter records every delivered tick, but interruption still provides no replay
or lossless guarantee for values emitted while the worker was stopped.

The example's reset increments a parent-owned epoch. Each component update sees
that changed prop, replaces its local stream, and clears its model. The ordinary
initial update keeps the mount-started registration untouched, avoiding a
duplicate `start`.

@:example(component-subscriptions)

Related guidance: [own work in an exact component instance](../guides/components-and-communication.md#use-component-local-capabilities).
