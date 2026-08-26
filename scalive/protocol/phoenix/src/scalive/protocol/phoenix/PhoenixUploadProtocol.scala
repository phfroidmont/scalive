package scalive.protocol.phoenix

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

import zio.Chunk
import zio.json.ast.Json

final private[scalive] case class PhoenixUploadEntry(
  ref: String,
  name: String,
  relativePath: Option[String],
  size: Long,
  mediaType: String,
  lastModified: Option[Long],
  meta: Option[Json])

final private[scalive] case class PhoenixUploadPreflight(
  uploadRef: String,
  entries: Vector[PhoenixUploadEntry],
  cid: Option[Long])

final private[scalive] case class PhoenixUploadProgress(
  uploadRef: String,
  entryRef: String,
  progress: Int,
  cid: Option[Long],
  event: Option[String])

final private[scalive] case class PhoenixUploadBinaryFrame(
  joinRef: String,
  ref: String,
  topic: String,
  event: String,
  payload: Chunk[Byte])

final private[scalive] case class PhoenixUploadClientConfig(
  maxFileSize: Long,
  maxEntries: Int,
  chunkSize: Int,
  chunkTimeout: Int)

private[scalive] enum PhoenixUploadEntryConfig:
  case Hosted(token: String)
  case External(config: Json)

final private[scalive] case class PhoenixUploadPreflightResponse(
  uploadRef: String,
  config: PhoenixUploadClientConfig,
  entries: Map[String, PhoenixUploadEntryConfig],
  errors: Map[String, Vector[Json]])

private[scalive] enum PhoenixUploadJoinError:
  case InvalidToken, AlreadyRegistered, Disallowed, WriterError

private[scalive] enum PhoenixUploadChunkError:
  case FileSizeLimitExceeded(limit: Long)
  case QueueOverflow, WriterError, Disallowed

