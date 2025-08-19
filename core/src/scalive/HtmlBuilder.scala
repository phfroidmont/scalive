package scalive

import java.io.StringWriter
import scalive.Mod.Attr
import scalive.Mod.Content

object HtmlBuilder:

  def build(el: HtmlElement, isRoot: Boolean = false): String =
    val strw = new StringWriter()
    if isRoot then strw.write("<!doctype html>")
    build(el.static, el.dynamicMods, strw)
    strw.toString()

  private def build(
    static: Seq[String],
    dynamic: Seq[(Mod.Attr | Mod.Content) & DynamicMod],
    strw: StringWriter
  ): Unit =
    for i <- dynamic.indices do
      strw.write(static(i))
      dynamic(i) match
        case Attr.Dyn(attr, value) =>
          strw.write(value.render(false).map(attr.codec.encode).getOrElse(""))
        case Attr.DynValueAsPresence(attr, value) =>
          strw.write(
            value.render(false).map(if _ then s" ${attr.name}" else "").getOrElse("")
          )
        case Content.Tag(el)               => build(el.static, el.dynamicMods, strw)
        case Content.DynText(dyn)          => strw.write(dyn.render(false).getOrElse(""))
        case Content.DynElement(dyn)       => ???
        case Content.DynOptionElement(dyn) =>
          dyn.render(false).foreach(_.foreach(el => build(el.static, el.dynamicMods, strw)))
        case Content.DynElementColl(dyn) => ???
        case Content.DynSplit(splitVar)  =>
          val entries   = splitVar.render(false)
          val staticOpt = entries.collectFirst { case (_, Some(el)) => el.static }
          entries.foreach {
            case (_, Some(entryEl)) =>
              build(staticOpt.getOrElse(Nil), entryEl.dynamicMods, strw)
            case _ => ()
          }
    strw.write(static.last)

end HtmlBuilder
