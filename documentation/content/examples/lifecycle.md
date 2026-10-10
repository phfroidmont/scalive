{%
title = "Lifecycle and connection state"
description = "Connection-aware mounting, keyed flash messages, page-title projection, and after-render effects."
order = 3
section = examples
%}

A LiveView that shows which mount produced its model, puts and clears a flash
message, and derives a page title from its model.

## Try It {#try-it}

1. Read **Mount phase**. It shows "Connected socket mount": the HTML from the
   first request showed "Disconnected HTTP mount", and the connected mount
   built a new model.
2. Click **Show notification**. "Your notification is ready." appears. Click
   **Clear notification** to remove it.
3. Click **Request attention**. **Projected page title** changes to "Attention
   needed". The browser tab keeps the documentation's title, because this
   example is nested inside the documentation page and only a page's root
   LiveView owns the title.
4. Click **Reset example** to restore the title and clear the notification.

@:example(lifecycle)

## How It Works {#how-it-works}

- `mount` stores `ctx.connection.isConnected`, so each mount records its own
  phase.
- `ctx.flash.put` and `ctx.flash.clear` change the `notification` flash, and
  `flash(NotificationFlash)` renders it.
- `pageTitle` derives the title from the model. The root layout would render it
  with `liveTitle` for a root LiveView.
- `connection.visibleWhenConnected` and `connection.visibleWhenDisconnected`
  switch the LiveSocket label in the browser, without a server message.
- The after-render hook logs each connected render. It observes the render and
  cannot change the model.

## Related Guides {#related-guides}

- [Lifecycle, state ownership, and reconnects](../learn/lifecycle-and-connection-behavior.md#two-independent-mounts)
  explains the two mounts.
- [Lifecycle feedback and page state](../guides/flash-title-and-lifecycle-ux.md#render-keyed-flash)
  covers flash, titles, and connection feedback.
