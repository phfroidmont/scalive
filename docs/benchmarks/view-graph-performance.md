# View Graph Performance Record

These development measurements were recorded on 2026-08-18 with OpenJDK 21.0.12 on an AMD Ryzen 9
9955HX3D. The one-off measurement harness was removed after the view graph architecture was
validated; this document preserves the observations rather than defining a maintained benchmark.

## Workload

The representative view contains 100 keyed rows, a dynamic title, and a dynamic boolean attribute.
A 200-update cycle increments each row once and then decrements each row once, so every transition
changes one row while the title and boolean also vary.

The reference path reconstructs and compiles the complete `HtmlElement` tree before diffing. The
view graph path constructs the signal-backed graph once and reevaluates its dynamic slots before
diffing. Both paths use the same `RenderSnapshot` and `TreeDiff` protocol implementation.

The measurement first verified identical rendered HTML for every update. It used four warmup rounds
and seven alternating measurement rounds with 5,000 updates per round. JVM thread-allocation
counters supplied allocation measurements, and payload measurements serialized the same repeated
update sequence.

Rendering-state measurements used JOL's reachable object-graph size. The common model and warmed
process-wide static artifacts were subtracted from both roots. The view graph state includes its
compiled snapshot, graph, and signal evaluation; the reconstruction state includes its compiled
snapshot. This estimates exclusive rendering state rather than an entire socket or process heap.

## Results

| Metric | Full reconstruction | View graph | Graph / reconstruction |
| --- | ---: | ---: | ---: |
| Update time | 151,276 ns | 69,331 ns | 0.458x |
| Allocated bytes per update | 1,186,861 B | 308,411 B | 0.260x |
| Initial payload | 9,823 B | 6,101 B | 0.621x |
| Mean update payload | 139.40 B | 91.40 B | 0.656x |
| Exclusive rendering state | 63,312 B | 146,040 B | 2.307x |

For this workload, view graph evaluation reduced median update time by about 54%, allocation by
about 74%, initial payload by about 38%, and update payload by about 34%. The tradeoff was about 2.3
times as much exclusive rendering state.

These figures explain an architectural decision; they are not a production capacity forecast or a
performance baseline. Future performance work should use dedicated benchmark infrastructure on
controlled hardware and compare representative application workloads across commits or releases.
