package scalive.render

import java.util.Locale
import scala.util.control.NonFatal

import zio.IO
import zio.UIO
import zio.ZIO

import scalive.BindingPayload
import scalive.Escaping
import scalive.HtmlElement
import scalive.JSCommands.JSCommand
import scalive.Mod
import scalive.Signal

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
  def close: UIO[Unit] = ZIO.succeed(rootScope.close())

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
      (for
        previousValues <- ZIO.fromEither(previousState)
        (signalState, previousRoot) = previousValues
        candidate <-
          ZIO.fromEither(RenderRevision.next(signalState.revision)).flatMap { revision =>
            val transaction = SignalEvaluation.begin(signalState, revision, source, input)
            ZIO
              .attempt(RenderProgram.evaluateElement(template, previousRoot, revision, transaction))
              .mapError(RenderError.EvaluationFailed.apply).flatMap(ZIO.fromEither(_)).flatMap {
                case (root, bindings) =>
                  ZIO
                    .fromEither(candidateScope.completeEvaluation()).as(
                      RenderCandidate(
                        EvaluatedTree(root, identity),
                        bindings,
                        transaction.result,
                        candidateScope,
                        identity
                      )
                    )
              }
          }
      yield candidate).onError(_ => candidateScope.discard)
    }
  end evaluateIn
end RenderProgram

object RenderProgram:
  /** Compiles `view` exactly once into a structured immutable template program. */
  def compile[Input, Msg](
    view: Signal[Input] => HtmlElement[Msg]
  ): Either[RenderError, RenderProgram[Input, Msg]] =
    val scope       = SignalScope.root()
    val sourceToken = SignalSource[Input](scope)
    val source      = Signal.source[Input](sourceToken)
    val allocator   = IdentityAllocator()

    try
      (for
        identity <- RenderProgramId.fresh()
        template <- Compiler(allocator, scope, identity).compileElement(view(source))
      yield RenderProgram(identity, source, scope, template)) match
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

  private object TextTemplate:
    enum Value:
      case Static(value: String, raw: Boolean)
      case Dynamic(slot: TemplateSlotId, signal: Signal[String], raw: Boolean)

  sealed private trait AttributeTemplate[+Msg]:
    def name: String

  private object AttributeTemplate:
    final case class Static(name: String, value: Option[AttributeValue])
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
  end AttributeTemplate

  final private class Compiler(
    allocator: IdentityAllocator,
    scope: SignalScope,
    program: RenderProgramId):

    def compileElement[Msg](element: HtmlElement[Msg]): Either[RenderError, ElementTemplate[Msg]] =
      for
        id         <- allocator.template()
        attributes <- traverse(element.attrMods)(compileAttribute)
        _          <- validateAttributeNames(attributes)
        _          <- Either.cond(
               !element.tag.void || element.contentMods.isEmpty,
               (),
               RenderError.InvalidHtml(
                 s"void element '${element.tag.name}' cannot contain child content"
               )
             )
        children <- traverse(element.contentMods)(compileContent)
      yield ElementTemplate(id, element.tag.name, element.tag.void, attributes, children)

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
        case Mod.Attr.RoutedBinding(_, _) =>
          Left(RenderError.Unsupported("component-routed bindings"))
        case Mod.Attr.ComponentBinding(_, _, _) =>
          Left(RenderError.Unsupported("component-targeted bindings"))
        case Mod.Attr.ComponentTarget(_) =>
          Left(RenderError.Unsupported("component targets"))
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
        case Mod.Content.Tag(element) => compileElement(element)
        case Mod.Content.SignalChoice(_, _) | Mod.Content.SignalModChoice(_, _) |
            Mod.Content.SignalOption(_, _) =>
          Left(RenderError.Unsupported("choices and optional content"))
        case Mod.Content.Keyed(_) | Mod.Content.SignalKeyed(_, _, _) |
            Mod.Content.SignalKeyedByIndex(_, _) =>
          Left(RenderError.Unsupported("keyed collections"))
        case Mod.Content.Stream(_, _) | Mod.Content.SignalStream(_, _) =>
          Left(RenderError.Unsupported("streams"))
        case Mod.Content.Component(_)  => Left(RenderError.Unsupported("components"))
        case Mod.Content.NestedView(_) => Left(RenderError.Unsupported("nested LiveViews"))
        case Mod.Content.Flash(_, _)   => Left(RenderError.Unsupported("flash"))

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
  end Compiler

  private object Compiler:
    def apply(
      allocator: IdentityAllocator,
      scope: SignalScope,
      program: RenderProgramId
    ): Compiler =
      new Compiler(allocator, scope, program)

  private def evaluateElement[Msg](
    template: ElementTemplate[Msg],
    previous: Option[EvaluatedNode.Element],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction
  ): Either[RenderError, (EvaluatedNode.Element, BindingTable[Msg])] =
    val bindings = BindingTable.Builder[Msg]()
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
                    evaluateNode(child, previousChild, revision, transaction, bindings)
                  }
      element = retainElementRevision(template, attributes, children, previous, revision)
    yield element -> bindings.result()

  private def evaluateNode[Msg](
    template: NodeTemplate[Msg],
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction,
    bindings: BindingTable.Builder[Msg]
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
                        evaluateNode(child, previousChild, revision, transaction, bindings)
                      }
        yield retainElementRevision(
          element,
          attributes,
          children,
          previous.collect { case node: EvaluatedNode.Element => node },
          revision
        )
      case text: TextTemplate => evaluateText(text, previous, revision, transaction)

  private def evaluateText(
    template: TextTemplate,
    previous: Option[EvaluatedNode],
    revision: RenderRevision,
    transaction: SignalEvaluation.Transaction
  ): Either[RenderError, EvaluatedNode.Text] =
    val value = template.value match
      case TextTemplate.Value.Static(value, raw)         => Right((None, value, raw))
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
        case Static(_, value)              => Right(None -> value)
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
end RenderProgram
