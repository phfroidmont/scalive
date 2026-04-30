package scalive
package socket

import zio.Chunk
import zio.json.ast.Json

private[scalive] object SocketUploadEntries:
  def buildUploadEntryState(
    config: UploadConfigState,
    uploadRef: String,
    entry: WebSocketMessage.UploadPreflightEntry,
    preflighted: Boolean,
    valid: Boolean,
    errors: List[LiveUploadError]
  ): UploadEntryState =
    UploadEntryState(
      uploadName = config.name,
      uploadRef = uploadRef,
      ref = entry.ref,
      name = entry.name,
      contentType = entry.`type`,
      size = entry.size,
      relativePath = entry.relative_path,
      lastModified = entry.last_modified,
      clientMeta = entry.meta,
      token = None,
      joined = false,
      bytes = Chunk.empty,
      progress = 0,
      preflighted = preflighted,
      valid = valid,
      errors = errors.map(SocketUploadValidation.errorJson),
      externalMeta = None,
      writer = config.options.writer,
      writerState = None,
      writerMeta = None,
      writerClosed = false
    )

  def withEntryErrors(
    entry: UploadEntryState,
    errors: List[LiveUploadError]
  ): UploadEntryState =
    entry.copy(valid = false, errors = errors.map(SocketUploadValidation.errorJson))

  def hasExternalUploader(meta: Json.Obj): Boolean =
    meta.fields.exists {
      case ("uploader", Json.Str(value)) => value.nonEmpty
      case _                             => false
    }
end SocketUploadEntries
