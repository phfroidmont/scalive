package scalive

import zio.json.EncoderOps

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
    el.mods.foreach(assignInMod(_, allocator))

  private def assignInMod(mod: Mod, allocator: Allocator): Unit =
    mod match
      case Mod.Attr.Binding(_, id, _) =>
        id match
          case idVar: Var[String] if isPending(idVar.currentValue) =>
            idVar.set(allocator.allocate())
          case _ => ()
      case Mod.Attr.JsBinding(_, jsonValue, command) =>
        command.assignPendingBindingIds(() => allocator.allocate())
        jsonValue.set(command.toJson)
      case Mod.Content.Tag(child)            => assignInElement(child, allocator)
      case Mod.Content.DynElement(dyn)       => assignInElement(dyn.currentValue, allocator)
      case Mod.Content.DynOptionElement(dyn) =>
        dyn.currentValue.foreach(assignInElement(_, allocator))
      case Mod.Content.DynElementColl(dyn) =>
        dyn.currentValue.iterator.foreach(assignInElement(_, allocator))
      case Mod.Content.DynSplit(splitVar) =>
        splitVar.currentValues.iterator.foreach(assignInElement(_, allocator))
      case _ => ()
end BindingId
