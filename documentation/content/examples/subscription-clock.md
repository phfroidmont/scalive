{%
title = "Managed clock subscription"
description = "An instance-scoped subscription key starts, replaces, cancels, and resets a managed clock stream."
order = 12
section = examples
%}

A LiveView that receives a message from a clock stream on every tick. Scalive
runs the stream for the LiveView and stops it when asked or when the page
closes, without a fiber to manage.

## Try it {#try-it}

1. Click **Start every second**. **Mode** shows "Every second", and **Ticks
   received** counts up once a second.
2. Click **Replace with fast clock**. **Mode** shows "Four times per second",
   and the count speeds up without starting over.
3. Click **Cancel clock**. **Mode** shows "Stopped", and the count stays where
   it was.

@:example(subscription-clock)

## How it works {#how-it-works}

- `ctx.subscriptions.start(ClockSubscription, ...)(ticks(1.second))` runs the
  stream under a key. **Start every second** is disabled while the clock runs,
  because starting a registered key again is an error.
- `ctx.subscriptions.replace` swaps the stream under the same key, and
  `ctx.subscriptions.cancel` stops it. The model's `mode` follows each change,
  so the buttons describe the registered state.
- Each tick arrives in `handleMessage` as `Msg.Tick`. `Lossless` delivery
  counts every tick.
- The key is derived from the embedded instance's ID. It names one stream
  inside this LiveView; it is not a global subscription name.

## Related guides {#related-guides}

- [Subscriptions](../guides/subscriptions.md#own-long-lived-streams-with-subscriptions)
  explains keys, replacement, and delivery policies.
