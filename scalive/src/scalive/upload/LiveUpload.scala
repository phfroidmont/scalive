package scalive
package upload

import scala.annotation.targetName

import zio.*
import zio.json.ast.Json

opaque type UploadRef = String

object UploadRef:
  private[scalive] def apply(value: String): UploadRef = value
  extension (ref: UploadRef)
    @targetName("uploadRefValue")
    def value: String = ref

opaque type UploadEntryRef = String

object UploadEntryRef:
  private[scalive] def apply(value: String): UploadEntryRef = value
  extension (ref: UploadEntryRef)
    @targetName("uploadEntryRefValue")
    def value: String = ref

sealed trait LiveUploadAccept:
  private[scalive] def values: Option[NonEmptyChunk[String]]

  final def toHtmlValue: String = values.fold("*/*")(_.mkString(","))

object LiveUploadAccept:
  case object Any extends LiveUploadAccept:
    private[scalive] val values = None

  def only(first: String, rest: String*): LiveUploadAccept =
    validated(first +: rest).fold(throw _, identity)

  def validated(values: Iterable[String]): Either[IllegalArgumentException, LiveUploadAccept] =
    val normalized = values.iterator.map(_.trim).toList
    normalized match
      case Nil => Left(new IllegalArgumentException("Upload accept filters must not be empty"))
      case filters if filters.exists(_.isEmpty) =>
        Left(new IllegalArgumentException("Upload accept filters must not be blank"))
      case filters if filters.distinct.size != filters.size =>
        Left(new IllegalArgumentException("Upload accept filters must be unique"))
      case filters if filters.exists(filter => !isValidFilter(filter)) =>
        Left(
          new IllegalArgumentException("Upload accept filters must be extensions or media types")
        )
      case first :: rest => Right(Only(NonEmptyChunk(first, rest*)))

  final private case class Only(
    filters: NonEmptyChunk[String])
      extends LiveUploadAccept:
    private[scalive] val values = Some(filters)

  private def isValidFilter(value: String): Boolean =
    value.startsWith(".") && value.length > 1 ||
      value.count(_ == '/') == 1 && !value.startsWith("/") && !value.endsWith("/")

enum LiveUploadError:
  case TooManyFiles
  case TooLarge
  case NotAccepted
  case ExternalClientFailure
  case WriterFailure(reason: String)
  case External(meta: Json.Obj)
  case Unknown(code: String)

object LiveUploadError:
  def fromReason(reason: String): LiveUploadError =
    reason match
      case "too_many_files"          => LiveUploadError.TooManyFiles
      case "too_large"               => LiveUploadError.TooLarge
      case "not_accepted"            => LiveUploadError.NotAccepted
      case "external_client_failure" => LiveUploadError.ExternalClientFailure
      case "writer_error"            => LiveUploadError.WriterFailure("writer_error")
      case other                     => LiveUploadError.Unknown(other)

  def fromJson(value: Json): LiveUploadError =
    value match
      case Json.Str(reason) => fromReason(reason)
      case obj: Json.Obj    =>
        obj.fields
          .collectFirst { case ("reason", Json.Str(reason)) => fromReason(reason) }
          .getOrElse(LiveUploadError.External(obj))
      case other => LiveUploadError.Unknown(other.toString)

  def toJson(error: LiveUploadError): Json =
    error match
      case LiveUploadError.TooManyFiles          => Json.Str("too_many_files")
      case LiveUploadError.TooLarge              => Json.Str("too_large")
      case LiveUploadError.NotAccepted           => Json.Str("not_accepted")
      case LiveUploadError.ExternalClientFailure => Json.Str("external_client_failure")
      case LiveUploadError.WriterFailure(reason) => Json.Str(reason)
      case LiveUploadError.External(meta)        => meta
      case LiveUploadError.Unknown(code)         => Json.Str(code)

final class UploadClientMetadata private[scalive] (
  val fileName: String,
  val relativePath: Option[String],
  val sizeBytes: Long,
  val mediaType: String,
  val lastModifiedMillis: Option[Long],
  val metadata: Option[Json])

enum LiveUploadEntryStatus:
  case Selected
  case Preflighted
  case Uploading(progress: Int)
  case Completed
  case Invalid(errors: List[LiveUploadError])

