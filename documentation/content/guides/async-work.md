{%
title = "Asynchronous work"
description = "Run a finite task such as a slow query in the background, show its loading and failure states, and cancel or replace it."
order = 40
section = guides
group = "Async and lifecycle"
%}

## Before you start {#prerequisites}

Start with a connected `LiveView` whose messages already produce explicit model
transitions and rendered states. [Loading data and live updates](../learn/loading-data-and-live-updates.md)
introduces this page's API with a complete example.

## Choose the resource by shape {#choose-the-resource-by-shape}

Scalive gives each connected LiveView and component three kinds of
lifecycle-owned work:

- [Asynchronous work](#run-finite-work-with-a-typed-key), on this page, runs one
  finite `Task[A]`: a database query, service call, or report that succeeds,
  fails, or is cancelled.
- [Subscriptions](subscriptions.md#prerequisites) run a
  `ZStream[Any, Nothing, Msg]` that can emit many messages over time: a clock,
  notification feed, or application event stream.
- [Connected resources](connected-resources.md#prerequisites) hold something
  that needs cleanup when the page closes but never sends the LiveView a
  message.

Scalive releases each of them when the connected lifecycle closes, and prevents
replaced work from delivering stale results. Prefer them to `fork` inside a
message handler: a manually forked fiber has no owner, result delivery, or
cleanup tied to the LiveView. Keep durable or shared state in an injected
service instead.

@:callout(info)

Phoenix LiveView uses `start_async` and process-owned subscriptions for related
jobs, but it has no general acquire/release registry. Cleanup normally follows
process ownership or a best-effort `terminate/2` callback. Scalive exposes ZIO
`Task` and `ZStream` directly for message-producing work and adds a deterministic
connected-lifecycle scope for other resources.

@:@

## Start work when the page connects {#start-work-when-the-page-connects}

Most pages load slow data in `mount`, once the browser has connected. Match
@:apiSymbol(def:scalive.LifecycleContext.connection)`ctx.connection`@:@ and start
the work in the connected branch:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-mount)

@:apiSymbol(def:scalive.RootMountConnected.async)`connected.async`@:@ exists
only in the `Connection.Connected` branch, so the disconnected HTTP render cannot
start background work. Return a model that renders a loading state from both
branches. Every connected mount, including the one after a reconnect, starts the
work again. The second statement starts a
[subscription](subscriptions.md#start-a-subscription-when-the-page-connects).

After mount, message handlers reach the same capability through
@:apiSymbol(def:scalive.MessageContext.async)`ctx.async`@:@.

## Run finite work with a typed key {#run-finite-work-with-a-typed-key}

An @:apiSymbol(opaque-type:scalive.AsyncKey)`AsyncKey[A]`@:@ names one task and fixes
its result type, as in `AsyncKey[Double]("reading")`. Derive keys from stable
instance identity when multiple copies of an example or component can coexist;
the report example below uses `AsyncKey[Report](s"async-report-$instanceId")`.

Start work through @:apiSymbol(def:scalive.Async.start)`ctx.async.start`@:@ and map
its result into the owning LiveView's message type. The report example starts
every run through one helper:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-start)

The mapper receives a @:apiSymbol(enum:scalive.LiveAsyncResult)`LiveAsyncResult[A]`@:@:
`Succeeded(value)`, `Failed(cause)`, or `Cancelled(reason)`. This is one task's
completion, not persistent UI state. Handle it as an ordinary typed message so
the model remains the single input to rendering.

Starting the same key again interrupts and replaces its previous task. The
obsolete task does not emit `Cancelled`, and its stale completion is not
delivered. This makes replacement suitable for refreshes and changing queries:
start the newest request under the same key instead of assigning request IDs
and filtering old results yourself.

## Model finite work with AsyncValue {#model-finite-work-with-asyncvalue}

@:apiSymbol(enum:scalive.AsyncValue)`AsyncValue[A]`@:@ is an optional application
model for rendering work across messages. It distinguishes empty, loading,
successful, failed, and explicitly cancelled states. Loading, failure, and
cancellation can retain the last successful value, which lets a refresh show
existing data instead of replacing the entire panel with a spinner. Keep it in
the model:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-model)

Then apply each completion with `updated`:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-completed)

Call `loading(reset = true)` when old data would be misleading. The default
retains it. Render every state deliberately; a failed task is domain-visible
state, not a reason for the LiveView process to crash. Match on the cases, or
read one aspect with `valueOption`, `isLoading`, `isOk`, `isFailed`, or
`failure`. `map` transforms the current and retained values and keeps the
state.

Do not expose arbitrary exception messages to visitors. Convert expected
failures into stable, actionable copy and log diagnostic context separately.
The report example displays a fixed failure explanation while the documentation
site's diagnostic state viewer records only the state label.

## Cancel explicitly when it is user-visible {#cancel-explicitly-when-it-is-user-visible}

@:apiSymbol(def:scalive.Async.cancel)`ctx.async.cancel`@:@ interrupts active work.
If the key exists, Scalive invokes the original mapper with
`LiveAsyncResult.Cancelled`, including the optional application reason. Use this
path when cancellation itself belongs in the UI:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-cancel)

Cancelling an absent key is a no-op. Replacement, component removal, and lifecycle
shutdown also interrupt work, but intentionally do not deliver cancellation
messages: their owner is obsolete or disappearing.

Reset needs an explicit policy. The example cancels active work and immediately
returns to `AsyncValue.Empty`; it then ignores the cancellation completion only
when the model is already empty. A reset that should display “cancelled” can
instead reuse the normal cancel path.

## Keep ownership local {#keep-ownership-local}

Keys are runtime identities, not persistence keys. Keep them stable within one
owner and unique among that owner's tasks. Exact component instances and nested
LiveViews have independent namespaces. Deriving root keys from instance identity
still makes ownership visible in traces and prevents collisions when code moves
into a shared owner.

Components start work the same way, through
@:apiSymbol(def:scalive.ComponentConnected.async)`connected.async`@:@ in their
connected mount or update and
@:apiSymbol(def:scalive.ComponentMessageContext.async)`ctx.async`@:@ in their
message handler. Completions pass through the component's async hooks before
its `handleMessage`. When a component becomes dormant or is removed, Scalive
interrupts its active task; a dormant component that returns does not restart
it.

Keep durable results in an application service or database when they must
survive navigation or reconnect. `AsyncValue` and task registrations are
lifecycle state. On page exit, Scalive interrupts the task; on remount,
initialize the model from its real source of truth.

## Study the complete example {#study-the-complete-examples}

The report implementation shows typed results, retained values, deterministic
failure, stale-completion suppression, explicit cancellation, retry, and reset:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-example)

Run the [managed async report](../examples/async-report.md) alongside the
documentation site's diagnostic views to correlate messages, model transitions,
and final DOM changes.

## Related tasks {#related-tasks}

- Emit many messages over time with [Subscriptions](subscriptions.md#prerequisites).
- Release a registration or handle when the page closes with [Connected resources](connected-resources.md#prerequisites).
- Inject the service that performs the work with [Services and dependency injection](services-and-zlayer-injection.md#prerequisites).
- Save a form in the background with [Saving forms](saving-forms.md#prerequisites).
- Test connected behavior with [Testing LiveViews](testing.md#cover-connected-behavior).
