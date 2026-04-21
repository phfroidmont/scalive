package scalive

import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ListBuffer

import scalive.Mod.Attr
import scalive.Mod.Content

object StaticBuilder:

  def build(el: HtmlElement): ArraySeq[String] =
    buildStaticFragments(el).flatten.to(ArraySeq)

  private def buildStaticFragments(el: HtmlElement): Seq[Option[String]] =
    val attrs = el.attrMods.flatMap {
      case Attr.Static(name, value) => List(Some(s""" $name="${Escaping.escape(value)}"""))
      case Attr.StaticValueAsPresence(name, value) =>
        List(Some(if value then s" $name" else ""))
      case Attr.Binding(name, _, _) => List(Some(s""" $name="""), None, Some('"'.toString))
      case Attr.JsBinding(name, _)  => List(Some(s" $name='"), None, Some("'"))
    }

    val children = el.contentMods.flatMap {
      case Content.Text(text, raw) => List(Some(if raw then text else Escaping.escape(text)))
      case Content.Tag(child)      => buildStaticFragments(child)
      case Content.Component(_, _) => List(None)
      case Content.Keyed(_, _, _)  => List(None)
    }

    val static         = ListBuffer.empty[Option[String]]
    var staticFragment = s"<${el.tag.name}"

    for attr <- attrs do
      attr match
        case Some(fragment) =>
          staticFragment += fragment
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""

    staticFragment += (if el.tag.void then "/>" else ">")

    for child <- children do
      child match
        case Some(fragment) =>
          staticFragment += fragment
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""

    staticFragment += (if el.tag.void then "" else s"</${el.tag.name}>")
    static.append(Some(staticFragment))
    static.toSeq
  end buildStaticFragments

end StaticBuilder
