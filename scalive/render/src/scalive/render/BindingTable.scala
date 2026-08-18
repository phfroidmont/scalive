package scalive.render

import scala.collection.mutable
import scala.util.control.NonFatal

import scalive.BindingPayload

/** A typed browser-payload operation captured from one evaluated render snapshot. */
sealed trait BindingOperation[+Msg]:
  private[scalive] def dispatch(payload: BindingPayload): Either[Throwable, Msg]

object BindingOperation:
  private[render] def apply[Msg](operation: BindingPayload => Msg): BindingOperation[Msg] =
    new BindingOperation[Msg]:
      private[scalive] def dispatch(payload: BindingPayload): Either[Throwable, Msg] =
        try Right(operation(payload))
        catch case NonFatal(error) => Left(error)

/** Immutable event dispatch table corresponding exactly to an [[EvaluatedTree]].
  *
  * Lifecycle code resolves events only against the committed table. Construction rejects duplicate
  * identities rather than selecting one operation by map overwrite.
  */
final class BindingTable[+Msg] private (
  private val entries: Map[BindingId, BindingOperation[Msg]]):
  def resolve(id: BindingId): Option[BindingOperation[Msg]] = entries.get(id)

  def size: Int = entries.size

  def isEmpty: Boolean = entries.isEmpty

  def ids: Vector[BindingId] = entries.keysIterator.toVector

object BindingTable:
  val empty: BindingTable[Nothing] = BindingTable(Map.empty)

  final private[render] class Builder[Msg]:
    private val entries = mutable.LinkedHashMap.empty[BindingId, BindingOperation[Msg]]

    def add(id: BindingId, operation: BindingOperation[Msg]): Either[RenderError, Unit] =
      if entries.contains(id) then Left(RenderError.DuplicateBinding(id))
      else
        entries.addOne(id -> operation)
        Right(())

    def result(): BindingTable[Msg] = BindingTable(Map.from(entries))
