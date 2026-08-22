package scalive
package upload

import scala.annotation.targetName

import zio.*
import zio.json.ast.Json

/** Opaque identifier for one allowed upload configuration in the current LiveView lifecycle.
  *
  * The identifier correlates browser upload protocol messages with a [[LiveUpload]]. It is
  * transient, is not an authorization credential, and must not be used as a durable application or
  * storage identifier.
  */
opaque type UploadRef = String

/** Access to the raw value of framework-created [[UploadRef]] identifiers.
  *
  * Application code cannot construct references; it receives them from upload snapshots.
  */
object UploadRef:
  private[scalive] def apply(value: String): UploadRef = value
  extension (ref: UploadRef)
    /** Returns the raw protocol value, for example when an upload DOM attribute requires it. */
    @targetName("uploadRefValue")
    def value: String = ref

/** Opaque identifier for one browser-selected entry in an allowed upload.
  *
  * An entry reference only identifies an entry within its active upload lifecycle. It originates in
  * the browser upload protocol, so it must not be treated as trusted input, an authorization
  * credential, a file name, or a durable storage identifier.
  */
opaque type UploadEntryRef = String

/** Access to the raw value of framework-created [[UploadEntryRef]] identifiers.
  *
  * Application code cannot construct references; it receives them from upload entry snapshots.
  */
object UploadEntryRef:
  private[scalive] def apply(value: String): UploadEntryRef = value
  extension (ref: UploadEntryRef)
    /** Returns the raw protocol value used to correlate this entry with browser events. */
    @targetName("uploadEntryRefValue")
    def value: String = ref

/** Declares which files a browser may select for an upload.
  *
  * Filters are rendered into the HTML `accept` attribute and checked during server preflight using
  * the file name and media type reported by the browser. Both values are client-controlled, so an
  * accept policy is a usability and early-validation mechanism, not a security boundary. Validate
  * the actual content before trusting or publishing an upload.
  */
sealed trait LiveUploadAccept:
  private[scalive] def values: Option[NonEmptyChunk[String]]

  /** Renders this policy in the comma-separated form expected by an HTML `accept` attribute.
    *
    * [[LiveUploadAccept.Any]] renders as the HTML wildcard for all media types.
    */
  final def toHtmlValue: String = values.fold("*/*")(_.mkString(","))

/** Constructors for upload accept policies. */
object LiveUploadAccept:
  /** Allows every browser-reported file name and media type. */
  case object Any extends LiveUploadAccept:
    private[scalive] val values = None

  /** Creates a policy containing one or more file extensions or media types.
    *
    * Leading and trailing whitespace is removed from every filter. Extensions start with `.`, while
    * media filters contain one non-edge `/` and may use a wildcard subtype, for example any image
    * type. This is deliberately shallow syntax validation rather than complete MIME parsing.
    * Preflight lowercases filters and browser-reported values before matching.
    *
    * @throws IllegalArgumentException
    *   if a filter is blank or does not match the shallow extension/media grammar, or if trimmed
    *   filters contain a case-sensitive exact duplicate
    */
  def only(first: String, rest: String*): LiveUploadAccept =
    validated(first +: rest).fold(throw _, identity)

  /** Validates and creates an accept policy without throwing.
    *
    * The input must contain at least one filter. Filters are trimmed, must remain non-empty and
    * unique by case-sensitive equality, and must be either an extension beginning with `.` or a
    * media-like value containing exactly one non-leading, non-trailing `/`. No complete MIME syntax
    * validation is performed.
    */
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
end LiveUploadAccept

/** A validation or destination failure associated with an upload or one of its entries.
  *
  * Errors derived from browser metadata or external uploader payloads may contain untrusted data.
  * Do not expose their raw values without applying the escaping appropriate to the destination.
  */
enum LiveUploadError:
  /** More entries were selected than the upload definition permits. */
  case TooManyFiles

  /** The browser-reported file size exceeds the configured per-entry limit. */
  case TooLarge

  /** The browser-reported file name and media type do not match the accept policy. */
  case NotAccepted

  /** External upload preflight failed without a structured external error. */
  case ExternalClientFailure

  /** A hosted writer failed; `reason` is the protocol-facing writer error code. */
  case WriterFailure(reason: String)

  /** Structured metadata returned for an external upload failure. */
  case External(meta: Json.Obj)

  /** An error code not recognized by this Scalive version. */
  case Unknown(code: String)

