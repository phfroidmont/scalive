package scalive

import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

import zio.*
import zio.http.{Body, Form as HttpForm, FormField as HttpFormField, MediaType}

/** An ordered browser form payload that preserves duplicate textual fields.
  *
  * [[raw]] is the lossless representation: it retains every name-value pair in encounter order.
  * Scalar accessors deliberately select the last value for a name, while [[values]] retains all
  * duplicates. Names remain bracket-encoded strings rather than being expanded into nested maps.
  *
  * For example:
  * {{{
  * val data = FormData(Vector("tag" -> "first", "tag" -> "second"))
  * data.values("tag") // Vector("first", "second")
  * data.get("tag")    // Some("second")
  * data.asMap          // Map("tag" -> "second")
  * }}}
  *
  * @param raw
  *   textual field pairs in encounter order
  */
final case class FormData private (raw: Vector[(String, String)]):
  /** Groups values by exact field name while preserving each name's value order.
    *
    * The map does not provide the cross-name ordering available from [[raw]].
    */
  lazy val fields: Map[String, FormValues] =
    raw.groupMap(_._1)(_._2).view.mapValues(values => FormValues(values.toVector)).toMap

  /** Returns the last value submitted under `name`, including an empty string, if present. */
  def get(name: String): Option[String] =
    values(name).lastOption

  /** Returns the last value submitted under the rendered [[FormPath.name path name]]. */
  def get(path: FormPath): Option[String] =
    get(path.name)

  /** Alias for [[get get]]. */
  def string(name: String): Option[String] = get(name)

  /** Alias for [[get get]]. */
  def string(path: FormPath): Option[String] = get(path)

  /** Returns every value submitted under `name`, in encounter order. */
  def values(name: String): Vector[String] =
    fields.get(name).map(_.values).getOrElse(Vector.empty)

  /** Returns every value submitted under the rendered [[FormPath.name path name]]. */
  def values(path: FormPath): Vector[String] =
    values(path.name)

  /** Returns the last value for `name`, or `fallback` when that name is absent. */
  def getOrElse(name: String, fallback: String): String =
    get(name).getOrElse(fallback)

  /** Whether at least one pair has the exact field name `name`, regardless of its value. */
  def contains(name: String): Boolean = fields.contains(name)

  /** Whether at least one pair has the rendered [[FormPath.name path name]]. */
  def contains(path: FormPath): Boolean = contains(path.name)

  /** Returns a lossy map containing only the last value for each exact field name. */
  def asMap: Map[String, String] =
    fields.view.mapValues(_.value).toMap

  /** Extracts fields textually nested below `name` and removes that outer name.
    *
    * A pair is included when its key starts with `name + "["` and ends with `]`. Encounter order
    * and duplicates are preserved. Matching is textual rather than a strict [[FormPath]] parse, and
    * the pair whose key is exactly `name` is not included.
    */
  def nested(name: String): FormData =
    val prefix = s"$name["
    FormData(
      raw.collect {
        case (key, value) if key.startsWith(prefix) && key.endsWith("]") =>
          stripNestedKey(key, prefix) -> value
      }
    )

  private def stripNestedKey(key: String, prefix: String): String =
    val segments = key.drop(prefix.length).dropRight(1).split("\\]\\[", -1).toVector
    if segments.isEmpty then ""
    else segments.head + segments.tail.map(segment => s"[$segment]").mkString
end FormData

/** All values submitted for one exact field name.
  *
  * @param values
  *   values in their original encounter order
  */
final case class FormValues(values: Vector[String]):
  /** The last value, or the empty string when there are no values. */
  def value: String = values.lastOption.getOrElse("")

