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
      validatedOptions <- SocketUploadShared.validateUploadOptions(name, options)
      ref              <- ZIO.succeed(SocketUploadShared.randomUploadRef())
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
                      Right(SocketUploadShared.buildLiveUpload(next, config)) -> next
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
      state.configs.get(name).map(config => SocketUploadShared.buildLiveUpload(state, config))
    )

  def cancel(name: String, entryRef: String): Task[Unit] =
    for
      state <- uploadRef.get
      _     <- state.configs.get(name) match
             case None    => ZIO.fail(new IllegalArgumentException(s"Upload $name is not allowed"))
             case Some(_) =>
               state.entries.get(entryRef) match
                 case None                                    => ZIO.unit
                 case Some(entry) if entry.uploadName != name =>
                   ZIO.fail(
                     new IllegalArgumentException(
                       s"Upload entry $entryRef does not belong to upload $name"
                     )
                   )
                 case Some(entry) =>
                   SocketUploadShared
                     .closeWriter(entry, LiveUploadWriterCloseReason.Cancel).ignore *>
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
               .filter(ref => state.entries.get(ref).exists(SocketUploadShared.isUploadEntryDone))
      consumed <- ZIO.foreach(refs)(SocketUploadShared.consumeEntry(uploadRef, _))
    yield consumed.flatten.toList

  def consume(entryRef: String): UIO[Option[LiveUploadedEntry]] =
    SocketUploadShared.consumeEntry(uploadRef, entryRef)

  def drop(entryRef: String): UIO[Unit] =
    for
      state <- uploadRef.get
      _     <- state.entries.get(entryRef) match
             case Some(entry) =>
               SocketUploadShared.closeWriter(entry, LiveUploadWriterCloseReason.Cancel).ignore
             case None => ZIO.unit
      _ <- uploadRef.update(_.removeEntry(entryRef)).unit
    yield ()
end SocketUploadRuntime

private[scalive] object SocketUploadRuntime:
  def scoped(runtime: UploadRuntime, scope: String): UploadRuntime =
    new ScopedUploadRuntime(runtime, scope)

  def removeComponentScopes(uploadRef: Ref[UploadRuntimeState], cids: Set[Int]): UIO[Unit] =
    val prefixes = cids.map(SocketStreamRuntime.componentScope)
    uploadRef.update { current =>
      prefixes.foldLeft(current) { (state, prefix) =>
        state.configs.keysIterator
          .filter(_.startsWith(prefix))
          .foldLeft(state)((inner, name) => inner.removeUploadByName(name))
      }
    }

  final private class ScopedUploadRuntime(runtime: UploadRuntime, scope: String)
      extends UploadRuntime:
    def allow(name: String, options: LiveUploadOptions): Task[LiveUpload] =
      runtime.allow(scoped(name), options).map(unscoped)

    def disallow(name: String): Task[Unit] =
      runtime.disallow(scoped(name))

    def get(name: String): UIO[Option[LiveUpload]] =
      runtime.get(scoped(name)).map(_.map(unscoped))

    def cancel(name: String, entryRef: String): Task[Unit] =
      runtime.cancel(scoped(name), entryRef)

    def consumeCompleted(name: String): UIO[List[LiveUploadedEntry]] =
      runtime.consumeCompleted(scoped(name))

    def consume(entryRef: String): UIO[Option[LiveUploadedEntry]] =
      runtime.consume(entryRef)

    def drop(entryRef: String): UIO[Unit] =
      runtime.drop(entryRef)

    private def scoped(name: String): String = scope + name

    private def unscoped(upload: LiveUpload): LiveUpload =
      if upload.name.startsWith(scope) then upload.copy(name = upload.name.drop(scope.length))
      else upload
end SocketUploadRuntime
