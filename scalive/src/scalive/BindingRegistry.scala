package scalive

import scala.collection.mutable
import scala.reflect.ClassTag

import scalive.Mod.Attr
import scalive.Mod.Content

private[scalive] object BindingRegistry:
  type Handler[Msg] = Map[String, String] => Either[String, Msg]

  def collect[Msg: ClassTag](root: HtmlElement): Map[String, Handler[Msg]] =
    val acc = mutable.LinkedHashMap.empty[String, Handler[Msg]]

    def collectElement(el: HtmlElement, path: BindingId.Path): Unit =
      el.attrMods.zipWithIndex.foreach { case (attr, attrIndex) =>
        attr match
          case Attr.Binding(_, f) =>
            val id = BindingId.attrBindingId(path, attrIndex)
            acc.update(id, params => toMessage(id, f(params)))
          case Attr.JsBinding(_, command) =>
            val scope = BindingId.jsBindingScope(path, attrIndex)
            command.bindings(scope).foreach { case (id, msg) =>
              acc.update(id, _ => toMessage(id, msg))
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

  private def toMessage[Msg](
    bindingId: String,
    value: Any
  )(using
    tag: ClassTag[Msg]
  ): Either[String, Msg] =
    tag
      .unapply(value).toRight(
        s"Binding '$bindingId' produced ${valueType(value)}, expected ${tag.runtimeClass.getName}"
      )

  private def valueType(value: Any): String =
    Option(value).map(_.getClass.getName).getOrElse("null")
end BindingRegistry
