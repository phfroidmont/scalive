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
                    val base = if reset then existing.copy(reset = true) else existing
                    collectInserts(items, atWire, limitWire, updateOnly = None)(item =>
                      safeDomId(definition.name)(existing.domId(item))
                    ) match
                      case Left(error)       => Left(error) -> current
                      case Right(newInserts) =>
                        val updated = base.copy(inserts = newInserts ++ base.inserts)
                        Right(toLiveStream[A](updated)) ->
                          current.copy(streams = current.streams.updated(definition.name, updated))

                  case None =>
                    val ref = current.nextRef.toString
                    collectInserts(items, at = -1, limit = limitWire, updateOnly = None)(item =>
                      safeDomId(definition.name)(definition.domId(item))
                    ) match
                      case Left(error)       => Left(error) -> current
                      case Right(newInserts) =>
                        val state = StreamState(
                          name = definition.name,
                          ref = ref,
                          domId = value => definition.domId(value.asInstanceOf[A]),
                          inserts = newInserts,
                          deleteIds = Nil,
                          reset = false
                        )
                        Right(toLiveStream[A](state)) ->
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
      next      <- streamRef.modify { current =>
                current.streams.get(definition.name) match
                  case None =>
                    Left(
                      new IllegalArgumentException(
                        s"No stream with name ${definition.name} has been defined"
                      )
                    ) -> current
                  case Some(existing) =>
                    safeDomId(definition.name)(existing.domId(item)) match
                      case Left(error)  => Left(error) -> current
                      case Right(domId) =>
                        val insert = StreamInsertState(
                          domId = domId,
                          at = atWire,
                          item = item,
                          limit = limitWire,
                          updateOnly = Some(updateOnly)
                        )
                        val updated = existing.copy(inserts = insert :: existing.inserts)
                        Right(toLiveStream[A](updated)) ->
                          current.copy(streams = current.streams.updated(definition.name, updated))
              }
      out <- ZIO.fromEither(next)
    yield out

  def delete[A](definition: LiveStreamDef[A], item: A): Task[LiveStream[A]] =
    for
      _    <- validateName(definition.name)
      next <- streamRef.modify { current =>
                current.streams.get(definition.name) match
                  case None =>
                    Left(
                      new IllegalArgumentException(
                        s"No stream with name ${definition.name} has been defined"
                      )
                    ) -> current
                  case Some(existing) =>
                    safeDomId(definition.name)(existing.domId(item)) match
                      case Left(error)  => Left(error) -> current
                      case Right(domId) =>
                        val updated = existing.copy(deleteIds = domId :: existing.deleteIds)
                        Right(toLiveStream[A](updated)) ->
                          current.copy(streams = current.streams.updated(definition.name, updated))
              }
      out <- ZIO.fromEither(next)
    yield out

  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Task[LiveStream[A]] =
    for
      _ <- validateName(definition.name)
      _ <-
        if domId.isEmpty then
          ZIO.fail(new IllegalArgumentException("Stream DOM id must not be empty"))
        else ZIO.unit
      next <- streamRef.modify { current =>
                current.streams.get(definition.name) match
                  case None =>
                    Left(
                      new IllegalArgumentException(
                        s"No stream with name ${definition.name} has been defined"
                      )
                    ) -> current
                  case Some(existing) =>
                    val updated = existing.copy(deleteIds = domId :: existing.deleteIds)
                    Right(toLiveStream[A](updated)) ->
                      current.copy(streams = current.streams.updated(definition.name, updated))
              }
      out <- ZIO.fromEither(next)
    yield out

  def get[A](definition: LiveStreamDef[A]): UIO[Option[LiveStream[A]]] =
    streamRef.get.map(_.streams.get(definition.name).map(stream => toLiveStream[A](stream)))

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
        item = item,
        limit = limit,
        updateOnly = updateOnly
      ) :: inserts
    }

  private def toLiveStream[A](stream: StreamState): LiveStream[A] =
    val dedupedInserts = dedupeInserts(stream.inserts)

    LiveStream(
      name = stream.name,
      entries = dedupedInserts
        .map(insert => LiveStreamEntry(insert.domId, insert.item.asInstanceOf[A])).toVector,
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

  private def dedupeInserts(inserts: List[StreamInsertState]): List[StreamInsertState] =
    inserts
      .foldLeft((List.empty[StreamInsertState], Set.empty[String])) { (acc, insert) =>
        val (entries, seen) = acc
        if seen.contains(insert.domId) then acc
        else (insert :: entries, seen + insert.domId)
      }._1
end SocketStreamRuntime

private[scalive] object SocketStreamRuntime:
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
