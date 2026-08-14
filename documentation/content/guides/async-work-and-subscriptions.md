{%
title = "Asynchronous work and subscriptions"
description = "Own finite tasks and long-lived message streams with the LiveView lifecycle, typed keys, explicit UI states, and predictable cancellation."
order = 31
section = guides
group = "Advanced features"
%}

## Choose The Resource By Shape {#choose-the-resource-by-shape}

Scalive gives a connected LiveView two lifecycle-owned ways to receive work
later. Use `ctx.async` for one finite `Task[A]`: a database query, service call,
or report that succeeds, fails, or is cancelled. Use `ctx.subscriptions` for a
`ZStream[Any, Nothing, Msg]` that can emit many messages over time: a clock,
notification feed, or application event stream.

Both APIs attach resources to the socket lifecycle. Scalive interrupts them
when the socket closes, and starting replacement work cannot leak a stale
completion into the current model. Prefer these APIs to `fork` inside a message
handler: a manually forked fiber has no automatic owner, result delivery, or
cleanup contract with the LiveView runtime.

@:callout(info)

Phoenix LiveView uses `start_async` and subscription callbacks for related
jobs. Scalive exposes ZIO `Task` and `ZStream` directly and delivers their
outcomes as typed messages.

@:@

## Run Finite Work With A Typed Key {#run-finite-work-with-a-typed-key}

An @:apiSymbol(opaque-type:scalive.AsyncKey)`AsyncKey[A]`@:@ names one task and fixes
its result type. Derive keys from stable instance identity when multiple copies
of an example or component can coexist:

```scala
private val ReportTask = AsyncKey[Report](s"async-report-$instanceId")
```

Start work through @:apiSymbol(def:scalive.Async.start)`ctx.async.start`@:@ and map
its result into the owning LiveView's message type:

```scala
ctx.async
  .start(ReportTask)(generateReport)(Msg.ReportCompleted(_))
  .as(model.copy(report = model.report.loading()))
```

The mapper receives a @:apiSymbol(enum:scalive.LiveAsyncResult)`LiveAsyncResult[A]`@:@:
`Succeeded(value)`, `Failed(cause)`, or `Cancelled(reason)`. This is one task's
completion, not persistent UI state. Handle it as an ordinary typed message so
the model remains the single input to rendering.

Starting the same key again interrupts and replaces its previous task. The
obsolete task does not emit `Cancelled`, and its stale completion is not
delivered. This makes replacement suitable for refreshes and changing queries:
start the newest request under the same key instead of assigning request IDs
and filtering old results yourself.

## Model Finite Work With AsyncValue {#model-finite-work-with-asyncvalue}

@:apiSymbol(enum:scalive.AsyncValue)`AsyncValue[A]`@:@ is an optional application
model for rendering work across messages. It distinguishes empty, loading,
successful, failed, and explicitly cancelled states. Loading, failure, and
cancellation can retain the last successful value, which lets a refresh show
existing data instead of replacing the entire panel with a spinner.

```scala
final case class Model(report: AsyncValue[Report] = AsyncValue.empty)

case Msg.ReportCompleted(result) =>
  ZIO.succeed(model.copy(report = model.report.updated(result)))
```

Call `loading(reset = true)` when old data would be misleading. The default
retains it. Render every state deliberately; a failed task is domain-visible
state, not a reason for the LiveView process to crash.

Do not expose arbitrary exception messages to visitors. Convert expected
failures into stable, actionable copy and log diagnostic context separately.
The report example displays a fixed failure explanation while its X-ray
projector records only the state label.

## Cancel Explicitly When It Is User-Visible {#cancel-explicitly-when-it-is-user-visible}

@:apiSymbol(def:scalive.Async.cancel)`ctx.async.cancel`@:@ interrupts active work.
If the key exists, Scalive invokes the original mapper with
`LiveAsyncResult.Cancelled`, including the optional application reason. Use this
path when cancellation itself belongs in the UI:

```scala
ctx.async.cancel(ReportTask, Some("Cancelled by the user")).as(model)
```

Cancelling an absent key is a no-op. Replacement, component removal, and socket
shutdown also interrupt work, but intentionally do not deliver cancellation
messages: their owner is obsolete or disappearing.

Reset needs an explicit policy. The example cancels active work and immediately
returns to `AsyncValue.Empty`; it then ignores the cancellation completion only
when the model is already empty. A reset that should display “cancelled” can
instead reuse the normal cancel path.

## Own Long-Lived Streams With Subscriptions {#own-long-lived-streams-with-subscriptions}

A @:apiSymbol(opaque-type:scalive.SubscriptionKey)`SubscriptionKey`@:@ identifies one
registered stream in a root LiveView. The stream must emit the LiveView's `Msg`
type and cannot fail:

```scala
private val ClockSubscription =
  SubscriptionKey(s"subscription-clock-$instanceId")

private def ticks(every: Duration): ZStream[Any, Nothing, Msg] =
  ZStream.tick(every).mapZIO(_ => Clock.instant).map(Msg.Tick(_))
```

@:apiSymbol(def:scalive.Subscriptions.start)`start`@:@ rejects a duplicate active
key. Use it when starting twice indicates a state-machine mistake. The clock
guards `start` with its model so the button and server transition agree.

@:apiSymbol(def:scalive.Subscriptions.replace)`replace`@:@ starts or swaps the
stream under a key. Replacing the registration interrupts the old stream and
resubscribes the runtime's current set. Use replacement when changing polling
frequency, topic, or another stream parameter is valid application behavior.

@:apiSymbol(def:scalive.Subscriptions.cancel)`cancel`@:@ removes the registration
and succeeds when it is already absent. Update the model in the same handler so
the rendered controls describe the registered state:

```scala
case Msg.Cancel =>
  ctx.subscriptions
    .cancel(ClockSubscription)
    .as(model.copy(mode = Mode.Stopped))
```

Subscription messages pass through info lifecycle hooks before
`handleMessage`. Registrations exist only for the connected socket and do not
run during disconnected rendering. Mount therefore starts required streams
again for each new connected lifecycle.

## Keep Ownership Local {#keep-ownership-local}

Keys are runtime identities, not persistence keys. Keep them stable within one
owner and unique among that owner's resources. Nested LiveViews already have
independent resource namespaces, but deriving keys from instance identity also
makes ownership visible in traces and prevents collisions when code moves into
a shared owner.

Keep durable results in an application service or database when they must
survive navigation or reconnect. `AsyncValue`, task registrations, and
subscription registrations are socket state. On page exit, Scalive releases
the managed resource; on remount, initialize the model and registrations from
their real source of truth.

## Study The Complete Examples {#study-the-complete-examples}

The clock implementation shows start, replacement, cancellation, reset, and an
instance-scoped subscription key:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-example)

The report implementation shows typed results, retained values, deterministic
failure, stale-completion suppression, explicit cancellation, retry, and reset:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-example)

Run the [managed clock subscription](../examples/subscription-clock.md) and
[managed async report](../examples/async-report.md) alongside their X-ray views
to correlate messages, model transitions, and final DOM changes.
