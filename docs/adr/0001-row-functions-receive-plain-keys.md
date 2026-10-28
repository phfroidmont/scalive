# Row functions receive plain keys

A collection's row function receives its entry's key as a plain value, so each row runs the row
function and compiles its own template. Sharing one compiled template across rows would save about
a third of the render-side memory per row (940 of 2,870 B for a keyed `tr` row, 550 of 1,874 B for
a stream row, measured 2026-10-28), but it needs the row function to run once for all rows, with
the key as a `Signal`. We kept plain keys: rows use them in messages (`on.click(Msg.Move(key))`),
static attributes, component ids, keyed form bindings and plain `if`, and every one of those would
become signal-driven ceremony or need new API.

## Considered Options

- Detecting rows that compiled to identical templates and sharing those: rejected, as each row's
  template holds its own signals and key-derived values, so rows rarely match and mount gets slower.