final class LiveUploadEntry[Result] private[scalive] (
  val ref: UploadEntryRef,
  val client: UploadClientMetadata,
  val status: LiveUploadEntryStatus,
  val metadata: Option[Json.Obj],
  private[scalive] val uploadName: String):
  def progress: Int = status match
    case LiveUploadEntryStatus.Uploading(value) => value
    case LiveUploadEntryStatus.Completed        => 100
    case _                                      => 0

  def errors: List[LiveUploadError] = status match
    case LiveUploadEntryStatus.Invalid(values) => values
    case _                                     => Nil

final class LiveUpload[Result] private[scalive] (
  val definition: LiveUploadDef[Result],
  val ref: UploadRef,
  val entries: List[LiveUploadEntry[Result]],
  val errors: List[LiveUploadError]):
  def name: String             = definition.name
  def accept: LiveUploadAccept = definition.accept
  def maxEntries: Int          = definition.maxEntries
  def maxFileSize: Long        = definition.maxFileSize
  def chunkSize: Int           = definition.chunkSize
  def chunkTimeout: Duration   = definition.chunkTimeout
  def autoUpload: Boolean      = definition.autoUpload
  def external: Boolean        = definition.destination.external

final class CompletedUpload[Result] private[scalive] (
  val ref: UploadEntryRef,
  val client: UploadClientMetadata,
  val result: Result,
  val metadata: Json.Obj)

enum ConsumeDecision[+A]:
  case Consume(value: A)
  case Postpone(value: A)

enum LiveUploadAbortReason:
  case Cancelled
  case Disallowed
  case ComponentRemoved
  case SocketShutdown
  case Failed(reason: String)

sealed abstract class LiveUploadOperationError(message: String) extends Exception(message)

object LiveUploadOperationError:
  final case class ActiveEntries(name: String)
      extends LiveUploadOperationError(s"Upload $name still has active entries")

  final case class DefinitionMismatch(name: String)
      extends LiveUploadOperationError(s"Upload $name has a different definition")

  final case class NotAllowed(name: String)
      extends LiveUploadOperationError(s"Upload $name is not allowed")

  final case class EntryNotActive(ref: UploadEntryRef)
      extends LiveUploadOperationError(s"Upload entry ${ref.value} is not active")

  final case class EntryNotCompleted(ref: UploadEntryRef)
      extends LiveUploadOperationError(s"Upload entry ${ref.value} is not completed")

  final case class EntriesInProgress(name: String)
      extends LiveUploadOperationError(s"Upload $name has entries in progress")

trait LiveUploadWriter[State, Result]:
  def init(client: UploadClientMetadata): Task[State]
  def writeChunk(data: Chunk[Byte], state: State): Task[State]
  def complete(state: State): Task[Result]
  def abort(state: State, reason: LiveUploadAbortReason): Task[Unit]
  def discard(result: Result): Task[Unit]
  def metadata(_result: Result): Json.Obj = Json.Obj.empty

final class ExternalUploadClientConfig private (val json: Json.Obj)

object ExternalUploadClientConfig:
  def apply(json: Json.Obj): ExternalUploadClientConfig =
    validated(json).fold(throw _, identity)

  def validated(
    json: Json.Obj
  ): Either[IllegalArgumentException, ExternalUploadClientConfig] =
    json.fields.collectFirst { case ("uploader", Json.Str(value)) if value.nonEmpty => value } match
      case Some(_) => Right(new ExternalUploadClientConfig(json))
      case None    =>
        Left(
          new IllegalArgumentException(
            "External upload client configuration requires a non-empty uploader"
          )
        )

enum LiveExternalUploadResult[+Result]:
  case Ready(clientConfig: ExternalUploadClientConfig, result: Result)
  case Error(meta: Json.Obj)

trait LiveUploadExternalUploader[Result]:
  def preflight(client: UploadClientMetadata): LiveIO[LiveExternalUploadResult[Result]]
  def discard(_result: Result): Task[Unit] = ZIO.unit

trait LiveUploadProgress[Result]:
  def onProgress(entry: LiveUploadEntry[Result]): LiveIO[Unit]

sealed trait LiveUploadDestination[Result]:
  private[scalive] def runtime: UploadDestinationRuntime

