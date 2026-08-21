# Scalive JMH benchmarks

Maintained Scala 3/JMH fixtures cover the Milestone 11 render pipeline:

- `KeyedRowsBenchmark`: 100, 1,000, and 10,000 retained keyed rows; one-row update,
  reorder, removal, and reintroduction. It measures semantic diff alone, Phoenix encoding alone, and
  render + diff + encoding end to end.
- `BindingsAndSignalsBenchmark`: five event bindings per row and complete binding-table lookup; a
  changed model whose two signal projections and rendered values remain equal (validated as an empty
  semantic diff).
- `StreamAndLifecycleBenchmark`: a 10,000-row stream snapshot with one inserted-row patch, 32
  resolved components retaining eight child templates each, and a duplicate key at the final
  candidate row to measure late failure and rollback.

Fixture invariants are checked once in trial setup, not in timed methods. `BenchmarkCounters`
accumulates output/diff size, expression sample count, render/diff nanoseconds, retained-template
count, coarse retained-heap lower bound, and lifecycle-resource estimate as JMH event-counter
secondary results. Divide those
iteration totals by the measured invocation count when a per-operation value is needed. They are
diagnostics, not stable performance scores; use JMH's primary latency score and
`gc.alloc.rate.norm` for comparisons.

## Runbook

```sh
mill --ticker false benchmarks.compile
mill --ticker false benchmarks.listJmhBenchmarks
mill --ticker false benchmarks.runJmh -prof gc
```

Fast CI/local smoke run (no fork, no warmup):

```sh
mill --ticker false benchmarks.runJmh -wi 0 -i 1 -r 100ms -f 0 -foe true '.*'
```

To isolate a scenario or avoid the 10,000-row parameter during iteration, use a JMH regex and `-p`,
for example:

```sh
mill --ticker false benchmarks.runJmh -prof gc -p rowCount=1000 -p operation=update \
  'scalive.benchmarks.KeyedRowsBenchmark.renderDiffAndEncode'
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
  overhead becomes a Milestone threshold.
- End-to-end timing includes ZIO execution and candidate discard, while the auxiliary render/diff
  durations use `System.nanoTime`; rely on the JMH score for total latency.
