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
  * A table is built from the one before it, so that the bindings of a collection the evaluation
  * reused cost nothing: it records which bindings come from each top-level collection, and which
  * bindings of the table before it this one dropped.
  */
final class BindingTable[+Msg] private (
  private val entries: Map[BindingId, BindingOperation[Msg]],
  private val retired: Map[BindingId, BindingTable.Retired[Msg]],
  // The bindings outside every collection, and those of each top-level collection.
  private val looseIds: Vector[BindingId],
  private val collectionIds: Map[TemplateId, Vector[BindingId]],
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
    BindingTable(entries, capped, looseIds, collectionIds, base, dropped)
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

  /** The bindings of the rows of one collection, its nested collections included. */
  private[render] type Rows[+Msg] = Vector[(BindingId, BindingOperation[Msg])]

  /** Builds the table of one evaluation from `base`, the table of the evaluation it follows. */
  final private[render] class Builder[Msg](base: BindingTable[Msg] = BindingTable.empty):
    // The bindings outside every collection, then those of each collection being evaluated.
    private val levels =
      mutable.ArrayBuffer(mutable.ArrayBuffer.empty[(BindingId, BindingOperation[Msg])])
    private val added                = mutable.HashSet.empty[BindingId]
    private val evaluatedCollections = mutable.LinkedHashMap.empty[TemplateId, Rows[Msg]]
    private val reusedCollections    = mutable.LinkedHashMap.empty[TemplateId, Rows[Msg]]

    def add(id: BindingId, operation: BindingOperation[Msg]): Either[RenderError, Unit] =
      if !added.add(id) then Left(RenderError.DuplicateBinding(id))
      else
        levels.last += id -> operation
        Right(())

    /** Starts collecting the bindings of the rows of a collection. */
    def enterRows(): Unit = levels += mutable.ArrayBuffer.empty

    /** Ends collecting the bindings of the rows of collection `id`, and returns them. */
    def exitRows(id: TemplateId): Rows[Msg] =
      val rows = levels.remove(levels.length - 1).toVector
      if levels.length == 1 then evaluatedCollections.update(id, rows) else levels.last ++= rows
      rows

    /** Keeps the bindings of the rows of collection `id`, reused from the previous evaluation. */
    def reuseRows(id: TemplateId, rows: Rows[Msg]): Either[RenderError, Unit] =
      if levels.length == 1 then
        reusedCollections.update(id, rows)
        Right(())
      else
        rows.foldLeft[Either[RenderError, Unit]](Right(())) { case (result, (id, operation)) =>
          result.flatMap(_ => add(id, operation))
        }

    def result(): Either[RenderError, BindingTable[Msg]] =
      val loose = levels.head
      // A reused top-level collection keeps its bindings from `base`.
      val keptIds = reusedCollections.keysIterator.filter(base.collectionIds.contains).toSet
      val removed = base.looseIds ++ base.collectionIds.iterator.collect {
        case (id, ids) if !keptIds.contains(id) => ids
      }.flatten
      val start      = base.entries -- removed
      val newlyAdded = loose.iterator ++ evaluatedCollections.valuesIterator.flatten ++
        reusedCollections.iterator.collect {
          case (id, rows) if !keptIds.contains(id) => rows
        }.flatten
      val entries = newlyAdded.foldLeft[Either[RenderError, Map[BindingId, BindingOperation[Msg]]]](
        Right(start)
      ) { case (result, (id, operation)) =>
        result.flatMap(all =>
          if all.contains(id) then Left(RenderError.DuplicateBinding(id))
          else Right(all.updated(id, operation))
        )
      }
      entries.map { all =>
        val collectionIds = base.collectionIds.filter((id, _) => keptIds.contains(id)) ++
          (evaluatedCollections.iterator ++ reusedCollections.iterator.filterNot((id, _) =>
            keptIds.contains(id)
          )).map((id, rows) => id -> rows.map(_._1))
        val dropped = removed.iterator.filterNot(all.contains).map(id => id -> base.entries(id))
        BindingTable(
          all,
          Map.empty,
          loose.iterator.map(_._1).toVector,
          collectionIds,
          base,
          dropped.toVector
        )
      }
    end result
  end Builder
end BindingTable
