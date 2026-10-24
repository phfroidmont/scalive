package scalive.runtime.kernel

import zio.LogAnnotation

import scalive.LifecycleFailure
import scalive.LifecycleMetrics

/** Log annotation keys shared by Scalive's log entries.
  *
  * Messages stay constant; identifiers and values from the browser or the application go in
  * annotations so structured loggers keep them separate. Labels match those of
  * [[scalive.LifecycleMetrics]].
  *
  * Level policy: `ERROR` for a failure that stops a lifecycle or request, `WARN` for a contained
  * failure or degraded operation, `INFO` for an outcome the application requested, and `DEBUG` for
  * anything a browser can trigger.
  */
private[scalive] object RuntimeLog:
  val ConnectionId = "connection_id"
  val LifecycleId  = "lifecycle_id"
  val Topic        = "topic"
  val View         = "view"
  val Route        = "route"
  val Stage        = "stage"
  val Failure      = "failure"
  val TurnKind     = "turn_kind"
  val TurnId       = "turn_id"
  val CommandId    = "command_id"
  val Action       = "action"
  val Reason       = "reason"
  val Event        = "event"
  val AsyncTask    = "async_task"
  val Subscription = "subscription"
  val Upload       = "upload"
  val UploadEntry  = "upload_entry"
  val Operation    = "operation"
  val RuntimeEvent = "runtime_event"
  val Epoch        = "epoch"
  val Component    = "component_id"
  val Cid          = "cid"

  def label(value: Product): String = LifecycleMetrics.label(value)

  def failure(value: LifecycleFailure): LogAnnotation = value match
    case LifecycleFailure.Stage(stage) => LogAnnotation(Stage, label(stage))
    case other                         => LogAnnotation(Failure, label(other))
