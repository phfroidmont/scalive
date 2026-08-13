{%
title = "Managed clock subscription"
description = "An instance-scoped subscription key starts, replaces, cancels, and resets a managed clock stream."
order = 9
section = examples
%}

Start the clock at one tick per second, then replace it with a faster stream
without managing a fiber yourself. Cancel stops delivery while retaining the
visible tick history. Reset both cancels the stream and restores the initial
model.

Each embedded instance derives its @:apiSymbol(opaque-type:scalive.SubscriptionKey)`SubscriptionKey`@:@
from its documentation instance ID. The key names one resource inside its
owning LiveView; it is not application state or a global subscription name.

@:example(subscription-clock)

Related guidance: [own long-lived streams with subscriptions](../guides/async-work-and-subscriptions.md#own-long-lived-streams-with-subscriptions).
