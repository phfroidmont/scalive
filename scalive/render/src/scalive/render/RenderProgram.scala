package scalive.render

import java.util.Locale
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import zio.IO
import zio.UIO
import zio.ZIO

import scalive.BindingPayload
import scalive.ComponentSpec
import scalive.Escaping
import scalive.FlashKind
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
  private val resolveFlash: Input => Map[FlashKind, String],
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
        case Some(committed) => Right(committed.signalEvaluation -> Some(committed.tree.root))
        case None            => Right(SignalEvaluation.empty -> None)
    }

    ZIO.fromEither(candidateScope.beginEvaluation()).flatMap { _ =>
      val candidateRows = RenderProgram.CandidateRows()
      (for
        previousValues <- ZIO.fromEither(previousState)
        (signalState, previousRoot) = previousValues
        candidate <-
          ZIO.fromEither(RenderRevision.next(signalState.revision)).flatMap { revision =>
            val transaction = SignalEvaluation.begin(signalState, revision, source, input)
            ZIO
              .attempt {
                val flash = resolveFlash(input)
                if flash == null then Left(RenderError.InvalidHtml("flash resolver returned null"))
                else
                  RenderProgram.evaluateElement(
                    template,
                    previousRoot,
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
                        commit
                      )
                    )
              }
          }
      yield candidate).onError(_ => ZIO.succeed(candidateRows.rollback()) *> candidateScope.discard)
    }
  end evaluateIn
end RenderProgram

