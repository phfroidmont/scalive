{%
title = "Typed counter"
description = "A LiveView with a count model and Decrement, Reset, and Increment messages."
order = 1
section = examples
%}

A @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ whose model is one `count`.
Each button sends a typed message, and the LiveView returns the next count.

## Try It {#try-it}

1. Click **Increase** twice. The current model shows `count = 2`.
2. Click **Decrease**. It shows `count = 1`.
3. Click **Reset**. It shows `count = 0`.

@:example(counter)

## How It Works {#how-it-works}

- `on.click(Msg.Increment)` binds a button to a constant message. The browser
  reports the click; it never constructs the message.
- `handleMessage` matches each `Msg` case and returns the next model with
  `model.copy(...)`.
- `view` renders `model.map(_.count.toString)`, so a click updates only the
  number in the page.

## Related Guides {#related-guides}

- [Quick start](../learn/quick-start.md) builds a counter from scratch.
- [Models, messages, and effects](../learn/models-and-messages.md#give-the-counter-a-model)
  gives the counter a model and a Reset message.
