package scalive

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import zio.*
import zio.http.Request
import zio.json.*
import zio.json.ast.Json

final private[scalive] case class RuntimeTraceValue(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)] = Vector.empty,
  scalaValue: Option[String] = None)

private[scalive] object RuntimeTraceValue:
  def redacted(value: Any): RuntimeTraceValue =
    val typeName   = if value == null then "null" else value.getClass.getName
    val scalaValue =
      if value == null then "null"
      else s"_: ${scalaTypeName(value.getClass)}"
    RuntimeTraceValue(typeName, "Content redacted", scalaValue = Some(scalaValue))

  private val ScalaName = "[A-Za-z_$][A-Za-z0-9_$]*".r

  private def scalaTypeName(value: Class[?]): String =
    if value.isArray then s"Array[${scalaTypeName(value.getComponentType)}]"
    else if value.isPrimitive then
      value.getName match
        case "boolean" => "Boolean"
        case "byte"    => "Byte"
        case "char"    => "Char"
        case "double"  => "Double"
        case "float"   => "Float"
        case "int"     => "Int"
        case "long"    => "Long"
        case "short"   => "Short"
        case "void"    => "Unit"
        case _         => "Any"
    else
      val module    = value.getName.endsWith("$")
      val candidate = value.getName.stripSuffix("$").replace('$', '.').split('.').takeRight(3)
      val valid     = candidate.nonEmpty && candidate.forall(ScalaName.matches) &&
        !candidate.exists(name => name.contains("$anon") || name.contains("$Lambda"))
      if !valid then "Any"
      else s"${candidate.mkString(".")}${if module then ".type" else ""}"
end RuntimeTraceValue

private[scalive] enum RuntimeTraceOperationKind:
  case Join
  case ClientEvent
  case ServerMessage
  case AsyncCompletion
  case LivePatch
  case Upload
  case Leave
  case Other

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
  joinReference: Option[Int],
  messageReference: Option[Int],
  operationSequence: Long,
  operationKind: RuntimeTraceOperationKind)

final private[scalive] case class RuntimeTraceRecord(
  identity: RuntimeTraceIdentity,
  recordSequence: Long,
  stage: RuntimeTraceStage,
  summary: String,
  value: Option[RuntimeTraceValue] = None,
  protocol: Option[Json] = None,
  byteSize: Option[Int] = None)

sealed private[scalive] trait RuntimeTrace:
  private[scalive] def session: Option[String]

  private[scalive] def begin(
    meta: WebSocketMessage.Meta,
    kind: RuntimeTraceOperationKind
  ): RuntimeTraceOperation

private[scalive] object RuntimeTrace:
  case object Disabled extends RuntimeTrace:
    val session: Option[String] = None

    def begin(
      meta: WebSocketMessage.Meta,
      kind: RuntimeTraceOperationKind
    ): RuntimeTraceOperation = RuntimeTraceOperation.Disabled

  abstract class Enabled(
    val traceSession: String,
    val connectionEpoch: Long)
      extends RuntimeTrace:

    final val session: Option[String] = Some(traceSession)

    final private class TopicState:
      private val socketEpoch       = AtomicLong(0L)
      private val operationSequence = AtomicLong(0L)

      def begin(kind: RuntimeTraceOperationKind): (Long, Long) =
        val epoch = kind match
          case RuntimeTraceOperationKind.Join =>
            operationSequence.set(0L)
            socketEpoch.incrementAndGet()
          case _ =>
            val current = socketEpoch.get()
            if current > 0 then current
            else
              socketEpoch.compareAndExchange(0L, 1L) match
                case 0L       => 1L
                case observed => observed
        epoch -> operationSequence.incrementAndGet()

    private val topics = ConcurrentHashMap[String, TopicState]()

    def isObserved(topic: String): Boolean
    def projectMessage(topic: String, value: Any): RuntimeTraceValue
    def projectModel(topic: String, value: Any): RuntimeTraceValue
    def sanitizeProtocol(message: WebSocketMessage, encoded: Option[String]): Json
    def publish(record: RuntimeTraceRecord): UIO[Unit]

    final def begin(
      meta: WebSocketMessage.Meta,
      kind: RuntimeTraceOperationKind
    ): RuntimeTraceOperation =
      val observed =
        try isObserved(meta.topic)
        catch case _: Throwable => false

      if !observed then RuntimeTraceOperation.Disabled
      else
        val state                            = topics.computeIfAbsent(meta.topic, _ => TopicState())
        val (socketEpoch, operationSequence) = state.begin(kind)
        RuntimeTraceOperation.Active(
          this,
          RuntimeTraceIdentity(
            traceSession,
            connectionEpoch,
            socketEpoch,
            meta.topic,
            meta.joinRef,
            meta.messageRef,
            operationSequence,
            kind
          )
        )
  end Enabled
end RuntimeTrace

private[scalive] trait RuntimeTraceFactory:
  def connect(request: Request): RuntimeTrace

