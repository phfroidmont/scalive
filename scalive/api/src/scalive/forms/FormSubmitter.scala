package scalive

import scala.reflect.Enum

/** A finite, definition-owned contract for rendering and decoding form submit controls.
  *
  * The browser representation remains an ordinary successful control with a stable `name` and
  * `value`. Decoding proves only that untrusted form data selected a declared action; applications
  * must still authorize the resulting operation.
  */
final class FormSubmitter[Owner, Schema, Action <: Enum] private[scalive] (
  private[scalive] val definition: FormDefinition[Owner, ?],
  /** Exact browser field name shared by this submitter's controls. */
  val name: String,
  mappings: Vector[(Action, String)]):

  private val encodedByAction = mappings.toMap
  private val actionByEncoded = mappings.iterator.map(_.swap).toMap

  /** Decodes exactly one declared submit action from a lossless browser payload. */
  def decode(data: FormData): Either[FormSubmitter.DecodeError, Action] =
    data.values(name) match
      case Vector()      => Left(FormSubmitter.DecodeError.Missing(name))
      case Vector(value) =>
        actionByEncoded
          .get(value).toRight(FormSubmitter.DecodeError.Unknown(name, value))
      case values => Left(FormSubmitter.DecodeError.Duplicate(name, values))

  /** Returns the raw successful-control pair for tests and transport adapters. */
  def raw(action: Action): RawFormSubmitter =
    RawFormSubmitter(name, encoded(action))

  /** Renders native submit-control attributes for `action`. */
  def attributes(action: Action): Mod.Attr[Nothing] =
    val submitter = raw(action)
    Mod.Attr.Group(
      Vector(
        typ      := "submit",
        nameAttr := submitter.name,
        value    := submitter.value
      )
    )

  /** Renders a native submit button for `action`. */
  def button[Msg](action: Action)(mods: Mod.Input[Msg]*): HtmlElement[Msg] =
    val content = Vector[Mod.Input[Msg]](attributes(action)) ++ mods
    _root_.scalive.button(content*)

  private def encoded(action: Action): String =
    encodedByAction.getOrElse(
      action,
      throw new IllegalArgumentException(s"submit action '$action' is not declared")
    )
end FormSubmitter

/** Validation and untrusted decoding failures for [[FormSubmitter]]. */
object FormSubmitter:
  /** Why a browser payload could not select one declared action. */
  enum DecodeError derives CanEqual:
    /** No value used the submitter's browser field name. */
    case Missing(name: String)

    /** One value was submitted, but it is not declared by this submitter. */
    case Unknown(name: String, value: String)

    /** Several values used the submitter's browser field name. */
    case Duplicate(name: String, values: Vector[String])

  private val DefaultSegment = "_scalive_submitter"

  private[scalive] def defaultName(root: FormPath): String =
    FormPath
      .fromSegments(root.segments :+ FormPathSegment.Name(DefaultSegment)).name

  private[scalive] def create[Owner, Schema, Action <: Enum](
    definition: FormDefinition[Owner, ?],
    actions: IterableOnce[Action],
    name: String,
    encode: Action => String
  ): FormSubmitter[Owner, Schema, Action] =
    val declared = actions.iterator.toVector
    require(declared.nonEmpty, "form submitter must declare at least one action")
    require(declared.distinct.size == declared.size, "duplicate form submit action")

    val mappings = declared.map { action =>
      val encoded = encode(action)
      require(encoded != null && encoded.nonEmpty, "form submit action value must not be empty")
      action -> encoded
    }
    require(
      mappings.map(_._2).distinct.size == mappings.size,
      "duplicate form submit action value"
    )

    new FormSubmitter(definition, name, mappings)
end FormSubmitter
