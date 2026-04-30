package scalive

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
  val empty: FormData = FormData(Vector.empty)

  def apply(raw: IterableOnce[(String, String)]): FormData =
    new FormData(raw.iterator.toVector)

  def fromMap(values: Map[String, String]): FormData =
    FormData(values.toVector)

  def fromUrlEncoded(value: String): FormData =
    if value.isEmpty then empty
    else
      FormData(
        value
          .split("&", -1).iterator.map { pair =>
            val Array(rawKey, rawValue) = pair.split("=", 2) match
              case Array(key, value) => Array(key, value)
              case Array(key)        => Array(key, "")
              case _                 => Array("", "")
            decode(rawKey) -> decode(rawValue)
          }.toVector
      )

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
