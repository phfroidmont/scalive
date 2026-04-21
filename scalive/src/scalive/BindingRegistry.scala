package scalive

import scala.collection.mutable

import scalive.Mod.Attr
import scalive.Mod.Content

private[scalive] object BindingRegistry:
  type Handler[Msg] = Map[String, String] => Msg

  def collect[Msg](root: HtmlElement): Map[String, Handler[Msg]] =
    val acc = mutable.LinkedHashMap.empty[String, Handler[Msg]]

    def collectElement(el: HtmlElement, path: BindingId.Path): Unit =
      el.attrMods.zipWithIndex.foreach { case (attr, attrIndex) =>
        attr match
          case Attr.Binding(_, f) =>
            val id = BindingId.attrBindingId(path, attrIndex)
            acc.update(id, f.asInstanceOf[Handler[Msg]])
          case Attr.JsBinding(_, command) =>
            val scope = BindingId.jsBindingScope(path, attrIndex)
            command.bindings[Msg](scope).foreach { case (id, msg) =>
              acc.update(id, _ => msg)
            }
          case _ => ()
      }

      var structuralChildIndex = 0
      el.contentMods.foreach {
        case Content.Text(_, _) =>
          ()
        case Content.Tag(child) =>
          val childPath = BindingId.childTagPath(path, structuralChildIndex, child.tag.name)
          structuralChildIndex = structuralChildIndex + 1
          collectElement(child, childPath)
        case Content.Component(cid, child) =>
          val childPath = BindingId.childComponentPath(path, structuralChildIndex, cid)
          structuralChildIndex = structuralChildIndex + 1
          collectElement(child, childPath)
        case Content.Keyed(entries, _, allEntries) =>
          val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
          structuralChildIndex = structuralChildIndex + 1
          allEntries.getOrElse(entries).foreach { entry =>
            collectElement(entry.element, BindingId.keyedEntryPath(keyedPath, entry.key))
          }
      }
    end collectElement

    collectElement(root, BindingId.rootPath(root.tag.name))
    acc.toMap
  end collect
end BindingRegistry
