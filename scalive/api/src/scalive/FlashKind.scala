package scalive

import zio.json.*

/** A nominal key for one category of flash message, whose value has type `A`.
  *
  * The same kind is used to put, retrieve, clear, render, and transfer a flash message across
  * navigation. Declare each kind once and reuse the value, like an [[AsyncKey]]:
  * `FlashKind("saved")` holds text, and `FlashKind.json[Notice]("notice")` holds a `Notice` encoded
  * as JSON.
  */
final class FlashKind[A] private (
  private[scalive] val key: FlashKey,
  private val encode: A => String,
  private val decode: String => Option[A]):

  /** The exact runtime flash key, for logging or a hand-written client. */
  def value: String = key.value

  /** A flash message of this kind, for example for `HttpFlash.seeOther`. */
  def message(value: A): FlashEntry = FlashEntry(key, encodeValue(value))

  private[scalive] def encodeValue(message: A): String = encode(message)

  private[scalive] def decodeValue(encoded: String): Option[A] = decode(encoded)

  override def equals(other: Any): Boolean = other match
    case kind: FlashKind[?] => kind.key == key
    case _                  => false

  override def hashCode: Int = key.hashCode

  override def toString: String = s"FlashKind($value)"

/** Creates [[FlashKind]] values. */
object FlashKind:
  /** Creates a flash kind for text, stored as is.
    *
    * No validation or normalization is performed on `value`, the exact runtime key.
    */
  def apply(value: String): FlashKind[String] =
    new FlashKind(FlashKey(value), identity, Some(_))

  /** Creates a flash kind for values of type `A`, stored as JSON.
    *
    * A stored value that no longer decodes, for example one an earlier deployment encoded, is
    * treated as absent.
    */
  def json[A: JsonCodec](value: String): FlashKind[A] =
    new FlashKind(FlashKey(value), _.toJson, _.fromJson[A].toOption)

/** One flash message, built with `kind.message(value)`. */
final class FlashEntry private[scalive] (
  private[scalive] val key: FlashKey,
  private[scalive] val encoded: String)

/** The runtime key of a flash kind, as stored and transferred. */
private[scalive] opaque type FlashKey = String

private[scalive] object FlashKey:
  def apply(value: String): FlashKey = value

  extension (key: FlashKey) def value: String = key

/** A stored flash message and how many times its kind was put while it was shown. */
final private[scalive] case class FlashValue(encoded: String, put: Int)
