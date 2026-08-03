package scalive
package streams

import scala.reflect.ClassTag

enum StreamAt:
  case First
  case Last
  case Index(value: Int)

object StreamAt:
  private[scalive] def toWire(at: StreamAt): Int =
    at match
      case StreamAt.First        => 0
      case StreamAt.Last         => -1
      case StreamAt.Index(value) => value

enum StreamLimit:
  case KeepFirst(count: Int)
  case KeepLast(count: Int)

object StreamLimit:
  private[scalive] def toWire(limit: StreamLimit): Int =
    limit match
      case StreamLimit.KeepFirst(count) => count
      case StreamLimit.KeepLast(count)  => -count

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

  def keepFirst(count: Int): LiveStreamDef[A] =
    copy(limit = Some(StreamLimit.KeepFirst(count)))

  def keepLast(count: Int): LiveStreamDef[A] =
    copy(limit = Some(StreamLimit.KeepLast(count)))

  def withLimit(limit: Option[StreamLimit]): LiveStreamDef[A] =
    copy(limit = limit)

  def withoutLimit: LiveStreamDef[A] =
    copy(limit = None)

object LiveStreamDef:
  def byId[A: ClassTag, Id](name: String)(id: A => Id): LiveStreamDef[A] =
    LiveStreamDef(name, value => s"$name-${id(value)}")

final private[scalive] case class LiveStreamEntry[+A](domId: String, value: A)

final private[scalive] case class LiveStreamInsert(
  domId: String,
  at: Int,
  limit: Option[Int],
  updateOnly: Option[Boolean])

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
