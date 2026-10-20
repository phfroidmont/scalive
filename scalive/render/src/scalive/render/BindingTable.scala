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
  */
final class BindingTable[+Msg] private (
  private val entries: Map[BindingId, BindingOperation[Msg]],
  private val retired: Map[BindingId, BindingTable.Retired[Msg]]):
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
    val dropped = previous.entries.iterator.collect {
      case (id, operation) if !entries.contains(id) => id -> BindingTable.Retired(operation, now)
    }
    val kept = previous.retired.iterator.filter((id, binding) =>
      !entries.contains(id) && binding.isRecent(now)
    )
    // `dropped` comes last, so it replaces an older retirement of the same ID.
    val all    = (kept ++ dropped).toMap
    val capped =
      if all.size <= BindingTable.RetiredCapacity then all
      else all.toVector.sortBy(_._2.at).takeRight(BindingTable.RetiredCapacity).toMap
    BindingTable(entries, capped)
end BindingTable

object BindingTable:
  /** How long a dropped binding still resolves. */
  val RetiredFor: Duration = Duration.ofSeconds(10)

  /** How many dropped bindings a table retains at most. */
  val RetiredCapacity: Int = 1000

  final private case class Retired[+Msg](operation: BindingOperation[Msg], at: Instant):
    def isRecent(now: Instant): Boolean = now.isBefore(at.plus(RetiredFor))

  val empty: BindingTable[Nothing] = BindingTable(Map.empty, Map.empty)

  final private[render] class Builder[Msg]:
    private val entries = mutable.LinkedHashMap.empty[BindingId, BindingOperation[Msg]]

    def add(id: BindingId, operation: BindingOperation[Msg]): Either[RenderError, Unit] =
      if entries.contains(id) then Left(RenderError.DuplicateBinding(id))
      else
        entries.addOne(id -> operation)
        Right(())

    def result(): BindingTable[Msg] = BindingTable(Map.from(entries), Map.empty)