/** Conversion helpers for upload errors carried by the LiveView protocol. */
object LiveUploadError:
  /** Decodes a protocol error code, preserving unrecognized codes as [[LiveUploadError.Unknown]].
    */
  def fromReason(reason: String): LiveUploadError =
    reason match
      case "too_many_files"          => LiveUploadError.TooManyFiles
      case "too_large"               => LiveUploadError.TooLarge
      case "not_accepted"            => LiveUploadError.NotAccepted
      case "external_client_failure" => LiveUploadError.ExternalClientFailure
      case "writer_error"            => LiveUploadError.WriterFailure("writer_error")
      case other                     => LiveUploadError.Unknown(other)

  /** Decodes a protocol error value.
    *
    * String values are decoded by [[fromReason]]. An object with a string `reason` field is decoded
    * from that field; any other object is retained as [[LiveUploadError.External]]. Other JSON
    * shapes are retained textually as [[LiveUploadError.Unknown]].
    */
  def fromJson(value: Json): LiveUploadError =
    value match
      case Json.Str(reason) => fromReason(reason)
      case obj: Json.Obj    =>
        obj.fields
          .collectFirst { case ("reason", Json.Str(reason)) => fromReason(reason) }
          .getOrElse(LiveUploadError.External(obj))
      case other => LiveUploadError.Unknown(other.toString)

  /** Encodes an upload error in its protocol JSON representation. */
  def toJson(error: LiveUploadError): Json =
    error match
      case LiveUploadError.TooManyFiles          => Json.Str("too_many_files")
      case LiveUploadError.TooLarge              => Json.Str("too_large")
      case LiveUploadError.NotAccepted           => Json.Str("not_accepted")
      case LiveUploadError.ExternalClientFailure => Json.Str("external_client_failure")
      case LiveUploadError.WriterFailure(reason) => Json.Str(reason)
      case LiveUploadError.External(meta)        => meta
      case LiveUploadError.Unknown(code)         => Json.Str(code)
end LiveUploadError

/** Browser-reported metadata for a selected upload entry.
  *
  * Every field in this value is untrusted client input. In particular, `fileName` and
  * `relativePath` are not safe file-system paths, `mediaType` does not prove the content type, and
  * `sizeBytes` does not prove how many bytes an external destination received. Sanitize names,
  * choose server-generated storage paths, and inspect or otherwise verify uploaded content before
  * using it.
  *
  * @param fileName
  *   the file name reported by the browser
  * @param relativePath
  *   the browser-reported relative path, when directory selection supplies one
  * @param sizeBytes
  *   the browser-reported size in bytes
  * @param mediaType
  *   the browser-reported media type
  * @param lastModifiedMillis
  *   the browser-reported last-modified time in Unix epoch milliseconds
  * @param metadata
  *   additional browser-provided JSON metadata
  */
final class UploadClientMetadata private[scalive] (
  val fileName: String,
  val relativePath: Option[String],
  val sizeBytes: Long,
  val mediaType: String,
  val lastModifiedMillis: Option[Long],
  val metadata: Option[Json])

/** The state of an entry in its upload protocol lifecycle. */
enum LiveUploadEntryStatus:
  /** The browser selected the entry, but it has not completed a successful preflight. */
  case Selected

  /** The entry passed preflight and is ready to upload, with no positive progress reported yet. */
  case Preflighted

  /** The entry has positive upload progress.
    *
    * Hosted chunk receipt can compute this value, while browser progress messages can report it.
    * The percentage is normalized to the range 0 through 100. It is status information, not proof
    * that the destination contains valid or complete content.
    */
  case Uploading(progress: Int)

  /** The protocol reached 100 percent and produced a destination result.
    *
    * For external uploads, completion is reported by the browser and must be verified against the
    * external service before the result is trusted.
    */
  case Completed

  /** The entry is unusable because validation or its destination failed. */
  case Invalid(errors: List[LiveUploadError])

/** Immutable runtime snapshot of one selected upload entry.
  *
  * A snapshot can become stale as browser events advance the upload. Obtain the latest
  * [[LiveUpload]] from the upload context or use the value returned by an upload operation.
  *
  * @tparam Result
  *   the result produced by the configured upload destination
  * @param ref
  *   the entry's transient protocol identifier
  * @param client
  *   untrusted metadata reported by the browser
  * @param status
  *   the entry's status when this snapshot was created
  * @param metadata
  *   destination metadata, when available; for hosted uploads this is produced by the writer after
  *   completion, while for external uploads it is the client-visible preflight configuration
  */
