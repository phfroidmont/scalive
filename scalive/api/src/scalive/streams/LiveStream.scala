package scalive
package streams

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

  /** Inserts a new item at the zero-based `value`, appending when it is beyond the current end. */
  case Index(value: Int)

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
  limit: Option[StreamLimit] = None):
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

/** Convenience constructors for [[LiveStreamDef]]. */
object LiveStreamDef:
  /** Defines DOM ids as `name-id(item)` using the selected id's string representation.
    *
    * The caller remains responsible for making the resulting strings stable, non-empty, valid HTML
    * ids, and unique. Distinct `Id` values with equal string representations collide.
    */
  def byId[A, Id](name: String)(id: A => Id): LiveStreamDef[A] =
    LiveStreamDef(name, value => s"$name-${id(value)}")

/** Opaque rendering handle for a LiveView stream.
  *
  * Stream operations return a handle for the `stream` and `renderIn` rendering helpers. Store and
  * render the returned handle; it is not a collection and intentionally exposes no entries or
  * runtime protocol state to application code.
  *
  * Stream runtime state belongs to the current socket or component lifecycle and is lost when that
  * lifecycle ends or mounts again. A `LiveStream` must therefore not be the durable source of truth
  * for domain data. Persist or retain canonical data separately and recreate the stream during
  * mount.
  *
  * @tparam A
  *   the streamed item type
  */
abstract class LiveStream[+A] private[scalive] ():
  private[scalive] def identity: LiveStreamIdentity          = unsupported
  private[scalive] def name: String                          = unsupported
  private[scalive] def generation: Long                      = unsupported
  private[scalive] def entries: Vector[LiveStreamEntry[A]]   = unsupported
  private[scalive] def inserted: Vector[LiveStreamInsert[A]] = unsupported
  private[scalive] def deleted: Vector[String]               = unsupported
  private[scalive] def reset: Boolean                        = unsupported

  private def unsupported: Nothing =
    throw UnsupportedOperationException("LiveStream was not created by the managed stream runtime")

/** Runtime-only identity shared by all replacement handles for one managed stream. */
final private[scalive] class LiveStreamIdentity private ()

private[scalive] object LiveStreamIdentity:
  def fresh(): LiveStreamIdentity = new LiveStreamIdentity()

/** One entry in the runtime's complete, ordered stream snapshot. */
final private[scalive] case class LiveStreamEntry[+A](domId: String, value: A)

/** One pending insertion or in-place update, expressed without wire-level conventions. */
final private[scalive] case class LiveStreamInsert[+A](
  entry: LiveStreamEntry[A],
  at: StreamAt,
  limit: Option[StreamLimit],
  updateOnly: Boolean)

private[scalive] object LiveStream:
  def apply[A](
    identity: LiveStreamIdentity,
    name: String,
    generation: Long,
    entries: Vector[LiveStreamEntry[A]],
    inserted: Vector[LiveStreamInsert[A]],
    deleted: Vector[String],
    reset: Boolean
  ): LiveStream[A] =
    Impl(identity, name, generation, entries, inserted, deleted, reset)

  final private case class Impl[+A](
    override val identity: LiveStreamIdentity,
    override val name: String,
    override val generation: Long,
    override val entries: Vector[LiveStreamEntry[A]],
    override val inserted: Vector[LiveStreamInsert[A]],
    override val deleted: Vector[String],
    override val reset: Boolean)
      extends LiveStream[A]

object api:
  export _root_.scalive.streams.{LiveStream, LiveStreamDef, StreamAt, StreamLimit}
