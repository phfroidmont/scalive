package scalive

import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram.Boundaries
import zio.{FiberRef, UIO}

/** Built-in ZIO Metrics instrumentation for [[LifecycleEvent]].
  *
  * The adapter records only stable, low-cardinality classifications. Runtime identifiers, exception
  * details, reconnect counters, and disconnect reason strings are deliberately excluded from metric
  * labels.
  */
object LifecycleMetrics:
  private val durationBoundaries = Boundaries.exponential(
    start = 0.0005,
    factor = 2.0,
    count = 18
  )
  private val queueDepthBoundaries = Boundaries.exponential(
    start = 1.0,
    factor = 2.0,
    count = 16
  )

  private val disconnectedRenderTotal = Metric.counter(
    "scalive_disconnected_render_total",
    "Completed disconnected LiveView renders"
  )
  private val disconnectedRenderDuration = Metric.histogram(
    "scalive_disconnected_render_duration_seconds",
    "Disconnected LiveView render duration in seconds",
    durationBoundaries
  )
  private val joinTotal = Metric.counter(
    "scalive_join_total",
    "Completed root and nested LiveView join attempts"
  )
  private val joinDuration = Metric.histogram(
    "scalive_join_duration_seconds",
    "LiveView join duration in seconds",
    durationBoundaries
  )
  private val mountTotal = Metric.counter(
    "scalive_mount_total",
    "Completed disconnected and connected LiveView mounts"
  )
  private val mountDuration = Metric.histogram(
    "scalive_mount_duration_seconds",
    "LiveView mount duration in seconds",
    durationBoundaries
  )
  private val turnTotal = Metric.counter(
    "scalive_turn_total",
    "Completed connected LiveView turns"
  )
  private val turnDuration = Metric.histogram(
    "scalive_turn_duration_seconds",
    "Connected LiveView turn duration in seconds",
    durationBoundaries
  )
  private val handlerFailures = Metric.counter(
    "scalive_handler_failures_total",
    "LiveView handler failures"
  )
  private val handlerFailureDuration = Metric.histogram(
    "scalive_handler_failure_duration_seconds",
    "Failed LiveView handler duration in seconds",
    durationBoundaries
  )
  private val subscriptionFailures = Metric.counter(
    "scalive_subscription_failures_total",
    "Managed subscription failures"
  )
  private val queueDepth = Metric.histogram(
    "scalive_queue_depth",
    "Observed bounded runtime queue depth",
    queueDepthBoundaries
  )
  private val queueSaturation = Metric.counter(
    "scalive_queue_saturation_total",
    "Rejected offers to bounded runtime queues"
  )
  private val lifecycleTerminations = Metric.counter(
    "scalive_lifecycle_terminations_total",
    "Completed connected LiveView lifecycles"
  )

  /** Records every lifecycle event in ZIO's metric registry. */
  val observer: LifecycleObserver =
    LifecycleObserver.fromFunction(event => FiberRef.currentTags.locally(Set.empty)(record(event)))

  private def record(event: LifecycleEvent): UIO[Unit] = event match
    case event: LifecycleEvent.DisconnectedRenderSucceeded =>
      recordTimed(
        disconnectedRenderTotal,
        disconnectedRenderDuration,
        event.durationNanos,
        outcomeLabels("succeeded")
      )
    case event: LifecycleEvent.DisconnectedRenderFailed =>
      recordTimed(
        disconnectedRenderTotal,
        disconnectedRenderDuration,
        event.durationNanos,
        outcomeLabels("failed", Some(event.error.failure))
      )
    case event: LifecycleEvent.JoinSucceeded =>
      recordTimed(
        joinTotal,
        joinDuration,
        event.durationNanos,
        joinLabels("succeeded", event.target, event.attempt, None)
      )
    case event: LifecycleEvent.JoinRejected =>
      recordTimed(
        joinTotal,
        joinDuration,
        event.durationNanos,
        joinLabels("rejected", event.target, event.attempt, Some(event.error.failure))
      )
    case event: LifecycleEvent.MountSucceeded =>
      recordTimed(
        mountTotal,
        mountDuration,
        event.durationNanos,
        mountLabels("succeeded", event.mount, None)
      )
    case event: LifecycleEvent.MountFailed =>
      recordTimed(
        mountTotal,
        mountDuration,
        event.durationNanos,
        mountLabels("failed", event.mount, Some(event.error.failure))
      )
    case event: LifecycleEvent.TurnSucceeded =>
      recordTimed(
        turnTotal,
        turnDuration,
        event.durationNanos,
        turnLabels("succeeded", event.kind, None)
      )
    case event: LifecycleEvent.TurnFailed =>
      recordTimed(
        turnTotal,
        turnDuration,
        event.durationNanos,
        turnLabels("failed", event.kind, Some(event.error.failure))
      )
    case event: LifecycleEvent.HandlerFailed =>
      val failure = failureLabels(Some(event.error.failure))
      recordTimed(
        handlerFailures,
        handlerFailureDuration,
        event.durationNanos,
        Vector("kind" -> label(event.kind)) ++ failure
      )
    case event: LifecycleEvent.SubscriptionFailed =>
      val labels = Vector("delivery" -> label(event.delivery)) ++
        failureLabels(Some(event.error.failure))
      tagged(subscriptionFailures, labels).update(1L)
    case event: LifecycleEvent.QueuePressure =>
      val depthLabels = Vector(
        "queue"  -> label(event.queue),
        "status" -> label(event.status)
      )
      tagged(queueDepth, depthLabels).update(event.depth.toDouble) *>
        tagged(queueSaturation, Vector("queue" -> label(event.queue)))
          .update(1L).when(event.status == LifecycleQueueStatus.Saturated).unit
    case event: LifecycleEvent.LifecycleTerminated =>
      tagged(lifecycleTerminations, terminationLabels(event.reason)).update(1L)

  private def recordTimed(
    total: Metric.Counter[Long],
    duration: Metric.Histogram[Double],
    durationNanos: Long,
    labels: Vector[(String, String)]
  ): UIO[Unit] =
    tagged(total, labels).update(1L) *>
      tagged(duration, labels).update(math.max(0L, durationNanos).toDouble / 1_000_000_000.0)

  private def tagged[Type, In, Out](
    metric: Metric[Type, In, Out],
    labels: Vector[(String, String)]
  ): Metric[Type, In, Out] =
    labels.foldLeft(metric) { case (current, (key, value)) => current.tagged(key, value) }

  private def outcomeLabels(
    outcome: String,
    failure: Option[LifecycleFailure] = None
  ): Vector[(String, String)] =
    Vector("outcome" -> outcome) ++ failureLabels(failure)

  private def joinLabels(
    outcome: String,
    target: LifecycleJoinTarget,
    attempt: LifecycleJoinAttempt,
    failure: Option[LifecycleFailure]
  ): Vector[(String, String)] =
    Vector(
      "outcome"   -> outcome,
      "target"    -> label(target),
      "reconnect" -> attempt.isReconnect.toString,
      "retry"     -> attempt.isRetry.toString
    ) ++ failureLabels(failure)

  private def mountLabels(
    outcome: String,
    mount: LifecycleMount,
    failure: Option[LifecycleFailure]
  ): Vector[(String, String)] =
    Vector(
      "outcome" -> outcome,
      "phase"   -> (mount match
        case _: LifecycleMount.Disconnected => "disconnected"
        case _: LifecycleMount.Connected    => "connected")
    ) ++ failureLabels(failure)

  private def turnLabels(
    outcome: String,
    kind: LifecycleTurnKind,
    failure: Option[LifecycleFailure]
  ): Vector[(String, String)] =
    Vector("outcome" -> outcome, "kind" -> label(kind)) ++ failureLabels(failure)

  private def terminationLabels(
    reason: LifecycleTerminationReason
  ): Vector[(String, String)] = reason match
    case LifecycleTerminationReason.Closed =>
      Vector("reason" -> "closed") ++ failureLabels(None)
    case LifecycleTerminationReason.Redirected =>
      Vector("reason" -> "redirected") ++ failureLabels(None)
    case LifecycleTerminationReason.Disconnected(_) =>
      Vector("reason" -> "disconnected") ++ failureLabels(None)
    case LifecycleTerminationReason.Failed(failure) =>
      Vector("reason" -> "failed") ++ failureLabels(Some(failure))

  private def failureLabels(
    failure: Option[LifecycleFailure]
  ): Vector[(String, String)] = failure match
    case None                                => Vector("failure" -> "none", "stage" -> "none")
    case Some(LifecycleFailure.Stage(stage)) =>
      Vector("failure" -> "stage", "stage" -> label(stage))
    case Some(value) => Vector("failure" -> label(value), "stage" -> "none")

  private def label(value: Product): String =
    value.productPrefix.zipWithIndex.flatMap { case (character, index) =>
      if character.isUpper && index > 0 then s"_${character.toLower}"
      else character.toLower.toString
    }.mkString
end LifecycleMetrics