final class LiveUploadEntry[Result] private[scalive] (
  val ref: UploadEntryRef,
  val client: UploadClientMetadata,
  val status: LiveUploadEntryStatus,
  val metadata: Option[Json.Obj],
  private[scalive] val uploadName: String):
  /** Returns the display progress for this snapshot.
    *
    * Uploading entries return their reported percentage, completed entries return 100, and all
    * other statuses return 0.
    */
  def progress: Int = status match
    case LiveUploadEntryStatus.Uploading(value) => value
    case LiveUploadEntryStatus.Completed        => 100
    case _                                      => 0

  /** Returns the entry errors when [[status]] is [[LiveUploadEntryStatus.Invalid]], or an empty
    * list otherwise.
    */
  def errors: List[LiveUploadError] = status match
    case LiveUploadEntryStatus.Invalid(values) => values
    case _                                     => Nil

/** Immutable runtime snapshot of an allowed upload and its current entries.
  *
  * The upload runtime owns the live state; this value is a point-in-time view intended for models
  * and rendering. Keep the snapshot returned by each upload operation or refresh it through the
  * upload context after progress events.
  *
  * @tparam Result
  *   the result produced by each completed destination entry
  * @param definition
  *   the reusable definition with which this upload was allowed
  * @param ref
  *   the transient reference used by the browser upload protocol
  * @param entries
  *   entry snapshots in their current browser selection order
  * @param errors
  *   upload-wide errors; entry-specific failures are available from [[LiveUploadEntry.errors]]
  */
final class LiveUpload[Result] private[scalive] (
  val definition: LiveUploadDef[Result],
  val ref: UploadRef,
  val entries: List[LiveUploadEntry[Result]],
  val errors: List[LiveUploadError]):
  /** Returns the public upload name from [[definition]]. */
  def name: String = definition.name

  /** Returns the accepted file policy from [[definition]]. */
  def accept: LiveUploadAccept = definition.accept

  /** Returns the configured accepted-entry limit from [[definition]]. */
  def maxEntries: Int = definition.maxEntries

  /** Returns the per-entry byte limit from [[definition]]. */
  def maxFileSize: Long = definition.maxFileSize

  /** Returns the requested client chunk size from [[definition]]. */
  def chunkSize: Int = definition.chunkSize

  /** Returns the client chunk timeout from [[definition]]. */
  def chunkTimeout: Duration = definition.chunkTimeout

  /** Returns whether the browser starts valid entries automatically. */
  def autoUpload: Boolean = definition.autoUpload

  /** Returns whether bytes are sent directly from the browser to an external destination. */
  def external: Boolean = definition.destination.external

/** A completed destination result supplied to a consume callback.
  *
  * The framework still owns `result` while the callback is running. Returning
  * [[ConsumeDecision.Consume]] removes the entry without calling destination cleanup and transfers
  * responsibility for the result to application code. Returning [[ConsumeDecision.Postpone]] leaves
  * the entry and result framework-owned, so a later cancellation, disallow, component removal, or
  * socket shutdown may discard it. If a consume callback fails, that entry remains tracked; batch
  * consumption is sequential rather than transactional, so entries consumed by earlier callbacks
  * remain consumed if a later callback fails.
  *
  * @tparam Result
  *   the destination result type
  * @param ref
  *   the completed entry's transient reference
  * @param client
  *   the untrusted metadata originally reported by the browser
  * @param result
  *   the completed hosted result or server-side external result
  * @param metadata
  *   writer metadata or external client configuration; empty when none was supplied
  */
final class CompletedUpload[Result] private[scalive] (
  val ref: UploadEntryRef,
  val client: UploadClientMetadata,
  val result: Result,
  val metadata: Json.Obj)

/** Decides whether a consume callback takes ownership of a completed upload result.
  *
  * In either case, `value` is returned as the callback's application-level output.
  */
enum ConsumeDecision[+A]:
  /** Removes the completed entry and transfers ownership of its destination result to the
    * application.
    *
    * Destination `discard` is not called. Complete any required persistence or verification before
    * returning this decision.
    */
  case Consume(value: A)

  /** Returns `value` but retains the completed entry under framework ownership for a later consume
    * attempt. Returning or retaining a reference to the destination result does not transfer its
    * ownership.
    */
  case Postpone(value: A)

