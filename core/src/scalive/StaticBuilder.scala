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
      case Attr.Static(name, value)                => List(Some(s" $name='$value'"))
      case Attr.StaticValueAsPresence(name, value) => List(Some(s" $name"))
      case Attr.Binding(name, id, _)               => List(Some(s""" $name="$id""""))
      case Attr.JsBinding(name, json, _)           => List(Some(s" $name='$json'"))
      case Attr.Dyn(name, value, isJson)           =>
        if isJson then List(Some(s" $name='"), None, Some("'"))
        else List(Some(s""" $name=""""), None, Some('"'.toString))
      case Attr.DynValueAsPresence(_, value) => List(Some(""), None, Some(""))
    }
    val children = el.contentMods.flatMap {
      case Content.Text(text)          => List(Some(text))
      case Content.Tag(el)             => buildStaticFragments(el)
      case Content.DynText(_)          => List(None)
      case Content.DynElement(_)       => List(None)
      case Content.DynOptionElement(_) => List(None)
      case Content.DynElementColl(_)   => List(None)
      case Content.DynSplit(_)         => List(None)
    }
    val static         = ListBuffer.empty[Option[String]]
    var staticFragment = s"<${el.tag.name}"
    for attr <- attrs do
      attr match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += (if el.tag.void then "/>" else ">")
    for child <- children do
      child match
        case Some(s) =>
          staticFragment += s
        case None =>
          static.append(Some(staticFragment))
          static.append(None)
          staticFragment = ""
    staticFragment += (if el.tag.void then "" else s"</${el.tag.name}>")
    static.append(Some(staticFragment))
    static.toSeq
  end buildStaticFragments

end StaticBuilder
