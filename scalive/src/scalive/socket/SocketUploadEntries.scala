package scalive
package socket

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
      bytesReceived = 0L,
      progress = 0,
      preflighted = preflighted,
      valid = valid,
      errors = errors.map(SocketUploadValidation.errorJson),
      externalMeta = None,
      destination = config.definition.destination,
      destinationState = None,
      completedResult = None,
      resultMeta = None
    )

  def withEntryErrors(
    entry: UploadEntryState,
    errors: List[LiveUploadError]
  ): UploadEntryState =
    entry.copy(valid = false, errors = errors.map(SocketUploadValidation.errorJson))

end SocketUploadEntries
