package scalive

import java.net.URI

import zio.*
import zio.json.ast.Json
import zio.stream.*

final case class LiveUploadedEntry(
  ref: String,
  name: String,
  contentType: String,
  bytes: Chunk[Byte],
  meta: Json.Obj = Json.Obj.empty)

enum LiveUploadAccept:
  case Any
  case Exactly(values: List[String])

  def toHtmlValue: String =
    this match
      case Any             => "*/*"
      case Exactly(values) => values.mkString(",")

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
          .collectFirst { case ("reason", Json.Str(reason)) =>
            fromReason(reason)
          }.getOrElse(LiveUploadError.External(obj))
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

final case class LiveUploadEntry(
  ref: String,
  clientName: String,
  clientRelativePath: Option[String],
  clientSize: Long,
  clientType: String,
  clientLastModified: Option[Long],
  progress: Int,
  preflighted: Boolean,
  done: Boolean,
  cancelled: Boolean,
  valid: Boolean,
  errors: List[LiveUploadError],
  meta: Option[Json.Obj])

final case class LiveUpload(
  name: String,
  ref: String,
  accept: LiveUploadAccept,
  maxEntries: Int,
  maxFileSize: Long,
  chunkSize: Int,
  chunkTimeout: Int,
  autoUpload: Boolean,
  external: Boolean,
  entries: List[LiveUploadEntry],
  errors: List[LiveUploadError])

final case class LiveExternalUploadEntry(
  ref: String,
  name: String,
  relativePath: Option[String],
  size: Long,
  contentType: String,
  lastModified: Option[Long],
  clientMeta: Option[Json])

enum LiveExternalUploadResult:
  case Ok(meta: Json.Obj)
  case Error(meta: Json.Obj)

trait LiveUploadExternalUploader:
  def preflight(entry: LiveExternalUploadEntry): RIO[LiveContext, LiveExternalUploadResult]

enum LiveUploadWriterCloseReason:
  case Done
  case Cancel
  case Error(reason: String)

trait LiveUploadWriter:
  def init(uploadName: String, entry: LiveExternalUploadEntry): Task[Any]
  def meta(state: Any): Json.Obj
  def writeChunk(data: Chunk[Byte], state: Any): Task[Any]
  def close(state: Any, reason: LiveUploadWriterCloseReason): Task[Any]

object LiveUploadWriter:
  final private case class InMemoryState(bytes: Chunk[Byte])

  val InMemory: LiveUploadWriter = new LiveUploadWriter:
    def init(uploadName: String, entry: LiveExternalUploadEntry): Task[Any] =
      ZIO.succeed(InMemoryState(Chunk.empty))

    def meta(state: Any): Json.Obj =
      state match
        case InMemoryState(bytes) => Json.Obj("bytes" -> Json.Num(BigDecimal(bytes.length.toLong)))
        case _                    => Json.Obj.empty

    def writeChunk(data: Chunk[Byte], state: Any): Task[Any] =
      state match
        case current: InMemoryState => ZIO.succeed(current.copy(bytes = current.bytes ++ data))
        case _                      => ZIO.succeed(InMemoryState(data))

    def close(state: Any, reason: LiveUploadWriterCloseReason): Task[Any] =
      ZIO.succeed(state)

trait LiveUploadProgress:
  def onProgress(uploadName: String, entry: LiveUploadEntry): RIO[LiveContext, Unit]

final case class LiveUploadOptions(
  accept: LiveUploadAccept,
  maxEntries: Int = 1,
  maxFileSize: Long = 8_000_000L,
  chunkSize: Int = 64_000,
  chunkTimeout: Int = 10_000,
  autoUpload: Boolean = false,
  external: Option[LiveUploadExternalUploader] = None,
  progress: Option[LiveUploadProgress] = None,
  writer: LiveUploadWriter = LiveUploadWriter.InMemory)

final case class LiveContext(
  staticChanged: Boolean,
  uploads: LiveContext.Uploads = LiveContext.Uploads.Disabled)
