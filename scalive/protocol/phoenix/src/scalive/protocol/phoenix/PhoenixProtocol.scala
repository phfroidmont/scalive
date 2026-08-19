package scalive.protocol.phoenix

import zio.json.*
import zio.json.ast.Json

import scalive.BindingPayload

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
  cid: Option[Long]):

  /** Converts only Phoenix's flat root click payload to Scalive binding parameters. */
  def rootClickParams: Either[String, BindingPayload.Params] =
    if eventType != "click" then Left(s"unsupported root event type '$eventType'")
    else
      value match
        case Json.Obj(fields) =>
          fields
            .foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) {
              case (result, (name, Json.Str(fieldValue))) =>
                result.map(_.updated(name, fieldValue))
              case (_, (name, _: Json.Obj | _: Json.Arr)) =>
                Left(s"unsupported nested click value at '$name'")
              case (_, (name, _)) => Left(s"click value '$name' must be a string")
            }.map(BindingPayload.Params.apply)
        case Json.Str(_) => Left("form-encoded click values are unsupported")
        case _           => Left("root click value must be a flat JSON object")

  def toBindingPayload: Either[String, BindingPayload] = rootClickParams

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
          _        <- Either.cond(
                 url.nonEmpty || redirect.nonEmpty,
                 (),
                 "phx_join payload must contain a non-null 'url' or 'redirect'"
               )
          flash   <- optionalString(fields, "flash")
          session <- requiredString(fields, "session")
          static  <- optionalString(fields, "static")
          params  <- requiredObject(fields, "params")
          sticky  <- optionalBoolean(fields, "sticky", default = false)
        yield RootJoin(url, redirect, flash, session, static, params.toMap, sticky)
      }
    case _ => Left("phx_join payload must be an object")

  private def decodeEvent(json: Json): Either[String, RootEvent] = json match
    case Json.Obj(rawFields) =>
      val fields = rawFields.toMap
      rejectUnknown(fields, Set("type", "event", "value", "cid")).flatMap { _ =>
        for
          eventType <- requiredString(fields, "type")
          event     <- requiredString(fields, "event")
          value     <- fields.get("value").toRight("missing field 'value'")
          cid       <- optionalCid(fields)
        yield RootEvent(eventType, event, value, cid)
      }
    case _ => Left("event payload must be an object")

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
