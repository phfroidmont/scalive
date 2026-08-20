package scalive.runtime.connection

import scalive.render.StreamRequirement
import scalive.streams.*

/** Immutable managed-stream state for one LiveView or LiveComponent owner. */
final private[scalive] case class StreamStore private (
  private val streams: Map[String, StreamStore.Stored]):
  import StreamStore.*

  def get[A](definition: LiveStreamDef[A]): Option[LiveStream[A]] =
    validateDefinition(definition)
    streams.get(definition.name).map { stored =>
      requireCoherent(stored, definition)
      stored.stream.asInstanceOf[LiveStream[A]]
    }

  def create[A](definition: LiveStreamDef[A], items: Iterable[A]): Replacement[A] =
    validateDefinition(definition)
    if streams.contains(definition.name) then
      throw IllegalArgumentException(s"stream '${definition.name}' already exists")

    val prepared = prepare(definition, items)
    val initial  = LiveStream(
      LiveStreamIdentity.fresh(),
      definition.name,
      generation = 1L,
      entries = Vector.empty,
      inserted = Vector.empty,
      deleted = Vector.empty,
      reset = false
    )
    install(
      definition,
      applyInsertions(initial, prepared, StreamAt.Last, definition.limit, updateOnly = false)
    )

  def insertAll[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Replacement[A] =
    validateOperation(definition, at)
    val current  = requireCurrent(definition)
    val prepared = prepare(definition, items)
    install(
      definition,
      applyInsertions(next(current), prepared, at, definition.limit, updateOnly = false)
    )

  def reset[A](
    definition: LiveStreamDef[A],
    items: Iterable[A],
    at: StreamAt = StreamAt.Last
  ): Replacement[A] =
    validateOperation(definition, at)
    val current  = requireCurrent(definition)
    val prepared = prepare(definition, items)
    val cleared  = LiveStream(
      current.identity,
      current.name,
      nextGeneration(current.generation),
      entries = Vector.empty,
      inserted = Vector.empty,
      deleted = Vector.empty,
      reset = true
    )
    install(
      definition,
      applyInsertions(cleared, prepared, at, definition.limit, updateOnly = false)
    )

  def insert[A](
    definition: LiveStreamDef[A],
    item: A,
    at: StreamAt = StreamAt.Last,
    updateOnly: Boolean = false
  ): Replacement[A] =
    validateOperation(definition, at)
    val current = requireCurrent(definition)
    val entry   = prepare(definition, List(item)).head
    val base    = next(current)
    val updated =
      if updateOnly && !current.entries.exists(_.domId == entry.domId) then base
      else applyInsertion(base, entry, at, definition.limit, updateOnly)
    install(definition, updated)

  def delete[A](definition: LiveStreamDef[A], item: A): Replacement[A] =
    validateDefinition(definition)
    val domId = evaluateDomId(definition, item)
    deletePrepared(definition, domId)

  def deleteByDomId[A](definition: LiveStreamDef[A], domId: String): Replacement[A] =
    validateDefinition(definition)
    validateDomId(domId)
    deletePrepared(definition, domId)

  /** Clears all operation journals after a successful render commit. */
  def prune: StreamStore =
    val pruned = streams.map { case (name, stored) =>
      name -> stored.copy(stream = pruneHandle(stored.stream))
    }
    StreamStore(pruned)

  /** Clears one stream's journal and returns its replacement rendering handle. */
  def prune[A](definition: LiveStreamDef[A]): Replacement[A] =
    validateDefinition(definition)
    val current = requireCurrent(definition)
    install(definition, pruneHandle(current))

  /** Rejects stale handles and handles created by another root or component lifecycle. */
  def validate(requirements: Vector[StreamRequirement[?]]): Unit =
    requirements.foreach { requirement =>
      val rendered = requirement.stream
      streams.get(rendered.name) match
        case None =>
          throw IllegalArgumentException(
            s"rendered stream '${rendered.name}' does not belong to this lifecycle"
          )
        case Some(stored) =>
          val current = stored.stream
          if !(current.identity eq rendered.identity) then
            throw IllegalArgumentException(
              s"rendered stream '${rendered.name}' does not belong to this lifecycle"
            )
          if current.generation != rendered.generation then
            throw IllegalArgumentException(
              s"rendered stream '${rendered.name}' is stale: expected generation ${current.generation}, got ${rendered.generation}"
            )
    }

  private def deletePrepared[A](definition: LiveStreamDef[A], domId: String): Replacement[A] =
    val current = requireCurrent(definition)
    val updated = LiveStream(
      current.identity,
      current.name,
      nextGeneration(current.generation),
      current.entries.filterNot(_.domId == domId),
      current.inserted.filterNot(_.entry.domId == domId),
      current.deleted.filterNot(_ == domId) :+ domId,
      current.reset
    )
    install(definition, updated)

  private def requireCurrent[A](definition: LiveStreamDef[A]): LiveStream[A] =
    streams.get(definition.name) match
      case None =>
        throw new java.util.NoSuchElementException(
          s"stream '${definition.name}' does not exist"
        )
      case Some(stored) =>
        requireCoherent(stored, definition)
        stored.stream.asInstanceOf[LiveStream[A]]

  private def install[A](definition: LiveStreamDef[A], stream: LiveStream[A]): Replacement[A] =
    val updated = StreamStore(streams.updated(definition.name, Stored(definition, stream)))
    Replacement(updated, stream)
end StreamStore

private[scalive] object StreamStore:
  final case class Replacement[A](store: StreamStore, stream: LiveStream[A])

  final private case class Stored(definition: LiveStreamDef[?], stream: LiveStream[?])

  val empty: StreamStore = StreamStore(Map.empty)

  private def validateDefinition[A](definition: LiveStreamDef[A]): Unit =
    if definition == null then throw NullPointerException("stream definition must not be null")
    if definition.name == null || definition.name.isEmpty then
      throw IllegalArgumentException("stream name must be non-empty")
    validateLimit(definition.limit)

  private def validateOperation[A](definition: LiveStreamDef[A], at: StreamAt): Unit =
    validateDefinition(definition)
    at match
      case null => throw NullPointerException("stream position must not be null")
      case StreamAt.Index(value) if value < 0 =>
        throw IllegalArgumentException(s"stream index must be non-negative: $value")
      case _ => ()

  private def validateLimit(limit: Option[StreamLimit]): Unit =
    if limit == null then throw NullPointerException("stream limit option must not be null")
    limit.foreach {
      case null => throw NullPointerException("stream limit must not be null")
      case StreamLimit.KeepFirst(count) if count <= 0 =>
        throw IllegalArgumentException(s"stream limit must be positive: $count")
      case StreamLimit.KeepLast(count) if count <= 0 =>
        throw IllegalArgumentException(s"stream limit must be positive: $count")
      case _ => ()
    }

  private def validateDomId(domId: String): Unit =
    if domId == null || domId.isEmpty then
      throw IllegalArgumentException("stream DOM id must be non-empty")

  private def evaluateDomId[A](definition: LiveStreamDef[A], item: A): String =
    val domId = definition.domId(item)
    validateDomId(domId)
    domId

  private def prepare[A](
    definition: LiveStreamDef[A],
    items: Iterable[A]
  ): Vector[LiveStreamEntry[A]] =
    if items == null then throw NullPointerException("stream items must not be null")
    items.iterator.map(item => LiveStreamEntry(evaluateDomId(definition, item), item)).toVector

  private def requireCoherent[A](stored: Stored, definition: LiveStreamDef[A]): Unit =
    if !(stored.definition.asInstanceOf[AnyRef] eq definition.asInstanceOf[AnyRef]) then
      throw IllegalArgumentException(
        s"stream '${definition.name}' must use the exact LiveStreamDef instance used to create it"
      )

  private def next[A](stream: LiveStream[A]): LiveStream[A] =
    LiveStream(
      stream.identity,
      stream.name,
      nextGeneration(stream.generation),
      stream.entries,
      stream.inserted,
      stream.deleted,
      stream.reset
    )

  private def nextGeneration(generation: Long): Long =
    if generation == Long.MaxValue then throw IllegalStateException("stream generation exhausted")
    generation + 1L

  private def applyInsertions[A](
    stream: LiveStream[A],
    entries: Vector[LiveStreamEntry[A]],
    at: StreamAt,
    limit: Option[StreamLimit],
    updateOnly: Boolean
  ): LiveStream[A] =
    entries.foldLeft(stream)((current, entry) =>
      applyInsertion(current, entry, at, limit, updateOnly)
    )

  private def applyInsertion[A](
    stream: LiveStream[A],
    entry: LiveStreamEntry[A],
    at: StreamAt,
    limit: Option[StreamLimit],
    updateOnly: Boolean
  ): LiveStream[A] =
    val existing        = stream.entries.indexWhere(_.domId == entry.domId)
    val insertedEntries =
      if existing >= 0 then stream.entries.updated(existing, entry)
      else
        at match
          case StreamAt.First        => entry +: stream.entries
          case StreamAt.Last         => stream.entries :+ entry
          case StreamAt.Index(value) => stream.entries.patch(value, Vector(entry), 0)
    val bounded   = applyLimit(insertedEntries, limit)
    val operation = LiveStreamInsert(entry, at, limit, updateOnly)
    LiveStream(
      stream.identity,
      stream.name,
      stream.generation,
      bounded,
      stream.inserted.filterNot(_.entry.domId == entry.domId) :+ operation,
      stream.deleted.filterNot(_ == entry.domId),
      stream.reset
    )

  private def applyLimit[A](
    entries: Vector[LiveStreamEntry[A]],
    limit: Option[StreamLimit]
  ): Vector[LiveStreamEntry[A]] =
    limit match
      case None                               => entries
      case Some(StreamLimit.KeepFirst(count)) => entries.take(count)
      case Some(StreamLimit.KeepLast(count))  => entries.takeRight(count)

  private def pruneHandle[A](stream: LiveStream[A]): LiveStream[A] =
    LiveStream(
      stream.identity,
      stream.name,
      stream.generation,
      stream.entries,
      inserted = Vector.empty,
      deleted = Vector.empty,
      reset = false
    )
end StreamStore
