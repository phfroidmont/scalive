package scalive
package socket

import zio.json.ast.Json

private[scalive] object SocketUploadValidation:
  def validationErrorsByEntry(
    entries: List[WebSocketMessage.UploadPreflightEntry],
    options: LiveUploadOptions
  ): Map[String, List[LiveUploadError]] =
    validateUploadEntries(entries, options).groupMap(_._1)(_._2)

  def errorJson(error: LiveUploadError): Json =
    LiveUploadError.toJson(error)

  val WriterErrorReason = "writer_error"
  val WriterError       = LiveUploadError.WriterFailure(WriterErrorReason)

  private def validateUploadEntries(
    entries: List[WebSocketMessage.UploadPreflightEntry],
    options: LiveUploadOptions
  ): List[(String, LiveUploadError)] =
    entries.zipWithIndex.flatMap { case (entry, index) =>
      if index >= options.maxEntries then Some(entry.ref -> LiveUploadError.TooManyFiles)
      else if entry.size > options.maxFileSize then Some(entry.ref -> LiveUploadError.TooLarge)
      else if !isAcceptedUploadEntry(entry, options.accept) then
        Some(entry.ref -> LiveUploadError.NotAccepted)
      else None
    }

  private def isAcceptedUploadEntry(
    entry: WebSocketMessage.UploadPreflightEntry,
    accept: LiveUploadAccept
  ): Boolean =
    accept match
      case LiveUploadAccept.Any              => true
      case LiveUploadAccept.Exactly(filters) =>
        val normalizedName = entry.name.toLowerCase
        val normalizedType = entry.`type`.toLowerCase
        filters.exists { filter =>
          val normalizedFilter = filter.toLowerCase
          if normalizedFilter.startsWith(".") then normalizedName.endsWith(normalizedFilter)
          else if normalizedFilter.endsWith("/*") then
            val prefix = normalizedFilter.dropRight(1)
            normalizedType.startsWith(prefix)
          else normalizedType == normalizedFilter
        }
end SocketUploadValidation