object LiveContext:
  trait Uploads:
    def allow(name: String, options: LiveUploadOptions): Task[LiveUpload]
    def disallow(name: String): Task[Unit]
    def get(name: String): UIO[Option[LiveUpload]]
    def cancel(name: String, entryRef: String): Task[Unit]
    def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]]
    def consume(entryRef: String): UIO[Option[LiveUploadedEntry]]
    def drop(entryRef: String): UIO[Unit]

  object Uploads:
    val Disabled: Uploads = new Uploads:
      def allow(name: String, options: LiveUploadOptions): Task[LiveUpload] =
        ZIO.fail(new IllegalStateException("Upload runtime is not available"))

      def disallow(name: String): Task[Unit] =
        ZIO.fail(new IllegalStateException("Upload runtime is not available"))

      def get(name: String): UIO[Option[LiveUpload]] = ZIO.none

      def cancel(name: String, entryRef: String): Task[Unit] =
        ZIO.fail(new IllegalStateException("Upload runtime is not available"))

      def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]] = ZIO.succeed(Nil)

      def consume(entryRef: String): UIO[Option[LiveUploadedEntry]] = ZIO.none
      def drop(entryRef: String): UIO[Unit]                         = ZIO.unit

  def staticChanged: URIO[LiveContext, Boolean] = ZIO.serviceWith[LiveContext](_.staticChanged)

  def allowUpload(name: String, options: LiveUploadOptions): RIO[LiveContext, LiveUpload] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.allow(name, options))

  def disallowUpload(name: String): RIO[LiveContext, Unit] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.disallow(name))

  def upload(name: String): URIO[LiveContext, Option[LiveUpload]] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.get(name))

  def cancelUpload(name: String, entryRef: String): RIO[LiveContext, Unit] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.cancel(name, entryRef))

  def consumeUploadedEntries(name: String): URIO[LiveContext, List[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.consumeCompleted(name))

  def consumeUploadedEntry(entryRef: String): URIO[LiveContext, Option[LiveUploadedEntry]] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.consume(entryRef))

  def dropUploadedEntry(entryRef: String): URIO[LiveContext, Unit] =
    ZIO.serviceWithZIO[LiveContext](_.uploads.drop(entryRef))
end LiveContext

trait LiveView[Msg, Model]:
  def init: Model | RIO[LiveContext, Model]
  def update(model: Model): Msg => Model | RIO[LiveContext, Model]
  def view(model: Dyn[Model]): HtmlElement
  def subscriptions(model: Model): ZStream[LiveContext, Nothing, Msg]
  def handleParams(model: Model, _params: Map[String, String], _uri: URI)
    : ParamsResult[Model] | RIO[LiveContext, ParamsResult[Model]] =
    ParamsResult.cont(model)
  def handleHook(model: Model, _event: String, _value: Json)
    : HookResult[Model] | RIO[LiveContext, HookResult[Model]] =
    HookResult.cont(model)

enum ParamsResult[Model]:
  case Continue(model: Model)
  case PushPatch(model: Model, to: String)
  case ReplacePatch(model: Model, to: String)

object ParamsResult:
  def cont[Model](model: Model): ParamsResult[Model] =
    ParamsResult.Continue(model)

  def pushPatch[Model](model: Model, to: String): ParamsResult[Model] =
    ParamsResult.PushPatch(model, to)

  def replacePatch[Model](model: Model, to: String): ParamsResult[Model] =
    ParamsResult.ReplacePatch(model, to)

enum HookResult[Model]:
  case Continue(model: Model)
  case Halt(model: Model, reply: Option[Json])

object HookResult:
  def cont[Model](model: Model): HookResult[Model]                   = HookResult.Continue(model)
  def halt[Model](model: Model): HookResult[Model]                   = HookResult.Halt(model, None)
  def haltReply[Model](model: Model, value: Json): HookResult[Model] =
    HookResult.Halt(model, Some(value))