/** Explains why the framework is abandoning an initialized hosted writer state. */
enum LiveUploadAbortReason:
  /** Application code cancelled the active entry. */
  case Cancelled

  /** Application code disallowed the upload definition. */
  case Disallowed

  /** The LiveComponent that owned the upload was removed. */
  case ComponentRemoved

  /** The owning LiveView socket shut down. */
  case SocketShutdown

  /** The entry failed while uploading; `reason` is a diagnostic protocol reason. */
  case Failed(reason: String)

/** Expected failure of an operation against the current upload runtime state. */
sealed abstract class LiveUploadOperationError(message: String) extends Exception(message)

/** Upload runtime operation failures. */
object LiveUploadOperationError:
  /** A new definition cannot replace `name` while the current upload still tracks entries,
    * including completed entries that have not been consumed.
    */
  final case class ActiveEntries(name: String)
      extends LiveUploadOperationError(s"Upload $name still has active entries")

  /** An operation used a definition whose destination identity differs from the allowed definition
    * with the same name.
    */
  final case class DefinitionMismatch(name: String)
      extends LiveUploadOperationError(s"Upload $name has a different definition")

  /** No matching upload named `name` is currently allowed. */
  final case class NotAllowed(name: String)
      extends LiveUploadOperationError(s"Upload $name is not allowed")

  /** The supplied entry snapshot no longer identifies an active entry. */
  final case class EntryNotActive(ref: UploadEntryRef)
      extends LiveUploadOperationError(s"Upload entry ${ref.value} is not active")

  /** A consume operation targeted an entry that is invalid or has not completed. */
  final case class EntryNotCompleted(ref: UploadEntryRef)
      extends LiveUploadOperationError(s"Upload entry ${ref.value} is not completed")

  /** Batch consumption was requested while at least one valid entry had not completed. */
  final case class EntriesInProgress(name: String)
      extends LiveUploadOperationError(s"Upload $name has entries in progress")

/** Stores chunks received by the Scalive server and produces a typed completed result.
  *
  * The framework threads the `State` returned by each successful method into the next lifecycle
  * step. Once [[complete]] succeeds, the state is replaced by `Result`. When the runtime releases a
  * resource it still tracks, state is cleaned with [[abort]] and a completed result with
  * [[discard]]. A failed method must release any side effects not represented by the last
  * successful state. Framework cleanup is best-effort and cannot run after abrupt process
  * termination, so writers must not rely on it as their only recovery mechanism.
  *
  * @tparam State
  *   private in-progress writer state
  * @tparam Result
  *   completed resource or value exposed to consume callbacks
  */
trait LiveUploadWriter[State, Result]:
  /** Initializes writer state when the browser joins the hosted upload channel.
    *
    * `client` is entirely client-controlled and must be validated before it influences storage or
    * authorization decisions.
    */
  def init(client: UploadClientMetadata): Task[State]

  /** Writes one received chunk and returns the state to use for the next chunk.
    *
    * Chunk boundaries are a transport detail and must not be used as a content or authorization
    * boundary. The writer remains responsible for its resource and content constraints.
    */
  def writeChunk(data: Chunk[Byte], state: State): Task[State]

  /** Finalizes an entry when the upload protocol considers it complete and returns its result.
    *
    * A browser progress message can request completion, so implementations should validate their
    * state rather than assuming client-reported size or progress proves that all expected content
    * was written.
    */
  def complete(state: State): Task[Result]

  /** Releases an initialized state that did not produce a completed result.
    *
    * This method is not called when initialization never produced a state. Cleanup failures are
    * logged and otherwise ignored by lifecycle cleanup. If [[writeChunk]] fails after acquiring
    * resources not represented by its input state, that failed call must release them itself.
    */
  def abort(state: State, reason: LiveUploadAbortReason): Task[Unit]

  /** Releases a completed result abandoned while it is still framework-owned.
    *
    * For results still tracked by the runtime, this includes cancellation, disallow, component
    * removal, and socket shutdown. It is not called after a consume callback returns
    * [[ConsumeDecision.Consume]]. Cleanup failures are logged and otherwise ignored by lifecycle
    * cleanup.
    */
  def discard(result: Result): Task[Unit]

  /** Produces application metadata stored with the completed result.
    *
    * The default is an empty object. Implementations should be pure and non-throwing because this
    * is evaluated immediately after [[complete]] succeeds.
    */
  def metadata(result: Result): Json.Obj =
    val _ = result
    Json.Obj.empty
end LiveUploadWriter

/** Client-visible JSON configuration for an external upload implementation.
  *
  * The object is sent verbatim to the browser during preflight and must contain a non-empty string
  * field named `uploader`. Do not include server secrets; include only credentials or signed values
  * intentionally granted to that browser for this upload.
  *
  * @param json
  *   the complete configuration sent to the browser
  */
