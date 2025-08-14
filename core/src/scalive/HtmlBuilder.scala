package scalive

import java.io.StringWriter

object HtmlBuilder:

  def build(lv: LiveView[?]): String =
    val strw = new StringWriter()
    build(lv.static, lv.dynamic, strw)
    strw.toString()

  private def build(
      static: Seq[String],
      dynamic: Seq[LiveDyn[?]],
      strw: StringWriter
  ): Unit =
    for i <- dynamic.indices do
      strw.append(static(i))
      dynamic(i) match
        case mod: LiveDyn.Value[?, ?] =>
          strw.append(mod.currentValue.toString)
        case mod: LiveDyn.When[?]     => build(mod, strw)
        case mod: LiveDyn.Split[?, ?] => build(mod, strw)
    strw.append(static.last)

  private def build(mod: LiveDyn.When[?], strw: StringWriter): Unit =
    if mod.displayed then build(mod.nested.static, mod.nested.dynamic, strw)

  private def build(mod: LiveDyn.Split[?, ?], strw: StringWriter): Unit =
    mod.dynamic.foreach(entry => build(mod.static, entry, strw))
