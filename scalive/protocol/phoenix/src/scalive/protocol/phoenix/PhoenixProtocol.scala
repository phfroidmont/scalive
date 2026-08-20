package scalive.protocol.phoenix

import zio.json.*
import zio.json.ast.Json

import scalive.*

/** The nullable references used by Phoenix's JSON serializer. */
private[scalive] enum PhoenixRef:
  case Null
  case Value(value: String)

private[scalive] object PhoenixRef:
  private[phoenix] def decode(json: Json, name: String): Either[String, PhoenixRef] = json match
    case Json.Null       => Right(Null)
    case Json.Str(value) => Right(Value(value))
    case _               => Left(s"$name must be a string or null")

  private[phoenix] def encode(ref: PhoenixRef): Json = ref match
    case Null         => Json.Null
    case Value(value) => Json.Str(value)

/** The exact five-item Phoenix websocket frame. */
final private[scalive] case class PhoenixEnvelope(
  joinRef: PhoenixRef,
  ref: PhoenixRef,
  topic: String,
  event: String,
  payload: Json)

private[scalive] object PhoenixEnvelope:
  def fromJson(json: Json): Either[String, PhoenixEnvelope] = json match
    case Json.Arr(values) if values.length == 5 =>
      for
        joinRef <- PhoenixRef.decode(values(0), "join ref")
        ref     <- PhoenixRef.decode(values(1), "ref")
        topic   <- string(values(2), "topic")
        event   <- string(values(3), "event")
      yield PhoenixEnvelope(joinRef, ref, topic, event, values(4))
    case Json.Arr(_) => Left("Phoenix envelope must contain exactly five items")
    case _           => Left("Phoenix envelope must be a JSON array")

  def toJson(envelope: PhoenixEnvelope): Json = Json.Arr(
    PhoenixRef.encode(envelope.joinRef),
    PhoenixRef.encode(envelope.ref),
    Json.Str(envelope.topic),
    Json.Str(envelope.event),
    envelope.payload
  )

  def decode(value: String): Either[String, PhoenixEnvelope] =
    value.fromJson[Json].left.map(error => s"invalid JSON: $error").flatMap(fromJson)

  def encode(envelope: PhoenixEnvelope): String = toJson(envelope).toJson

  given JsonCodec[PhoenixEnvelope] = JsonCodec(
    JsonEncoder[Json].contramap(toJson),
    JsonDecoder[Json].mapOrFail(fromJson)
  )

  private def string(json: Json, name: String): Either[String, String] = json match
    case Json.Str(value) => Right(value)
    case _               => Left(s"$name must be a string")
end PhoenixEnvelope

private[scalive] enum PhoenixInbound:
  case Heartbeat(joinRef: PhoenixRef, ref: PhoenixRef)
  case Join(joinRef: PhoenixRef, ref: PhoenixRef, topic: String, payload: RootJoin)
  case Event(joinRef: PhoenixRef, ref: PhoenixRef, topic: String, payload: RootEvent)
  case LivePatch(joinRef: PhoenixRef, ref: PhoenixRef, topic: String, url: String)
  case Leave(joinRef: PhoenixRef, ref: PhoenixRef, topic: String)

final private[scalive] case class RootJoin(
  url: Option[String],
  redirect: Option[String],
  flash: Option[String],
  session: String,
  static: Option[String],
  params: Map[String, Json],
  sticky: Boolean)

