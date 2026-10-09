package uploads

import java.nio.file.{Files, Path, StandardOpenOption}

import zio.*

import scalive.*

// docs:start uploads-hosted-writer
final case class PendingFile(path: Path, expected: Long, written: Long)
final case class StoredTempFile(path: Path, bytes: Long)

val MaxBytes = 2L * 1024L * 1024L

val tempFileWriter = new LiveUploadWriter[PendingFile, StoredTempFile]:
  def init(client: UploadClientMetadata): Task[PendingFile] =
    ZIO.attemptBlocking {
      if client.sizeBytes > MaxBytes then
        throw new IllegalArgumentException("upload exceeds destination limit")
      val path = Files.createTempFile("scalive-upload-", ".pending")
      PendingFile(path, client.sizeBytes, 0L)
    }

  def writeChunk(data: Chunk[Byte], state: PendingFile): Task[PendingFile] =
    ZIO.attemptBlocking {
      val nextSize = state.written + data.length
      if nextSize > MaxBytes then
        throw new IllegalArgumentException("upload exceeds destination limit")
      val _ = Files.write(state.path, data.toArray, StandardOpenOption.APPEND)
      state.copy(written = nextSize)
    }

  def complete(state: PendingFile): Task[StoredTempFile] =
    ZIO.attempt {
      if state.written != state.expected then
        throw new IllegalStateException("upload length mismatch")
      StoredTempFile(state.path, state.written)
    }

  def abort(state: PendingFile, reason: LiveUploadAbortReason): Task[Unit] =
    ZIO.attemptBlocking(Files.deleteIfExists(state.path)).unit

  def discard(result: StoredTempFile): Task[Unit] =
    ZIO.attemptBlocking(Files.deleteIfExists(result.path)).unit
// docs:end uploads-hosted-writer

// docs:start uploads-auto-upload
val Avatars = LiveUploadDef.hosted(
  name = "avatar",
  accept = LiveUploadAccept.only("image/jpeg", "image/png"),
  writer = tempFileWriter,
  maxFileSize = MaxBytes,
  autoUpload = true
)
// docs:end uploads-auto-upload

// docs:start uploads-progress-hook
trait UploadMetrics:
  def record(entry: UploadEntryRef, progress: Int): UIO[Unit]

final class RecordProgress(metrics: UploadMetrics) extends LiveUploadProgress[StoredTempFile]:
  def onProgress(entry: LiveUploadEntry[StoredTempFile]): Task[Unit] =
    metrics.record(entry.ref, entry.progress)

def trackedAvatars(metrics: UploadMetrics) = LiveUploadDef.hosted(
  name = "avatar",
  accept = LiveUploadAccept.only("image/jpeg", "image/png"),
  writer = tempFileWriter,
  maxFileSize = MaxBytes,
  progress = Some(RecordProgress(metrics))
)
// docs:end uploads-progress-hook
