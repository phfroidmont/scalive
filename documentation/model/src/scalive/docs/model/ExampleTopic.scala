package scalive.docs.model

import java.util.Locale

private[docs] object ExampleTopic:
  def key(value: String): String =
    value.trim
      .toLowerCase(Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")
      .stripSuffix("-")

  def label(value: String): String =
    val normalized = value.trim.replaceAll("\\s+", " ")
    if normalized.isEmpty then normalized
    else s"${normalized.head.toUpper}${normalized.tail}"
