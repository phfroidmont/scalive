package scalive

import java.net.URI

import zio.http.URL

object LiveParams:
  final case class Parsed(
    params: Map[String, String],
    uri: URI)

  def fromUrl(url: URL): Parsed =
    val params =
      url.queryParams.map.iterator.map { case (key, values) =>
        key -> values.lastOption.getOrElse("")
      }.toMap
    Parsed(params, url.toJavaURI)
