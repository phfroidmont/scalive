package scalive
package socket

import scala.util.control.NonFatal

import zio.*

import scalive.*
import scalive.streams.StreamRuntime

final private[scalive] class SocketStreamRuntime(
  streamRef: Ref[StreamRuntimeState])
    extends StreamRuntime:
  def stream[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt,
    reset: Boolean,
    limit: Option[StreamLimit]
  ): Task[LiveStream[A]] =
    for
      _         <- validateName(definition.name)
      atWire    <- normalizeAt(at)
      limitWire <- normalizeLimit(limit)
      next      <- streamRef.modify { current =>
                current.streams.get(definition.name) match
                  case Some(existing) =>
                    val base =
                      if reset then
                        existing.copy(
                          inserts = Nil,
                          deleteIds = Nil,
                          reset = true,
                          entries = Vector.empty
                        )
                      else existing
                    collectStreamInserts(
                      definition = definition,
                      items = items,
                      at = atWire,
                      limit = limitWire,
                      updateOnly = None
                    ) match
                      case Left(error)       => Left(error) -> current
                      case Right(newInserts) =>
                        val dedupedInserts = dedupeInserts(newInserts)
                        val updated        = base.copy(
                          inserts = newInserts ++ base.inserts,
                          entries = applyInserts(base.entries, dedupedInserts)
                        )
                        toLiveStream(updated, definition) -> current.copy(
                          streams = current.streams.updated(definition.name, updated)
                        )

                  case None =>
                    val ref = current.nextRef.toString
                    collectStreamInserts(
                      definition = definition,
                      items = items,
                      at = -1,
                      limit = limitWire,
                      updateOnly = None
                    ) match
                      case Left(error)       => Left(error) -> current
                      case Right(newInserts) =>
                        val dedupedInserts = dedupeInserts(newInserts)
                        val state          = StreamState(
                          name = definition.name,
                          ref = ref,
                          inserts = newInserts,
                          deleteIds = Nil,
                          reset = false,
                          entries = applyInserts(Vector.empty, dedupedInserts)
                        )
                        toLiveStream(state, definition) ->
                          current.copy(
                            streams = current.streams.updated(definition.name, state),
                            nextRef = current.nextRef + 1
                          )
              }
      out <- ZIO.fromEither(next)
    yield out

  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt,
    limit: Option[StreamLimit],
    updateOnly: Boolean
  ): Task[LiveStream[A]] =
    for
      _         <- validateName(definition.name)
      atWire    <- normalizeAt(at)
      limitWire <- normalizeLimit(limit)
      next      <- streamRef.modify(current =>
                updateExistingStream(current, definition) { existing =>
                  safeDomId(definition.name)(definition.domId(item)).map { domId =>
                    val insert = StreamInsertState(
                      domId = domId,
                      at = atWire,
                      item = StreamItem(item),
                      limit = limitWire,
                      updateOnly = Some(updateOnly)
                    )
                    existing.copy(
                      inserts = insert :: existing.inserts,
                      entries = applyInsert(existing.entries, insert)
                    )
                  }
                }
              )
      out <- ZIO.fromEither(next)
    yield out

  def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]] =
    for
      _    <- validateName(definition.name)
      next <- streamRef.modify(current =>
                updateExistingStream(current, definition) { existing =>
                  safeDomId(definition.name)(definition.domId(item)).map(domId =>
                    markDeleted(existing, domId)
                  )
                }
              )
      out <- ZIO.fromEither(next)
    yield out

  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]] =
    for
      _ <- validateName(definition.name)
      _ <-
        if domId.isEmpty then
          ZIO.fail(new IllegalArgumentException("Stream DOM id must not be empty"))
        else ZIO.unit
      next <- streamRef.modify(current =>
                updateExistingStream(current, definition) { existing =>
                  Right(markDeleted(existing, domId))
                }
              )
      out <- ZIO.fromEither(next)
    yield out

  def get[A](definition: LiveStreamDef[A]): UIO[Option[LiveStream[A]]] =
    streamRef.get.map(
      _.streams
        .get(definition.name)
        .flatMap(stream => toLiveStream(stream, definition).toOption)
    )

  private def validateName(name: String): Task[Unit] =
    if name.isEmpty then ZIO.fail(new IllegalArgumentException("Stream name must not be empty"))
    else ZIO.unit

  private def normalizeAt(at: StreamAt): Task[Int] =
    val wire = StreamAt.toWire(at)
    if wire == -1 || wire >= 0 then ZIO.succeed(wire)
    else ZIO.fail(new IllegalArgumentException("Stream index must be >= 0"))

  private def normalizeLimit(limit: Option[StreamLimit]): Task[Option[Int]] =
    limit match
      case None                                             => ZIO.none
      case Some(StreamLimit.KeepFirst(count)) if count <= 0 =>
        ZIO.fail(new IllegalArgumentException("Stream KeepFirst limit must be > 0"))
      case Some(StreamLimit.KeepLast(count)) if count <= 0 =>
        ZIO.fail(new IllegalArgumentException("Stream KeepLast limit must be > 0"))
      case Some(value) =>
        val wire = StreamLimit.toWire(value)
        if wire == 0 then ZIO.fail(new IllegalArgumentException("Stream limit must not be 0"))
        else if wire == Int.MinValue then
          ZIO.fail(new IllegalArgumentException("Stream limit is out of range"))
        else ZIO.succeed(Some(wire))

  private def safeDomId(name: String)(evaluate: => String): Either[Throwable, String] =
    try
      val domId = evaluate
      if domId.isEmpty then
        Left(new IllegalArgumentException(s"Stream $name generated an empty DOM id"))
      else Right(domId)
    catch case NonFatal(error) => Left(error)

  private def missingStreamError(name: String): Throwable =
    new IllegalArgumentException(s"No stream with name $name has been defined")

  private def updateExistingStream[A](
    current: StreamRuntimeState,
    definition: LiveStreamDef[A]
  )(
    f: StreamState => Either[Throwable, StreamState]
  ): (Either[Throwable, LiveStream[A]], StreamRuntimeState) =
    current.streams.get(definition.name) match
      case None =>
        Left(missingStreamError(definition.name)) -> current
      case Some(existing) =>
        f(existing) match
          case Left(error) =>
            Left(error) -> current
          case Right(updated) =>
            toLiveStream(updated, definition) -> current.copy(
              streams = current.streams.updated(definition.name, updated)
            )

  private def markDeleted(stream: StreamState, domId: String): StreamState =
    stream.copy(
      deleteIds = domId :: stream.deleteIds,
      entries = stream.entries.filterNot(_.domId == domId)
    )

  private def collectStreamInserts[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: Int,
    limit: Option[Int],
    updateOnly: Option[Boolean]
  ): Either[Throwable, List[StreamInsertState]] =
    collectInserts(items, at, limit, updateOnly)(item =>
      safeDomId(definition.name)(definition.domId(item))
    )

  private def collectInserts[A](
    items: Iterable[A],
    at: Int,
    limit: Option[Int],
    updateOnly: Option[Boolean]
  )(
    domIdFor: A => Either[Throwable, String]
  ): Either[Throwable, List[StreamInsertState]] =
    items.foldLeft(Right(List.empty): Either[Throwable, List[StreamInsertState]]) { (acc, item) =>
      for
        inserts <- acc
        domId   <- domIdFor(item)
      yield StreamInsertState(
        domId = domId,
        at = at,
        item = StreamItem(item),
        limit = limit,
        updateOnly = updateOnly
      ) :: inserts
    }

  private def applyInserts(
    entries: Vector[StreamEntryState],
    inserts: List[StreamInsertState]
  ): Vector[StreamEntryState] =
    inserts.foldLeft(entries)((acc, insert) => applyInsert(acc, insert))

  private def applyInsert(
    entries: Vector[StreamEntryState],
    insert: StreamInsertState
  ): Vector[StreamEntryState] =
    val updatedEntry = StreamEntryState(insert.domId, insert.item)
    entries.indexWhere(_.domId == insert.domId) match
      case existingIndex if existingIndex >= 0 =>
        applyLimit(entries.updated(existingIndex, updatedEntry), insert.limit)
      case _ if insert.updateOnly.contains(true) =>
        entries
      case _ =>
        applyLimit(insertEntry(entries, updatedEntry, insert.at), insert.limit)

  private def insertEntry(
    entries: Vector[StreamEntryState],
    entry: StreamEntryState,
    at: Int
  ): Vector[StreamEntryState] =
    if at == -1 || at >= entries.length then entries :+ entry
    else if at <= 0 then entry +: entries
    else entries.patch(at, Seq(entry), 0)

  private def applyLimit(
    entries: Vector[StreamEntryState],
    limit: Option[Int]
  ): Vector[StreamEntryState] =
    limit match
      case Some(value) if value > 0 && entries.length > value =>
        entries.take(value)
      case Some(value) if value < 0 && entries.length > -value =>
        entries.takeRight(-value)
      case _ =>
        entries

  private def toLiveStream[A](stream: StreamState, definition: LiveStreamDef[A])
    : Either[Throwable, LiveStream[A]] =
    val dedupedInserts = dedupeInserts(stream.inserts)
    val entries        = decodeEntries(
      dedupedInserts.map(insert => insert.domId -> insert.item),
      stream.name,
      definition
    )
    val snapshotEntries = decodeEntries(
      stream.entries.map(entry => entry.domId -> entry.item),
      stream.name,
      definition
    )

    for
      decodedEntries    <- entries
      decodedAllEntries <- snapshotEntries
    yield LiveStream(
      name = stream.name,
      entries = decodedEntries,
      snapshotEntries = decodedAllEntries,
      ref = stream.ref,
      inserts = dedupedInserts
        .map(insert =>
          _root_.scalive.streams.LiveStreamInsert(
            domId = insert.domId,
            at = insert.at,
            limit = insert.limit,
            updateOnly = insert.updateOnly
          )
        ).toVector,
      deleteIds = stream.deleteIds.toVector,
      reset = stream.reset
    )
  end toLiveStream

  private def decodeEntries[A](
    entries: Iterable[(String, StreamItem)],
    streamName: String,
    definition: LiveStreamDef[A]
  ): Either[Throwable, Vector[LiveStreamEntry[A]]] =
    entries.foldLeft(Right(Vector.empty): Either[Throwable, Vector[LiveStreamEntry[A]]]) {
      case (acc, (domId, item)) =>
        for
          decoded <- acc
          value   <- decodeItem(streamName, domId, item, definition)
        yield decoded :+ LiveStreamEntry(domId, value)
    }

  private def decodeItem[A](
    streamName: String,
    domId: String,
    item: StreamItem,
    definition: LiveStreamDef[A]
  ): Either[Throwable, A] =
    definition
      .decode(item.value).toRight(
        new IllegalStateException(
          s"Stream $streamName contains incompatible value for dom id $domId"
        )
      )

  private def dedupeInserts(inserts: List[StreamInsertState]): List[StreamInsertState] =
    inserts
      .foldLeft((List.empty[StreamInsertState], Set.empty[String])) { (acc, insert) =>
        val (entries, seen) = acc
        if seen.contains(insert.domId) then acc
        else (insert :: entries, seen + insert.domId)
      }._1
