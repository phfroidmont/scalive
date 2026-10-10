{%
title = "Bounded activity stream"
description = "Durable activity history drives an opaque stream handle with stable IDs and a five-row DOM window."
order = 5
section = examples
%}

A rendered stream that shows the five newest activities while the model keeps
the complete history. The @:apiSymbol(type-alias:scalive.LiveStream)`LiveStream`@:@
sends the browser one change per insert or delete instead of re-rendering the
list.

## Try It {#try-it}

1. Click **Insert activity** twice. **Durable history** shows 6, and the list
   shows the five newest rows: the oldest row has left the page.
2. Click **Delete** on a row. The row disappears and the history count drops by
   one. The row that left in step 1 does not come back, because the stream only
   sends changes.

@:example(activity-stream)

## How It Works {#how-it-works}

- `LiveStreamDef.byId(...)(_.id).keepLast(5)` names the stream, identifies rows
  by activity ID, and limits the browser to five rows.
- `ctx.streams.insert` and `ctx.streams.delete` each send one change. The
  model's `activities` vector stays the source of truth for the count.
- `renderIn(ol, ...)` renders the stream's container and gives each row its DOM
  ID.

## Related Guides {#related-guides}

- [Streams and collection updates](../guides/streams-and-collection-updates.md#separate-domain-state-from-stream-state)
  explains when to use a stream instead of a keyed list.
