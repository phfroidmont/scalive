package scalive
package socket

import zio.*

import scalive.*
import scalive.upload.UploadRuntime

final private[scalive] class SocketUploadRuntime(uploadRef: Ref[UploadRuntimeState])
    extends UploadRuntime:
  def allow[R](definition: LiveUploadDef[R]): Task[LiveUpload[R]] =
    for
      ref    <- ZIO.succeed(SocketUploadShared.randomUploadRef())
      result <- uploadRef.modify { current =>
                  current.configs.get(definition.name) match
                    case Some(existing) if existing.entryOrder.nonEmpty =>
                      Left(LiveUploadOperationError.ActiveEntries(definition.name)) -> current
                    case _ =>
                      val base   = current.removeUploadByName(definition.name)
                      val config = UploadConfigState(
                        name = definition.name,
                        ref = ref,
                        definition = definition
                      )
                      val next = base.copy(
                        configs = base.configs.updated(definition.name, config),
                        refsToNames = base.refsToNames.updated(ref, definition.name)
                      )
                      Right(SocketUploadShared.buildLiveUpload(next, config, definition)) -> next
                }
      upload <- ZIO.fromEither(result)
    yield upload

  def disallow[R](definition: LiveUploadDef[R]): Task[Unit] =
    for
      removed <- uploadRef.modify { current =>
                   current.configs.get(definition.name) match
                     case Some(config) if matches(config, definition) =>
                       val entries = config.entryOrder.flatMap(current.entries.get)
                       Right(entries) -> current.removeUploadByName(definition.name)
                     case Some(_) =>
                       Left(LiveUploadOperationError.DefinitionMismatch(definition.name)) -> current
                     case None =>
                       Left(LiveUploadOperationError.NotAllowed(definition.name)) -> current
                 }
      entries <- ZIO.fromEither(removed)
      _       <- SocketUploadShared.cleanupEntries(entries, LiveUploadAbortReason.Disallowed)
    yield ()

  def get[R](definition: LiveUploadDef[R]): UIO[Option[LiveUpload[R]]] =
    uploadRef.get.map { state =>
      state.configs.get(definition.name).collect {
        case config if matches(config, definition) =>
          SocketUploadShared.buildLiveUpload(state, config, definition)
      }
    }

  def cancel[R](entry: LiveUploadEntry[R]): Task[LiveUpload[R]] =
    for
      removed <- uploadRef.modify { current =>
                   current.configs.get(entry.uploadName) match
                     case None =>
                       Left(LiveUploadOperationError.NotAllowed(entry.uploadName)) -> current
                     case Some(config) =>
                       current.entries.get(entry.ref.value) match
                         case Some(found) if found.uploadName == entry.uploadName =>
                           val withoutEntry = current.removeEntry(entry.ref.value)
                           val nextConfig   = withoutEntry
                             .configs(entry.uploadName).copy(
                               cancelledRefs = config.cancelledRefs + entry.ref.value,
                               errors = Nil
                             )
                           val next = withoutEntry.copy(
                             configs = withoutEntry.configs.updated(entry.uploadName, nextConfig)
                           )
                           Right((found, next, nextConfig)) -> next
                         case _ =>
                           Left(LiveUploadOperationError.EntryNotActive(entry.ref)) -> current
                 }
      tuple <- ZIO.fromEither(removed)
      (removedEntry, next, config) = tuple
      _ <- SocketUploadShared.cleanupEntry(removedEntry, LiveUploadAbortReason.Cancelled)
    yield SocketUploadShared.buildLiveUpload(
      next,
      config,
      config.definition.asInstanceOf[LiveUploadDef[R]]
    )

  def consume[R, A](
    entry: LiveUploadEntry[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): Task[(A, LiveUpload[R])] =
    for
      current <- uploadRef.get
      config  <-
        ZIO
          .fromOption(current.configs.get(entry.uploadName))
          .orElseFail(LiveUploadOperationError.NotAllowed(entry.uploadName))
      stored <- ZIO
                  .fromOption(current.entries.get(entry.ref.value))
                  .orElseFail(LiveUploadOperationError.EntryNotActive(entry.ref))
      _ <- ZIO
             .fail(LiveUploadOperationError.EntryNotCompleted(entry.ref)).unless(
               stored.valid && SocketUploadShared.isUploadEntryDone(stored)
             )
      completed <- ZIO
                     .fromOption(SocketUploadShared.toCompletedUpload[R](stored))
                     .orElseFail(new IllegalStateException("Completed upload result is missing"))
      decision <- callback(completed)
      next     <- decision match
                case ConsumeDecision.Consume(_) => uploadRef.updateAndGet(_.removeEntry(stored.ref))
                case ConsumeDecision.Postpone(_) => ZIO.succeed(current)
      nextConfig <-
        ZIO
          .fromOption(next.configs.get(entry.uploadName))
          .orElseFail(new IllegalStateException("Upload was disallowed while consuming"))
      value = decision match
                case ConsumeDecision.Consume(result)  => result
                case ConsumeDecision.Postpone(result) => result
    yield value -> SocketUploadShared.buildLiveUpload(
      next,
      nextConfig,
      config.definition.asInstanceOf[LiveUploadDef[R]]
    )

  def consumeCompleted[R, A](
    definition: LiveUploadDef[R]
  )(
    callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
  ): Task[(List[A], LiveUpload[R])] =
    for
      current <- uploadRef.get
      config  <-
        ZIO
          .fromOption(current.configs.get(definition.name).filter(matches(_, definition)))
          .orElseFail(LiveUploadOperationError.NotAllowed(definition.name))
      entries    = config.entryOrder.flatMap(current.entries.get)
      inProgress =
        entries.exists(entry =>
          entry.valid && entry.errors.isEmpty && !SocketUploadShared.isUploadEntryDone(entry)
        )
      _ <-
        ZIO
          .fail(LiveUploadOperationError.EntriesInProgress(definition.name))
          .when(inProgress)
      completed =
        entries.filter(entry => entry.valid && SocketUploadShared.isUploadEntryDone(entry))
      values <- ZIO.foreach(completed) { entry =>
                  SocketUploadShared.toCompletedUpload[R](entry) match
                    case Some(value) =>
                      callback(value).flatMap {
                        case ConsumeDecision.Consume(result) =>
                          uploadRef.update(_.removeEntry(entry.ref)).as(result)
                        case ConsumeDecision.Postpone(result) => ZIO.succeed(result)
                      }
                    case None => ZIO.dieMessage("Completed upload result is missing")
                }
      next       <- uploadRef.get
      nextConfig <-
        ZIO
          .fromOption(next.configs.get(definition.name))
          .orElseFail(new IllegalStateException("Upload was disallowed while consuming"))
    yield values.toList -> SocketUploadShared.buildLiveUpload(next, nextConfig, definition)

  private def matches[R](config: UploadConfigState, definition: LiveUploadDef[R]): Boolean =
    config.definition.destination.eq(definition.destination)
end SocketUploadRuntime

private[scalive] object SocketUploadRuntime:
  def scoped(runtime: UploadRuntime, scope: String): UploadRuntime =
    new ScopedUploadRuntime(runtime, scope)

  def removeComponentScopes(uploadRef: Ref[UploadRuntimeState], cids: Set[Int]): UIO[Unit] =
    val prefixes = cids.map(SocketStreamRuntime.componentScope)
    for
      removed <- uploadRef.modify { current =>
                   val names = current.configs.keysIterator
                     .filter(name => prefixes.exists(name.startsWith))
                     .toSet
                   val entries = names.toList.flatMap(name =>
                     current.configs
                       .get(name).toList.flatMap(_.entryOrder.flatMap(current.entries.get))
                   )
                   entries -> names.foldLeft(current)(_.removeUploadByName(_))
                 }
      _ <- SocketUploadShared.cleanupEntries(removed, LiveUploadAbortReason.ComponentRemoved)
    yield ()

  def shutdown(uploadRef: Ref[UploadRuntimeState]): UIO[Unit] =
    for
      entries <- uploadRef.getAndSet(UploadRuntimeState.empty).map(_.entries.values)
      _       <- SocketUploadShared.cleanupEntries(entries, LiveUploadAbortReason.SocketShutdown)
    yield ()

  final private class ScopedUploadRuntime(runtime: UploadRuntime, scope: String)
      extends UploadRuntime:
    private def scoped[R](definition: LiveUploadDef[R]) =
      definition.withName(scope + definition.name)

    def allow[R](definition: LiveUploadDef[R]) =
      runtime.allow(scoped(definition)).map(unscoped(_, definition))
    def disallow[R](definition: LiveUploadDef[R]) = runtime.disallow(scoped(definition))
    def get[R](definition: LiveUploadDef[R])      =
      runtime.get(scoped(definition)).map(_.map(unscoped(_, definition)))
    def cancel[R](entry: LiveUploadEntry[R]) = runtime.cancel(entry).map(unscopedName)
    def consume[R, A](
      entry: LiveUploadEntry[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ) = runtime.consume(entry)(callback).map((value, upload) => value -> unscopedName(upload))
    def consumeCompleted[R, A](
      definition: LiveUploadDef[R]
    )(
      callback: CompletedUpload[R] => LiveIO[ConsumeDecision[A]]
    ) = runtime
      .consumeCompleted(scoped(definition))(callback)
      .map((values, upload) => values -> unscoped(upload, definition))

    private def unscoped[R](upload: LiveUpload[R], definition: LiveUploadDef[R]): LiveUpload[R] =
      new LiveUpload(
        definition,
        upload.ref,
        upload.entries,
        upload.errors
      )

    private def unscopedName[R](upload: LiveUpload[R]): LiveUpload[R] =
      new LiveUpload(
        upload.definition.withName(upload.name.stripPrefix(scope)),
        upload.ref,
        upload.entries,
        upload.errors
      )
  end ScopedUploadRuntime
end SocketUploadRuntime