final class ExternalUploadClientConfig private (val json: Json.Obj)

/** Constructors for validated external uploader client configuration. */
object ExternalUploadClientConfig:
  /** Creates a client configuration or throws when `json.uploader` is absent, empty, or not a
    * string.
    *
    * @throws IllegalArgumentException
    *   if `json` has no non-empty string `uploader` field
    */
  def apply(json: Json.Obj): ExternalUploadClientConfig =
    validated(json).fold(throw _, identity)

  /** Validates that `json` contains a non-empty string `uploader` field without throwing. */
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

/** Result of preparing one direct-to-external-service upload. */
enum LiveExternalUploadResult[+Result]:
  /** Allows the browser upload to start.
    *
    * `clientConfig` is sent to the browser. `result` remains server-side under framework ownership
    * and is exposed through [[CompletedUpload]] after the browser reports completion.
    */
  case Ready(clientConfig: ExternalUploadClientConfig, result: Result)

  /** Rejects preflight with structured error metadata.
    *
    * This metadata becomes [[LiveUploadError.External]] and may be sent to the browser, so it must
    * not contain secrets.
    */
  case Error(meta: Json.Obj)

/** Prepares direct browser uploads to an external service.
  *
  * Scalive does not receive or inspect the uploaded bytes. Browser metadata and completion progress
  * are untrusted, so consume callbacks should verify the external object, including authorization,
  * expected size, media type, and integrity as appropriate, before returning
  * [[ConsumeDecision.Consume]].
  *
  * @tparam Result
  *   the server-side handle retained for a prepared external upload
  */
trait LiveUploadExternalUploader[Result]:
  /** Authorizes and prepares one entry.
    *
    * A failed effect is reported as [[LiveUploadError.ExternalClientFailure]]. The supplied
    * metadata is client-controlled. If preparation reserves a resource but does not return
    * [[LiveExternalUploadResult.Ready]], it must release that resource itself because Scalive has
    * no `Result` to pass to [[discard]].
    */
  def preflight(client: UploadClientMetadata): Task[LiveExternalUploadResult[Result]]

  /** Releases a prepared result abandoned before application code consumes it.
    *
    * Override this when preflight reserves a temporary object, multipart upload, or other external
    * resource. It is not called after [[ConsumeDecision.Consume]]. The default performs no cleanup.
    */
  def discard(result: Result): Task[Unit] =
    val _ = result
    ZIO.unit

/** Receives snapshots after accepted browser upload progress reports.
  *
  * Progress is client-reported status, not a durable event or proof of external object integrity.
  * The callback is not invoked for every hosted server chunk.
  */
trait LiveUploadProgress[Result]:
  /** Handles the updated entry snapshot.
    *
    * The runtime state is updated before this callback runs; a failed effect fails the progress
    * operation but does not roll that update back.
    */
  def onProgress(entry: LiveUploadEntry[Result]): Task[Unit]

/** Selects where an upload's bytes are written and what completed result it produces.
  *
  * Destinations are created through [[LiveUploadDestination.inMemory]],
  * [[LiveUploadDestination.hosted]], or [[LiveUploadDestination.external]]. A destination instance
  * also supplies the stable runtime identity of a [[LiveUploadDef]].
  */
sealed trait LiveUploadDestination[Result]:
  private[scalive] def external: Boolean

/** Constructors for upload destinations. */
object LiveUploadDestination:
  /** Buffers every uploaded byte in server heap memory and returns it as one `Chunk[Byte]`.
    *
    * Bytes remain reachable until the entry is consumed or cleaned up, and chunk accumulation may
    * require additional intermediate allocations. Use this destination only for small, strictly
    * bounded uploads and modest concurrency. Prefer a streaming hosted writer or external uploader
    * for large or attacker-controlled workloads.
    */
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

  /** Creates a server-hosted destination backed by `writer`.
    *
    * The browser sends chunks to Scalive, which drives the writer lifecycle and retains its result
    * until application code consumes it or framework cleanup discards it.
    */
  def hosted[State, Result](
    writer: LiveUploadWriter[State, Result]
  ): LiveUploadDestination[Result] =
    Hosted(writer)

  /** Creates a direct-to-external-service destination backed by `uploader`.
    *
    * Scalive performs preflight and tracks the server-side result, but the browser transfers bytes
    * directly and reports completion.
    */
  def external[Result](
    uploader: LiveUploadExternalUploader[Result]
  ): LiveUploadDestination[Result] =
    External(uploader)

  /** Declarative server-hosted destination retaining the writer's state and result types. */
  final private[scalive] case class Hosted[State, Result](
    writer: LiveUploadWriter[State, Result])
      extends LiveUploadDestination[Result]:
    private[scalive] val external = false

  /** Declarative external destination retaining the uploader's result type. */
  final private[scalive] case class External[Result](uploader: LiveUploadExternalUploader[Result])
      extends LiveUploadDestination[Result]:
    private[scalive] val external = true
