{%
title = "Browser integration"
description = "Composed JS commands and a focused hook exchange correlated, typed browser events."
order = 16
section = examples
%}

Two ways to run code in the browser: a composed `JS` command that never reaches
the server, and a JavaScript hook that copies text for the server and reports a
typed result back.

## Try it {#try-it}

1. Click **Run composed command**. The placeholder disappears, and a panel
   reads "The command showed this panel." with "Run it again to toggle this
   detail."
2. Click **Run composed command** again. The detail line disappears; the panel
   stays.
3. Click **Copy sample text**. The status reads "Browser operation completed.",
   and the sample sentence is on your clipboard. If the browser denies clipboard
   access, the status reads "Browser operation could not be completed."
4. Click **Reset browser integration** to restore the placeholder and status.

@:example(browser-integration)

## How it works {#how-it-works}

- `JS.show(...).hide(...).toggle(...)` composes the first button's command. It
  runs in the browser without a server message.
- **Copy sample text** sends `Msg.CopySample`. The handler pushes a typed
  `ServerToBrowserEvent` with a request ID through `ctx.client.push`.
- The `BrowserInterop` hook copies the text and pushes a typed
  `BrowserToServerEvent` with the same request ID. The LiveView receives it as
  `Msg.CopyFinished` and accepts only the result for the pending request.
- A denied clipboard is an ordinary failed result, not a crash.

## Related guides {#related-guides}

- [Browser commands, events, and hooks](../guides/browser-integration.md#choose-the-boundary)
  explains commands, directional events, hook IDs, and correlation.
