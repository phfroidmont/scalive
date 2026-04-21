package scalive

import scala.collection.mutable

import scalive.Mod.Attr
import scalive.Mod.Content

private[scalive] object TreeDiff:

  def initial(root: HtmlElement): Diff =
    val compiled = compileElement(
      root,
      isTopLevel = true,
      path = BindingId.rootPath(root.tag.name)
    )
    val componentNodes = collectComponents(compiled)
    val withComponents = withComponentDiffs(
      diff = fullNode(compiled, includeStatic = true),
      previous = Map.empty,
      current = componentNodes,
      includeAll = true
    )

    withComponentStaticSharing(
      previous = Map.empty,
      diff = withTemplateSharing(withComponents)
    )

  def diff(previous: HtmlElement, current: HtmlElement): Diff =
    val previousCompiled = compileElement(
      previous,
      isTopLevel = true,
      path = BindingId.rootPath(previous.tag.name)
    )
    val currentCompiled = compileElement(
      current,
      isTopLevel = true,
      path = BindingId.rootPath(current.tag.name)
    )
    val previousComponents = collectComponents(previousCompiled)
    val currentComponents  = collectComponents(currentCompiled)

    val raw = withComponentDiffs(
      diff = diffNode(previousCompiled, currentCompiled).getOrElse(Diff.Tag()),
      previous = previousComponents,
      current = currentComponents,
      includeAll = false
    )

    withComponentStaticSharing(
      previous = previousComponents,
      diff = withTemplateSharing(raw)
    )

  sealed private trait CompiledNode

  final private case class TagNode(
    static: Vector[String],
    slots: Vector[CompiledSlot],
    root: Boolean)
      extends CompiledNode

  final private case class KeyedNode(
    entries: Vector[KeyedEntry],
    stream: Option[Diff.Stream])
      extends CompiledNode

  final private case class KeyedEntry(key: Any, node: CompiledNode)

  final private case class CompiledComponent(
    cid: Int,
    node: CompiledNode)

  sealed private trait CompiledSlot
  final private case class StringSlot(value: String)                   extends CompiledSlot
  final private case class NodeSlot(node: CompiledNode)                extends CompiledSlot
  final private case class KeyedSlot(node: KeyedNode)                  extends CompiledSlot
  final private case class ComponentSlot(component: CompiledComponent) extends CompiledSlot

  private def compileElement(
    el: HtmlElement,
    isTopLevel: Boolean,
    path: BindingId.Path
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

    el.attrMods.zipWithIndex.foreach { case (attr, attrIndex) =>
      attr match
        case Attr.Static(name, value) =>
          staticFragment += s" $name=\""
          pushStringSlot(Escaping.escape(value))
          staticFragment += "\""
        case Attr.StaticValueAsPresence(name, value) =>
          if value then staticFragment += s" $name"
        case Attr.Binding(name, _) =>
          staticFragment += s" $name=\""
          pushStringSlot(Escaping.escape(BindingId.attrBindingId(path, attrIndex)))
          staticFragment += "\""
        case Attr.JsBinding(name, command) =>
          staticFragment += s" $name='"
          val scope = BindingId.jsBindingScope(path, attrIndex)
          pushStringSlot(Escaping.escape(command.renderJson(scope)))
          staticFragment += "'"
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
        pushNodeSlot(compileElement(child, isTopLevel = false, path = childPath))
      case Content.Component(cid, child) =>
        val componentPath = BindingId.childComponentPath(path, structuralChildIndex, cid)
        structuralChildIndex = structuralChildIndex + 1
        pushComponentSlot(
          CompiledComponent(
            cid,
            compileElement(child, isTopLevel = false, path = componentPath)
          )
        )
      case Content.Keyed(entries, stream, _) =>
        val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
        structuralChildIndex = structuralChildIndex + 1
        val keyedEntries = entries.map(entry =>
          KeyedEntry(
            entry.key,
            compileElement(
              entry.element,
              isTopLevel = false,
              path = BindingId.keyedEntryPath(keyedPath, entry.key)
            )
          )
        )
        pushKeyedSlot(KeyedNode(keyedEntries, stream))
    }

    if !el.tag.void then staticFragment += s"</${el.tag.name}>"
    staticBuilder += staticFragment

    TagNode(
      static = staticBuilder.result(),
      slots = slotBuilder.result(),
      root = !isTopLevel
    )
  end compileElement

  private def slotShape(slot: CompiledSlot): String =
    slot match
      case _: StringSlot    => "string"
      case _: NodeSlot      => "node"
      case _: KeyedSlot     => "keyed"
      case _: ComponentSlot => "component"

  private def sameTagShape(left: TagNode, right: TagNode): Boolean =
    left.static == right.static &&
      left.slots.length == right.slots.length &&
      left.slots.indices.forall(i => slotShape(left.slots(i)) == slotShape(right.slots(i)))

  private def diffNode(previous: CompiledNode, current: CompiledNode): Option[Diff] =
    (previous, current) match
      case (left: TagNode, right: TagNode) =>
        if !sameTagShape(left, right) then Some(fullNode(right, includeStatic = true))
        else
          val dynamic: Vector[Diff.Dynamic] = right.slots.zipWithIndex.flatMap {
            case (slot, index) =>
              diffSlot(left.slots(index), slot)
                .map(diff => Diff.Dynamic(index, diff).asInstanceOf[Diff.Dynamic])
          }
          Option.when(dynamic.nonEmpty)(
            Diff.Tag(
              dynamic = dynamic,
              root = right.root
            )
          )
      case (left: KeyedNode, right: KeyedNode) =>
        diffKeyed(left, right)
      case _ =>
        Some(fullNode(current, includeStatic = true))

  private def diffSlot(previous: CompiledSlot, current: CompiledSlot): Option[Diff] =
    (previous, current) match
      case (StringSlot(left), StringSlot(right)) =>
        Option.when(left != right)(Diff.Value(right))
      case (NodeSlot(left), NodeSlot(right)) =>
        diffNode(left, right)
      case (KeyedSlot(left), KeyedSlot(right)) =>
        diffKeyed(left, right)
      case (ComponentSlot(left), ComponentSlot(right)) =>
        Option.when(left.cid != right.cid)(Diff.ComponentRef(right.cid))
      case _ =>
        Some(fullSlot(current))

  private def fullSlot(slot: CompiledSlot): Diff =
    slot match
      case StringSlot(value)        => Diff.Value(value)
      case NodeSlot(node)           => fullNode(node, includeStatic = true)
      case KeyedSlot(node)          => fullNode(node, includeStatic = true)
      case ComponentSlot(component) => Diff.ComponentRef(component.cid)

  private def fullNode(node: CompiledNode, includeStatic: Boolean): Diff =
    node match
      case tag: TagNode =>
        Diff.Tag(
          static = if includeStatic then tag.static else Vector.empty,
          dynamic = tag.slots.zipWithIndex.map { case (slot, index) =>
            Diff.Dynamic(index, fullSlot(slot)).asInstanceOf[Diff.Dynamic]
          },
          root = tag.root
        )
      case keyed: KeyedNode =>
        fullKeyed(keyed, includeStatic = includeStatic)

  private def keyedSharedStatic(entries: Vector[KeyedEntry]): Option[Vector[String]] =
    entries.headOption.flatMap {
      case KeyedEntry(_, tag: TagNode) =>
        val same = entries.forall {
          case KeyedEntry(_, other: TagNode) => other.static == tag.static
          case _                             => false
        }
        Option.when(same)(tag.static)
      case _ => None
    }

  private def fullKeyed(node: KeyedNode, includeStatic: Boolean): Diff.Comprehension =
    val sharedStatic = keyedSharedStatic(node.entries)
    val static       =
      if includeStatic then sharedStatic.getOrElse(Vector.empty)
      else Vector.empty

    val entries: Vector[Diff.Dynamic] = node.entries.zipWithIndex.map { case (entry, index) =>
      val includeEntryStatic = sharedStatic.isEmpty
      Diff
        .Dynamic(index, fullNode(entry.node, includeStatic = includeEntryStatic))
        .asInstanceOf[Diff.Dynamic]
    }

    Diff.Comprehension(
      static = static,
      entries = entries,
      count = node.entries.length,
      stream = node.stream
    )

  private def diffKeyed(previous: KeyedNode, current: KeyedNode): Option[Diff] =
    val previousStatic = keyedSharedStatic(previous.entries)
    val currentStatic  = keyedSharedStatic(current.entries)
    val hasStreamPatch = current.stream.nonEmpty && current.stream != previous.stream

    if hasStreamPatch then
      Some(
        fullKeyed(
          current,
          includeStatic = previous.entries.isEmpty || previousStatic != currentStatic
        )
      )
    else if previousStatic != currentStatic && currentStatic.nonEmpty then
      Some(fullKeyed(current, includeStatic = true))
    else
      val previousByKey = previous.entries.zipWithIndex.map { case (entry, index) =>
        entry.key -> (index, entry.node)
      }.toMap

      val entries: Vector[Diff.Dynamic | Diff.IndexChange | Diff.IndexMerge] =
        current.entries.zipWithIndex.flatMap { case (entry, index) =>
          previousByKey.get(entry.key) match
            case None =>
              val includeStaticInEntry = currentStatic.isEmpty
              Some(
                Diff
                  .Dynamic(index, fullNode(entry.node, includeStatic = includeStaticInEntry))
                  .asInstanceOf[Diff.Dynamic]
              )
            case Some((previousIndex, previousNode)) =>
              val maybeDiff = diffNode(previousNode, entry.node)
              if previousIndex == index then
                maybeDiff.map(diff => Diff.Dynamic(index, diff).asInstanceOf[Diff.Dynamic])
              else
                maybeDiff match
                  case Some(diff) => Some(Diff.IndexMerge(index, previousIndex, diff))
                  case None       => Some(Diff.IndexChange(index, previousIndex))
        }

      val includeStatic =
        currentStatic.nonEmpty && previous.entries.isEmpty && current.entries.nonEmpty

      val hasCountChange = previous.entries.length != current.entries.length

      if entries.isEmpty && !hasCountChange && !includeStatic then None
      else
        Some(
          Diff.Comprehension(
            static = if includeStatic then currentStatic.getOrElse(Vector.empty) else Vector.empty,
            entries = entries,
            count = current.entries.length,
            stream = None
          )
        )
    end if
  end diffKeyed

  private def withComponentDiffs(
    diff: Diff,
    previous: Map[Int, CompiledNode],
    current: Map[Int, CompiledNode],
    includeAll: Boolean
  ): Diff =
    diff match
      case tag: Diff.Tag =>
        val componentDiffs = current.toSeq.flatMap { case (cid, currentNode) =>
          val nodeDiff =
            if includeAll then Some(fullNode(currentNode, includeStatic = true))
            else
              previous.get(cid) match
                case Some(previousNode) => diffNode(previousNode, currentNode)
                case None               => Some(fullNode(currentNode, includeStatic = true))

          nodeDiff.filterNot(_.isEmpty).map(cid -> _)
        }.toMap
        tag.copy(components = componentDiffs)
      case other => other

  private def withComponentStaticSharing(
    previous: Map[Int, CompiledNode],
    diff: Diff
  ): Diff =
    diff match
      case tag: Diff.Tag if tag.components.nonEmpty =>
        val previousStatics = previous.flatMap { case (cid, node) =>
          tagStatic(node).map(cid -> _)
        }

        val seenCurrentStatics = mutable.Map.empty[Vector[String], Int]

        val sharedComponents = tag.components.toSeq
          .sortBy(_._1).map { case (cid, componentDiff) =>
            componentDiff match
              case componentTag: Diff.Tag
                  if componentTag.static.nonEmpty && componentTag.templateRef.isEmpty =>
                val static   = componentTag.static.toVector
                val maybeRef =
                  seenCurrentStatics
                    .get(static).orElse(
                      previousStatics.collectFirst {
                        case (previousCid, previousStatic)
                            if previousCid != cid && previousStatic == static =>
                          -previousCid
                      }
                    )

                maybeRef match
                  case Some(ref) =>
                    cid -> componentTag.copy(
                      static = Vector.empty,
                      templateRef = Some(ref)
                    )
                  case None =>
                    seenCurrentStatics.update(static, cid)
                    cid -> componentTag
              case _ =>
                cid -> componentDiff
          }.toMap

        tag.copy(components = sharedComponents)
      case other => other

  private def tagStatic(node: CompiledNode): Option[Vector[String]] =
    node match
      case TagNode(static, _, _) => Some(static)
      case _                     => None

  private def collectComponents(node: CompiledNode): Map[Int, CompiledNode] =
    val acc = mutable.LinkedHashMap.empty[Int, CompiledNode]

    def loopNode(current: CompiledNode): Unit =
      current match
        case TagNode(_, slots, _)  => slots.foreach(loopSlot)
        case KeyedNode(entries, _) => entries.foreach(entry => loopNode(entry.node))

    def loopSlot(slot: CompiledSlot): Unit =
      slot match
        case NodeSlot(node)           => loopNode(node)
        case KeyedSlot(node)          => loopNode(node)
        case ComponentSlot(component) =>
          acc.update(component.cid, component.node)
          loopNode(component.node)
        case _ => ()

    loopNode(node)
    acc.toMap

  private def withTemplateSharing(diff: Diff): Diff =
    diff match
      case tag: Diff.Tag =>
        val counts = mutable.Map.empty[Vector[String], Int].withDefaultValue(0)

        def collect(node: Diff): Unit =
          node match
            case Diff.Tag(static, dynamic, _, _, _, _, _, _) =>
              if static.nonEmpty then counts.update(static.toVector, counts(static.toVector) + 1)
              dynamic.foreach(d => collect(d.diff))
            case Diff.Comprehension(_, entries, _, _) =>
              entries.foreach {
                case Diff.Dynamic(_, child)       => collect(child)
                case Diff.IndexChange(_, _)       => ()
                case Diff.IndexMerge(_, _, child) => collect(child)
              }
            case _ => ()

        collect(tag)

        val shared =
          counts.toSeq.collect { case (static, count) if count > 1 => static }.sortBy(_.mkString)
        if shared.isEmpty then tag
        else
          val refs = shared.zipWithIndex.toMap

          def rewrite(node: Diff): Diff =
            node match
              case Diff.Tag(
                    static,
                    dynamic,
                    events,
                    root,
                    title,
                    components,
                    templates,
                    templateRef
                  ) =>
                val updatedDynamic = dynamic.map(d => d.copy(diff = rewrite(d.diff)))
                refs.get(static.toVector) match
                  case Some(ref) if static.nonEmpty =>
                    Diff.Tag(
                      static = Vector.empty,
                      dynamic = updatedDynamic,
                      events = events,
                      root = root,
                      title = title,
                      components = components,
                      templates = templates,
                      templateRef = Some(ref)
                    )
                  case _ =>
                    Diff.Tag(
                      static = static,
                      dynamic = updatedDynamic,
                      events = events,
                      root = root,
                      title = title,
                      components = components,
                      templates = templates,
                      templateRef = templateRef
                    )
              case Diff.Comprehension(static, entries, count, stream) =>
                Diff.Comprehension(
                  static = static,
                  entries = entries.map {
                    case d: Diff.Dynamic     => d.copy(diff = rewrite(d.diff))
                    case d: Diff.IndexChange => d
                    case d: Diff.IndexMerge  => d.copy(diff = rewrite(d.diff))
                  },
                  count = count,
                  stream = stream
                )
              case other => other

          val rewritten = rewrite(tag).asInstanceOf[Diff.Tag]
          rewritten.copy(
            templates = rewritten.templates ++ refs.toSeq.map { case (static, ref) =>
              ref -> static
            }
          )
        end if
      case other => other

end TreeDiff
