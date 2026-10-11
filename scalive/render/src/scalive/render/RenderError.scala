package scalive.render

/** A validation or evaluation failure produced before render state can be committed. */
sealed abstract class RenderError(message: String, cause: Throwable = null)
    extends Exception(message, cause)

object RenderError:
  /** An internal identity counter ran out of values.
    *
    * @param kind
    *   The identity space that ran out, such as `render revision`.
    */
  final case class IdentityExhausted(kind: String) extends RenderError(s"$kind space is exhausted")

  /** The tree uses a feature that needs a LiveView lifecycle.
    *
    * Static rendering rejects server event bindings, LiveView streams, and flash content.
    *
    * @param feature
    *   A description of the rejected feature, such as `Flash content in static HTML`.
    */
  final case class Unsupported(feature: String)
      extends RenderError(s"$feature requires a LiveView lifecycle")

  /** The tree does not describe valid HTML.
    *
    * Causes include child content in a void element, an invalid or duplicate attribute name, a
    * `null` value, and `chooseMod` branches that mix attribute and content positions.
    *
    * @param message
    *   A description of the problem.
    */
  final case class InvalidHtml(message: String) extends RenderError(message)

  /** The tree reads a signal outside the render that owns it.
    *
    * Static rendering has no model, so a signal in the tree can only come from another render, such
    * as a signal captured from a LiveView's `view`.
    *
    * @param message
    *   A description of the violation.
    */
  final case class SignalScopeViolation(message: String) extends RenderError(message)

  final case class MissingSignalSource()
      extends RenderError("the render evaluation did not supply a signal source value")

  /** Two event bindings in the tree have the same identity.
    *
    * @param id
    *   The duplicated binding identity.
    */
  final case class DuplicateBinding(id: BindingId)
      extends RenderError(s"duplicate binding identity '${id.encoded}'")

  /** Two rows of a keyed collection, such as one rendered with `splitBy`, have the same key.
    *
    * @param key
    *   The repeated key.
    */
  final case class DuplicateKey(key: Any)
      extends RenderError(s"duplicate keyed collection key '$key'")

  /** Two items of a LiveView stream have the same DOM `id`.
    *
    * @param domId
    *   The repeated DOM `id`.
    */
  final case class DuplicateStreamDomId(domId: String)
      extends RenderError(s"duplicate stream DOM id '$domId'")

  final case class ComponentResolutionInvalid(message: String) extends RenderError(message)

  final case class NestedResolutionInvalid(message: String) extends RenderError(message)

  /** The tree contains stateful LiveComponents, which need a LiveView lifecycle.
    *
    * @param locations
    *   The internal identities of the LiveComponent nodes, useful only in diagnostics.
    */
  final case class UnresolvedComponents(locations: Vector[TemplateId])
      extends RenderError(
        s"unresolved component declarations: ${locations.map(_.value).mkString(", ")}"
      )

  /** The tree contains nested LiveViews, which need a LiveView lifecycle.
    *
    * @param locations
    *   The internal identities of the nested LiveView nodes, useful only in diagnostics.
    */
  final case class UnresolvedNested(locations: Vector[TemplateId])
      extends RenderError(
        s"unresolved nested declarations: ${locations.map(_.value).mkString(", ")}"
      )

  final case class ProgramMismatch()
      extends RenderError("the committed render belongs to a different render program")

  final case class ClosedCommittedRender()
      extends RenderError("the committed render scope is already closed")

  final case class CandidateScopeUnavailable()
      extends RenderError("the candidate scope is not available for evaluation")

  /** Building, evaluating, or serializing the tree threw an exception.
    *
    * The exception usually comes from application code, such as the code that builds the element.
    *
    * @param error
    *   The exception that was thrown.
    */
  final case class EvaluationFailed(error: Throwable)
      extends RenderError("render evaluation failed", error)
end RenderError
