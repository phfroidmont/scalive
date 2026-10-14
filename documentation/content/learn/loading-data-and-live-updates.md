{%
title = "Loading data and live updates"
description = "Give a LiveView a service, load slow data once the page connects, and keep it current with a subscription."
order = 5
section = learn
%}

## Define a service {#define-a-service}

So far every page has kept its data in its own model. Real pages read data from
databases, other services, or devices. This lesson builds a thermostat page
that reads a temperature sensor when the page connects and refreshes the
reading every ten seconds.

Put the sensor behind a service. Create `app/src/quickstart/Thermostat.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Thermostat.scala, learn-thermostat-service)

`Thermostat` is an ordinary Scala trait, so a test can replace it with a
predictable implementation. The `ZLayer` describes how the application builds
the real one at startup. This stand-in returns a random temperature after half a
second, like a slow device would.

## Give the page its service {#give-the-page-its-service}

Add a route to `Routes.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Routes.scala, learn-thermostat-route)

In `Main.scala`, register the route with
@:apiSymbol(def:scalive.LiveRouteBuilder.from)`from`@:@ instead of `->`. It
receives the service and builds the LiveView:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Main.scala, learn-thermostat-registration)

The route now requires a `Thermostat`, and so do the routes built from the
application. Provide the layer where the server starts:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Main.scala, learn-thermostat-provide)

If `Main` does not provide it, the program does not compile. Scalive calls the
`from` function for each mount, so the LiveView can be a plain class that takes
the service in its constructor. The
[services guide](../guides/services-and-zlayer-injection.md) covers several
services, layers that need resources, and routes with parameters.

## Load data when the page connects {#load-data-when-the-page-connects}

A LiveView mounts twice: once to render the first HTML response, and again when
the browser connects. Reading a slow sensor during the first mount would delay
the page, and its result would be discarded when the connected mount builds a
new model. Read it in the connected mount instead:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-mount)

`ctx.connection` is `Connection.Disconnected` during the first render and
`Connection.Connected(connected)` once the browser has connected. Only the
connected branch has `connected.async` and `connected.subscriptions`, so the
compiler prevents starting background work during the first render. Both
branches return a model whose reading is
@:apiSymbol(enum:scalive.AsyncValue)`AsyncValue.loading`@:@.

@:apiSymbol(def:scalive.Async.start)`connected.async.start`@:@ runs the
`Task` in the background and returns immediately, so the connected page renders
without waiting. The key and the result-to-message function come from the
companion object:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-model)

`AsyncKey[Double]` names the task and fixes its result type. When the task
finishes, Scalive sends `Msg.Measured(result)`, where `result` is a
`LiveAsyncResult[Double]`: `Succeeded(value)`, `Failed(cause)`, or
`Cancelled(reason)`. The task stops if the visitor leaves the page.

## Refresh on a timer {#refresh-on-a-timer}

The second statement in the connected branch starts a subscription.
@:apiSymbol(def:scalive.Subscriptions.start)`connected.subscriptions.start`@:@
runs a `ZStream` for as long as the page stays connected and sends each value it
emits as a message. This stream emits `Msg.Refresh` every ten seconds.
`SubscriptionDelivery.Latest` skips refreshes that queue up while the page is
busy, because only the newest one matters.

`Msg.Refresh` starts the same task again, and the **Refresh** button sends the
same message:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-handle-message)

Starting a key that is already running replaces the old task, so a slow reading
can never overwrite a newer one. `loading()` marks the reading as loading while
keeping the last value, and `updated(result)` applies the finished result.

## Render each state {#render-each-state}

The view renders every state of the reading:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-view)

`valueOption` is the last successful reading, so the temperature stays visible
while a refresh runs or after it fails. `isLoading` shows the progress message,
and `isFailed` shows the alert only after a failure. The visitor sees a fixed
message rather than the exception.

## Try it {#try-it}

The complete `app/src/quickstart/ThermostatLiveView.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/ThermostatLiveView.scala, learn-thermostat-live-view)

Run `mill app.run` and open `http://localhost:8080/thermostat`. The page shows
**Reading the sensor…**, then a temperature about half a second later. The
reading changes every ten seconds, and **Refresh** reads it immediately. View
the page source: the first HTML response contains **Reading the sensor…**,
because the sensor is only read after the browser connects.

## Choose where to load {#choose-where-to-load}

Load in the connected branch when the data is slow or only matters to visitors
with a live connection. Load directly in `mount` when the data is fast and
should be in the first HTML response, for example for search engines or
visitors whose JavaScript has not loaded yet. Both mounts then run the query.

When a service can announce changes itself, subscribe to its stream rather than
polling. The stream maps each change to a message:

```scala fragment
connected.subscriptions.start(Changes, SubscriptionDelivery.Lossless)(
  orders.placed.map(Msg.OrderPlaced(_))
)
```

The [asynchronous work](../guides/async-work.md) and
[subscriptions](../guides/subscriptions.md) guides cover cancellation,
replacing a stream, and work owned by components. Next,
[URL state and navigation](url-state-and-navigation.md) keeps page state in the
address bar.