/** Constructors, adapters, and decoding failures for [[FormData]]. */
object FormData:
  /** Binary ZIO HTTP field representations that cannot be stored in textual [[FormData]]. */
  enum UnsupportedFieldKind:
    /** An in-memory binary field. */
    case Binary

    /** A streaming binary field. */
    case StreamingBinary

  /** Failures while reading a bounded HTTP request body. */
  enum BodyError:
    /** The body exceeded the configured byte limit.
      *
      * @param maxBytes
      *   the configured limit, not the observed body size
      */
    case TooLarge(maxBytes: Long)

    /** Reading the body stream failed.
      *
      * @param cause
      *   the underlying stream failure
      */
    case Read(cause: Throwable)

  /** Failures interpreting input as textual form data. */
  enum RepresentationError:
    /** The body did not declare a URL-encoded form media type.
      *
      * @param actual
      *   the declared media type, or `None` when it was absent
      */
    case InvalidContentType(actual: Option[MediaType])

    /** URL percent-decoding failed.
      *
      * @param details
      *   diagnostic text from the decoder; its wording is not a stable error code
      */
    case InvalidUrlEncoding(details: String)

    /** A ZIO HTTP form contained a non-textual field.
      *
      * @param name
      *   the first unsupported field encountered
      * @param kind
      *   its binary representation
      */
    case UnsupportedField(name: String, kind: UnsupportedFieldKind)

  /** Distinguishes body acquisition failures from representation failures. */
  enum DecodeError:
    /** The bounded body could not be obtained. */
    case Body(error: BodyError)

    /** The obtained input was not a supported textual form representation. */
    case Representation(error: RepresentationError)

  /** Empty form data. */
  val empty: FormData = FormData(Vector.empty)

  /** Consumes `raw` once and stores all pairs in encounter order. */
  def apply(raw: IterableOnce[(String, String)]): FormData =
    new FormData(raw.iterator.toVector)

  /** Creates form data from a map.
    *
    * A map cannot represent duplicate names, and pair order is whatever its iterator provides.
    */
  def fromMap(values: Map[String, String]): FormData =
    FormData(values.toVector)

  /** Decodes an `application/x-www-form-urlencoded` value as UTF-8.
    *
    * Pair and duplicate order are retained. `+` decodes to a space, a pair without `=` has an empty
    * value, and malformed percent encoding produces [[RepresentationError.InvalidUrlEncoding]].
    */
  def fromUrlEncoded(value: String): Either[RepresentationError, FormData] =
    fromUrlEncoded(value, StandardCharsets.UTF_8)

  /** Reads and decodes a bounded URL-encoded HTTP body.
    *
    * The body media type must match `application/x-www-form-urlencoded`. A declared charset is
    * used, defaulting to UTF-8. At most `maxBytes + 1` bytes are consumed so oversized streaming
    * bodies can be detected; no partial form is returned. `maxBytes` must be from zero through
    * `Long.MaxValue - 1`, inclusive.
    *
    * Body size and stream failures are wrapped in [[DecodeError.Body]], while media type and URL
    * decoding failures are wrapped in [[DecodeError.Representation]].
    *
    * @throws IllegalArgumentException
    *   when `maxBytes` is outside the supported range
    */
  def fromUrlEncodedBody(body: Body, maxBytes: Long): IO[DecodeError, FormData] =
    require(
      maxBytes >= 0 && maxBytes < Long.MaxValue,
      "maxBytes must be between 0 and Long.MaxValue - 1"
    )

    body.mediaType match
      case Some(actual) if actual.matches(MediaType.application.`x-www-form-urlencoded`) =>
        body.knownContentLength match
          case Some(length) if length > maxBytes =>
            ZIO.fail(DecodeError.Body(BodyError.TooLarge(maxBytes)))
          case _ =>
            body.asStream
              .take(maxBytes + 1)
              .runCollect
              .mapError(error => DecodeError.Body(BodyError.Read(error)))
              .flatMap { bytes =>
                if bytes.length.toLong > maxBytes then
                  ZIO.fail(DecodeError.Body(BodyError.TooLarge(maxBytes)))
                else
                  val charset =
                    body.contentType.flatMap(_.charset).getOrElse(StandardCharsets.UTF_8)
                  ZIO
                    .fromEither(fromUrlEncoded(new String(bytes.toArray, charset), charset))
                    .mapError(DecodeError.Representation(_))
              }
      case actual =>
        ZIO.fail(
          DecodeError.Representation(RepresentationError.InvalidContentType(actual))
        )
  end fromUrlEncodedBody

  /** Converts simple and text fields from a ZIO HTTP form.
    *
    * Fields and duplicate values retain the form's iteration order. Encountering the first binary
    * or streaming binary field returns [[RepresentationError.UnsupportedField]] instead of partial
    * data; streaming fields are not consumed. Text-field media metadata is not retained.
    */
  def fromZioHttpForm(form: HttpForm): Either[RepresentationError, FormData] =
    val fields   = Vector.newBuilder[(String, String)]
    val iterator = form.formData.iterator
    var error    = Option.empty[RepresentationError]

    while iterator.hasNext && error.isEmpty do
      iterator.next() match
        case HttpFormField.Simple(name, value) =>
          val _ = fields.addOne(name -> value)
        case HttpFormField.Text(name, value, _, _) =>
          val _ = fields.addOne(name -> value)
        case HttpFormField.Binary(name, _, _, _, _) =>
          error = Some(RepresentationError.UnsupportedField(name, UnsupportedFieldKind.Binary))
        case HttpFormField.StreamingBinary(name, _, _, _, _) =>
          error = Some(
            RepresentationError.UnsupportedField(name, UnsupportedFieldKind.StreamingBinary)
          )

    error.toLeft(FormData(fields.result()))

  private def fromUrlEncoded(
    value: String,
    charset: Charset
  ): Either[RepresentationError, FormData] =
    if value.isEmpty then Right(empty)
    else
      try
        Right(
          FormData(
            value
              .split("&", -1).iterator.map { pair =>
                val Array(rawKey, rawValue) = pair.split("=", 2) match
                  case Array(key, value) => Array(key, value)
                  case Array(key)        => Array(key, "")
                  case _                 => Array("", "")
                decode(rawKey, charset) -> decode(rawValue, charset)
              }.toVector
          )
        )
      catch
        case NonFatal(error) =>
          Left(
            RepresentationError.InvalidUrlEncoding(
              Option(error.getMessage).getOrElse("Invalid URL-encoded form data")
            )
          )

  private def decode(value: String, charset: Charset): String =
    URLDecoder.decode(value, charset)
end FormData
