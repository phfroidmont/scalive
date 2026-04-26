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
  domId: A => String
)(using
  private val itemClassTag: ClassTag[A]):
  private[scalive] def decode(value: Any): Option[A] =
    itemClassTag.unapply(value)

  private[scalive] def withName(name: String): LiveStreamDef[A] =
    copy(name = name)

object LiveStreamDef:
  def byId[A: ClassTag, Id](name: String)(id: A => Id): LiveStreamDef[A] =
    LiveStreamDef(name, value => s"$name-${id(value)}")

final case class LiveStreamEntry[+A](domId: String, value: A)

final private[scalive] case class LiveStreamInsert(
  domId: String,
  at: Int,
  limit: Option[Int],
  updateOnly: Option[Boolean])

final case class LiveStream[+A] private[scalive] (
  name: String,
  entries: Vector[LiveStreamEntry[A]],
  private[scalive] snapshotEntries: Vector[LiveStreamEntry[A]],
  private[scalive] ref: String,
  private[scalive] inserts: Vector[LiveStreamInsert],
  private[scalive] deleteIds: Vector[String],
  private[scalive] reset: Boolean):
  def isEmpty: Boolean  = snapshotEntries.isEmpty
  def nonEmpty: Boolean = snapshotEntries.nonEmpty

object api:
  export _root_.scalive.streams.{LiveStream, LiveStreamDef, LiveStreamEntry, StreamAt, StreamLimit}
