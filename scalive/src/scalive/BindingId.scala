package scalive

import zio.json.EncoderOps

import scala.util.hashing.MurmurHash3

/** Binding IDs are internal server-side routing keys used to map client events back to handlers.
  *
  * IDs are intentionally stored on the binding node itself (`Mod.Attr.Binding` and JS push
  * bindings) rather than in a separate registry. The HTML tree is already the source of truth for
  * diffing and lookup (`findBinding`), so keeping the ID on the node makes IDs naturally follow the
  * node lifecycle across diffs.
  *
  * New bindings start with a pending marker. During render/diff, pending IDs are replaced with
  * monotonic `b<N>` IDs using a caller-provided cursor. The cursor state is owned by the
  * `HtmlElement` instance (one render tree), which keeps allocation scoped to that tree and avoids
  * global mutable state.
  */
private[scalive] object BindingId:
  private val PendingValue = "__scalive_pending_binding_id__"

  final private class Allocator(var nextId: Long):
    def allocate(): String =
      val id = nextId
      nextId = nextId + 1
      s"b$id"

  def pending(): String = PendingValue

  def isPending(id: String): Boolean = id == PendingValue

  def assignPending(root: HtmlElement, startAt: Long): Long =
    val allocator = new Allocator(startAt)
    assignInElement(root, allocator)
    allocator.nextId

  private def assignInElement(el: HtmlElement, allocator: Allocator): Unit =
    assignInElement(el, allocator, () => allocator.allocate())

  private def assignInElement(
    el: HtmlElement,
    allocator: Allocator,
    nextId: () => String
  ): Unit =
    el.mods.foreach(assignInMod(_, allocator, nextId))

  private def assignInMod(mod: Mod, allocator: Allocator, nextId: () => String): Unit =
    mod match
      case Mod.Attr.Binding(_, id, _) =>
        id match
          case idVar: Var[String] if isPending(idVar.currentValue) =>
            idVar.set(nextId())
          case _ => ()
      case Mod.Attr.JsBinding(_, jsonValue, command) =>
        command.assignPendingBindingIds(nextId)
        jsonValue.set(command.toJson)
      case Mod.Content.Tag(child)            => assignInElement(child, allocator, nextId)
      case Mod.Content.DynElement(dyn)       => assignInElement(dyn.currentValue, allocator, nextId)
      case Mod.Content.DynOptionElement(dyn) =>
        dyn.currentValue.foreach(assignInElement(_, allocator, nextId))
      case Mod.Content.DynElementColl(dyn) =>
        dyn.currentValue.iterator.foreach(assignInElement(_, allocator, nextId))
      case Mod.Content.DynSplit(splitVar) =>
        splitVar.currentValues.iterator.foreach(assignInElement(_, allocator, nextId))
      case Mod.Content.DynStream(streamVar) =>
        streamVar.currentValues.iterator.foreach(assignInElement(_, allocator, nextId))
      case Mod.Content.Keyed(entries, _, allEntries) =>
        val keyedEntries = allEntries.getOrElse(entries)
        val groupSalt    = allocator.allocate().drop(1)
        keyedEntries.foreach { entry =>
          var localIndex  = 0
          val keyPrefix   = stableKeyPrefix(entry.key)
          val keyedNextId = () =>
            val id = s"b${groupSalt}_${keyPrefix}_$localIndex"
            localIndex = localIndex + 1
            id
          assignInElement(entry.element, allocator, keyedNextId)
        }
      case _ => ()

  private def stableKeyPrefix(key: Any): String =
    Integer.toUnsignedString(MurmurHash3.stringHash(String.valueOf(key)), 36)
end BindingId
