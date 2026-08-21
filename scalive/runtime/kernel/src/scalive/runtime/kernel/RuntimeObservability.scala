package scalive.runtime.kernel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json

import scalive.runtime.contracts.*

final private[scalive] case class RuntimeCorrelation(
  connection: ConnectionId,
  lifecycle: LifecycleId,
  epoch: Epoch,
  command: Option[CommandId] = None,
  turn: Option[TurnId] = None,
  revision: Option[TurnRevision] = None,
  navigation: Option[NavigationId] = None,
  resource: Option[ResourceId] = None)

private[scalive] enum RuntimeCommandKind:
  case ClientEvent
  case ComponentClientEvent
  case Message
  case AsyncCompletion
  case ManagedAsync
  case ManagedSubscription
  case ManagedSubscriptionEnded
  case ComponentMessage
  case ComponentUpdate
  case ComponentAsyncCompletion
  case Upload
  case ParamsPatch
  case Internal

private[scalive] enum RuntimeInitiator:
  case Browser, Application, ManagedResource, Runtime

private[scalive] enum RuntimeFailure:
  case Stage(stage: SessionStage)
  case CommitDefect
  case NavigationTimeout
  case NavigationRedirectOverflow
  case NavigationDeferredOverflow
  case MailboxSaturated
  case IngressSaturated
  case Writer
  case UploadEntry
  case Protocol
  case Cleanup
  case RuntimeDefect

private[scalive] enum RuntimeTerminal:
  case Crashed, Closed, Redirected

private[scalive] enum RuntimeEvent:
  case CommandAccepted(
    correlation: RuntimeCorrelation,
    kind: RuntimeCommandKind,
    initiator: RuntimeInitiator,
    queueDepth: Int)
  case TurnStarted(
    correlation: RuntimeCorrelation,
    kind: RuntimeCommandKind,
    initiator: RuntimeInitiator)
  case HandlerCompleted(correlation: RuntimeCorrelation, durationNanos: Long)
  case CandidateRenderStarted(correlation: RuntimeCorrelation)
  case CandidateValidated(correlation: RuntimeCorrelation)
  case DiffCompleted(correlation: RuntimeCorrelation)
  case StateCommitted(correlation: RuntimeCorrelation)
  case OutputPublished(correlation: RuntimeCorrelation)
  case ResourceActivated(correlation: RuntimeCorrelation)
  case ResourceRetired(correlation: RuntimeCorrelation)
  case TurnFailed(correlation: RuntimeCorrelation, failure: RuntimeFailure)
  case SessionTerminated(correlation: RuntimeCorrelation, terminal: RuntimeTerminal)

  def context: RuntimeCorrelation = this match
    case CommandAccepted(value, _, _, _) => value
    case TurnStarted(value, _, _)        => value
    case HandlerCompleted(value, _)      => value
    case CandidateRenderStarted(value)   => value
    case CandidateValidated(value)       => value
    case DiffCompleted(value)            => value
    case StateCommitted(value)           => value
    case OutputPublished(value)          => value
    case ResourceActivated(value)        => value
    case ResourceRetired(value)          => value
    case TurnFailed(value, _)            => value
    case SessionTerminated(value, _)     => value

  def name: String = this match
    case _: CommandAccepted        => "command_accepted"
    case _: TurnStarted            => "turn_started"
    case _: HandlerCompleted       => "handler_completed"
    case _: CandidateRenderStarted => "candidate_render_started"
    case _: CandidateValidated     => "candidate_validated"
    case _: DiffCompleted          => "diff_completed"
    case _: StateCommitted         => "state_committed"
    case _: OutputPublished        => "output_published"
    case _: ResourceActivated      => "resource_activated"
    case _: ResourceRetired        => "resource_retired"
    case _: TurnFailed             => "turn_failed"
    case _: SessionTerminated      => "session_terminated"
end RuntimeEvent

final private[scalive] case class RuntimeTraceValue(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)] = Vector.empty,
  scalaValue: Option[String] = None)

