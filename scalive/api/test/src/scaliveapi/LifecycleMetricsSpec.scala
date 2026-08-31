package scaliveapi

import zio.*
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.test.*

import scalive.*

object LifecycleMetricsSpec extends ZIOSpecDefault:
  private val durationBoundaries = Boundaries.exponential(0.0005, 2.0, 18)
  private val queueDepthBoundaries = Boundaries.exponential(1.0, 2.0, 16)

  override def spec = suite("LifecycleMetricsSpec")(
    test("records successful and failed turns with seconds and stable failure labels") {
      val succeededLabels = Vector(
        "outcome" -> "succeeded",
        "kind"    -> "browser_event",
        "failure" -> "none",
        "stage"   -> "none"
      )
      val failedLabels = Vector(
        "outcome" -> "failed",
        "kind"    -> "message",
        "failure" -> "stage",
        "stage"   -> "handler"
      )
      val succeededCounter = tagged(
        Metric.counter("scalive_turn_total", "Completed connected LiveView turns"),
        succeededLabels
      )
      val succeededDuration = tagged(
        Metric.histogram(
          "scalive_turn_duration_seconds",
          "Connected LiveView turn duration in seconds",
          durationBoundaries
        ),
        succeededLabels
      )
      val failedCounter = tagged(
        Metric.counter("scalive_turn_total", "Completed connected LiveView turns"),
        failedLabels
      )
      val failedDuration = tagged(
        Metric.histogram(
          "scalive_turn_duration_seconds",
          "Connected LiveView turn duration in seconds",
          durationBoundaries
        ),
        failedLabels
      )
      val lifecycle = ConnectedLifecycleContext(101L, 102L, 103L)

      for
        succeededBefore         <- succeededCounter.value
        succeededDurationBefore <- succeededDuration.value
        failedBefore            <- failedCounter.value
        failedDurationBefore    <- failedDuration.value
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.TurnSucceeded(
                 lifecycle,
                 turnId = 104L,
                 commandId = Some(105L),
                 LifecycleTurnKind.BrowserEvent,
                 durationNanos = 2_000_000_000L
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.TurnFailed(
                 lifecycle,
                 turnId = 106L,
                 commandId = Some(107L),
                 LifecycleTurnKind.Message,
                 durationNanos = 3_000_000L,
                 LifecycleError(
                   LifecycleFailure.Stage(LifecycleFailureStage.Handler),
                   Some(Exception("application secret"))
                 )
               )
             )
        succeededAfter         <- succeededCounter.value
        succeededDurationAfter <- succeededDuration.value
        failedAfter            <- failedCounter.value
        failedDurationAfter    <- failedDuration.value
      yield assertTrue(
        succeededAfter.count == succeededBefore.count + 1.0,
        succeededDurationAfter.count == succeededDurationBefore.count + 1L,
        succeededDurationAfter.sum == succeededDurationBefore.sum + 2.0,
        failedAfter.count == failedBefore.count + 1.0,
        failedDurationAfter.count == failedDurationBefore.count + 1L,
        failedDurationAfter.sum == failedDurationBefore.sum + 0.003
      )
    },
    test("isolates built-in metrics from ambient and colliding metric tags") {
      val labels = Vector(
        "outcome" -> "succeeded",
        "kind"    -> "component_update",
        "failure" -> "none",
        "stage"   -> "none"
      )
      val base = Metric.counter("scalive_turn_total", "Completed connected LiveView turns")
      val expected = tagged(base, labels)
      val contaminated = tagged(
        base,
        labels ++ Vector("request_id" -> "runtime-identifier", "outcome" -> "ambient")
      )
      val lifecycle = ConnectedLifecycleContext(111L, 112L, 113L)

      for
        expectedBefore     <- expected.value
        contaminatedBefore <- contaminated.value
        _ <- LifecycleMetrics.observer
               .observe(
                 LifecycleEvent.TurnSucceeded(
                   lifecycle,
                   turnId = 114L,
                   commandId = Some(115L),
                   LifecycleTurnKind.ComponentUpdate,
                   durationNanos = 1L
                 )
               ) @@ ZIOAspect.tagged("request_id" -> "runtime-identifier") @@
               ZIOAspect.tagged("outcome" -> "ambient")
        expectedAfter     <- expected.value
        contaminatedAfter <- contaminated.value
      yield assertTrue(
        expectedAfter.count == expectedBefore.count + 1.0,
        contaminatedAfter.count == contaminatedBefore.count
      )
    },
    test("records remaining success, failure, and termination variants") {
      val renderLabels = Vector(
        "outcome" -> "succeeded",
        "failure" -> "none",
        "stage"   -> "none"
      )
      val render = tagged(
        Metric.counter(
          "scalive_disconnected_render_total",
          "Completed disconnected LiveView renders"
        ),
        renderLabels
      )
      val renderDuration = tagged(
        Metric.histogram(
          "scalive_disconnected_render_duration_seconds",
          "Disconnected LiveView render duration in seconds",
          durationBoundaries
        ),
        renderLabels
      )
      val join = tagged(
        Metric.counter(
          "scalive_join_total",
          "Completed root and nested LiveView join attempts"
        ),
        Vector(
          "outcome"   -> "succeeded",
          "target"    -> "root",
          "reconnect" -> "false",
          "retry"     -> "false",
          "failure"   -> "none",
          "stage"     -> "none"
        )
      )
      val mountLabels = Vector(
        "outcome" -> "failed",
        "phase"   -> "disconnected",
        "failure" -> "stage",
        "stage"   -> "mount"
      )
      val mount = tagged(
        Metric.counter(
          "scalive_mount_total",
          "Completed disconnected and connected LiveView mounts"
        ),
        mountLabels
      )
      val mountDuration = tagged(
        Metric.histogram(
          "scalive_mount_duration_seconds",
          "LiveView mount duration in seconds",
          durationBoundaries
        ),
        mountLabels
      )
      val terminations = Metric.counter(
        "scalive_lifecycle_terminations_total",
        "Completed connected LiveView lifecycles"
      )
      val closed = tagged(
        terminations,
        Vector("reason" -> "closed", "failure" -> "none", "stage" -> "none")
      )
      val redirected = tagged(
        terminations,
        Vector("reason" -> "redirected", "failure" -> "none", "stage" -> "none")
      )
      val failed = tagged(
        terminations,
        Vector(
          "reason"  -> "failed",
          "failure" -> "ingress_saturated",
          "stage"   -> "none"
        )
      )
      val lifecycle = ConnectedLifecycleContext(121L, 122L, 123L)

      for
        renderBefore         <- render.value
        renderDurationBefore <- renderDuration.value
        joinBefore           <- join.value
        mountBefore          <- mount.value
        mountDurationBefore  <- mountDuration.value
        closedBefore         <- closed.value
        redirectedBefore     <- redirected.value
        failedBefore         <- failed.value
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.DisconnectedRenderSucceeded(124L, durationNanos = -1L)
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.JoinSucceeded(
                 lifecycle,
                 LifecycleJoinTarget.Root,
                 LifecycleJoinAttempt(Some(0L), Some(0L)),
                 durationNanos = 1L
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.MountFailed(
                 LifecycleMount.Disconnected(125L),
                 durationNanos = 250_000_000L,
                 LifecycleError(LifecycleFailure.Stage(LifecycleFailureStage.Mount))
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.LifecycleTerminated(
                 lifecycle,
                 LifecycleTerminationReason.Closed
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.LifecycleTerminated(
                 lifecycle,
                 LifecycleTerminationReason.Redirected
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.LifecycleTerminated(
                 lifecycle,
                 LifecycleTerminationReason.Failed(LifecycleFailure.IngressSaturated)
               )
             )
        renderAfter         <- render.value
        renderDurationAfter <- renderDuration.value
        joinAfter           <- join.value
        mountAfter          <- mount.value
        mountDurationAfter  <- mountDuration.value
        closedAfter         <- closed.value
        redirectedAfter     <- redirected.value
        failedAfter         <- failed.value
      yield assertTrue(
        renderAfter.count == renderBefore.count + 1.0,
        renderDurationAfter.count == renderDurationBefore.count + 1L,
        renderDurationAfter.sum == renderDurationBefore.sum,
        joinAfter.count == joinBefore.count + 1.0,
        mountAfter.count == mountBefore.count + 1.0,
        mountDurationAfter.count == mountDurationBefore.count + 1L,
        mountDurationAfter.sum == mountDurationBefore.sum + 0.25,
        closedAfter.count == closedBefore.count + 1.0,
        redirectedAfter.count == redirectedBefore.count + 1.0,
        failedAfter.count == failedBefore.count + 1.0
      )
    },
    test("records join target and reconnect classifications without runtime identifiers") {
      val labels = Vector(
        "outcome"   -> "rejected",
        "target"    -> "nested",
        "reconnect" -> "true",
        "retry"     -> "true",
        "failure"   -> "interrupted",
        "stage"     -> "none"
      )
      val counter = tagged(
        Metric.counter(
          "scalive_join_total",
          "Completed root and nested LiveView join attempts"
        ),
        labels
      )
      val duration = tagged(
        Metric.histogram(
          "scalive_join_duration_seconds",
          "LiveView join duration in seconds",
          durationBoundaries
        ),
        labels
      )

      for
        before         <- counter.value
        durationBefore <- duration.value
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.JoinRejected(
                 connectionId = 201L,
                 lifecycleId = Some(202L),
                 LifecycleJoinTarget.Nested,
                 LifecycleJoinAttempt(Some(3L), Some(2L)),
                 durationNanos = 500_000_000L,
                 LifecycleError(LifecycleFailure.Interrupted)
               )
             )
        after         <- counter.value
        durationAfter <- duration.value
      yield assertTrue(
        after.count == before.count + 1.0,
        durationAfter.count == durationBefore.count + 1L,
        durationAfter.sum == durationBefore.sum + 0.5
      )
    },
    test("records render, mount, handler, and subscription families") {
      val render = tagged(
        Metric.counter(
          "scalive_disconnected_render_total",
          "Completed disconnected LiveView renders"
        ),
        Vector("outcome" -> "failed", "failure" -> "stage", "stage" -> "render")
      )
      val mount = tagged(
        Metric.counter(
          "scalive_mount_total",
          "Completed disconnected and connected LiveView mounts"
        ),
        Vector(
          "outcome" -> "succeeded",
          "phase"   -> "connected",
          "failure" -> "none",
          "stage"   -> "none"
        )
      )
      val handlerLabels = Vector(
        "kind" -> "async_completion",
        "failure" -> "stage",
        "stage" -> "handler"
      )
      val handler = tagged(
        Metric.counter("scalive_handler_failures_total", "LiveView handler failures"),
        handlerLabels
      )
      val handlerDuration = tagged(
        Metric.histogram(
          "scalive_handler_failure_duration_seconds",
          "Failed LiveView handler duration in seconds",
          durationBoundaries
        ),
        handlerLabels
      )
      val subscription = tagged(
        Metric.counter(
          "scalive_subscription_failures_total",
          "Managed subscription failures"
        ),
        Vector(
          "delivery" -> "latest",
          "failure"  -> "subscription_defect",
          "stage"    -> "none"
        )
      )
      val lifecycle = ConnectedLifecycleContext(301L, 302L, 303L)

      for
        renderBefore       <- render.value
        mountBefore        <- mount.value
        handlerBefore      <- handler.value
        handlerDurationBefore <- handlerDuration.value
        subscriptionBefore <- subscription.value
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.DisconnectedRenderFailed(
                 lifecycleId = 304L,
                 durationNanos = 1L,
                 LifecycleError(LifecycleFailure.Stage(LifecycleFailureStage.Render))
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.MountSucceeded(LifecycleMount.Connected(lifecycle), 2L)
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.HandlerFailed(
                 lifecycle,
                 commandId = Some(305L),
                 LifecycleTurnKind.AsyncCompletion,
                 durationNanos = 3L,
                 LifecycleError(LifecycleFailure.Stage(LifecycleFailureStage.Handler))
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.SubscriptionFailed(
                 lifecycle,
                 resourceId = 306L,
                 SubscriptionDelivery.Latest,
                 LifecycleError(LifecycleFailure.SubscriptionDefect)
               )
             )
        renderAfter       <- render.value
        mountAfter        <- mount.value
        handlerAfter      <- handler.value
        handlerDurationAfter <- handlerDuration.value
        subscriptionAfter <- subscription.value
      yield assertTrue(
        renderAfter.count == renderBefore.count + 1.0,
        mountAfter.count == mountBefore.count + 1.0,
        handlerAfter.count == handlerBefore.count + 1.0,
        handlerDurationAfter.count == handlerDurationBefore.count + 1L,
        subscriptionAfter.count == subscriptionBefore.count + 1.0
      )
    },
    test("records queue depth samples and counts only saturation") {
      val sampledDepth = tagged(
        Metric.histogram(
          "scalive_queue_depth",
          "Observed bounded runtime queue depth",
          queueDepthBoundaries
        ),
        Vector("queue" -> "writer", "status" -> "sampled")
      )
      val saturatedDepth = tagged(
        Metric.histogram(
          "scalive_queue_depth",
          "Observed bounded runtime queue depth",
          queueDepthBoundaries
        ),
        Vector("queue" -> "connection_pending_commands", "status" -> "saturated")
      )
      val sampledSaturation = tagged(
        Metric.counter(
          "scalive_queue_saturation_total",
          "Rejected offers to bounded runtime queues"
        ),
        Vector("queue" -> "writer")
      )
      val saturated = tagged(
        Metric.counter(
          "scalive_queue_saturation_total",
          "Rejected offers to bounded runtime queues"
        ),
        Vector("queue" -> "connection_pending_commands")
      )
      val lifecycle = ConnectedLifecycleContext(401L, 402L, 403L)

      for
        sampledDepthBefore      <- sampledDepth.value
        saturatedDepthBefore    <- saturatedDepth.value
        sampledSaturationBefore <- sampledSaturation.value
        saturatedBefore         <- saturated.value
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.QueuePressure(
                 lifecycle,
                 LifecycleQueue.Writer,
                 depth = 4,
                 capacity = 8,
                 LifecycleQueueStatus.Sampled
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.QueuePressure(
                 lifecycle,
                 LifecycleQueue.ConnectionPendingCommands,
                 depth = 8,
                 capacity = 8,
                 LifecycleQueueStatus.Saturated
               )
             )
        sampledDepthAfter      <- sampledDepth.value
        saturatedDepthAfter    <- saturatedDepth.value
        sampledSaturationAfter <- sampledSaturation.value
        saturatedAfter         <- saturated.value
      yield assertTrue(
        sampledDepthAfter.count == sampledDepthBefore.count + 1L,
        sampledDepthAfter.sum == sampledDepthBefore.sum + 4.0,
        saturatedDepthAfter.count == saturatedDepthBefore.count + 1L,
        saturatedDepthAfter.sum == saturatedDepthBefore.sum + 8.0,
        sampledSaturationAfter.count == sampledSaturationBefore.count,
        saturatedAfter.count == saturatedBefore.count + 1.0
      )
    },
    test("collapses disconnect details and preserves typed terminal failures") {
      val disconnected = tagged(
        Metric.counter(
          "scalive_lifecycle_terminations_total",
          "Completed connected LiveView lifecycles"
        ),
        Vector("reason" -> "disconnected", "failure" -> "none", "stage" -> "none")
      )
      val failed = tagged(
        Metric.counter(
          "scalive_lifecycle_terminations_total",
          "Completed connected LiveView lifecycles"
        ),
        Vector("reason" -> "failed", "failure" -> "stage", "stage" -> "writer")
      )
      val lifecycle = ConnectedLifecycleContext(501L, 502L, 503L)

      for
        disconnectedBefore <- disconnected.value
        failedBefore       <- failed.value
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.LifecycleTerminated(
                 lifecycle,
                 LifecycleTerminationReason.Disconnected(Some("high-cardinality secret"))
               )
             )
        _ <- LifecycleMetrics.observer.observe(
               LifecycleEvent.LifecycleTerminated(
                 lifecycle,
                 LifecycleTerminationReason.Failed(
                   LifecycleFailure.Stage(LifecycleFailureStage.Writer)
                 )
               )
             )
        disconnectedAfter <- disconnected.value
        failedAfter       <- failed.value
      yield assertTrue(
        disconnectedAfter.count == disconnectedBefore.count + 1.0,
        failedAfter.count == failedBefore.count + 1.0
      )
    }
  ) @@ TestAspect.sequential

  private def tagged[Type, In, Out](
    metric: Metric[Type, In, Out],
    labels: Vector[(String, String)]
  ): Metric[Type, In, Out] =
    labels.foldLeft(metric) { case (current, (key, value)) => current.tagged(key, value) }
end LifecycleMetricsSpec
