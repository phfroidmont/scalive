package scalive

import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

import zio.*
import zio.http.{Body, Form as HttpForm, FormField as HttpFormField, MediaType}

/** Ordered browser form payload with a lossless raw view and convenience accessors. */
final case class FormData private (raw: Vector[(String, String)]):
  lazy val fields: Map[String, FormField] =
    raw.groupMap(_._1)(_._2).view.mapValues(values => FormField(values.toVector)).toMap

  def get(name: String): Option[String] =
    values(name).lastOption

  def get(path: FormPath): Option[String] =
    get(path.name)

  def string(name: String): Option[String] = get(name)

  def string(path: FormPath): Option[String] = get(path)

  def values(name: String): Vector[String] =
    fields.get(name).map(_.values).getOrElse(Vector.empty)

  def values(path: FormPath): Vector[String] =
    values(path.name)

  def getOrElse(name: String, fallback: String): String =
    get(name).getOrElse(fallback)

  def contains(name: String): Boolean = fields.contains(name)

  def contains(path: FormPath): Boolean = contains(path.name)

  def asMap: Map[String, String] =
    fields.view.mapValues(_.value).toMap

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

final case class FormField(values: Vector[String]):
  def value: String = values.lastOption.getOrElse("")

object FormData:
  enum UnsupportedFieldKind:
    case Binary
    case StreamingBinary

  enum DecodeError:
    case InvalidContentType(actual: Option[MediaType])
    case BodyTooLarge(maxBytes: Long)
    case BodyRead(cause: Throwable)
    case InvalidUrlEncoding(details: String)
    case UnsupportedField(name: String, kind: UnsupportedFieldKind)

  val empty: FormData = FormData(Vector.empty)

  def apply(raw: IterableOnce[(String, String)]): FormData =
    new FormData(raw.iterator.toVector)

  def fromMap(values: Map[String, String]): FormData =
    FormData(values.toVector)

  def fromUrlEncoded(value: String): Either[DecodeError, FormData] =
    fromUrlEncoded(value, StandardCharsets.UTF_8)

  def fromUrlEncodedBody(body: Body, maxBytes: Long): IO[DecodeError, FormData] =
    require(
      maxBytes >= 0 && maxBytes < Long.MaxValue,
      "maxBytes must be between 0 and Long.MaxValue - 1"
    )

    body.mediaType match
      case Some(actual) if actual.matches(MediaType.application.`x-www-form-urlencoded`) =>
        body.knownContentLength match
          case Some(length) if length > maxBytes =>
            ZIO.fail(DecodeError.BodyTooLarge(maxBytes))
          case _ =>
            body.asStream
              .take(maxBytes + 1)
              .runCollect
              .mapError(DecodeError.BodyRead(_))
              .flatMap { bytes =>
                if bytes.length.toLong > maxBytes then ZIO.fail(DecodeError.BodyTooLarge(maxBytes))
                else
                  val charset =
                    body.contentType.flatMap(_.charset).getOrElse(StandardCharsets.UTF_8)
                  ZIO.fromEither(fromUrlEncoded(new String(bytes.toArray, charset), charset))
              }
      case actual =>
        ZIO.fail(DecodeError.InvalidContentType(actual))

  def fromZioHttpForm(form: HttpForm): Either[DecodeError, FormData] =
    val fields   = Vector.newBuilder[(String, String)]
    val iterator = form.formData.iterator
    var error    = Option.empty[DecodeError]

    while iterator.hasNext && error.isEmpty do
      iterator.next() match
        case HttpFormField.Simple(name, value) =>
          val _ = fields.addOne(name -> value)
        case HttpFormField.Text(name, value, _, _) =>
          val _ = fields.addOne(name -> value)
        case HttpFormField.Binary(name, _, _, _, _) =>
          error = Some(DecodeError.UnsupportedField(name, UnsupportedFieldKind.Binary))
        case HttpFormField.StreamingBinary(name, _, _, _, _) =>
          error = Some(
            DecodeError.UnsupportedField(name, UnsupportedFieldKind.StreamingBinary)
          )

    error.toLeft(FormData(fields.result()))

  private def fromUrlEncoded(
    value: String,
    charset: Charset
  ): Either[DecodeError, FormData] =
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
            DecodeError.InvalidUrlEncoding(
              Option(error.getMessage).getOrElse("Invalid URL-encoded form data")
            )
          )

  private def decode(value: String, charset: Charset): String =
    URLDecoder.decode(value, charset)
end FormData
