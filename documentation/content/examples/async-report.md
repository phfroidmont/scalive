{%
title = "Managed async report"
description = "A typed task key drives deterministic success, failure, replacement, retry, and cancellation states."
order = 11
section = examples
%}

A LiveView that runs a slow report in the background and renders every state
of that work: loading, succeeded, failed, and cancelled. The delays are fixed
so each state stays visible; in an application the same code wraps a database
query or service call.

## Try it {#try-it}

1. Click **Run successful report**. The panel shows "Loading" for about two
   seconds, then "Succeeded" with the "Quarterly activity" report.
2. Click **Run failing report**. After a second it shows "Failed", and still
   displays "Quarterly activity", the last successful report.
3. Click **Run successful report**, then **Replace current work** before it
   finishes. Only "Replacement report" arrives; the first task's result never
   appears.
4. Click **Run successful report**, then **Cancel report**. The panel shows
   "Cancelled" and "Cancelled by the user".

@:example(async-report)

## How it works {#how-it-works}

- Every button starts its task with `ctx.async.start(ReportTask)(task)(...)`.
  Starting the same key again interrupts the running task, so its result is
  never delivered.
- The model keeps an `AsyncValue[ReportError, Report]`. `loading()` marks work as
  running and `updated(result)` applies each completion; both keep the last
  successful report.
- `ctx.async.cancel` interrupts the task and delivers a `Cancelled` result with
  the given reason. **Cancel report** is disabled while nothing runs.
- The failing report fails with a typed `ReportError`, and the view shows one
  explanation per error. A defect would arrive as `Died` and show generic copy.

## Related guides {#related-guides}

- [Asynchronous work](../guides/async-work.md#model-finite-work-with-asyncvalue)
  explains task keys, `AsyncValue`, and cancellation.
