package scalive

import java.io.StringWriter

import scalive.Mod.Attr
import scalive.Mod.Content

object HtmlBuilder:

  def build(el: HtmlElement, isRoot: Boolean = false): String =
    val strw = new StringWriter()
    if isRoot then strw.write("<!doctype html>")
    buildElement(el, strw, path = BindingId.rootPath(el.tag.name))
    strw.toString()

  private def buildElement(el: HtmlElement, strw: StringWriter, path: BindingId.Path): Unit =
    strw.write(s"<${el.tag.name}")

    el.attrMods.zipWithIndex.foreach { case (attr, attrIndex) =>
      attr match
        case Attr.Static(name, value) =>
          strw.write(s" $name=\"")
          strw.writeEscaped(value)
          strw.write("\"")
        case Attr.StaticValueAsPresence(name, value) =>
          if value then strw.write(s" $name")
        case Attr.Binding(name, _) =>
          strw.write(s" $name=\"")
          strw.writeEscaped(BindingId.attrBindingId(path, attrIndex))
          strw.write("\"")
        case Attr.JsBinding(name, command) =>
          strw.write(s" $name='")
          strw.writeEscaped(command.renderJson(BindingId.jsBindingScope(path, attrIndex)))
          strw.write("'")
    }

    if el.tag.void then strw.write("/>")
    else
      strw.write(">")
      var structuralChildIndex = 0
      el.contentMods.foreach {
        case Content.Text(text, raw) =>
          if raw then strw.write(text)
          else strw.writeEscaped(text)
        case Content.Tag(child) =>
          val childPath = BindingId.childTagPath(path, structuralChildIndex, child.tag.name)
          structuralChildIndex = structuralChildIndex + 1
          buildElement(child, strw, childPath)
        case Content.Component(cid, child) =>
          val componentPath = BindingId.childComponentPath(path, structuralChildIndex, cid)
          structuralChildIndex = structuralChildIndex + 1
          buildElement(child, strw, componentPath)
        case Content.Keyed(entries, _, _) =>
          val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
          structuralChildIndex = structuralChildIndex + 1
          entries.foreach(entry =>
            buildElement(entry.element, strw, BindingId.keyedEntryPath(keyedPath, entry.key))
          )
      }
      strw.write(s"</${el.tag.name}>")
  end buildElement

end HtmlBuilder