final private[scalive] case class RootEvent(
  eventType: String,
  event: String,
  value: Json,
  cid: Option[Long],
  uploads: Option[Json.Obj] = None,
  meta: Option[Json.Obj] = None):

  /** Converts a non-form root event value to Scalive binding parameters. */
  def rootClickParams: Either[String, BindingPayload.Params] =
    if eventType == "form" then Left("form events do not contain parameter objects")
    else
      value match
        case Json.Obj(fields) =>
          Right(
            BindingPayload.Params(
              fields.map((name, fieldValue) => name -> stringify(fieldValue)).toMap
            )
          )
        case _ => Left("non-form root event value must be a JSON object")

  def toBindingPayload: Either[String, BindingPayload] =
    if eventType == "form" then
      value match
        case Json.Str(encoded) =>
          FormData
            .fromUrlEncoded(encoded)
            .left.map(error => s"could not decode form event value: $error")
            .map(data => BindingPayload.Form(data, formMeta(data)))
        case _ => Left("form event value must be a URL-encoded string")
    else rootClickParams

  private def formMeta(data: FormData): FormEvent.Meta =
    val fields = meta.fold(Map.empty[String, Json])(_.fields.toMap)
    FormEvent.Meta(
      target = fields.get("_target").flatMap(decodeTarget),
      submitter = decodeSubmitter(fields, data),
      recovery = fields
        .get("_recover")
        .orElse(fields.get("_recovery"))
        .orElse(fields.get("recovery"))
        .exists(asBoolean),
      metadata = fields.view.mapValues(stringify).toMap
    )

  private def decodeTarget(json: Json): Option[FormPath] = json match
    case Json.Str("undefined") => None
    case Json.Str(value)       => Some(FormPath.parse(value))
    case Json.Arr(values)      =>
      val segments = values.collect {
        case Json.Str(segment) if segment.nonEmpty && segment != "undefined" => segment
      }.toVector
      if segments.isEmpty && values.exists(_.asString.contains("undefined")) then None
      else Some(FormPath(segments))
    case _ => None

  private def decodeSubmitter(
    fields: Map[String, Json],
    data: FormData
  ): Option[FormSubmitter] =
    fields.get("submitter").orElse(fields.get("_submitter")).flatMap {
      case Json.Obj(rawSubmitterFields) =>
        val submitterFields = rawSubmitterFields.toMap
        submitterFields.get("name").flatMap(_.asString).filter(_.nonEmpty).map { name =>
          val value = submitterFields.get("value").flatMap(_.asString).getOrElse("")
          FormSubmitter(name, value)
        }
      case Json.Str(name) if name.nonEmpty =>
        data.get(name).map(value => FormSubmitter(name, value))
      case _ => None
    }

  private def asBoolean(json: Json): Boolean = json match
    case Json.Bool(value) => value
    case Json.Str(value)  => value == "true"
    case _                => false

  private def stringify(json: Json): String = json match
    case Json.Str(value)  => value
    case Json.Num(value)  => value.toString
    case Json.Bool(value) => value.toString
    case Json.Null        => ""
    case nested           => nested.toJson
end RootEvent

