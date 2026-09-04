package scalive

import java.nio.charset.StandardCharsets
import java.util.UUID

import zio.UIO
import zio.ZIO

/** One schema-level component of a logical form address.
  *
  * `Row` is distinct from `Name`, so a stable row key cannot collide with a schema segment.
  */
enum FormAddressSegment derives CanEqual:
  /** A schema-declared name. */
  case Name(value: String)

  /** A stable repeated-row identity. */
  case Row(key: String)

/** A stable, owner-scoped logical address used by validation and interaction state.
  *
  * Unlike [[FormPath]], an address is not a browser field name: it distinguishes keyed row identity
  * from schema names and remains stable when rows are reordered. `Owner` prevents cross-root use;
  * exact definition identity is carried separately by [[FormValues]] and [[Form]].
  */
final class FormAddress[Owner] private[scalive] (
  private[scalive] val segments: Vector[FormAddressSegment])
    derives CanEqual:

  /** An injective, ASCII-only DOM identity. */
  def id: String =
    val encoded = segments.map {
      case FormAddressSegment.Name(value) => s"n${FormAddress.hex(value)}"
      case FormAddressSegment.Row(value)  => s"r${FormAddress.hex(value)}"
    }
    ("fa" +: encoded).mkString("_")

  /** Whether this address identifies `prefix` or one of its descendants. */
  def startsWith(prefix: FormAddress[Owner]): Boolean =
    segments.startsWith(prefix.segments)

  override def equals(other: Any): Boolean = other match
    case that: FormAddress[?] => segments == that.segments
    case _                    => false

  override def hashCode(): Int = segments.hashCode()

  override def toString: String = segments
    .map {
      case FormAddressSegment.Name(value) => value
      case FormAddressSegment.Row(key)    => s"{$key}"
    }.mkString("/")

/** Constructors for owner-scoped logical field and row addresses. */
object FormAddress:
  private[scalive] def root[Owner](path: FormPath): FormAddress[Owner] =
    fromPath(path)

  private[scalive] def fromPath[Owner](path: FormPath): FormAddress[Owner] =
    new FormAddress(path.segments.map {
      case FormPathSegment.Name(value) => FormAddressSegment.Name(value)
      case FormPathSegment.Array       => FormAddressSegment.Name("[]")
    })

  private[scalive] def names[Owner](names: Vector[String]): FormAddress[Owner] =
    new FormAddress(names.map(FormAddressSegment.Name.apply))

  private[scalive] def row[Owner](
    prefix: FormAddress[Owner],
    key: String
  ): FormAddress[Owner] =
    new FormAddress(prefix.segments :+ FormAddressSegment.Row(key))

  private[scalive] def append[Owner](
    prefix: FormAddress[Owner],
    names: Vector[String]
  ): FormAddress[Owner] =
    new FormAddress(prefix.segments ++ names.map(FormAddressSegment.Name.apply))

  private def hex(value: String): String =
    value.getBytes(StandardCharsets.UTF_8).iterator.map(byte => f"${byte & 0xff}%02x").mkString

/** A stable UI row identity scoped to one exact repeated group.
  *
  * Keys survive reordering and drive row addresses and mutations. The `Group` parameter prevents a
  * key created for one [[RepeatedGroup]] from being used with another.
  */
opaque type FormRowKey[Group] = String

/** Validation, allocation, and access operations for [[FormRowKey]]. */
object FormRowKey:
  /** Maximum accepted key length in UTF-16 code units. */
  val MaxLength = 64

  /** Why an external string cannot be represented as a row key. */
  enum Error derives CanEqual:
    case Empty
    case TooLong(maxLength: Int)
    case InvalidCharacter(offset: Int)

    /** Stable machine-readable error code. */
    def code: String = this match
      case Empty               => "empty_row_key"
      case TooLong(_)          => "row_key_too_long"
      case InvalidCharacter(_) => "invalid_row_key_character"

  /** Validates an external key using the bounded alphanumeric, underscore, and hyphen grammar. */
  def from[Group](value: String): Either[Error, FormRowKey[Group]] =
    if value == null || value.isEmpty then Left(Error.Empty)
    else if value.length > MaxLength then Left(Error.TooLong(MaxLength))
    else
      value.indexWhere(char => !char.isLetterOrDigit && char != '_' && char != '-') match
        case -1     => Right(value)
        case offset => Left(Error.InvalidCharacter(offset))

  /** Allocates a UUID-based key suitable for a new client-side row. */
  def random[Group]: UIO[FormRowKey[Group]] =
    ZIO.succeed(UUID.randomUUID().toString)

  extension [Group](key: FormRowKey[Group])
    /** Returns the validated wire representation. */
    def value: String = key
end FormRowKey
