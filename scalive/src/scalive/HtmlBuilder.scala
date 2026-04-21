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
        case Attr.Binding(_, id, _)          => strw.write(id.render(false).getOrElse(""))
        case Attr.JsBinding(_, jsonValue, _) => strw.write(jsonValue.render(false).getOrElse(""))
        case Attr.Dyn(name, value, isJson)   =>
          if isJson then strw.write(value.render(false).getOrElse(""))
          else strw.writeEscaped(value.render(false).getOrElse(""))
        case Attr.DynValueAsPresence(name, value) =>
          strw.write(
            value.render(false).map(if _ then s" $name" else "").getOrElse("")
          )
        case Content.Tag(el)          => build(el.static, el.dynamicMods, strw)
        case Content.Component(_, el) => build(el.static, el.dynamicMods, strw)
        case Content.DynText(dyn)     => strw.writeEscaped(dyn.render(false).getOrElse(""))
        case Content.DynElement(dyn)  =>
          dyn.render(false).foreach(el => build(el.static, el.dynamicMods, strw))
        case Content.DynOptionElement(dyn) =>
          dyn.render(false).foreach(_.foreach(el => build(el.static, el.dynamicMods, strw)))
        case Content.DynElementColl(dyn) =>
          dyn
            .render(false).foreach(_.iterator.foreach(el => build(el.static, el.dynamicMods, strw)))
        case Content.DynSplit(splitVar) =>
          val (entries, _, _, _) =
            splitVar.render(false).getOrElse((List.empty, 0, true, None))
          val staticOpt = entries.collectFirst { case (value = el) => el.static }
          entries.foreach { case (_, _, value) =>
            build(staticOpt.getOrElse(Nil), value.dynamicMods, strw)
          }
        case Content.DynStream(streamVar) =>
          val (entries, _, _, _) =
            streamVar.render(false).getOrElse((List.empty, 0, true, None))
          val staticOpt = entries.collectFirst { case (value = el) => el.static }
          entries.foreach { case (_, _, value) =>
            build(staticOpt.getOrElse(Nil), value.dynamicMods, strw)
          }
        case Content.Keyed(entries, _, _) =>
          entries.foreach(entry => build(entry.element.static, entry.element.dynamicMods, strw))
      end match
    end for
    strw.write(static.last)
  end build

end HtmlBuilder
