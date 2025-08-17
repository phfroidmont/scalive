package scalive

import java.io.StringWriter

object HtmlBuilder:

  def build(rendered: Rendered, isRoot: Boolean = false): String =
    val strw = new StringWriter()
    if isRoot then strw.append("<!doctype html>")
    build(rendered.static, rendered.dynamic, strw)
    strw.toString()

  private def build(
    static: Seq[String],
    dynamic: Seq[Boolean => RenderedDyn],
    strw: StringWriter
  ): Unit =
    for i <- dynamic.indices do
      strw.append(static(i))
      dynamic(i)(false).foreach {
        case s: String        => strw.append(s)
        case r: Rendered      => build(r)
        case c: Comprehension => build(c, strw)
      }
    strw.append(static.last)

  private def build(comp: Comprehension, strw: StringWriter): Unit =
    comp.entries.foreach(entry => build(comp.static, entry(false).map(d => _ => d), strw))
