{%
title = "Connection-local shopping cart"
description = "Typed messages update an immutable cart with derived totals and SKU-keyed rows."
order = 2
section = examples
%}

A cart whose model is an immutable list of lines. Adding and removing products
are typed messages, and the item count, subtotals, and total are derived from
the lines rather than stored. The cart lives only as long as this connection.

## Try it {#try-it}

1. Click **Coffee beans** twice and **Notebook** once. The cart shows 3 items, a
   Coffee beans line with quantity 2, and a total of $34.48.
2. Click **Remove one** on the Coffee beans line. Its quantity drops to 1 and
   the total to $21.49.
3. Click **Clear**. The cart shows "Add a product to begin."

@:example(shopping-cart)

## How it works {#how-it-works}

- `Msg.Add(product)` carries the product as a typed value. The browser only
  reports which button was clicked.
- `model.map(_.lines).splitBy(_.product.sku)` keys each table row by SKU, so
  adding a product updates its row instead of rebuilding the table.
- Each row's **Remove one** button sends
  `line.map(line => Msg.Remove(line.product))`, the message for that row's
  current product.
- `itemCount` and `totalInCents` are methods of the model, so they cannot
  disagree with the lines.

## Related guides {#related-guides}

- [Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#send-row-specific-messages)
  explains keyed rows and row-specific messages.
- [Models, messages, and effects](../learn/models-and-messages.md#produce-explicit-state-transitions)
  builds the same cart model step by step.
