package scalive.render

import java.time.Duration
import java.time.Instant
import scala.collection.mutable
import scala.util.control.NonFatal

import scalive.BindingPayload
import scalive.ComponentDispatch

/** Renderer-owned destination selected by one evaluated binding. */
sealed trait BindingDispatch[+OwnerMsg]

object BindingDispatch:
  final case class Owner[OwnerMsg](message: OwnerMsg)  extends BindingDispatch[OwnerMsg]
  final case class Routed(dispatch: ComponentDispatch) extends BindingDispatch[Nothing]

/** A typed browser-payload operation captured from one evaluated render snapshot. */
sealed trait BindingOperation[+Msg]:
  private[scalive] def dispatch(payload: BindingPayload): Either[Throwable, BindingDispatch[Msg]]

object BindingOperation:
  private[render] def apply[Msg](operation: BindingPayload => Msg): BindingOperation[Msg] =
    dispatching(payload => BindingDispatch.Owner(operation(payload)))

  private[render] def dispatching[Msg](
    operation: BindingPayload => BindingDispatch[Msg]
  ): BindingOperation[Msg] =
    new BindingOperation[Msg]:
      private[scalive] def dispatch(
        payload: BindingPayload
      ): Either[Throwable, BindingDispatch[Msg]] =
        try Right(operation(payload))
        catch case NonFatal(error) => Left(error)

/** Immutable event dispatch table corresponding exactly to an [[EvaluatedTree]].
  *
  * Lifecycle code resolves events against the committed table and, for a short while, against the
  * bindings earlier renders dropped: see [[resolveRetired]]. Construction rejects duplicate
  * identities rather than selecting one operation by map overwrite.
  *
  * A table is built from the one before it, so that the bindings of a collection or a row the
  * evaluation reused cost nothing: it records the bindings of each row of each top-level
  * collection, and which bindings of the table before it this one dropped.
  */
final class BindingTable[+Msg] private (
  private val entries: Map[BindingId, BindingOperation[Msg]],
  private val retired: Map[BindingId, BindingTable.Retired[Msg]],
  // The bindings outside every collection, and those of each row of each top-level collection.
  private val looseIds: Vector[BindingId],
  private val collections: Map[TemplateId, BindingTable.Collection[Msg]],
  // The table this one was built from, and the bindings of it that this one dropped.
  private val base: AnyRef,
  private val dropped: Vector[(BindingId, BindingOperation[Msg])]):
  def resolve(id: BindingId): Option[BindingOperation[Msg]] = entries.get(id)

  /** Resolves a binding that an earlier render had and a later one dropped less than
    * [[BindingTable.RetiredFor]] before `now`.
    *
    * The browser sends only bindings in its DOM, so an event naming a dropped binding left before
    * the browser applied the update that removed its element: it is what the visitor clicked on the
    * page they saw.
    */
  def resolveRetired(id: BindingId, now: Instant): Option[BindingOperation[Msg]] =
    retired.get(id).filter(_.isRecent(now)).map(_.operation)

  def size: Int = entries.size

  def isEmpty: Boolean = entries.isEmpty

  def ids: Vector[BindingId] = entries.keysIterator.toVector

  /** The bindings of each row of top-level collection `id`. */
  private[render] def rowGroups(id: TemplateId): BindingTable.Collection[Msg] =
    collections.getOrElse(id, Map.empty)

  /** This table, also retaining the bindings `previous` resolved and this one does not.
    *
    * Retired bindings are kept for [[BindingTable.RetiredFor]], at most
    * [[BindingTable.RetiredCapacity]] of them, newest first. A binding rendered again resolves to
    * its current operation.
    */
  private[scalive] def retiring[M >: Msg](previous: BindingTable[M], now: Instant)
    : BindingTable[M] =
    val droppedOperations =
      // A reused render keeps its table, which dropped nothing since.
      if previous eq this then Iterator.empty
      else if base eq previous then dropped.iterator
      else previous.entries.iterator.filterNot((id, _) => entries.contains(id))
    val newlyRetired =
      droppedOperations.map((id, operation) => id -> BindingTable.Retired(operation, now))
    val kept = previous.retired.iterator.filter((id, binding) =>
      !entries.contains(id) && binding.isRecent(now)
    )
    // `newlyRetired` comes last, so it replaces an older retirement of the same ID.
    val all    = (kept ++ newlyRetired).toMap
    val capped =
      if all.size <= BindingTable.RetiredCapacity then all
      else all.toVector.sortBy(_._2.at).takeRight(BindingTable.RetiredCapacity).toMap
    BindingTable(entries, capped, looseIds, collections, base, dropped)
end BindingTable

