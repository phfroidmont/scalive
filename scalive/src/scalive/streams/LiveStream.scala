package scalive
package streams

import scala.reflect.ClassTag

/** Position at which new stream items are inserted.
  *
  * Position affects new DOM ids only; inserting an id already present updates that entry in place.
  * Bulk insertion behaves like repeated insertion at the same position, so inserting multiple items
  * at [[StreamAt.First]] or one fixed [[StreamAt.Index]] can reverse their order.
  */
enum StreamAt:
  /** Inserts a new item at index zero. */
  case First

  /** Appends a new item after the current last item. */
  case Last

  /** Inserts a new item at the zero-based `value`, appending when it is beyond the current end.
    *
    * `-1` is the protocol append sentinel and behaves like [[StreamAt.Last]]; values below `-1` are
    * rejected when a stream operation uses the position.
    */
  case Index(value: Int)

object StreamAt:
  private[scalive] def toWire(at: StreamAt): Int =
    at match
      case StreamAt.First        => 0
      case StreamAt.Last         => -1
      case StreamAt.Index(value) => value

/** Retention limit applied to the stream snapshot after stream insertions.
  *
  * Counts are validated when a stream operation uses the definition and must be strictly positive.
  * A definition limit applies to creation, reset, and subsequent insert operations.
  */
enum StreamLimit:
  /** Retains at most the first `count` items, pruning items from the end. */
  case KeepFirst(count: Int)

  /** Retains at most the last `count` items, pruning items from the beginning. */
  case KeepLast(count: Int)

object StreamLimit:
  private[scalive] def toWire(limit: StreamLimit): Int =
    limit match
      case StreamLimit.KeepFirst(count) => count
      case StreamLimit.KeepLast(count)  => -count

/** Defines the identity and optional retention policy of a LiveView stream.
  *
  * A stream is keyed by `name` within its owning LiveView or LiveComponent. Reuse one coherent
  * definition for that name throughout the lifecycle: using another item type or DOM-id function
  * against existing runtime state can fail decoding or target different DOM nodes.
  *
  * Every item must produce a stable, non-empty DOM id that is unique within the stream and, as an
  * HTML invariant, within the rendered document. Equal ids identify the same stream entry and cause
  * an in-place update rather than a second row. Changing an item's id does not delete its old row.
  * The runtime rejects an empty name, an empty generated id, a throwing `domId`, an invalid
  * position, or a non-positive limit when an operation uses the definition; construction itself is
  * unchecked.
  *
  * @tparam A
  *   the stream item type
  * @param name
  *   non-empty runtime key and the container id used by the `renderIn` helper
  * @param domId
  *   stable function assigning each item its row DOM id
  * @param limit
  *   optional retention limit applied by creation, reset, and insertion operations, defaulting to
  *   no limit
  */
final case class LiveStreamDef[A](
  name: String,
  domId: A => String,
  limit: Option[StreamLimit] = None
)(using
  private val itemClassTag: ClassTag[A]):
  private[scalive] def decode(value: Any): Option[A] =
    itemClassTag.unapply(value)

  private[scalive] def withName(name: String): LiveStreamDef[A] =
    copy(name = name)

  /** Returns a copy that retains at most the first `count` items.
    *
    * Validation that `count` is positive is deferred until an operation applies the limit.
    */
  def keepFirst(count: Int): LiveStreamDef[A] =
    copy(limit = Some(StreamLimit.KeepFirst(count)))

  /** Returns a copy that retains at most the last `count` items.
    *
    * Validation that `count` is positive is deferred until an operation applies the limit.
    */
  def keepLast(count: Int): LiveStreamDef[A] =
    copy(limit = Some(StreamLimit.KeepLast(count)))

  /** Returns a copy using `limit`; `None` disables retention pruning.
    *
    * Limit validation is deferred until an operation applies the limit.
    */
  def withLimit(limit: Option[StreamLimit]): LiveStreamDef[A] =
    copy(limit = limit)

  /** Returns a copy with no retention limit. */
  def withoutLimit: LiveStreamDef[A] =
    copy(limit = None)
end LiveStreamDef

/** Convenience constructors for [[LiveStreamDef]]. */
object LiveStreamDef:
  /** Defines DOM ids as `name-id(item)` using the selected id's string representation.
    *
    * The caller remains responsible for making the resulting strings stable, non-empty, valid HTML
    * ids, and unique. Distinct `Id` values with equal string representations collide.
    */
  def byId[A: ClassTag, Id](name: String)(id: A => Id): LiveStreamDef[A] =
    LiveStreamDef(name, value => s"$name-${id(value)}")

final private[scalive] case class LiveStreamEntry[+A](domId: String, value: A)

final private[scalive] case class LiveStreamInsert(
  domId: String,
  at: Int,
  limit: Option[Int],
  updateOnly: Option[Boolean])

/** Opaque, immutable rendering handle for a LiveView stream.
  *
  * Stream operations return a new handle containing the snapshot and pending browser patch needed
  * by the `stream` and `renderIn` rendering helpers. Store and render the returned handle; it is
  * not a collection and intentionally exposes no entries or runtime protocol state to application
  * code.
  *
  * Stream runtime state belongs to the current socket or component lifecycle and is lost when that
  * lifecycle ends or mounts again. A `LiveStream` must therefore not be the durable source of truth
  * for domain data. Persist or retain canonical data separately and recreate the stream during
  * mount.
  *
  * @tparam A
  *   the streamed item type
  */
final class LiveStream[+A] private[scalive] (
  private[scalive] val name: String,
  private[scalive] val entries: Vector[LiveStreamEntry[A]],
  private[scalive] val snapshotEntries: Vector[LiveStreamEntry[A]],
  private[scalive] val ref: String,
  private[scalive] val inserts: Vector[LiveStreamInsert],
  private[scalive] val deleteIds: Vector[String],
  private[scalive] val reset: Boolean):
  private[scalive] def withName(name: String): LiveStream[A] =
    new LiveStream(name, entries, snapshotEntries, ref, inserts, deleteIds, reset)

object api:
  export _root_.scalive.streams.{LiveStream, LiveStreamDef, StreamAt, StreamLimit}
