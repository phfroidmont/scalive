package scalive

import java.io.StringWriter

object HtmlBuilder:

  def build(lv: LiveView[?]): String =
    val strw = new StringWriter()
    build(lv.static, lv.dynamic, strw)
    strw.toString()

  private def build(
      static: Seq[String],
      dynamic: Seq[LiveMod[?]],
      strw: StringWriter
  ): Unit =
    for i <- dynamic.indices do
      strw.append(static(i))
      dynamic(i) match
        case mod: LiveMod.Dynamic[?, ?] =>
          strw.append(mod.currentValue.toString)
        case mod: LiveMod.When[?]     => build(mod, strw)
        case mod: LiveMod.Split[?, ?] => build(mod, strw)
    strw.append(static.last)

  private def build(mod: LiveMod.When[?], strw: StringWriter): Unit =
    if mod.displayed then build(mod.nested.static, mod.nested.dynamic, strw)

  private def build(mod: LiveMod.Split[?, ?], strw: StringWriter): Unit =
    mod.dynamic.foreach(entry => build(mod.static, entry, strw))
