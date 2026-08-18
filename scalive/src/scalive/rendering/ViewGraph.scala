package scalive

import scala.collection.mutable
import scala.util.control.NonFatal

import zio.*

import scalive.Mod.Attr
import scalive.Mod.Content

/** Compiles a signal-backed view once and evaluates its staged slots without rebuilding ordinary
  * HTML.
  */
private[scalive] object ViewGraph:

  final case class Evaluated(
    compiled: RenderSnapshot.Compiled,
    evaluation: SignalEvaluation)

  final case class ResolvedContent(
    slot: RenderSnapshot.CompiledSlot,
    bindings: Map[String, RenderSnapshot.RawBindingHandler],
    trackedStaticUrls: Vector[String])

  trait Resolver:
    def component(
      spec: LiveComponentSpec[?, ?, ?, ?],
      path: BindingId.Path,
      transaction: SignalEvaluation.Transaction
    ): Task[ResolvedContent]

    def liveView(
      spec: NestedLiveViewSpec[?, ?],
      path: BindingId.Path
    ): Task[ResolvedContent]

    def flash(kind: String): Task[Option[String]]

  final class Root[Model] private[ViewGraph] (
    source: Signal.Source[Model],
    template: Template):

    def evaluate(
      model: Model,
      previous: SignalEvaluation,
      revision: Long
    ): Evaluated =
      val transaction = SignalEvaluation.begin(
        previous,
        revision,
        Map[Signal.Source[?], Any](source -> model)
      )
      try
        val evaluated = template.evaluate(transaction)
        Evaluated(
          RenderSnapshot.Compiled(
            evaluated.node,
            evaluated.bindings,
            evaluated.trackedStaticUrls
          ),
          transaction.commit()
        )
      catch
        case NonFatal(error) =>
          transaction.rollback()
          throw error

    def evaluateZIO(
      model: Model,
      previous: SignalEvaluation,
      revision: Long,
      resolver: Resolver
    ): Task[Evaluated] =
      val transaction = SignalEvaluation.begin(
        previous,
        revision,
        Map[Signal.Source[?], Any](source -> model)
      )
      template
        .evaluateZIO(transaction, resolver).map(evaluated =>
          Evaluated(
            RenderSnapshot.Compiled(
              evaluated.node,
              evaluated.bindings,
              evaluated.trackedStaticUrls
            ),
            transaction.commit()
          )
        ).onError(_ => ZIO.succeed(transaction.rollback()))

    def dispose(): Unit = template.dispose()
  end Root

  final class Component[Props, Model] private[ViewGraph] (
    propsSource: Signal.Source[Props],
    modelSource: Signal.Source[Model],
    template: Template):

    def evaluateZIO(
      props: Props,
      model: Model,
      previous: SignalEvaluation,
      revision: Long,
      resolver: Resolver,
      parentTransaction: Option[SignalEvaluation.Transaction] = None
    ): Task[Evaluated] =
      val transaction = SignalEvaluation.begin(
        previous,
        revision,
        Map[Signal.Source[?], Any](propsSource -> props, modelSource -> model)
      )
      template
        .evaluateZIO(transaction, resolver).map(evaluated =>
          Evaluated(
            RenderSnapshot.Compiled(
              evaluated.node,
              evaluated.bindings,
              evaluated.trackedStaticUrls
            ),
            parentTransaction.fold(transaction.commit())(transaction.deferCommitTo)
          )
        ).onError(_ => ZIO.succeed(transaction.rollback()))

    def dispose(): Unit = template.dispose()
  end Component

  def build[Model](view: Signal[Model] => HtmlElement[?]): Root[Model] =
    val scope  = SignalScope.root()
    val source = Signal.source[Model](scope)
    try new Root(source, Compiler.compile(view(source), scope, root = false))
    catch
      case NonFatal(error) =>
        scope.dispose()
        throw error

  def buildComponent[Props, Model](
    view: (Signal[Props], Signal[Model]) => HtmlElement[?],
    rootPath: BindingId.Path
  ): Component[Props, Model] =
    val scope       = SignalScope.root()
    val propsSource = Signal.source[Props](scope)
    val modelSource = Signal.source[Model](scope)
    try
      new Component(
        propsSource,
        modelSource,
        Compiler.compile(view(propsSource, modelSource), scope, root = true, rootPath)
      )
    catch
      case NonFatal(error) =>
        scope.dispose()
        throw error

  final private case class EvaluatedNode(
    node: RenderSnapshot.TagNode,
    bindings: Map[String, RenderSnapshot.RawBindingHandler],
    trackedStaticUrls: Vector[String])

  final private case class EvaluatedSlot(
    slot: RenderSnapshot.CompiledSlot,
    bindings: Map[String, RenderSnapshot.RawBindingHandler] = Map.empty,
    trackedStaticUrls: Vector[String] = Vector.empty)

  sealed private trait NodeTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedNode
    def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedNode]
    def dispose(): Unit

  final private case class DynamicBinding(
    id: String,
    signal: Signal[Any],
    run: (Any, BindingPayload) => Any)

  final private case class Template(
    static: Vector[String],
    slots: Vector[SlotTemplate],
    bindings: Map[String, RenderSnapshot.RawBindingHandler],
    dynamicBindings: Vector[DynamicBinding],
    trackedStaticUrls: Vector[String],
    root: Boolean,
    scope: SignalScope)
      extends NodeTemplate:

    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedNode =
      val evaluatedSlots = slots.map(_.evaluate(transaction))
      assemble(transaction, evaluatedSlots)

    def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedNode] =
      ZIO.foreach(slots)(_.evaluateZIO(transaction, resolver)).map(assemble(transaction, _))

    private def assemble(
      transaction: SignalEvaluation.Transaction,
      evaluatedSlots: Vector[EvaluatedSlot]
    ): EvaluatedNode =
      val currentDynamicBindings = dynamicBindings.iterator.map { binding =>
        val value = transaction.sample(binding.signal).value
        binding.id -> ((payload: BindingPayload) => binding.run(value, payload))
      }.toMap
      val activeBindings = evaluatedSlots.foldLeft(bindings ++ currentDynamicBindings) {
        case (all, evaluated) => all ++ evaluated.bindings
      }
      val activeTracked = evaluatedSlots.foldLeft(trackedStaticUrls) { case (all, evaluated) =>
        all ++ evaluated.trackedStaticUrls
      }
      EvaluatedNode(
        RenderSnapshot.buildTagNode(static, evaluatedSlots.map(_.slot), root),
        activeBindings,
        activeTracked
      )

    def dispose(): Unit =
      slots.foreach(_.dispose())
      scope.dispose()
  end Template

  final private case class RootSlotTemplate(slot: SlotTemplate, scope: SignalScope)
      extends NodeTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedNode =
      toNode(slot.evaluate(transaction))

    def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedNode] =
      slot.evaluateZIO(transaction, resolver).map(toNode)

    private def toNode(evaluated: EvaluatedSlot): EvaluatedNode =
      evaluated.slot match
        case RenderSnapshot.NodeSlot(node: RenderSnapshot.TagNode) =>
          EvaluatedNode(node, evaluated.bindings, evaluated.trackedStaticUrls)
        case other =>
          throw new IllegalStateException(
            s"nested LiveView row root resolved to ${other.getClass.getSimpleName}"
          )

    def dispose(): Unit =
      slot.dispose()
      scope.dispose()

  sealed private trait SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot

    def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      _resolver: Resolver
    ): Task[EvaluatedSlot] =
      val _ = _resolver
      ZIO.attempt(evaluate(transaction))

    def dispose(): Unit = ()

  final private case class ScalarSlot(
    signal: Signal[String],
    render: String => String)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      EvaluatedSlot(RenderSnapshot.StringSlot(render(transaction.sample(signal).value)))

  final private case class ConstantSlot(value: String) extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      EvaluatedSlot(RenderSnapshot.StringSlot(value))

  final private case class JsCommandSlot(
    command: Signal[JSCommands.JSCommand[Any]],
    scope: String)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val current = transaction.sample(command).value
      EvaluatedSlot(
        RenderSnapshot.StringSlot(Escaping.escape(current.renderJson(scope))),
        current.bindings(scope).map((id, message) => id -> ((_: BindingPayload) => message))
      )

  final private case class ChoiceSlot(
    value: Signal[Any],
    branches: Vector[(Any, Template)])
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val selected = transaction.sample(value).value
      branches.find((key, _) => key == selected) match
        case Some((_, branch)) =>
          val evaluated = branch.evaluate(transaction)
          EvaluatedSlot(
            RenderSnapshot.NodeSlot(evaluated.node),
            evaluated.bindings,
            evaluated.trackedStaticUrls
          )
        case None => EvaluatedSlot(RenderSnapshot.StringSlot(""))

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val selected = transaction.sample(value).value
      branches.find((key, _) => key == selected) match
        case Some((_, branch)) =>
          branch
            .evaluateZIO(transaction, resolver).map(evaluated =>
              EvaluatedSlot(
                RenderSnapshot.NodeSlot(evaluated.node),
                evaluated.bindings,
                evaluated.trackedStaticUrls
              )
            )
        case None => ZIO.succeed(EvaluatedSlot(RenderSnapshot.StringSlot("")))

    override def dispose(): Unit = branches.foreach(_._2.dispose())
  end ChoiceSlot

  final private case class ChoiceModSlot(
    value: Signal[Any],
    branches: Vector[(Any, SlotTemplate)])
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val selected = transaction.sample(value).value
      branches.find((key, _) => key == selected) match
        case Some((_, branch)) => branch.evaluate(transaction)
        case None              => EvaluatedSlot(RenderSnapshot.StringSlot(""))

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val selected = transaction.sample(value).value
      branches.find((key, _) => key == selected) match
        case Some((_, branch)) => branch.evaluateZIO(transaction, resolver)
        case None              => ZIO.succeed(EvaluatedSlot(RenderSnapshot.StringSlot("")))

    override def dispose(): Unit = branches.foreach(_._2.dispose())

  final private case class ScopedSlotTemplate(slot: SlotTemplate, scope: SignalScope)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      slot.evaluate(transaction)

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] = slot.evaluateZIO(transaction, resolver)

    override def dispose(): Unit =
      slot.dispose()
      scope.dispose()

  final private case class AttributeTemplateSlot(template: Template) extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      attributeSlot(template.evaluate(transaction))

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      template.evaluateZIO(transaction, resolver).map(attributeSlot)

    private def attributeSlot(evaluated: EvaluatedNode): EvaluatedSlot =
      val html = RenderSnapshot.renderHtml(
        RenderSnapshot.Compiled(evaluated.node, evaluated.bindings, evaluated.trackedStaticUrls)
      )
      val openingTagEnd = html.indexOf('>')
      val attributes    = html.substring("<view-graph-attrs".length, openingTagEnd)
      EvaluatedSlot(
        RenderSnapshot.StringSlot(attributes),
        evaluated.bindings,
        evaluated.trackedStaticUrls
      )

    override def dispose(): Unit = template.dispose()

  final private case class TemplateNodeSlot(template: Template) extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val evaluated = template.evaluate(transaction)
      EvaluatedSlot(
        RenderSnapshot.NodeSlot(evaluated.node),
        evaluated.bindings,
        evaluated.trackedStaticUrls
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      template
        .evaluateZIO(transaction, resolver).map(evaluated =>
          EvaluatedSlot(
            RenderSnapshot.NodeSlot(evaluated.node),
            evaluated.bindings,
            evaluated.trackedStaticUrls
          )
        )

    override def dispose(): Unit = template.dispose()

  final private case class OptionalSlot(
    value: Signal[Option[Any]],
    source: Signal.Source[Any],
    template: Template)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      transaction.sample(value).value match
        case Some(current) =>
          transaction.setSource(source, current)
          val evaluated = template.evaluate(transaction)
          EvaluatedSlot(
            RenderSnapshot.NodeSlot(evaluated.node),
            evaluated.bindings,
            evaluated.trackedStaticUrls
          )
        case None => EvaluatedSlot(RenderSnapshot.StringSlot(""))

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      transaction.sample(value).value match
        case Some(current) =>
          transaction.setSource(source, current)
          template
            .evaluateZIO(transaction, resolver).map(evaluated =>
              EvaluatedSlot(
                RenderSnapshot.NodeSlot(evaluated.node),
                evaluated.bindings,
                evaluated.trackedStaticUrls
              )
            )
        case None => ZIO.succeed(EvaluatedSlot(RenderSnapshot.StringSlot("")))

    override def dispose(): Unit = template.dispose()
  end OptionalSlot

  final private case class LiveComponentSlot(
    spec: LiveComponentSpec[?, ?, ?, ?],
    path: BindingId.Path)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      throw new UnsupportedOperationException(
        "live components require an effectful view-graph resolver"
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      resolver
        .component(spec, path, transaction).map(resolved =>
          EvaluatedSlot(
            resolved.slot,
            resolved.bindings,
            resolved.trackedStaticUrls
          )
        )

  final private case class SignalLiveComponentSlot(
    spec: LiveComponentSignalSpec[?, ?, ?, ?],
    path: BindingId.Path)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      throw new UnsupportedOperationException(
        "live components require an effectful view-graph resolver"
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val typed   = spec.asInstanceOf[LiveComponentSignalSpec[Any, Any, Any, Any]]
      val current = LiveComponentSpec(
        typed.component,
        typed.id,
        transaction.sample(typed.props).value,
        typed.outputMapper
      )
      resolver
        .component(current, path, transaction).map(resolved =>
          EvaluatedSlot(
            resolved.slot,
            resolved.bindings,
            resolved.trackedStaticUrls
          )
        )

  final private case class DynamicLiveComponentSlot(
    spec: LiveComponentDynamicSpec[?, ?, ?, ?],
    path: BindingId.Path)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      throw new UnsupportedOperationException(
        "live components require an effectful view-graph resolver"
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val typed   = spec.asInstanceOf[LiveComponentDynamicSpec[Any, Any, Any, Any]]
      val current = LiveComponentSpec(
        typed.component,
        transaction.sample(typed.id).value,
        transaction.sample(typed.props).value,
        typed.outputMapper
      )
      resolver
        .component(current, path, transaction).map(resolved =>
          EvaluatedSlot(resolved.slot, resolved.bindings, resolved.trackedStaticUrls)
        )

  final private case class NestedLiveViewSlot(
    spec: NestedLiveViewSpec[?, ?],
    path: BindingId.Path)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      throw new UnsupportedOperationException(
        "nested LiveViews require an effectful view-graph resolver"
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      resolver
        .liveView(spec, path).map(resolved =>
          EvaluatedSlot(resolved.slot, resolved.bindings, resolved.trackedStaticUrls)
        )

  final private case class SignalNestedLiveViewSlot(
    spec: SignalNestedLiveViewSpec[?, ?, ?],
    path: BindingId.Path)
      extends SlotTemplate:
    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      throw new UnsupportedOperationException(
        "nested LiveViews require an effectful view-graph resolver"
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val typed   = spec.asInstanceOf[SignalNestedLiveViewSpec[Any, Any, Any]]
      val current = transaction.sample(typed.value).value
      val nested  = NestedLiveViewSpec(
        typed.id,
        () => typed.liveView(current),
        typed.msgClassTag,
        typed.sticky,
        typed.linkParentOnCrash(current)
      )
      resolver
        .liveView(nested, path).map(resolved =>
          EvaluatedSlot(resolved.slot, resolved.bindings, resolved.trackedStaticUrls)
        )

  final private class FlashSlot(
    kind: String,
    f: String => HtmlElement[Nothing],
    parentScope: SignalScope,
    path: BindingId.Path)
      extends SlotTemplate:
    private var cached: Option[(String, Template)] = None

    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      throw new UnsupportedOperationException("flash requires an effectful view-graph resolver")

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      resolver.flash(kind).flatMap {
        case None =>
          val previous = cached
          previous.foreach(entry => transaction.discardScopeOnCommit(entry._2.scope))
          transaction.onCommit {
            previous.foreach(_._2.dispose())
            cached = None
          }
          ZIO.succeed(EvaluatedSlot(RenderSnapshot.StringSlot("")))
        case Some(message) =>
          val existing  = cached.filter(_._1 == message)
          val candidate = existing.getOrElse {
            val scope = parentScope.child()
            message -> Compiler.compile(f(message), scope, root = true, path)
          }
          if existing.isEmpty then
            cached.foreach(entry => transaction.discardScopeOnCommit(entry._2.scope))
            transaction.onCommit {
              cached.foreach(_._2.dispose())
              cached = Some(candidate)
            }
            transaction.onRollback(candidate._2.dispose())
          candidate._2
            .evaluateZIO(transaction, resolver).map(evaluated =>
              EvaluatedSlot(
                RenderSnapshot.NodeSlot(evaluated.node),
                evaluated.bindings,
                evaluated.trackedStaticUrls
              )
            )
      }

    override def dispose(): Unit = cached.foreach(_._2.dispose())
  end FlashSlot

  final private class KeyedSlot(
    values: Signal[Iterable[Any]],
    key: (Any, Int) => Any,
    project: (Any, Signal[Any]) => HtmlElement[?],
    parentScope: SignalScope,
    path: BindingId.Path)
      extends SlotTemplate:

    final private case class Row(source: Signal.Source[Any], template: NodeTemplate):
      def dispose(): Unit = template.dispose()

    private val rows = mutable.HashMap.empty[Any, Row]

    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val candidateRows = mutable.HashMap.from(rows)
      stageRows(transaction, candidateRows)
      val current       = transaction.sample(values).value.toVector
      val seen          = mutable.HashSet.empty[Any]
      val evaluatedRows = current.zipWithIndex.map { case (item, index) =>
        val itemKey = key(item, index)
        require(seen.add(itemKey), s"duplicate stable collection key '$itemKey'")
        val row = candidateRows.getOrElseUpdate(itemKey, createRow(itemKey))
        transaction.setSource(row.source, item)
        itemKey -> row.template.evaluate(transaction)
      }
      candidateRows.keys.filterNot(seen).toVector.foreach(candidateRows.remove)
      discardRemovedRows(transaction, candidateRows)
      val entries = evaluatedRows.map { case (itemKey, evaluated) =>
        RenderSnapshot.KeyedEntry(
          itemKey,
          evaluated.node,
          Some(RenderSnapshot.keyedEntryFingerprint(itemKey, evaluated.node))
        )
      }
      val node = RenderSnapshot.buildKeyedNode(entries, stream = None)
      EvaluatedSlot(
        RenderSnapshot.KeyedSlot(node),
        evaluatedRows.foldLeft(Map.empty[String, RenderSnapshot.RawBindingHandler]) {
          case (all, (_, evaluated)) => all ++ evaluated.bindings
        },
        evaluatedRows.flatMap(_._2.trackedStaticUrls)
      )

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val candidateRows = mutable.HashMap.from(rows)
      stageRows(transaction, candidateRows)
      val current = transaction.sample(values).value.toVector
      val seen    = mutable.HashSet.empty[Any]
      ZIO
        .foreach(current.zipWithIndex) { case (item, index) =>
          val itemKey = key(item, index)
          ZIO.attempt(
            require(seen.add(itemKey), s"duplicate stable collection key '$itemKey'")
          ) *>
            ZIO.suspendSucceed {
              val row = candidateRows.getOrElseUpdate(itemKey, createRow(itemKey))
              transaction.setSource(row.source, item)
              row.template.evaluateZIO(transaction, resolver).map(itemKey -> _)
            }
        }.map { evaluatedRows =>
          candidateRows.keys.filterNot(seen).toVector.foreach(candidateRows.remove)
          discardRemovedRows(transaction, candidateRows)
          evaluatedKeyedRows(evaluatedRows)
        }

    private def discardRemovedRows(
      transaction: SignalEvaluation.Transaction,
      candidateRows: mutable.HashMap[Any, Row]
    ): Unit =
      rows.iterator
        .filterNot((key, _) => candidateRows.contains(key))
        .foreach((_, row) => transaction.discardScopeOnCommit(row.source.scope))

    private def stageRows(
      transaction: SignalEvaluation.Transaction,
      candidateRows: mutable.HashMap[Any, Row]
    ): Unit =
      transaction.onCommit {
        rows.iterator.filterNot((key, _) => candidateRows.contains(key)).foreach(_._2.dispose())
        rows.clear()
        rows.addAll(candidateRows)
      }
      transaction.onRollback {
        candidateRows.iterator.filterNot((key, _) => rows.contains(key)).foreach(_._2.dispose())
      }

    private def createRow(itemKey: Any): Row =
      val scope   = parentScope.child()
      val source  = Signal.source[Any](scope)
      val rowPath = BindingId.keyedEntryPath(path, itemKey)
      Row(
        source,
        Compiler.compileRow(project(itemKey, source), scope, rowPath)
      )

    override def dispose(): Unit =
      rows.values.foreach(_.dispose())
      rows.clear()

    private def evaluatedKeyedRows(
      evaluatedRows: Vector[(Any, EvaluatedNode)]
    ): EvaluatedSlot =
      val entries = evaluatedRows.map { case (itemKey, evaluated) =>
        RenderSnapshot.KeyedEntry(
          itemKey,
          evaluated.node,
          Some(RenderSnapshot.keyedEntryFingerprint(itemKey, evaluated.node))
        )
      }
      EvaluatedSlot(
        RenderSnapshot.KeyedSlot(RenderSnapshot.buildKeyedNode(entries, stream = None)),
        evaluatedRows.foldLeft(Map.empty[String, RenderSnapshot.RawBindingHandler]) {
          case (all, (_, evaluated)) => all ++ evaluated.bindings
        },
        evaluatedRows.flatMap(_._2.trackedStaticUrls)
      )
  end KeyedSlot

  final private case class FixedKeyedSlot(
    entries: Vector[(Any, NodeTemplate)],
    allEntries: Vector[(Any, NodeTemplate)],
    stream: Option[Diff.Stream])
      extends SlotTemplate:

    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val evaluated = allEntries.map((key, template) => key -> template.evaluate(transaction))
      evaluatedSlot(evaluated)

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      ZIO
        .foreach(allEntries) { case (key, template) =>
          template.evaluateZIO(transaction, resolver).map(key -> _)
        }.map(evaluatedSlot)

    private def evaluatedSlot(evaluated: Vector[(Any, EvaluatedNode)]): EvaluatedSlot =
      val evaluatedByKey = evaluated.toMap
      val keyedEntries   = entries.map { case (key, _) =>
        val row = evaluatedByKey(key)
        RenderSnapshot.KeyedEntry(
          key,
          row.node,
          Option.unless(stream.nonEmpty)(RenderSnapshot.keyedEntryFingerprint(key, row.node))
        )
      }
      EvaluatedSlot(
        RenderSnapshot.KeyedSlot(RenderSnapshot.buildKeyedNode(keyedEntries, stream)),
        evaluated.foldLeft(Map.empty[String, RenderSnapshot.RawBindingHandler]) {
          case (all, (_, row)) => all ++ row.bindings
        },
        evaluated.iterator.flatMap(_._2.trackedStaticUrls).toVector
      )

    override def dispose(): Unit = allEntries.foreach(_._2.dispose())
  end FixedKeyedSlot

  final private class StreamSlot(
    value: Signal[streams.LiveStream[Any]],
    project: (String, Signal[Any]) => HtmlElement[?],
    parentScope: SignalScope,
    path: BindingId.Path)
      extends SlotTemplate:

    final private case class Row(source: Signal.Source[Any], template: NodeTemplate):
      def dispose(): Unit = template.dispose()

    private val rows = mutable.HashMap.empty[String, Row]

    def evaluate(transaction: SignalEvaluation.Transaction): EvaluatedSlot =
      val candidateRows = mutable.HashMap.from(rows)
      stageRows(transaction, candidateRows)
      val stream   = transaction.sample(value).value
      val snapshot = stream.snapshotEntries.map(entry =>
        entry.domId -> evaluateRow(entry.domId, entry.value, candidateRows, transaction)
      )
      require(
        snapshot.map(_._1).distinct.size == snapshot.size,
        s"duplicate active stream DOM id in '${stream.name}'"
      )
      val activeDomIds = snapshot.iterator.map(_._1).toSet
      candidateRows.keys.filterNot(activeDomIds).toVector.foreach(candidateRows.remove)
      val snapshotByDomId = snapshot.toMap
      val emitted         = stream.entries.map { entry =>
        entry.domId -> snapshotByDomId.getOrElse(
          entry.domId,
          evaluateRow(entry.domId, entry.value, candidateRows, transaction)
        )
      }
      discardRemovedRows(transaction, candidateRows)
      val streamPatch = Option.when(
        stream.inserts.nonEmpty || stream.deleteIds.nonEmpty || stream.reset
      )(
        Diff.Stream(
          ref = stream.ref,
          inserts = stream.inserts.map(insert =>
            Diff.StreamInsert(
              domId = insert.domId,
              at = insert.at,
              limit = insert.limit,
              updateOnly = insert.updateOnly
            )
          ),
          deleteIds = stream.deleteIds,
          reset = stream.reset
        )
      )
      val entries = emitted.map { case (domId, evaluated) =>
        RenderSnapshot.KeyedEntry(domId, evaluated.node, fingerprint = None)
      }
      val allEvaluated = (snapshot ++ emitted).groupMapReduce(_._1)(_._2)((left, _) => left).values
      EvaluatedSlot(
        RenderSnapshot.KeyedSlot(RenderSnapshot.buildKeyedNode(entries, streamPatch)),
        allEvaluated.foldLeft(Map.empty[String, RenderSnapshot.RawBindingHandler]) {
          case (all, evaluated) => all ++ evaluated.bindings
        },
        allEvaluated.iterator.flatMap(_.trackedStaticUrls).toVector
      )
    end evaluate

    override def evaluateZIO(
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedSlot] =
      val candidateRows = mutable.HashMap.from(rows)
      stageRows(transaction, candidateRows)
      val stream = transaction.sample(value).value
      for
        snapshot <- ZIO.foreach(stream.snapshotEntries)(entry =>
                      evaluateRowZIO(
                        entry.domId,
                        entry.value,
                        candidateRows,
                        transaction,
                        resolver
                      ).map(entry.domId -> _)
                    )
        _ <- ZIO.attempt(
               require(
                 snapshot.map(_._1).distinct.size == snapshot.size,
                 s"duplicate active stream DOM id in '${stream.name}'"
               )
             )
        snapshotByDomId = snapshot.toMap
        emitted <- ZIO.foreach(stream.entries)(entry =>
                     snapshotByDomId.get(entry.domId) match
                       case Some(evaluated) => ZIO.succeed(entry.domId -> evaluated)
                       case None            =>
                         evaluateRowZIO(
                           entry.domId,
                           entry.value,
                           candidateRows,
                           transaction,
                           resolver
                         ).map(entry.domId -> _)
                   )
        _ =
          val activeDomIds = snapshot.iterator.map(_._1).toSet
          candidateRows.keys.filterNot(activeDomIds).toVector.foreach(candidateRows.remove)
          discardRemovedRows(transaction, candidateRows)
      yield evaluatedStream(stream, snapshot, emitted)
      end for
    end evaluateZIO

    private def evaluateRow(
      domId: String,
      item: Any,
      candidateRows: mutable.HashMap[String, Row],
      transaction: SignalEvaluation.Transaction
    ): EvaluatedNode =
      val row = candidateRows.getOrElseUpdate(domId, createRow(domId))
      transaction.setSource(row.source, item)
      row.template.evaluate(transaction)

    private def evaluateRowZIO(
      domId: String,
      item: Any,
      candidateRows: mutable.HashMap[String, Row],
      transaction: SignalEvaluation.Transaction,
      resolver: Resolver
    ): Task[EvaluatedNode] =
      val row = candidateRows.getOrElseUpdate(domId, createRow(domId))
      transaction.setSource(row.source, item)
      row.template.evaluateZIO(transaction, resolver)

    private def stageRows(
      transaction: SignalEvaluation.Transaction,
      candidateRows: mutable.HashMap[String, Row]
    ): Unit =
      transaction.onCommit {
        rows.iterator.filterNot((key, _) => candidateRows.contains(key)).foreach(_._2.dispose())
        rows.clear()
        rows.addAll(candidateRows)
      }
      transaction.onRollback {
        candidateRows.iterator.filterNot((key, _) => rows.contains(key)).foreach(_._2.dispose())
      }

    private def discardRemovedRows(
      transaction: SignalEvaluation.Transaction,
      candidateRows: mutable.HashMap[String, Row]
    ): Unit =
      rows.iterator
        .filterNot((key, _) => candidateRows.contains(key))
        .foreach((_, row) => transaction.discardScopeOnCommit(row.source.scope))

    override def dispose(): Unit =
      rows.values.foreach(_.dispose())
      rows.clear()

    private def createRow(domId: String): Row =
      val scope   = parentScope.child()
      val source  = Signal.source[Any](scope)
      val rowPath = BindingId.keyedEntryPath(path, domId)
      Row(
        source,
        Compiler.compileRow(project(domId, source), scope, rowPath)
      )

    private def evaluatedStream(
      stream: streams.LiveStream[Any],
      snapshot: Vector[(String, EvaluatedNode)],
      emitted: Vector[(String, EvaluatedNode)]
    ): EvaluatedSlot =
      val streamPatch = Option.when(
        stream.inserts.nonEmpty || stream.deleteIds.nonEmpty || stream.reset
      )(
        Diff.Stream(
          ref = stream.ref,
          inserts = stream.inserts.map(insert =>
            Diff.StreamInsert(
              domId = insert.domId,
              at = insert.at,
              limit = insert.limit,
              updateOnly = insert.updateOnly
            )
          ),
          deleteIds = stream.deleteIds,
          reset = stream.reset
        )
      )
      val entries = emitted.map { case (domId, evaluated) =>
        RenderSnapshot.KeyedEntry(domId, evaluated.node, fingerprint = None)
      }
      val allEvaluated = (snapshot ++ emitted).groupMapReduce(_._1)(_._2)((left, _) => left).values
      EvaluatedSlot(
        RenderSnapshot.KeyedSlot(RenderSnapshot.buildKeyedNode(entries, streamPatch)),
        allEvaluated.foldLeft(Map.empty[String, RenderSnapshot.RawBindingHandler]) {
          case (all, evaluated) => all ++ evaluated.bindings
        },
        allEvaluated.iterator.flatMap(_.trackedStaticUrls).toVector
      )
    end evaluatedStream
  end StreamSlot

  private object Compiler:
    def compileRow(
      element: HtmlElement[?],
      scope: SignalScope,
      path: BindingId.Path
    ): NodeTemplate =
      nestedLiveViewRoot(element) match
        case Some(Content.LiveView(spec)) =>
          RootSlotTemplate(NestedLiveViewSlot(spec, path), scope)
        case Some(Content.SignalLiveView(spec)) =>
          scope
            .validate(spec.value).fold(
              message => throw new IllegalArgumentException(message),
              identity
            )
          RootSlotTemplate(SignalNestedLiveViewSlot(spec, path), scope)
        case _ => compile(element, scope, root = true, path, stageConstants = true)

    def compile(
      rootElement: HtmlElement[?],
      scope: SignalScope,
      root: Boolean,
      rootPath: BindingId.Path = BindingId.rootPath("view"),
      stageConstants: Boolean = false
    ): Template =
      val static          = Vector.newBuilder[String]
      val slots           = Vector.newBuilder[SlotTemplate]
      val bindings        = mutable.LinkedHashMap.empty[String, RenderSnapshot.RawBindingHandler]
      val dynamicBindings = Vector.newBuilder[DynamicBinding]
      val tracked         = mutable.ArrayBuffer.empty[String]
      var fragment        = ""

      def append(value: String): Unit = fragment += value

      def push(slot: SlotTemplate): Unit =
        static += fragment
        fragment = ""
        slots += slot

      def validate(signal: Signal[?]): Unit =
        scope
          .validate(signal).fold(message => throw new IllegalArgumentException(message), identity)

      def appendElement(element: HtmlElement[?], path: BindingId.Path): Unit =
        append(s"<${element.tag.name}")
        val attributeChoices = element.mods.collect {
          case choice: Content.SignalModChoice[?, ?] if isAttributeChoice(choice) => choice
        }.toSet
        val attrs = element.mods.flatMap {
          case attr: Attr[?] => attr.flattened
          case choice: Content.SignalModChoice[?, ?] if attributeChoices.contains(choice) =>
            Vector(choice)
          case _ => Vector.empty
        }
        collectTrackedStatic(attrs.collect { case attr: Attr[?] => attr }, tracked)
        attrs.zipWithIndex.foreach { case (attr, attrIndex) =>
          attr match
            case Attr.Static(name, value) =>
              append(s" $name=\"")
              if stageConstants then push(ConstantSlot(Escaping.escape(value)))
              else append(Escaping.escape(value))
              append("\"")
            case Attr.StaticValueAsPresence(name, value) =>
              val rendered = if value then s" $name" else ""
              if stageConstants then push(ConstantSlot(rendered))
              else append(rendered)
            case Attr.SignalValue(name, value) =>
              validate(value)
              append(s" $name=\"")
              push(ScalarSlot(value, Escaping.escape))
              append("\"")
            case Attr.SignalOptionalValue(name, value) =>
              validate(value)
              push(
                ScalarSlot(
                  value.map(_.fold("")(encoded => s" $name=\"${Escaping.escape(encoded)}\"")),
                  identity
                )
              )
            case Attr.SignalValueAsPresence(name, value) =>
              validate(value)
              push(ScalarSlot(value.map(if _ then s" $name" else ""), identity))
            case Attr.Binding(name, f) =>
              val id = BindingId.attrBindingId(path, attrIndex)
              append(s" $name=\"")
              push(ConstantSlot(Escaping.escape(id)))
              append("\"")
              bindings.update(id, payload => f(payload.params))
            case Attr.SignalBinding(name, signal, f) =>
              validate(signal)
              val id = BindingId.attrBindingId(path, attrIndex)
              append(s" $name=\"")
              push(ConstantSlot(Escaping.escape(id)))
              append("\"")
              dynamicBindings += DynamicBinding(
                id,
                signal.asInstanceOf[Signal[Any]],
                f.asInstanceOf[(Any, BindingPayload) => Any]
              )
            case Attr.FormBinding(name, f) =>
              val id = BindingId.attrBindingId(path, attrIndex)
              append(s" $name=\"")
              push(ConstantSlot(Escaping.escape(id)))
              append("\"")
              bindings.update(id, payload => f(payload.formData))
            case Attr.FormEventBinding(name, codec, f) =>
              val id = BindingId.attrBindingId(path, attrIndex)
              append(s" $name=\"")
              push(ConstantSlot(Escaping.escape(id)))
              append("\"")
              bindings.update(
                id,
                payload => f(payload.formEvent(codec, submitted = name == "phx-submit"))
              )
            case Attr.JsBinding(name, command) =>
              val bindingScope = BindingId.jsBindingScope(path, attrIndex)
              append(s" $name=\"")
              push(ConstantSlot(Escaping.escape(command.renderJson(bindingScope))))
              append("\"")
              command.bindings(bindingScope).foreach { case (id, message) =>
                bindings.update(id, _ => message)
              }
            case Attr.SignalJsBinding(name, command) =>
              validate(command)
              val bindingScope = BindingId.jsBindingScope(path, attrIndex)
              append(s" $name=\"")
              push(
                JsCommandSlot(
                  command.asInstanceOf[Signal[JSCommands.JSCommand[Any]]],
                  bindingScope
                )
              )
              append("\"")
            case Attr.RoutedBinding(name, f) =>
              val id = BindingId.attrBindingId(path, attrIndex)
              append(s" $name=\"")
              push(ConstantSlot(Escaping.escape(id)))
              append("\"")
              bindings.update(id, f)
            case Attr.Group(_) =>
              throw new IllegalStateException("attribute groups must be flattened before rendering")
            case choice: Content.SignalModChoice[?, ?] =>
              validate(choice.value)
              require(
                choice.branches.map(_._1).distinct.size == choice.branches.size,
                "stable choice values must be unique"
              )
              val branchSlots = choice.branches.map { case (key, branch) =>
                val branchScope = scope.child()
                val branchPath  = BindingId.childTagPath(
                  path,
                  attrIndex,
                  "choice-attribute"
                )
                val attrElement = HtmlElement(
                  HtmlTag("view-graph-attrs"),
                  Vector(branch.asInstanceOf[Mod[Any]])
                )
                key -> AttributeTemplateSlot(
                  compile(attrElement, branchScope, root = true, branchPath, stageConstants)
                )
              }
              push(
                ChoiceModSlot(
                  choice.value.asInstanceOf[Signal[Any]],
                  branchSlots.asInstanceOf[Vector[(Any, SlotTemplate)]]
                )
              )
            case other =>
              throw new IllegalStateException(
                s"unexpected ${other.getClass.getSimpleName} in view-graph attribute compilation"
              )
        }

        append(if element.tag.void then "/>" else ">")

        var structuralChildIndex = 0
        element.contentMods
          .filterNot {
            case choice: Content.SignalModChoice[?, ?] => attributeChoices.contains(choice)
            case _                                     => false
          }.foreach {
            case Content.Text(text, raw) =>
              val rendered = if raw then text else Escaping.escape(text)
              if stageConstants then push(ConstantSlot(rendered))
              else append(rendered)
            case Content.SignalText(value, raw) =>
              validate(value)
              push(ScalarSlot(value, if raw then identity else Escaping.escape))
            case Content.SignalChoice(value, branches) =>
              validate(value)
              require(
                branches.map(_._1).distinct.size == branches.size,
                "stable choice values must be unique"
              )
              val branchTemplates = branches.zipWithIndex.map { case ((key, branch), branchIndex) =>
                val branchScope = scope.child()
                val branchPath  = BindingId.childTagPath(
                  path,
                  structuralChildIndex,
                  s"choice-$branchIndex-${branch.tag.name}"
                )
                key -> compile(branch, branchScope, root = true, branchPath, stageConstants)
              }
              structuralChildIndex += 1
              push(
                ChoiceSlot(
                  value.asInstanceOf[Signal[Any]],
                  branchTemplates.asInstanceOf[Vector[(Any, Template)]]
                )
              )
            case Content.SignalModChoice(value, branches) =>
              validate(value)
              require(
                branches.map(_._1).distinct.size == branches.size,
                "stable choice values must be unique"
              )
              val branchSlots = branches.zipWithIndex.map { case ((key, branch), branchIndex) =>
                val branchScope = scope.child()
                val branchPath  = BindingId.childTagPath(
                  path,
                  structuralChildIndex,
                  s"choice-mod-$branchIndex"
                )
                key -> ScopedSlotTemplate(
                  compileMod(branch, branchScope, branchPath, stageConstants),
                  branchScope
                )
              }
              structuralChildIndex += 1
              push(
                ChoiceModSlot(
                  value.asInstanceOf[Signal[Any]],
                  branchSlots.asInstanceOf[Vector[(Any, SlotTemplate)]]
                )
              )
            case Content.SignalOption(value, project) =>
              validate(value)
              val childScope  = scope.child()
              val childSource = Signal.source[Any](childScope)
              val child       = project.asInstanceOf[Signal[Any] => HtmlElement[?]](childSource)
              val childPath   = BindingId.childTagPath(
                path,
                structuralChildIndex,
                s"option-${child.tag.name}"
              )
              structuralChildIndex += 1
              push(
                OptionalSlot(
                  value.asInstanceOf[Signal[Option[Any]]],
                  childSource,
                  compile(child, childScope, root = true, childPath, stageConstants)
                )
              )
            case Content.SignalKeyed(values, key, project) =>
              validate(values)
              val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
              structuralChildIndex += 1
              push(
                new KeyedSlot(
                  values.asInstanceOf[Signal[Iterable[Any]]],
                  (item, _) => key.asInstanceOf[Any => Any](item),
                  project.asInstanceOf[(Any, Signal[Any]) => HtmlElement[?]],
                  scope,
                  keyedPath
                )
              )
            case Content.SignalKeyedByIndex(values, project) =>
              validate(values)
              val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
              structuralChildIndex += 1
              push(
                new KeyedSlot(
                  values.asInstanceOf[Signal[Iterable[Any]]],
                  (_, index) => index,
                  project.asInstanceOf[(Any, Signal[Any]) => HtmlElement[?]],
                  scope,
                  keyedPath
                )
              )
            case Content.SignalStream(value, project) =>
              validate(value)
              val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
              structuralChildIndex += 1
              push(
                new StreamSlot(
                  value.asInstanceOf[Signal[streams.LiveStream[Any]]],
                  project.asInstanceOf[(String, Signal[Any]) => HtmlElement[?]],
                  scope,
                  keyedPath
                )
              )
            case keyed: Content.Keyed[?] =>
              val keyedPath = BindingId.childKeyedPath(path, structuralChildIndex)
              structuralChildIndex += 1
              push(compileFixedKeyed(keyed, scope, keyedPath))
            case Content.Tag(child) =>
              val childPath = BindingId.childTagPath(path, structuralChildIndex, child.tag.name)
              structuralChildIndex += 1
              appendElement(child, childPath)
            case Content.LiveComponent(spec) =>
              val componentPath = BindingId.childTagPath(
                path,
                structuralChildIndex,
                s"live-component-${spec.id}"
              )
              structuralChildIndex += 1
              push(LiveComponentSlot(spec, componentPath))
            case Content.SignalLiveComponent(spec) =>
              validate(spec.props)
              val componentPath = BindingId.childTagPath(
                path,
                structuralChildIndex,
                s"live-component-${spec.id}"
              )
              structuralChildIndex += 1
              push(SignalLiveComponentSlot(spec, componentPath))
            case Content.DynamicLiveComponent(spec) =>
              validate(spec.id)
              validate(spec.props)
              val componentPath = BindingId.childTagPath(
                path,
                structuralChildIndex,
                "dynamic-live-component"
              )
              structuralChildIndex += 1
              push(DynamicLiveComponentSlot(spec, componentPath))
            case Content.LiveView(spec) =>
              val liveViewPath = BindingId.childTagPath(
                path,
                structuralChildIndex,
                s"live-view-${spec.id}"
              )
              structuralChildIndex += 1
              push(NestedLiveViewSlot(spec, liveViewPath))
            case Content.SignalLiveView(spec) =>
              validate(spec.value)
              val liveViewPath = BindingId.childTagPath(
                path,
                structuralChildIndex,
                s"live-view-${spec.id}"
              )
              structuralChildIndex += 1
              push(SignalNestedLiveViewSlot(spec, liveViewPath))
            case Content.Flash(kind, f) =>
              val flashPath = BindingId.childTagPath(
                path,
                structuralChildIndex,
                s"flash-$kind"
              )
              structuralChildIndex += 1
              push(FlashSlot(kind, f, scope, flashPath))
            case unsupported =>
              throw new UnsupportedOperationException(
                s"view-graph rendering does not support ${unsupported.productPrefix} yet"
              )
          }

        if !element.tag.void then append(s"</${element.tag.name}>")
      end appendElement

      val path =
        if rootPath == BindingId.rootPath("view") then BindingId.rootPath(rootElement.tag.name)
        else rootPath
      appendElement(rootElement, path)
      static += fragment
      Template(
        static.result(),
        slots.result(),
        bindings.toMap,
        dynamicBindings.result(),
        tracked.toVector,
        root,
        scope
      )
    end compile

    private def compileMod(
      mod: Mod[?],
      scope: SignalScope,
      path: BindingId.Path,
      stageConstants: Boolean
    ): SlotTemplate =
      mod match
        case Content.Tag(element) =>
          TemplateNodeSlot(compile(element, scope, root = true, path, stageConstants))
        case Content.Text(text, raw) =>
          val rendered = if raw then text else Escaping.escape(text)
          new SlotTemplate:
            def evaluate(transaction: SignalEvaluation.Transaction) =
              EvaluatedSlot(RenderSnapshot.StringSlot(rendered))
        case Content.SignalText(value, raw) =>
          scope
            .validate(value).fold(message => throw new IllegalArgumentException(message), identity)
          ScalarSlot(value, if raw then identity else Escaping.escape)
        case Content.SignalModChoice(value, branches) =>
          scope
            .validate(value).fold(message => throw new IllegalArgumentException(message), identity)
          require(
            branches.map(_._1).distinct.size == branches.size,
            "stable choice values must be unique"
          )
          val branchSlots = branches.zipWithIndex.map { case ((key, branch), index) =>
            val branchScope = scope.child()
            val branchPath  = BindingId.childTagPath(path, index, s"nested-choice-mod-$index")
            key -> ScopedSlotTemplate(
              compileMod(branch, branchScope, branchPath, stageConstants),
              branchScope
            )
          }
          ChoiceModSlot(
            value.asInstanceOf[Signal[Any]],
            branchSlots.asInstanceOf[Vector[(Any, SlotTemplate)]]
          )
        case Content.SignalKeyed(values, key, project) =>
          scope
            .validate(values).fold(message => throw new IllegalArgumentException(message), identity)
          new KeyedSlot(
            values.asInstanceOf[Signal[Iterable[Any]]],
            (item, _) => key.asInstanceOf[Any => Any](item),
            project.asInstanceOf[(Any, Signal[Any]) => HtmlElement[?]],
            scope,
            path
          )
        case Content.SignalKeyedByIndex(values, project) =>
          scope
            .validate(values).fold(message => throw new IllegalArgumentException(message), identity)
          new KeyedSlot(
            values.asInstanceOf[Signal[Iterable[Any]]],
            (_, index) => index,
            project.asInstanceOf[(Any, Signal[Any]) => HtmlElement[?]],
            scope,
            path
          )
        case Content.SignalStream(value, project) =>
          scope
            .validate(value).fold(message => throw new IllegalArgumentException(message), identity)
          new StreamSlot(
            value.asInstanceOf[Signal[streams.LiveStream[Any]]],
            project.asInstanceOf[(String, Signal[Any]) => HtmlElement[?]],
            scope,
            path
          )
        case keyed: Content.Keyed[?] =>
          compileFixedKeyed(keyed, scope, path)
        case Content.LiveComponent(spec)       => LiveComponentSlot(spec, path)
        case Content.SignalLiveComponent(spec) =>
          scope
            .validate(spec.props).fold(
              message => throw new IllegalArgumentException(message),
              identity
            )
          SignalLiveComponentSlot(spec, path)
        case Content.DynamicLiveComponent(spec) =>
          scope
            .validate(spec.id).fold(
              message => throw new IllegalArgumentException(message),
              identity
            )
          scope
            .validate(spec.props).fold(
              message => throw new IllegalArgumentException(message),
              identity
            )
          DynamicLiveComponentSlot(spec, path)
        case Content.LiveView(spec)       => NestedLiveViewSlot(spec, path)
        case Content.SignalLiveView(spec) =>
          scope
            .validate(spec.value).fold(
              message => throw new IllegalArgumentException(message),
              identity
            )
          SignalNestedLiveViewSlot(spec, path)
        case Content.Flash(kind, f) => FlashSlot(kind, f, scope, path)
        case _: Attr[?]             =>
          throw new IllegalArgumentException("a content choice cannot select an attribute")
        case other =>
          throw new IllegalArgumentException(
            s"nested staged ${other.getClass.getSimpleName} modifiers must be wrapped in an element"
          )

    private def isAttributeChoice(choice: Content.SignalModChoice[?, ?]): Boolean =
      val attributeBranches = choice.branches.count(_._2.isInstanceOf[Attr[?]])
      if attributeBranches == choice.branches.size then true
      else if attributeBranches == 0 then false
      else
        throw new IllegalArgumentException(
          "a modifier choice cannot mix attributes and content"
        )

    private def compileFixedKeyed(
      keyed: Content.Keyed[?],
      scope: SignalScope,
      path: BindingId.Path
    ): FixedKeyedSlot =
      val sourceEntries = keyed.allEntries.getOrElse(keyed.entries)
      val templates     = sourceEntries.map { entry =>
        val entryScope = scope.child()
        entry.key -> compileRow(
          entry.element,
          entryScope,
          BindingId.keyedEntryPath(path, entry.key)
        )
      }
      val templatesByKey = templates.toMap
      val emitted        = keyed.entries.map(entry => entry.key -> templatesByKey(entry.key))
      FixedKeyedSlot(emitted, templates, keyed.stream)

    private def nestedLiveViewRoot(element: HtmlElement[?]): Option[Content[?]] =
      element.contentMods match
        case Seq(content @ Content.LiveView(spec)) if hasOnlyMatchingId(element, spec.id) =>
          Some(content)
        case Seq(content @ Content.SignalLiveView(spec)) if hasOnlyMatchingId(element, spec.id) =>
          Some(content)
        case _ => None

    private def hasOnlyMatchingId(element: HtmlElement[?], expectedId: String): Boolean =
      element.tag.name == "div" &&
        (element.attrMods match
          case Seq(Attr.Static(name, value)) => name == idAttr.name && value == expectedId
          case _                             => false)

    private def collectTrackedStatic(
      attrs: Seq[Attr[?]],
      tracked: mutable.ArrayBuffer[String]
    ): Unit =
      val trackedName = phx.trackStatic.name
      val hasTrack    = attrs.exists {
        case Attr.Static(`trackedName`, _)                => true
        case Attr.StaticValueAsPresence(`trackedName`, v) => v
        case _                                            => false
      }
      if hasTrack then
        attrs.foreach {
          case Attr.Static(name, value) if name == href.name || name == src.name =>
            tracked += value
          case _ => ()
        }
  end Compiler
end ViewGraph
