package scalive.docs.xray

import zio.json.*
import zio.json.ast.Json

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

  def browserLabel(stage: String): (String, String) =
    BrowserLabels
      .get(stage).fold("BrowserRecord" -> "Browser trace record")(summary => stage -> summary)

end DocumentationTraceSanitizer
