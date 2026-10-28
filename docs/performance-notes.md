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
| Keyed list | 77 B | 2,856 B | 1,221 B | 4,153 B |
| Stream | 244 B | 1,860 B | 725 B | 2,829 B |
| Keyed list, a component per row | 77 B | 2,165 B | 1,542 B | 3,783 B |

Upstream keeps only the assigns, and nothing of a stream row once sent. For 100 connections
showing 1,000 rows, Scalive keeps about 400 MB.

What a keyed row holds on the render side, from a JOL class histogram:

- 17 small vectors, about 760 B: each element's children and attributes, and each row's records.
- 5 `EvaluatedNode.Element`, 240 B.
- 5 `ElementTemplate` (200 B) and 3 `TextTemplate` (72 B): each row compiles its own templates.
- 5 boxed `Long` row keys, 120 B: one per map keyed by `RowId`.
- 6 `Some`, 96 B, and signal samples, about 250 B.
- The row's entry in the binding table's groups (#3), about 44 B, of which 16 B is one more boxed
  `RowId`. Rows without bindings have none.

Rows sharing one compiled template would remove about a third of it, and needs row functions to
receive their key as a signal; it is not done, see
[ADR 0001](adr/0001-row-functions-receive-plain-keys.md).

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
| Evaluation | 12.0 KB |
| Initial Phoenix payload | 10.3 KB |
| Disconnected HTML | 7.5 KB |

This is garbage, not retained memory: it matters for mount latency and GC pressure when many
browsers join at once. Evaluating an element and compiling a row's elements now loop and return at
the first error instead of chaining `for` over `Either` (#6), which saved a fifth of evaluation
(15.3 KB to 12.0 KB per row at 1,000 rows, and 14.8 KB to 12.1 KB at 10,000; 2026-10-28). About
40% of that came from evaluating elements, the rest from compiling them.

What is left, by the JFR profile below: compiling each row's templates is a little over a quarter,
of which `SignalScope.validate` building an identity map for each signal it checks is more than a
third; running the row function itself (the view's own `HtmlElement`s and `Mod.flatten`) about a
fifth; then text and attribute values with their boxed slots (#5), binding groups, row signals and
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
4 ms and 6.6 MB for the delete before). Each
collection and stream keeps which of its rows read each signal from outside them, samples each such
signal once per turn, and counts one that can no longer be sampled, such as one read in a branch
since closed, as changed. The stream's row order is replayed from its operations; a delete still
searches the rows for the one it deletes and copies the row vector, which is the cost left at
10,000 rows.

What still visits every row:

- A stream whose rows hold components. The kernel receives every collection's component
  requirements in evaluation order each turn, so each row declares its components again. Visiting
  only the changed rows would need the kernel to keep the requirements of each collection as a
  persistent sequence.
- A stream after a reset, or whose rows render something no signal tracks (a nested LiveView, a
  stream, or a flash).
- An insertion at an index, or with a limit, still costs O(rows) to place: it patches or trims the
  row vector, then checks the whole row order against the stream's entries. When operations the
  stream merged do not replay to its entries, such as an entry inserted, then another, then the
  first updated, the rows are found by DOM id, which costs a map of them.
- A keyed list whose items changed (`KeyedRowsBenchmark`), as upstream walks every entry of a
  changed comprehension: it compares every item, though a kept row now costs less (a turn
  updating one row of 1,000 allocates 3.25 MB instead of 3.63 MB in `renderDiffAndEncode`, and of
  10,000 rows 31.4 MB instead of 37.2 MB).
- A row that builds its own outside signal, such as `selected.map(_ == id)` in the row function,
  costs one indexed signal, sampled every turn the collection is evaluated: the bindings guide
  advises folding such state into the entries.