end SocketStreamRuntime

private[scalive] object SocketStreamRuntime:
  def scoped(runtime: StreamRuntime, scope: String): StreamRuntime =
    new ScopedStreamRuntime(runtime, scope)

  def removeComponentScopes(streamRef: Ref[StreamRuntimeState], cids: Set[Int]): UIO[Unit] =
    val prefixes = cids.map(componentScope)
    streamRef.update { current =>
      current.copy(streams = current.streams.filterNot { case (name, _) =>
        prefixes.exists(name.startsWith)
      })
    }.unit

  def componentScope(cid: Int): String = s"component:$cid:"

  def prune(streamRef: Ref[StreamRuntimeState]): UIO[Unit] =
    streamRef.update { current =>
      current.copy(
        streams = current.streams.view
          .mapValues(stream =>
            stream.copy(
              inserts = Nil,
              deleteIds = Nil,
              reset = false
            )
          )
          .toMap
      )
    }.unit

  final private class ScopedStreamRuntime(runtime: StreamRuntime, scope: String)
      extends StreamRuntime:
    def stream[A](
      definition: LiveStreamDef[A],
      items: Iterable[A],
      at: StreamAt,
      reset: Boolean,
      limit: Option[StreamLimit]
    ): Task[LiveStream[A]] =
      runtime.stream(scoped(definition), items, at, reset, limit).map(unscoped(_, definition))

    def insert[A](
      definition: LiveStreamDef[A],
      item: A,
      at: StreamAt,
      limit: Option[StreamLimit],
      updateOnly: Boolean
    ): Task[LiveStream[A]] =
      runtime.insert(scoped(definition), item, at, limit, updateOnly).map(unscoped(_, definition))

    def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]] =
      runtime.delete(scoped(definition), item).map(unscoped(_, definition))

    def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]] =
      runtime.deleteByDomId(scoped(definition), domId).map(unscoped(_, definition))

    def get[A](definition: LiveStreamDef[A]): UIO[Option[LiveStream[A]]] =
      runtime.get(scoped(definition)).map(_.map(unscoped(_, definition)))

    private def scoped[A](definition: LiveStreamDef[A]): LiveStreamDef[A] =
      definition.withName(scope + definition.name)

    private def unscoped[A](stream: LiveStream[A], definition: LiveStreamDef[A]): LiveStream[A] =
      stream.copy(name = definition.name)
  end ScopedStreamRuntime
end SocketStreamRuntime