private[scalive] object RuntimeTraceValue:
  def redacted(value: Any): RuntimeTraceValue =
    val typeName = if value == null then "null" else value.getClass.getName
    RuntimeTraceValue(typeName, "Content redacted")

private[scalive] enum RuntimeTraceOperationKind:
  case Join, ClientEvent, ServerMessage, AsyncCompletion, LivePatch, Upload, Leave, Other

private[scalive] enum RuntimeTraceInitiator:
  case Browser
  case Runtime
  case Component(typeName: String, id: String)

private[scalive] enum RuntimeTraceStage:
  case SocketJoin
  case DecodedEvent
  case BindingResolution
  case TypedMessage
  case LifecycleStarted
  case LifecycleCompleted
  case ModelProposed
  case RenderStarted
  case ModelRendered
  case RenderCompleted
  case TreeDiff
  case ModelCommitted
  case FinalPayload
  case FinalFrame
  case Crash
  case Upload

final private[scalive] case class RuntimeTraceIdentity(
  traceSession: String,
  connectionEpoch: Long,
  socketEpoch: Long,
  topic: String,
  joinReference: Option[String],
  messageReference: Option[String],
  operationSequence: Long,
  operationKind: RuntimeTraceOperationKind,
  initiator: RuntimeTraceInitiator)

final private[scalive] case class RuntimeTraceRecord(
  identity: RuntimeTraceIdentity,
  recordSequence: Long,
  stage: RuntimeTraceStage,
  summary: String,
  value: Option[RuntimeTraceValue] = None,
  protocol: Option[Json] = None,
  byteSize: Option[Int] = None)

sealed private[scalive] trait RuntimeDiagnostic:
  def session: Option[String]
  def isObserved(topic: String): Boolean
  def begin(
    topic: String,
    joinReference: Option[String],
    messageReference: Option[String],
    kind: RuntimeTraceOperationKind,
    initiator: RuntimeTraceInitiator
  ): RuntimeTraceOperation

private[scalive] object RuntimeDiagnostic:
  case object Disabled extends RuntimeDiagnostic:
    val session = None

    def isObserved(topic: String): Boolean = false

    def begin(
      topic: String,
      joinReference: Option[String],
      messageReference: Option[String],
      kind: RuntimeTraceOperationKind,
      initiator: RuntimeTraceInitiator
    ): RuntimeTraceOperation = RuntimeTraceOperation.Disabled

  abstract class Enabled(
    val traceSession: String,
    val connectionEpoch: Long)
      extends RuntimeDiagnostic:

    final val session = Some(traceSession)

    final private class TopicState:
      private val socketEpoch       = AtomicLong(1L)
      private val operationSequence = AtomicLong(0L)

      def begin(kind: RuntimeTraceOperationKind): (Long, Long) =
        val epoch = kind match
          case RuntimeTraceOperationKind.Join => socketEpoch.incrementAndGet()
          case _                              => socketEpoch.get()
        epoch -> operationSequence.incrementAndGet()

    private val topics = ConcurrentHashMap[String, TopicState]()

    def isObserved(topic: String): Boolean
    def projectMessage(topic: String, value: Any): RuntimeTraceValue
    def projectModel(topic: String, value: Any): RuntimeTraceValue
    def publish(record: RuntimeTraceRecord): UIO[Unit]

    final def begin(
      topic: String,
      joinReference: Option[String],
      messageReference: Option[String],
      kind: RuntimeTraceOperationKind,
      initiator: RuntimeTraceInitiator
    ): RuntimeTraceOperation =
      val observed =
        try isObserved(topic)
        catch case _: Throwable => false

      if !observed then RuntimeTraceOperation.Disabled
      else
        val state                    = topics.computeIfAbsent(topic, _ => TopicState())
        val (socketEpoch, operation) = state.begin(kind)
        RuntimeTraceOperation.Active(
          this,
          RuntimeTraceIdentity(
            traceSession,
            connectionEpoch,
            socketEpoch,
            topic,
            joinReference,
            messageReference,
            operation,
            kind,
            initiator
          )
        )
  end Enabled