object BindingTable:
  /** How long a dropped binding still resolves. */
  val RetiredFor: Duration = Duration.ofSeconds(10)

  /** How many dropped bindings a table retains at most. */
  val RetiredCapacity: Int = 1000

  final private case class Retired[+Msg](operation: BindingOperation[Msg], at: Instant):
    def isRecent(now: Instant): Boolean = now.isBefore(at.plus(RetiredFor))

  val empty: BindingTable[Nothing] =
    BindingTable(Map.empty, Map.empty, Vector.empty, Map.empty, NoBase, Vector.empty)

  private object NoBase

  /** The bindings of one row, those of the collections it holds included. */
  private[render] type Row[+Msg] = Vector[(BindingId, BindingOperation[Msg])]

  /** The bindings of the rows of one collection that render any, by row. */
  private[render] type Collection[+Msg] = Map[RowId, Row[Msg]]

  /** The rows of collection `id` collected so far, from `prior`, its rows in the base table. */
  final private class Collecting[Msg](val id: TemplateId, val prior: Collection[Msg]):
    // The rows of `prior` this evaluation renders again, and their bindings now.
    val rendered              = mutable.ArrayBuffer.empty[RowId]
    var kept: Collection[Msg] = prior
    // The rows `prior` does not hold.
    val fresh = Map.newBuilder[RowId, Row[Msg]]

  /** Builds the table of one evaluation from `base`, the table of the evaluation it follows.
    *
    * A row of a top-level collection the evaluation keeps keeps its bindings from `base`: building
    * the table removes and adds only the bindings of the rows the evaluation removes, adds or
    * evaluates again.
    */
  final private[render] class Builder[Msg](base: BindingTable[Msg] = BindingTable.empty):
    // The bindings outside every collection, then those of each row being evaluated.
    private val levels =
      mutable.ArrayBuffer(mutable.ArrayBuffer.empty[(BindingId, BindingOperation[Msg])])
    // The collections being collected, the outermost first.
    private val open        = mutable.ArrayBuffer.empty[Collecting[Msg]]
    private val added       = mutable.HashSet.empty[BindingId]
    private val collections = mutable.LinkedHashMap.empty[TemplateId, Collection[Msg]]
    // The rows of top-level collections that `base` does not hold, and those of `base` this table
    // does not hold.
    private val addedRows   = mutable.ArrayBuffer.empty[Row[Msg]]
    private val removedRows = mutable.ArrayBuffer.empty[Row[Msg]]

    def add(id: BindingId, operation: BindingOperation[Msg]): Either[RenderError, Unit] =
      if !added.add(id) then Left(RenderError.DuplicateBinding(id))
      else
        levels.last += id -> operation
        Right(())

    /** Starts collecting the bindings of the rows of collection `id`. */
    def enterRows(id: TemplateId): Unit =
      val prior = if open.isEmpty then base.rowGroups(id) else Map.empty[RowId, Row[Msg]]
      open += Collecting(id, prior)

    /** Ends collecting the bindings of the rows of the collection the last `enterRows` started, and
      * returns them.
      */
    def exitRows(): Collection[Msg] =
      val collection = open.remove(open.length - 1)
      val kept       =
        if collection.rendered.length == collection.prior.size then collection.kept
        else
          val rendered = collection.rendered.toSet
          val removed  = collection.prior.filterNot((id, _) => rendered.contains(id))
          removedRows ++= removed.valuesIterator
          collection.kept -- removed.keysIterator
      val fresh = collection.fresh.result()
      val rows  =
        if fresh.isEmpty then kept
        else if kept.isEmpty then fresh
        else kept ++ fresh
      if open.isEmpty then collections.update(collection.id, rows)
      else rows.valuesIterator.foreach(levels.last ++= _)
      rows

    /** Starts collecting the bindings of one row of the collection being collected. */
    def enterRow(): Unit = levels += mutable.ArrayBuffer.empty

    /** Ends collecting the bindings of row `id`, which the last `enterRow` started, and returns
      * them.
      */
    def exitRow(id: RowId): Row[Msg] =
      val row = levels.remove(levels.length - 1).toVector
      collect(id, row)
      row

    /** Keeps `row`, the bindings of row `id` in the previous evaluation, in the collection being
      * collected. A duplicate among them and the rest is found when the table is built.
      */
    def keepRow(id: RowId, row: Row[Msg]): Unit = collect(id, row)

    private def collect(id: RowId, row: Row[Msg]): Unit =
      val collection = open.last
      val topLevel   = open.length == 1
      collection.prior.get(id) match
        case Some(prior) =>
          collection.rendered += id
          if !(prior eq row) then
            collection.kept =
              if row.isEmpty then collection.kept - id else collection.kept.updated(id, row)
            if topLevel then
              addedRows += row
              removedRows += prior
        case None if row.nonEmpty =>
          collection.fresh += id -> row
          if topLevel then addedRows += row
        case None => ()

    /** Keeps the bindings of the rows of collection `id`, reused from the previous evaluation. */
    def reuseRows(id: TemplateId, rows: Collection[Msg]): Either[RenderError, Unit] =
      if open.isEmpty then
        val prior = base.rowGroups(id)
        if !(prior eq rows) then
          addedRows ++= rows.valuesIterator
          removedRows ++= prior.valuesIterator
        collections.update(id, rows)
        Right(())
      else
        rows.valuesIterator.flatten.foldLeft[Either[RenderError, Unit]](Right(())) {
          case (result, (bindingId, operation)) => result.flatMap(_ => add(bindingId, operation))
        }

    def result(): Either[RenderError, BindingTable[Msg]] =
      val loose = levels.head
      val gone  = base.collections.iterator.collect {
        case (id, rows) if !collections.contains(id) => rows.valuesIterator
      }.flatten
      val removed = (base.looseIds.iterator ++
        (removedRows.iterator ++ gone).flatMap(_.iterator.map(_._1))).toVector
      val start      = base.entries -- removed
      val newlyAdded = loose.iterator ++ addedRows.iterator.flatten
      val entries = newlyAdded.foldLeft[Either[RenderError, Map[BindingId, BindingOperation[Msg]]]](
        Right(start)
      ) { case (result, (id, operation)) =>
        result.flatMap(all =>
          if all.contains(id) then Left(RenderError.DuplicateBinding(id))
          else Right(all.updated(id, operation))
        )
      }
      entries.map { all =>
        val dropped = removed.iterator.filterNot(all.contains).map(id => id -> base.entries(id))
        BindingTable(
          all,
          Map.empty,
          loose.iterator.map(_._1).toVector,
          collections.toMap,
          base,
          dropped.toVector
        )
      }
  end Builder
end BindingTable
