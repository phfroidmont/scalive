package scalive.render

import java.util.Locale
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

  /** What the committed collections and streams of the program's own template keep of each row: its
    * record and what it read.
    */
  private[render] def retainedRows: Map[RowId, (AnyRef, AnyRef)] =
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

  /** A committed row, with the bindings it rendered and the components it declared, in order.
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
      case Static(value: String, raw: Boolean, slot: Option[TemplateSlotId] = None)
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
      slot: Option[TemplateSlotId] = None)
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

    def compileElement[Msg](element: HtmlElement[Msg]): Either[RenderError, ElementTemplate[Msg]] =
      for
        id    <- allocator.template()
        parts <- classifyMods(element.mods)
        (attributeCandidates, contents) = parts
        _          <- validateAttributeCandidateNames(attributeCandidates)
        finalized  <- finalizeAttributes(attributeCandidates)
        attributes <- targetOwner(finalized)
        _          <- validateAttributeNames(attributes)
        _          <- Either.cond(
               !element.tag.void || contents.isEmpty,
               (),
               RenderError.InvalidHtml(
                 s"void element '${element.tag.name}' cannot contain child content"
               )
             )
        children <- traverse(contents)(compileContent)
      yield ElementTemplate(id, element.tag.name, element.tag.void, attributes, children)

    private def classifyMods[Msg](
      mods: Vector[Mod[Msg]]
    ): Either[RenderError, (Vector[AttributeCandidate[Msg]], Vector[Mod.Content[Msg]])] =
      mods
        .foldLeft[Either[RenderError, (Vector[AttributeCandidate[Msg]], Vector[Mod.Content[Msg]])]](
          Right(Vector.empty -> Vector.empty)
        ) { (result, mod) =>
          result.flatMap { case (attributes, contents) =>
            mod match
              case Mod.Attr.Group(attrs) =>
                traverse(attrs.flatMap(_.flattened))(compileAttribute)
                  .map(compiled => attributes.appendedAll(compiled) -> contents)
              case attribute: Mod.Attr[Msg] =>
                compileAttribute(attribute).map(compiled => (attributes :+ compiled) -> contents)
              case choice @ Mod.Content.SignalChoice(_, _) =>
                val branchMods = choice.branches.flatMap(_._2)
                val allAttrs = branchMods.nonEmpty && branchMods.forall(_.isInstanceOf[Mod.Attr[?]])
                val allContent = branchMods.forall(_.isInstanceOf[Mod.Content[?]])
                if allAttrs then
                  compileAttributeChoice(choice).map(compiled =>
                    (attributes :+ compiled) -> contents
                  )
                else if allContent then Right(attributes -> (contents :+ choice))
                else
                  Left(
                    RenderError.InvalidHtml(
                      "the branches of a choice cannot mix attributes and content"
                    )
                  )
              case content: Mod.Content[Msg] => Right(attributes -> (contents :+ content))
          }
        }

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
      candidates
        .foldLeft[Either[RenderError, (Vector[AttributeTemplate[Msg]], Set[String])]](
          Right(Vector.empty -> Set.empty)
        ) { (result, candidate) =>
          result.flatMap { case (attributes, emittedCompositeNames) =>
            candidate match
              case AttributeCandidate.Scalar(template) =>
                Right((attributes :+ template) -> emittedCompositeNames)
              case AttributeCandidate.Composite(name, _) =>
                val normalizedName = name.toLowerCase(Locale.ROOT)
                if emittedCompositeNames.contains(normalizedName) then
                  Right(attributes -> emittedCompositeNames)
                else
                  val parts = candidates.collect {
                    case AttributeCandidate.Composite(candidateName, part)
                        if candidateName.equalsIgnoreCase(name) =>
                      part
                  }
                  finalizeCompositeAttribute(name, parts).map(template =>
                    (attributes :+ template) -> (emittedCompositeNames + normalizedName)
                  )
          }
        }.map(_._1)

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
          for
            value <- nonNullString(text, "text content")
            id    <- allocator.template()
          yield TextTemplate(id, TextTemplate.Value.Static(value, raw))
        case Mod.Content.SignalText(value, raw) =>
          for
            _    <- scope.validate(value)
            id   <- allocator.template()
            slot <- allocator.slot()
          yield TextTemplate(id, TextTemplate.Value.Dynamic(slot, value, raw))
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
      traverse(candidates)(candidate => validateAttributeName(candidate.name)).map(_ => ())

    private def validateAttributeName(name: String): Either[RenderError, Unit] =
      Either.cond(
        name != null && Escaping.validAttrName(name),
        (),
        RenderError.InvalidHtml(s"invalid HTML attribute name '$name'")
      )

    private def validateAttributeNames[Msg](
      attributes: Vector[AttributeTemplate[Msg]]
    ): Either[RenderError, Unit] =
      attributes
        .foldLeft[Either[RenderError, Set[String]]](Right(Set.empty)) { case (result, attribute) =>
          result.flatMap { names =>
            validateAttributeName(attribute.name).flatMap { _ =>
              val normalizedName = attribute.name.toLowerCase(Locale.ROOT)
              if names.contains(normalizedName) then
                Left(RenderError.InvalidHtml(s"duplicate HTML attribute '${attribute.name}'"))
              else Right(names + normalizedName)
            }
          }
        }.map(_ => ())

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
            static.copy(slot = allocator.slot().fold(throw _, Some(_)))
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
                    allocator.slot().fold(throw _, Some(_))
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
    for
      attributes <- evaluateAttributes(
                      template,
                      previous,
                      revision,
                      transaction,
                      bindings
                    )
      children <- traverseIndexed(template.children) { (child, index) =>
                    val previousChild = previous.flatMap(_.children.lift(index))
                    evaluateNode(
                      child,
                      previousChild,
                      revision,
                      transaction,
                      flash,
                      bindings,
                      commitActions,
                      candidateRows,
                      requirements
                    )
                  }
      element = retainElementRevision(template, attributes, children, previous, revision)
      table <- bindings.result()
    yield (
      element,
      table,
      requirements,
      CandidateCommit(commitActions.toArray, candidateRows.rollbackActions, candidateRows.scopes)
    )
    end for
  end evaluateElement

  private def evaluateAttributes[Msg](
    template: ElementTemplate[Msg],
    previous: Option[EvaluatedNode.Element],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    bindings: BindingTable.Builder[Msg]
  ): Either[RenderError, Vector[EvaluatedAttribute]] =
    // Phoenix gates form recovery on the old and new phx-change values matching exactly.
    val formIdIndex = Option
      .when(template.tag.equalsIgnoreCase("form"))(
        template.attributes.indexWhere(_.name.equalsIgnoreCase("id"))
      ).filter(_ >= 0)

    val evaluatedFormId = formIdIndex match
      case Some(index) =>
        evaluateAttribute(
          template.attributes(index),
          previous.flatMap(_.attributes.lift(index)),
          revision,
          transaction,
          bindings,
          None
        ).map(attribute => Some((index, attribute, textValue(attribute).filter(_.nonEmpty))))
      case None => Right(None)

    evaluatedFormId.flatMap { evaluatedId =>
      val formId = evaluatedId.flatMap(_._3)
      traverseIndexed(template.attributes) { (attribute, index) =>
        evaluatedId match
          case Some((idIndex, value, _)) if index == idIndex => Right(value)
          case _                                             =>
            evaluateAttribute(
              attribute,
              previous.flatMap(_.attributes.lift(index)),
              revision,
              transaction,
              bindings,
              formId
            )
      }
    }
  end evaluateAttributes

  private def textValue(attribute: EvaluatedAttribute): Option[String] =
    attribute.value.collect { case AttributeValue.Text(value) => value }

  private def evaluateNode[Msg](
    template: NodeTemplate[Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode] =
    template match
      case element: ElementTemplate[Msg] =>
        for
          previousElement = previous.collect { case node: EvaluatedNode.Element => node }
          attributes <- evaluateAttributes(
                          element,
                          previousElement,
                          revision,
                          transaction,
                          bindings
                        )
          children <- traverseIndexed(element.children) { (child, index) =>
                        val previousChild = previousElement.flatMap(_.children.lift(index))
                        evaluateNode(
                          child,
                          previousChild,
                          revision,
                          transaction,
                          flash,
                          bindings,
                          commitActions,
                          candidateRows,
                          requirements
                        )
                      }
        yield retainElementRevision(
          element,
          attributes,
          children,
          previousElement,
          revision
        )
      case text: TextTemplate            => evaluateText(text, previous, revision, transaction)
      case template: ChoiceTemplate[Msg] =>
        for
          selected <- transaction.sample(template.signal)
          previousChildren = previous
                               .collect { case choice: EvaluatedNode.Choice => choice.children }
                               .getOrElse(Vector.empty)
          children <- template.branches.find(_._1 == selected.value) match
                        case Some((_, branch)) =>
                          traverseIndexed(branch) { (node, index) =>
                            evaluateNode(
                              node,
                              previousChildren.lift(index),
                              revision,
                              transaction,
                              flash,
                              bindings,
                              commitActions,
                              candidateRows,
                              requirements
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
          case _ => EvaluatedNode.Choice(template.id, children, revision)
      case template: KeyedTemplate[Msg] =>
        evaluateKeyedRows(
          template.id,
          template.rows,
          previous,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements
        )
      case template: SignalKeyedTemplate[?, ?, Msg] =>
        evaluateSignalKeyed(
          template,
          previous,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements
        )
      case template: SignalKeyedByIndexTemplate[?, Msg] =>
        evaluateSignalKeyedByIndex(
          template,
          previous,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements
        )
      // Signals track a component's declaration. A kept row or collection declares its components
      // again from what it recorded, and the runtime resolves them into it on every turn.
      case template: ComponentTemplate[Msg] =>
        evaluateComponentRequirement(template.id, template.spec, transaction).map { requirement =>
          requirements.components += requirement
          EvaluatedNode.Component(
            template.id,
            requirement.applicationId,
            None,
            revision
          )
        }
      case template: NestedTemplate =>
        transaction.markUntracked()
        evaluateNestedRequirement(template.id, template.spec, transaction).map { requirement =>
          requirements.nested += requirement
          previous match
            case Some(old: EvaluatedNode.Nested)
                if old.id == template.id && old.applicationId == requirement.applicationId =>
              EvaluatedNode.Nested(template.id, requirement.applicationId, None, old.revision)
            case _ => EvaluatedNode.Nested(template.id, requirement.applicationId, None, revision)
        }
      case template: StaticStreamTemplate[?, Msg] =>
        transaction.markUntracked()
        val requirement = StreamRequirement.Static(
          template.id,
          template.stream,
          template.project
        )
        requirements.streams += requirement
        evaluateStaticStream(
          template,
          previous,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements
        )
      case template: SignalStreamTemplate[?, Msg] =>
        transaction.markUntracked()
        transaction.sample(template.stream).flatMap { sample =>
          requirements.streams += StreamRequirement.SignalBacked(
            template.id,
            sample.value,
            template.project
          )
          evaluateSignalStream(
            template,
            sample.value,
            transaction.unchanged(sample),
            previous,
            revision,
            transaction,
            flash,
            bindings,
            commitActions,
            candidateRows,
            requirements
          )
        }
      case template: FlashTemplate =>
        // A flash comes from the render state, not from a signal.
        transaction.markUntracked()
        val oldFlash = previous.collect { case node: EvaluatedNode.Flash => node }
        def absent   =
          commitActions += template.state.assignment(None)
          oldFlash match
            case Some(node) if node.id == template.id && node.child.isEmpty => Right(node)
            case _ => Right(EvaluatedNode.Flash(template.id, None, revision))
        val candidate =
          flash.get(template.key) match
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
                           revision,
                           transaction,
                           flash,
                           bindings,
                           commitActions,
                           candidateRows,
                           requirements
                         ).map(_.asInstanceOf[EvaluatedNode.Element])
            yield
              commitActions += template.state.assignment(Some(value -> projected))
              oldFlash match
                case Some(node) if node.id == template.id && node.child.exists(_ eq child) => node
                case Some(node)
                    if node.id == template.id && node.child.exists(_.revision == child.revision) =>
                  node.copy(child = Some(child))
                case _ => EvaluatedNode.Flash(template.id, Some(child), revision)
        }

  private def evaluateStaticStream[A, Msg](
    template: StaticStreamTemplate[A, Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Stream] =
    evaluateStream(
      template.id,
      template.stream,
      // The view holds this stream, which therefore never changes.
      unchanged = true,
      template.state,
      previous,
      revision,
      transaction,
      flash,
      bindings,
      commitActions,
      candidateRows,
      requirements,
      (domId, value, retained) =>
        template.compiler.staticStreamRowCandidate(
          domId,
          value,
          template.project,
          retained,
          candidateRows
        )
    )

  private def evaluateSignalStream[A, Msg](
    template: SignalStreamTemplate[A, Msg],
    stream: LiveStream[A],
    unchanged: Boolean,
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Stream] =
    evaluateStream(
      template.id,
      stream,
      unchanged,
      template.state,
      previous,
      revision,
      transaction,
      flash,
      bindings,
      commitActions,
      candidateRows,
      requirements,
      (domId, _, retained) =>
        retained match
          case Some(row) => Right(row)
          case None      =>
            template.compiler.signalStreamRowCandidate(domId, template.project, candidateRows)
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
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg],
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
            EvaluatedTree.resolvedFrom(committed, rows.node) =>
        transaction
          .allUnchanged(rows.signals.outside)
          .map(Option.when(_)(rows -> committed))
      case _ => Right(None)

    reusable.flatMap {
      case Some((rows, committed)) =>
        bindings.reuseRows(id, rows.bindings).map { _ =>
          requirements.components ++= rows.components
          committed
        }
      case None =>
        val declared = requirements.components.length
        bindings.enterRows(id)
        val evaluated = evaluateStreamRows(
          id,
          stream,
          state,
          old,
          last,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements,
          rowCandidate
        )
        val rowBindings = bindings.exitRows()
        evaluated.map { (node, recorded, rows, signals, changes) =>
          commitActions += state.assignment(
            stream.identity,
            changes,
            EvaluatedRows(
              recorded,
              signals,
              rowBindings,
              rows,
              requirements.componentsSince(declared)
            )
          )
          node
        }
    }
  end evaluateStream

  private def evaluateStreamRows[A, Msg](
    id: TemplateId,
    stream: LiveStream[A],
    state: StreamTemplate.State[A, Msg],
    old: Option[EvaluatedNode.Stream],
    last: Option[EvaluatedRows[EvaluatedNode.Stream, Msg]],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg],
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
          transaction,
          last.fold(SignalEvaluation.RowsEvaluation.empty)(_.signals)
        ) {
          traverse(templates.zip(renderedEntries)) { case (row, entry) =>
            val lastRow = lastRows.get(row.id)
            evaluateRow(
              row.id,
              row.source,
              entry.value,
              row.element,
              if committed then lastRow.map(_.node) else oldRows.get(entry.domId),
              lastRow,
              revision,
              transaction,
              flash,
              bindings,
              commitActions,
              candidateRows,
              requirements
            ).map(EvaluatedNode.StreamRow(entry.domId, _) -> _)
          }.map { evaluated =>
            val changes = streamRowChanges(stream, retained, templates)
            transaction.dropRows(changes.dropped)
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
        commitActions += (() => transientTemplates.foreach(_.retire()))
      val node = old match
        case Some(node)
            if node.id == id && (node.identity eq stream.identity) &&
              node.generation == stream.generation &&
              sameBy(node.rows, rows)(_.child.revision) =>
          node.copy(rows = rows, operations = operations)
        case _ =>
          EvaluatedNode.Stream(id, stream.identity, stream.generation, rows, operations, revision)
      val records = updatedRecords(
        lastRows -- changes.dropped,
        lastRows,
        templates.iterator.take(entries.length).map(_.id),
        evaluated.iterator.map(_._2)
      )
      (node, recordedStream(node, evaluated), records, rowSignals, changes)
    end for
  end evaluateStreamRows

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
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    transaction.sample(template.values).flatMap { sample =>
      evaluateCollection(
        template.id,
        sample,
        template.state.lastEvaluated,
        previous,
        transaction,
        bindings,
        commitActions,
        requirements
      )(
        evaluateSignalKeyedRows(
          template,
          sample.value,
          previous,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements
        )
      )
    }

  private def evaluateSignalKeyedRows[A, Key, Msg](
    template: SignalKeyedTemplate[A, Key, Msg],
    values: Iterable[A],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, CollectionRows[Msg]] =
    val entries = values.iterator.map(value => template.key(value) -> value).toVector
    for
      keys <- validateEvaluationKeys(entries.map(_._1))
      retained = template.state.snapshot
      rows <- traverse(entries) { case (key, _) =>
                retained.get(key) match
                  case Some(row) => Right(row)
                  case None      =>
                    template.compiler.keyedRowCandidate(
                      template.project,
                      key,
                      candidateRows
                    )
              }
      (next, removed) = keptRowTemplates(retained, entries.map(_._1), rows, keys.contains)
      result <- evaluateItemRows(
                  template.id,
                  rows.zip(entries.map(_._2)),
                  removed.map(_.id),
                  previous,
                  template.state.lastEvaluated,
                  revision,
                  transaction,
                  flash,
                  bindings,
                  commitActions,
                  candidateRows,
                  requirements
                )
    yield CollectionRows(
      result._1,
      result._2,
      result._3,
      evaluated => template.state.assignment(next, removed, evaluated)
    )
    end for
  end evaluateSignalKeyedRows

  private def evaluateSignalKeyedByIndex[A, Msg](
    template: SignalKeyedByIndexTemplate[A, Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    transaction.sample(template.values).flatMap { sample =>
      evaluateCollection(
        template.id,
        sample,
        template.state.lastEvaluated,
        previous,
        transaction,
        bindings,
        commitActions,
        requirements
      )(
        evaluateSignalKeyedByIndexRows(
          template,
          sample.value,
          previous,
          revision,
          transaction,
          flash,
          bindings,
          commitActions,
          candidateRows,
          requirements
        )
      )
    }

  private def evaluateSignalKeyedByIndexRows[A, Msg](
    template: SignalKeyedByIndexTemplate[A, Msg],
    items: Iterable[A],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
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
                      candidateRows
                    )
              }
      (next, removed) = keptRowTemplates(retained, indices, rows, _ < values.length)
      result <- evaluateItemRows(
                  template.id,
                  rows.zip(values),
                  removed.map(_.id),
                  previous,
                  template.state.lastEvaluated,
                  revision,
                  transaction,
                  flash,
                  bindings,
                  commitActions,
                  candidateRows,
                  requirements
                )
    yield CollectionRows(
      result._1,
      result._2,
      result._3,
      evaluated => template.state.assignment(next, removed, evaluated)
    )
    end for
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
    * otherwise evaluates them with `evaluateRows`, which returns the rows and their commit.
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
    transaction: SignalEvaluation.Transaction,
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    requirements: Requirements[Msg]
  )(
    evaluateRows: => Either[RenderError, CollectionRows[Msg]]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    val old      = previous.collect { case keyed: EvaluatedNode.Keyed => keyed }
    val reusable = (last, old) match
      case (Some(rows), Some(committed))
          if !rows.signals.untracked && transaction.unchanged(items) &&
            EvaluatedTree.resolvedFrom(committed, rows.node) =>
        transaction
          .allUnchanged(rows.signals.outside)
          .map(Option.when(_)(rows -> committed))
      case _ => Right(None)

    reusable.flatMap {
      case Some((rows, committed)) =>
        bindings.reuseRows(id, rows.bindings).map { _ =>
          requirements.components ++= rows.components
          committed
        }
      case None =>
        val declared = requirements.components.length
        bindings.enterRows(id)
        val evaluated = withinRows(
          transaction,
          last.fold(SignalEvaluation.RowsEvaluation.empty)(_.signals)
        )(evaluateRows)
        val rowBindings = bindings.exitRows()
        evaluated.map { (collection, signals) =>
          commitActions += collection.assignment(
            EvaluatedRows(
              collection.recorded,
              signals,
              rowBindings,
              collection.rows,
              requirements.componentsSince(declared)
            )
          )
          collection.node
        }
    }
  end evaluateCollection

  /** Evaluates rows, whose signals belong one level deeper than their collection. */
  private def withinRows[A](
    transaction: SignalEvaluation.Transaction,
    previous: SignalEvaluation.RowsEvaluation
  )(
    evaluate: => Either[RenderError, A]
  ): Either[RenderError, (A, SignalEvaluation.RowsEvaluation)] =
    transaction.enterRows(previous)
    val evaluated = evaluate
    val signals   = transaction.exitRows()
    evaluated.map(_ -> signals)

  /** Evaluates the rows of a plain collection, which share the scope around them. */
  private def evaluateKeyedRows[Msg](
    id: TemplateId,
    templates: Vector[KeyedRowTemplate[Msg]],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    val old     = previous.collect { case keyed: EvaluatedNode.Keyed => keyed }
    val oldRows = old.toVector.flatMap(_.rows).map(row => row.id -> row.child).toMap
    traverse(templates) { row =>
      evaluateNode(
        row.element,
        oldRows.get(row.id),
        revision,
        transaction,
        flash,
        bindings,
        commitActions,
        candidateRows,
        requirements
      ).map(node => EvaluatedNode.KeyedRow(row.id, node.asInstanceOf[EvaluatedNode.Element]))
    }.map(rows => keyedNode(id, old, rows, revision))

  /** Evaluates the rows of a signal collection, each from its item. Each row keeps its committed
    * node when nothing it read changed, given `last`, the committed rows, which held the rows
    * `removed` too.
    */
  private def evaluateItemRows[A, Msg](
    id: TemplateId,
    rows: Vector[(DynamicKeyedRowTemplate[A, Msg], A)],
    removed: Vector[RowId],
    previous: Option[EvaluatedNode],
    last: Option[EvaluatedRows[?, Msg]],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
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
    traverse(rows) { (row, item) =>
      val lastRow = lastRows.get(row.id)
      evaluateRow(
        row.id,
        row.source,
        item,
        row.element,
        if committed then lastRow.map(_.node) else oldRows.get(row.id),
        lastRow,
        revision,
        transaction,
        flash,
        bindings,
        commitActions,
        candidateRows,
        requirements
      ).map(EvaluatedNode.KeyedRow(row.id, _) -> _)
    }.map { evaluated =>
      transaction.dropRows(removed)
      val keyedRows = evaluated.map(_._1)
      val byId      = updatedRecords(
        lastRows -- removed,
        lastRows,
        keyedRows.iterator.map(_.id),
        evaluated.iterator.map(_._2)
      )
      val node = keyedNode(id, old, keyedRows, revision)
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

  /** `node`, whose rendered rows are `rows`, holding each row's recorded node instead, also in the
    * operations that insert it, like [[recordedKeyed]].
    */
  private def recordedStream[Msg](
    node: EvaluatedNode.Stream,
    rows: Vector[(EvaluatedNode.StreamRow, EvaluatedRow[Msg])]
  ): EvaluatedNode.Stream =
    if rows.forall((row, record) => row.child eq record.node) then node
    else
      val recorded = rows.map((row, record) =>
        if row.child eq record.node then row else row.copy(child = record.node)
      )
      val byDomId    = recorded.map(row => row.domId -> row).toMap
      val operations = node.operations.copy(inserts =
        node.operations.inserts.map(insert => insert.copy(row = byDomId(insert.row.domId)))
      )
      node.copy(rows = recorded.take(node.rows.length), operations = operations)

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

  /** Evaluates one row from `item`, or keeps its committed node, `previous`, when its item and
    * every signal it read from outside it are unchanged, and it renders nothing that signals do not
    * track. `last` is what the row's last evaluation recorded, and `previous` that evaluation's
    * node as resolution filled in its components. A kept row keeps its bindings and signal samples,
    * and declares its components again. Returns the row's node and what it records.
    */
  private def evaluateRow[A, Msg](
    id: RowId,
    source: Signal[A],
    item: A,
    element: ElementTemplate[Msg],
    previous: Option[EvaluatedNode],
    last: Option[EvaluatedRow[Msg]],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKey, FlashValue],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, (EvaluatedNode.Element, EvaluatedRow[Msg])] =
    val kept = (last, transaction.previousRow(id), previous) match
      case (Some(row), Some(read), Some(committed: EvaluatedNode.Element))
          if !read.untracked && row.item == item &&
            EvaluatedTree.resolvedFrom(committed, row.node) =>
        transaction.allUnchanged(read.outside).map(Option.when(_)(committed -> row))
      case _ => Right(None)

    kept.flatMap {
      case Some(keptRow @ (_, row)) =>
        bindings.keepRow(id, row.bindings)
        requirements.components ++= row.components
        Right(keptRow)
      case None =>
        transaction.bindSource(source, item).flatMap { _ =>
          val declared = requirements.components.length
          transaction.enterRow(id)
          bindings.enterRow()
          val evaluated = evaluateNode(
            element,
            previous,
            revision,
            transaction,
            flash,
            bindings,
            commitActions,
            candidateRows,
            requirements
          ).map(_.asInstanceOf[EvaluatedNode.Element])
          transaction.exitRow()
          val rowBindings = bindings.exitRow(id)
          evaluated.map(node =>
            node -> EvaluatedRow(
              EvaluatedTree.unresolved(node),
              rowBindings,
              item,
              requirements.componentsSince(declared)
            )
          )
        }
    }
  end evaluateRow

  /** Rejects a key `keys` holds twice, and otherwise returns them as a set. */
  private def validateEvaluationKeys[A](keys: Vector[A]): Either[RenderError, Set[A]] =
    keys
      .foldLeft[Either[RenderError, Set[A]]](Right(Set.empty)) { (result, key) =>
        result.flatMap(seen =>
          if seen.contains(key) then Left(RenderError.DuplicateKey(key))
          else Right(seen + key)
        )
      }

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
    val value = template.value match
      case TextTemplate.Value.Static(value, raw, slot)   => Right((slot, value, raw))
      case TextTemplate.Value.Dynamic(slot, signal, raw) =>
        transaction
          .sample(signal).flatMap(sample =>
            nonNullString(sample.value, "signal text content")
              .map(value => (Some(slot), value, raw))
          )

    value.map { case (slot, text, raw) =>
      previous match
        case Some(node: EvaluatedNode.Text)
            if node.id == template.id && node.slot == slot && node.value == text && node.raw == raw =>
          node
        case _ => EvaluatedNode.Text(template.id, slot, text, raw, revision)
    }

  private def evaluateAttribute[Msg](
    template: AttributeTemplate[Msg],
    previous: Option[EvaluatedAttribute],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    bindings: BindingTable.Builder[Msg],
    formId: Option[String]
  ): Either[RenderError, EvaluatedAttribute] =
    import AttributeTemplate.*

    val evaluated: Either[RenderError, (Option[TemplateSlotId], Option[AttributeValue])] =
      template match
        case Static(_, value, slot)        => Right(slot -> value)
        case DynamicValue(_, slot, signal) =>
          transaction
            .sample(signal).flatMap(sample =>
              nonNullString(sample.value, s"signal attribute '${template.name}'")
                .map(value => Some(slot) -> Some(AttributeValue.Text(value)))
            )
        case DynamicOptional(_, slot, signal) =>
          transaction.sample(signal).flatMap { sample =>
            sample.value match
              case Some(value) =>
                nonNullString(value, s"optional signal attribute '${template.name}'")
                  .map(value => Some(slot) -> Some(AttributeValue.Text(value)))
              case None => Right(Some(slot) -> None)
          }
        case DynamicPresence(_, slot, signal) =>
          transaction
            .sample(signal).map(sample =>
              Some(slot) -> Option.when(sample.value)(AttributeValue.Presence)
            )
        case Composite(name, slot, parts) =>
          traverse(parts)(part => evaluateCompositePart(name, part, transaction))
            .map(tokens => Some(slot) -> compositeAttributeValue(tokens.flatten))
        case Binding(name, valueSlot, id, operation) =>
          val resolvedId = resolveBindingId(name, id, formId)
          bindings
            .add(resolvedId, BindingOperation(operation)).map(_ =>
              Some(valueSlot) -> Some(AttributeValue.Text(resolvedId.encoded))
            )
        case SignalBinding(name, valueSlot, id, signal, operation) =>
          val resolvedId = resolveBindingId(name, id, formId)
          transaction.sample(signal).flatMap { sample =>
            bindings
              .add(resolvedId, BindingOperation(payload => operation(sample.value, payload))).map(
                _ => Some(valueSlot) -> Some(AttributeValue.Text(resolvedId.encoded))
              )
          }
        case JsBinding(name, valueSlot, id, command) =>
          evaluateJs(resolveBindingId(name, id, formId), command, bindings).map(value =>
            Some(valueSlot) -> Some(AttributeValue.Text(value))
          )
        case SignalJsBinding(name, valueSlot, id, command) =>
          val resolvedId = resolveBindingId(name, id, formId)
          transaction
            .sample(command).flatMap(sample =>
              evaluateJs(resolvedId, sample.value, bindings)
                .map(value => Some(valueSlot) -> Some(AttributeValue.Text(value)))
            )
        case RoutedBinding(name, valueSlot, id, operation) =>
          val resolvedId = resolveBindingId(name, id, formId)
          bindings
            .add(
              resolvedId,
              BindingOperation.dispatching(payload => BindingDispatch.Routed(operation(payload)))
            ).map(_ => Some(valueSlot) -> Some(AttributeValue.Text(resolvedId.encoded)))
        case ComponentTarget(target, valueSlot) =>
          Right(Some(valueSlot) -> Some(AttributeValue.ComponentTarget(target)))
        case Choice(_, signal, branches) =>
          transaction.sample(signal).flatMap { sample =>
            branches.find(_._1 == sample.value) match
              case Some((_, branch)) =>
                evaluateAttribute(branch, previous, revision, transaction, bindings, formId)
                  .map(attribute => attribute.slot -> attribute.value)
              case None => Right(None -> None)
          }

    evaluated.map { case (slot, value) =>
      previous match
        case Some(attribute)
            if attribute.name == template.name && attribute.slot == slot && attribute.value == value =>
          attribute
        case _ => EvaluatedAttribute(template.name, value, slot, revision)
    }
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

  /** A missing record or signals is `null`. */
  private def retainedRows(template: NodeTemplate[?]): Map[RowId, (AnyRef, AnyRef)] =
    def of(rows: Option[EvaluatedRows[?, ?]]): Map[RowId, (AnyRef, AnyRef)] =
      rows.fold(Map.empty) { rows =>
        (rows.rows.keySet ++ rows.signals.rows.keySet).iterator
          .map(id => id -> (rows.rows.get(id).orNull, rows.signals.rows.get(id).orNull))
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
