package scalive.render

import java.util.Locale
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.boundary
import scala.util.boundary.break
import scala.util.control.NonFatal

import zio.IO
import zio.UIO
import zio.ZIO

import scalive.BindingPayload
import scalive.ComponentRef
import scalive.ComponentSpec
import scalive.Escaping
import scalive.FlashKey
import scalive.FlashValue
import scalive.FormEventKind
import scalive.HtmlElement
import scalive.JSCommands.JSCommand
import scalive.Mod
import scalive.NestedViewSpec
import scalive.Signal
import scalive.streams.LiveStream
import scalive.streams.LiveStreamEntry
import scalive.streams.LiveStreamIdentity
import scalive.streams.StreamAt
import scalive.streams.StreamLimit

/** A retained, protocol-neutral render program compiled once for one lifecycle graph.
  *
  * Repeated evaluation samples signal expressions against immutable committed state and produces an
  * isolated [[RenderCandidate]]. The program owns template and signal-scope identity, but lifecycle
  * code owns candidate commit, rollback, and eventual closure.
  */
final class RenderProgram[Input, Msg] private (
  private val identity: RenderProgramId,
  private val source: Signal[Input],
  private val rootScope: SignalScope,
  private val resolveState: Input => RenderState,
  private val template: RenderProgram.ElementTemplate[Msg]):

  /** Evaluates `input` against an optional committed render from this program. */
  def evaluate(
    input: Input,
    previous: Option[CommittedRender[Msg]] = None
  ): IO[RenderError, RenderCandidate[Msg]] =
    CandidateScope.make.flatMap { candidateScope =>
      evaluateIn(input, previous, candidateScope)
    }

  /** Closes the program's signal graph when its lifecycle ends. */
  def close: UIO[Unit] = ZIO.succeed {
    RenderProgram.closeRetainedRows(template)
    rootScope.close()
  }

  private[render] def retainedFlashProjectionCount: Int =
    RenderProgram.retainedFlashProjectionCount(template)

  private[render] def retainedKeyedRowCount: Int =
    RenderProgram.retainedKeyedRowCount(template)

  /** What the committed collections and streams of the program's own template keep of each row. */
  private[render] def retainedRows: Map[RowId, RenderProgram.RetainedRow] =
    RenderProgram.retainedRows(template)

  private[scalive] def evaluateIn(
    input: Input,
    previous: Option[CommittedRender[Msg]],
    candidateScope: CandidateScope
  ): IO[RenderError, RenderCandidate[Msg]] =
    def previousState = rootScope.validate(source).flatMap { _ =>
      previous match
        case Some(committed) if committed.programIdentity != identity =>
          Left(RenderError.ProgramMismatch())
        case Some(committed) if committed.scope.isClosed =>
          Left(RenderError.ClosedCommittedRender())
        case Some(committed) =>
          Right((committed.signalEvaluation, Some(committed.tree.root), committed.bindings))
        case None => Right((SignalEvaluation.empty, None, BindingTable.empty))
    }

    ZIO.fromEither(candidateScope.beginEvaluation()).flatMap { _ =>
      val candidateRows = RenderProgram.CandidateRows()
      (for
        previousValues <- ZIO.fromEither(previousState)
        (signalState, previousRoot, previousBindings) = previousValues
        candidate <-
          ZIO.fromEither(RenderRevision.next(signalState.revision)).flatMap { revision =>
            val transaction  = SignalEvaluation.begin(signalState, revision, source, input)
            val inputChanged = previous match
              case Some(_) =>
                transaction.sample(source).map(sample => !transaction.unchanged(sample))
              case None => Right(true)
            ZIO.fromEither(inputChanged).flatMap { changed =>
              previous match
                case Some(committed) if !changed && !committed.signalEvaluation.untracked =>
                  reuse(committed, candidateScope)
                case _ =>
                  evaluateCandidate(
                    input,
                    changed,
                    previousRoot,
                    previousBindings,
                    revision,
                    transaction,
                    candidateScope,
                    candidateRows
                  )
            }
          }
      yield candidate).onError(_ => ZIO.succeed(candidateRows.rollback()) *> candidateScope.discard)
    }
  end evaluateIn

  /** Whether evaluating `input` after `committed` would render `committed` again, as `evaluateIn`
    * then does: `input` equals the input `committed` rendered, and `committed` read nothing that
    * signals do not track. The runtime keeps such a render, and what it resolved into it, without
    * evaluating it.
    */
  private[scalive] def rendersAgain(input: Input, committed: CommittedRender[Msg]): Boolean =
    committed.programIdentity == identity && !committed.scope.isClosed &&
      !committed.signalEvaluation.untracked && rootScope.validate(source).isRight &&
      committed.signalEvaluation.samples.get(source).exists(_.value == input)

  // The same input renders the same again when the render read nothing that signals do not track.
  // Its requirements are kept, so that its components are staged and resolved into it again.
  private def reuse(
    committed: CommittedRender[Msg],
    candidateScope: CandidateScope
  ): IO[RenderError, RenderCandidate[Msg]] =
    ZIO
      .fromEither(candidateScope.completeEvaluation()).as(
        RenderCandidate(
          committed.tree,
          committed.bindings,
          committed.signalEvaluation,
          committed.componentRequirements,
          committed.nestedRequirements,
          committed.streamRequirements,
          candidateScope,
          identity,
          CandidateCommit(Array.empty),
          inputChanged = false
        )
      )

  private def evaluateCandidate(
    input: Input,
    inputChanged: Boolean,
    previousRoot: Option[EvaluatedNode.Element],
    previousBindings: BindingTable[Msg],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    candidateScope: CandidateScope,
    candidateRows: RenderProgram.CandidateRows
  ): IO[RenderError, RenderCandidate[Msg]] =
    ZIO
      .attempt {
        val state = resolveState(input)
        val flash = if state == null then null else state.flash
        if flash == null then Left(RenderError.InvalidHtml("render state resolver returned null"))
        else
          RenderProgram.evaluateElement(
            template,
            previousRoot,
            previousBindings,
            revision,
            transaction,
            flash,
            candidateRows
          )
      }
      .mapError(RenderError.EvaluationFailed.apply).flatMap(ZIO.fromEither(_)).flatMap {
        case (root, bindings, requirements, commit) =>
          ZIO
            .fromEither(candidateScope.completeEvaluation()).as(
              RenderCandidate(
                EvaluatedTree(root, identity),
                bindings,
                transaction.result,
                requirements.components.toVector,
                requirements.nested.toVector,
                requirements.streams.toVector,
                candidateScope,
                identity,
                commit,
                inputChanged
              )
            )
      }
end RenderProgram