object LiveUploadDestination:
  def inMemory: LiveUploadDestination[Chunk[Byte]] =
    hosted(new LiveUploadWriter[Chunk[Byte], Chunk[Byte]]:
      def init(client: UploadClientMetadata): Task[Chunk[Byte]] = ZIO.succeed(Chunk.empty)
      def writeChunk(data: Chunk[Byte], state: Chunk[Byte]): Task[Chunk[Byte]] =
        ZIO.succeed(state ++ data)
      def complete(state: Chunk[Byte]): Task[Chunk[Byte]]                      = ZIO.succeed(state)
      def abort(state: Chunk[Byte], reason: LiveUploadAbortReason): Task[Unit] = ZIO.unit
      def discard(result: Chunk[Byte]): Task[Unit]                             = ZIO.unit
      override def metadata(result: Chunk[Byte]): Json.Obj                     =
        Json.Obj("bytes" -> Json.Num(BigDecimal(result.length.toLong))))

  def hosted[State, Result](
    writer: LiveUploadWriter[State, Result]
  ): LiveUploadDestination[Result] =
    new LiveUploadDestination[Result]:
      private[scalive] val runtime: UploadDestinationRuntime =
        UploadDestinationRuntime.hosted(writer)

  def external[Result](
    uploader: LiveUploadExternalUploader[Result]
  ): LiveUploadDestination[Result] =
    new LiveUploadDestination[Result]:
      private[scalive] val runtime: UploadDestinationRuntime =
        UploadDestinationRuntime.external(uploader)

final class LiveUploadDef[Result] private (
  val name: String,
  val accept: LiveUploadAccept,
  val maxEntries: Int,
  val maxFileSize: Long,
  val chunkSize: Int,
  val chunkTimeout: Duration,
  val autoUpload: Boolean,
  val progress: Option[LiveUploadProgress[Result]],
  private[scalive] val destination: UploadDestinationRuntime):
  private[scalive] def withName(value: String): LiveUploadDef[Result] =
    new LiveUploadDef(
      value,
      accept,
      maxEntries,
      maxFileSize,
      chunkSize,
      chunkTimeout,
      autoUpload,
      progress,
      destination
    )

object LiveUploadDef:
  def inMemory(
    name: String,
    accept: LiveUploadAccept,
    maxEntries: Int = 1,
    maxFileSize: Long = 8_000_000L,
    chunkSize: Int = 64_000,
    chunkTimeout: Duration = 10.seconds,
    autoUpload: Boolean = false,
    progress: Option[LiveUploadProgress[Chunk[Byte]]] = None
  ): LiveUploadDef[Chunk[Byte]] =
    validated(
      name,
      accept,
      LiveUploadDestination.inMemory,
      maxEntries,
      maxFileSize,
      chunkSize,
      chunkTimeout,
      autoUpload,
      progress
    ).fold(throw _, identity)

  def hosted[State, Result](
    name: String,
    accept: LiveUploadAccept,
    writer: LiveUploadWriter[State, Result],
    maxEntries: Int = 1,
    maxFileSize: Long = 8_000_000L,
    chunkSize: Int = 64_000,
    chunkTimeout: Duration = 10.seconds,
    autoUpload: Boolean = false,
    progress: Option[LiveUploadProgress[Result]] = None
  ): LiveUploadDef[Result] =
    validated(
      name,
      accept,
      LiveUploadDestination.hosted(writer),
      maxEntries,
      maxFileSize,
      chunkSize,
      chunkTimeout,
      autoUpload,
      progress
    ).fold(throw _, identity)

  def external[Result](
    name: String,
    accept: LiveUploadAccept,
    uploader: LiveUploadExternalUploader[Result],
    maxEntries: Int = 1,
    maxFileSize: Long = 8_000_000L,
    chunkSize: Int = 64_000,
    chunkTimeout: Duration = 10.seconds,
    autoUpload: Boolean = false,
    progress: Option[LiveUploadProgress[Result]] = None
  ): LiveUploadDef[Result] =
    validated(
      name,
      accept,
      LiveUploadDestination.external(uploader),
      maxEntries,
      maxFileSize,
      chunkSize,
      chunkTimeout,
      autoUpload,
      progress
    ).fold(throw _, identity)

  def validated[Result](
    name: String,
    accept: LiveUploadAccept,
    destination: LiveUploadDestination[Result],
    maxEntries: Int = 1,
    maxFileSize: Long = 8_000_000L,
    chunkSize: Int = 64_000,
    chunkTimeout: Duration = 10.seconds,
    autoUpload: Boolean = false,
    progress: Option[LiveUploadProgress[Result]] = None
  ): Either[IllegalArgumentException, LiveUploadDef[Result]] =
    val error =
      if name.isEmpty then Some("Upload name must not be empty")
      else if maxEntries <= 0 then Some(s"Upload $name maxEntries must be > 0")
      else if maxFileSize <= 0 then Some(s"Upload $name maxFileSize must be > 0")
      else if chunkSize <= 0 then Some(s"Upload $name chunkSize must be > 0")
      else if chunkTimeout <= Duration.Zero then Some(s"Upload $name chunkTimeout must be > 0")
      else if chunkTimeout.toMillis > Int.MaxValue then
        Some(s"Upload $name chunkTimeout must fit protocol milliseconds")
      else None

    error match
      case Some(message) => Left(new IllegalArgumentException(message))
      case None          =>
        Right(
          new LiveUploadDef(
            name,
            accept,
            maxEntries,
            maxFileSize,
            chunkSize,
            chunkTimeout,
            autoUpload,
            progress,
            destination.runtime
          )
        )
  end validated
