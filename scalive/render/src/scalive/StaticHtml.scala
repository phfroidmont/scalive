package scalive

import zio.IO
import zio.ZIO

import scalive.render.EvaluatedNode
import scalive.render.HtmlRenderer
import scalive.render.RenderCandidate
import scalive.render.RenderError
import scalive.render.RenderProgram

/** A validation or evaluation failure produced while rendering static HTML. */
type StaticHtmlError = RenderError

/** Constructors and extractors for [[StaticHtmlError]]. */
object StaticHtmlError:
  export RenderError.{
    DuplicateBinding,
    DuplicateKey,
    DuplicateStreamDomId,
    EvaluationFailed,
    IdentityExhausted,
    InvalidHtml,
    SignalScopeViolation,
    UnresolvedComponents,
    UnresolvedNested,
    Unsupported
  }

/** Renders concrete HTML DSL trees without mounting a LiveView.
  *
  * Static rendering uses the same validation, escaping, and serialization as LiveView rendering.
  * Server event bindings, flash content, managed streams, stateful LiveComponents, and nested
  * LiveViews require lifecycle state and are therefore rejected. Client-only JS commands can be
  * serialized, but require an initialized Phoenix LiveView client in the browser to execute.
  */
object StaticHtml:
  /** Renders `element` once, optionally prefixing the result with the HTML doctype. */
  def render(
    element: => HtmlElement[Nothing],
    includeDoctype: Boolean = false
  ): IO[StaticHtmlError, String] =
    ZIO.acquireReleaseWith(
      ZIO.fromEither(RenderProgram.compile[Unit, Nothing](_ => element))
    )(_.close) { program =>
      ZIO.acquireReleaseWith(program.evaluate(()))(_.discard) { candidate =>
        ZIO.fromEither(validate(candidate)).flatMap { _ =>
          ZIO
            .attempt(HtmlRenderer.render(candidate.tree, includeDoctype))
            .mapError(RenderError.EvaluationFailed.apply)
        }
      }
    }

  private def validate(candidate: RenderCandidate[Nothing]): Either[RenderError, Unit] =
    val components = candidate.componentRequirements.map(_.location)
    val nested     = candidate.nestedRequirements.map(_.location)

    if !candidate.bindings.isEmpty then
      Left(RenderError.Unsupported("A server event binding in static HTML"))
    else if components.nonEmpty then Left(RenderError.UnresolvedComponents(components))
    else if nested.nonEmpty then Left(RenderError.UnresolvedNested(nested))
    else if candidate.streamRequirements.nonEmpty then
      Left(RenderError.Unsupported("A LiveView stream in static HTML"))
    else if containsFlash(candidate.tree.root) then
      Left(RenderError.Unsupported("Flash content in static HTML"))
    else Right(())

  private def containsFlash(node: EvaluatedNode): Boolean = node match
    case _: EvaluatedNode.Flash         => true
    case value: EvaluatedNode.Element   => value.children.exists(containsFlash)
    case value: EvaluatedNode.Choice    => value.child.exists(containsFlash)
    case value: EvaluatedNode.Keyed     => value.rows.exists(row => containsFlash(row.child))
    case value: EvaluatedNode.Stream    => value.rows.exists(row => containsFlash(row.child))
    case value: EvaluatedNode.Component =>
      value.resolution.exists(resolution => containsFlash(resolution.child.root))
    case value: EvaluatedNode.Nested =>
      value.resolution.flatMap(_.child).exists(tree => containsFlash(tree.root))
    case _: EvaluatedNode.Text => false
end StaticHtml
