package scalive

import scalive.Mod.Attr
import scalive.Mod.Content

object DiffBuilder:
  def build(el: HtmlElement, trackUpdates: Boolean = true): Diff =
    build(
      static = if trackUpdates then Seq.empty else el.static,
      dynamicMods = el.dynamicMods,
      trackUpdates = trackUpdates
    )

  private def build(static: Seq[String], dynamicMods: Seq[DynamicMod], trackUpdates: Boolean)
    : Diff =
    Diff.Tag(
      static = static,
      dynamic =
        buildDynamic(dynamicMods, trackUpdates).zipWithIndex.collect { case (Some(diff), index) =>
          Diff.Dynamic(index.toString, diff)
        }
    )

  private def buildDynamic(dynamicMods: Seq[DynamicMod], trackUpdates: Boolean): Seq[Option[Diff]] =
    dynamicMods.flatMap {
      case Attr.Dyn(name, value, _) =>
        List(value.render(trackUpdates).map(v => Diff.Value(v.toString)))
      case Attr.DynValueAsPresence(name, value) =>
        List(value.render(trackUpdates).map(v => Diff.Value(if v then s" $name" else "")))
      case Content.Tag(el)               => buildDynamic(el.dynamicMods, trackUpdates)
      case Content.DynText(dyn)          => List(dyn.render(trackUpdates).map(Diff.Value(_)))
      case Content.DynElement(dyn)       => ???
      case Content.DynOptionElement(dyn) =>
        List(dyn.render(trackUpdates) match
          // Element is added
          case Some(Some(el)) => Some(build(el, trackUpdates = false))
          // Element is removed
          case Some(None) => Some(Diff.Deleted)
          // Element is updated if present
          case None => dyn.currentValue.map(build(_, trackUpdates)))
      case Content.DynElementColl(dyn) => ???
      case Content.DynSplit(splitVar)  =>
        splitVar.render(trackUpdates) match
          case Some((entries, keysCount, includeStatics)) =>
            val static =
              if !trackUpdates || includeStatics then
                entries.collectFirst { case (_, el) => el.static }.getOrElse(List.empty)
              else List.empty
            List(
              Some(
                Diff.Comprehension(
                  static = static,
                  entries = entries.map((key, el) =>
                    Diff.Dynamic(key.toString, build(Seq.empty, el.dynamicMods, trackUpdates))
                  ),
                  count = keysCount
                )
              )
            )
          case None => List(None)
    }

end DiffBuilder
