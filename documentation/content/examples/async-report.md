{%
title = "Managed async report"
description = "A typed task key drives deterministic success, failure, replacement, retry, and cancellation states."
order = 10
section = examples
%}

Run a report and watch @:apiSymbol(enum:scalive.AsyncValue)`AsyncValue`@:@ move through
loading and completion states. A failure retains the last successful report,
replacement suppresses the obsolete task's completion, and explicit
cancellation produces a typed cancellation result. Reset cancels active work
and returns the visible state to empty.

The delays are deterministic teaching controls. In an application, the same
pattern wraps database queries, service calls, or other finite `Task` values.

@:example(async-report)

Related guidance: [model finite background work explicitly](../guides/async-work-and-subscriptions.md#model-finite-work-with-asyncvalue).
