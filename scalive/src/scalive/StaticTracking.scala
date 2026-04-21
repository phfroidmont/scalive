package scalive

import zio.json.*
import zio.json.ast.Json

object StaticTracking:
  def collect(el: HtmlElement): List[String] =
    RenderSnapshot.compile(el).trackedStaticUrls.toList

  def clientListFromParams(params: Option[Map[String, Json]]): Option[List[String]] =
    params.flatMap(_.get("_track_static")).flatMap(_.as[List[String]].toOption)

  def staticChanged(client: Option[List[String]], server: List[String]): Boolean =
    client.exists(_ != server)
