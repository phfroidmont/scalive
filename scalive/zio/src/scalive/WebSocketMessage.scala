package scalive

import java.nio.charset.StandardCharsets

import zio.Chunk
import zio.http.QueryParams
import zio.json.*
import zio.json.ast.Json

import scalive.WebSocketMessage.LiveResponse
import scalive.WebSocketMessage.Payload
import scalive.WebSocketMessage.ReplyStatus

final case class WebSocketMessage(
  // Live session ID, auto increment defined by the client on join
  joinRef: Option[Int],
  // Message ID, global auto increment defined by the client on every message
  messageRef: Option[Int],
  // LiveView instance id
  topic: String,
  eventType: String,
  payload: WebSocketMessage.Payload):
  val meta    = WebSocketMessage.Meta(joinRef, messageRef, topic, eventType)
  def okReply =
    WebSocketMessage(
      joinRef,
      messageRef,
      topic,
      "phx_reply",
      Payload.Reply(ReplyStatus.Ok, LiveResponse.Empty)
    )
object WebSocketMessage:

  final case class Meta(
    joinRef: Option[Int],
    messageRef: Option[Int],
    topic: String,
    eventType: String)

  given JsonCodec[WebSocketMessage] = JsonCodec[Json].transformOrFail(
    {
      case Json.Arr(
            Chunk(joinRef, Json.Str(messageRef), Json.Str(topic), Json.Str(eventType), payload)
          ) =>
        val payloadParsed = eventType match
          case "heartbeat"    => Right(Payload.Heartbeat)
          case "phx_join"     => decodeJoinPayload(payload)
          case "phx_leave"    => Right(Payload.Leave)
          case "phx_close"    => Right(Payload.Close)
          case "event"        => payload.as[Payload.Event]
          case "live_patch"   => payload.as[Payload.LivePatch]
          case "allow_upload" => payload.as[Payload.AllowUpload]
          case "progress"     => payload.as[Payload.Progress]
          case s              => Left(s"Unknown event type : $s")

        payloadParsed.map(
          WebSocketMessage(
            joinRef.asString.map(_.toInt),
            Some(messageRef.toInt),
            topic,
            eventType,
            _
          )
        )
      case v => Left(s"Could not parse socket message ${v.toJson}")
    },
    m =>
      Json.Arr(
        m.joinRef.map(ref => Json.Str(ref.toString)).getOrElse(Json.Null),
        m.messageRef.map(ref => Json.Str(ref.toString)).getOrElse(Json.Null),
        Json.Str(m.topic),
        Json.Str(m.eventType),
        m.payload match
          case Payload.Heartbeat      => Json.Obj.empty
          case p: Payload.Join        => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.UploadJoin  => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case Payload.UploadChunk(_) =>
            throw new IllegalArgumentException("UploadChunk cannot be JSON encoded")
          case Payload.Leave          => Json.Obj.empty
          case Payload.Close          => Json.Obj.empty
          case p: Payload.LivePatch   => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.AllowUpload => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.Progress    => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.LiveNavigation =>
            p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case Payload.Error    => Json.Obj.empty
          case p: Payload.Reply => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.Event => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
          case p: Payload.Diff  => p.toJsonAST.getOrElse(throw new IllegalArgumentException())
      )
  )

  enum Payload:
    case Heartbeat
    case Join(
      url: Option[String],
      redirect: Option[String],
      // params: Map[String, String],
      session: String,
      static: Option[List[String]],
      params: Option[Map[String, Json]],
      sticky: Boolean)
    case UploadJoin(token: String)
    case Leave
    case Close
    case LivePatch(url: String)
    case AllowUpload(ref: String, entries: List[UploadPreflightEntry], cid: Option[Int])
    case Progress(
      event: Option[String],
      ref: String,
      entry_ref: String,
      progress: Json,
      cid: Option[Int])
    case UploadChunk(bytes: Chunk[Byte])
    case LiveNavigation(to: String, kind: LivePatchKind)
    case Error
    case Reply(status: ReplyStatus, response: LiveResponse)
    case Diff(diff: scalive.Diff)
    case Event(
      `type`: String,
      event: String,
      value: Json,
      uploads: Option[Json] = None,
      cid: Option[Int] = None,
      meta: Option[Json] = None)
  end Payload

  final case class UploadPreflightEntry(
    ref: String,
    name: String,
    relative_path: Option[String],
    size: Long,
    `type`: String,
    last_modified: Option[Long] = None,
    meta: Option[Json] = None)

  object Payload:
    given JsonCodec[Payload.Join]             = JsonCodec.derived
    given JsonCodec[Payload.UploadJoin]       = JsonCodec.derived
    given JsonCodec[Payload.LivePatch]        = JsonCodec.derived
    given JsonCodec[UploadPreflightEntry]     = JsonCodec.derived
    given JsonCodec[Payload.AllowUpload]      = JsonCodec.derived
    given JsonCodec[Payload.Progress]         = JsonCodec.derived
    given JsonEncoder[Payload.LiveNavigation] =
      JsonEncoder[Json].contramap { navigation =>
        Json.Obj(
          "to"   -> Json.Str(navigation.to),
          "kind" -> Json.Str(
            navigation.kind match
              case LivePatchKind.Push    => "push"
              case LivePatchKind.Replace => "replace"
          )
        )
      }
    given JsonEncoder[Payload.Reply] = JsonEncoder.derived
    given JsonCodec[Payload.Event]   = JsonCodec.derived
    given JsonEncoder[Payload.Diff]  = JsonEncoder[scalive.Diff].contramap(_.diff)

    extension (p: Payload.Event)
      def params: Map[String, String] =
        val base =
          p.`type` match
            case "form" =>
              QueryParams
                .decode(
                  p.value.asString.getOrElse(throw new IllegalArgumentException())
                ).map.iterator.map { case (key, values) =>
                  key -> values.headOption.getOrElse("")
                }.toMap
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
          value.as[Map[String, String]].getOrElse(throw new IllegalArgumentException())

    private def jsonToParamValue(value: Json): String =
      value match
        case Json.Str(v)  => v
        case Json.Num(v)  => v.toString
        case Json.Bool(v) => v.toString
        case Json.Null    => ""
        case other        => other.toJson

    def okReply(response: LiveResponse): Payload.Reply =
      Payload.Reply(ReplyStatus.Ok, response)

    def errorReply(response: LiveResponse): Payload.Reply =
      Payload.Reply(ReplyStatus.Error, response)
  end Payload

  enum ReplyStatus:
    case Ok
    case Error
  object ReplyStatus:
    given JsonEncoder[ReplyStatus] =
      JsonEncoder[String].contramap {
        case ReplyStatus.Ok    => "ok"
        case ReplyStatus.Error => "error"
      }

  final case class UploadClientConfig(
    max_file_size: Long,
    max_entries: Int,
    chunk_size: Int,
    chunk_timeout: Int)
  object UploadClientConfig:
    given JsonCodec[UploadClientConfig] = JsonCodec.derived

  enum LivePatchKind:
    case Push
    case Replace

  final case class UploadJoinToken(
    liveViewTopic: String,
    uploadRef: String,
    entryRef: String)
  object UploadJoinToken:
    given JsonCodec[UploadJoinToken] = JsonCodec.derived

  enum JoinErrorReason:
    case Unauthorized
    case Stale

  enum UploadJoinErrorReason:
    case InvalidToken
    case AlreadyRegistered
    case Disallowed
    case WriterError

  enum UploadChunkErrorReason:
    case FileSizeLimitExceeded
    case WriterError
    case Disallowed

  enum LiveResponse:
    case Empty
    case InitDiff(rendered: scalive.Diff)
    case Diff(diff: scalive.Diff)
    case HookReply(reply: Json, diff: Option[scalive.Diff] = None)
    case JoinError(reason: JoinErrorReason)
    case UploadJoinError(reason: UploadJoinErrorReason)
    case UploadChunkError(reason: UploadChunkErrorReason, limit: Option[Long] = None)
    case UploadPreflightSuccess(
      ref: String,
      config: UploadClientConfig,
      entries: Map[String, Json],
      errors: Map[String, List[Json]])
    case UploadPreflightFailure(ref: String, error: List[(String, String)])
  object LiveResponse:
    given JsonEncoder[LiveResponse] =
      JsonEncoder[Json].contramap {
        case Empty              => Json.Obj.empty
        case InitDiff(rendered) =>
          Json.Obj(
            "liveview_version" -> Json.Str("1.1.8"),
            "rendered"         -> rendered.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
        case Diff(diff) =>
          Json.Obj(
            "diff" -> diff.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
        case HookReply(reply, diff) =>
          val mergedDiff =
            diff
              .flatMap(_.toJsonAST.toOption)
              .collect { case obj: Json.Obj => obj }
              .getOrElse(Json.Obj.empty)
          Json.Obj(
            "diff" -> mergedDiff.add("r", reply)
          )
        case JoinError(reason) =>
          val reasonString =
            reason match
              case JoinErrorReason.Unauthorized => "unauthorized"
              case JoinErrorReason.Stale        => "stale"
          Json.Obj("reason" -> Json.Str(reasonString))
        case UploadJoinError(reason) =>
          val reasonString =
            reason match
              case UploadJoinErrorReason.InvalidToken      => "invalid_token"
              case UploadJoinErrorReason.AlreadyRegistered => "already_registered"
              case UploadJoinErrorReason.Disallowed        => "disallowed"
              case UploadJoinErrorReason.WriterError       => "writer_error"
          Json.Obj("reason" -> Json.Str(reasonString))
        case UploadChunkError(reason, limit) =>
          val reasonString =
            reason match
              case UploadChunkErrorReason.FileSizeLimitExceeded => "file_size_limit_exceeded"
              case UploadChunkErrorReason.WriterError           => "writer_error"
              case UploadChunkErrorReason.Disallowed            => "disallowed"
          limit match
            case Some(maxLimit) =>
              Json.Obj(
                "reason" -> Json.Str(reasonString),
                "limit"  -> Json.Num(BigDecimal(maxLimit))
              )
            case None =>
              Json.Obj("reason" -> Json.Str(reasonString))
        case UploadPreflightSuccess(ref, config, entries, errors) =>
          Json.Obj(
            "ref"     -> Json.Str(ref),
            "config"  -> config.toJsonAST.getOrElse(throw new IllegalArgumentException()),
            "entries" -> entries.toJsonAST.getOrElse(throw new IllegalArgumentException()),
            "errors"  -> errors.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
        case UploadPreflightFailure(ref, error) =>
          Json.Obj(
            "ref"   -> Json.Str(ref),
            "error" -> error.toJsonAST.getOrElse(throw new IllegalArgumentException())
          )
      }
  end LiveResponse

  private def decodeJoinPayload(payload: Json): Either[String, Payload] =
    payload match
      case obj: Json.Obj
          if obj.fields.exists(_._1 == "token") && !obj.fields.exists(_._1 == "session") =>
        payload.as[Payload.UploadJoin]
      case _ =>
        payload.as[Payload.Join]

  def decodeBinaryPush(bytes: Chunk[Byte]): Either[String, WebSocketMessage] =
    if bytes.length < 5 then Left("Binary push frame too short")
    else
      val kind = bytes(0)
      if kind != 0.toByte then Left(s"Unsupported binary frame kind: $kind")
      else
        val joinRefSize   = bytes(1).toInt & 0xff
        val refSize       = bytes(2).toInt & 0xff
        val topicSize     = bytes(3).toInt & 0xff
        val eventSize     = bytes(4).toInt & 0xff
        val headerSize    = 5
        val totalMetaSize = headerSize + joinRefSize + refSize + topicSize + eventSize
        if bytes.length < totalMetaSize then Left("Binary push frame metadata truncated")
        else
          val joinRefStart = headerSize
          val refStart     = joinRefStart + joinRefSize
          val topicStart   = refStart + refSize
          val eventStart   = topicStart + topicSize
          val payloadStart = eventStart + eventSize

          val joinRefString = decodeAsciiSegment(bytes, joinRefStart, joinRefSize)
          val refString     = decodeAsciiSegment(bytes, refStart, refSize)
          val topic         = decodeAsciiSegment(bytes, topicStart, topicSize)
          val eventType     = decodeAsciiSegment(bytes, eventStart, eventSize)
          val payloadBytes  = bytes.drop(payloadStart)

          val joinRef =
            if joinRefString.isEmpty then Right(None)
            else
              joinRefString.toIntOption
                .toRight(s"Invalid binary join_ref: $joinRefString")
                .map(Some(_))

          val messageRef =
            if refString.isEmpty then Right(None)
            else
              refString.toIntOption
                .toRight(s"Invalid binary ref: $refString")
                .map(Some(_))

          for
            parsedJoinRef    <- joinRef
            parsedMessageRef <- messageRef
            payload          <- eventType match
                         case "chunk" => Right(Payload.UploadChunk(payloadBytes))
                         case other   => Left(s"Unsupported binary event type: $other")
          yield WebSocketMessage(
            joinRef = parsedJoinRef,
            messageRef = parsedMessageRef,
            topic = topic,
            eventType = eventType,
            payload = payload
          )
        end if
      end if

  private def decodeAsciiSegment(bytes: Chunk[Byte], start: Int, length: Int): String =
    if length <= 0 then ""
    else new String(bytes.slice(start, start + length).toArray, StandardCharsets.UTF_8)
end WebSocketMessage
