{%
title = "Services and dependency injection"
description = "Inject application services into LiveViews, derive route layers from constructors, and provide shared dependencies at startup."
order = 22
section = guides
group = "Routing and application structure"
%}

## Before You Start {#prerequisites}

Start with a working Live route whose `mount` and `handleMessage` callbacks can
call application operations through `Task` effects.

## Inject A Service Into A LiveView {#inject-a-service-into-a-liveview}

Use constructor injection when a @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@
needs an application service such as a repository, API client, or mailer. The
constructor makes the dependency explicit, while a ZIO layer supplies its
implementation when the application starts.

In the types used below, `ZIO[R, E, A]` needs environment `R`, may fail with
`E`, and may produce `A`; `Task[A]` uses `Throwable` as its error and `UIO[A]`
cannot fail. A `ZLayer[RIn, E, ROut]` constructs `ROut` from `RIn`, while
`ULayer` and `URLayer` are infallible aliases.

The complete path has four parts:

1. define a service trait;
2. accept that service in the LiveView constructor;
3. derive a LiveView layer with `ZLayer.fromFunction`;
4. provide the service implementation to the assembled routes.

## Define The Service Boundary {#define-the-service-boundary}

Expose the operations the LiveView needs instead of passing low-level clients or
mutable references into UI code. For example, a reports page can depend on this
service:

```scala
final case class Report(id: Long, title: String)

trait Reports:
  def recent: Task[Vector[Report]]
```

Use an error type appropriate to the application. `Task` works when callers
handle arbitrary operational failures. A more specific `IO[ReportsError, A]`
can preserve domain failures until the LiveView maps them into user-facing state.

Implementations belong outside the LiveView. This small in-memory layer is useful
for local development and tests:

```scala
object Reports:
  val inMemory: ULayer[Reports] =
    ZLayer.succeed(new Reports:
      def recent = ZIO.succeed(
        Vector(
          Report(1L, "Daily sales"),
          Report(2L, "Open incidents")
        )
      )
    )
```

A production layer can instead acquire a database pool or HTTP client and expose
the same `Reports` interface. If it acquires resources, build it with
`ZLayer.scoped` so ZIO releases them when the application shuts down.

## Capture The Service In The Constructor {#capture-the-service-in-the-constructor}

Accept the service in the LiveView constructor and use it from lifecycle
callbacks:

```scala
final class ReportsLiveView(reports: Reports)
    extends LiveView[ReportsLiveView.Msg, ReportsLiveView.Model]:
  import ReportsLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    reports.recent.map(Model.Loaded.apply)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Refresh => reports.recent.map(Model.Loaded.apply)

  def view(model: Signal[Model]) =
    val loaded = model.map {
      case Model.Loaded(reports) => Some(reports)
    }
    div(
      button(on.click(Msg.Refresh), "Refresh"),
      loaded.option { reports =>
        ul(reports.splitBy(_.id) { (_, report) =>
          li(report.map(_.title))
        })
      }
    )
```

Callbacks return `Task`, so the constructor-captured service is directly available
to `mount` and `handleMessage`; callback environment types do not need to change.

Define the model and messages as usual:

```scala
object ReportsLiveView:
  enum Msg:
    case Refresh

  enum Model:
    case Loaded(reports: Vector[Report])
```

## Derive And Register The LiveView Layer {#derive-and-register-the-liveview-layer}

Derive an infallible LiveView layer from its constructor:

```scala
object ReportsLiveView:
  val layer: URLayer[Reports, ReportsLiveView] =
    ZLayer.fromFunction(reports => new ReportsLiveView(reports))
```

Register that layer instead of constructing the LiveView manually:

```scala
val reportsRoute =
  (live / "reports") -> ReportsLiveView.layer
```

The @:apiSymbol(def:scalive.LiveRouteBuilder.->)`route operator`@:@ adds `Reports`
to the route environment. If the application does not provide a `Reports` layer,
the server startup effect cannot compile with a fully provided environment.

`ZLayer.fromFunction` also handles multiple constructor dependencies. A
LiveView constructed with `(reports: Reports, audit: AuditLog)` produces a layer
requiring both services; no manual environment lookup is necessary.

## Provide Services At Startup {#provide-services-at-startup}

Build the router, combine it with any ordinary HTTP routes, and provide shared
service layers where the server starts:

```scala
val routes = Live.router(reportsRoute)

Server.serve(routes).provide(
  Server.default,
  Reports.inMemory
)
```

In a production application, replace `Reports.inMemory` with the production
implementation. Provide database pools, HTTP clients, repositories, and other
long-lived resources at this boundary. This keeps construction in one place and
lets ZIO report missing dependencies before the server can run.

## Handle Service Failures In The Model {#handle-service-failures-in-the-model}

An unhandled service failure fails the LiveView lifecycle or message operation.
Recover when the user can act on the failure, and represent that state explicitly
in the model:

```scala
def load: UIO[Model] =
  reports.recent
    .map(Model.Loaded.apply)
    .catchAll(_ => ZIO.succeed(Model.Failed("Reports are temporarily unavailable.")))
```

Prefer a safe user-facing message over displaying raw exception details. Log the
underlying cause with request or correlation context where the application can
diagnose it. Keep retry as a typed message that invokes the service again.

## Understand Service And LiveView Lifetimes {#understand-service-and-liveview-lifetimes}

The service layer provided to `Server.serve` is normally built once and shared by
the routes that require it. Scalive builds the route's LiveView layer separately
for disconnected rendering and connected mount. Both LiveView objects may refer
to the same shared service, but each socket owns its own immutable model and
lifecycle resources.

Do not provide a prebuilt LiveView as an application service. Register its
constructor-derived layer so Scalive can create the lifecycle instances it
needs.

A shared service may own mutable or durable application state, but it must apply
the application's authorization and isolation rules. Scope records by tenant or
user when required, bound retained data, and use concurrency-safe implementations.
Visitor-specific presentation state still belongs in the LiveView model.

## Supply A Test Implementation {#supply-a-test-implementation}

Tests can replace the production service without changing the LiveView:

```scala
val testReports = ZLayer.succeed(new Reports:
  def recent = ZIO.succeed(Vector(Report(42L, "Fixture report")))
)

val viewLayer = ReportsLiveView.layer.provide(testReports)
```

Use fixed results for rendering and message tests. Add failing or delayed test
implementations when verifying error, retry, replacement, or cancellation
behavior.

## Explore The Runnable Example {#explore-the-runnable-example}

The reports example applies the complete structure from this guide. It handles
loaded, empty, and failed service results; refresh queries the service again,
while reset changes only connection-local selection state.

@:sourceRegion(documentation/site/src/scalive/docs/examples/ReportsExample.scala, reports-service)

@:sourceRegion(documentation/site/src/scalive/docs/examples/ReportsExample.scala, reports-liveview)

Try the embedded preview or open the real layer-backed route from the
[reports service injection example](../examples/service-injection.md).

## Related Tasks {#related-tasks}

- Share an authentication store between HTTP and Live routes in [Authentication and sessions](authentication.md#provide-one-shared-authentication-service).
- Move finite, streaming, or acquired work under lifecycle ownership with [Asynchronous work, subscriptions, and connected resources](async-work-and-subscriptions.md#prerequisites).
- Replace production layers in [Testing LiveViews](testing.md#prerequisites).