private[scalive] object RuntimeTraceFactory:
  object Disabled extends RuntimeTraceFactory:
    def connect(request: Request): RuntimeTrace = RuntimeTrace.Disabled

private[scalive] object RuntimeTraceFrame:
  def encode(message: WebSocketMessage): UIO[String] =
    val encoded = message.toJson
    RuntimeTraceOperation
      .protocol(
        message.traceOperation,
        RuntimeTraceStage.FinalFrame,
        "Final protocol frame sent",
        message,
        Some(encoded)
      ).as(encoded)

sealed private[scalive] trait RuntimeTraceOperation

private[scalive] object RuntimeTraceOperation:
  case object Disabled extends RuntimeTraceOperation

  def resolve(
    trace: RuntimeTrace,
    meta: WebSocketMessage.Meta,
    kind: RuntimeTraceOperationKind
  ): RuntimeTraceOperation =
    meta.traceOperation match
      case Disabled => trace.begin(meta, kind)
      case active   => active

  def attach(
    meta: WebSocketMessage.Meta,
    operation: RuntimeTraceOperation
  ): WebSocketMessage.Meta =
    if meta.traceOperation == operation then meta
    else meta.copy(traceOperation = operation)

  def attach(
    message: WebSocketMessage,
    operation: RuntimeTraceOperation
  ): WebSocketMessage =
    if message.traceOperation == operation then message
    else message.copy(traceOperation = operation)

  def event(
    operation: RuntimeTraceOperation,
    stage: RuntimeTraceStage,
    summary: String
  ): UIO[Unit] =
    operation match
      case value: Active => value.event(stage, summary)
      case Disabled      => ZIO.unit

  def message(
    operation: RuntimeTraceOperation,
    stage: RuntimeTraceStage,
    summary: String,
    value: Any
  ): UIO[Unit] =
    operation match
      case active: Active => active.message(stage, summary, value)
      case Disabled       => ZIO.unit

  def model(
    operation: RuntimeTraceOperation,
    stage: RuntimeTraceStage,
    summary: String,
    value: Any
  ): UIO[Unit] =
    operation match
      case active: Active => active.model(stage, summary, value)
      case Disabled       => ZIO.unit

  def protocol(
    operation: RuntimeTraceOperation,
    stage: RuntimeTraceStage,
    summary: String,
    message: WebSocketMessage,
    encoded: Option[String]
  ): UIO[Unit] =
    operation match
      case active: Active => active.protocol(stage, summary, message, encoded)
      case Disabled       => ZIO.unit

  def crash(
    operation: RuntimeTraceOperation,
    stageName: String,
    error: Throwable
  ): UIO[Unit] =
    operation match
      case active: Active => active.crash(stageName, error)
      case Disabled       => ZIO.unit

  final case class Active(
    trace: RuntimeTrace.Enabled,
    identity: RuntimeTraceIdentity)
      extends RuntimeTraceOperation:

    private val nextRecordSequence = AtomicLong(0L)

    def event(stage: RuntimeTraceStage, summary: String): UIO[Unit] =
      publish(RuntimeTraceRecord(identity, nextSequence(), stage, summary))

    def message(stage: RuntimeTraceStage, summary: String, value: Any): UIO[Unit] =
      project(value, trace.projectMessage(identity.topic, _)).flatMap(projected =>
        publish(
          RuntimeTraceRecord(identity, nextSequence(), stage, summary, value = Some(projected))
        )
      )

    def model(stage: RuntimeTraceStage, summary: String, value: Any): UIO[Unit] =
      project(value, trace.projectModel(identity.topic, _)).flatMap(projected =>
        publish(
          RuntimeTraceRecord(identity, nextSequence(), stage, summary, value = Some(projected))
        )
      )

    def protocol(
      stage: RuntimeTraceStage,
      summary: String,
      message: WebSocketMessage,
      encoded: Option[String]
    ): UIO[Unit] =
      ZIO
        .attempt(trace.sanitizeProtocol(message, encoded))
        .foldZIO(
          _ => publish(RuntimeTraceRecord(identity, nextSequence(), stage, summary)),
          sanitized =>
            publish(
              RuntimeTraceRecord(
                identity,
                nextSequence(),
                stage,
                summary,
                protocol = Some(sanitized),
                byteSize = encoded.map(_.getBytes(StandardCharsets.UTF_8).length)
              )
            )
        )

    def crash(stageName: String, error: Throwable): UIO[Unit] =
      event(RuntimeTraceStage.Crash, s"$stageName failed with ${error.getClass.getName}")

    private def project(
      value: Any,
      projector: Any => RuntimeTraceValue
    ): UIO[RuntimeTraceValue] =
      ZIO.attempt(projector(value)).orElseSucceed(RuntimeTraceValue.redacted(value))

    private def publish(record: RuntimeTraceRecord): UIO[Unit] =
      trace.publish(record).sandbox.catchAllCause(_ => ZIO.unit)

    private def nextSequence(): Long = nextRecordSequence.incrementAndGet()
  end Active
end RuntimeTraceOperation
