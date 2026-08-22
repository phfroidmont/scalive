package scalive.render

import zio.Task
import zio.ZIO
import zio.test.*

import scalive.*

object NestedRenderingSpec extends ZIOSpecDefault:
  object ChildView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): Task[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  private def answer(
    requirement: NestedRequirement,
    token: Object,
    child: Option[EvaluatedTree] = None
  ): NestedResolution =
    requirement.resolve(
      token,
      parentDomId = "parent",
      topic = "topic",
      joinCredential = "join-secret",
      staticCredential = Some("static-secret"),
      loading = false,
      child = child
    )

  override def spec = suite("NestedRenderingSpec")(
    test("validates exact one-to-one nested resolutions and required metadata") {
      val compiled = RenderProgram.compile[Unit, Nothing](_ => div(liveView("child", ChildView)))

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        requirement = candidate.nestedRequirements.head
        valid = answer(requirement, Object()).asInstanceOf[NestedResolution.Value]
        missing    = candidate.resolveNested(Vector.empty)
        unknown    = candidate.resolveNested(Vector(valid.copy(location = TemplateId(999999L))))
        duplicate  = candidate.resolveNested(Vector(valid, valid))
        mismatched = candidate.resolveNested(Vector(valid.copy(applicationId = "other")))
        nullToken  = candidate.resolveNested(Vector(valid.copy(instanceToken = null)))
        emptyTopic = candidate.resolveNested(Vector(valid.copy(topic = "")))
        nullTopic  = candidate.resolveNested(Vector(valid.copy(topic = null)))
      yield assertTrue(
        missing.left.exists(_.isInstanceOf[RenderError.UnresolvedNested]),
        unknown.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        duplicate.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        mismatched.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        nullToken.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        emptyTopic.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        nullTopic.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid])
      )
    },
    test("rejects duplicate application ids and unresolved children") {
      val duplicateCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveView("same", ChildView), liveView("same", ChildView))
      )
      val parentCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveView("parent-child", ChildView))
      )
      val unresolvedChildCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveView("grandchild", ChildView))
      )

      for
        duplicateProgram <- ZIO.fromEither(duplicateCompiled)
        parentProgram    <- ZIO.fromEither(parentCompiled)
        childProgram     <- ZIO.fromEither(unresolvedChildCompiled)
        duplicate        <- duplicateProgram.evaluate(())
        parent           <- parentProgram.evaluate(())
        child            <- childProgram.evaluate(())
        duplicateAnswers = duplicate.nestedRequirements.map(answer(_, Object()))
        duplicateResult  = duplicate.resolveNested(duplicateAnswers)
        unresolvedResult = parent.resolveNested(
          Vector(answer(parent.nestedRequirements.head, Object(), Some(child.tree)))
        )
      yield assertTrue(
        duplicateResult.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        unresolvedResult.left.exists(_.isInstanceOf[RenderError.NestedResolutionInvalid]),
        scala.util.Try(parent.commit).isFailure
      )
    },
    test("component children must have nested placeholders finalized") {
      object Component extends LiveComponent[Unit, Unit, Unit]:
        def mount(props: Unit, ctx: MountContext): Task[Unit] = ZIO.unit
        def handleMessage(props: Unit, model: Unit, ctx: MessageContext): Unit => Task[Unit] =
          _ => ZIO.unit
        def view(
          props: Signal[Unit],
          model: Signal[Unit],
          self: ComponentRef[Unit]
        ): HtmlElement[Unit] = div()

      val parentCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(Component, "component", ()))
      )
      val childCompiled = RenderProgram.compile[Unit, Unit](_ => div(liveView("nested", ChildView)))

      for
        parentProgram <- ZIO.fromEither(parentCompiled)
        childProgram  <- ZIO.fromEither(childCompiled)
        parent        <- parentProgram.evaluate(())
        child         <- childProgram.evaluate(())
        requirement = parent.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[Unit, Unit, Unit]]
        token = Object()
        result = parent.resolveComponents(
          Vector(requirement.resolve(ComponentRef.runtime[Unit](token), token, child))
        )
      yield assertTrue(result.left.exists(_.isInstanceOf[RenderError.ComponentResolutionInvalid]))
    },
    test("renders a plain nested container with optional disconnected child HTML") {
      val parentCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        mainTag("before", liveView("child&view", ChildView), "after")
      )
      val childCompiled = RenderProgram.compile[Unit, Nothing](_ => span("disconnected"))

      for
        parentProgram <- ZIO.fromEither(parentCompiled)
        childProgram  <- ZIO.fromEither(childCompiled)
        parent        <- parentProgram.evaluate(())
        child         <- childProgram.evaluate(())
        resolved      <- ZIO.fromEither(
          parent.resolveNested(
            Vector(answer(parent.nestedRequirements.head, Object(), Some(child.tree)))
          )
        )
      yield assertTrue(
        HtmlRenderer.render(resolved.tree) ==
          "<main>before<div id=\"child&amp;view\"><span>disconnected</span></div>after</main>"
      )
    },
    test("uses exact nested instance identity even when node revisions are retained") {
      var constructions = 0
      val compiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveView("child", {
          constructions += 1
          ChildView
        }))
      )
      val retainedToken = Object()
      val changedToken  = Object()

      for
        program <- ZIO.fromEither(compiled)
        initial <- program.evaluate(())
        initialResolved <- ZIO.fromEither(
          initial.resolveNested(Vector(answer(initial.nestedRequirements.head, retainedToken)))
        )
        base = initialResolved.commit
        retained <- program.evaluate((), Some(base))
        retainedResolved <- ZIO.fromEither(
          retained.resolveNested(Vector(answer(retained.nestedRequirements.head, retainedToken)))
        )
        changed <- program.evaluate((), Some(base))
        changedResolved <- ZIO.fromEither(
          changed.resolveNested(Vector(answer(changed.nestedRequirements.head, changedToken)))
        )
        changedDelta = TreeDiffer.diff(base.tree, changedResolved.tree)
      yield assertTrue(
        constructions == 0,
        TreeDiffer.diff(base.tree, retainedResolved.tree) == RenderDelta.Empty,
        changedDelta match
          case RenderDelta.Update(_, Vector(RenderChange.Replace(_, nested: EvaluatedNode.Nested))) =>
            nested.resolution.exists(value => value.instanceToken eq changedToken)
          case _ => false
      )
    }
  )