object RenderProgram:
  /** Compiles `view` exactly once into a structured immutable template program. */
  def compile[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg]
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    compile(view, _ => Map.empty)

  /** Compiles a view once and resolves protocol-neutral flash values from each evaluated input.
    *
    * Each declaration retains only its currently committed present projection. Candidate
    * projections remain isolated until commit. Structurally equivalent continuous projections share
    * template identities; removal clears that identity before any later reintroduction.
    */
  def compile[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg],
    resolveFlash: Input => Map[FlashKind, String]
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    val scope       = SignalScope.root()
    val sourceToken = SignalSource[Input](scope)
    val source      = Signal.source[Input](sourceToken)
    val allocator   = IdentityAllocator()

    try
      (for
        identity <- RenderProgramId.fresh()
        template <- Compiler(allocator, scope, identity).compileElement(view(source))
      yield RenderProgram(identity, source, scope, resolveFlash, template)) match
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
    kind: FlashKind,
    project: String => HtmlElement[Nothing],
    compiler: Compiler,
    state: FlashTemplate.State)
      extends NodeTemplate[Nothing]

  final private case class ChoiceTemplate[+Msg](
    id: TemplateId,
    signal: Signal[Any],
    branches: Vector[(Any, NodeTemplate[Msg])])
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

  private object StreamTemplate:
    final class State[A, Msg]:
      private var retainedIdentity: Option[LiveStreamIdentity] = None
      private var retained = Map.empty[String, StreamRowTemplate[A, Msg]]

      def snapshot(identity: LiveStreamIdentity): Map[String, StreamRowTemplate[A, Msg]] =
        synchronized {
          if retainedIdentity.exists(_ eq identity) then retained else Map.empty
        }

      def assignment(
        identity: LiveStreamIdentity,
        next: Map[String, StreamRowTemplate[A, Msg]]
      ): () => Unit = () =>
        synchronized {
          val old          = retained
          val sameIdentity = retainedIdentity.exists(_ eq identity)
          retainedIdentity = Some(identity)
          retained = next
          old.foreach { case (domId, row) =>
            if !sameIdentity || !next.get(domId).exists(_.scope eq row.scope) then row.retire()
          }
        }

      def closeAll(): Unit = synchronized {
        retained.values.foreach(_.retire())
        retained = Map.empty
        retainedIdentity = None
      }

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
    final class State[A, Key, Msg]:
      private var retained = Map.empty[Key, DynamicKeyedRowTemplate[A, Msg]]
      def snapshot: Map[Key, DynamicKeyedRowTemplate[A, Msg]] = synchronized(retained)
      def assignment(
        next: Map[Key, DynamicKeyedRowTemplate[A, Msg]],
        removed: Vector[DynamicKeyedRowTemplate[A, Msg]]
      ): () => Unit =
        () =>
          synchronized {
            retained = next
            removed.foreach(_.retire())
          }

      def closeAll(): Unit = synchronized {
        retained.values.foreach(_.retire())
        retained = Map.empty
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

  private object FlashTemplate:
    final class State:
      private var retained: Option[(String, ElementTemplate[Nothing])] = None

      def snapshot: Option[(String, ElementTemplate[Nothing])] = synchronized(retained)

      def assignment(next: Option[(String, ElementTemplate[Nothing])]): () => Unit =
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
    final case class Binding[Msg](
      name: String,
      id: BindingId,
      operation: BindingPayload => Msg)
        extends AttributeTemplate[Msg]
    final case class SignalBinding[A, Msg](
      name: String,
      id: BindingId,
      signal: Signal[A],
      operation: (A, BindingPayload) => Msg)
        extends AttributeTemplate[Msg]
    final case class JsBinding[Msg](name: String, id: BindingId, command: JSCommand[Msg])
        extends AttributeTemplate[Msg]
    final case class SignalJsBinding[Msg](
      name: String,
      valueSlot: TemplateSlotId,
      id: BindingId,
      command: Signal[JSCommand[Msg]])
        extends AttributeTemplate[Msg]
    final case class RoutedBinding(
      name: String,
      id: BindingId,
      operation: BindingPayload => scalive.ComponentDispatch)
        extends AttributeTemplate[Nothing]
    final case class TargetedBinding[Message](
      name: String,
      id: BindingId,
      target: scalive.ComponentRef[Message],
      operation: BindingPayload => Message)
        extends AttributeTemplate[Nothing]
    final case class ComponentTarget[Message](target: scalive.ComponentRef[Message])
        extends AttributeTemplate[Nothing]:
      val name = "phx-target"
    final case class Choice[Msg](
      name: String,
      signal: Signal[Any],
      branches: Vector[(Any, AttributeTemplate[Msg])])
        extends AttributeTemplate[Msg]
  end AttributeTemplate

  final private class Compiler(
    allocator: IdentityAllocator,
    scope: SignalScope,
    program: RenderProgramId,
    lock: Object):

    def compileElement[Msg](element: HtmlElement[Msg]): Either[RenderError, ElementTemplate[Msg]] =
      for
        id    <- allocator.template()
        parts <- classifyMods(element.mods)
        (attributes, contents) = parts
        _ <- validateAttributeNames(attributes)
        _ <- Either.cond(
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
    ): Either[RenderError, (Vector[AttributeTemplate[Msg]], Vector[Mod.Content[Msg]])] =
      mods
        .foldLeft[Either[RenderError, (Vector[AttributeTemplate[Msg]], Vector[Mod.Content[Msg]])]](
          Right(Vector.empty -> Vector.empty)
        ) { (result, mod) =>
          result.flatMap { case (attributes, contents) =>
            mod match
              case Mod.Attr.Group(attrs) =>
                traverse(attrs.flatMap(_.flattened))(compileAttribute)
                  .map(compiled => attributes.appendedAll(compiled) -> contents)
              case attribute: Mod.Attr[Msg] =>
                compileAttribute(attribute).map(compiled => (attributes :+ compiled) -> contents)
              case choice @ Mod.Content.SignalModChoice(_, _) =>
                val branchMods = choice.branches.map(_._2)
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
                      "chooseMod branches cannot mix attribute and content positions"
                    )
                  )
              case content: Mod.Content[Msg] => Right(attributes -> (contents :+ content))
          }
        }

    private def compileAttributeChoice[Msg](
      choice: Mod.Content.SignalModChoice[?, Msg]
    ): Either[RenderError, AttributeTemplate[Msg]] =
      val signal   = choice.value.asInstanceOf[Signal[Any]]
      val branches = choice.branches.map { case (key, mod) =>
        key.asInstanceOf[Any] -> mod.asInstanceOf[Mod.Attr[Msg]]
      }
      for
        _        <- scope.validate(signal)
        _        <- validateDistinctKeys(branches.map(_._1))
        compiled <- traverse(branches) { case (key, attribute) =>
                      compileAttribute(attribute).map(key -> _)
                    }
        name <- compiled.headOption
                  .map(_._2.name).toRight(
                    RenderError.InvalidHtml("attribute chooseMod requires at least one branch")
                  )
        _ <- Either.cond(
               compiled.forall(_._2.name.equalsIgnoreCase(name)),
               (),
               RenderError.InvalidHtml(
                 "attribute chooseMod branches must use the same attribute name"
               )
             )
      yield AttributeTemplate.Choice(name, signal, compiled)

    private def compileAttribute[Msg](
      attribute: Mod.Attr[Msg]
    ): Either[RenderError, AttributeTemplate[Msg]] =
      attribute match
        case Mod.Attr.Static(name, value) =>
          Either.cond(
            value != null,
            AttributeTemplate.Static(name, Some(AttributeValue.Text(value))),
            RenderError.InvalidHtml(s"attribute '$name' has a null value")
          )
        case Mod.Attr.StaticValueAsPresence(name, value) =>
          Right(AttributeTemplate.Static(name, Option.when(value)(AttributeValue.Presence)))
        case Mod.Attr.SignalValue(name, value) =>
          dynamicAttribute(name, value)(AttributeTemplate.DynamicValue.apply)
        case Mod.Attr.SignalOptionalValue(name, value) =>
          dynamicAttribute(name, value)(AttributeTemplate.DynamicOptional.apply)
        case Mod.Attr.SignalValueAsPresence(name, value) =>
          dynamicAttribute(name, value)(AttributeTemplate.DynamicPresence.apply)
        case Mod.Attr.Binding(name, operation) =>
          allocator
            .binding().map(slot =>
              AttributeTemplate.Binding(name, BindingId.event(program, slot), operation)
            )
        case Mod.Attr.SignalBinding(name, signal, operation) =>
          for
            _    <- scope.validate(signal)
            slot <- allocator.binding()
          yield AttributeTemplate.SignalBinding(
            name,
            BindingId.event(program, slot),
            signal,
            operation
          )
        case Mod.Attr.FormBinding(name, operation) =>
          allocator
            .binding().map(slot =>
              AttributeTemplate.Binding(
                name,
                BindingId.event(program, slot),
                payload => operation(payload.formData)
              )
            )
        case Mod.Attr.FormEventBinding(name, codec, operation) =>
          allocator
            .binding().map(slot =>
              AttributeTemplate.Binding(
                name,
                BindingId.event(program, slot),
                payload => operation(payload.formEvent(codec, submitted = name == "phx-submit"))
              )
            )
        case Mod.Attr.JsBinding(name, command) =>
          allocator
            .binding().map(slot =>
              AttributeTemplate.JsBinding(name, BindingId.event(program, slot), command)
            )
        case Mod.Attr.SignalJsBinding(name, command) =>
          for
            _           <- scope.validate(command)
            valueSlot   <- allocator.slot()
            bindingSlot <- allocator.binding()
          yield AttributeTemplate.SignalJsBinding(
            name,
            valueSlot,
            BindingId.event(program, bindingSlot),
            command
          )
        case Mod.Attr.RoutedBinding(name, operation) =>
          allocator
            .binding().map(slot =>
              AttributeTemplate.RoutedBinding(
                name,
                BindingId.event(program, slot),
                operation
              )
            )
        case Mod.Attr.ComponentBinding(name, target, operation) =>
          allocator
            .binding().map(slot =>
              AttributeTemplate.TargetedBinding(
                name,
                BindingId.event(program, slot),
                target,
                operation
              )
            )
        case Mod.Attr.ComponentTarget(target) => Right(AttributeTemplate.ComponentTarget(target))
        case Mod.Attr.Group(_)                =>
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
          compileChoice(value, branches.map((key, element) => key -> Mod.Content.Tag(element)))
        case Mod.Content.SignalModChoice(value, branches) =>
          if branches.exists(_._2.isInstanceOf[Mod.Attr[?]]) then
            Left(
              RenderError.InvalidHtml(
                "chooseMod branches cannot mix attribute and content positions"
              )
            )
          else
            compileChoice(
              value,
              branches.map((key, mod) => key -> mod.asInstanceOf[Mod.Content[Msg]])
            )
        case Mod.Content.SignalOption(value, project) =>
          val present = value.map(_.isDefined)
          val item    = value.map(_.get)
          compileChoice(present, Vector(true -> Mod.Content.Tag(project(item))))
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
        case Mod.Content.Flash(kind, project) =>
          allocator.template().map { id =>
            FlashTemplate(
              id,
              kind,
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
      branches: Vector[(A, Mod.Content[Msg])]
    ): Either[RenderError, NodeTemplate[Msg]] =
      for
        _        <- scope.validate(value)
        _        <- validateDistinctKeys(branches.map(_._1))
        id       <- allocator.template()
        compiled <- traverse(branches) { case (key, content) =>
                      compileContent(content).map(key.asInstanceOf[Any] -> _)
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
            Compiler(allocator, old.scope, program, lock)
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
                Compiler(allocator, childScope, program, lock).compileElement(project(source))
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
              element <- Compiler(allocator, childScope, program, lock)
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

    private def validateAttributeNames[Msg](
      attributes: Vector[AttributeTemplate[Msg]]
    ): Either[RenderError, Unit] =
      attributes
        .foldLeft[Either[RenderError, Set[String]]](Right(Set.empty)) { case (result, attribute) =>
          result.flatMap { names =>
            if attribute.name == null || !Escaping.validAttrName(attribute.name) then
              Left(RenderError.InvalidHtml(s"invalid HTML attribute name '${attribute.name}'"))
            else if names.contains(attribute.name.toLowerCase(Locale.ROOT)) then
              Left(RenderError.InvalidHtml(s"duplicate HTML attribute '${attribute.name}'"))
            else Right(names + attribute.name.toLowerCase(Locale.ROOT))
          }
        }.map(_ => ())

    /** Compiles a changed projection candidate without changing committed retained state. */
    def flashProjectionCandidate(
      template: FlashTemplate,
      message: String
    ): Either[RenderError, ElementTemplate[Nothing]] = lock.synchronized {
      template.state.snapshot match
        case Some((retainedMessage, retained)) if retainedMessage == message => Right(retained)
        case retained                                                        =>
          compileElement(template.project(message)).map { compiled =>
            retained
              .flatMap((_, existing) => alignElement(compiled, existing))
              .getOrElse(markStaticScalars(compiled))
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
      case (left: FlashTemplate, right: FlashTemplate) if left.kind == right.kind =>
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
          case (left: AttributeTemplate.Binding[?], right: AttributeTemplate.Binding[?]) =>
            Some(left.copy(id = right.id).asInstanceOf[AttributeTemplate[Msg]])
          case (
                left: AttributeTemplate.SignalBinding[?, ?],
                right: AttributeTemplate.SignalBinding[?, ?]
              ) =>
            Some(left.copy(id = right.id).asInstanceOf[AttributeTemplate[Msg]])
          case (left: AttributeTemplate.JsBinding[?], right: AttributeTemplate.JsBinding[?]) =>
            Some(left.copy(id = right.id).asInstanceOf[AttributeTemplate[Msg]])
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
            Some(left.copy(id = right.id))
          case (
                left: AttributeTemplate.TargetedBinding[?],
                right: AttributeTemplate.TargetedBinding[?]
              ) =>
            Some(left.copy(id = right.id))
          case (
                left: AttributeTemplate.ComponentTarget[?],
                _: AttributeTemplate.ComponentTarget[?]
              ) =>
            Some(left)
          case _ => None
  end Compiler

  private object Compiler:
    def apply(
      allocator: IdentityAllocator,
      scope: SignalScope,
      program: RenderProgramId
    ): Compiler =
      new Compiler(allocator, scope, program, Object())

    def apply(
      allocator: IdentityAllocator,
      scope: SignalScope,
      program: RenderProgramId,
      lock: Object
    ): Compiler = new Compiler(allocator, scope, program, lock)

  private def evaluateElement[Msg](
    template: ElementTemplate[Msg],
    previous: Option[EvaluatedNode.Element],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    candidateRows: CandidateRows
  ): Either[
    RenderError,
    (EvaluatedNode.Element, BindingTable[Msg], Requirements[Msg], CandidateCommit)
  ] =
    val bindings      = BindingTable.Builder[Msg]()
    val commitActions = ArrayBuffer.empty[() => Unit]
    val requirements  = Requirements[Msg]()
    for
      attributes <- traverse(template.attributes.zipWithIndex) { case (attribute, index) =>
                      val previousAttribute = previous.flatMap(_.attributes.lift(index))
                      evaluateAttribute(
                        attribute,
                        previousAttribute,
                        revision,
                        transaction,
                        bindings
                      )
                    }
      children <- traverse(template.children.zipWithIndex) { case (child, index) =>
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
    yield (
      element,
      bindings.result(),
      requirements,
      CandidateCommit(commitActions.toArray, candidateRows.rollbackActions, candidateRows.scopes)
    )
    end for
  end evaluateElement

  private def evaluateNode[Msg](
    template: NodeTemplate[Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode] =
    template match
      case element: ElementTemplate[Msg] =>
        for
          attributes <- traverse(element.attributes.zipWithIndex) { case (attribute, index) =>
                          val previousAttribute = previous
                            .collect { case node: EvaluatedNode.Element => node }
                            .flatMap(_.attributes.lift(index))
                          evaluateAttribute(
                            attribute,
                            previousAttribute,
                            revision,
                            transaction,
                            bindings
                          )
                        }
          children <- traverse(element.children.zipWithIndex) { case (child, index) =>
                        val previousChild = previous
                          .collect { case node: EvaluatedNode.Element => node }
                          .flatMap(_.children.lift(index))
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
          previous.collect { case node: EvaluatedNode.Element => node },
          revision
        )
      case text: TextTemplate            => evaluateText(text, previous, revision, transaction)
      case template: ChoiceTemplate[Msg] =>
        for
          selected <- transaction.sample(template.signal)
          child    <- template.branches.find(_._1 == selected.value) match
                     case Some((_, branch)) =>
                       evaluateNode(
                         branch,
                         previous
                           .collect { case choice: EvaluatedNode.Choice => choice }.flatMap(
                             _.child
                           ),
                         revision,
                         transaction,
                         flash,
                         bindings,
                         commitActions,
                         candidateRows,
                         requirements
                       ).map(Some(_))
                     case None => Right(None)
        yield previous match
          case Some(old: EvaluatedNode.Choice)
              if old.id == template.id && old.child.map(_.revision) == child.map(_.revision) =>
            old.copy(child = child)
          case _ => EvaluatedNode.Choice(template.id, child, revision)
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
        evaluateNestedRequirement(template.id, template.spec, transaction).map { requirement =>
          requirements.nested += requirement
          previous match
            case Some(old: EvaluatedNode.Nested)
                if old.id == template.id && old.applicationId == requirement.applicationId =>
              old
            case _ => EvaluatedNode.Nested(template.id, requirement.applicationId, revision)
        }
      case template: StaticStreamTemplate[?, Msg] =>
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
        transaction.sample(template.stream).flatMap { sample =>
          requirements.streams += StreamRequirement.SignalBacked(
            template.id,
            sample.value,
            template.project
          )
          evaluateSignalStream(
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
        }
      case template: FlashTemplate =>
        val oldFlash = previous.collect { case node: EvaluatedNode.Flash => node }
        flash.get(template.kind) match
          case None =>
            commitActions += template.state.assignment(None)
            oldFlash match
              case Some(node) if node.id == template.id && node.child.isEmpty => Right(node)
              case _ => Right(EvaluatedNode.Flash(template.id, None, revision))
          case Some(message) =>
            for
              value     <- nonNullString(message, s"flash '${template.kind.value}'")
              projected <- template.compiler.flashProjectionCandidate(template, value)
              child     <- evaluateNode(
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
                case Some(node)
                    if node.id == template.id && node.child.exists(_.revision == child.revision) =>
                  node.copy(child = Some(child))
                case _ => EvaluatedNode.Flash(template.id, Some(child), revision)

  private def evaluateStaticStream[A, Msg](
    template: StaticStreamTemplate[A, Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Stream] =
    evaluateStream(
      template.id,
      template.stream,
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
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Stream] =
    evaluateStream(
      template.id,
      stream,
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

  private def evaluateStream[A, Msg](
    id: TemplateId,
    stream: LiveStream[A],
    state: StreamTemplate.State[A, Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg],
    rowCandidate: (String, A, Option[StreamRowTemplate[A, Msg]]) => Either[
      RenderError,
      StreamRowTemplate[A, Msg]
    ]
  ): Either[RenderError, EvaluatedNode.Stream] =
    val entries = stream.entries
    for
      _ <- validateStreamDomIds(entries)
      retained = state.snapshot(stream.identity)
      templates <- traverse(entries) { entry =>
                     rowCandidate(entry.domId, entry.value, retained.get(entry.domId))
                   }
      _ <- traverse(templates.zip(entries)) { case (row, entry) =>
             transaction.bindSource(row.source, entry.value)
           }
      old     = previous.collect { case value: EvaluatedNode.Stream => value }
      oldRows = old.toVector.flatMap(_.rows).map(row => row.domId -> row.child).toMap
      rows <- traverse(templates.zip(entries)) { case (row, entry) =>
                evaluateNode(
                  row.element,
                  oldRows.get(entry.domId),
                  revision,
                  transaction,
                  flash,
                  bindings,
                  commitActions,
                  candidateRows,
                  requirements
                ).map(child =>
                  EvaluatedNode.StreamRow(entry.domId, child.asInstanceOf[EvaluatedNode.Element])
                )
              }
    yield
      val rowsById = rows.map(row => row.domId -> row).toMap
      val inserts  = stream.inserted.map { insert =>
        val row = rowsById.getOrElse(
          insert.entry.domId,
          throw RenderError.InvalidHtml(
            s"stream insertion '${insert.entry.domId}' is absent from the retained snapshot"
          )
        )
        EvaluatedNode.StreamInsert(row, insert.at, insert.limit, insert.updateOnly)
      }
      val operations = EvaluatedNode.StreamOperations(inserts, stream.deleted, stream.reset)
      val next       = Map.from(entries.map(_.domId).zip(templates))
      commitActions += state.assignment(stream.identity, next)
      old match
        case Some(node)
            if node.id == id && (node.identity eq stream.identity) &&
              node.generation == stream.generation &&
              node.rows.map(_.child.revision) == rows.map(_.child.revision) =>
          node.copy(rows = rows, operations = operations)
        case _ =>
          EvaluatedNode.Stream(id, stream.identity, stream.generation, rows, operations, revision)
    end for
  end evaluateStream

  private def validateStreamDomIds[A](
    entries: Vector[LiveStreamEntry[A]]
  ): Either[RenderError, Unit] =
    entries
      .foldLeft[Either[RenderError, Set[String]]](Right(Set.empty)) { (result, entry) =>
        result.flatMap { seen =>
          if seen.contains(entry.domId) then Left(RenderError.DuplicateStreamDomId(entry.domId))
          else Right(seen + entry.domId)
        }
      }.map(_ => ())

  private def evaluateSignalKeyed[A, Key, Msg](
    template: SignalKeyedTemplate[A, Key, Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    transaction.sample(template.values).flatMap { sample =>
      val entries = sample.value.iterator.map(value => template.key(value) -> value).toVector
      for
        _ <- validateEvaluationKeys(entries.map(_._1))
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
        _ <- traverse(rows.zip(entries)) { case (row, (_, value)) =>
               transaction.bindSource(row.source, value)
             }
        result <- evaluateKeyedRows(
                    template.id,
                    rows,
                    previous,
                    revision,
                    transaction,
                    flash,
                    bindings,
                    commitActions,
                    candidateRows,
                    requirements
                  )
      yield
        val next    = Map.from(entries.map(_._1).zip(rows))
        val removed = retained.iterator.collect {
          case (key, row) if !next.contains(key) => row
        }.toVector
        commitActions += template.state.assignment(next, removed)
        result
      end for
    }

  private def evaluateSignalKeyedByIndex[A, Msg](
    template: SignalKeyedByIndexTemplate[A, Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
    bindings: BindingTable.Builder[Msg],
    commitActions: ArrayBuffer[() => Unit],
    candidateRows: CandidateRows,
    requirements: Requirements[Msg]
  ): Either[RenderError, EvaluatedNode.Keyed] =
    transaction.sample(template.values).flatMap { sample =>
      val values   = sample.value.iterator.toVector
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
        _ <- traverse(rows.zip(values)) { case (row, value) =>
               transaction.bindSource(row.source, value)
             }
        result <- evaluateKeyedRows(
                    template.id,
                    rows,
                    previous,
                    revision,
                    transaction,
                    flash,
                    bindings,
                    commitActions,
                    candidateRows,
                    requirements
                  )
      yield
        val next    = Map.from(indices.zip(rows))
        val removed = retained.iterator.collect {
          case (index, row) if !next.contains(index) => row
        }.toVector
        commitActions += template.state.assignment(next, removed)
        result
      end for
    }

  private def evaluateKeyedRows[Msg](
    id: TemplateId,
    templates: Vector[KeyedRowTemplate[Msg]],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    flash: Map[FlashKind, String],
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
    }.map { rows =>
      old match
        case Some(node)
            if node.id == id && node.rows.map(_.id) == rows.map(_.id) &&
              node.rows.map(_.child.revision) == rows.map(_.child.revision) =>
          node.copy(rows = rows)
        case _ => EvaluatedNode.Keyed(id, rows, revision)
    }
  end evaluateKeyedRows

  private def validateEvaluationKeys[A](keys: Vector[A]): Either[RenderError, Unit] =
    keys
      .foldLeft[Either[RenderError, Set[A]]](Right(Set.empty)) { (result, key) =>
        result.flatMap(seen =>
          if seen.contains(key) then Left(RenderError.DuplicateKey(key))
          else Right(seen + key)
        )
      }.map(_ => ())

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
    bindings: BindingTable.Builder[Msg]
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
        case Binding(_, id, operation) =>
          bindings
            .add(id, BindingOperation(operation)).map(_ =>
              None -> Some(AttributeValue.Text(id.encoded))
            )
        case SignalBinding(_, id, signal, operation) =>
          transaction.sample(signal).flatMap { sample =>
            bindings
              .add(id, BindingOperation(payload => operation(sample.value, payload))).map(_ =>
                None -> Some(AttributeValue.Text(id.encoded))
              )
          }
        case JsBinding(_, id, command) =>
          evaluateJs(id, command, bindings).map(value => None -> Some(AttributeValue.Text(value)))
        case SignalJsBinding(_, valueSlot, id, command) =>
          transaction
            .sample(command).flatMap(sample =>
              evaluateJs(id, sample.value, bindings)
                .map(value => Some(valueSlot) -> Some(AttributeValue.Text(value)))
            )
        case RoutedBinding(_, id, operation) =>
          bindings
            .add(
              id,
              BindingOperation.dispatching(payload => BindingDispatch.Routed(operation(payload)))
            ).map(_ => None -> Some(AttributeValue.Text(id.encoded)))
        case TargetedBinding(_, id, target, operation) =>
          bindings
            .add(
              id,
              BindingOperation
                .dispatching(payload => BindingDispatch.Targeted.Value(target, operation(payload)))
            ).map(_ => None -> Some(AttributeValue.Text(id.encoded)))
        case ComponentTarget(target) =>
          Right(None -> Some(AttributeValue.ComponentTarget(target)))
        case Choice(_, signal, branches) =>
          transaction.sample(signal).flatMap { sample =>
            branches.find(_._1 == sample.value) match
              case Some((_, branch)) =>
                evaluateAttribute(branch, previous, revision, transaction, bindings)
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

  private def evaluateJs[Msg](
    id: BindingId,
    command: JSCommand[Msg],
    bindings: BindingTable.Builder[Msg]
  ): Either[RenderError, String] =
    val scope = id.encoded
    command
      .bindings(scope).foldLeft[Either[RenderError, Unit]](Right(())) {
        case (result, (encodedId, message)) =>
          result.flatMap(_ =>
            bindings.add(
              BindingId.fromEncoded(encodedId),
              BindingOperation(_ => message)
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
          if node.id == template.id && node.tag == template.tag && node.void == template.void &&
            node.attributes.map(_.revision) == attributes.map(_.revision) &&
            node.children.map(_.revision) == children.map(_.revision) =>
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

  private def traverse[A, B](
    values: Vector[A]
  )(
    f: A => Either[RenderError, B]
  ): Either[RenderError, Vector[B]] =
    values.foldLeft[Either[RenderError, Vector[B]]](Right(Vector.empty)) { (result, value) =>
      for
        accumulated <- result
        next        <- f(value)
      yield accumulated :+ next
    }

  private def nonNullString(value: String, location: String): Either[RenderError, String] =
    Either.cond(value != null, value, RenderError.InvalidHtml(s"$location has a null value"))

  private def retainedFlashProjectionCount(template: NodeTemplate[?]): Int = template match
    case element: ElementTemplate[?] => element.children.map(retainedFlashProjectionCount).sum
    case flash: FlashTemplate        =>
      flash.state.snapshot match
        case Some((_, projection)) => 1 + retainedFlashProjectionCount(projection)
        case None                  => 0
    case choice: ChoiceTemplate[?] =>
      choice.branches.map((_, child) => retainedFlashProjectionCount(child)).sum
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
      choice.branches.map((_, child) => retainedKeyedRowCount(child)).sum
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

  private def closeRetainedRows(template: NodeTemplate[?]): Unit = template match
    case element: ElementTemplate[?] => element.children.foreach(closeRetainedRows)
    case choice: ChoiceTemplate[?]   =>
      choice.branches.foreach((_, child) => closeRetainedRows(child))
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