object RenderProgram:
  private def formEventKind(name: String): FormEventKind = name match
    case "phx-submit"       => FormEventKind.Submitted
    case "phx-auto-recover" => FormEventKind.Recovered
    case _                  => FormEventKind.Changed

  /** Compiles `view` exactly once into a structured immutable template program. */
  def compile[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg]
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    compile(view, _ => RenderState.empty)

  /** Compiles a view once and resolves its runtime-owned state, such as flash values, uploads, and
    * streams, from each evaluated input.
    *
    * Each declaration retains only its currently committed present projection. Candidate
    * projections remain isolated until commit. Structurally equivalent continuous projections share
    * template identities; removal clears that identity before any later reintroduction.
    */
  def compile[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg],
    resolveState: Input => RenderState
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    compileOwned(view, resolveState, None)

  /** Compiles a component's view, like `compile`.
    *
    * Every element that carries a binding and sets no `phx-target` targets `owner`, so that the
    * browser sends its events to the component that owns the binding.
    */
  def compileComponent[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg],
    resolveState: Input => RenderState,
    owner: ComponentRef[Msg]
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    compileOwned(view, resolveState, Some(owner))

  private def compileOwned[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg],
    resolveState: Input => RenderState,
    owner: Option[ComponentRef[Any]]
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    val scope       = SignalScope.root()
    val sourceToken = SignalSource[Input](scope)
    val source      = Signal.source[Input](sourceToken)
    val allocator   = IdentityAllocator()

    try
      (for
        identity <- RenderProgramId.fresh()
        template <- Compiler(allocator, scope, identity, owner, source.map(resolveState))
                      .compileElement(view(source))
      yield RenderProgram(identity, source, scope, resolveState, template)) match
        case Right(program) => Right(program)
        case Left(error)    =>
          scope.close()
          Left(error)
    catch
      case error: RenderError =>
        scope.close()
        Left(error)
      case NonFatal(error) =>
        scope.close()
        Left(RenderError.EvaluationFailed(error))

  sealed private trait NodeTemplate[+Msg]:
    def id: TemplateId

  final private case class ElementTemplate[+Msg](
    id: TemplateId,
    tag: String,
    void: Boolean,
    attributes: Vector[AttributeTemplate[Msg]],
    children: Vector[NodeTemplate[Msg]])
      extends NodeTemplate[Msg]

  final private case class TextTemplate(
    id: TemplateId,
    value: TextTemplate.Value)
      extends NodeTemplate[Nothing]

  final private case class FlashTemplate(
    id: TemplateId,
    key: FlashKey,
    project: (String, Int) => Option[HtmlElement[Nothing]],
    compiler: Compiler,
    state: FlashTemplate.State)
      extends NodeTemplate[Nothing]

  final private case class ChoiceTemplate[+Msg](
    id: TemplateId,
    signal: Signal[Any],
    branches: Vector[(Any, Vector[NodeTemplate[Msg]])])
      extends NodeTemplate[Msg]

  final private case class ComponentTemplate[+Msg](id: TemplateId, spec: ComponentSpec[Msg])
      extends NodeTemplate[Msg]

  final private case class NestedTemplate(id: TemplateId, spec: NestedViewSpec)
      extends NodeTemplate[Nothing]

  final private case class StaticStreamTemplate[A, Msg](
    id: TemplateId,
    stream: LiveStream[A],
    project: (String, A) => HtmlElement[Msg],
    compiler: Compiler,
    state: StreamTemplate.State[A, Msg])
      extends NodeTemplate[Msg]

  final private case class SignalStreamTemplate[A, Msg](
    id: TemplateId,
    stream: Signal[LiveStream[A]],
    project: (String, Signal[A]) => HtmlElement[Msg],
    compiler: Compiler,
    state: StreamTemplate.State[A, Msg])
      extends NodeTemplate[Msg]

  final private case class StreamRowTemplate[A, Msg](
    id: RowId,
    scope: SignalScope,
    source: Signal[A],
    element: ElementTemplate[Msg]):
    def retire(): Unit = scope.close()

  /** A committed row, with the bindings it rendered, the item and key it was evaluated from, and
    * the components it declared, in order. Its key is the one its collection identifies it by: its
    * item's key, its index, or its DOM id in a stream.
    *
    * Its node is the one its evaluation made, without what resolution filled in
    * (`EvaluatedTree.unresolved`), and the node of [[EvaluatedRows]] holds those of its rows. The
    * committed tree holds the resolved copies, which the runtime replaces when a component renders
    * again, so a record keeps no component's render.
    */
  final private case class EvaluatedRow[Msg](
    node: EvaluatedNode.Element,
    bindings: BindingTable.Row[Msg],
    item: Any,
    key: Any,
    components: Vector[ComponentRequirement[Msg]])

  /** The committed rows of a collection or a stream, with what evaluating them read, the bindings
    * they rendered and the components they declared, in order.
    */
  final private case class EvaluatedRows[+Node <: EvaluatedNode, Msg](
    node: Node,
    signals: SignalEvaluation.RowsEvaluation,
    bindings: BindingTable.Collection[Msg],
    rows: Map[RowId, EvaluatedRow[Msg]],
    components: Vector[ComponentRequirement[Msg]])

  private object StreamTemplate:
    final class State[A, Msg]:
      private var retainedIdentity: Option[LiveStreamIdentity] = None
      private var retained  = Map.empty[String, StreamRowTemplate[A, Msg]]
      private var evaluated = Option.empty[EvaluatedRows[EvaluatedNode.Stream, Msg]]

      def snapshot(identity: LiveStreamIdentity): Map[String, StreamRowTemplate[A, Msg]] =
        synchronized {
          if retainedIdentity.exists(_ eq identity) then retained else Map.empty
        }

      def lastEvaluated(
        identity: LiveStreamIdentity
      ): Option[EvaluatedRows[EvaluatedNode.Stream, Msg]] =
        synchronized {
          if retainedIdentity.exists(_ eq identity) then evaluated else None
        }

      def committed: Option[EvaluatedRows[EvaluatedNode.Stream, Msg]] = synchronized(evaluated)

      /** Keeps `changes.templates` and `rows`, retiring the row templates no longer kept: only
        * `changes.retired` when the retained templates are still those the changes were made from.
        */
      def assignment(
        identity: LiveStreamIdentity,
        changes: StreamRowChanges[A, Msg],
        rows: EvaluatedRows[EvaluatedNode.Stream, Msg]
      ): () => Unit = () =>
        synchronized {
          val old          = retained
          val sameIdentity = retainedIdentity.exists(_ eq identity)
          val next         = changes.templates
          retainedIdentity = Some(identity)
          retained = next
          evaluated = Some(rows)
          if sameIdentity && (old eq changes.base) then changes.retired.foreach(_.retire())
          else
            old.foreach { case (domId, row) =>
              if !sameIdentity || !next.get(domId).exists(_.scope eq row.scope) then row.retire()
            }
        }

      def closeAll(): Unit = synchronized {
        retained.values.foreach(_.retire())
        retained = Map.empty
        retainedIdentity = None
        evaluated = None
      }
    end State

    /** How a stream's evaluation changes the row templates it keeps: `base`, those it kept, become
      * `templates`. `retired` are those of `base` no longer kept, and `dropped` the ids of the rows
      * whose record and samples the stream no longer keeps.
      */
    final case class StreamRowChanges[A, Msg](
      base: Map[String, StreamRowTemplate[A, Msg]],
      templates: Map[String, StreamRowTemplate[A, Msg]],
      retired: Vector[StreamRowTemplate[A, Msg]],
      dropped: Vector[RowId])
  end StreamTemplate

  sealed private trait KeyedRowTemplate[+Msg]:
    def id: RowId
    def element: ElementTemplate[Msg]

  final private case class StaticKeyedRowTemplate[+Msg](
    id: RowId,
    element: ElementTemplate[Msg])
      extends KeyedRowTemplate[Msg]

  final private class DynamicKeyedRowTemplate[A, Msg](
    val id: RowId,
    val scope: SignalScope,
    val source: Signal[A],
    val element: ElementTemplate[Msg])
      extends KeyedRowTemplate[Msg]:
    def retire(): Unit = scope.close()

  final private case class KeyedTemplate[+Msg](
    id: TemplateId,
    rows: Vector[KeyedRowTemplate[Msg]])
      extends NodeTemplate[Msg]

  final private case class SignalKeyedTemplate[A, Key, Msg](
    id: TemplateId,
    values: Signal[Iterable[A]],
    key: A => Key,
    project: (Key, Signal[A]) => HtmlElement[Msg],
    compiler: Compiler,
    state: SignalKeyedTemplate.State[A, Key, Msg])
      extends NodeTemplate[Msg]

  private object SignalKeyedTemplate:
    type Evaluated[Msg] = EvaluatedRows[EvaluatedNode.Keyed, Msg]

    final class State[A, Key, Msg]:
      private var retained  = Map.empty[Key, DynamicKeyedRowTemplate[A, Msg]]
      private var evaluated = Option.empty[Evaluated[Msg]]
      def snapshot: Map[Key, DynamicKeyedRowTemplate[A, Msg]] = synchronized(retained)
      def lastEvaluated: Option[Evaluated[Msg]]               = synchronized(evaluated)
      def assignment(
        next: Map[Key, DynamicKeyedRowTemplate[A, Msg]],
        removed: Vector[DynamicKeyedRowTemplate[A, Msg]],
        rows: Evaluated[Msg]
      ): () => Unit =
        () =>
          synchronized {
            retained = next
            evaluated = Some(rows)
            removed.foreach(_.retire())
          }

      def closeAll(): Unit = synchronized {
        retained.values.foreach(_.retire())
        retained = Map.empty
        evaluated = None
      }

  final private case class SignalKeyedByIndexTemplate[A, Msg](
    id: TemplateId,
    values: Signal[Iterable[A]],
    project: (Int, Signal[A]) => HtmlElement[Msg],
    compiler: Compiler,
    state: SignalKeyedTemplate.State[A, Int, Msg])
      extends NodeTemplate[Msg]

  final private class CandidateRows:
    private val rows = ArrayBuffer.empty[(RowId, SignalScope, () => Unit)]

    def register(row: DynamicKeyedRowTemplate[?, ?]): Unit =
      rows += ((row.id, row.scope, () => row.retire()))

    def register[A, Msg](row: StreamRowTemplate[A, Msg]): Unit =
      rows += ((row.id, row.scope, () => row.retire()))

    def rollback(): Unit = rows.foreach(_._3())

    def rollbackActions: Array[() => Unit] = rows.map(_._3).toArray

    def scopes: Map[RowId, SignalScope] = rows.iterator.map(row => row._1 -> row._2).toMap

  /** What evaluating one render passes to every node it evaluates: the revision and signal
    * transaction it evaluates in, the flash it renders, and where it collects bindings, commit
    * actions, candidate rows and requirements. There is one per render.
    */
  final private class Evaluation[Msg](
    val revision: RenderRevision,
    val transaction: SignalEvaluation.Transaction,
    val flash: Map[FlashKey, FlashValue],
    val bindings: BindingTable.Builder[Msg],
    val commitActions: ArrayBuffer[() => Unit],
    val candidateRows: CandidateRows,
    val requirements: Requirements[Msg])

  final private class Requirements[Msg]:
    val components = ArrayBuffer.empty[ComponentRequirement[Msg]]
    val nested     = ArrayBuffer.empty[NestedRequirement]
    val streams    = ArrayBuffer.empty[StreamRequirement[Msg]]

    /** The components declared since `count` of them were. */
    def componentsSince(count: Int): Vector[ComponentRequirement[Msg]] =
      if count == components.length then Vector.empty else components.view.drop(count).toVector

  private object FlashTemplate:
    final class State:
      private var retained: Option[(FlashValue, ElementTemplate[Nothing])] = None

      def snapshot: Option[(FlashValue, ElementTemplate[Nothing])] = synchronized(retained)

      def assignment(next: Option[(FlashValue, ElementTemplate[Nothing])]): () => Unit =
        () =>
          synchronized {
            val previous = retained
            retained = next
            previous.foreach { case (_, projection) =>
              val continuouslyRetained = next.exists { case (_, candidate) =>
                candidate eq projection
              }
              if !continuouslyRetained then RenderProgram.closeRetainedRows(projection)
            }
          }

  private object TextTemplate:
    enum Value:
      case Static(value: String, raw: Boolean, slot: OptionalSlotId = OptionalSlotId.none)
      case Dynamic(slot: TemplateSlotId, signal: Signal[String], raw: Boolean)

  private enum CompositePart:
    case Static(tokens: Vector[String])
    case Dynamic(signal: Signal[String])
    case DynamicOptional(signal: Signal[Option[String]])
    case Choice(signal: Signal[Any], branches: Vector[(Any, CompositePart)])

    def dynamic: Boolean = this match
      case Static(_) => false
      case _         => true

  sealed private trait AttributeTemplate[+Msg]:
    def name: String

  private object AttributeTemplate:
    final case class Static(
      name: String,
      value: Option[AttributeValue],
      slot: OptionalSlotId = OptionalSlotId.none)
        extends AttributeTemplate[Nothing]
    final case class DynamicValue(name: String, slot: TemplateSlotId, signal: Signal[String])
        extends AttributeTemplate[Nothing]
    final case class DynamicOptional(
      name: String,
      slot: TemplateSlotId,
      signal: Signal[Option[String]])
        extends AttributeTemplate[Nothing]
    final case class DynamicPresence(name: String, slot: TemplateSlotId, signal: Signal[Boolean])
        extends AttributeTemplate[Nothing]
    final case class Composite(name: String, slot: TemplateSlotId, parts: Vector[CompositePart])
        extends AttributeTemplate[Nothing]
    final case class Binding[Msg](
      name: String,
      valueSlot: TemplateSlotId,
      id: BindingId,
      operation: BindingPayload => Msg)
        extends AttributeTemplate[Msg]
    final case class SignalBinding[A, Msg](
      name: String,
      valueSlot: TemplateSlotId,
      id: BindingId,
      signal: Signal[A],
      operation: (A, BindingPayload) => Msg)
        extends AttributeTemplate[Msg]
    final case class JsBinding[Msg](
      name: String,
      valueSlot: TemplateSlotId,
      id: BindingId,
      command: JSCommand[Msg])
        extends AttributeTemplate[Msg]
    final case class SignalJsBinding[Msg](
      name: String,
      valueSlot: TemplateSlotId,
      id: BindingId,
      command: Signal[JSCommand[Msg]])
        extends AttributeTemplate[Msg]
    final case class RoutedBinding(
      name: String,
      valueSlot: TemplateSlotId,
      id: BindingId,
      operation: BindingPayload => scalive.ComponentDispatch)
        extends AttributeTemplate[Nothing]
    final case class ComponentTarget(
      target: ComponentRef[Any],
      valueSlot: TemplateSlotId)
        extends AttributeTemplate[Nothing]:
      val name = "phx-target"
    final case class Choice[Msg](
      name: String,
      signal: Signal[Any],
      branches: Vector[(Any, AttributeTemplate[Msg])])
        extends AttributeTemplate[Msg]
  end AttributeTemplate

  private enum AttributeCandidate[+Msg]:
    case Scalar(template: AttributeTemplate[Msg])
    case Composite(override val name: String, part: CompositePart)

    def name: String = this match
      case Scalar(template)   => template.name
      case Composite(name, _) => name

  final private class Compiler(
    allocator: IdentityAllocator,
    scope: SignalScope,
    program: RenderProgramId,
    owner: Option[ComponentRef[Any]],
    lock: Object,
    state: Signal[RenderState]):

    // Each row compiles its elements when it mounts, so these steps return early rather than
    // allocate the closures of a for over Either.
    def compileElement[Msg](element: HtmlElement[Msg]): Either[RenderError, ElementTemplate[Msg]] =
      val id = allocator.template() match
        case Right(id)   => id
        case Left(error) => return Left(error)
      val (attributeCandidates, contents) = classifyMods(element.mods) match
        case Right(parts) => parts
        case Left(error)  => return Left(error)
      validateAttributeCandidateNames(attributeCandidates) match
        case Right(_)    => ()
        case Left(error) => return Left(error)
      val finalized = finalizeAttributes(attributeCandidates) match
        case Right(finalized) => finalized
        case Left(error)      => return Left(error)
      val attributes = targetOwner(finalized) match
        case Right(attributes) => attributes
        case Left(error)       => return Left(error)
      validateAttributeNames(attributes) match
        case Right(_)    => ()
        case Left(error) => return Left(error)
      if element.tag.void && contents.nonEmpty then
        return Left(
          RenderError.InvalidHtml(
            s"void element '${element.tag.name}' cannot contain child content"
          )
        )
      traverse(contents)(compileContent) match
        case Right(children) =>
          Right(ElementTemplate(id, element.tag.name, element.tag.void, attributes, children))
        case Left(error) => Left(error)

    private def classifyMods[Msg](
      mods: Vector[Mod[Msg]]
    ): Either[RenderError, (Vector[AttributeCandidate[Msg]], Vector[Mod.Content[Msg]])] =
      var attributes = Vector.empty[AttributeCandidate[Msg]]
      var contents   = Vector.empty[Mod.Content[Msg]]
      var index      = 0
      while index < mods.length do
        mods(index) match
          case Mod.Attr.Group(attrs) =>
            traverse(attrs.flatMap(_.flattened))(compileAttribute) match
              case Right(compiled) => attributes = attributes.appendedAll(compiled)
              case Left(error)     => return Left(error)
          case attribute: Mod.Attr[Msg] =>
            compileAttribute(attribute) match
              case Right(compiled) => attributes = attributes :+ compiled
              case Left(error)     => return Left(error)
          case choice @ Mod.Content.SignalChoice(_, _) =>
            val branchMods = choice.branches.flatMap(_._2)
            val allAttrs   = branchMods.nonEmpty && branchMods.forall(_.isInstanceOf[Mod.Attr[?]])
            val allContent = branchMods.forall(_.isInstanceOf[Mod.Content[?]])
            if allAttrs then
              compileAttributeChoice(choice) match
                case Right(compiled) => attributes = attributes :+ compiled
                case Left(error)     => return Left(error)
            else if allContent then contents = contents :+ choice
            else
              return Left(
                RenderError.InvalidHtml(
                  "the branches of a choice cannot mix attributes and content"
                )
              )
          case content: Mod.Content[Msg] => contents = contents :+ content
        index += 1
      Right(attributes -> contents)
    end classifyMods

    // A branch without an attribute renders the attribute absent, like an unmatched value.
    private def compileAttributeChoice[Msg](
      choice: Mod.Content.SignalChoice[?, Msg]
    ): Either[RenderError, AttributeCandidate[Msg]] =
      val signal = choice.value.asInstanceOf[Signal[Any]]
      for
        _ <- scope.validate(signal)
        _ <- validateDistinctKeys(choice.branches.map(_._1))
        flattened = choice.branches.map { (key, branch) =>
                      key -> branch.flatMap {
                        case attribute: Mod.Attr[Msg] => attribute.flattened
                        case other                    => Vector(other)
                      }
                    }
        branches <- traverse(flattened.filter(_._2.nonEmpty)) {
                      case (key, Vector(attribute: Mod.Attr[Msg] @unchecked)) =>
                        Right(key.asInstanceOf[Any] -> attribute)
                      case _ =>
                        Left(
                          RenderError.InvalidHtml(
                            "an attribute branch of a choice must set exactly one attribute"
                          )
                        )
                    }
        compiled <- traverse(branches) { case (key, attribute) =>
                      compileAttribute(attribute).map(key -> _)
                    }
        _    <- traverse(compiled.map(_._2))(candidate => validateAttributeName(candidate.name))
        name <- compiled.headOption
                  .map(_._2.name).toRight(
                    RenderError.InvalidHtml("an attribute choice requires at least one branch")
                  )
        _ <- Either.cond(
               compiled.forall(_._2.name.equalsIgnoreCase(name)),
               (),
               RenderError.InvalidHtml(
                 "the branches of an attribute choice must use the same attribute name"
               )
             )
        candidate <-
          val compositeBranches = compiled.collect {
            case (key, AttributeCandidate.Composite(_, part)) => key -> part
          }
          val scalarBranches = compiled.collect { case (key, AttributeCandidate.Scalar(template)) =>
            key -> template
          }
          val result: Either[RenderError, AttributeCandidate[Msg]] =
            if compositeBranches.size == compiled.size then
              Right(
                AttributeCandidate.Composite(
                  name,
                  CompositePart.Choice(signal, compositeBranches)
                )
              )
            else if scalarBranches.size == compiled.size then
              Right(
                AttributeCandidate.Scalar(AttributeTemplate.Choice(name, signal, scalarBranches))
              )
            else
              Left(
                RenderError.InvalidHtml(
                  "the branches of an attribute choice cannot mix composite and scalar attributes"
                )
              )
          result
      yield candidate
      end for
    end compileAttributeChoice

    private def compileAttribute[Msg](
      attribute: Mod.Attr[Msg]
    ): Either[RenderError, AttributeCandidate[Msg]] =
      attribute match
        case Mod.Attr.Static(name, value) =>
          Either.cond(
            value != null,
            AttributeCandidate.Scalar(
              AttributeTemplate.Static(name, Some(AttributeValue.Text(value)))
            ),
            RenderError.InvalidHtml(s"attribute '$name' has a null value")
          )
        case Mod.Attr.StaticValueAsPresence(name, value) =>
          Right(
            AttributeCandidate.Scalar(
              AttributeTemplate.Static(name, Option.when(value)(AttributeValue.Presence))
            )
          )
        case Mod.Attr.SignalValue(name, value) =>
          dynamicAttribute(name, value)(AttributeTemplate.DynamicValue.apply)
            .map(AttributeCandidate.Scalar.apply)
        case Mod.Attr.SignalOptionalValue(name, value) =>
          dynamicAttribute(name, value)(AttributeTemplate.DynamicOptional.apply)
            .map(AttributeCandidate.Scalar.apply)
        case Mod.Attr.SignalValueAsPresence(name, value) =>
          dynamicAttribute(name, value)(AttributeTemplate.DynamicPresence.apply)
            .map(AttributeCandidate.Scalar.apply)
        case Mod.Attr.ManagedUploadDropTarget(definition) =>
          val ref = state.map(_.upload(definition).map(_.ref))
          compileAttribute(scalive.phx.dropTarget.optional(ref))
        case Mod.Attr.CompositeStatic(name, value) =>
          nonNullString(value, s"attribute '$name'")
            .map(value =>
              AttributeCandidate.Composite(name, CompositePart.Static(compositeTokens(value)))
            )
        case Mod.Attr.CompositeSignalValue(name, value) =>
          scope
            .validate(value).map(_ =>
              AttributeCandidate.Composite(name, CompositePart.Dynamic(value))
            )
        case Mod.Attr.CompositeSignalOptionalValue(name, value) =>
          scope
            .validate(value).map(_ =>
              AttributeCandidate.Composite(name, CompositePart.DynamicOptional(value))
            )
        case Mod.Attr.Binding(name, operation) =>
          for
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.Binding(
              name,
              valueSlot,
              BindingId.event(program, bindingSlot),
              operation
            )
          )
        case Mod.Attr.SignalBinding(name, signal, operation) =>
          for
            _           <- scope.validate(signal)
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.SignalBinding(
              name,
              valueSlot,
              BindingId.event(program, bindingSlot),
              signal,
              operation
            )
          )
        case Mod.Attr.FormBinding(name, operation) =>
          for
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.Binding(
              name,
              valueSlot,
              BindingId.event(program, bindingSlot),
              payload => operation(payload.formData)
            )
          )
        case Mod.Attr.FormEventBinding(name, codec, operation) =>
          for
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.Binding(
              name,
              valueSlot,
              BindingId.event(program, bindingSlot),
              payload => operation(payload.formEvent(codec, RenderProgram.formEventKind(name)))
            )
          )
        case Mod.Attr.TypedFormEventBinding(name, decode, operation) =>
          for
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.Binding(
              name,
              valueSlot,
              BindingId.event(program, bindingSlot),
              payload =>
                operation(
                  payload.typedFormEvent(decode, RenderProgram.formEventKind(name))
                )
            )
          )
        case Mod.Attr.JsBinding(name, command) =>
          for
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.JsBinding(
              name,
              valueSlot,
              BindingId.js(bindingSlot),
              command
            )
          )
        case Mod.Attr.SignalJsBinding(name, command) =>
          for
            _           <- scope.validate(command)
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.SignalJsBinding(
              name,
              valueSlot,
              BindingId.js(bindingSlot),
              command
            )
          )
        case Mod.Attr.RoutedBinding(name, operation) =>
          for
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeCandidate.Scalar(
            AttributeTemplate.RoutedBinding(
              name,
              valueSlot,
              BindingId.event(program, bindingSlot),
              operation
            )
          )
        case Mod.Attr.Group(_) =>
          Left(RenderError.InvalidHtml("attribute groups must be flattened before compilation"))

    private def dynamicAttribute[A, Msg](
      name: String,
      signal: Signal[A]
    )(
      build: (String, TemplateSlotId, Signal[A]) => AttributeTemplate[Msg]
    ): Either[RenderError, AttributeTemplate[Msg]] =
      for
        _    <- scope.validate(signal)
        slot <- allocator.slot()
      yield build(name, slot, signal)

    private def finalizeAttributes[Msg](
      candidates: Vector[AttributeCandidate[Msg]]
    ): Either[RenderError, Vector[AttributeTemplate[Msg]]] =
      var attributes            = Vector.empty[AttributeTemplate[Msg]]
      var emittedCompositeNames = Set.empty[String]
      var index                 = 0
      while index < candidates.length do
        candidates(index) match
          case AttributeCandidate.Scalar(template)   => attributes = attributes :+ template
          case AttributeCandidate.Composite(name, _) =>
            val normalizedName = name.toLowerCase(Locale.ROOT)
            if !emittedCompositeNames.contains(normalizedName) then
              val parts = candidates.collect {
                case AttributeCandidate.Composite(candidateName, part)
                    if candidateName.equalsIgnoreCase(name) =>
                  part
              }
              finalizeCompositeAttribute(name, parts) match
                case Right(template) =>
                  attributes = attributes :+ template
                  emittedCompositeNames += normalizedName
                case Left(error) => return Left(error)
        index += 1
      Right(attributes)

    private def targetOwner[Msg](
      attributes: Vector[AttributeTemplate[Msg]]
    ): Either[RenderError, Vector[AttributeTemplate[Msg]]] =
      def isBinding(attribute: AttributeTemplate[Msg]): Boolean = attribute match
        case _: AttributeTemplate.Binding[?] | _: AttributeTemplate.SignalBinding[?, ?] |
            _: AttributeTemplate.JsBinding[?] | _: AttributeTemplate.SignalJsBinding[?] |
            _: AttributeTemplate.RoutedBinding =>
          true
        case choice: AttributeTemplate.Choice[Msg] => choice.branches.exists((_, b) => isBinding(b))
        case _                                     => false
      val targeted = attributes.exists(_.name.equalsIgnoreCase("phx-target"))
      owner match
        case Some(ref) if !targeted && attributes.exists(isBinding) =>
          allocator.slot().map(slot => attributes :+ AttributeTemplate.ComponentTarget(ref, slot))
        case _ => Right(attributes)

    private def finalizeCompositeAttribute(
      name: String,
      parts: Vector[CompositePart]
    ): Either[RenderError, AttributeTemplate[Nothing]] =
      if parts.forall(!_.dynamic) then
        val tokens = parts.flatMap {
          case CompositePart.Static(tokens) => tokens
          case _                            => Vector.empty
        }
        Right(AttributeTemplate.Static(name, compositeAttributeValue(tokens)))
      else allocator.slot().map(slot => AttributeTemplate.Composite(name, slot, parts))

    private def compileContent[Msg](
      content: Mod.Content[Msg]
    ): Either[RenderError, NodeTemplate[Msg]] =
      content match
        case Mod.Content.Text(text, raw) =>
          nonNullString(text, "text content") match
            case Right(_)    => ()
            case Left(error) => return Left(error)
          allocator.template() match
            case Right(id)   => Right(TextTemplate(id, TextTemplate.Value.Static(text, raw)))
            case Left(error) => Left(error)
        case Mod.Content.SignalText(value, raw) =>
          scope.validate(value) match
            case Right(_)    => ()
            case Left(error) => return Left(error)
          val id = allocator.template() match
            case Right(id)   => id
            case Left(error) => return Left(error)
          allocator.slot() match
            case Right(slot) =>
              Right(TextTemplate(id, TextTemplate.Value.Dynamic(slot, value, raw)))
            case Left(error) => Left(error)
        case Mod.Content.Tag(element)                  => compileElement(element)
        case Mod.Content.SignalChoice(value, branches) =>
          if branches.exists(_._2.exists(_.isInstanceOf[Mod.Attr[?]])) then
            Left(
              RenderError.InvalidHtml("a choice of attributes must be a modifier of an element")
            )
          else
            compileChoice(
              value,
              branches.map((key, mods) => key -> mods.map(_.asInstanceOf[Mod.Content[Msg]]))
            )
        case Mod.Content.Keyed(entries)                    => compileStaticKeyed(entries)
        case Mod.Content.SignalKeyed(values, key, project) =>
          for
            _  <- scope.validate(values)
            id <- allocator.template()
          yield SignalKeyedTemplate(
            id,
            values,
            key,
            project,
            this,
            SignalKeyedTemplate.State()
          )
        case Mod.Content.SignalKeyedByIndex(values, project) =>
          for
            _  <- scope.validate(values)
            id <- allocator.template()
          yield SignalKeyedByIndexTemplate(
            id,
            values,
            project,
            this,
            SignalKeyedTemplate.State()
          )
        case Mod.Content.Stream(stream, project) =>
          allocator
            .template().map(StaticStreamTemplate(_, stream, project, this, StreamTemplate.State()))
        case Mod.Content.SignalStream(stream, project) =>
          for
            _  <- scope.validate(stream)
            id <- allocator.template()
          yield SignalStreamTemplate(id, stream, project, this, StreamTemplate.State())
        case Mod.Content.ManagedStream(definition, project) =>
          val stream = state.map(
            _.stream(definition).getOrElse(
              throw IllegalStateException(
                s"stream '${definition.name}' was not created by this LiveView or component"
              )
            )
          )
          compileContent(Mod.Content.SignalStream(stream, project))
        case Mod.Content.ManagedUpload(definition, body) =>
          val upload  = state.map(_.upload(definition))
          val content = body(upload.map(_.get))
          compileChoice(upload.map(_.isDefined), Vector(true -> Vector(content)))
        case Mod.Content.Component(spec) =>
          for
            _  <- validateComponentSpec(spec)
            id <- allocator.template()
          yield ComponentTemplate(id, spec)
        case Mod.Content.NestedView(spec) =>
          for
            _ <- spec match
                   case dynamic: NestedViewSpec.Dynamic[?, ?, ?] => scope.validate(dynamic.value)
                   case _: NestedViewSpec.Static[?, ?]           => Right(())
            id <- allocator.template()
          yield NestedTemplate(id, spec)
        case Mod.Content.Flash(key, project) =>
          allocator.template().map { id =>
            FlashTemplate(
              id,
              key,
              project,
              this,
              FlashTemplate.State()
            )
          }

    private def validateComponentSpec(spec: ComponentSpec[?]): Either[RenderError, Unit] =
      spec match
        case _: ComponentSpec.Plain[?, ?, ?] | _: ComponentSpec.Output[?, ?, ?, ?, ?] => Right(())
        case value: ComponentSpec.PlainSignal[?, ?, ?]        => scope.validate(value.props)
        case value: ComponentSpec.OutputSignal[?, ?, ?, ?, ?] => scope.validate(value.props)
        case value: ComponentSpec.Dynamic[?, ?, ?]            =>
          scope.validate(value.id).flatMap(_ => scope.validate(value.props))
        case value: ComponentSpec.OutputDynamic[?, ?, ?, ?, ?] =>
          scope.validate(value.id).flatMap(_ => scope.validate(value.props))

    private def compileChoice[A, Msg](
      value: Signal[A],
      branches: Vector[(A, Vector[Mod.Content[Msg]])]
    ): Either[RenderError, NodeTemplate[Msg]] =
      for
        _        <- scope.validate(value)
        _        <- validateDistinctKeys(branches.map(_._1))
        id       <- allocator.template()
        compiled <- traverse(branches) { case (key, contents) =>
                      traverse(contents)(compileContent).map(key.asInstanceOf[Any] -> _)
                    }
      yield ChoiceTemplate(id, value.asInstanceOf[Signal[Any]], compiled)

    private def compileStaticKeyed[Key, Msg](
      entries: Vector[Mod.Content.Keyed.Entry[Key, Msg]]
    ): Either[RenderError, NodeTemplate[Msg]] =
      for
        _    <- validateDistinctKeys(entries.map(_.key))
        id   <- allocator.template()
        rows <- traverse(entries) { entry =>
                  for
                    rowId   <- RowId.fresh()
                    element <- compileElement(entry.element)
                  yield StaticKeyedRowTemplate(rowId, element)
                }
      yield KeyedTemplate(id, rows)

    private def validateDistinctKeys[A](keys: Vector[A]): Either[RenderError, Unit] =
      keys
        .foldLeft[Either[RenderError, Set[A]]](Right(Set.empty)) { (result, key) =>
          result.flatMap(seen =>
            if seen.contains(key) then Left(RenderError.DuplicateKey(key))
            else Right(seen + key)
          )
        }.map(_ => ())

    def keyedRowCandidate[A, Key, Msg](
      project: (Key, Signal[A]) => HtmlElement[Msg],
      rowKey: Key,
      candidateRows: CandidateRows
    ): Either[RenderError, DynamicKeyedRowTemplate[A, Msg]] = lock.synchronized {
      dynamicRowCandidate(signal => project(rowKey, signal), candidateRows)
    }

    def indexedRowCandidate[A, Msg](
      project: (Int, Signal[A]) => HtmlElement[Msg],
      index: Int,
      candidateRows: CandidateRows
    ): Either[RenderError, DynamicKeyedRowTemplate[A, Msg]] = lock.synchronized {
      dynamicRowCandidate(signal => project(index, signal), candidateRows)
    }

    def staticStreamRowCandidate[A, Msg](
      domId: String,
      value: A,
      project: (String, A) => HtmlElement[Msg],
      retained: Option[StreamRowTemplate[A, Msg]],
      candidateRows: CandidateRows
    ): Either[RenderError, StreamRowTemplate[A, Msg]] = lock.synchronized {
      retained match
        case Some(old) =>
          try
            Compiler(allocator, old.scope, program, owner, lock, state)
              .compileElement(project(domId, value)).map { compiled =>
                val aligned =
                  alignElement(compiled, old.element).getOrElse(markStaticScalars(compiled))
                old.copy(element = aligned)
              }
          catch
            case error: RenderError => Left(error)
            case NonFatal(error)    => Left(RenderError.EvaluationFailed(error))
        case None =>
          newStreamRowCandidate(_ => project(domId, value), candidateRows)
    }

    def signalStreamRowCandidate[A, Msg](
      domId: String,
      project: (String, Signal[A]) => HtmlElement[Msg],
      candidateRows: CandidateRows
    ): Either[RenderError, StreamRowTemplate[A, Msg]] = lock.synchronized {
      newStreamRowCandidate(signal => project(domId, signal), candidateRows)
    }

    private def newStreamRowCandidate[A, Msg](
      project: Signal[A] => HtmlElement[Msg],
      candidateRows: CandidateRows
    ): Either[RenderError, StreamRowTemplate[A, Msg]] =
      scope.child().flatMap { childScope =>
        val source = Signal.source[A](SignalSource[A](childScope))
        val result =
          try
            for
              rowId   <- RowId.fresh()
              element <-
                Compiler(allocator, childScope, program, owner, lock, state).compileElement(
                  project(source)
                )
            yield StreamRowTemplate(rowId, childScope, source, element)
          catch
            case error: RenderError => Left(error)
            case NonFatal(error)    => Left(RenderError.EvaluationFailed(error))
        result match
          case Right(row) =>
            candidateRows.register(row)
            Right(row)
          case Left(error) =>
            childScope.close()
            Left(error)
      }

    private def dynamicRowCandidate[A, Msg](
      project: Signal[A] => HtmlElement[Msg],
      candidateRows: CandidateRows
    ): Either[RenderError, DynamicKeyedRowTemplate[A, Msg]] =
      scope.child().flatMap { childScope =>
        val source = Signal.source[A](SignalSource[A](childScope))
        val result =
          try
            for
              rowId   <- RowId.fresh()
              element <- Compiler(allocator, childScope, program, owner, lock, state)
                           .compileElement(project(source))
            yield DynamicKeyedRowTemplate(rowId, childScope, source, element)
          catch
            case error: RenderError => Left(error)
            case NonFatal(error)    => Left(RenderError.EvaluationFailed(error))

        result match
          case Right(row) =>
            candidateRows.register(row)
            Right(row)
          case Left(error) =>
            childScope.close()
            Left(error)
      }

    private def validateAttributeCandidateNames[Msg](
      candidates: Vector[AttributeCandidate[Msg]]
    ): Either[RenderError, Unit] =
      var index = 0
      while index < candidates.length do
        validateAttributeName(candidates(index).name) match
          case Right(_)    => ()
          case Left(error) => return Left(error)
        index += 1
      Right(())

    private def validateAttributeName(name: String): Either[RenderError, Unit] =
      Either.cond(
        name != null && Escaping.validAttrName(name),
        (),
        RenderError.InvalidHtml(s"invalid HTML attribute name '$name'")
      )

    private def validateAttributeNames[Msg](
      attributes: Vector[AttributeTemplate[Msg]]
    ): Either[RenderError, Unit] =
      var names = Set.empty[String]
      var index = 0
      while index < attributes.length do
        val name = attributes(index).name
        validateAttributeName(name) match
          case Right(_)    => ()
          case Left(error) => return Left(error)
        val normalizedName = name.toLowerCase(Locale.ROOT)
        if names.contains(normalizedName) then
          return Left(RenderError.InvalidHtml(s"duplicate HTML attribute '$name'"))
        names += normalizedName
        index += 1
      Right(())

    /** Compiles a changed projection candidate without changing committed retained state.
      *
      * `None` when the message does not decode for the template's kind, which renders as absent.
      */
    def flashProjectionCandidate(
      template: FlashTemplate,
      message: FlashValue
    ): Either[RenderError, Option[ElementTemplate[Nothing]]] = lock.synchronized {
      template.state.snapshot match
        case Some((retainedMessage, retained)) if retainedMessage == message =>
          Right(Some(retained))
        case retained =>
          template.project(message.encoded, message.put) match
            case None          => Right(None)
            case Some(element) =>
              compileElement(element).map { compiled =>
                Some(
                  retained
                    .flatMap((_, existing) => alignElement(compiled, existing))
                    .getOrElse(markStaticScalars(compiled))
                )
              }
    }

    private def markStaticScalars[Msg](
      element: ElementTemplate[Msg]
    ): ElementTemplate[Msg] =
      element.copy(
        attributes = element.attributes.map {
          case static: AttributeTemplate.Static =>
            static.copy(slot = allocator.slot().fold(throw _, OptionalSlotId(_)))
          case other => other
        },
        children = element.children.map {
          case child: ElementTemplate[Msg] => markStaticScalars(child)
          case text: TextTemplate          =>
            text.value match
              case TextTemplate.Value.Static(value, raw, _) =>
                text.copy(value =
                  TextTemplate.Value.Static(
                    value,
                    raw,
                    allocator.slot().fold(throw _, OptionalSlotId(_))
                  )
                )
              case _ => text
          case flash: FlashTemplate                      => flash
          case choice: ChoiceTemplate[Msg]               => choice
          case keyed: KeyedTemplate[Msg]                 => keyed
          case keyed: SignalKeyedTemplate[?, ?, Msg]     => keyed
          case keyed: SignalKeyedByIndexTemplate[?, Msg] => keyed
          case component: ComponentTemplate[Msg]         => component
          case nested: NestedTemplate                    => nested
          case stream: StaticStreamTemplate[?, Msg]      => stream
          case stream: SignalStreamTemplate[?, Msg]      => stream
        }
      )

    private def alignElement[Msg](
      candidate: ElementTemplate[Msg],
      retained: ElementTemplate[Msg]
    ): Option[ElementTemplate[Msg]] =
      if candidate.tag != retained.tag || candidate.void != retained.void ||
        candidate.attributes.length != retained.attributes.length ||
        candidate.children.length != retained.children.length
      then None
      else
        val attributes = candidate.attributes.zip(retained.attributes).map(alignAttribute)
        val children   = candidate.children.zip(retained.children).map(alignNode)
        if attributes.forall(_.nonEmpty) && children.forall(_.nonEmpty) then
          Some(
            candidate.copy(
              id = retained.id,
              attributes = attributes.flatten,
              children = children.flatten
            )
          )
        else None

    private def alignNode[Msg](
      candidate: NodeTemplate[Msg],
      retained: NodeTemplate[Msg]
    ): Option[NodeTemplate[Msg]] = (candidate, retained) match
      case (left: ElementTemplate[Msg], right: ElementTemplate[Msg]) =>
        alignElement(left, right)
      case (left: TextTemplate, right: TextTemplate) =>
        (left.value, right.value) match
          case (
                TextTemplate.Value.Static(value, raw, _),
                TextTemplate.Value.Static(_, oldRaw, slot)
              ) if raw == oldRaw =>
            Some(left.copy(id = right.id, value = TextTemplate.Value.Static(value, raw, slot)))
          case (
                TextTemplate.Value.Dynamic(slot, signal, raw),
                TextTemplate.Value.Dynamic(oldSlot, _, oldRaw)
              ) if raw == oldRaw =>
            Some(left.copy(id = right.id, value = TextTemplate.Value.Dynamic(oldSlot, signal, raw)))
          case _ => None
      case (left: FlashTemplate, right: FlashTemplate) if left.key == right.key =>
        Some(left.copy(id = right.id))
      case _ => None

    private def alignAttribute[Msg](
      candidate: AttributeTemplate[Msg],
      retained: AttributeTemplate[Msg]
    ): Option[AttributeTemplate[Msg]] =
      if candidate.name != retained.name then None
      else
        (candidate, retained) match
          case (left: AttributeTemplate.Static, right: AttributeTemplate.Static) =>
            Some(left.copy(slot = right.slot))
          case (left: AttributeTemplate.DynamicValue, right: AttributeTemplate.DynamicValue) =>
            Some(left.copy(slot = right.slot))
          case (
                left: AttributeTemplate.DynamicOptional,
                right: AttributeTemplate.DynamicOptional
              ) =>
            Some(left.copy(slot = right.slot))
          case (
                left: AttributeTemplate.DynamicPresence,
                right: AttributeTemplate.DynamicPresence
              ) =>
            Some(left.copy(slot = right.slot))
          case (left: AttributeTemplate.Composite, right: AttributeTemplate.Composite) =>
            Some(left.copy(slot = right.slot))
          case (left: AttributeTemplate.Binding[?], right: AttributeTemplate.Binding[?]) =>
            Some(
              left
                .copy(valueSlot = right.valueSlot, id = right.id)
                .asInstanceOf[AttributeTemplate[Msg]]
            )
          case (
                left: AttributeTemplate.SignalBinding[?, ?],
                right: AttributeTemplate.SignalBinding[?, ?]
              ) =>
            Some(
              left
                .copy(valueSlot = right.valueSlot, id = right.id)
                .asInstanceOf[AttributeTemplate[Msg]]
            )
          case (left: AttributeTemplate.JsBinding[?], right: AttributeTemplate.JsBinding[?]) =>
            Some(
              left
                .copy(valueSlot = right.valueSlot, id = right.id)
                .asInstanceOf[AttributeTemplate[Msg]]
            )
          case (
                left: AttributeTemplate.SignalJsBinding[?],
                right: AttributeTemplate.SignalJsBinding[?]
              ) =>
            Some(
              left
                .copy(valueSlot = right.valueSlot, id = right.id)
                .asInstanceOf[AttributeTemplate[Msg]]
            )
          case (left: AttributeTemplate.RoutedBinding, right: AttributeTemplate.RoutedBinding) =>
            Some(left.copy(valueSlot = right.valueSlot, id = right.id))
          case (
                left: AttributeTemplate.ComponentTarget,
                right: AttributeTemplate.ComponentTarget
              ) =>
            Some(left.copy(valueSlot = right.valueSlot))
          case _ => None
  end Compiler

  private object Compiler:
    def apply(
      allocator: IdentityAllocator,
      scope: SignalScope,
      program: RenderProgramId,
      owner: Option[ComponentRef[Any]],
      state: Signal[RenderState]
    ): Compiler =
      new Compiler(allocator, scope, program, owner, Object(), state)

    def apply(
      allocator: IdentityAllocator,
      scope: SignalScope,
      program: RenderProgramId,
      owner: Option[ComponentRef[Any]],
      lock: Object,
      state: Signal[RenderState]
    ): Compiler = new Compiler(allocator, scope, program, owner, lock, state)

  private def evaluateElement[Msg](
    template: ElementTemplate[Msg],
    previous: Option[EvaluatedNode.Element],
    previousBindings: BindingTable[Msg],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    candidateRows: CandidateRows
  ): Either[
    RenderError,
    (EvaluatedNode.Element, BindingTable[Msg], Requirements[Msg], CandidateCommit)
  ] =
    val bindings      = BindingTable.Builder[Msg](previousBindings)
    val commitActions = ArrayBuffer.empty[() => Unit]
    val requirements  = Requirements[Msg]()
    val evaluation    = Evaluation(
      revision,
      transaction,
      flash,
      bindings,
      commitActions,
      candidateRows,
      requirements
    )
    for
      attributes <- evaluateAttributes(
                      template,
                      previous,
                      revision,
                      transaction,
                      bindings
                    )
      children <- evaluateChildren(
                    template,
                    previous,
                    evaluation
                  )
      element = retainElementRevision(template, attributes, children, previous, revision)
      table <- bindings.result()
    yield (
      element,
      table,
      requirements,
      CandidateCommit(commitActions.toArray, candidateRows.rollbackActions, candidateRows.scopes)
    )
  end evaluateElement

  private def evaluateAttributes[Msg](
    template: ElementTemplate[Msg],
    previous: Option[EvaluatedNode.Element],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    bindings: BindingTable.Builder[Msg]
  ): Either[RenderError, Vector[EvaluatedAttribute]] =
    val attributes = template.attributes
    val evaluated  = RefArray[EvaluatedAttribute](attributes.length)

    // Phoenix gates form recovery on the old and new phx-change values matching exactly.
    val formIdIndex =
      if template.tag.equalsIgnoreCase("form") then
        attributes.indexWhere(_.name.equalsIgnoreCase("id"))
      else -1
    var formId: Option[String] = None
    if formIdIndex >= 0 then
      evaluateAttribute(
        attributes(formIdIndex),
        previousAttribute(previous, formIdIndex),
        revision,
        transaction,
        bindings,
        None
      ) match
        case Right(attribute) =>
          evaluated(formIdIndex) = attribute
          formId = textValue(attribute).filter(_.nonEmpty)
        case Left(error) => return Left(error)

    // A loop rather than a traversal, so that evaluating an element allocates no closures.
    var index = 0
    while index < attributes.length do
      if index != formIdIndex then
        evaluateAttribute(
          attributes(index),
          previousAttribute(previous, index),
          revision,
          transaction,
          bindings,
          formId
        ) match
          case Right(attribute) => evaluated(index) = attribute
          case Left(error)      => return Left(error)
      index += 1
    Right(evaluated.toVector)
  end evaluateAttributes

  private def evaluateChildren[Msg](
    template: ElementTemplate[Msg],
    previous: Option[EvaluatedNode.Element],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, Vector[EvaluatedNode]] =
    val children  = template.children
    val evaluated = RefArray[EvaluatedNode](children.length)
    var index     = 0
    while index < children.length do
      evaluateNode(
        children(index),
        previousChild(previous, index),
        evaluation
      ) match
        case Right(child) => evaluated(index) = child
        case Left(error)  => return Left(error)
      index += 1
    Right(evaluated.toVector)

  private def previousAttribute(
    previous: Option[EvaluatedNode.Element],
    index: Int
  ): Option[EvaluatedAttribute] =
    previous match
      case Some(element) if index < element.attributes.length => Some(element.attributes(index))
      case _                                                  => None

  private def previousChild(
    previous: Option[EvaluatedNode.Element],
    index: Int
  ): Option[EvaluatedNode] =
    previous match
      case Some(element) if index < element.children.length => Some(element.children(index))
      case _                                                => None

  /** An array of references that holds only `A`s: up to 32 elements, a vector wraps it without
    * copying it, which it would not do for an array typed `A`.
    */
  private object RefArray:
    opaque type RefArray[A <: AnyRef] = Array[AnyRef]

    def apply[A <: AnyRef](length: Int): RefArray[A] = new Array[AnyRef](length)

    extension [A <: AnyRef](array: RefArray[A])
      inline def update(index: Int, value: A): Unit = array(index) = value

      // Only `update` writes to it, so every element is an `A`.
      inline def toVector: Vector[A] =
        Vector.from(ArraySeq.unsafeWrapArray(array)).asInstanceOf[Vector[A]]

  private def textValue(attribute: EvaluatedAttribute): Option[String] =
    attribute.value.collect { case AttributeValue.Text(value) => value }

  private def evaluateNode[Msg](
    template: NodeTemplate[Msg],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, EvaluatedNode] =
    template match
      // Matches rather than a for over Either, which would allocate closures for every element.
      case element: ElementTemplate[Msg] =>
        val previousElement = previous match
          case Some(node: EvaluatedNode.Element) => Some(node)
          case _                                 => None
        evaluateAttributes(
          element,
          previousElement,
          evaluation.revision,
          evaluation.transaction,
          evaluation.bindings
        ) match
          case Left(error)       => Left(error)
          case Right(attributes) =>
            evaluateChildren(
              element,
              previousElement,
              evaluation
            ) match
              case Left(error)     => Left(error)
              case Right(children) =>
                Right(
                  retainElementRevision(
                    element,
                    attributes,
                    children,
                    previousElement,
                    evaluation.revision
                  )
                )
      case text: TextTemplate =>
        evaluateText(text, previous, evaluation.revision, evaluation.transaction)
      case template: ChoiceTemplate[Msg] =>
        for
          selected <- evaluation.transaction.sample(template.signal)
          previousChildren = previous
                               .collect { case choice: EvaluatedNode.Choice => choice.children }
                               .getOrElse(Vector.empty)
          children <- template.branches.find(_._1 == selected.value) match
                        case Some((_, branch)) =>
                          traverseIndexed(branch) { (node, index) =>
                            evaluateNode(
                              node,
                              previousChildren.lift(index),
                              evaluation
                            )
                          }
                        case None => Right(Vector.empty)
        yield previous match
          case Some(old: EvaluatedNode.Choice)
              if old.id == template.id && sameInstances(old.children, children) =>
            old
          case Some(old: EvaluatedNode.Choice)
              if old.id == template.id && sameBy(old.children, children)(_.revision) =>
            old.copy(children = children)
          case _ => EvaluatedNode.Choice(template.id, children, evaluation.revision)
      case template: KeyedTemplate[Msg] =>
        evaluateKeyedRows(
          template.id,
          template.rows,
          previous,
          evaluation
        )
      case template: SignalKeyedTemplate[?, ?, Msg] =>
        evaluateSignalKeyed(
          template,
          previous,
          evaluation
        )
      case template: SignalKeyedByIndexTemplate[?, Msg] =>
        evaluateSignalKeyedByIndex(
          template,
          previous,
          evaluation
        )
      // Signals track a component's declaration. A kept row or collection declares its components
      // again from what it recorded, and the runtime resolves them into it on every turn.
      case template: ComponentTemplate[Msg] =>
        evaluateComponentRequirement(template.id, template.spec, evaluation.transaction).map {
          requirement =>
            evaluation.requirements.components += requirement
            EvaluatedNode.Component(
              template.id,
              requirement.applicationId,
              None,
              evaluation.revision
            )
        }
      case template: NestedTemplate =>
        evaluation.transaction.markUntracked()
        evaluateNestedRequirement(template.id, template.spec, evaluation.transaction).map {
          requirement =>
            evaluation.requirements.nested += requirement
            previous match
              case Some(old: EvaluatedNode.Nested)
                  if old.id == template.id && old.applicationId == requirement.applicationId =>
                EvaluatedNode.Nested(template.id, requirement.applicationId, None, old.revision)
              case _ =>
                EvaluatedNode.Nested(
                  template.id,
                  requirement.applicationId,
                  None,
                  evaluation.revision
                )
        }
      case template: StaticStreamTemplate[?, Msg] =>
        evaluation.transaction.markUntracked()
        val requirement = StreamRequirement.Static(
          template.id,
          template.stream,
          template.project
        )
        evaluation.requirements.streams += requirement
        evaluateStaticStream(
          template,
          previous,
          evaluation
        )
      case template: SignalStreamTemplate[?, Msg] =>
        evaluation.transaction.markUntracked()
        evaluation.transaction.sample(template.stream).flatMap { sample =>
          evaluation.requirements.streams += StreamRequirement.SignalBacked(
            template.id,
            sample.value,
            template.project
          )
          evaluateSignalStream(
            template,
            sample.value,
            evaluation.transaction.unchanged(sample),
            previous,
            evaluation
          )
        }
      case template: FlashTemplate =>
        // A flash comes from the render state, not from a signal.
        evaluation.transaction.markUntracked()
        val oldFlash = previous.collect { case node: EvaluatedNode.Flash => node }
        def absent   =
          evaluation.commitActions += template.state.assignment(None)
          oldFlash match
            case Some(node) if node.id == template.id && node.child.isEmpty => Right(node)
            case _ => Right(EvaluatedNode.Flash(template.id, None, evaluation.revision))
        val candidate =
          evaluation.flash.get(template.key) match
            case None          => Right(None)
            case Some(message) =>
              for
                _ <- nonNullString(message.encoded, s"flash '${template.key.value}'")
                value = message
                projected <- template.compiler.flashProjectionCandidate(template, value)
              yield projected.map(value -> _)
        candidate.flatMap {
          case None                     => absent
          case Some((value, projected)) =>
            for child <- evaluateNode(
                           projected,
                           oldFlash.flatMap(_.child),
                           evaluation
                         ).map(_.asInstanceOf[EvaluatedNode.Element])
            yield
              evaluation.commitActions += template.state.assignment(Some(value -> projected))
              oldFlash match
                case Some(node) if node.id == template.id && node.child.exists(_ eq child) => node
                case Some(node)
                    if node.id == template.id && node.child.exists(_.revision == child.revision) =>
                  node.copy(child = Some(child))
                case _ => EvaluatedNode.Flash(template.id, Some(child), evaluation.revision)
        }

  private def evaluateStaticStream[A, Msg](
    template: StaticStreamTemplate[A, Msg],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, EvaluatedNode.Stream] =
    evaluateStream(
      template.id,
      template.stream,
      // The view holds this stream, which therefore never changes.
      unchanged = true,
      template.state,
      previous,
      evaluation,
      (domId, value, retained) =>
        template.compiler.staticStreamRowCandidate(
          domId,
          value,
          template.project,
          retained,
          evaluation.candidateRows
        )
    )

  private def evaluateSignalStream[A, Msg](
    template: SignalStreamTemplate[A, Msg],
    stream: LiveStream[A],
    unchanged: Boolean,
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, EvaluatedNode.Stream] =
    evaluateStream(
      template.id,
      stream,
      unchanged,
      template.state,
      previous,
      evaluation,
      (domId, _, retained) =>
        retained match
          case Some(row) => Right(row)
          case None      =>
            template.compiler
              .signalStreamRowCandidate(domId, template.project, evaluation.candidateRows)
    )

  /** Evaluates a stream, or keeps its committed node when `unchanged`, the stream is the same, and
    * every signal its rows read from outside them is unchanged too. A kept stream declares the
    * components of its rows again.
    */
  private def evaluateStream[A, Msg](
    id: TemplateId,
    stream: LiveStream[A],
    unchanged: Boolean,
    state: StreamTemplate.State[A, Msg],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg],
    rowCandidate: (String, A, Option[StreamRowTemplate[A, Msg]]) => Either[
      RenderError,
      StreamRowTemplate[A, Msg]
    ]
  ): Either[RenderError, EvaluatedNode.Stream] =
    val last     = state.lastEvaluated(stream.identity)
    val old      = previous.collect { case value: EvaluatedNode.Stream => value }
    val reusable = (last, old) match
      case (Some(rows), Some(committed))
          if unchanged && !rows.signals.untracked &&
            EvaluatedTree.resolvedFrom(committed, rows.node) &&
            evaluation.transaction.allUnchanged(rows.signals.outside) =>
        Some(rows -> committed)
      case _ => None
    // Only the rows the operations touch or that read a changed signal are evaluated when the
    // committed stream is the one recorded, which then holds no component, and the operations
    // since are known: they are the stream's own when its generation changed, and none when the
    // stream is unchanged.
    val changesOnly = (last, old) match
      case (Some(rows), Some(committed)) =>
        (committed eq rows.node) && rows.components.isEmpty && !rows.signals.untracked &&
        !stream.reset &&
        (committed.generation != stream.generation ||
          unchanged && stream.inserted.isEmpty && stream.deleted.isEmpty)
      case _ => false

    reusable match
      case Some((rows, committed)) =>
        evaluation.bindings.reuseRows(id, rows.bindings).map { _ =>
          evaluation.requirements.components ++= rows.components
          committed
        }
      case None =>
        val declared  = evaluation.requirements.components.length
        val evaluated = (last, old) match
          case (Some(rows), Some(committed)) if changesOnly =>
            evaluation.bindings.enterChangedRows(id, rows.bindings)
            evaluateStreamChanges(
              id,
              stream,
              state,
              committed,
              rows,
              declared,
              evaluation,
              rowCandidate
            )
          case _ =>
            evaluation.bindings.enterRows(id)
            evaluateStreamRows(
              id,
              stream,
              state,
              old,
              last,
              evaluation,
              rowCandidate
            )
        val rowBindings = evaluation.bindings.exitRows()
        evaluated.map { (node, recorded, rows, signals, changes) =>
          evaluation.commitActions += state.assignment(
            stream.identity,
            changes,
            EvaluatedRows(
              recorded,
              signals,
              rowBindings,
              rows,
              evaluation.requirements.componentsSince(declared)
            )
          )
          node
        }
    end match
  end evaluateStream

  private def evaluateStreamRows[A, Msg](
    id: TemplateId,
    stream: LiveStream[A],
    state: StreamTemplate.State[A, Msg],
    old: Option[EvaluatedNode.Stream],
    last: Option[EvaluatedRows[EvaluatedNode.Stream, Msg]],
    evaluation: Evaluation[Msg],
    rowCandidate: (String, A, Option[StreamRowTemplate[A, Msg]]) => Either[
      RenderError,
      StreamRowTemplate[A, Msg]
    ]
  ): Either[
    RenderError,
    (
      EvaluatedNode.Stream,
      EvaluatedNode.Stream,
      Map[RowId, EvaluatedRow[Msg]],
      SignalEvaluation.RowsEvaluation,
      StreamTemplate.StreamRowChanges[A, Msg]
    )
  ] =
    val entries          = stream.entries
    val retainedDomIds   = stream.entryIds
    val operationEntries = stream.inserted.iterator
      .map(_.entry)
      .filterNot(entry => retainedDomIds.contains(entry.domId))
      .toVector
    val renderedEntries = entries ++ operationEntries
    val lastRows        = last.fold(Map.empty[RowId, EvaluatedRow[Msg]])(_.rows)
    // The recorded rows are the committed ones when the recorded stream is: it then holds nothing
    // resolution would have filled in.
    val committed = last.exists(rows => old.exists(_ eq rows.node))
    for
      _ <- validateStreamDomIds(stream, renderedEntries)
      retained = state.snapshot(stream.identity)
      templates <- traverse(renderedEntries) { entry =>
                     rowCandidate(entry.domId, entry.value, retained.get(entry.domId))
                   }
      oldRows =
        if committed then Map.empty[String, EvaluatedNode]
        else old.toVector.flatMap(_.rows).map(row => row.domId -> row.child).toMap
      evaluatedRows <-
        withinRows(
          evaluation.transaction,
          last.fold(SignalEvaluation.RowsEvaluation.empty)(_.signals)
        ) { changed =>
          traverse(templates.zip(renderedEntries).zipWithIndex) { case ((row, entry), index) =>
            val transient = index >= entries.length
            val lastRow   = if transient then None else lastRows.get(row.id)
            evaluateRow(
              row.id,
              entry.domId,
              row.source,
              entry.value,
              row.element,
              if committed then lastRow.map(_.node) else oldRows.get(entry.domId),
              lastRow,
              changed,
              transient,
              evaluation
            ).map(EvaluatedNode.StreamRow(entry.domId, _) -> _)
          }.map { evaluated =>
            val changes = streamRowChanges(stream, retained, templates)
            evaluation.transaction.dropRows(changes.dropped)
            evaluated -> changes
          }
        }
      ((evaluated, changes), rowSignals) = evaluatedRows
      renderedRows                       = evaluated.map(_._1)
    yield
      val rows         = renderedRows.take(entries.length)
      val insertedRows =
        if stream.inserted.isEmpty then Map.empty[String, EvaluatedNode.StreamRow]
        else
          renderedRows.iterator
            .filter(row => stream.insertedIds.contains(row.domId))
            .map(row => row.domId -> row)
            .toMap
      val inserts = stream.inserted.map { insert =>
        val row = insertedRows(insert.entry.domId)
        EvaluatedNode.StreamInsert(row, insert.at, insert.limit, insert.updateOnly)
      }
      val operations         = EvaluatedNode.StreamOperations(inserts, stream.deleted, stream.reset)
      val transientTemplates = operationEntries
        .zip(templates.drop(entries.length)).collect {
          case (entry, template) if !retained.contains(entry.domId) => template
        }
      if transientTemplates.nonEmpty then
        evaluation.commitActions += (() => transientTemplates.foreach(_.retire()))
      val node = old match
        case Some(node)
            if node.id == id && (node.identity eq stream.identity) &&
              node.generation == stream.generation &&
              sameBy(node.rows, rows)(_.child.revision) =>
          node.copy(rows = rows, operations = operations)
        case _ =>
          EvaluatedNode.Stream(
            id,
            stream.identity,
            stream.generation,
            rows,
            operations,
            evaluation.revision
          )
      val records = updatedRecords(
        lastRows -- changes.dropped,
        lastRows,
        templates.iterator.take(entries.length).map(_.id),
        evaluated.iterator.map(_._2)
      )
      (node, recordedStream(node, evaluated), records, rowSignals, changes)
    end for
  end evaluateStreamRows

  /** Evaluates the rows of `stream` that its operations since `last`, its committed rows, insert,
    * and those that read a signal from outside them that changed: the others keep their node,
    * record, bindings and signal samples without being visited. `committed`, the committed stream,
    * is the one `last` recorded, and `declared` the count of components declared before it.
    */
  private def evaluateStreamChanges[A, Msg](
    id: TemplateId,
    stream: LiveStream[A],
    state: StreamTemplate.State[A, Msg],
    committed: EvaluatedNode.Stream,
    last: EvaluatedRows[EvaluatedNode.Stream, Msg],
    declared: Int,
    evaluation: Evaluation[Msg],
    rowCandidate: (String, A, Option[StreamRowTemplate[A, Msg]]) => Either[
      RenderError,
      StreamRowTemplate[A, Msg]
    ]
  ): Either[
    RenderError,
    (
      EvaluatedNode.Stream,
      EvaluatedNode.Stream,
      Map[RowId, EvaluatedRow[Msg]],
      SignalEvaluation.RowsEvaluation,
      StreamTemplate.StreamRowChanges[A, Msg]
    )
  ] =
    val retained = state.snapshot(stream.identity)
    // The entries the operations insert that the stream holds, and those only they render.
    val (insertedEntries, transientEntries) =
      stream.inserted.map(_.entry).partition(entry => stream.entryIds.contains(entry.domId))

    def evaluate(
      entry: LiveStreamEntry[A],
      transient: Boolean,
      changed: Set[RowId]
    ): Either[RenderError, EvaluatedStreamRow[A, Msg]] =
      rowCandidate(entry.domId, entry.value, retained.get(entry.domId)).flatMap { template =>
        val lastRow = if transient then None else last.rows.get(template.id)
        evaluateRow(
          template.id,
          entry.domId,
          template.source,
          entry.value,
          template.element,
          lastRow.map(_.node),
          lastRow,
          changed,
          transient,
          evaluation
        ).map((node, record) =>
          EvaluatedStreamRow(
            template,
            EvaluatedNode.StreamRow(entry.domId, node),
            record,
            transient
          )
        )
      }

    // Ends evaluating the stream from `evaluated`, the rows it evaluated: drops what it keeps of
    // the rows it no longer holds, and returns its node, the node it records, its records and how
    // its templates change.
    def complete(evaluated: Vector[EvaluatedStreamRow[A, Msg]]) =
      val held         = evaluated.filterNot(_.transient)
      val (rows, gone) = streamRowOrder(
        stream,
        committed.rows,
        retained.contains,
        held.iterator.map(row => row.row.domId -> row.row).toMap
      )
      val (templates, replaced) = heldTemplates(retained, held)
      val retired               = gone.map(retained) ++ replaced
      val dropped               =
        retired.map(_.id) ++ evaluated.iterator.filter(_.transient).map(_.template.id)
      evaluation.transaction.dropRows(dropped)
      retired.foreach(row => evaluation.bindings.removeRow(row.id))
      val transientTemplates = evaluated.collect {
        case row if row.transient && !retained.contains(row.row.domId) => row.template
      }
      if transientTemplates.nonEmpty then
        evaluation.commitActions += (() => transientTemplates.foreach(_.retire()))
      if evaluated.count(_.record.components.nonEmpty) > 1 then
        reorderComponents(rows, evaluated, declared, evaluation.requirements)

      val byDomId = evaluated.iterator.map(row => row.row.domId -> row.row).toMap
      val inserts = stream.inserted.map { insert =>
        val row = byDomId(insert.entry.domId)
        EvaluatedNode.StreamInsert(row, insert.at, insert.limit, insert.updateOnly)
      }
      val operations = EvaluatedNode.StreamOperations(inserts, stream.deleted, stream.reset)
      // The same generation means no operations, so only the rows that read a changed signal may
      // have changed.
      val sameRevisions = committed.generation == stream.generation &&
        held.forall(row =>
          last.rows.get(row.template.id).exists(_.node.revision == row.row.child.revision)
        )
      val node =
        if sameRevisions then committed.copy(rows = rows, operations = operations)
        else
          EvaluatedNode.Stream(
            id,
            stream.identity,
            stream.generation,
            rows,
            operations,
            evaluation.revision
          )
      val records = updatedRecords(
        last.rows -- dropped,
        last.rows,
        held.iterator.map(_.template.id),
        held.iterator.map(_.record)
      )
      val recorded = recordedStream(node, evaluated.map(row => row.row -> row.record))
      (
        node,
        recorded,
        records,
        StreamTemplate.StreamRowChanges(retained, templates -- gone, retired, dropped)
      )
    end complete

    for
      _             <- validateStreamDomIds(stream, stream.entries ++ transientEntries)
      evaluatedRows <- withinRows(evaluation.transaction, last.signals) { changed =>
                         // A stream's records hold its entries' DOM ids and values.
                         val reading = changed.iterator
                           .flatMap(last.rows.get)
                           .map(record =>
                             LiveStreamEntry(
                               record.key.asInstanceOf[String],
                               record.item.asInstanceOf[A]
                             )
                           )
                           .filter(entry =>
                             stream.entryIds.contains(entry.domId) &&
                               !stream.insertedIds.contains(entry.domId)
                           )
                         val touched = (insertedEntries ++ reading).map(_ -> false) ++
                           transientEntries.map(_ -> true)
                         traverse(touched)((entry, transient) =>
                           evaluate(entry, transient, changed)
                         )
                           .map(complete)
                       }
      ((node, recorded, records, changes), signals) = evaluatedRows
    yield (node, recorded, records, signals, changes)
  end evaluateStreamChanges

  /** The row templates of a stream after `retained`, now that the rows it holds that it evaluated
    * are `held`, and those of `retained` they replace.
    */
  private def heldTemplates[A, Msg](
    retained: Map[String, StreamRowTemplate[A, Msg]],
    held: Vector[EvaluatedStreamRow[A, Msg]]
  ): (Map[String, StreamRowTemplate[A, Msg]], Vector[StreamRowTemplate[A, Msg]]) =
    var templates = retained
    val replaced  = Vector.newBuilder[StreamRowTemplate[A, Msg]]
    held.foreach { row =>
      val domId = row.row.domId
      retained.get(domId) match
        case Some(kept) if kept eq row.template => ()
        case Some(kept)                         =>
          templates = templates.updated(domId, row.template)
          if !(kept.scope eq row.template.scope) then replaced += kept
        case None => templates = templates.updated(domId, row.template)
    }
    templates -> replaced.result()

  /** A stream row evaluated from `template`, and whether only the stream's operations render it. */
  final private case class EvaluatedStreamRow[A, Msg](
    template: StreamRowTemplate[A, Msg],
    row: EvaluatedNode.StreamRow,
    record: EvaluatedRow[Msg],
    transient: Boolean)

  /** Declares the components of `evaluated`, the rows a stream evaluated, in the order of `rows`,
    * the stream's rows, followed by those of the rows only its operations render, in place of those
    * declared since `declared` of them were: its other rows declare none.
    */
  private def reorderComponents[A, Msg](
    rows: Vector[EvaluatedNode.StreamRow],
    evaluated: Vector[EvaluatedStreamRow[A, Msg]],
    declared: Int,
    requirements: Requirements[Msg]
  ): Unit =
    val held = evaluated.iterator.filterNot(_.transient).map(row => row.row.domId -> row).toMap
    requirements.components.dropRightInPlace(requirements.components.length - declared)
    rows.foreach(row =>
      held.get(row.domId).foreach(requirements.components ++= _.record.components)
    )
    evaluated.foreach(row =>
      if row.transient then requirements.components ++= row.record.components
    )

  /** The rows of `stream` in order: `previous`, its rows before its operations, of the DOM ids
    * `retained`, after them, with `evaluated`, the rows it holds that it evaluated, in place of
    * theirs. Also returns the DOM ids of `retained` the stream no longer holds.
    *
    * Replays the operations: the deletions, then the insertions with their limits. The stream
    * merges some operations, such as an insertion of an entry it inserted before, so when replaying
    * them does not give its entries, the rows are found by DOM id instead. No operation moves a row
    * it keeps, so without limits nor insertions at an index, only the rows inserted first or last
    * may be out of place.
    */
  private def streamRowOrder[A](
    stream: LiveStream[A],
    previous: Vector[EvaluatedNode.StreamRow],
    retained: String => Boolean,
    evaluated: Map[String, EvaluatedNode.StreamRow]
  ): (Vector[EvaluatedNode.StreamRow], Vector[String]) =
    var rows    = previous
    val removed = Vector.newBuilder[String]
    val deleted = mutable.HashSet.empty[String]
    stream.deleted.foreach { domId =>
      if retained(domId) && deleted.add(domId) then
        val index = rows.indexWhere(_.domId == domId)
        if index >= 0 then
          rows = rows.take(index) ++ rows.drop(index + 1)
          removed += domId
    }
    val added    = mutable.HashSet.empty[String]
    var first    = 0
    var last     = 0
    var anyWhere = false
    stream.inserted.foreach { insert =>
      evaluated.get(insert.entry.domId).foreach { row =>
        val held =
          (retained(row.domId) && !deleted.contains(row.domId)) || added.contains(row.domId)
        if !held then
          added += row.domId
          insert.at match
            case StreamAt.First =>
              rows = row +: rows
              first += 1
            case StreamAt.Last =>
              rows = rows :+ row
              last += 1
            case StreamAt.Index(index) =>
              rows = rows.patch(index, Vector(row), 0)
              anyWhere = true
        if insert.limit.nonEmpty then anyWhere = true
        insert.limit match
          case Some(StreamLimit.KeepFirst(count)) if rows.length > count =>
            removed ++= rows.iterator.drop(count).map(_.domId)
            rows = rows.take(count)
          case Some(StreamLimit.KeepLast(count)) if rows.length > count =>
            removed ++= rows.iterator.take(rows.length - count).map(_.domId)
            rows = rows.takeRight(count)
          case _ => ()
      }
    }
    val replacing = evaluated.filterNot((domId, _) => added.contains(domId))
    if replacing.size <= 8 then
      replacing.foreach { (domId, row) =>
        val index = rows.indexWhere(_.domId == domId)
        if index >= 0 && !(rows(index).child eq row.child) then rows = rows.updated(index, row)
      }
    else rows = rows.map(row => replacing.getOrElse(row.domId, row))

    val gone = removed.result().filter(domId => retained(domId) && !stream.entryIds.contains(domId))
    val entries = stream.entries
    val inPlace =
      rows.length == entries.length && (
        if anyWhere then sameDomIds(rows.iterator, entries.iterator)
        else
          sameDomIds(rows.iterator.take(first), entries.iterator.take(first)) &&
          sameDomIds(rows.reverseIterator.take(last), entries.reverseIterator.take(last))
      )
    if inPlace then rows -> gone.distinct
    else
      // Every entry no operation inserts was among the previous rows.
      val byDomId = previous.iterator.map(row => row.domId -> row).toMap ++ evaluated
      val all     = stream.entries.map(entry => byDomId(entry.domId))
      all -> previous.iterator.map(_.domId).filterNot(stream.entryIds.contains).toVector
  end streamRowOrder

  /** Whether `rows` and `entries`, of the same length, have the same DOM ids in order. */
  private def sameDomIds[A](
    rows: Iterator[EvaluatedNode.StreamRow],
    entries: Iterator[LiveStreamEntry[A]]
  ): Boolean =
    while rows.hasNext do
      val domId = rows.next().domId
      val entry = entries.next().domId
      if !((domId eq entry) || domId == entry) then return false
    true

  /** How evaluating `stream` from the row templates `retained` and to `templates`, those of its
    * rendered entries, changes the templates it keeps.
    *
    * The rows it no longer keeps are those of `retained` its entries no longer hold. Its deletions
    * usually tell them all; only when they do not, such as after a limit pruned some rows or a
    * reset, are its entries searched for them. The rows only its operations render are not kept.
    */
  private def streamRowChanges[A, Msg](
    stream: LiveStream[A],
    retained: Map[String, StreamRowTemplate[A, Msg]],
    templates: Vector[StreamRowTemplate[A, Msg]]
  ): StreamTemplate.StreamRowChanges[A, Msg] =
    val entries = stream.entries
    if retained.isEmpty then
      val kept    = Map.from(entries.iterator.map(_.domId).zip(templates))
      val dropped = templates.iterator.drop(entries.length).map(_.id).toVector
      return StreamTemplate.StreamRowChanges(retained, kept, Vector.empty, dropped)
    var next     = retained
    var added    = 0
    val replaced = Vector.newBuilder[StreamRowTemplate[A, Msg]]
    var index    = 0
    while index < entries.length do
      val domId    = entries(index).domId
      val template = templates(index)
      retained.get(domId) match
        case Some(kept) if kept eq template => ()
        case Some(kept)                     =>
          next = next.updated(domId, template)
          if !(kept.scope eq template.scope) then replaced += kept
        case None =>
          next = next.updated(domId, template)
          added += 1
      index += 1

    val removedCount = retained.size + added - entries.length
    val deleted      =
      stream.deleted.filter(domId => retained.contains(domId) && !stream.entryIds.contains(domId))
    val removed =
      if deleted.length == removedCount then deleted
      else retained.keysIterator.filterNot(stream.entryIds.contains).toVector
    val retired = removed.map(retained) ++ replaced.result()
    StreamTemplate.StreamRowChanges(
      retained,
      next -- removed,
      retired,
      retired.map(_.id) ++ templates.iterator.drop(entries.length).map(_.id)
    )
  end streamRowChanges

  /** `records`, the records of the rows that were kept, holding `evaluated`, the records of the
    * rows `ids`, for each that `last` does not already hold.
    */
  private def updatedRecords[Msg](
    records: Map[RowId, EvaluatedRow[Msg]],
    last: Map[RowId, EvaluatedRow[Msg]],
    ids: Iterator[RowId],
    evaluated: Iterator[EvaluatedRow[Msg]]
  ): Map[RowId, EvaluatedRow[Msg]] =
    // Building them at once allocates less than adding them one by one to no records.
    if records.isEmpty then Map.from(ids.zip(evaluated))
    else
      var next = records
      while ids.hasNext do
        val id     = ids.next()
        val record = evaluated.next()
        if !last.get(id).exists(_ eq record) then next = next.updated(id, record)
      next

  /** Rejects a DOM id `entries`, the rendered entries of `stream`, holds twice.
    *
    * The entries the operations render are those not among the stream's own, so they are all
    * distinct when the stream's id sets are as large as what they index: only then is the search
    * needed.
    */
  private def validateStreamDomIds[A](
    stream: LiveStream[A],
    entries: Vector[LiveStreamEntry[A]]
  ): Either[RenderError, Unit] =
    if stream.entryIds.size == stream.entries.length &&
      stream.insertedIds.size == stream.inserted.length
    then Right(())
    else
      val seen      = mutable.HashSet.empty[String]
      val duplicate = entries.find(entry => !seen.add(entry.domId))
      duplicate.fold(Right(()))(entry => Left(RenderError.DuplicateStreamDomId(entry.domId)))

  private def evaluateSignalKeyed[A, Key, Msg](
    template: SignalKeyedTemplate[A, Key, Msg],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    evaluation.transaction.sample(template.values).flatMap { sample =>
      evaluateCollection(
        template.id,
        sample,
        template.state.lastEvaluated,
        previous,
        evaluation
      )(changed =>
        evaluateSignalKeyedRows(
          template,
          sample.value,
          previous,
          evaluation,
          changed
        )
      )
    }

  private def evaluateSignalKeyedRows[A, Key, Msg](
    template: SignalKeyedTemplate[A, Key, Msg],
    values: Iterable[A],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg],
    changed: Set[RowId]
  ): Either[RenderError, CollectionRows[Msg]] =
    val entries = values.iterator.map(value => template.key(value) -> value).toVector
    val keys    = entries.map(_._1)
    for
      present <- validateEvaluationKeys(keys)
      retained = template.state.snapshot
      rows <- traverse(entries) { case (key, _) =>
                retained.get(key) match
                  case Some(row) => Right(row)
                  case None      =>
                    template.compiler.keyedRowCandidate(
                      template.project,
                      key,
                      evaluation.candidateRows
                    )
              }
      (next, removed) = keptRowTemplates(retained, keys, rows, present.contains)
      result <- evaluateItemRows(
                  template.id,
                  rows.zip(entries),
                  removed.map(_.id),
                  previous,
                  template.state.lastEvaluated,
                  changed,
                  evaluation
                )
    yield CollectionRows(
      result._1,
      result._2,
      result._3,
      evaluated => template.state.assignment(next, removed, evaluated)
    )
  end evaluateSignalKeyedRows

  private def evaluateSignalKeyedByIndex[A, Msg](
    template: SignalKeyedByIndexTemplate[A, Msg],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    evaluation.transaction.sample(template.values).flatMap { sample =>
      evaluateCollection(
        template.id,
        sample,
        template.state.lastEvaluated,
        previous,
        evaluation
      )(changed =>
        evaluateSignalKeyedByIndexRows(
          template,
          sample.value,
          previous,
          evaluation,
          changed
        )
      )
    }

  private def evaluateSignalKeyedByIndexRows[A, Msg](
    template: SignalKeyedByIndexTemplate[A, Msg],
    items: Iterable[A],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg],
    changed: Set[RowId]
  ): Either[RenderError, CollectionRows[Msg]] =
    val values   = items.iterator.toVector
    val indices  = values.indices.toVector
    val retained = template.state.snapshot
    for
      rows <- traverse(indices) { index =>
                retained.get(index) match
                  case Some(row) => Right(row)
                  case None      =>
                    template.compiler.indexedRowCandidate(
                      template.project,
                      index,
                      evaluation.candidateRows
                    )
              }
      (next, removed) = keptRowTemplates(retained, indices, rows, _ < values.length)
      result <- evaluateItemRows(
                  template.id,
                  rows.zip(indices.zip(values)),
                  removed.map(_.id),
                  previous,
                  template.state.lastEvaluated,
                  changed,
                  evaluation
                )
    yield CollectionRows(
      result._1,
      result._2,
      result._3,
      evaluated => template.state.assignment(next, removed, evaluated)
    )
  end evaluateSignalKeyedByIndexRows

  /** The row templates a collection keeps after `retained`, now that its rows are `rows`, keyed
    * `keys`, and those of `retained` it no longer keeps, whose keys are not `present`.
    *
    * Only when `retained` and the new rows hold more than `keys` is `retained` searched for them.
    */
  private def keptRowTemplates[Key, A, Msg](
    retained: Map[Key, DynamicKeyedRowTemplate[A, Msg]],
    keys: Vector[Key],
    rows: Vector[DynamicKeyedRowTemplate[A, Msg]],
    present: Key => Boolean
  ): (Map[Key, DynamicKeyedRowTemplate[A, Msg]], Vector[DynamicKeyedRowTemplate[A, Msg]]) =
    if retained.isEmpty then return Map.from(keys.iterator.zip(rows)) -> Vector.empty
    var next  = retained
    var added = 0
    var index = 0
    while index < keys.length do
      if !retained.contains(keys(index)) then
        next = next.updated(keys(index), rows(index))
        added += 1
      index += 1
    if retained.size + added == keys.length then next -> Vector.empty
    else
      val removed = retained.filterNot((key, _) => present(key))
      (next -- removed.keys, removed.values.toVector)

  /** The evaluated rows of a collection, its node and the one it records, and the commit that keeps
    * them.
    */
  final private case class CollectionRows[Msg](
    node: EvaluatedNode.Keyed,
    recorded: EvaluatedNode.Keyed,
    rows: Map[RowId, EvaluatedRow[Msg]],
    assignment: SignalKeyedTemplate.Evaluated[Msg] => () => Unit)

  /** Reuses the committed rows of a collection when nothing they were evaluated from changed, and
    * otherwise evaluates them with `evaluateRows`, which, given the rows that read a signal from
    * outside them that changed, returns the rows and their commit.
    *
    * Nothing changed when the collection's items and every signal its rows read from outside them
    * are unchanged, and its rows render nothing that signals do not track. The reused rows keep
    * their node, bindings, and signal samples, and declare their components again.
    */
  private def evaluateCollection[Msg](
    id: TemplateId,
    items: SignalSample[?],
    last: Option[SignalKeyedTemplate.Evaluated[Msg]],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  )(
    evaluateRows: Set[RowId] => Either[RenderError, CollectionRows[Msg]]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    val old      = previous.collect { case keyed: EvaluatedNode.Keyed => keyed }
    val reusable = (last, old) match
      case (Some(rows), Some(committed))
          if !rows.signals.untracked && evaluation.transaction.unchanged(items) &&
            EvaluatedTree.resolvedFrom(committed, rows.node) &&
            evaluation.transaction.allUnchanged(rows.signals.outside) =>
        Some(rows -> committed)
      case _ => None

    reusable match
      case Some((rows, committed)) =>
        evaluation.bindings.reuseRows(id, rows.bindings).map { _ =>
          evaluation.requirements.components ++= rows.components
          committed
        }
      case None =>
        val declared = evaluation.requirements.components.length
        evaluation.bindings.enterRows(id)
        val evaluated = withinRows(
          evaluation.transaction,
          last.fold(SignalEvaluation.RowsEvaluation.empty)(_.signals)
        )(evaluateRows)
        val rowBindings = evaluation.bindings.exitRows()
        evaluated.map { (collection, signals) =>
          evaluation.commitActions += collection.assignment(
            EvaluatedRows(
              collection.recorded,
              signals,
              rowBindings,
              collection.rows,
              evaluation.requirements.componentsSince(declared)
            )
          )
          collection.node
        }
  end evaluateCollection

  /** Evaluates rows, whose signals belong one level deeper than their collection, given those of
    * them that read a signal from outside them that changed.
    */
  private def withinRows[A](
    transaction: SignalEvaluation.Transaction,
    previous: SignalEvaluation.RowsEvaluation
  )(
    evaluate: Set[RowId] => Either[RenderError, A]
  ): Either[RenderError, (A, SignalEvaluation.RowsEvaluation)] =
    transaction.enterRows(previous)
    val evaluated = evaluate(transaction.changedRows())
    val signals   = transaction.exitRows()
    evaluated.map(_ -> signals)

  /** Evaluates the rows of a plain collection, which share the scope around them. */
  private def evaluateKeyedRows[Msg](
    id: TemplateId,
    templates: Vector[KeyedRowTemplate[Msg]],
    previous: Option[EvaluatedNode],
    evaluation: Evaluation[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    val old     = previous.collect { case keyed: EvaluatedNode.Keyed => keyed }
    val oldRows = old.toVector.flatMap(_.rows).map(row => row.id -> row.child).toMap
    traverse(templates) { row =>
      evaluateNode(
        row.element,
        oldRows.get(row.id),
        evaluation
      ).map(node => EvaluatedNode.KeyedRow(row.id, node.asInstanceOf[EvaluatedNode.Element]))
    }.map(rows => keyedNode(id, old, rows, evaluation.revision))

  /** Evaluates the rows of a signal collection, each from its key and item. Each row keeps its
    * committed node when nothing it read changed, given `last`, the committed rows, which held the
    * rows `removed` too, and `changed`, those that read a signal from outside them that changed.
    */
  private def evaluateItemRows[A, Msg](
    id: TemplateId,
    rows: Vector[(DynamicKeyedRowTemplate[A, Msg], (Any, A))],
    removed: Vector[RowId],
    previous: Option[EvaluatedNode],
    last: Option[EvaluatedRows[?, Msg]],
    changed: Set[RowId],
    evaluation: Evaluation[Msg]
  ): Either[
    RenderError,
    (EvaluatedNode.Keyed, EvaluatedNode.Keyed, Map[RowId, EvaluatedRow[Msg]])
  ] =
    val old      = previous.collect { case keyed: EvaluatedNode.Keyed => keyed }
    val lastRows = last.fold(Map.empty[RowId, EvaluatedRow[Msg]])(_.rows)
    // The recorded rows are the committed ones when the recorded collection is: it then holds
    // nothing resolution would have filled in.
    val committed = last.exists(rows => old.exists(_ eq rows.node))
    val oldRows   =
      if committed then Map.empty[RowId, EvaluatedNode]
      else old.toVector.flatMap(_.rows).map(row => row.id -> row.child).toMap
    traverse(rows) { case (row, (key, item)) =>
      val lastRow = lastRows.get(row.id)
      evaluateRow(
        row.id,
        key,
        row.source,
        item,
        row.element,
        if committed then lastRow.map(_.node) else oldRows.get(row.id),
        lastRow,
        changed,
        transient = false,
        evaluation
      ).map(EvaluatedNode.KeyedRow(row.id, _) -> _)
    }.map { evaluated =>
      evaluation.transaction.dropRows(removed)
      val keyedRows = evaluated.map(_._1)
      val byId      = updatedRecords(
        lastRows -- removed,
        lastRows,
        keyedRows.iterator.map(_.id),
        evaluated.iterator.map(_._2)
      )
      val node = keyedNode(id, old, keyedRows, evaluation.revision)
      (node, recordedKeyed(node, evaluated), byId)
    }
  end evaluateItemRows

  /** `node`, whose rows are `rows`, holding each row's recorded node instead: what a collection
    * records holds what its rows record, without what resolution filled in.
    */
  private def recordedKeyed[Msg](
    node: EvaluatedNode.Keyed,
    rows: Vector[(EvaluatedNode.KeyedRow, EvaluatedRow[Msg])]
  ): EvaluatedNode.Keyed =
    if rows.forall((row, record) => row.child eq record.node) then node
    else
      node.copy(rows =
        rows.map((row, record) =>
          if row.child eq record.node then row else row.copy(child = record.node)
        )
      )

  /** `node` holding the recorded node of each of `rows`, the rows it evaluated, instead, also in
    * the operations that insert it, like [[recordedKeyed]].
    */
  private def recordedStream[Msg](
    node: EvaluatedNode.Stream,
    rows: Vector[(EvaluatedNode.StreamRow, EvaluatedRow[Msg])]
  ): EvaluatedNode.Stream =
    if rows.forall((row, record) => row.child eq record.node) then node
    else
      val recorded = rows.iterator.collect {
        case (row, record) if !(row.child eq record.node) =>
          row.domId -> row.copy(child = record.node)
      }.toMap
      def of(row: EvaluatedNode.StreamRow) = recorded.getOrElse(row.domId, row)
      val operations                       = node.operations.copy(inserts =
        node.operations.inserts.map(insert => insert.copy(row = of(insert.row)))
      )
      node.copy(rows = node.rows.map(of), operations = operations)

  private def keyedNode(
    id: TemplateId,
    old: Option[EvaluatedNode.Keyed],
    rows: Vector[EvaluatedNode.KeyedRow],
    revision: RenderRevision
  ): EvaluatedNode.Keyed =
    old match
      case Some(node) if node.id == id && sameRows(node.rows, rows) => node
      case Some(node)
          if node.id == id && sameBy(node.rows, rows)(_.id) &&
            sameBy(node.rows, rows)(_.child.revision) =>
        node.copy(rows = rows)
      case _ => EvaluatedNode.Keyed(id, rows, revision)

  /** Evaluates one row from `item`, or keeps its committed node, `previous`, when its item is
    * unchanged, it is not among the rows of `changed`, which read from outside them a signal that
    * changed, and it renders nothing that signals do not track. `last` is what the row's last
    * evaluation recorded, and `previous` that evaluation's node as resolution filled in its
    * components. A kept row keeps its bindings and signal samples, and declares its components
    * again. The bindings of a `transient` row, which its collection renders only for this
    * evaluation, are not kept with the collection's rows. Returns the row's node and what it
    * records.
    */
  private def evaluateRow[A, Msg](
    id: RowId,
    key: Any,
    source: Signal[A],
    item: A,
    element: ElementTemplate[Msg],
    previous: Option[EvaluatedNode],
    last: Option[EvaluatedRow[Msg]],
    changed: Set[RowId],
    transient: Boolean,
    evaluation: Evaluation[Msg]
  ): Either[RenderError, (EvaluatedNode.Element, EvaluatedRow[Msg])] =
    val kept = (last, evaluation.transaction.previousRow(id), previous) match
      case (Some(row), Some(read), Some(committed: EvaluatedNode.Element))
          if !transient && !read.untracked && row.item == item && !changed.contains(id) &&
            EvaluatedTree.resolvedFrom(committed, row.node) =>
        Some(committed -> row)
      case _ => None

    kept match
      case Some(keptRow @ (_, row)) =>
        evaluation.bindings.keepRow(id, row.bindings)
        evaluation.requirements.components ++= row.components
        Right(keptRow)
      case None =>
        evaluation.transaction.bindSource(source, item).flatMap { _ =>
          val declared = evaluation.requirements.components.length
          evaluation.transaction.enterRow(id)
          evaluation.bindings.enterRow()
          val evaluated = evaluateNode(
            element,
            previous,
            evaluation
          ).map(_.asInstanceOf[EvaluatedNode.Element])
          evaluation.transaction.exitRow()
          val rowBindings =
            if transient then
              evaluation.bindings.exitTransientRow()
              Vector.empty
            else evaluation.bindings.exitRow(id)
          evaluated.map(node =>
            node -> EvaluatedRow(
              EvaluatedTree.unresolved(node),
              rowBindings,
              item,
              key,
              evaluation.requirements.componentsSince(declared)
            )
          )
        }
    end match
  end evaluateRow

  /** Rejects a key `keys` holds twice, and otherwise returns them as a set. */
  private def validateEvaluationKeys[A](keys: Vector[A]): Either[RenderError, collection.Set[A]] =
    val seen = mutable.HashSet.empty[A]
    keys.find(key => !seen.add(key)) match
      case Some(key) => Left(RenderError.DuplicateKey(key))
      case None      => Right(seen)

  private def evaluateComponentRequirement[OwnerMsg](
    location: TemplateId,
    spec: ComponentSpec[OwnerMsg],
    transaction: SignalEvaluation.Transaction
  ): Either[RenderError, ComponentRequirement[OwnerMsg]] = spec match
    case ComponentSpec.Plain(component, applicationId, props) =>
      componentId(applicationId).map(id =>
        ComponentRequirement.Plain(location, id, component, props)
      )
    case ComponentSpec.PlainSignal(component, applicationId, props) =>
      for
        id     <- componentId(applicationId)
        sample <- transaction.sample(props)
      yield ComponentRequirement.Plain(location, id, component, sample.value)
    case ComponentSpec.Dynamic(component, applicationId, props) =>
      for
        idSample    <- transaction.sample(applicationId)
        id          <- componentId(idSample.value)
        propsSample <- transaction.sample(props)
      yield ComponentRequirement.Plain(location, id, component, propsSample.value)
    case ComponentSpec.Output(component, applicationId, props, onOutput) =>
      componentId(applicationId).map(id =>
        ComponentRequirement.WithOutput(
          location,
          id,
          component,
          props,
          onOutput
        )
      )
    case ComponentSpec.OutputSignal(component, applicationId, props, onOutput) =>
      for
        id     <- componentId(applicationId)
        sample <- transaction.sample(props)
      yield ComponentRequirement.WithOutput(
        location,
        id,
        component,
        sample.value,
        onOutput
      )
    case ComponentSpec.OutputDynamic(component, applicationId, props, onOutput) =>
      for
        idSample    <- transaction.sample(applicationId)
        id          <- componentId(idSample.value)
        propsSample <- transaction.sample(props)
      yield ComponentRequirement.WithOutput(
        location,
        id,
        component,
        propsSample.value,
        onOutput
      )

  private def evaluateNestedRequirement(
    location: TemplateId,
    spec: NestedViewSpec,
    transaction: SignalEvaluation.Transaction
  ): Either[RenderError, NestedRequirement] = spec match
    case NestedViewSpec.Static(applicationId, factory, sticky, linkParentOnCrash) =>
      componentId(applicationId).map(id =>
        NestedRequirement.Value(
          location,
          id,
          sticky,
          linkParentOnCrash,
          factory
        )
      )
    case NestedViewSpec.Dynamic(applicationId, value, factory, sticky, linkParentOnCrash) =>
      for
        id     <- componentId(applicationId)
        sample <- transaction.sample(value)
      yield NestedRequirement.Value(
        location,
        id,
        sticky,
        linkParentOnCrash(sample.value),
        () => factory(sample.value)
      )

  private def componentId(value: String): Either[RenderError, String] =
    nonNullString(value, "component or nested application id")

  private def evaluateText(
    template: TextTemplate,
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction
  ): Either[RenderError, EvaluatedNode.Text] =
    def node(slot: OptionalSlotId, text: String, raw: Boolean): EvaluatedNode.Text =
      previous match
        case Some(node: EvaluatedNode.Text)
            if node.id == template.id && node.slot == slot && node.value == text && node.raw == raw =>
          node
        case _ => EvaluatedNode.Text(template.id, slot, text, raw, revision)

    template.value match
      case TextTemplate.Value.Static(value, raw, slot)   => Right(node(slot, value, raw))
      case TextTemplate.Value.Dynamic(slot, signal, raw) =>
        transaction
          .sample(signal).flatMap(sample =>
            nonNullString(sample.value, "signal text content")
              .map(value => node(OptionalSlotId(slot), value, raw))
          )

  private def evaluateAttribute[Msg](
    template: AttributeTemplate[Msg],
    previous: Option[EvaluatedAttribute],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    bindings: BindingTable.Builder[Msg],
    formId: Option[String]
  ): Either[RenderError, EvaluatedAttribute] =
    import AttributeTemplate.*

    def attribute(slot: OptionalSlotId, value: Option[AttributeValue]): EvaluatedAttribute =
      previous match
        case Some(attribute)
            if attribute.name == template.name && attribute.slot == slot && attribute.value == value =>
          attribute
        case _ => EvaluatedAttribute(template.name, value, slot, revision)

    template match
      case Static(_, value, slot)        => Right(attribute(slot, value))
      case DynamicValue(_, slot, signal) =>
        transaction
          .sample(signal).flatMap(sample =>
            nonNullString(sample.value, s"signal attribute '${template.name}'")
              .map(value => attribute(OptionalSlotId(slot), Some(AttributeValue.Text(value))))
          )
      case DynamicOptional(_, slot, signal) =>
        transaction.sample(signal).flatMap { sample =>
          sample.value match
            case Some(value) =>
              nonNullString(value, s"optional signal attribute '${template.name}'")
                .map(value => attribute(OptionalSlotId(slot), Some(AttributeValue.Text(value))))
            case None => Right(attribute(OptionalSlotId(slot), None))
        }
      case DynamicPresence(_, slot, signal) =>
        transaction
          .sample(signal).map(sample =>
            attribute(OptionalSlotId(slot), Option.when(sample.value)(AttributeValue.Presence))
          )
      case Composite(name, slot, parts) =>
        traverse(parts)(part => evaluateCompositePart(name, part, transaction))
          .map(tokens => attribute(OptionalSlotId(slot), compositeAttributeValue(tokens.flatten)))
      case Binding(name, valueSlot, id, operation) =>
        val resolvedId = resolveBindingId(name, id, formId)
        bindings
          .add(resolvedId, BindingOperation(operation)).map(_ =>
            attribute(OptionalSlotId(valueSlot), Some(AttributeValue.Text(resolvedId.encoded)))
          )
      case SignalBinding(name, valueSlot, id, signal, operation) =>
        val resolvedId = resolveBindingId(name, id, formId)
        transaction.sample(signal).flatMap { sample =>
          bindings
            .add(resolvedId, BindingOperation(payload => operation(sample.value, payload))).map(_ =>
              attribute(OptionalSlotId(valueSlot), Some(AttributeValue.Text(resolvedId.encoded)))
            )
        }
      case JsBinding(name, valueSlot, id, command) =>
        evaluateJs(resolveBindingId(name, id, formId), command, bindings).map(value =>
          attribute(OptionalSlotId(valueSlot), Some(AttributeValue.Text(value)))
        )
      case SignalJsBinding(name, valueSlot, id, command) =>
        val resolvedId = resolveBindingId(name, id, formId)
        transaction
          .sample(command).flatMap(sample =>
            evaluateJs(resolvedId, sample.value, bindings)
              .map(value => attribute(OptionalSlotId(valueSlot), Some(AttributeValue.Text(value))))
          )
      case RoutedBinding(name, valueSlot, id, operation) =>
        val resolvedId = resolveBindingId(name, id, formId)
        bindings
          .add(
            resolvedId,
            BindingOperation.dispatching(payload => BindingDispatch.Routed(operation(payload)))
          ).map(_ =>
            attribute(OptionalSlotId(valueSlot), Some(AttributeValue.Text(resolvedId.encoded)))
          )
      case ComponentTarget(target, valueSlot) =>
        Right(attribute(OptionalSlotId(valueSlot), Some(AttributeValue.ComponentTarget(target))))
      case Choice(_, signal, branches) =>
        transaction.sample(signal).flatMap { sample =>
          branches.find(_._1 == sample.value) match
            case Some((_, branch)) =>
              evaluateAttribute(branch, previous, revision, transaction, bindings, formId)
                .map(selected => attribute(selected.slot, selected.value))
            case None => Right(attribute(OptionalSlotId.none, None))
        }
    end match

  end evaluateAttribute

  private def evaluateCompositePart(
    name: String,
    part: CompositePart,
    transaction: SignalEvaluation.Transaction
  ): Either[RenderError, Vector[String]] =
    part match
      case CompositePart.Static(tokens)  => Right(tokens)
      case CompositePart.Dynamic(signal) =>
        transaction
          .sample(signal).flatMap(sample =>
            nonNullString(sample.value, s"signal attribute '$name'").map(compositeTokens)
          )
      case CompositePart.DynamicOptional(signal) =>
        transaction.sample(signal).flatMap { sample =>
          sample.value match
            case Some(value) =>
              nonNullString(value, s"optional signal attribute '$name'").map(compositeTokens)
            case None => Right(Vector.empty)
        }
      case CompositePart.Choice(signal, branches) =>
        transaction.sample(signal).flatMap { sample =>
          branches.find(_._1 == sample.value) match
            case Some((_, selected)) => evaluateCompositePart(name, selected, transaction)
            case None                => Right(Vector.empty)
        }

  private def resolveBindingId(
    name: String,
    id: BindingId,
    formId: Option[String]
  ): BindingId =
    if name.equalsIgnoreCase("phx-change") then formId.fold(id)(BindingId.formChange)
    else id

  private def evaluateJs[Msg](
    id: BindingId,
    command: JSCommand[Msg],
    bindings: BindingTable.Builder[Msg]
  ): Either[RenderError, String] =
    val scope = id.encoded
    command
      .bindings(scope).foldLeft[Either[RenderError, Unit]](Right(())) {
        case (result, (encodedId, operation)) =>
          result.flatMap(_ =>
            bindings.add(
              BindingId.fromEncoded(encodedId),
              BindingOperation(operation)
            )
          )
      }.map(_ => command.renderJson(scope))

  private def retainElementRevision[Msg](
    template: ElementTemplate[Msg],
    attributes: Vector[EvaluatedAttribute],
    children: Vector[EvaluatedNode],
    previous: Option[EvaluatedNode.Element],
    revision: RenderRevision
  ): EvaluatedNode.Element =
    previous match
      case Some(node)
          if node.id == template.id && sameInstances(node.attributes, attributes) &&
            sameInstances(node.children, children) =>
        node
      case Some(node)
          if node.id == template.id && node.tag == template.tag && node.void == template.void &&
            sameBy(node.attributes, attributes)(_.revision) &&
            sameBy(node.children, children)(_.revision) =>
        node.copy(attributes = attributes, children = children)
      case _ =>
        EvaluatedNode.Element(
          template.id,
          template.tag,
          template.void,
          attributes,
          children,
          revision
        )

  // Inline, so comparing ids or revisions neither boxes them nor maps the vectors to compare.
  private inline def sameBy[A, B](
    previous: Vector[A],
    current: Vector[A]
  )(
    inline key: A => B
  ): Boolean =
    previous.length == current.length && previous.indices.forall(index =>
      key(previous(index)) == key(current(index))
    )

  // Keeping the previous instance of an unchanged node lets the differ skip it by identity.
  private def sameInstances[A <: AnyRef](previous: Vector[A], current: Vector[A]): Boolean =
    previous.length == current.length && previous.indices.forall(index =>
      previous(index) eq current(index)
    )

  private def sameRows(
    previous: Vector[EvaluatedNode.KeyedRow],
    current: Vector[EvaluatedNode.KeyedRow]
  ): Boolean =
    previous.length == current.length && previous.indices.forall(index =>
      previous(index).id == current(index).id && (previous(index).child eq current(index).child)
    )

  private def traverseIndexed[A, B](
    values: Vector[A]
  )(
    f: (A, Int) => Either[RenderError, B]
  ): Either[RenderError, Vector[B]] =
    var index = -1
    traverse(values) { value =>
      index += 1
      f(value, index)
    }

  private def traverse[A, B](
    values: Vector[A]
  )(
    f: A => Either[RenderError, B]
  ): Either[RenderError, Vector[B]] =
    // Mapping allocates only the results, where a builder would allocate room for 32 of them.
    boundary:
      Right(values.map { value =>
        f(value) match
          case Right(result) => result
          case Left(error)   => break(Left(error))
      })

  private def compositeTokens(value: String): Vector[String] =
    val tokens     = Vector.newBuilder[String]
    var tokenStart = -1
    var index      = 0
    while index < value.length do
      if htmlAsciiWhitespace(value.charAt(index)) then
        if tokenStart >= 0 then
          tokens += value.substring(tokenStart, index)
          tokenStart = -1
      else if tokenStart < 0 then tokenStart = index
      index += 1
    if tokenStart >= 0 then tokens += value.substring(tokenStart)
    tokens.result()

  private def htmlAsciiWhitespace(value: Char): Boolean =
    value == ' ' || value == '\t' || value == '\n' || value == '\f' || value == '\r'

  private def compositeAttributeValue(tokens: Vector[String]): Option[AttributeValue] =
    val seen     = scala.collection.mutable.HashSet.empty[String]
    val distinct = tokens.filter(seen.add)
    Option.when(distinct.nonEmpty)(AttributeValue.Text(distinct.mkString(" ")))

  private def nonNullString(value: String, location: String): Either[RenderError, String] =
    Either.cond(value != null, value, RenderError.InvalidHtml(s"$location has a null value"))

  private def retainedFlashProjectionCount(template: NodeTemplate[?]): Int = template match
    case element: ElementTemplate[?] => element.children.map(retainedFlashProjectionCount).sum
    case flash: FlashTemplate        =>
      flash.state.snapshot match
        case Some((_, projection)) => 1 + retainedFlashProjectionCount(projection)
        case None                  => 0
    case choice: ChoiceTemplate[?] =>
      choice.branches.flatMap(_._2).map(retainedFlashProjectionCount).sum
    case keyed: KeyedTemplate[?] =>
      keyed.rows.map(row => retainedFlashProjectionCount(row.element)).sum
    case keyed: SignalKeyedTemplate[?, ?, ?] =>
      keyed.state.snapshot.values.map(row => retainedFlashProjectionCount(row.element)).sum
    case keyed: SignalKeyedByIndexTemplate[?, ?] =>
      keyed.state.snapshot.values.map(row => retainedFlashProjectionCount(row.element)).sum
    case _: ComponentTemplate[?] | _: NestedTemplate =>
      0
    case stream: StaticStreamTemplate[?, ?] =>
      stream.state
        .snapshot(stream.stream.identity).values.map(row =>
          retainedFlashProjectionCount(row.element)
        ).sum
    case _: SignalStreamTemplate[?, ?] => 0
    case _: TextTemplate               => 0

  private def retainedKeyedRowCount(template: NodeTemplate[?]): Int = template match
    case element: ElementTemplate[?] => element.children.map(retainedKeyedRowCount).sum
    case choice: ChoiceTemplate[?]   =>
      choice.branches.flatMap(_._2).map(retainedKeyedRowCount).sum
    case keyed: KeyedTemplate[?]                 => keyed.rows.size
    case keyed: SignalKeyedTemplate[?, ?, ?]     => keyed.state.snapshot.size
    case keyed: SignalKeyedByIndexTemplate[?, ?] => keyed.state.snapshot.size
    case flash: FlashTemplate                    =>
      flash.state.snapshot.map((_, child) => retainedKeyedRowCount(child)).getOrElse(0)
    case _: ComponentTemplate[?] | _: NestedTemplate =>
      0
    case stream: StaticStreamTemplate[?, ?] => stream.state.snapshot(stream.stream.identity).size
    case _: SignalStreamTemplate[?, ?]      => 0
    case _: TextTemplate                    => 0

  /** What a committed collection or stream keeps of one row: its record, an `EvaluatedRow` this
    * leaves opaque, and what it read.
    */
  final private[render] case class RetainedRow(
    record: Option[AnyRef],
    read: Option[SignalEvaluation.RowSignals])

  private def retainedRows(template: NodeTemplate[?]): Map[RowId, RetainedRow] =
    def of(rows: Option[EvaluatedRows[?, ?]]): Map[RowId, RetainedRow] =
      rows.fold(Map.empty) { rows =>
        (rows.rows.keySet ++ rows.signals.rows.keySet).iterator
          .map(id => id -> RetainedRow(rows.rows.get(id), rows.signals.rows.get(id)))
          .toMap
      }
    template match
      case element: ElementTemplate[?] =>
        element.children.map(retainedRows).foldLeft(Map.empty)(_ ++ _)
      case choice: ChoiceTemplate[?] =>
        choice.branches.flatMap(_._2).map(retainedRows).foldLeft(Map.empty)(_ ++ _)
      case keyed: SignalKeyedTemplate[?, ?, ?]     => of(keyed.state.lastEvaluated)
      case keyed: SignalKeyedByIndexTemplate[?, ?] => of(keyed.state.lastEvaluated)
      case stream: StaticStreamTemplate[?, ?]      => of(stream.state.committed)
      case stream: SignalStreamTemplate[?, ?]      => of(stream.state.committed)
      case _                                       => Map.empty

  private def closeRetainedRows(template: NodeTemplate[?]): Unit = template match
    case element: ElementTemplate[?] => element.children.foreach(closeRetainedRows)
    case choice: ChoiceTemplate[?]   =>
      choice.branches.flatMap(_._2).foreach(closeRetainedRows)
    case keyed: KeyedTemplate[?] => keyed.rows.foreach(row => closeRetainedRows(row.element))
    case keyed: SignalKeyedTemplate[?, ?, ?] =>
      keyed.state.snapshot.values.foreach(row => closeRetainedRows(row.element))
      keyed.state.closeAll()
    case keyed: SignalKeyedByIndexTemplate[?, ?] =>
      keyed.state.snapshot.values.foreach(row => closeRetainedRows(row.element))
      keyed.state.closeAll()
    case flash: FlashTemplate =>
      flash.state.snapshot.foreach((_, child) => closeRetainedRows(child))
    case stream: StaticStreamTemplate[?, ?]                            => stream.state.closeAll()
    case stream: SignalStreamTemplate[?, ?]                            => stream.state.closeAll()
    case _: TextTemplate | _: ComponentTemplate[?] | _: NestedTemplate =>
      ()
end RenderProgram
