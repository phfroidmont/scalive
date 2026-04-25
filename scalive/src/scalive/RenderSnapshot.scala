package scalive

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.util.hashing.MurmurHash3

import scalive.Mod.Attr
import scalive.Mod.Content

private[scalive] object RenderSnapshot:
  type RawBindingHandler = BindingPayload => Any

  final case class Compiled(
    root: TagNode,
    bindings: Map[String, RawBindingHandler],
    trackedStaticUrls: Vector[String])

  sealed trait CompiledNode:
    def fingerprint: Int
    def templateFingerprint: Int

  final case class TagNode(
    static: Vector[String],
    slots: Vector[CompiledSlot],
    root: Boolean,
    fingerprint: Int,
    templateFingerprint: Int)
      extends CompiledNode

  final case class KeyedNode(
    entries: Vector[KeyedEntry],
    stream: Option[Diff.Stream],
    fingerprint: Int,
    templateFingerprint: Int,
    entryFingerprints: Option[Map[Any, Int]])
      extends CompiledNode

  final case class KeyedEntry(key: Any, node: CompiledNode, fingerprint: Option[Int])

  final case class CompiledComponent(
    cid: Int,
    node: CompiledNode)

  sealed trait CompiledSlot
  final case class StringSlot(value: String)                   extends CompiledSlot
  final case class NodeSlot(node: CompiledNode)                extends CompiledSlot
  final case class KeyedSlot(node: KeyedNode)                  extends CompiledSlot
  final case class ComponentSlot(component: CompiledComponent) extends CompiledSlot

  private val trackStaticAttrName           = phx.trackStatic.name
  private val urlAttrNames                  = Set(href.name, src.name)
  private val stringSlotTemplateFingerprint = MurmurHash3.stringHash("string-slot")

  private object RenderArtifactCache:
    private val staticPool = new ConcurrentHashMap[Vector[String], Vector[String]]()

    def internStatic(static: Vector[String]): Vector[String] =
      val existing = staticPool.putIfAbsent(static, static)
      if existing == null then static else existing

  def compile(root: HtmlElement[?]): Compiled =
    val bindings = mutable.LinkedHashMap.empty[String, RawBindingHandler]
    val tracked  = mutable.ArrayBuffer.empty[String]

    val rootNode = compileElement(
      root,
      isTopLevel = true,
      path = BindingId.rootPath(root.tag.name),
      bindings = bindings,
      trackedStaticUrls = tracked
    )

    Compiled(
      root = rootNode,
      bindings = bindings.toMap,
      trackedStaticUrls = tracked.toVector
    )

  def renderHtml(compiled: Compiled, isRoot: Boolean = false): String =
    val out = new StringBuilder()
    if isRoot then out.append("<!doctype html>")
    appendNode(compiled.root, out)
    out.toString

  private def appendNode(node: CompiledNode, out: StringBuilder): Unit =
    node match
      case TagNode(static, slots, _, _, _) =>
        var index = 0
        while index < slots.length do
          out.append(static(index))
          appendSlot(slots(index), out)
          index = index + 1
        out.append(static.lastOption.getOrElse(""))
      case KeyedNode(entries, _, _, _, _) =>
        entries.foreach(entry => appendNode(entry.node, out))

  private def appendSlot(slot: CompiledSlot, out: StringBuilder): Unit =
    slot match
      case StringSlot(value)        => out.append(value)
      case NodeSlot(node)           => appendNode(node, out)
      case KeyedSlot(node)          => appendNode(node, out)
      case ComponentSlot(component) => appendNode(component.node, out)

  private def compileElement(
    el: HtmlElement[?],
    isTopLevel: Boolean,
    path: BindingId.Path,
    bindings: mutable.LinkedHashMap[String, RawBindingHandler],
    trackedStaticUrls: mutable.ArrayBuffer[String]
  ): TagNode =
    val staticBuilder = Vector.newBuilder[String]
    val slotBuilder   = Vector.newBuilder[CompiledSlot]

    var staticFragment = s"<${el.tag.name}"

    def pushStringSlot(value: String): Unit =
      staticBuilder += staticFragment
      staticFragment = ""
      slotBuilder += StringSlot(value)

    def pushNodeSlot(value: CompiledNode): Unit =
      staticBuilder += staticFragment
      staticFragment = ""
      slotBuilder += NodeSlot(value)

    def pushKeyedSlot(value: KeyedNode): Unit =
      staticBuilder += staticFragment
      staticFragment = ""
      slotBuilder += KeyedSlot(value)

    def pushComponentSlot(value: CompiledComponent): Unit =
      staticBuilder += staticFragment
      staticFragment = ""
      slotBuilder += ComponentSlot(value)

    val attrs = el.attrMods
    maybeCollectTrackedStaticUrls(attrs, trackedStaticUrls)

    attrs.zipWithIndex.foreach { case (attr, attrIndex) =>
      attr match
        case Attr.Static(name, value) =>
          staticFragment += s" $name=\""
          pushStringSlot(Escaping.escape(value))
          staticFragment += "\""
        case Attr.StaticValueAsPresence(name, value) =>
          if value then staticFragment += s" $name"
        case Attr.Binding(name, f) =>
          val id = BindingId.attrBindingId(path, attrIndex)
          staticFragment += s" $name=\""
          pushStringSlot(Escaping.escape(id))
          staticFragment += "\""
          bindings.update(id, payload => f(payload.params))
        case Attr.FormBinding(name, f) =>
          val id = BindingId.attrBindingId(path, attrIndex)
          staticFragment += s" $name=\""
          pushStringSlot(Escaping.escape(id))
          staticFragment += "\""
          bindings.update(id, payload => f(payload.formData))
        case Attr.FormEventBinding(name, codec, f) =>
          val id = BindingId.attrBindingId(path, attrIndex)
          staticFragment += s" $name=\""
          pushStringSlot(Escaping.escape(id))
          staticFragment += "\""
          bindings.update(
            id,
            payload => f(payload.formEvent(codec, submitted = name == "phx-submit"))
          )
        case Attr.JsBinding(name, command) =>
          val scope = BindingId.jsBindingScope(path, attrIndex)
          staticFragment += s" $name='"
          pushStringSlot(Escaping.escape(command.renderJson(scope)))
          staticFragment += "'"
          command.bindings(scope).foreach { case (id, msg) =>
            bindings.update(id, _ => msg)
          }
    }

    staticFragment += (if el.tag.void then "/>" else ">")

    var structuralChildIndex = 0
    el.contentMods.foreach {
      case Content.Text(text, raw) =>
        if raw then pushStringSlot(text)
        else pushStringSlot(Escaping.escape(text))
      case Content.Tag(child) =>
        val childPath = BindingId.childTagPath(path, structuralChildIndex, child.tag.name)
        structuralChildIndex = structuralChildIndex + 1
        pushNodeSlot(
          compileElement(
            child,
            isTopLevel = false,
            path = childPath,
            bindings = bindings,
            trackedStaticUrls = trackedStaticUrls
          )
        )
      case Content.Component(cid, child) =>
        val componentPath = BindingId.childComponentPath(path, structuralChildIndex, cid)
        structuralChildIndex = structuralChildIndex + 1
        pushComponentSlot(
          CompiledComponent(
            cid,
            compileElement(
              child,
              isTopLevel = false,
              path = componentPath,
              bindings = bindings,
              trackedStaticUrls = trackedStaticUrls
            )
          )
        )
      case Content.LiveComponent(_) =>
        throw new IllegalStateException("live components must be resolved before rendering")
      case Content.Keyed(entries, stream, allEntries) =>
        val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
        structuralChildIndex = structuralChildIndex + 1
        val keyedEntries = entries.map(entry =>
          val node =
            compileElement(
              entry.element,
              isTopLevel = false,
              path = BindingId.keyedEntryPath(keyedPath, entry.key),
              bindings = bindings,
              trackedStaticUrls = trackedStaticUrls
            )
          KeyedEntry(
            entry.key,
            node,
            fingerprint = Option.unless(stream.nonEmpty)(keyedEntryFingerprint(entry.key, node))
          )
        )

        allEntries.foreach { expandedEntries =>
          expandedEntries.foreach { entry =>
            collectBindingsOnly(
              entry.element,
              path = BindingId.keyedEntryPath(keyedPath, entry.key),
              bindings = bindings
            )
          }
        }

        pushKeyedSlot(buildKeyedNode(keyedEntries, stream))
    }

    if !el.tag.void then staticFragment += s"</${el.tag.name}>"
    staticBuilder += staticFragment

    val rawStatic = staticBuilder.result()
    val slots     = slotBuilder.result()

    buildTagNode(
      static = rawStatic,
      slots = slots,
      root = !isTopLevel
    )
  end compileElement

  private def buildTagNode(
    static: Vector[String],
    slots: Vector[CompiledSlot],
    root: Boolean
  ): TagNode =
    val internedStatic      = RenderArtifactCache.internStatic(static)
    val templateFingerprint = hashTagTemplate(internedStatic, slots, root)
    val fingerprint         = hashTag(internedStatic, slots, root)

    TagNode(
      static = internedStatic,
      slots = slots,
      root = root,
      fingerprint = fingerprint,
      templateFingerprint = templateFingerprint
    )

  private def buildKeyedNode(
    entries: Vector[KeyedEntry],
    stream: Option[Diff.Stream]
  ): KeyedNode =
    val hasStream           = stream.nonEmpty
    val templateFingerprint =
      entries.foldLeft(MurmurHash3.mix(0x6b657965, if hasStream then 1 else 0))((acc, entry) =>
        MurmurHash3.mix(acc, entry.node.templateFingerprint)
      )
    val fingerprint =
      entries.foldLeft(MurmurHash3.mix(0x6b657966, streamHash(stream)))((acc, entry) =>
        val entryFingerprint = entry.fingerprint.getOrElse(entry.node.fingerprint)
        MurmurHash3.mix(MurmurHash3.mix(acc, stableKeyHash(entry.key)), entryFingerprint)
      )

    KeyedNode(
      entries = entries,
      stream = stream,
      fingerprint = MurmurHash3.finalizeHash(fingerprint, 1 + entries.length * 2),
      templateFingerprint = MurmurHash3.finalizeHash(templateFingerprint, 1 + entries.length),
      entryFingerprints = Option.unless(hasStream)(
        entries.iterator.flatMap(entry => entry.fingerprint.map(print => entry.key -> print)).toMap
      )
    )

  private def hashTag(static: Vector[String], slots: Vector[CompiledSlot], root: Boolean): Int =
    val withStatic = static.foldLeft(MurmurHash3.mix(0x74616766, if root then 1 else 0))(
      (acc, value) => MurmurHash3.mix(acc, value.hashCode)
    )
    val withSlots =
      slots.foldLeft(withStatic)((acc, slot) => MurmurHash3.mix(acc, slotFingerprint(slot)))
    MurmurHash3.finalizeHash(withSlots, 1 + static.length + slots.length)

  private def hashTagTemplate(
    static: Vector[String],
    slots: Vector[CompiledSlot],
    root: Boolean
  ): Int =
    val withStatic = static.foldLeft(MurmurHash3.mix(0x74616774, if root then 1 else 0))(
      (acc, value) => MurmurHash3.mix(acc, value.hashCode)
    )
    val withSlots =
      slots.foldLeft(withStatic)((acc, slot) => MurmurHash3.mix(acc, slotTemplateFingerprint(slot)))
    MurmurHash3.finalizeHash(withSlots, 1 + static.length + slots.length)

  private def slotFingerprint(slot: CompiledSlot): Int =
    slot match
      case StringSlot(value)        => value.hashCode
      case NodeSlot(node)           => node.fingerprint
      case KeyedSlot(node)          => node.fingerprint
      case ComponentSlot(component) => MurmurHash3.mix(0x636f6d70, component.cid)

  private def slotTemplateFingerprint(slot: CompiledSlot): Int =
    slot match
      case _: StringSlot            => stringSlotTemplateFingerprint
      case NodeSlot(node)           => node.templateFingerprint
      case KeyedSlot(node)          => node.templateFingerprint
      case ComponentSlot(component) => MurmurHash3.mix(0x636f6d74, component.cid)

  private def keyedEntryFingerprint(key: Any, node: CompiledNode): Int =
    val hash = MurmurHash3.mix(MurmurHash3.mix(0x656e7472, stableKeyHash(key)), node.fingerprint)
    MurmurHash3.finalizeHash(hash, 2)

  private def stableKeyHash(key: Any): Int =
    val className = Option(key).map(_.getClass.getName).getOrElse("null")
    s"$className:${String.valueOf(key)}".hashCode

  private def streamHash(stream: Option[Diff.Stream]): Int =
    stream match
      case Some(value) => value.hashCode
      case None        => 0

  private def maybeCollectTrackedStaticUrls(
    attrs: Seq[Attr[?]],
    trackedStaticUrls: mutable.ArrayBuffer[String]
  ): Unit =
    val hasTrack = attrs.exists {
      case Attr.Static(`trackStaticAttrName`, _)                => true
      case Attr.StaticValueAsPresence(`trackStaticAttrName`, v) => v
      case _                                                    => false
    }

    if hasTrack then
      attrs.foreach {
        case Attr.Static(name, value) if urlAttrNames.contains(name) =>
          trackedStaticUrls += value
        case _ => ()
      }

  private def collectBindingsOnly(
    el: HtmlElement[?],
    path: BindingId.Path,
    bindings: mutable.LinkedHashMap[String, RawBindingHandler]
  ): Unit =
    el.attrMods.zipWithIndex.foreach { case (attr, attrIndex) =>
      attr match
        case Attr.Binding(_, f) =>
          val id = BindingId.attrBindingId(path, attrIndex)
          bindings.update(id, payload => f(payload.params))
        case Attr.FormBinding(_, f) =>
          val id = BindingId.attrBindingId(path, attrIndex)
          bindings.update(id, payload => f(payload.formData))
        case Attr.JsBinding(_, command) =>
          val scope = BindingId.jsBindingScope(path, attrIndex)
          command.bindings(scope).foreach { case (id, msg) =>
            bindings.update(id, _ => msg)
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
        collectBindingsOnly(child, childPath, bindings)
      case Content.Component(cid, child) =>
        val childPath = BindingId.childComponentPath(path, structuralChildIndex, cid)
        structuralChildIndex = structuralChildIndex + 1
        collectBindingsOnly(child, childPath, bindings)
      case Content.LiveComponent(_) =>
        throw new IllegalStateException(
          "live components must be resolved before collecting bindings"
        )
      case Content.Keyed(entries, _, allEntries) =>
        val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
        structuralChildIndex = structuralChildIndex + 1
        allEntries.getOrElse(entries).foreach { entry =>
          collectBindingsOnly(
            entry.element,
            BindingId.keyedEntryPath(keyedPath, entry.key),
            bindings
          )
        }
    }
  end collectBindingsOnly
end RenderSnapshot
