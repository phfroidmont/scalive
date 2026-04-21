package scalive

import java.io.StringWriter

import zio.json.EncoderOps

import scalive.Mod.Attr
import scalive.Mod.Content

object HtmlBuilder:

  def build(el: HtmlElement, isRoot: Boolean = false): String =
    val strw = new StringWriter()
    if isRoot then strw.write("<!doctype html>")
    buildElement(el, strw)
    strw.toString()

  private def buildElement(el: HtmlElement, strw: StringWriter): Unit =
    strw.write(s"<${el.tag.name}")

    el.attrMods.foreach {
      case Attr.Static(name, value) =>
        strw.write(s" $name=\"")
        strw.writeEscaped(value)
        strw.write("\"")
      case Attr.StaticValueAsPresence(name, value) =>
        if value then strw.write(s" $name")
      case Attr.Binding(name, id, _) =>
        strw.write(s" $name=\"")
        strw.writeEscaped(id.currentValue)
        strw.write("\"")
      case Attr.JsBinding(name, command) =>
        strw.write(s" $name='")
        strw.writeEscaped(command.toJson)
        strw.write("'")
    }

    if el.tag.void then strw.write("/>")
    else
      strw.write(">")
      el.contentMods.foreach {
        case Content.Text(text, raw) =>
          if raw then strw.write(text)
          else strw.writeEscaped(text)
        case Content.Tag(child) =>
          buildElement(child, strw)
        case Content.Component(_, child) =>
          buildElement(child, strw)
        case Content.Keyed(entries, _, _) =>
          entries.foreach(entry => buildElement(entry.element, strw))
      }
      strw.write(s"</${el.tag.name}>")
  end buildElement

end HtmlBuilder
