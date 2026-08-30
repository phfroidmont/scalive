{%
title = "Asynchronous work, subscriptions, and connected resources"
description = "Own finite tasks, message streams, and other resources with the connected LiveView lifecycle."
order = 40
section = guides
group = "Async and lifecycle"
%}

## Before You Start {#prerequisites}

Start with a connected `LiveView` whose messages already produce explicit model
transitions and rendered UI states.

## Choose The Resource By Shape {#choose-the-resource-by-shape}

Scalive gives a connected LiveView three lifecycle-owned resource shapes. Use
`ctx.async` for one finite `Task[A]`: a database query, service call, or report
that succeeds, fails, or is cancelled. Use `ctx.subscriptions` for a
`ZStream[Any, Nothing, Msg]` that can emit many messages over time: a clock,
notification feed, or application event stream. Use
@:apiSymbol(def:scalive.RootMountConnected.resources)`connected.resources`@:@
during mount for a resource that needs deterministic cleanup but does not itself
deliver messages to the LiveView.

A `Task[A]` is finite work that may fail with a `Throwable`; a fiber is its
running, interruptible execution. A `ZStream[R, E, A]` emits zero or more `A`
values over time. Scalive uses interruption to cancel work when its owner or a
replacement disappears.

Use an injected service for durable or shared state. The APIs on this page own
work only for one connected LiveView lifecycle.

These APIs attach resources to one connected LiveView lifecycle. Scalive
releases them when that lifecycle closes. Managed async tasks and subscriptions
also prevent replaced work from delivering stale completions. Prefer these APIs
to `fork` inside a message handler: a manually forked fiber has no automatic
owner, result delivery, or cleanup contract with the LiveView runtime.

@:callout(info)

Phoenix LiveView uses `start_async` and process-owned subscriptions for related
jobs, but it has no general acquire/release registry. Cleanup normally follows
process ownership or a best-effort `terminate/2` callback. Scalive exposes ZIO
`Task` and `ZStream` directly for message-producing work and adds a deterministic
connected-lifecycle scope for other resources.

@:@

## Acquire Other Connected Resources {#connected-resources}

The @:apiSymbol(trait:scalive.ConnectedResources)`ConnectedResources`@:@
capability is exposed through the connected mount branch. Call
@:apiSymbol(def:scalive.ConnectedResources.acquireRelease)`acquireRelease`@:@
there and provide a non-failing finalizer:

```scala
trait SessionMonitor:
  def stop: UIO[Unit]

def startSessionMonitor: Task[SessionMonitor]

def mount(ctx: MountContext): Task[Model] =
  ctx.connection match
    case Connection.Disconnected =>
      loadDisconnectedModel

    case Connection.Connected(connected) =>
      connected.resources
        .acquireRelease(startSessionMonitor)(_.stop)
        .flatMap(monitor => loadConnectedModel(monitor))
```

The capability is intentionally exposed only during connected mount. Do not
retain it in the model, a service, or a callback for later acquisition; use a
keyed managed API or an explicitly scoped service when ownership changes after
mount.

The acquisition `Task[A]` may fail normally. Once it succeeds, Scalive registers
the finalizer before returning the value. That finalizer runs exactly once when
the lifecycle closes, including when the remainder of connected mount or the
initial render fails. An acquisition failure does not run the finalizer, and an
attempt made after lifecycle closure fails before acquisition starts.

The finalizer is a `UIO[Unit]`: it has no expected typed failure. If an external
cleanup API returns `Task[Unit]`, make the policy explicit by recovering and
logging, retrying within a bound, or deliberately converting the failure to a
defect. Do not leave that decision implicit at shutdown.

ZIO runs acquisition and finalization uninterruptibly so interruption cannot
strand a partially registered resource. Keep both effects short and bounded;
a blocked acquisition or finalizer also delays lifecycle shutdown.

The owner is the connected LiveView lifecycle, not an application session ID.
Two tabs using the same login session acquire independent resources, and closing
one tab does not release the other tab's resource. Root and nested LiveViews on
one WebSocket also have independent lifecycles; closing the socket releases all
of them. Put genuinely session-shared resources in an injected service with
explicit reference counting or leases.

Use this capability for registrations, observers, external subscriptions, and
handles whose release is the only lifecycle interaction. Continue to use
`ctx.subscriptions` when a timer or feed emits `Msg` values, or when work must be
replaced or cancelled while the LiveView remains mounted.

Cleanup starts after the server observes lifecycle termination. External
presence and locks still need leases or expiry for node failure and undetected
network partitions.

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
The report example displays a fixed failure explanation while the documentation
site's diagnostic state viewer records only the state label.

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
guards `start` with its model so the button and server transition agree:

```scala
ctx.subscriptions
  .start(ClockSubscription, SubscriptionDelivery.Lossless)(ticks(1.second))
```

@:apiSymbol(def:scalive.Subscriptions.replace)`replace`@:@ starts or swaps the
stream under a key. Replacing the registration interrupts the old stream and
resubscribes the runtime's current set. Use replacement when changing polling
frequency, topic, or another stream parameter is valid application behavior:

```scala
ctx.subscriptions
  .replace(ClockSubscription, SubscriptionDelivery.Lossless)(ticks(250.millis))
```

The required `SubscriptionDelivery` argument controls backpressure at the
LiveView mailbox. `Lossless` delivers every emitted message in order; use it
when every transition matters. `Latest` coalesces pending delivery so newer
messages supersede older ones; use it for high-frequency state snapshots where
only the newest value matters. The compiled clock example below deliberately
uses `Lossless` for both start and replacement so every tick is counted.

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
`handleMessage`. Registrations exist only for the connected lifecycle and do
not run during disconnected rendering. Mount therefore starts required streams
again for each new connected lifecycle.

## Keep Ownership Local {#keep-ownership-local}

Keys are runtime identities, not persistence keys. Keep them stable within one
owner and unique among that owner's resources. Nested LiveViews already have
independent resource namespaces, but deriving keys from instance identity also
makes ownership visible in traces and prevents collisions when code moves into
a shared owner.

Keep durable results in an application service or database when they must
survive navigation or reconnect. `AsyncValue`, task registrations, and
subscription registrations are lifecycle state. On page exit, Scalive releases
the managed resource; on remount, initialize the model and registrations from
their real source of truth.

## Study The Complete Examples {#study-the-complete-examples}

The clock implementation shows start, replacement, cancellation, reset, and an
instance-scoped subscription key:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-example)

The report implementation shows typed results, retained values, deterministic
failure, stale-completion suppression, explicit cancellation, retry, and reset:

@:sourceRegion(documentation/site/src/scalive/docs/examples/AsyncReportExample.scala, async-report-example)

The connected registration example shows the acquired handle in its initial
model, updates ordinary model state without reacquiring, and releases the exact
handle when its LiveView closes:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ConnectedResourceExample.scala, connected-resource-example)

Run the [connected lifecycle registration](../examples/connected-resource.md),
[managed clock subscription](../examples/subscription-clock.md), and
[managed async report](../examples/async-report.md) alongside the documentation
site's diagnostic views to correlate messages, model transitions, and final DOM
changes.

## Related Tasks {#related-tasks}

- Inject the service that starts the work with [Services and dependency injection](services-and-zlayer-injection.md#prerequisites).
- Apply emitted collection changes with [Streams and collection updates](streams-and-collection-updates.md#prerequisites).
- Choose a test boundary for connected behavior with [Testing LiveViews](testing.md#cover-connected-behavior).
