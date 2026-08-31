{%
title = "Lifecycle observability"
description = "Observe LiveView lifecycle outcomes safely, publish the built-in ZIO metrics, and interpret their fixed low-cardinality contract."
order = 72
section = guides
group = "Assets and operations"
%}

## Before You Start {#prerequisites}

Start with an assembled `LiveApplication` and the `LiveSecurity` or
`ZioHttpConfig` used to build its routes. Decide which ZIO Metrics connector the
application will use before relying on the built-in metrics in production.

## Choose The Observation Boundary {#choose-the-observation-boundary}

Pass one @:apiSymbol(trait:scalive.LifecycleObserver)`LifecycleObserver`@:@ while
assembling routes to receive structured events from disconnected HTTP rendering
and connected WebSocket lifecycles. It reports disconnected render and mount
outcomes, joins, connected mounts and turns, handler and subscription failures,
queue pressure, and lifecycle termination. Durations are monotonic nanoseconds
and stop before physical WebSocket transmission.

Lifecycle observation begins inside Scalive's LiveView operation boundary. It
does not report endpoint request duration, an Origin rejection before WebSocket
upgrade, a failed upgrade, proxy behavior, or infrastructure health. Instrument
those boundaries with ZIO HTTP middleware, application access logs, edge
telemetry, and infrastructure metrics.

