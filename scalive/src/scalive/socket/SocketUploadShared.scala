package scalive
package socket

import java.util.Base64
import scala.util.Random

import zio.*
import zio.json.ast.Json

import scalive.*

private[socket] object SocketUploadShared:
  private val UploadRefLength = 12

  def validateUploadOptions(
    name: String,
    options: LiveUploadOptions
  ): Task[LiveUploadOptions] =
    if name.isEmpty then ZIO.fail(new IllegalArgumentException("Upload name must not be empty"))
    else if options.maxEntries <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name maxEntries must be > 0"))
    else if options.maxFileSize <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name maxFileSize must be > 0"))
    else if options.chunkSize <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name chunkSize must be > 0"))
    else if options.chunkTimeout <= 0 then
      ZIO.fail(new IllegalArgumentException(s"Upload $name chunkTimeout must be > 0"))
    else
      options.accept match
        case LiveUploadAccept.Exactly(values) if values.isEmpty =>
          ZIO.fail(new IllegalArgumentException(s"Upload $name accept list must not be empty"))
        case _ =>
          ZIO.succeed(options)

  def randomUploadRef(length: Int = UploadRefLength): String =
    val bytes   = Random.nextBytes(length)
    val encoded = Base64
      .getUrlEncoder()
      .withoutPadding()
      .encodeToString(bytes)
    if encoded.length >= length then encoded.take(length)
    else encoded

  def buildLiveUpload(
    state: UploadRuntimeState,
    config: UploadConfigState
  ): LiveUpload =
    LiveUpload(
      name = config.name,
      ref = config.ref,
      accept = config.options.accept,
      maxEntries = config.options.maxEntries,
      maxFileSize = config.options.maxFileSize,
      chunkSize = config.options.chunkSize,
      chunkTimeout = config.options.chunkTimeout,
      autoUpload = config.options.autoUpload,
      external = config.options.external.nonEmpty,
      entries = config.entryOrder.flatMap(state.entries.get).map(toLiveUploadEntry).toList,
      errors = config.errors.map(_._2).map(LiveUploadError.fromJson)
    )

  def consumeEntry(
    uploadRef: Ref[UploadRuntimeState],
    entryRef: String
  ): UIO[Option[LiveUploadedEntry]] =
    uploadRef.modify { current =>
      current.entries.get(entryRef) match
        case Some(entry) if isUploadEntryDone(entry) && entry.valid =>
          val uploadedEntry = LiveUploadedEntry(
            ref = entry.ref,
            name = entry.name,
            contentType = entry.contentType,
            bytes = entry.bytes,
            meta = entry.externalMeta.orElse(entry.writerMeta).getOrElse(Json.Obj.empty)
          )
          Some(uploadedEntry) -> current.removeEntry(entryRef)
        case _ =>
          None -> current
    }

  def closeWriter(
    entry: UploadEntryState,
    reason: LiveUploadWriterCloseReason
  ): Task[UploadEntryState] =
    if entry.writerClosed then ZIO.succeed(entry)
    else
      entry.writerState match
        case Some(state) =>
          entry.writer
            .close(state, reason)
            .either
            .map {
              case Right(closedState) =>
                entry.copy(
                  writerState = Some(closedState),
                  writerMeta = Some(entry.writer.meta(closedState)),
                  writerClosed = true
                )
              case Left(_) =>
                entry.copy(writerClosed = true)
            }
        case None => ZIO.succeed(entry.copy(writerClosed = true))

  def ensureWriterState(entry: UploadEntryState): Task[UploadEntryState] =
    entry.writerState match
      case Some(_) => ZIO.succeed(entry)
      case None    =>
        entry.writer
          .init(entry.uploadName, toExternalUploadEntry(entry))
          .map(state => entry.copy(writerState = Some(state)))

  def toExternalUploadEntry(entry: UploadEntryState): LiveExternalUploadEntry =
    LiveExternalUploadEntry(
      ref = entry.ref,
      name = entry.name,
      relativePath = entry.relativePath,
      size = entry.size,
      contentType = entry.contentType,
      lastModified = entry.lastModified,
      clientMeta = entry.clientMeta
    )

  def isUploadEntryDone(entry: UploadEntryState): Boolean =
    entry.progress >= 100 || entry.bytes.length == entry.size

  def toLiveUploadEntry(entry: UploadEntryState): LiveUploadEntry =
    LiveUploadEntry(
      ref = entry.ref,
      clientName = entry.name,
      clientRelativePath = entry.relativePath,
      clientSize = entry.size,
      clientType = entry.contentType,
      clientLastModified = entry.lastModified,
      progress = entry.progress,
      preflighted = entry.preflighted,
      done = isUploadEntryDone(entry),
      cancelled = false,
      valid = entry.valid && entry.errors.isEmpty,
      errors = entry.errors.map(LiveUploadError.fromJson),
      meta = entry.externalMeta.orElse(entry.writerMeta)
    )
end SocketUploadShared
