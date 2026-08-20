package scalive.render

/** A validation or evaluation failure produced before render state can be committed. */
sealed abstract class RenderError(message: String, cause: Throwable = null)
    extends Exception(message, cause)

object RenderError:
  final case class IdentityExhausted(kind: String) extends RenderError(s"$kind space is exhausted")

  final case class Unsupported(feature: String)
      extends RenderError(s"$feature is not supported by the minimal render engine")

  final case class InvalidHtml(message: String) extends RenderError(message)

  final case class SignalScopeViolation(message: String) extends RenderError(message)

  final case class MissingSignalSource()
      extends RenderError("the render evaluation did not supply a signal source value")

  final case class DuplicateBinding(id: BindingId)
      extends RenderError(s"duplicate binding identity '${id.encoded}'")

  final case class DuplicateKey(key: Any)
      extends RenderError(s"duplicate keyed collection key '$key'")

  final case class ComponentResolutionInvalid(message: String) extends RenderError(message)

  final case class UnresolvedComponents(locations: Vector[TemplateId])
      extends RenderError(
        s"unresolved component declarations: ${locations.map(_.value).mkString(", ")}"
      )

  final case class ProgramMismatch()
      extends RenderError("the committed render belongs to a different render program")

  final case class ClosedCommittedRender()
      extends RenderError("the committed render scope is already closed")

  final case class CandidateScopeUnavailable()
      extends RenderError("the candidate scope is not available for evaluation")

  final case class EvaluationFailed(error: Throwable)
      extends RenderError("render evaluation failed", error)
end RenderError
