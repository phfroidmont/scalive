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
| Keyed list | 77 B | 2,812 B | 1,221 B | 4,109 B |
| Stream | 244 B | 1,816 B | 725 B | 2,785 B |
| Keyed list, a component per row | 77 B | 2,165 B | 1,542 B | 3,783 B |

Upstream keeps only the assigns, and nothing of a stream row once sent. For 100 connections
showing 1,000 rows, Scalive keeps about 400 MB.

What a keyed row holds on the render side, from a JOL class histogram:

- 17 small vectors, about 760 B: each element's children and attributes, and each row's records.
- 5 `EvaluatedNode.Element`, 240 B.
- 5 `ElementTemplate` (200 B) and 3 `TextTemplate` (72 B): each row compiles its own templates.
- 5 boxed `Long` row keys, 120 B: one per map keyed by `RowId`.
- 6 `Some`, 96 B, and signal samples, about 250 B.

The structural change that removes most of it is rows sharing one compiled template, instead of
each row compiling its own when it is first rendered. A row would then hold only its values and its
samples. It is a large refactor of `RenderProgram`, as row templates carry the row's signal scope.
Before starting, measure what share of the 2,812 B it removes, with a JOL histogram of a row
(`GraphLayout.parseInstance(...).toFootprint`) as `RetainedHeap` takes it.

On the encoder side, the remaining 1,221 B are mostly parts vectors (about 390 B), 8
`ProjectedNode` (256 B) and 5 branch summaries (280 B). Two smaller savings were measured and not
done:

- Parts as `IArray` instead of `Vector`: about 130 B, but it loses structural equality and the
  vector operations the encoder uses throughout.
- Summaries only on nodes holding something other than leaves: about 220 B, but a search then
  costs O(children) at each node with many leaf children.

A further structural option is the encoder deriving what it needs from the evaluated tree
instead of holding a second copy of it.

## Evaluation allocation on mount {#evaluation-allocation-on-mount}

A first render allocates per row, in `InitialRenderBenchmark`:

| Step | Allocated per row |
|---|---|
| Evaluation | 15.3 KB |
| Initial Phoenix payload | 10.3 KB |
| Disconnected HTML | 7.5 KB |

This is garbage, not retained memory: it matters for mount latency and GC pressure when many
browsers join at once. In evaluation, most of it is closures from `for` over `Either` in
`evaluateNode` and the per-row template compilation above, which sharing row templates would also
remove. A JFR allocation profile (JMH `-prof jfr`, then `jfr print --events
jdk.ObjectAllocationSample`) attributes it by frame. Its sample weights are skewed, so confirm a
saving with `gc.alloc.rate.norm` before and after.

## Stream insert evaluation {#stream-insert-evaluation}

Inserting one row into a stream costs O(rows) to evaluate: 2.6 MB and under 1 ms at 1,000 rows,
26 MB and about 10 ms at 10,000 (`StreamTurnBenchmark.evaluate`, `operation=insert`). The diff and
the encoder cost little for the same insert (4 KB and 49 KB at 1,000 rows), since they find each
row's previous instance with `StreamRowCursor` and shape the payload from the rows it sends.

Each kept row is cheap, about 1 µs, but evaluation rebuilds per turn every structure indexed by row:
the row templates map, the row records, the rows' signal samples (`RowsEvaluation`), and the
collection's bindings, which `BindingTable.Builder.result` adds back to the table one by one. The
same holds for a keyed list whose items changed (`KeyedRowsBenchmark`), where it matches upstream,
which also walks every entry of a changed comprehension.

Making it O(changed) means keeping those structures across turns and updating them by the rows a
turn inserts, deletes or evaluates: persistent maps updated in place of rebuilt ones, bindings
grouped per row so that the table drops and adds only the rows that changed, and the stream's
operations used to tell which rows changed. That touches `RenderProgram`, `SignalEvaluation` and
`BindingTable` together.
