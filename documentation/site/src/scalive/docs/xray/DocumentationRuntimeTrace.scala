package scalive.docs.xray

import zio.*
import zio.http.Request
import zio.json.*
import zio.json.ast.Json

import scalive.*
import scalive.WebSocketMessage.Payload

private[docs] object DocumentationTraceSanitizer:
  private val Redacted           = Json.Str("[redacted]")
  private val SensitiveFragments = Vector(
    "authorization",
    "claim",
    "cookie",
    "credential",
    "csrf",
    "flash",
    "password",
    "secret",
    "session",
    "token",
    "upload"
  )
  private val SafeStringKeys = Set(
    "event",
    "kind",
    "status",
    "type",
    "liveview_version"
  )
  private val BrowserLabels = Map(
    "BrowserEvent"  -> "Browser event sent",
    "OutboundFrame" -> "Outbound protocol frame encoded",
    "InboundFrame"  -> "Inbound protocol frame decoded",
    "DomPatch"      -> "DOM patch started",
    "DomDiff"       -> "Final DOM changes observed"
  )

  def protocol(message: WebSocketMessage): Json =
    val payload = message.payload match
      case Payload.UploadChunk(bytes) =>
        Json.Obj(
          "content"    -> Redacted,
          "byteLength" -> Json.Num(bytes.length)
        )
      case _ =>
        message.toJsonAST.toOption match
          case Some(Json.Arr(parts)) if parts.length == 5 => structure(parts(4))
          case _                                          => Json.Obj.empty

    Json.Obj(
      "joinReference"    -> optionalReference(message.joinRef),
      "messageReference" -> optionalReference(message.messageRef),
      "topic"            -> Json.Str(message.topic),
      "event"            -> Json.Str(message.eventType),
      "payload"          -> payload
    )

  def structure(value: Json): Json = sanitize(value, None)

  private def sanitize(value: Json, fieldName: Option[String]): Json =
    fieldName match
      case Some(name) if isSensitive(name) => Redacted
      case _                               =>
        value match
          case Json.Obj(fields) =>
            Json.Obj(fields.map((name, child) => name -> sanitize(child, Some(name)))*)
          case Json.Arr(values) => Json.Arr(values.map(sanitize(_, None)))
          case Json.Str(value) if fieldName.exists(SafeStringKeys.contains) => Json.Str(value)
          case Json.Str(_)                                                  => Redacted
          case number: Json.Num                                             => number
          case bool: Json.Bool                                              => bool
          case Json.Null                                                    => Json.Null

  private def isSensitive(name: String): Boolean =
    val normalized = name.toLowerCase
    SensitiveFragments.exists(normalized.contains)

  def projectedField(name: String, value: String): String =
    if isSensitive(name) then "[redacted]" else value

  def browserLabel(stage: String): (String, String) =
    BrowserLabels
      .get(stage).fold("BrowserRecord" -> "Browser trace record")(summary => stage -> summary)

  private def optionalReference(value: Option[Int]): Json =
    value.fold[Json](Json.Null)(current => Json.Str(current.toString))
end DocumentationTraceSanitizer

final private[docs] class DocumentationRuntimeTrace(
  store: DocumentationTraceStore,
  session: String,
  epoch: Long)
    extends RuntimeTrace.Enabled(session, epoch):

  def isObserved(topic: String): Boolean = store.isActive(session, topic)

  def projectMessage(topic: String, value: Any): RuntimeTraceValue =
    store
      .registered(session, topic)
      .flatMap(_.projectMessage(value))
      .fold(RuntimeTraceValue.redacted(value))(projected)

  def projectModel(topic: String, value: Any): RuntimeTraceValue =
    store
      .registered(session, topic)
      .flatMap(_.projectModel(value))
      .fold(RuntimeTraceValue.redacted(value))(projected)

  def sanitizeProtocol(message: WebSocketMessage, encoded: Option[String]): Json =
    DocumentationTraceSanitizer.protocol(message)

  def publish(record: RuntimeTraceRecord): UIO[Unit] = store.appendServer(record)

  private def projected(value: scalive.docs.examples.ExampleTraceValue): RuntimeTraceValue =
    RuntimeTraceValue(
      value.typeName,
      value.summary,
      value.fields.map { case (name, fieldValue) =>
        name -> DocumentationTraceSanitizer.projectedField(name, fieldValue)
      }
    )
end DocumentationRuntimeTrace

private[docs] object DocumentationRuntimeTrace:
  def apply(
    store: DocumentationTraceStore,
    session: String,
    connectionEpoch: Long
  ): DocumentationRuntimeTrace =
    new DocumentationRuntimeTrace(store, session, connectionEpoch)

final private[docs] class DocumentationRuntimeTraceFactory(store: DocumentationTraceStore)
    extends RuntimeTraceFactory:

  def connect(request: Request): RuntimeTrace =
    request.url.queryParams
      .getAll(DocumentationRuntimeTraceFactory.TraceSessionParameter)
      .headOption
      .filter(DocumentationRuntimeTraceFactory.ValidSession.matches)
      .fold[RuntimeTrace](RuntimeTrace.Disabled)(session =>
        DocumentationRuntimeTrace(store, session, store.nextConnectionEpoch(session))
      )

private[docs] object DocumentationRuntimeTraceFactory:
  val TraceSessionParameter = "_scalive_xray_session"
  val ValidSession          = "[A-Za-z0-9_-]{16,64}".r
