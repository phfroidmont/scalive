package scalive
package socket

import java.util.Base64
import scala.util.Random

import zio.*
import zio.json.ast.Json

import scalive.*

private[socket] object SocketUploadShared:
  private val UploadRefLength = 12

  def randomUploadRef(length: Int = UploadRefLength): String =
    val encoded = Base64
      .getUrlEncoder()
      .withoutPadding()
      .encodeToString(Random.nextBytes(length))
    encoded.take(length)

  def buildLiveUpload[R](
    state: UploadRuntimeState,
    config: UploadConfigState,
    definition: LiveUploadDef[R]
  ): LiveUpload[R] =
    new LiveUpload(
      definition = definition,
      ref = UploadRef(config.ref),
      entries = config.entryOrder.flatMap(state.entries.get).map(toLiveUploadEntry[R]).toList,
      errors = config.errors.map(_._2).map(LiveUploadError.fromJson)
    )

  def clientMetadata(entry: UploadEntryState): UploadClientMetadata =
    new UploadClientMetadata(
      fileName = entry.name,
      relativePath = entry.relativePath,
      sizeBytes = entry.size,
      mediaType = entry.contentType,
      lastModifiedMillis = entry.lastModified,
      metadata = entry.clientMeta
    )

  def ensureDestinationState(entry: UploadEntryState): Task[UploadEntryState] =
    entry.destinationState match
      case Some(_) => ZIO.succeed(entry)
      case None    =>
        entry.destination
          .init(clientMetadata(entry))
          .map(state => entry.copy(destinationState = Some(state)))

  def complete(entry: UploadEntryState): Task[UploadEntryState] =
    entry.completedResult match
      case Some(_) => ZIO.succeed(entry)
      case None    =>
        entry.destinationState match
          case Some(state) =>
            entry.destination.complete(state).map { result =>
              entry.copy(
                destinationState = None,
                completedResult = Some(result),
                resultMeta = Some(entry.destination.metadata(result))
              )
            }
          case None =>
            ZIO.fail(new IllegalStateException(s"Upload entry ${entry.ref} has no writer state"))

  def cleanupEntry(entry: UploadEntryState, reason: LiveUploadAbortReason): UIO[Unit] =
    val cleanup = entry.completedResult match
      case Some(result) => entry.destination.discard(result)
      case None         =>
        entry.destinationState match
          case Some(state) => entry.destination.abort(state, reason)
          case None        => ZIO.unit

    cleanup.catchAllCause(cause =>
      ZIO.logErrorCause(s"Upload cleanup failed for entry ${entry.ref}", cause)
    )

  def cleanupEntries(entries: Iterable[UploadEntryState], reason: LiveUploadAbortReason)
    : UIO[Unit] =
    ZIO.foreachDiscard(entries)(cleanupEntry(_, reason))

  def isUploadEntryDone(entry: UploadEntryState): Boolean =
    entry.progress >= 100 && entry.completedResult.nonEmpty

  def toLiveUploadEntry[R](entry: UploadEntryState): LiveUploadEntry[R] =
    val uploadErrors = entry.errors.map(LiveUploadError.fromJson)
    val status       =
      if uploadErrors.nonEmpty || !entry.valid then LiveUploadEntryStatus.Invalid(uploadErrors)
      else if isUploadEntryDone(entry) then LiveUploadEntryStatus.Completed
      else if entry.progress > 0 then LiveUploadEntryStatus.Uploading(entry.progress)
      else if entry.preflighted then LiveUploadEntryStatus.Preflighted
      else LiveUploadEntryStatus.Selected

    new LiveUploadEntry(
      ref = UploadEntryRef(entry.ref),
      client = clientMetadata(entry),
      status = status,
      metadata = entry.externalMeta.orElse(entry.resultMeta),
      uploadName = entry.uploadName
    )

  def toCompletedUpload[R](entry: UploadEntryState): Option[CompletedUpload[R]] =
    entry.completedResult.map(result =>
      new CompletedUpload(
        ref = UploadEntryRef(entry.ref),
        client = clientMetadata(entry),
        result = result.asInstanceOf[R],
        metadata = entry.resultMeta.orElse(entry.externalMeta).getOrElse(Json.Obj.empty)
      )
    )
end SocketUploadShared
