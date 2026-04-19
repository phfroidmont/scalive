package scalive
package socket

import zio.*
import zio.json.ast.Json

import scalive.*
import scalive.upload.UploadRuntime

final private[scalive] class SocketUploadRuntime(
  uploadRef: Ref[UploadRuntimeState])
    extends UploadRuntime:
  def allow(name: String, options: LiveUploadOptions): Task[LiveUpload] =
    for
      validatedOptions <- SocketUploadProtocol.validateUploadOptions(name, options)
      ref              <- ZIO.succeed(SocketUploadProtocol.randomUploadRef())
      result           <- uploadRef.modify { current =>
                  current.configs.get(name) match
                    case Some(existing) if existing.entryOrder.nonEmpty =>
                      Left(
                        new IllegalArgumentException(
                          s"Upload $name still has active entries"
                        )
                      ) -> current
                    case _ =>
                      val base   = current.removeUploadByName(name)
                      val config = UploadConfigState(
                        name = name,
                        ref = ref,
                        options = validatedOptions
                      )
                      val next = base.copy(
                        configs = base.configs.updated(name, config),
                        refsToNames = base.refsToNames.updated(ref, name)
                      )
                      Right(SocketUploadProtocol.buildLiveUpload(next, config)) -> next
                }
      upload <- ZIO.fromEither(result)
    yield upload

  def disallow(name: String): Task[Unit] =
    uploadRef
      .modify { current =>
        current.configs.get(name) match
          case Some(config) if config.entryOrder.nonEmpty =>
            Left(
              new IllegalArgumentException(
                s"Upload $name still has active entries"
              )
            ) ->
              current
          case Some(_) =>
            Right(()) -> current.removeUploadByName(name)
          case None =>
            Left(
              new IllegalArgumentException(s"Upload $name is not allowed")
            ) -> current
      }
      .flatMap(ZIO.fromEither(_))

  def get(name: String): UIO[Option[LiveUpload]] =
    uploadRef.get.map(state =>
      state.configs.get(name).map(config => SocketUploadProtocol.buildLiveUpload(state, config))
    )

  def cancel(name: String, entryRef: String): Task[Unit] =
    for
      state <- uploadRef.get
      entry <- ZIO
                 .fromOption(state.entries.get(entryRef))
                 .orElseFail(
                   new IllegalArgumentException(
                     s"No upload entry found for ref $entryRef"
                   )
                 )
      _ <-
        if entry.uploadName != name then
          ZIO.fail(
            new IllegalArgumentException(
              s"Upload entry $entryRef does not belong to upload $name"
            )
          )
        else
          SocketUploadProtocol.closeWriter(entry, LiveUploadWriterCloseReason.Cancel).ignore *>
            uploadRef.update { current =>
              val removed = current.removeEntry(entryRef)
              removed.configs.get(name) match
                case Some(config) =>
                  val nextErrors =
                    Option
                      .when(
                        config.entryOrder.length > config.options.maxEntries
                      )(
                        config.ref -> Json.Str("too_many_files")
                      )
                      .toList
                  val nextConfig = config.copy(
                    cancelledRefs = config.cancelledRefs + entryRef,
                    errors = nextErrors
                  )
                  removed.copy(
                    configs = removed.configs.updated(
                      name,
                      nextConfig
                    )
                  )
                case None => removed
            }.unit
    yield ()

  def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]] =
    for
      state <- uploadRef.get
      refs = state.configs
               .get(name)
               .map(_.entryOrder)
               .getOrElse(Vector.empty)
               .filter(ref => state.entries.get(ref).exists(SocketUploadProtocol.isUploadEntryDone))
      consumed <- ZIO.foreach(refs)(SocketUploadProtocol.consumeEntry(uploadRef, _))
    yield consumed.flatten.toList

  def consume(entryRef: String): UIO[Option[LiveUploadedEntry]] =
    SocketUploadProtocol.consumeEntry(uploadRef, entryRef)

  def drop(entryRef: String): UIO[Unit] =
    for
      state <- uploadRef.get
      _     <- state.entries.get(entryRef) match
             case Some(entry) =>
               SocketUploadProtocol.closeWriter(entry, LiveUploadWriterCloseReason.Cancel).ignore
             case None => ZIO.unit
      _ <- uploadRef.update(_.removeEntry(entryRef)).unit
    yield ()
end SocketUploadRuntime
