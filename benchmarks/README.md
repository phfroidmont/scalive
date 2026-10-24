# Scalive JMH benchmarks

Maintained Scala 3/JMH fixtures cover the Scalive render pipeline:

- `KeyedRowsBenchmark`: 100, 1,000, and 10,000 retained keyed rows; one-row update,
  reorder, removal, and reintroduction. It measures semantic diff alone, Phoenix encoding alone, and
  render + diff + encoding end to end.
- `BindingsAndSignalsBenchmark`: five event bindings per row and complete binding-table lookup; a
  changed model whose two signal projections and rendered values remain equal (validated as an empty
  semantic diff).
- `UnrelatedChangeBenchmark`: a counter changes next to a keyed list of 0, 1,000, or 10,000 rows
  that does not. It measures evaluation, semantic diff, and Phoenix encoding, none of which should
  grow with the row count.
- `InitialRenderBenchmark`: the first render of a list of 1,000 or 10,000 rows, as a mount makes
  it. It measures evaluation, the initial Phoenix payload, and the disconnected HTML.
- `StreamTurnBenchmark`: a counter changes next to a stream of 1,000 or 10,000 rows, or one row is
  inserted into it. It measures evaluation, semantic diff, and Phoenix encoding, and creating the
  stream.
- `ComponentTurnBenchmark`: a click on the root of a page holding 100 or 1,000 card components,
  each holding two child components, none of which the click changes. It runs the turn through a
  root connection and the session kernel, then encodes it for Phoenix.
- `StreamAndLifecycleBenchmark`: a 10,000-row stream snapshot with one inserted-row patch, 32
  resolved components retaining eight child templates each, and a duplicate key at the final
  candidate row to measure late failure and rollback.

`RetainedHeap` is not a JMH benchmark: it measures the heap a connection keeps per row of a keyed
list and of a stream after a turn, split into the model, the render and the Phoenix encoder state.
It walks the object graph with JOL, so its figures are exact and repeat on the same JVM.

Fixture invariants are checked once in trial setup, not in timed methods. `BenchmarkCounters`
accumulates output/diff size, expression sample count, render/diff nanoseconds, retained-template
count, coarse retained-heap lower bound, and lifecycle-resource estimate as JMH event-counter
secondary results. Divide those
iteration totals by the measured invocation count when a per-operation value is needed. They are
diagnostics, not stable performance scores; use JMH's primary latency score and
`gc.alloc.rate.norm` for comparisons.

## Runbook

```sh
mill benchmarks.compile
mill benchmarks.listJmhBenchmarks
mill benchmarks.runJmh -prof gc
```

Fast CI/local smoke run (no fork, no warmup):

```sh
mill benchmarks.runJmh -wi 0 -i 1 -r 100ms -f 0 -foe true '.*'
```

To isolate a scenario or avoid the 10,000-row parameter during iteration, use a JMH regex and `-p`,
for example:

```sh
mill benchmarks.runJmh -prof gc -p rowCount=1000 -p operation=update \
  'scalive.benchmarks.KeyedRowsBenchmark.renderDiffAndEncode'
```

Heap retained per row:

```sh
mill benchmarks.runMain scalive.benchmarks.RetainedHeap
```

### Regression check

`mill benchmarks.checkRegressions` runs every benchmark at 1,000 rows and 100 cards with the GC
profiler, measures the retained heap, and compares both with
[`regression-baseline.txt`](regression-baseline.txt). It fails when an allocation
(`gc.alloc.rate.norm`, B/op) exceeds its baseline by more than 10% + 256 B, a retained size by more
than 2%, or when a benchmark is added or removed without updating the baseline. Allocation barely
depends on the machine or its load, unlike time, so CI runs this check; it takes about two
minutes. Run-to-run spread is within 5%.

After an intended change, including an improvement worth locking in, rewrite the baseline and
commit it with the change:

```sh
mill benchmarks.updateRegressionBaseline
```

### Measurement caveats

- `-f 0` is only for smoke tests; publish measurements from forked runs on an idle, fixed JVM/host.
- Phoenix output size is the UTF-16 Scala string length of compact JSON, a close but not exact UTF-8
  wire-byte count for non-ASCII data.
- Signal samples count executed mapped expressions, not source-cache entries. `retainedHeapBytes`
  applies a deliberately coarse 64-byte-per-retained-template lower bound, while lifecycle counters
  are fixture cardinality estimates; use a heap profiler for object-retention attribution.
- The component fixture resolves real semantic component nodes but deliberately excludes runtime
  mailbox supervision. Runtime lifecycle benchmarks should be added separately if supervision
  overhead needs a stable threshold.
- End-to-end timing includes ZIO execution and candidate discard, while the auxiliary render/diff
  durations use `System.nanoTime`; rely on the JMH score for total latency.