end LiveUploadDestination

/** Immutable definition of a named upload.
  *
  * Keep one stable definition value and reuse it with upload context operations. Runtime matching
  * uses the destination's identity rather than structural equality, so recreating an otherwise
  * identical definition may not match an upload that is already allowed.
  *
  * `name` must be non-empty; numeric limits must be positive; and `chunkTimeout` must be positive
  * and at most `Int.MaxValue` milliseconds. Eager factory methods throw on invalid values, while
  * [[LiveUploadDef.validated]] returns the validation error. Protocol encoding truncates fractional
  * milliseconds, so a positive timeout below one millisecond is sent as zero.
  *
  * @tparam Result
  *   the result produced by each completed entry
  * @param name
  *   stable upload name used by the form input and runtime; it is validated but not trimmed
  * @param accept
  *   browser selection and preflight policy, not a content-security check
  * @param maxEntries
  *   positive maximum number of entries accepted as valid during preflight, defaulting to 1; excess
  *   selections may remain visible as invalid entries
  * @param maxFileSize
  *   positive per-entry limit in bytes, defaulting to 8,000,000; initially checked against
  *   client-reported metadata
  * @param chunkSize
  *   positive requested browser chunk size in bytes, defaulting to 64,000
  * @param chunkTimeout
  *   positive browser chunk timeout, defaulting to 10 seconds and encoded with
  *   `chunkTimeout.toMillis.toInt`
  * @param autoUpload
  *   whether valid entries start uploading without a form submit, defaulting to `false`
  * @param progress
  *   optional callback for accepted browser progress reports, defaulting to none
  */
final class LiveUploadDef[Result] private (
  val name: String,
  val accept: LiveUploadAccept,
  val maxEntries: Int,
  val maxFileSize: Long,
  val chunkSize: Int,
  val chunkTimeout: Duration,
  val autoUpload: Boolean,
  val progress: Option[LiveUploadProgress[Result]],
  private[scalive] val destination: LiveUploadDestination[Result]):
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

/** Factories and validation for [[LiveUploadDef]]. */
object LiveUploadDef:
  /** Defines an upload buffered entirely in server memory.
    *
    * Defaults are one entry, an 8,000,000-byte per-entry limit, 64,000-byte chunks, a 10-second
    * chunk timeout, manual upload, and no progress callback. This factory has the resource caveats
    * of [[LiveUploadDestination.inMemory]].
    *
    * @throws IllegalArgumentException
    *   if `name` is empty, a numeric limit is not positive, or `chunkTimeout` is invalid
    */
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

  /** Defines an upload whose bytes pass through the Scalive server to `writer`.
    *
    * Defaults are one entry, an 8,000,000-byte per-entry limit, 64,000-byte chunks, a 10-second
    * chunk timeout, manual upload, and no progress callback.
    *
    * @throws IllegalArgumentException
    *   if `name` is empty, a numeric limit is not positive, or `chunkTimeout` is invalid
    */
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

  /** Defines an upload transferred directly from the browser through `uploader`.
    *
    * Defaults are one entry, an 8,000,000-byte browser-reported per-entry limit, 64,000-byte
    * chunks, a 10-second chunk timeout, manual upload, and no progress callback. The external
    * service and consume callback remain responsible for server-side validation of the resulting
    * object.
    *
    * @throws IllegalArgumentException
    *   if `name` is empty, a numeric limit is not positive, or `chunkTimeout` is invalid
    */
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

  /** Validates and creates a definition for an already selected destination.
    *
    * Validation rejects an empty `name`; non-positive `maxEntries`, `maxFileSize`, or `chunkSize`;
    * and a `chunkTimeout` that is non-positive or exceeds `Int.MaxValue` milliseconds. Positive
    * sub-millisecond durations pass validation and are truncated to zero on the protocol. Names and
    * other values are not normalized. Defaults match the eager factory methods.
    */
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
            destination
          )
        )
  end validated
end LiveUploadDef

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