end LiveUploadDef

private[scalive] trait UploadDestinationRuntime:
  def external: Boolean
  def init(client: UploadClientMetadata): Task[Object]
  def writeChunk(data: Chunk[Byte], state: Object): Task[Object]
  def complete(state: Object): Task[Object]
  def abort(state: Object, reason: LiveUploadAbortReason): Task[Unit]
  def discard(result: Object): Task[Unit]
  def metadata(result: Object): Json.Obj
  def preflight(client: UploadClientMetadata): LiveIO[Option[LiveExternalUploadResult[Object]]]

private[scalive] object UploadDestinationRuntime:
  def hosted[State, Result](writer: LiveUploadWriter[State, Result]): UploadDestinationRuntime =
    new UploadDestinationRuntime:
      val external                                         = false
      def init(client: UploadClientMetadata): Task[Object] =
        writer.init(client).map(_.asInstanceOf[Object])
      def writeChunk(data: Chunk[Byte], state: Object): Task[Object] =
        writer.writeChunk(data, state.asInstanceOf[State]).map(_.asInstanceOf[Object])
      def complete(state: Object): Task[Object] =
        writer.complete(state.asInstanceOf[State]).map(_.asInstanceOf[Object])
      def abort(state: Object, reason: LiveUploadAbortReason): Task[Unit] =
        writer.abort(state.asInstanceOf[State], reason)
      def discard(result: Object): Task[Unit]     = writer.discard(result.asInstanceOf[Result])
      def metadata(result: Object): Json.Obj      = writer.metadata(result.asInstanceOf[Result])
      def preflight(client: UploadClientMetadata) = ZIO.none

  def external[Result](uploader: LiveUploadExternalUploader[Result]): UploadDestinationRuntime =
    new UploadDestinationRuntime:
      val external                                         = true
      def init(client: UploadClientMetadata): Task[Object] =
        ZIO.fail(new IllegalStateException("External uploads do not initialize a server writer"))
      def writeChunk(data: Chunk[Byte], state: Object): Task[Object] =
        ZIO.fail(new IllegalStateException("External uploads do not accept server chunks"))
      def complete(state: Object): Task[Object]                           = ZIO.succeed(state)
      def abort(state: Object, reason: LiveUploadAbortReason): Task[Unit] = ZIO.unit
      def discard(result: Object): Task[Unit] = uploader.discard(result.asInstanceOf[Result])
      def metadata(result: Object): Json.Obj  = Json.Obj.empty
      def preflight(client: UploadClientMetadata)
        : LiveIO[Option[LiveExternalUploadResult[Object]]] =
        uploader.preflight(client).map {
          case LiveExternalUploadResult.Ready(config, result) =>
            Some(LiveExternalUploadResult.Ready(config, result.asInstanceOf[Object]))
          case LiveExternalUploadResult.Error(meta) => Some(LiveExternalUploadResult.Error(meta))
        }
end UploadDestinationRuntime

object api:
  export _root_.scalive.upload.UploadRef.value
  export _root_.scalive.upload.UploadEntryRef.value
  export _root_.scalive.upload.{
    CompletedUpload,
    ConsumeDecision,
    ExternalUploadClientConfig,
    LiveExternalUploadResult,
    LiveUpload,
    LiveUploadAbortReason,
    LiveUploadAccept,
    LiveUploadDef,
    LiveUploadDestination,
    LiveUploadEntry,
    LiveUploadEntryStatus,
    LiveUploadError,
    LiveUploadExternalUploader,
    LiveUploadOperationError,
    LiveUploadProgress,
    LiveUploadWriter,
    UploadClientMetadata,
    UploadEntryRef,
    UploadRef
  }
