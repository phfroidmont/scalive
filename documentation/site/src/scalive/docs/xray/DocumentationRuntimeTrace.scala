package scalive.docs.xray

import zio.*
import zio.http.Request
import zio.json.*
import zio.json.ast.Json

import scalive.*
import scalive.runtime.kernel.*

private[docs] object DocumentationTraceSanitizer:
  private val Redacted           = Json.Str("[redacted]")
  private val SensitiveFragments = Vector(
    "authorization",
    "cookie",
    "csrf",
    "secret",
    "session",
    "static",
    "token",
    "url",
    "redirect"
  )
  private val BrowserLabels = Map(
    "BrowserEvent"     -> "Browser event sent",
    "OutboundFrame"    -> "Outbound protocol frame encoded",
    "InboundFrame"     -> "Inbound protocol frame decoded",
    "InboundProcessed" -> "Inbound protocol frame processed",
    "DomPatch"         -> "DOM patch started",
    "DomDiff"          -> "Final DOM changes observed"
  )

  def structure(value: Json): Json = sanitize(value, None)

  private def sanitize(value: Json, fieldName: Option[String]): Json =
    fieldName match
      case Some(name) if isSensitive(name) => Redacted
      case _                               =>
        value match
          case Json.Obj(fields) =>
            Json.Obj(fields.map((name, child) => name -> sanitize(child, Some(name)))*)
          case Json.Arr(values) =>
            values.headOption match
              case Some(Json.Str(name)) if isSensitive(name) =>
                Json.Arr(values.zipWithIndex.map { case (child, index) =>
                  if index == 0 then child else Redacted
                })
              case _ => Json.Arr(values.map(sanitize(_, None)))
          case string: Json.Str => string
          case number: Json.Num => number
          case bool: Json.Bool  => bool
          case Json.Null        => Json.Null

  private def isSensitive(name: String): Boolean =
    val normalized = name.toLowerCase
    SensitiveFragments.exists(normalized.contains)

  def projectedField(name: String, value: String): String =
    if isSensitive(name) then "[redacted]" else value

  def browserLabel(stage: String): (String, String) =
    BrowserLabels
      .get(stage).fold("BrowserRecord" -> "Browser trace record")(summary => stage -> summary)

end DocumentationTraceSanitizer

final private[docs] class DocumentationRuntimeDiagnostic(
  store: DocumentationTraceStore,
  session: String,
  epoch: Long)
    extends RuntimeDiagnostic.Enabled(session, epoch):

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

  def publish(record: RuntimeTraceRecord): UIO[Unit] = store.appendServer(record)

  private def projected(value: scalive.docs.examples.ExampleTraceValue): RuntimeTraceValue =
    RuntimeTraceValue(
      value.typeName,
      value.summary,
      value.fields.map { case (name, fieldValue) =>
        name -> DocumentationTraceSanitizer.projectedField(name, fieldValue)
      },
      value.scalaValue
    )
end DocumentationRuntimeDiagnostic

final private[docs] class DocumentationRuntimeObserverFactory(store: DocumentationTraceStore)
    extends ZioHttp.RuntimeObserverFactory:

  def connect(request: Request): RuntimeObserver =
    request.url.queryParams
      .getAll(DocumentationRuntimeObserverFactory.TraceSessionParameter)
      .headOption
      .filter(DocumentationRuntimeObserverFactory.ValidSession.matches)
      .fold(RuntimeObserver.logging)(session =>
        RuntimeObserver.loggingWithDiagnostic(
          DocumentationRuntimeDiagnostic(store, session, store.nextConnectionEpoch(session))
        )
      )

private[docs] object DocumentationRuntimeObserverFactory:
  val TraceSessionParameter = "_scalive_trace_session"
  val ValidSession          = "[A-Za-z0-9_-]{16,64}".r