end RuntimeDiagnostic

sealed private[scalive] trait RuntimeTraceOperation

private[scalive] object RuntimeTraceOperation:
  case object Disabled extends RuntimeTraceOperation

  final case class Active(
    diagnostic: RuntimeDiagnostic.Enabled,
    identity: RuntimeTraceIdentity)
      extends RuntimeTraceOperation:
    private val nextRecordSequence = AtomicLong(0L)

    def event(stage: RuntimeTraceStage, summary: String): UIO[Unit] =
      publish(RuntimeTraceRecord(identity, nextRecordSequence.incrementAndGet(), stage, summary))

    def message(stage: RuntimeTraceStage, summary: String, value: Any): UIO[Unit] =
      project(value, diagnostic.projectMessage(identity.topic, _)).flatMap(projected =>
        publish(
          RuntimeTraceRecord(
            identity,
            nextRecordSequence.incrementAndGet(),
            stage,
            summary,
            value = Some(projected)
          )
        )
      )

    def model(stage: RuntimeTraceStage, summary: String, value: Any): UIO[Unit] =
      project(value, diagnostic.projectModel(identity.topic, _)).flatMap(projected =>
        publish(
          RuntimeTraceRecord(
            identity,
            nextRecordSequence.incrementAndGet(),
            stage,
            summary,
            value = Some(projected)
          )
        )
      )

    def protocol(
      stage: RuntimeTraceStage,
      summary: String,
      value: Json,
      bytes: Option[Int]
    ): UIO[Unit] =
      publish(
        RuntimeTraceRecord(
          identity,
          nextRecordSequence.incrementAndGet(),
          stage,
          summary,
          protocol = Some(value),
          byteSize = bytes
        )
      )

    private def project(
      value: Any,
      projector: Any => RuntimeTraceValue
    ): UIO[RuntimeTraceValue] =
      ZIO.attempt(projector(value)).orElseSucceed(RuntimeTraceValue.redacted(value))

    private def publish(record: RuntimeTraceRecord): UIO[Unit] =
      diagnostic.publish(record).catchAllCause(_ => ZIO.unit)
  end Active
end RuntimeTraceOperation

final private[scalive] case class RuntimeFrameTrace(
  operation: RuntimeTraceOperation.Active,
  command: Option[CommandId])

private[scalive] trait RuntimeEventSink:
  def emit(event: RuntimeEvent): UIO[Unit]

