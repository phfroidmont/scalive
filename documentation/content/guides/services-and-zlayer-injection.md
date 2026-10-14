{%
title = "Services and dependency injection"
description = "Pass application services to LiveViews through their constructors, register service-backed routes with from, and provide the services when the server starts."
order = 23
section = guides
group = "Routing and application structure"
%}

## Before you start {#prerequisites}

Start from a working application such as the one in the
[quick start](../learn/quick-start.md). This guide adds a reports page whose data
comes from an application service.

## Inject a service into a LiveView {#inject-a-service-into-a-liveview}

Use constructor injection when a @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@
needs an application service such as a repository, API client, or mailer. The
constructor makes the dependency explicit, and a ZIO layer supplies the
implementation when the application starts.

In the types used below, `ZIO[R, E, A]` needs environment `R`, may fail with
`E`, and may produce `A`. `Task[A]` uses `Throwable` as its error and `UIO[A]`
cannot fail. A `ZLayer[RIn, E, ROut]` constructs `ROut` from `RIn`, and `ULayer`
is a layer that needs nothing and cannot fail.

The complete path has four steps:

1. Define a service trait.
2. Accept that service in the LiveView constructor.
3. Build the route with `from`, which reads the service from the application
   environment.
4. Provide the service's layer where the server starts.

## Define the service boundary {#define-the-service-boundary}

Expose the operations the LiveView needs instead of passing low-level clients or
mutable references into UI code. A reports page can depend on this service:

@:sourceRegion(documentation/fixtures/guides/src/reports/Reports.scala, services-service)

Use an error type appropriate to the application. `Task` works when callers
handle arbitrary operational failures. A more specific `IO[ReportsError, A]` can
preserve domain failures until the LiveView maps them into user-facing state.

`Reports.inMemory` is a small layer for local development and tests. A
production layer can instead acquire a database pool or HTTP client and expose
the same `Reports` interface. If it acquires resources, build it with
`ZLayer.scoped` so ZIO releases them when the application shuts down.

## Capture the service in the constructor {#capture-the-service-in-the-constructor}

Accept the service in the LiveView constructor and use it from lifecycle
callbacks:

@:sourceRegion(documentation/fixtures/guides/src/reports/ReportsLiveView.scala, services-liveview)

Callbacks return `Task`, so `mount` and `handleMessage` call the captured service
directly. Their environment type does not change.

This `mount` loads the reports in both the disconnected and the connected mount,
so the first HTML response already lists them. When a query is too slow for the
first response, start it once the page connects instead, as
[Loading data and live updates](../learn/loading-data-and-live-updates.md#load-data-when-the-page-connects)
shows.

The model has one case for loaded reports and one for a failure, and the view
handles both. `loaded` and `failure` are `Signal[Option[...]]` values, and
`option` renders its content only while the value is defined.

## Register the route {#register-the-route}

Build the route with @:apiSymbol(def:scalive.LiveRouteSetup.from)`from`@:@:

@:sourceRegion(documentation/fixtures/guides/src/reports/Main.scala, services-route)

`from` takes an effect that reads the service from the application environment
and builds the LiveView. The registration's type,
`LiveRegistration[Reports]`, records that it needs `Reports`. An application
built from it is a `LiveApplication[Reports]`, and the server does not compile
until something provides `Reports`.

When the LiveView needs several services, read them all in the effect. ZIO infers
the combined requirement, here `LiveRegistration[Reports & Mailer]`:

```scala fragment
Routes.exports.from(
  for
    reports <- ZIO.service[Reports]
    mailer  <- ZIO.service[Mailer]
  yield ExportsLiveView(reports, mailer)
)
```

Inside a named live session, `from((user: CurrentUser) => ...)` also receives the
context produced by the session's mount aspects, such as the signed-in user. See
[Authentication and sessions](authentication.md#provide-one-shared-authentication-service).

## Provide services at startup {#provide-services-at-startup}

Register the route and provide the service where the server starts:

@:sourceRegion(documentation/fixtures/guides/src/reports/Main.scala, services-main)

This is the quick start's `Main` with two changes: the router registers
`Routes.reports`, and `provide` adds `Reports.inMemory` next to the server layer.
In a production application, provide the production implementation instead.
Provide database pools, HTTP clients, repositories, and other long-lived
resources here. Construction then stays in one place, and the compiler reports a
missing layer before the server can run.

## Handle service failures in the model {#handle-service-failures-in-the-model}

An unhandled service failure fails the LiveView's mount or message handling.
Recover when the user can act on the failure, and represent that state in the
model. In `ReportsLiveView`, `load` logs the cause and returns `Model.Failed`
with a message that is safe to show. **Refresh** sends a typed message that
calls the service again.

Show a safe user-facing message rather than raw exception details. Log the
underlying cause with enough context, such as a request or correlation ID, to
diagnose it.

## Understand service and LiveView lifetimes {#understand-service-and-liveview-lifetimes}

The layer given to `provide` is built once and shared by every route that needs
the service. Scalive runs the route's `from` effect for the disconnected
render and again for each connected mount. Every LiveView lifecycle therefore
gets its own LiveView instance, model, and lifecycle resources, while all of
them share the same service.

A shared service may own mutable application state, or durable state kept in a
database. It must apply the application's authorization and isolation rules.
Scope records by tenant or user when required, bound retained data, and use
concurrency-safe implementations. Per-visitor presentation state, such as the
selected report, still belongs in the LiveView model.

## Supply a test implementation {#supply-a-test-implementation}

Because the service is a constructor argument, a test can pass its own
implementation directly:

@:sourceRegion(documentation/fixtures/guides/test/src/reports/ReportsLiveViewSpec.scala, services-test)

Use fixed results for rendering and message tests. Add failing or delayed
implementations to test errors, retries, and cancellation.

To test the registered route, provide a test layer to the application instead:

@:sourceRegion(documentation/fixtures/guides/test/src/reports/ReportsRouteSpec.scala, services-route-test)

The `zio.test` import excludes `Live` because Scalive's
@:apiSymbol(object:scalive.Live)`Live`@:@ has the same name. See
[Testing LiveViews](testing.md#test-disconnected-rendering).

## Explore the runnable example {#explore-the-runnable-example}

The reports example applies the same structure. It handles loaded, empty, and
failed service results. Refresh queries the service again, while reset changes
only connection-local selection state.

@:sourceRegion(documentation/site/src/scalive/docs/examples/ReportsExample.scala, reports-service)

@:sourceRegion(documentation/site/src/scalive/docs/examples/ReportsExample.scala, reports-liveview)

Try the embedded preview, or open the real service-backed route from the
[reports service injection example](../examples/service-injection.md).

## Related tasks {#related-tasks}

- Share an authentication store between HTTP and Live routes in [Authentication and sessions](authentication.md#provide-one-shared-authentication-service).
- Move finite, streaming, or acquired work under lifecycle ownership with [Asynchronous work](async-work.md#prerequisites).
- Test LiveViews at each boundary in [Testing LiveViews](testing.md#prerequisites).
