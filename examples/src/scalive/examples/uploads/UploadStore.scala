package scalive.examples.uploads

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import scala.jdk.CollectionConverters.*

import zio.*

import scalive.LiveUploadedEntry

trait UploadStore:
  def save(upload: LiveUploadedEntry): Task[UploadStore.Entry]
  def delete(storageId: String): Task[Unit]
  def entries: UIO[Vector[UploadStore.Entry]]

object UploadStore:
  final case class Entry(
    storageId: String,
    clientName: String,
    contentType: String,
    size: Long)

  val live: ZLayer[Any, Throwable, UploadStore] =
    ZLayer.scoped {
      for
        directory <- ZIO.acquireRelease(
                       ZIO.attemptBlocking(Files.createTempDirectory("scalive-documents-"))
                     )(directory =>
                       deleteRecursively(directory).catchAllCause(cause =>
                         ZIO.logErrorCause(
                           s"UploadStore operation=cleanup directory=$directory",
                           cause
                         )
                       )
                     )
        entriesRef <- Ref.make(Vector.empty[Entry])
      yield Live(directory, entriesRef)
    }

  final private case class Live(directory: Path, entriesRef: Ref[Vector[Entry]])
      extends UploadStore:
    def save(upload: LiveUploadedEntry): Task[Entry] =
      ZIO.uninterruptible {
        for
          pending <- ZIO.attemptBlocking(publish(upload))
          commit: Task[Entry] = entriesRef.update(_ :+ pending.entry).as(pending.entry)
          entry <- commit.catchAllCause(cause => cleanupUncommitted(pending, cause))
        yield entry
      }

    private def publish(upload: LiveUploadedEntry): PendingEntry =
      var storageId        = ""
      var finalPath        = directory
      var reservationOwned = false
      var stagingPath      = directory
      var stagingOwned     = false

      try
        while !reservationOwned do
          storageId = UUID.randomUUID().toString
          finalPath = directory.resolve(storageId)
          try
            val _ = Files.createFile(finalPath)
            reservationOwned = true
          catch case _: FileAlreadyExistsException => ()

        stagingPath = Files.createTempFile(directory, s".$storageId-", ".tmp")
        stagingOwned = true
        val _ = Files.write(
          stagingPath,
          upload.bytes.toArray,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
        try
          val _ = Files.move(
            stagingPath,
            finalPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
          )
        catch
          case _: AtomicMoveNotSupportedException =>
            val _ = Files.move(stagingPath, finalPath, StandardCopyOption.REPLACE_EXISTING)

        PendingEntry(
          entry = Entry(
            storageId = storageId,
            clientName = upload.name,
            contentType = upload.contentType,
            size = upload.bytes.length.toLong
          ),
          stagingPath = stagingPath,
          finalPath = finalPath
        )
      catch
        case error: Throwable =>
          if stagingOwned then deleteWithSuppressed(stagingPath, error)
          if reservationOwned then deleteWithSuppressed(finalPath, error)
          throw error
      end try
    end publish

    private def cleanupUncommitted(
      pending: PendingEntry,
      cause: Cause[Throwable]
    ): Task[Nothing] =
      cause.failureOption.orElse(cause.dieOption) match
        case Some(original) =>
          ZIO.attemptBlocking {
            deleteWithSuppressed(pending.stagingPath, original)
            deleteWithSuppressed(pending.finalPath, original)
          } *> ZIO.failCause(cause)
        case None =>
          ZIO
            .attemptBlocking {
              val _ = Files.deleteIfExists(pending.stagingPath)
              val _ = Files.deleteIfExists(pending.finalPath)
            }.foldCauseZIO(
              cleanupCause => ZIO.failCause(cause ++ cleanupCause),
              _ => ZIO.failCause(cause)
            )

    private def deleteWithSuppressed(path: Path, original: Throwable): Unit =
      try
        val _ = Files.deleteIfExists(path)
      catch case cleanupError: Throwable => original.addSuppressed(cleanupError)

    def delete(storageId: String): Task[Unit] =
      ZIO.uninterruptible {
        entriesRef.get.flatMap { current =>
          current.find(_.storageId == storageId) match
            case None        => ZIO.unit
            case Some(entry) =>
              ZIO.attemptBlocking {
                val _ = Files.deleteIfExists(directory.resolve(entry.storageId))
              } *> entriesRef.update(_.filterNot(_.storageId == entry.storageId)).unit
        }
      }

    def entries: UIO[Vector[Entry]] =
      entriesRef.get
  end Live

  final private case class PendingEntry(entry: Entry, stagingPath: Path, finalPath: Path)

  private def deleteRecursively(directory: Path): Task[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(directory) then
        val paths = Files.walk(directory)
        try
          paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            val _ = Files.deleteIfExists(path)
          }
        finally paths.close()
    }
end UploadStore