final private[scalive] class RuntimeObserver private (
  sink: RuntimeEventSink,
  diagnostic: RuntimeDiagnostic):

  final private case class LifecycleTrace(
    topic: String,
    modelValue: Any => Any)

  final private case class TraceCoordinates(
    lifecycle: LifecycleId,
    topic: String,
    joinReference: Option[String],
    messageReference: Option[String])

  final private case class PendingFrame(
    lifecycle: LifecycleId,
    command: Option[CommandId],
    operation: RuntimeTraceOperation.Active,
    transportBound: Boolean)

  private val diagnosticsEnabled   = diagnostic.session.nonEmpty
  private lazy val lifecycles      = ConcurrentHashMap[LifecycleId, LifecycleTrace]()
  private lazy val coordinates     = ConcurrentHashMap[CommandId, TraceCoordinates]()
  private lazy val commands        = ConcurrentHashMap[CommandId, RuntimeTraceOperation.Active]()
  private lazy val commandOwners   = ConcurrentHashMap[CommandId, LifecycleId]()
  private lazy val turns           = ConcurrentHashMap[TurnId, RuntimeTraceOperation.Active]()
  private lazy val turnOwners      = ConcurrentHashMap[TurnId, LifecycleId]()
  private lazy val pendingInternal =
    ConcurrentHashMap[LifecycleId, RuntimeTraceOperation.Active]()
  private lazy val frameGate     = Object()
  private lazy val pendingFrames = java.util.IdentityHashMap[AnyRef, PendingFrame]()

  def emit(event: RuntimeEvent): UIO[Unit] =
    sink.emit(event).catchAllCause(_ => ZIO.unit) *>
      (if diagnosticsEnabled then traceEvent(event) else ZIO.unit)

  def registerLifecycle(
    lifecycle: LifecycleId,
    topic: String,
    modelValue: Any => Any
  ): Unit =
    if diagnosticsEnabled then
      val _ = lifecycles.put(lifecycle, LifecycleTrace(topic, modelValue))

  def unregisterLifecycle(lifecycle: LifecycleId): Unit =
    if diagnosticsEnabled then
      val _ = lifecycles.remove(lifecycle)
      val _ = pendingInternal.remove(lifecycle)
      coordinates
        .entrySet().asScala
        .filter(_.getValue.lifecycle == lifecycle).foreach(entry =>
          coordinates.remove(entry.getKey)
        )
      commandOwners
        .entrySet().asScala
        .filter(_.getValue == lifecycle).foreach(entry => cancel(entry.getKey))
      turnOwners
        .entrySet().asScala
        .filter(_.getValue == lifecycle).foreach { entry =>
          val _ = turns.remove(entry.getKey)
          val _ = turnOwners.remove(entry.getKey)
        }
      frameGate.synchronized {
        val _ = pendingFrames
          .values().removeIf(frame => frame.lifecycle == lifecycle && !frame.transportBound)
      }

  def correlate(
    command: CommandId,
    lifecycle: LifecycleId,
    topic: String,
    joinReference: Option[String],
    messageReference: Option[String]
  ): UIO[Unit] =
    if !diagnosticsEnabled || !observes(topic) then ZIO.unit
    else
      ZIO.succeed {
        val _ = coordinates.put(
          command,
          TraceCoordinates(lifecycle, topic, joinReference, messageReference)
        )
      }

  def cancel(command: CommandId): Unit =
    if diagnosticsEnabled then
      val _        = coordinates.remove(command)
      val removed  = commands.remove(command)
      val _        = commandOwners.remove(command)
      val prepared = frameGate.synchronized {
        val operations = pendingFrames
          .values().asScala
          .filter(frame =>
            !frame.transportBound &&
              (frame.command.contains(command) || (frame.operation eq removed))
          )
          .map(_.operation).toVector
        pendingFrames
          .values().removeIf(frame =>
            !frame.transportBound &&
              (frame.command.contains(command) || (frame.operation eq removed))
          )
        operations
      }
      val operations = Option(removed).toVector ++ prepared
      if operations.nonEmpty then
        turns
          .entrySet().asScala
          .filter(entry => operations.exists(_ eq entry.getValue)).foreach { entry =>
            val _ = turns.remove(entry.getKey)
            val _ = turnOwners.remove(entry.getKey)
          }

  def beginInternal(
    lifecycle: LifecycleId,
    kind: RuntimeTraceOperationKind,
    initiator: RuntimeTraceInitiator,
    message: Option[Any]
  ): UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      Option(lifecycles.get(lifecycle)) match
        case None          => ZIO.unit
        case Some(context) =>
          diagnostic.begin(context.topic, None, None, kind, initiator) match
            case RuntimeTraceOperation.Disabled          => ZIO.unit
            case operation: RuntimeTraceOperation.Active =>
              ZIO.succeed(pendingInternal.put(lifecycle, operation)) *>
                ZIO.foreachDiscard(message)(value =>
                  operation.message(RuntimeTraceStage.TypedMessage, "Typed message resolved", value)
                )

  def message(correlation: => RuntimeCorrelation, value: => Any): UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      val current = correlation
      withOperation(current)(
        _.message(RuntimeTraceStage.TypedMessage, "Typed message resolved", value)
      )

  def model(correlation: => RuntimeCorrelation, value: => Any): UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      val current = correlation
      Option(lifecycles.get(current.lifecycle)) match
        case None          => ZIO.unit
        case Some(context) =>
          withOperation(current)(
            _.model(
              RuntimeTraceStage.ModelProposed,
              "Handler proposed a model",
              context.modelValue(value)
            )
          )

  def diff(correlation: => RuntimeCorrelation, changed: => Boolean): UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      withOperation(correlation)(
        _.event(
          RuntimeTraceStage.TreeDiff,
          if changed then "Tree diff contains changes" else "Tree diff is empty"
        )
      )

  def frame(
    trace: RuntimeFrameTrace,
    protocol: Json,
    byteSize: Int
  ): UIO[Unit] =
    trace.operation
      .protocol(
        RuntimeTraceStage.FinalFrame,
        "Final protocol frame sent",
        protocol,
        Some(byteSize)
      ).ensuring(ZIO.succeed(trace.command.foreach(cancel)))

  def frameFailed(trace: RuntimeFrameTrace): UIO[Unit] =
    trace.operation
      .event(RuntimeTraceStage.Crash, "Final protocol frame failed")
      .ensuring(ZIO.succeed(trace.command.foreach(cancel)))

  def bindOutput(source: AnyRef, target: AnyRef): Unit =
    if diagnosticsEnabled then
      frameGate.synchronized {
        Option(pendingFrames.remove(source)).foreach(frame => pendingFrames.put(target, frame))
      }

  def bindFrame(source: AnyRef, target: AnyRef): Unit =
    if diagnosticsEnabled then
      frameGate.synchronized {
        Option(pendingFrames.remove(source)).foreach(frame =>
          pendingFrames.put(target, frame.copy(transportBound = true))
        )
      }

  def takeOutput(output: AnyRef): Option[RuntimeFrameTrace] =
    if !diagnosticsEnabled then None
    else
      frameGate.synchronized {
        Option(pendingFrames.remove(output)).map(frame =>
          RuntimeFrameTrace(frame.operation, frame.command)
        )
      }

  def failOutput(output: AnyRef): UIO[Unit] =
    takeOutput(output).fold[UIO[Unit]](ZIO.unit)(frameFailed)

  def failTransportFrames: UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      val traces = frameGate.synchronized {
        val frames = pendingFrames
          .entrySet().asScala
          .filter(_.getValue.transportBound).map(_.getValue).toVector
        val _ = pendingFrames.entrySet().removeIf(_.getValue.transportBound)
        frames.map(frame => RuntimeFrameTrace(frame.operation, frame.command))
      }
      ZIO.foreachDiscard(traces)(frameFailed)

  def reject(correlation: => RuntimeCorrelation, summary: => String): UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      val current = correlation
      withOperation(current)(_.event(RuntimeTraceStage.Crash, summary))
        .ensuring(finish(current))

  def prepareOutput(correlation: => RuntimeCorrelation, output: => AnyRef): UIO[Unit] =
    if !diagnosticsEnabled then ZIO.unit
    else
      val current = correlation
      val marker  = output
      withOperation(current)(operation =>
        operation.event(RuntimeTraceStage.FinalPayload, "Final payload produced") *>
          ZIO.succeed {
            frameGate.synchronized {
              pendingFrames.put(
                marker,
                PendingFrame(current.lifecycle, current.command, operation, transportBound = false)
              )
            }
          }.unit
      )

  private def traceEvent(event: RuntimeEvent): UIO[Unit] =
    event match
      case RuntimeEvent.CommandAccepted(correlation, kind, initiator, _) =>
        ensureCommand(correlation, kind, initiator)
      case RuntimeEvent.TurnStarted(correlation, _, _) =>
        bindTurn(correlation) *>
          withOperation(correlation)(
            _.event(RuntimeTraceStage.LifecycleStarted, "Lifecycle handler started")
          )
      case RuntimeEvent.HandlerCompleted(correlation, _) =>
        withOperation(correlation)(
          _.event(RuntimeTraceStage.LifecycleCompleted, "Lifecycle handler completed")
        )
      case RuntimeEvent.CandidateRenderStarted(correlation) =>
        withOperation(correlation)(_.event(RuntimeTraceStage.RenderStarted, "Render started"))
      case RuntimeEvent.CandidateValidated(correlation) =>
        withOperation(correlation)(operation =>
          operation.event(RuntimeTraceStage.ModelRendered, "Model rendered") *>
            operation.event(RuntimeTraceStage.RenderCompleted, "Render completed")
        )
      case RuntimeEvent.StateCommitted(correlation) =>
        withOperation(correlation)(_.event(RuntimeTraceStage.ModelCommitted, "Model committed"))
      case RuntimeEvent.OutputPublished(correlation)     => finishTurn(correlation)
      case RuntimeEvent.TurnFailed(correlation, failure) =>
        withOperation(correlation)(
          _.event(RuntimeTraceStage.Crash, s"Runtime operation failed at $failure")
        ).ensuring(finish(correlation))
      case RuntimeEvent.SessionTerminated(correlation, terminal) =>
        traceTermination(correlation, terminal)
      case _ => ZIO.unit

  private def ensureCommand(
    correlation: RuntimeCorrelation,
    kind: RuntimeCommandKind,
    initiator: RuntimeInitiator
  ): UIO[Unit] =
    correlation.command match
      case None                                           => ZIO.unit
      case Some(command) if commands.containsKey(command) => ZIO.unit
      case Some(command)                                  =>
        val coordinatesForCommand = Option(coordinates.remove(command))
        val lifecycle             = coordinatesForCommand
          .map(_.lifecycle).getOrElse(correlation.lifecycle)
        val context = Option(lifecycles.get(lifecycle)).map { registered =>
          coordinatesForCommand.fold(registered)(value => registered.copy(topic = value.topic))
        }
        context match
          case None          => ZIO.unit
          case Some(context) =>
            diagnostic.begin(
              context.topic,
              coordinatesForCommand.flatMap(_.joinReference),
              coordinatesForCommand.flatMap(_.messageReference),
              operationKind(kind),
              traceInitiator(initiator)
            ) match
              case RuntimeTraceOperation.Disabled          => ZIO.unit
              case operation: RuntimeTraceOperation.Active =>
                ZIO.succeed {
                  val _ = commands.put(command, operation)
                  val _ = commandOwners.put(command, lifecycle)
                }

  private def bindTurn(correlation: RuntimeCorrelation): UIO[Unit] =
    ZIO.succeed {
      correlation.turn.foreach { turn =>
        val operation = correlation.command
          .flatMap(command => Option(commands.get(command)))
          .orElse(Option(pendingInternal.get(correlation.lifecycle)))
        operation.foreach { value =>
          val _ = turns.put(turn, value)
          val _ = turnOwners.put(turn, correlation.lifecycle)
        }
      }
    }

  private def traceTermination(
    correlation: RuntimeCorrelation,
    terminal: RuntimeTerminal
  ): UIO[Unit] = terminal match
    case RuntimeTerminal.Crashed =>
      withOperation(correlation)(
        _.event(RuntimeTraceStage.Crash, "Runtime session crashed")
      ).ensuring(finish(correlation))
    case RuntimeTerminal.Redirected => ZIO.unit
    case RuntimeTerminal.Closed     => traceLeave(correlation)

  private def traceLeave(correlation: RuntimeCorrelation): UIO[Unit] =
    Option(lifecycles.get(correlation.lifecycle)) match
      case None          => ZIO.unit
      case Some(context) =>
        diagnostic.begin(
          context.topic,
          None,
          None,
          RuntimeTraceOperationKind.Leave,
          RuntimeTraceInitiator.Browser
        ) match
          case RuntimeTraceOperation.Disabled          => ZIO.unit
          case operation: RuntimeTraceOperation.Active =>
            operation.event(RuntimeTraceStage.LifecycleStarted, "Socket leave started") *>
              operation.event(RuntimeTraceStage.FinalFrame, "Socket leave completed")

  private def withOperation(
    correlation: RuntimeCorrelation
  )(
    run: RuntimeTraceOperation.Active => UIO[Unit]
  ): UIO[Unit] =
    operation(correlation).fold[UIO[Unit]](ZIO.unit)(run)

  private def operation(correlation: RuntimeCorrelation): Option[RuntimeTraceOperation.Active] =
    correlation.command
      .flatMap(command => Option(commands.get(command)))
      .orElse(correlation.turn.flatMap(turn => Option(turns.get(turn))))
      .orElse(Option(pendingInternal.get(correlation.lifecycle)))
      .collect { case active: RuntimeTraceOperation.Active => active }

  private def finish(correlation: RuntimeCorrelation): UIO[Unit] =
    ZIO.succeed {
      operation(correlation).foreach(removePending)
      correlation.command.foreach(cancel)
      val _ = pendingInternal.remove(correlation.lifecycle)
      finishTurnNow(correlation)
    }

  private def finishTurn(correlation: RuntimeCorrelation): UIO[Unit] =
    ZIO.succeed(finishTurnNow(correlation))

  private def finishTurnNow(correlation: RuntimeCorrelation): Unit =
    val _ = pendingInternal.remove(correlation.lifecycle)
    correlation.turn.foreach { turn =>
      val _ = turns.remove(turn)
      val _ = turnOwners.remove(turn)
    }

  private def removePending(operation: RuntimeTraceOperation.Active): Unit =
    frameGate.synchronized {
      val _ = pendingFrames
        .values().removeIf(frame => !frame.transportBound && (frame.operation eq operation))
    }

  private def observes(topic: String): Boolean =
    try diagnostic.isObserved(topic)
    catch case _: Throwable => false

  private def operationKind(kind: RuntimeCommandKind): RuntimeTraceOperationKind = kind match
    case RuntimeCommandKind.ClientEvent | RuntimeCommandKind.ComponentClientEvent =>
      RuntimeTraceOperationKind.ClientEvent
    case RuntimeCommandKind.AsyncCompletion | RuntimeCommandKind.ManagedAsync =>
      RuntimeTraceOperationKind.AsyncCompletion
    case RuntimeCommandKind.Upload      => RuntimeTraceOperationKind.Upload
    case RuntimeCommandKind.ParamsPatch => RuntimeTraceOperationKind.LivePatch
    case RuntimeCommandKind.Message | RuntimeCommandKind.ManagedSubscription |
        RuntimeCommandKind.ManagedSubscriptionEnded | RuntimeCommandKind.ComponentMessage |
        RuntimeCommandKind.ComponentUpdate | RuntimeCommandKind.ComponentAsyncCompletion =>
      RuntimeTraceOperationKind.ServerMessage
    case RuntimeCommandKind.Internal => RuntimeTraceOperationKind.Other

  private def traceInitiator(initiator: RuntimeInitiator): RuntimeTraceInitiator = initiator match
    case RuntimeInitiator.Browser => RuntimeTraceInitiator.Browser
    case RuntimeInitiator.Application | RuntimeInitiator.ManagedResource |
        RuntimeInitiator.Runtime =>
      RuntimeTraceInitiator.Runtime
end RuntimeObserver

private[scalive] object RuntimeObserver:
  val noop: RuntimeObserver = fromFunction(_ => ZIO.unit)

  val logging: RuntimeObserver = fromFunction(logEvent)

  def loggingWithDiagnostic(diagnostic: RuntimeDiagnostic): RuntimeObserver =
    withDiagnostic(logEvent, diagnostic)

  private def logEvent(event: RuntimeEvent): UIO[Unit] =
    val correlation = event.context
    ZIO.logDebug(
      s"runtime_event=${event.name} connection=${correlation.connection.value} " +
        s"lifecycle=${correlation.lifecycle.value} epoch=${correlation.epoch.value}"
    )

  def fromFunction(emitEvent: RuntimeEvent => UIO[Unit]): RuntimeObserver =
    withDiagnostic(emitEvent, RuntimeDiagnostic.Disabled)

  def withDiagnostic(
    emitEvent: RuntimeEvent => UIO[Unit],
    diagnostic: RuntimeDiagnostic
  ): RuntimeObserver =
    new RuntimeObserver(
      new RuntimeEventSink:
        def emit(event: RuntimeEvent): UIO[Unit] = emitEvent(event)
      ,
      diagnostic
    )