private[scalive] object PhoenixUploadProtocol:
  private val TopicPrefix = "lvu:"

  def entryRef(topic: String): Option[String] =
    Option.when(topic.startsWith(TopicPrefix) && topic.length > TopicPrefix.length)(
      topic.substring(TopicPrefix.length)
    )

  def decodeJoin(payload: Json): Either[String, String] = payload match
    case Json.Obj(rawFields) =>
      for
        fields <- uniqueFields(rawFields.toVector)
        _      <- rejectUnknown(fields, Set("token"))
        token  <- requiredString(fields, "token")
      yield token
    case _ => Left("upload phx_join payload must be an object")

  def decodePreflight(value: Json): Either[String, PhoenixUploadPreflight] = value match
    case Json.Obj(rawFields) =>
      for
        fields    <- uniqueFields(rawFields.toVector)
        _         <- rejectUnknown(fields, Set("ref", "entries", "cid"))
        uploadRef <- requiredString(fields, "ref")
        entries   <- requiredArray(fields, "entries").flatMap(decodeEntries)
        cid       <- optionalLong(fields, "cid", nonNegative = false)
      yield PhoenixUploadPreflight(uploadRef, entries, cid)
    case _ => Left("allow_upload value must be an object")

  def decodeProgress(value: Json): Either[String, PhoenixUploadProgress] = value match
    case Json.Obj(rawFields) =>
      for
        fields    <- uniqueFields(rawFields.toVector)
        _         <- rejectUnknown(fields, Set("ref", "entry_ref", "progress", "cid", "event"))
        uploadRef <- requiredString(fields, "ref")
        entryRef  <- requiredString(fields, "entry_ref")
        progress  <- requiredLong(fields, "progress", nonNegative = true)
        _         <- Either.cond(progress <= 100, (), "field 'progress' must be between 0 and 100")
        cid       <- optionalLong(fields, "cid", nonNegative = false)
        event     <- optionalString(fields, "event")
      yield PhoenixUploadProgress(uploadRef, entryRef, progress.toInt, cid, event)
    case _ => Left("progress value must be an object")

  def decodeEventUploads(value: Json.Obj): Either[String, Vector[PhoenixUploadPreflight]] =
    uniqueFields(value.fields.toVector).flatMap { uploads =>
      uploads.toVector.foldLeft[Either[String, Vector[PhoenixUploadPreflight]]](
        Right(Vector.empty)
      ) {
        case (result, (uploadRef, Json.Arr(entries))) =>
          for
            current <- result
            decoded <-
              decodeEntries(entries.toVector).left.map(error => s"upload '$uploadRef': $error")
          yield current :+ PhoenixUploadPreflight(uploadRef, decoded, None)
        case (_, (uploadRef, _)) => Left(s"upload '$uploadRef' entries must be an array")
      }
    }

  def decodeBinary(bytes: Chunk[Byte]): Either[String, PhoenixUploadBinaryFrame] =
    if bytes.length < 5 then Left("upload binary frame is truncated")
    else if bytes(0) != 0.toByte then Left(s"unsupported upload binary kind ${bytes(0) & 0xff}")
    else
      val lengths        = Vector.tabulate(4)(index => bytes(index + 1) & 0xff)
      val metadataLength = lengths.foldLeft(5)(_ + _)
      if metadataLength > bytes.length then Left("upload binary frame metadata is truncated")
      else
        val starts = lengths.scanLeft(5)(_ + _).dropRight(1)
        for
          segments <-
            starts.zip(lengths).foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) {
              case (result, (start, length)) =>
                for
                  values <- result
                  value  <- decodeUtf8(bytes.slice(start, start + length))
                yield values :+ value
            }
          frame = PhoenixUploadBinaryFrame(
                    segments(0),
                    segments(1),
                    segments(2),
                    segments(3),
                    bytes.drop(metadataLength)
                  )
          _ <- validateBinaryShape(frame)
        yield frame

  def encodeBinary(frame: PhoenixUploadBinaryFrame): Either[String, Chunk[Byte]] =
    for
      _ <- validateBinaryShape(frame)
      segments = Vector(frame.joinRef, frame.ref, frame.topic, frame.event).map(utf8)
      _ <- Either.cond(
             segments.forall(_.length <= 255),
             (),
             "upload binary metadata segment exceeds 255 UTF-8 bytes"
           )
      header = Chunk.fromIterable(0.toByte +: segments.map(_.length.toByte))
    yield segments.foldLeft(header)(_ ++ _) ++ frame.payload

  def preflightReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    response: PhoenixUploadPreflightResponse,
    diff: Option[Json.Obj]
  ): PhoenixEnvelope =
    val payload =
      diff.fold(encodePreflight(response))(value => encodePreflight(response).add("diff", value))
    acknowledgement(joinRef, ref, topic, payload)

  def uploadJoinAcknowledgement(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String
  ): PhoenixEnvelope = acknowledgement(joinRef, ref, topic, Json.Obj.empty)

  def chunkAcknowledgement(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String
  ): PhoenixEnvelope = acknowledgement(joinRef, ref, topic, Json.Obj.empty)

  def uploadJoinErrorReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    error: PhoenixUploadJoinError
  ): PhoenixEnvelope = errorReply(joinRef, ref, topic, joinErrorReason(error))

  def chunkErrorReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    error: PhoenixUploadChunkError
  ): PhoenixEnvelope =
    val response = error match
      case PhoenixUploadChunkError.FileSizeLimitExceeded(limit) =>
        Json.Obj(
          "reason" -> Json.Str("file_size_limit_exceeded"),
          "limit"  -> Json.Num(BigDecimal(limit))
        )
      case PhoenixUploadChunkError.QueueOverflow =>
        Json.Obj("reason" -> Json.Str("queue_overflow"))
      case PhoenixUploadChunkError.WriterError => Json.Obj("reason" -> Json.Str("writer_error"))
      case PhoenixUploadChunkError.Disallowed  => Json.Obj("reason" -> Json.Str("disallowed"))
    PhoenixEnvelope(
      joinRef,
      ref,
      topic,
      "phx_reply",
      Json.Obj("status" -> Json.Str("error"), "response" -> response)
    )

  def encodePreflight(response: PhoenixUploadPreflightResponse): Json.Obj =
    val config = Json.Obj(
      "max_file_size" -> Json.Num(BigDecimal(response.config.maxFileSize)),
      "max_entries"   -> Json.Num(BigDecimal(response.config.maxEntries)),
      "chunk_size"    -> Json.Num(BigDecimal(response.config.chunkSize)),
      "chunk_timeout" -> Json.Num(BigDecimal(response.config.chunkTimeout))
    )
    val entries = Json.Obj(response.entries.toVector.sortBy(_._1).map { case (ref, value) =>
      val json = value match
        case PhoenixUploadEntryConfig.Hosted(token)   => Json.Str(token)
        case PhoenixUploadEntryConfig.External(value) => value
      ref -> json
    }*)
    val errors = Json.Obj(response.errors.toVector.sortBy(_._1).map { case (ref, values) =>
      ref -> Json.Arr(values*)
    }*)
    Json.Obj(
      "ref"     -> Json.Str(response.uploadRef),
      "config"  -> config,
      "entries" -> entries,
      "errors"  -> errors
    )

  private def decodeEntries(values: Vector[Json]): Either[String, Vector[PhoenixUploadEntry]] =
    values.zipWithIndex.foldLeft[Either[String, Vector[PhoenixUploadEntry]]](Right(Vector.empty)) {
      case (result, (value, index)) =>
        for
          entries <- result
          entry   <- decodeEntry(value).left.map(error => s"entry $index: $error")
        yield entries :+ entry
    }

  private def decodeEntry(value: Json): Either[String, PhoenixUploadEntry] = value match
    case Json.Obj(rawFields) =>
      for
        fields <- uniqueFields(rawFields.toVector)
        _      <- rejectUnknown(
               fields,
               Set("ref", "name", "path", "relative_path", "size", "type", "last_modified", "meta")
             )
        ref          <- requiredString(fields, "ref")
        name         <- requiredString(fields, "name")
        _            <- optionalString(fields, "path")
        relativePath <- optionalString(fields, "relative_path")
        size         <- requiredLong(fields, "size", nonNegative = true)
        mediaType    <- requiredString(fields, "type")
        lastModified <- optionalLong(fields, "last_modified", nonNegative = true)
        meta = fields.get("meta").filterNot(_ == Json.Null)
      yield PhoenixUploadEntry(ref, name, relativePath, size, mediaType, lastModified, meta)
    case _ => Left("must be an object")

  private def validateBinaryShape(frame: PhoenixUploadBinaryFrame): Either[String, Unit] =
    for
      _ <- Either.cond(
             entryRef(frame.topic).nonEmpty,
             (),
             "upload binary topic must be 'lvu:<entry-ref>'"
           )
      _ <- Either.cond(frame.event == "chunk", (), "upload binary event must be 'chunk'")
    yield ()

  private def decodeUtf8(bytes: Chunk[Byte]): Either[String, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val array = Array.tabulate(bytes.length)(bytes(_))
    try Right(decoder.decode(ByteBuffer.wrap(array)).toString)
    catch
      case _: java.nio.charset.CharacterCodingException =>
        Left("upload binary metadata is not valid UTF-8")

  private def utf8(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  private def acknowledgement(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    response: Json.Obj
  ): PhoenixEnvelope = PhoenixEnvelope(
    joinRef,
    ref,
    topic,
    "phx_reply",
    Json.Obj("status" -> Json.Str("ok"), "response" -> response)
  )

  private def errorReply(
    joinRef: PhoenixRef,
    ref: PhoenixRef,
    topic: String,
    reason: String
  ): PhoenixEnvelope = PhoenixEnvelope(
    joinRef,
    ref,
    topic,
    "phx_reply",
    Json.Obj(
      "status"   -> Json.Str("error"),
      "response" -> Json.Obj("reason" -> Json.Str(reason))
    )
  )

  private def joinErrorReason(error: PhoenixUploadJoinError): String = error match
    case PhoenixUploadJoinError.InvalidToken      => "invalid_token"
    case PhoenixUploadJoinError.AlreadyRegistered => "already_registered"
    case PhoenixUploadJoinError.Disallowed        => "disallowed"
    case PhoenixUploadJoinError.WriterError       => "writer_error"

  private def requiredString(fields: Map[String, Json], name: String): Either[String, String] =
    fields.get(name) match
      case Some(Json.Str(value)) => Right(value)
      case Some(_)               => Left(s"field '$name' must be a string")
      case None                  => Left(s"missing field '$name'")

  private def optionalString(
    fields: Map[String, Json],
    name: String
  ): Either[String, Option[String]] = fields.get(name) match
    case None | Some(Json.Null) => Right(None)
    case Some(Json.Str(value))  => Right(Some(value))
    case Some(_)                => Left(s"field '$name' must be a string or null")

  private def requiredArray(fields: Map[String, Json], name: String): Either[String, Vector[Json]] =
    fields.get(name) match
      case Some(Json.Arr(values)) => Right(values.toVector)
      case Some(_)                => Left(s"field '$name' must be an array")
      case None                   => Left(s"missing field '$name'")

  private def requiredLong(
    fields: Map[String, Json],
    name: String,
    nonNegative: Boolean
  ): Either[String, Long] = fields.get(name) match
    case Some(value) => decodeLong(value, name, nonNegative)
    case None        => Left(s"missing field '$name'")

  private def optionalLong(
    fields: Map[String, Json],
    name: String,
    nonNegative: Boolean
  ): Either[String, Option[Long]] = fields.get(name) match
    case None | Some(Json.Null) => Right(None)
    case Some(value)            => decodeLong(value, name, nonNegative).map(Some(_))

  private def decodeLong(value: Json, name: String, nonNegative: Boolean): Either[String, Long] =
    value match
      case Json.Num(number) =>
        val decimal = BigDecimal(number)
        if !decimal.isWhole || !decimal.isValidLong then Left(s"field '$name' must be an integer")
        else if nonNegative && decimal.signum < 0 then Left(s"field '$name' must be non-negative")
        else Right(decimal.toLong)
      case _ => Left(s"field '$name' must be an integer")

  private def rejectUnknown(fields: Map[String, Json], allowed: Set[String]): Either[String, Unit] =
    fields.keysIterator.find(!allowed.contains(_)) match
      case Some(name) => Left(s"unknown field '$name'")
      case None       => Right(())

  private def uniqueFields(fields: Vector[(String, Json)]): Either[String, Map[String, Json]] =
    val duplicate = fields
      .groupMapReduce(_._1)(_ => 1)(_ + _)
      .collectFirst { case (name, count) if count > 1 => name }
    duplicate match
      case Some(name) => Left(s"duplicate field '$name'")
      case None       => Right(fields.toMap)
end PhoenixUploadProtocol
