package scalive

import java.net.URI

import zio.json.*
import zio.json.ast.Json

private[scalive] object StaticTracking:
  def collect(el: HtmlElement[?]): List[String] =
    RenderSnapshot.compile(el).trackedStaticUrls.toList

  def clientListFromParams(params: Option[Map[String, Json]]): Option[List[String]] =
    params.flatMap(_.get("_track_static")).flatMap(_.as[List[String]].toOption)

  def staticChanged(client: Option[List[String]], server: List[String]): Boolean =
    client.exists(urls => urls.nonEmpty && urls.map(normalizeUrl) != server.map(normalizeUrl))

  private def normalizeUrl(value: String): String =
    val withoutQuery = value.takeWhile(ch => ch != '?' && ch != '#')
    try
      val uri = URI.create(value)
      Option(uri.getRawPath).filter(_.nonEmpty).getOrElse(withoutQuery)
    catch case _: IllegalArgumentException => withoutQuery