Use [lifecycle hooks](lifecycle-hooks.md#choose-hooks) when application policy
must intercept or alter a particular root or component stage. Use a
`LifecycleObserver` for route-wide operational events that cannot alter the
observed lifecycle.

## Register A Safe Observer {#register-an-observer}

Create a custom observer for structured logging or tracing and pass it to
@:apiSymbol(def:scalive.ZioHttp.routes)`ZioHttp.routes`@:@:

```scala
val applicationObserver = LifecycleObserver.fromFunction { event =>
  ZIO.logAnnotate("lifecycle_event", event.name) {
    ZIO.logDebug("Scalive lifecycle event")
  }
}

val liveRoutes = ZioHttp.routes(application, security, applicationObserver)
```

The example records only the stable event name. Do not log `event.toString`:
failure events may contain an application-thrown cause whose message contains
application data. Select and redact any additional log or span fields under the
same policy as application logs.

Observers run synchronously and must remain non-blocking. Scalive catches all
observer failures so they cannot fail the lifecycle being observed, but a slow
observer still delays that lifecycle. If an integration performs network or
file I/O, hand the event to an application-owned exporter through a
non-suspending bounded offer, such as a dropping queue, rather than performing
that I/O or waiting for queue capacity in `observe`. Define the overflow policy
explicitly and record dropped exporter events in a separate low-cardinality
metric.

Scalive may add event or classification cases as lifecycle coverage grows. When
matching an event enum, include a fallback that safely ignores or generically
records unknown cases so a library upgrade does not introduce a runtime
`MatchError`.

## Publish The Built-In Metrics {#publish-built-in-metrics}

@:apiSymbol(object:scalive.LifecycleMetrics)`LifecycleMetrics`@:@ provides an
exporter-independent ZIO Metrics adapter with fixed metric names, labels, and
histogram boundaries:

```scala
val liveRoutes = ZioHttp.routes(
  application,
  security,
  LifecycleMetrics.observer
)
```

Metric updates write synchronously to ZIO's in-memory metric registry and do no
network or file I/O. Install and configure the application's preferred
[ZIO Metrics connector](https://zio.dev/zio-metrics-connectors/) to export that
registry to Prometheus or another supported monitoring backend. Connector
dependencies, polling, and endpoint exposure remain application concerns.

After enabling the connector, request one Live route and verify that
`scalive_disconnected_render_total` increments. Connect its WebSocket and verify
`scalive_join_total` and `scalive_mount_total`. This checks the LiveView observer,
registry, connector, and exported endpoint as one path before dashboards or
alerts depend on it.

Compose the metrics adapter with application-specific logging or tracing when
both are needed. Observers run in composition order, and a failure in one does
not prevent the next observer from receiving the event:

```scala
val observer = LifecycleMetrics.observer.andThen(applicationObserver)
val liveRoutes = ZioHttp.routes(application, security, observer)
```

The built-in metric contract is intentionally fixed. Implement a separate
`LifecycleObserver` when an application requires different names, labels, or
histogram boundaries.

## Use The Metric Families {#metric-families}

The adapter publishes these metric families:

| Metric | Labels | Meaning |
| --- | --- | --- |
| `scalive_disconnected_render_total` | `outcome`, `failure`, `stage` | Completed disconnected renders. |
| `scalive_disconnected_render_duration_seconds` | `outcome`, `failure`, `stage` | Disconnected render duration histogram. |
| `scalive_join_total` | `outcome`, `target`, `reconnect`, `retry`, `failure`, `stage` | Completed root and nested join attempts. |
| `scalive_join_duration_seconds` | `outcome`, `target`, `reconnect`, `retry`, `failure`, `stage` | Join duration histogram. |
| `scalive_mount_total` | `outcome`, `phase`, `failure`, `stage` | Completed disconnected and connected mounts. |
| `scalive_mount_duration_seconds` | `outcome`, `phase`, `failure`, `stage` | Mount duration histogram. |
| `scalive_turn_total` | `outcome`, `kind`, `failure`, `stage` | Completed connected turns. |
| `scalive_turn_duration_seconds` | `outcome`, `kind`, `failure`, `stage` | Connected turn duration histogram. |
| `scalive_handler_failures_total` | `kind`, `failure`, `stage` | Handler failures, also represented by failed turns. |
| `scalive_handler_failure_duration_seconds` | `kind`, `failure`, `stage` | Failed handler duration histogram. |
| `scalive_subscription_failures_total` | `delivery`, `failure`, `stage` | Managed subscription defects. |
| `scalive_queue_depth` | `queue`, `status` | Observed bounded queue depth histogram. |
| `scalive_queue_saturation_total` | `queue` | Offers rejected at a bounded queue or pending-command limit. |
| `scalive_lifecycle_terminations_total` | `reason`, `failure`, `stage` | Completed connected lifecycle terminations. |

Metric families describe nested operation boundaries, not mutually exclusive
incident classes. For example, a handler failure is represented by both its
handler-failure metric and the enclosing failed turn. A mount failure may also
fail an enclosing render, bootstrap turn, or join. Query the boundary relevant
to an alert; do not sum counters across families as an incident total.

## Read Labels Correctly {#metric-labels}

Every series in one metric family has the same label keys. Enum values use lower
snake case.

| Label | Values and interpretation |
| --- | --- |
| `outcome` | `succeeded` or `failed` for render, mount, and turn metrics; `succeeded` or `rejected` for join metrics. |
| `target` | `root` or `nested`. |
| `reconnect` | `true` only when the untrusted `_mounts` join counter is a positive integer; otherwise `false`. |
| `retry` | `true` only when the untrusted `_mount_attempts` join counter is a positive integer; otherwise `false`. |
| `phase` | `disconnected` or `connected`. |
| `kind` | `bootstrap`, `browser_event`, `component_browser_event`, `message`, `async_completion`, `subscription`, `component_message`, `component_update`, `upload`, `params_patch`, or `internal`. |
| `delivery` | `lossless` or `latest`. |
| `queue` | `connection_ingress`, `connection_pending_commands`, `kernel_mailbox`, or `writer`. |
| `status` | `sampled` for an ordinary depth observation or `saturated` for a rejected offer. |
| `reason` | `closed`, `redirected`, `disconnected`, or `failed`. Disconnect strings are deliberately collapsed to `disconnected`. |
| `failure` | `none`, `stage`, `join_rejected`, `mailbox_saturated`, `ingress_saturated`, `subscription_defect`, or `interrupted`. |
| `stage` | `none`, `mount`, `connected_turn_guard`, `handler`, `resource_preparation`, `topology_preparation`, `render`, `output_reservation`, `after_render`, `validation`, `identity`, `retirement`, `component_mount`, `component_update`, `component_message`, `component_async`, `component_after_render`, `commit`, `navigation`, `writer`, `upload`, `cleanup`, or `runtime`. |

Successful observations use `failure="none"` and `stage="none"`. A stage failure
uses `failure="stage"` and its specific `stage`; every other failure uses its
specific `failure` and `stage="none"`.

Phoenix's standard client reports `_mounts` and `_mount_attempts` with each join.
@:apiSymbol(class:scalive.LifecycleJoinAttempt)`LifecycleJoinAttempt`@:@ exposes
the parsed counters and derives `isReconnect` and `isRetry`. Missing, malformed,
zero, and negative values all produce `false`; that value means no positive
client-reported counter was observed, not authoritative proof of a first join.
The counters are untrusted diagnostics and never authorization inputs.

## Keep Series Bounded {#metric-cardinality}

Durations are converted from monotonic nanoseconds to seconds. Duration
histograms use exponentially doubling finite buckets from `0.0005` to `65.536`
seconds. Queue-depth histograms use exponentially doubling finite buckets from
`1` to `32768`. Both include a terminal overflow bucket.

The adapter clears ambient ZIO metric tags while updating its metrics. This
prevents request IDs and colliding tags from changing the fixed label contract.
Attach deployment-wide labels such as service, environment, or region in the
metrics exporter instead.

Runtime IDs, exception messages, reconnect counters, and disconnect strings are
excluded from built-in labels. Structured event fields never contain models,
messages, browser payloads, credentials, or session claims. Application-thrown
causes may contain application data and require the same redaction policy as
application logs.

## Related Tasks {#related-tasks}

- Assemble security and routes with [Configuration](configuration.md#current-configuration-contract).
- Map operational symptoms to these metrics in
  [Troubleshooting](troubleshooting.md#use-lifecycle-metrics).
- Verify exported metrics with the deployed application in
  [Deployment](deployment.md#verify-the-deployment).
