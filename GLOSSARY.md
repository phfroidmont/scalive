# Scalive

A Scala 3 re-implementation of Phoenix LiveView: a server renders a page from signals, and sends
the browser only what changed.

## Collections

**Collection**:
Markup repeated once per entry of a signal of items, through `splitBy`, `splitByIndex` or a stream.
_Avoid_: comprehension, list (when meaning the rendering construct)

**Entry**:
One item of a collection's items, with the key or DOM id that identifies it.
_Avoid_: element, record

**Row**:
The markup a collection renders for one entry, built by the collection's row function.
_Avoid_: child, item

**Row function**:
The function a collection calls to build a row, given the entry's key as a plain value and a signal
of the entry.
_Avoid_: row closure, row builder, render function

**Outside signal**:
A signal a row reads that is not derived from its own entry alone, such as a selection the row
compares its key with.
_Avoid_: external signal, shared signal

**Kept row**:
A row that a render leaves as it was, because its entry and every outside signal it reads are
unchanged.
_Avoid_: cached row, skipped row, reused row
