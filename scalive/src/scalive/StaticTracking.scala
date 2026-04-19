package scalive

import scala.collection.mutable.ListBuffer

import zio.json.*
import zio.json.ast.Json

object StaticTracking:
  private val attrName     = phx.trackStatic.name
  private val urlAttrNames = List(href.name, src.name)

  def collect(el: HtmlElement): List[String] =
    val urls = ListBuffer.empty[String]

    def hasTrack(mods: Seq[Mod.Attr]): Boolean =
      mods.exists {
        case Mod.Attr.Static(`attrName`, _)                => true
        case Mod.Attr.StaticValueAsPresence(`attrName`, v) => v
        case Mod.Attr.DynValueAsPresence(`attrName`, dyn)  => dyn.currentValue
        case _                                             => false
      }

    def loop(node: HtmlElement): Unit =
      val attrs = node.attrMods
      if hasTrack(attrs) then
        attrs.foreach {
          case Mod.Attr.Static(name, value) if urlAttrNames.contains(name) =>
            urls += value
          case Mod.Attr.Dyn(name, dyn, _) if urlAttrNames.contains(name) =>
            urls += dyn.currentValue
          case _ => ()
        }
      node.contentMods.foreach {
        case Mod.Content.Tag(child) => loop(child)
        case _                      => ()
      }

    loop(el)
    urls.toList

  def clientListFromParams(params: Option[Map[String, Json]]): Option[List[String]] =
    params.flatMap(_.get("_track_static")).flatMap(_.as[List[String]].toOption)

  def staticChanged(client: Option[List[String]], server: List[String]): Boolean =
    client.exists(_ != server)
end StaticTracking
