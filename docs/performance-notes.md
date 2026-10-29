# Performance notes

Status: internal notes, not published on the documentation site

Known server-side costs that are not worth addressing for current use cases, with what is known
about them, so that the work can start from measurements rather than from scratch. Figures are
from the benchmarks in `benchmarks/` (see its README) on 2026-10-25, at 1,000 rows unless stated.

## Per-connection memory {#per-connection-memory}

A connection keeps every row of every keyed list and stream it renders, in two forms: the render's
(the evaluated tree, row records, signal samples, bindings) and the Phoenix encoder's (the projected
tree the browser holds). `RetainedHeap` measures it per row:

| Page | Model | Render | Encoder | Total |
|---|---|---|---|---|
| Keyed list | 77 B | 2,576 B | 1,221 B | 3,873 B |
| Stream | 244 B | 1,692 B | 725 B | 2,661 B |
| Keyed list, a component per row | 77 B | 1,970 B | 1,448 B | 3,495 B |

Upstream keeps only the assigns, and nothing of a stream row once sent. For 100 connections
showing 1,000 rows, Scalive keeps about 390 MB.

What a keyed row holds on the render side, from a JOL class histogram (2026-10-28):

- 15 small vectors, about 680 B: each element's children and attributes, and each row's records.
- 5 `EvaluatedNode.Element`, 240 B.
- 5 `ElementTemplate` (200 B) and 3 `TextTemplate` (72 B): each row compiles its own templates.
- 3 boxed `Long` row keys, 72 B: one per map keyed by `RowId`.
- 3 signal samples in one array, about 160 B, and 3 `Some` attribute values, 48 B.
- The row's entry in the binding table's groups (#3), about 44 B, of which 16 B is one more boxed
  `RowId`. Rows without bindings have none.

Slots and signal samples were made compact on 2026-10-28 (#5), which saved 280 B per keyed row
(2,856 B before), 168 B per stream row and 203 B per component row. Evaluated texts and
attributes hold their slot as a plain `Long` (`OptionalSlotId`) instead of a boxed `Some`; a row's
samples sit in one array, signal then sample, rather than a map of a key and a wrapper per sample;
and a sample holds its dependency revisions as two `Long` fields rather than a vector.

Net of spec #1, a keyed row's render side went from 2,812 B to 2,576 B, 236 B less: #5 saved
280 B, and #3's binding groups added 44 B. The target was about 300 B. What is left of the
wrappers are the boxed `RowId` keys (72 B) and the `Some` attribute values (48 B) above.

Rows sharing one compiled template would remove about a third of it, and needs row functions to
receive their key as a signal; it is not done, see
[ADR 0001](adr/0001-row-functions-receive-plain-keys.md).

On the wire, rows share templates even so (#8). Text a row builds from its key, such as
`idAttr := s"row-$key"`, lands in its statics, so rows used to share a template in the payload's
`p` only when they rendered the same static text, and each such row sent its own. The encoder now
groups the templates of a payload that have as many statics, keeps the text they all start and end
with at each static they differ in, and sends what is left among each row's dynamics, as stream
rows do. It does so only for a group that encodes shorter that way, and records which statics each
row it sent received so, to number later diffs of that row as the browser does. The join payload
of `InitialRenderBenchmark` at 1,000 rows went from 170,836 B to 80,153 B, for 0.7 KB more
allocated per row while encoding it (10.3 to 11.0 KB before #9, 2026-10-29). Render memory is
unchanged, as each row still compiles its own template, and so is the encoder's retained memory: a
node holds how its statics were sent in the field that said whether they are its own, through
instances its rows share.

Components share statics on the wire too (#12). A component sent in full sends `"s": -cid` when
a component the browser holds, and that is not pending deletion, has the same static tree, or
`"s": cid` when one sent earlier in the payload has it, as upstream's `maybe_reuse_static` does; the
nested rendered objects the browser then resolves from that component leave out their statics.
The static tree is what the browser rebuilds, nested statics included, whatever component renders
it, and the encoder state indexes the components by it, so a lookup does not depend on how many
the browser holds (upstream gives up after 32). A component's root also no longer carries
`data-phx-component` in its statics, which made every instance's differ: the browser adds it
itself, and only the server-rendered HTML holds it. The join payload of `ComponentTurnBenchmark`'s
page at 100 cards went from 25,435 B to 11,385 B (`static` layout; 17,443 B from dropping the
attribute alone) and from 26,999 B to 12,949 B with `splitBy`; at 1,000 cards, 266,342 B to
122,491 B and 281,403 B to 137,552 B (2026-10-29). The index costs a map entry and a set entry
per component, and the components of one tree share one instance of it: `componentList.encoder`
went from 1,542 B to 1,557 B, net of the attribute it no longer keeps.

On the encoder side, the remaining 1,221 B are mostly parts vectors (about 390 B), 8
`ProjectedNode` (256 B) and 5 branch summaries (280 B). Two smaller savings were measured and not
done:

- Parts as `IArray` instead of `Vector`: about 130 B, but it loses structural equality and the
  vector operations the encoder uses throughout.
- Summaries only on nodes holding something other than leaves: about 220 B, but a search then
  costs O(children) at each node with many leaf children.

The encoder cannot derive what it holds from the evaluated tree instead: it also holds what is
local to the connection and the protocol (component CIDs, stream refs, the CSRF input, the row
statics the browser holds, pending stream operations), and it updates its copy from each turn's
changes rather than projecting the tree again.

## Evaluation allocation on mount {#evaluation-allocation-on-mount}

A first render allocates per row, in `InitialRenderBenchmark`:

| Step | Allocated per row |
|---|---|
| Evaluation | 11.2 KB (15.1 KB before #5 and #6) |
| Initial Phoenix payload | 9.4 KB (10.3 KB before #8 and #9) |
| Disconnected HTML | 7.5 KB |

This is garbage, not retained memory: it matters for mount latency and GC pressure when many
browsers join at once. Evaluating an element and compiling a row's elements now loop and return at
the first error instead of chaining `for` over `Either` (#6), which saved a fifth of evaluation
(14.4 KB to 11.2 KB per row at 1,000 rows, and 14.0 KB to 11.7 KB at 10,000; 2026-10-28). #6 was
scoped to `evaluateNode`, but the JFR profile of the mount path flagged the compile frames too, as
a mount compiles each row's templates before evaluating them, so both were rewritten: the loops in
`evaluateNode` alone saved 9–10%, and with compilation 16–22%.

What is left, by the JFR profile below (taken before #5): compiling each row's templates is a little
over a quarter, of which `SignalScope.validate` building an identity map for each signal it checks
is more than a third; running the row function itself (the view's own `HtmlElement`s and
`Mod.flatten`) about a fifth; then text and attribute values, binding groups, row signals and
signal samples.

A JFR allocation profile (JMH `-prof 'jfr:configName=profile'`, then `jfr print --stack-depth 64
--events jdk.ObjectAllocationSample`; the default depth of 5 hides the callers) attributes it by
frame. Its sample weights are skewed by TLAB size, so count samples rather than sum weights, and
confirm a saving with `gc.alloc.rate.norm` before and after.

## Stream rows evaluated on every turn {#stream-rows-evaluated-on-every-turn}

A stream insert or delete evaluates only the rows its operations insert and those that read a
changed signal from outside them (#4): an append costs about 5 µs and 29 KB at 1,000 and at
10,000 rows, a delete in the middle 5 µs and 24 KB at 1,000 rows and 27 µs and 46 KB at 10,000
(`StreamTurnBenchmark.evaluate`, 2026-10-28; at 10,000 rows, 4 ms and 5.5 MB for the append and
4 ms and 6.6 MB for the delete before). Each collection and stream keeps which of its rows read
each signal from outside them, samples each such signal once per turn, and counts one that can no
longer be sampled as changed. Evaluating the rows behind it again samples it again only if they
still read it: one read in a branch since closed is skipped, and any other failure fails the turn,
as it did when each row checked its own signals. The stream's row order is replayed from its
operations; a delete still searches the rows for the one it deletes and copies the row vector,
which is the cost left at 10,000 rows (accepted, see below).

What still visits every row:

- An insertion into or a deletion from a stream whose rows hold components: the stream evaluates
  every row, as only a stream without components takes the path through its changed rows, and the
  kernel then indexes the committed component declarations of the stream's owner to find those the
  render carries over (see [Turns and components](#turns-and-components)). Inserting the first card
  of a stream of cards again, which updates its row in place, costs 296 KB and 0.20 ms at 100
  cards (300 components) and 1.87 MB and 0.92 ms at 1,000 (`ComponentTurnBenchmark.streamInsert`,
  2026-10-29, 8 iterations of 1 s): about 1.75 KB per card. A turn that leaves such a stream as it
  is no longer visits its rows' components (#10): a click on the root of the same page costs 122 KB
  at 100 cards and 138 KB at 1,000 (`rootClick`, `layout=stream`).
- A stream after a reset, or whose rows render something no signal tracks (a nested LiveView, a
  stream, or a flash).
- An insertion at an index, or with a limit, still costs O(rows) to place: it patches or trims the
  row vector, then checks the whole row order against the stream's entries. When operations the
  stream merged do not replay to its entries, such as an entry inserted, then another, then the
  first updated, the rows are found by DOM id, which costs a map of them.
- A delete, or an update of a row by its DOM id, still searches the rows for it, and a delete copies
  the row vector around it: about 5 µs at 1,000 rows and 27 µs at 10,000 for a delete in the
  middle (`StreamTurnBenchmark.evaluate`). Accepted on 2026-10-28, like insertions at an index;
  avoiding it would need the stream to keep its rows indexed by DOM id across turns.
- A `splitBy` or `splitByIndex` collection whose items changed (`KeyedRowsBenchmark`), as upstream
  walks every entry of a changed Phoenix comprehension: it compares every entry, though a kept row
  now costs less (a turn updating one row of 1,000 allocates 3.25 MB instead of 3.63 MB in
  `renderDiffAndEncode`, and of 10,000 rows 31.4 MB instead of 37.2 MB). A row kept in place
  and unchanged now costs almost nothing besides that comparison (#11): a keyed change lists only
  the rows inserted and the rows that changed or moved, with their old index, and the new row
  count; rows in the same order are compared by position without a map of the old rows; and the
  encoder gathers what the rows changed once per change and records only the rows the browser
  receives something of, so building the payload and clearing what it sent skip the others. Per
  kept row of a one-row update, measured as (10,000 − 1,000 rows) / 9,000 on
  `gc.alloc.rate.norm` (`KeyedRowsBenchmark`, `operation=update`, 2026-10-29):

  | Step | Before | After |
  |---|---|---|
  | `semanticDiff` | 274 B (308 KB, 2.78 MB) | 16 B (21 KB, 166 KB) |
  | `phoenixEncoding` | 2,283 B (2.38 MB, 22.9 MB) | 16 B (32 KB, 179 KB) |
  | `renderDiffAndEncode` | 3,119 B (3.25 MB, 31.3 MB) | 597 B (0.60 MB, 5.98 MB) |

  What is left of `renderDiffAndEncode` is evaluating the collection. A reorder, removal or
  reintroduction lists every row that moved, so it still costs each such row, though less than
  before (at 1,000 rows, 1.37 MB instead of 3.45 MB for a reorder in `renderDiffAndEncode`).
- A row that builds its own outside signal, such as `selected.map(_ == id)` in the row function,
  costs one indexed signal, sampled every turn the collection is evaluated: the bindings guide
  advises folding such state into the entries.

## Turns and components {#turns-and-components}

A turn reaches only the components it may change (#10): those a render declares anew, the target
of its action and of each component update, those whose render reads what signals do not track
(a stream, a flash, a nested LiveView), and the ancestors of these. A click on the root of a page
whose cards each hold two child components (`ComponentTurnBenchmark.rootClick`, 2026-10-29, 8
iterations of 1 s, other benchmarks running alongside):

| Cards (components) | Layout | Before | After |
|---|---|---|---|
| 100 (300) | static | 0.91 MB, 1.8 ms | 123 KB, 0.13 ms |
| 1,000 (3,000) | static | 9.40 MB, 9.0 ms | 160 KB, 0.25 ms |
| 100 (300) | `splitBy` | 0.91 MB, 2.2 ms | 121 KB, 0.15 ms |
| 1,000 (3,000) | `splitBy` | 9.38 MB, 8.7 ms | 136 KB, 0.21 ms |

Before, about 3 KB per component per turn went to the kernel resolving every component again,
wherever it sat: listing, sorting, checking, keeping and resolving each one, then building maps and
sets over all of them.

A render now tells the kernel what it carried over without a separate list. Its
`componentRequirements` are the committed render's vector itself when it declares the same
components in the same order; a declaration it carries is the same requirement object, and the
tree holds the resolution committed for it. The render carries a declaration whose id and props
cannot change, or whose signals did not change, with its committed node, and a kept row or
collection carries those it recorded. The kernel finds the carried declarations by identity, keeps
their components without touching them, and resolves into the render only those it staged.
Resolving and checking instance tokens cover what the turn supplies.

Each component now also keeps the requirement that last declared it, 8 B more per row of
`componentList` on the render side. In the encoder, a node whose CIDs all come from one child
shares that child's set rather than copying it: the root's summary no longer copies every CID on
each turn, and a row of `componentList` keeps 109 B less there (1,557 B to 1,448 B).

Every component renders again when the runtime-owned state it reads, the shown flash and the
uploads, differs from the one it last rendered with: the committed state records that root model,
or none when components rendered with different ones in a turn, as when one puts a flash. A flash
a component puts mid-turn therefore shows in the same turn in every component, including those
declared before it, which the turn renders again with the new flash. This is intended (decided
on 2026-10-29 in the review of #10).

What still grows with the number of components:

- A render that declares a component anew, or no longer declares one, among the declarations of
  one owner: the kernel then indexes the owner's committed declarations by identity, which costs
  O(declarations) for that owner. A change of one component's props among 1,000 siblings is such a
  turn.
- A turn that targets a component, by an action, a component update or an untracked read: each
  owner on the way, whose declarations are unchanged, searches them once for each component the
  turn targets, which costs O(declarations) of that owner per target. A click on one card of 1,000
  searches the root's declarations once for that card.
- A root, or a component, that renders again walks every child of each element it evaluates again,
  without allocating for a component it carries: what the remaining 40 B per card of the static
  layout are, with the element's new child vector.
- The Phoenix encoder gathers the streams of every component at the end of each update, which is
  most of the time left at 1,000 cards (about 0.1 ms).
- A turn in which the flash or the uploads change visits every component, as each may render
  differently.
