{%
title = "Subscriptions"
description = "Run a stream of messages for as long as a page or component stays connected: timers, feeds, and application events."
order = 41
section = guides
group = "Async and lifecycle"
%}

## Before You Start {#prerequisites}

Start with a connected `LiveView` whose messages already produce explicit model
transitions. [Loading data and live updates](../learn/loading-data-and-live-updates.md)
introduces subscriptions with a complete example. Use
[asynchronous work](async-work.md#prerequisites) instead for one finite task.

A subscription runs a `ZStream[Any, Nothing, Msg]` owned by one connected
LiveView or component and sends each emitted value to `handleMessage`. Scalive
stops it when its owner closes. A `ZStream[R, E, A]` emits zero or more `A`
values over time; the subscription's stream needs no environment and cannot
fail.

## Start A Subscription When The Page Connects {#start-a-subscription-when-the-page-connects}

Most pages subscribe in `mount`, once the browser has connected, and stay
subscribed while the page is open. Match
@:apiSymbol(def:scalive.LifecycleContext.connection)`ctx.connection`@:@ and start
the stream in the connected branch:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-mount)

@:apiSymbol(def:scalive.RootMountConnected.subscriptions)`connected.subscriptions`@:@
exists only in the `Connection.Connected` branch, so the disconnected HTTP
render cannot start a stream. Every connected mount, including the one after a
reconnect, subscribes again. This stream emits `Msg.Refresh` every ten seconds.

When a service announces changes itself, subscribe to its stream rather than
polling, and map each change to a message:

```scala fragment
connected.subscriptions.start(Changes, SubscriptionDelivery.Lossless)(
  orders.placed.map(Msg.OrderPlaced(_))
)
```

Loading a snapshot and subscribing to its changes separately can miss or reorder
a change made while both are starting. Prefer a service stream that emits the
current value first and then each change, so one subscription keeps the model
current.

After mount, message handlers reach the same capability through
@:apiSymbol(def:scalive.MessageContext.subscriptions)`ctx.subscriptions`@:@.

## Own Long-Lived Streams With Subscriptions {#own-long-lived-streams-with-subscriptions}

A @:apiSymbol(opaque-type:scalive.SubscriptionKey)`SubscriptionKey`@:@ identifies one
registered stream in its root LiveView or exact component owner. The stream must
emit that owner's `Msg` type and cannot fail. The clock example keys its
subscription by instance, `SubscriptionKey(s"subscription-clock-$instanceId")`,
and builds its stream with:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-ticks)

@:apiSymbol(def:scalive.Subscriptions.start)`start`@:@ rejects a duplicate
registered key, including a component registration whose worker is suspended by
dormancy. Use it when starting twice indicates a state-machine mistake. The clock
guards `start` with its model so the button and server transition agree:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-start)

## Replace Or Cancel A Subscription {#replace-or-cancel-a-subscription}

@:apiSymbol(def:scalive.Subscriptions.replace)`replace`@:@ starts or swaps the
stream under a key. Replacing the registration interrupts the old stream and
resubscribes the runtime's current set. Use replacement when changing polling
frequency, topic, props, or another stream parameter is valid application
behavior:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-replace)

@:apiSymbol(def:scalive.Subscriptions.cancel)`cancel`@:@ removes the registration
and succeeds when it is already absent. Update the model in the same handler so
the rendered controls describe the registered state:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-cancel)

## Choose A Delivery Policy {#choose-a-delivery-policy}

The required @:apiSymbol(enum:scalive.SubscriptionDelivery)`SubscriptionDelivery`@:@
argument controls backpressure at the LiveView mailbox. `Lossless` delivers
every emitted message in order; use it when every transition matters. `Latest`
coalesces pending delivery so newer messages supersede older ones; use it for
high-frequency state snapshots or refresh signals where only the newest value
matters. The clock deliberately uses `Lossless` for both start and replacement
so every tick is counted.

Root subscription messages pass through the root's typed info lifecycle hooks
before `handleMessage`. Registrations exist only for the connected lifecycle and
do not run during disconnected rendering. Mount therefore starts required
streams again for each new connected lifecycle.

Construct each stream so resources are acquired anew every time it runs. Put
handles inside `ZStream.scoped` or `ZStream.unwrapScoped`; do not acquire a handle
first and capture it in a stream value. Replacement and component revival rerun
the stored stream, so a captured handle may already have been released.

## Own A Stream In A Component {#component-subscriptions}

Components expose the same `Subscriptions[Msg]` capability through
@:apiSymbol(def:scalive.ComponentConnected.subscriptions)`connected.subscriptions`@:@
during connected mount and update, and through
@:apiSymbol(def:scalive.ComponentMessageContext.subscriptions)`ctx.subscriptions`@:@
while handling a component message. A registered stream emits the component's
`Msg`, not the parent LiveView's message type. Delivery follows the component's
typed event hooks and `handleMessage`. Root info hooks do not handle component
messages.

The subscription namespace is the exact runtime component instance. Two
instances can deliberately reuse the same `SubscriptionKey` without collision,
and root registrations remain independent. A disconnected component mount or
update cannot acquire a subscription because the capability exists only in its
`Connection.Connected` branch.

When a rendered component becomes dormant while the runtime waits for browser
removal confirmation, Scalive stops its subscription worker but retains the
registration with the component model. If that same instance returns first, the
runtime runs the stored `ZStream` again with fresh internal tokens, without
calling mount or update merely to restart it. Pending values from the old worker
are discarded. This is interruption and resubscription, not a replay or lossless
handoff guarantee.

Only a stream still actively registered at dormancy is eligible for this
automatic restart. An explicitly cancelled stream, a normally completed stream,
or a defective stream stays stopped. Once the browser confirms destruction,
Scalive removes the registration; rendering the same class and logical ID later
mounts a fresh component and may register a fresh stream. Root subscriptions
are unaffected by component dormancy.

## Keep Ownership Local {#keep-ownership-local}

Keys are runtime identities, not persistence keys. Keep them stable within one
owner and unique among that owner's subscriptions. Deriving root keys from
instance identity makes ownership visible in traces and prevents collisions when
code moves into a shared owner. On page exit, Scalive stops every registered
stream; on remount, start them again from the page's real source of truth.

## Study The Complete Examples {#study-the-complete-examples}

The clock implementation shows start, replacement, cancellation, reset, and an
instance-scoped subscription key:

@:sourceRegion(documentation/site/src/scalive/docs/examples/SubscriptionClockExample.scala, subscription-clock-example)

The component implementation shows two instances safely reusing one local key,
mount-started streams, local replacement and cancellation, and parent-controlled
visibility:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ComponentSubscriptionsExample.scala, component-subscriptions-example)

Run the [managed clock subscription](../examples/subscription-clock.md) and
[component-owned subscriptions](../examples/component-subscriptions.md)
alongside the documentation site's diagnostic views to correlate messages, model
transitions, and final DOM changes.

## Related Tasks {#related-tasks}

- Run one finite task with [Asynchronous work](async-work.md#prerequisites).
- Apply emitted collection changes with [Streams and collection updates](streams-and-collection-updates.md#prerequisites).
- Inject the service that provides the stream with [Services and dependency injection](services-and-zlayer-injection.md#prerequisites).
- Test connected behavior with [Testing LiveViews](testing.md#cover-connected-behavior).
