package scalive

import zio.json.*
import zio.json.ast.Json

private[scalive] object WebSocketPayloadEvent:
  def bindingPayload(p: WebSocketMessage.Payload.Event): BindingPayload =
    p.`type` match
      case "form" => BindingPayload.Form(formData(p), formMeta(p))
      case _      => BindingPayload.Params(params(p))

  def formMeta(p: WebSocketMessage.Payload.Event): FormEvent.Meta =
    val fields = p.meta match
      case Some(meta: Json.Obj) => meta.fields.toMap
      case _                    => Map.empty[String, Json]

    FormEvent.Meta(
      target = fields.get("_target").flatMap(decodeFormTarget),
      submitter = decodeFormSubmitter(fields, formData(p)),
      recovery = fields
        .get("_recover")
        .orElse(fields.get("_recovery"))
        .orElse(fields.get("recovery"))
        .exists(jsonToBoolean),
      metadata = fields.view.mapValues(jsonToParamValue).toMap,
      componentId = p.cid,
      uploads = p.uploads
    )

  def formData(p: WebSocketMessage.Payload.Event): FormData =
    p.`type` match
      case "form" =>
        p.value.asString
          .map(FormData.fromUrlEncoded)
          .getOrElse(FormData.empty)
      case _ => FormData.fromMap(decodeObjectToStringMap(p.value))

  def params(p: WebSocketMessage.Payload.Event): Map[String, String] =
    val base =
      p.`type` match
        case "form" =>
          formData(p).asMap
        case _ => decodeObjectToStringMap(p.value)

    val withMeta = p.meta match
      case Some(meta: Json.Obj) =>
        meta.fields.foldLeft(base) { case (acc, (key, jsonValue)) =>
          acc.updated(key, jsonToParamValue(jsonValue))
        }
      case _ => base

    val withUploads = p.uploads match
      case Some(uploads) => withMeta.updated("__uploads", uploads.toJson)
      case None          => withMeta

    p.cid match
      case Some(cid) => withUploads.updated("__cid", cid.toString)
      case None      => withUploads

  private def decodeObjectToStringMap(value: Json): Map[String, String] =
    value match
      case obj: Json.Obj =>
        obj.fields.map { case (key, jsonValue) => key -> jsonToParamValue(jsonValue) }.toMap
      case _ =>
        value.as[Map[String, String]].getOrElse(Map.empty)

  private def decodeFormTarget(value: Json): Option[FormPath] =
    value match
      case Json.Str("undefined") => None
      case Json.Str(value)       => Some(FormPath.parse(value))
      case Json.Arr(values)      =>
        val segments = values.collect {
          case Json.Str(segment) if segment.nonEmpty && segment != "undefined" => segment
        }.toVector
        if segments.isEmpty && values.exists(_.asString.contains("undefined")) then None
        else Some(FormPath(segments))
      case _ => None

  private def decodeFormSubmitter(
    fields: Map[String, Json],
    data: FormData
  ): Option[FormSubmitter] =
    fields.get("submitter").orElse(fields.get("_submitter")).flatMap {
      case submitter: Json.Obj =>
        val submitterFields = submitter.fields.toMap
        submitterFields.get("name").flatMap(_.asString).filter(_.nonEmpty).map { name =>
          val value = submitterFields.get("value").flatMap(_.asString).getOrElse("")
          FormSubmitter(name, value)
        }
      case Json.Str(name) if name.nonEmpty =>
        data.get(name).map(value => FormSubmitter(name, value))
      case _ => None
    }

  private def jsonToBoolean(value: Json): Boolean =
    value match
      case Json.Bool(value) => value
      case Json.Str(value)  => value == "true"
      case _                => false

  private def jsonToParamValue(value: Json): String =
    value match
      case Json.Str(v)  => v
      case Json.Num(v)  => v.toString
      case Json.Bool(v) => v.toString
      case Json.Null    => ""
      case other        => other.toJson
end WebSocketPayloadEvent