private[scalive] object PhoenixProtocol:
  val PhoenixVersion  = "1.7.21"
  val LiveViewVersion = "1.1.28"

  def decode(json: Json): Either[String, PhoenixInbound] =
    PhoenixEnvelope.fromJson(json).flatMap(decodeEnvelope)

  def decode(value: String): Either[String, PhoenixInbound] =
    PhoenixEnvelope.decode(value).flatMap(decodeEnvelope)

  def decodeEnvelope(envelope: PhoenixEnvelope): Either[String, PhoenixInbound] =
    envelope match
      case PhoenixEnvelope(joinRef, ref, "phoenix", "heartbeat", Json.Obj(fields))
          if fields.isEmpty =>
        Right(PhoenixInbound.Heartbeat(joinRef, ref))
      case PhoenixEnvelope(_, _, "phoenix", "heartbeat", _) =>
        Left("heartbeat payload must be an empty object")
      case PhoenixEnvelope(joinRef, ref, topic, "phx_join", payload) if topic.startsWith("lv:") =>
        decodeJoin(payload).map(PhoenixInbound.Join(joinRef, ref, topic, _))
      case PhoenixEnvelope(joinRef, ref, topic, "event", payload) if topic.startsWith("lv:") =>
        decodeEvent(payload).map(PhoenixInbound.Event(joinRef, ref, topic, _))
      case PhoenixEnvelope(joinRef, ref, topic, "live_patch", payload) if topic.startsWith("lv:") =>
        decodeLivePatch(payload).map(PhoenixInbound.LivePatch(joinRef, ref, topic, _))
      case PhoenixEnvelope(joinRef, ref, topic, "phx_leave", Json.Obj(fields))
          if topic.startsWith("lv:") && fields.isEmpty =>
        Right(PhoenixInbound.Leave(joinRef, ref, topic))
      case PhoenixEnvelope(_, _, topic, "phx_leave", _) if topic.startsWith("lv:") =>
        Left("phx_leave payload must be an empty object")
      case other => Left(s"unsupported Phoenix message '${other.topic}:${other.event}'")

  private def decodeJoin(json: Json): Either[String, RootJoin] = json match
    case Json.Obj(rawFields) =>
      val fields = rawFields.toMap
      rejectUnknown(
        fields,
        Set("url", "redirect", "flash", "session", "static", "params", "sticky")
      ).flatMap { _ =>
        for
          url      <- optionalString(fields, "url")
          redirect <- optionalString(fields, "redirect")
          flash    <- optionalString(fields, "flash")
          session  <- requiredString(fields, "session")
          static   <- optionalString(fields, "static")
          params   <- requiredObject(fields, "params")
          sticky   <- optionalBoolean(fields, "sticky", default = false)
        yield RootJoin(url, redirect, flash, session, static, params.toMap, sticky)
      }
    case _ => Left("phx_join payload must be an object")

  private def decodeEvent(json: Json): Either[String, RootEvent] = json match
    case Json.Obj(rawFields) =>
      val fields = rawFields.toMap
      rejectUnknown(fields, Set("type", "event", "value", "uploads", "cid", "meta")).flatMap { _ =>
        for
          eventType <- requiredString(fields, "type")
          event     <- requiredString(fields, "event")
          value     <- fields.get("value").toRight("missing field 'value'")
          uploads   <- optionalObject(fields, "uploads")
          cid       <- optionalCid(fields)
          meta      <- optionalObject(fields, "meta")
        yield RootEvent(eventType, event, value, cid, uploads, meta)
      }
    case _ => Left("event payload must be an object")

  private def decodeLivePatch(json: Json): Either[String, String] = json match
    case Json.Obj(rawFields) if rawFields.size == 1 =>
      requiredString(rawFields.toMap, "url")
    case Json.Obj(_) => Left("live_patch payload must contain exactly one 'url' field")
    case _           => Left("live_patch payload must be an object")

  private def requiredString(fields: Map[String, Json], name: String): Either[String, String] =
    fields.get(name) match
      case Some(Json.Str(value)) => Right(value)
      case Some(_)               => Left(s"field '$name' must be a string")
      case None                  => Left(s"missing field '$name'")

  private def optionalString(fields: Map[String, Json], name: String)
    : Either[String, Option[String]] =
    fields.get(name) match
      case None | Some(Json.Null) => Right(None)
      case Some(Json.Str(value))  => Right(Some(value))
      case Some(_)                => Left(s"field '$name' must be a string or null")

  private def requiredObject(
    fields: Map[String, Json],
    name: String
  ): Either[String, Map[String, Json]] = fields.get(name) match
    case Some(Json.Obj(values)) => Right(values.toMap)
    case Some(_)                => Left(s"field '$name' must be an object")
    case None                   => Left(s"missing field '$name'")

  private def optionalBoolean(
    fields: Map[String, Json],
    name: String,
    default: Boolean
  ): Either[String, Boolean] = fields.get(name) match
    case None                   => Right(default)
    case Some(Json.Bool(value)) => Right(value)
    case Some(_)                => Left(s"field '$name' must be a boolean")

  private def optionalObject(
    fields: Map[String, Json],
    name: String
  ): Either[String, Option[Json.Obj]] = fields.get(name) match
    case None | Some(Json.Null) => Right(None)
    case Some(value: Json.Obj)  => Right(Some(value))
    case Some(_)                => Left(s"field '$name' must be an object or null")

  private def optionalCid(fields: Map[String, Json]): Either[String, Option[Long]] =
    fields.get("cid") match
      case None | Some(Json.Null) => Right(None)
      case Some(Json.Num(value)) if BigDecimal(value).isValidLong && BigDecimal(value).isWhole =>
        Right(Some(BigDecimal(value).toLong))
      case Some(_) => Left("field 'cid' must be an integer or null")

  private def rejectUnknown(fields: Map[String, Json], allowed: Set[String]): Either[String, Unit] =
    fields.keysIterator.find(!allowed.contains(_)) match
      case Some(name) => Left(s"unknown field '$name'")
      case None       => Right(())
end PhoenixProtocol
