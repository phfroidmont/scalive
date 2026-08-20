package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*
import scalive.streams.LiveStream
import scalive.streams.LiveStreamEntry
import scalive.streams.LiveStreamIdentity

object RenderRequirementsSpec extends ZIOSpecDefault:
  object PlainComponent extends LiveComponent[String, String, Unit]:
    def mount(props: String, ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => LiveIO[Unit] =
      _ => ZIO.unit
    def view(
      props: Signal[String],
      model: Signal[Unit],
      self: ComponentRef[String]
    ): HtmlElement[String] = span(props)

  object OutputComponent extends LiveComponent.WithOutput[String, String, Unit, String]:
    def mount(props: String, ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => LiveIO[Unit] =
      _ => ZIO.unit
    def view(
      props: Signal[String],
      model: Signal[Unit],
      self: ComponentRef[String]
    ): HtmlElement[String] = span(props)

  object ChildView extends LiveView.Eventless[String]:
    def mount(ctx: MountContext): LiveIO[String] = ZIO.succeed("child")
    def view(model: Signal[String]): HtmlElement[Nothing] = span(model)

  override def spec = suite("RenderRequirementsSpec")(
    test("component resolution preserves the component message type") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        import scalive.render.*
        def mismatch(
          requirement: ComponentRequirement[Nothing] { type Message = String },
          ref: ComponentRef[String],
          child: RenderCandidate[Int]
        ) = requirement.resolve(ref, new Object(), child)
      """)

      assertTrue(errors.nonEmpty)
    },
    test("evaluates static, signal, dynamic, and output component requirements once") {
      var idSamples    = 0
      var propsSamples = 0
      val compiled = RenderProgram.compile[(String, String), Int] { model =>
        val id = model.map { value =>
          idSamples += 1
          value._1
        }
        val props = model.map { value =>
          propsSamples += 1
          value._2
        }
        div(
          liveComponent(PlainComponent, "static", "one"),
          liveComponent(PlainComponent, "signal", props),
          liveComponent(PlainComponent, id, props),
          liveComponent(OutputComponent, "output", props, _.length)
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate("dynamic" -> "value")
        requirements = candidate.componentRequirements
        output = requirements.last
          .asInstanceOf[ComponentRequirement.WithOutput[String, String, Unit, String, Int]]
      yield assertTrue(
        requirements.map(_.applicationId) == Vector("static", "signal", "dynamic", "output"),
        requirements.map(_.props.asInstanceOf[String]) == Vector("one", "value", "value", "value"),
        requirements.map(_.location).distinct.size == 4,
        idSamples == 1,
        propsSamples == 1,
        output.outputMapper.exists(_("abcd") == 4),
        output.definition eq OutputComponent
      )
    },
    test("aggregates selected and keyed component requirements in render order") {
      val compiled = RenderProgram.compile[(Boolean, Vector[String]), Nothing] { model =>
        val selected = model.map(_._1)
        val items    = model.map(_._2)
        div(
          liveComponent(PlainComponent, "first", "first"),
          selected.when(span(liveComponent(PlainComponent, "choice", "choice"))),
          items.splitBy(identity) { (key, item) =>
            span(liveComponent(PlainComponent, key, item))
          }
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(true -> Vector("row-a", "row-b"))
        ids = candidate.componentRequirements.map(_.applicationId)
        _ <- candidate.discard
      yield assertTrue(ids == Vector("first", "choice", "row-a", "row-b"))
    },
    test("requires component finalization and inlines resolved child HTML") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ => div(
        "before",
        liveComponent(PlainComponent, "child", "props"),
        "after"
      ))
      val childCompiled = RenderProgram.compile[String, String](value => span(value))

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        candidate    <- rootProgram.evaluate(())
        child         <- childProgram.evaluate("inside")
        unresolvedCommit = scala.util.Try(candidate.commit)
        requirement = candidate.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        token = Object()
        ref = ComponentRef.runtime[String](token)
        missing = candidate.resolveComponents(Vector.empty)
        resolved <- ZIO.fromEither(
                      candidate.resolveComponents(
                        Vector(requirement.resolve(ref, token, child))
                      )
                    )
        childStillStaged = !child.stagedScope.isClosed
        childCommitted = child.commit
        committed = resolved.commit
        placeholder = committed.tree.root.children(1).asInstanceOf[EvaluatedNode.Component]
      yield assertTrue(
        unresolvedCommit.isFailure,
        missing.left.exists(_.isInstanceOf[RenderError.UnresolvedComponents]),
        childStillStaged,
        !childCommitted.scope.isClosed,
        placeholder.resolution.exists(_.instanceToken eq token),
        placeholder.resolution.exists(_.ref == ref),
        HtmlRenderer.render(committed.tree) ==
          "<div>before<span>inside</span>after</div>"
      )
    },
    test("component resolution leaves child candidate rollback ownership untouched") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(PlainComponent, "child", "props"))
      )
      val childCompiled = RenderProgram.compile[String, String](value => span(value))

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        parent       <- rootProgram.evaluate(())
        child        <- childProgram.evaluate("staged")
        requirement = parent.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        token = Object()
        ref = ComponentRef.runtime[String](token)
        finalized <- ZIO.fromEither(
                       parent.resolveComponents(Vector(requirement.resolve(ref, token, child)))
                     )
        childOpenAfterResolution = !child.stagedScope.isClosed
        _ <- finalized.discard
        childOpenAfterParentRollback = !child.stagedScope.isClosed
        _ <- child.discard
        childCommitAfterDiscard = scala.util.Try(child.commit)
      yield assertTrue(
        childOpenAfterResolution,
        childOpenAfterParentRollback,
        child.stagedScope.isClosed,
        childCommitAfterDiscard.isFailure
      )
    },
    test("rejects duplicate exact component tokens across siblings and nested children") {
      final class EqualToken:
        override def equals(other: Any): Boolean = other != null
        override def hashCode(): Int             = 1

      val siblingsCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(
          liveComponent(PlainComponent, "left", "left"),
          liveComponent(PlainComponent, "right", "right")
        )
      )
      val nestedRootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(PlainComponent, "outer", "outer"))
      )
      val outerCompiled = RenderProgram.compile[Unit, String](_ =>
        div(liveComponent(PlainComponent, "inner", "inner"))
      )
      val childCompiled = RenderProgram.compile[Unit, String](_ => span("child"))

      for
        siblingsProgram <- ZIO.fromEither(siblingsCompiled)
        nestedProgram   <- ZIO.fromEither(nestedRootCompiled)
        outerProgram    <- ZIO.fromEither(outerCompiled)
        childProgram    <- ZIO.fromEither(childCompiled)
        siblings        <- siblingsProgram.evaluate(())
        nestedRoot      <- nestedProgram.evaluate(())
        outer           <- outerProgram.evaluate(())
        child           <- childProgram.evaluate(())
        left = siblings.componentRequirements(0)
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        right = siblings.componentRequirements(1)
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        duplicateToken = Object()
        duplicateSiblings = siblings.resolveComponents(
          Vector(
            left.resolve(ComponentRef.runtime[String](duplicateToken), duplicateToken, child),
            right.resolve(ComponentRef.runtime[String](duplicateToken), duplicateToken, child)
          )
        )
        equalLeft  = EqualToken()
        equalRight = EqualToken()
        distinctEqualSiblings = siblings.resolveComponents(
          Vector(
            left.resolve(ComponentRef.runtime[String](equalLeft), equalLeft, child),
            right.resolve(ComponentRef.runtime[String](equalRight), equalRight, child)
          )
        )
        inner = outer.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        nestedToken = Object()
        outerResolved <- ZIO.fromEither(
                           outer.resolveComponents(
                             Vector(
                               inner.resolve(
                                 ComponentRef.runtime[String](nestedToken),
                                 nestedToken,
                                 child
                               )
                             )
                           )
                         )
        outerRequirement = nestedRoot.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        duplicateNested = nestedRoot.resolveComponents(
          Vector(
            outerRequirement.resolve(
              ComponentRef.runtime[String](nestedToken),
              nestedToken,
              outerResolved
            )
          )
        )
      yield assertTrue(
        duplicateSiblings.left.exists(_.isInstanceOf[RenderError.ComponentResolutionInvalid]),
        distinctEqualSiblings.isRight,
        duplicateNested.left.exists(_.isInstanceOf[RenderError.ComponentResolutionInvalid])
      )
    },
    test("produces owner, routed, targeted, and semantic target bindings") {
      val targetToken = Object()
      val target      = ComponentRef.runtime[String](targetToken)
      val compiled = RenderProgram.compile[Unit, String] { _ =>
        div(
          button(on.click("owner")),
          button(on.click.toComponent(PlainComponent)("routed")),
          button(on.click.to(target)("targeted"), phx.target(target))
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(())
        ids = candidate.bindings.ids
        owner = candidate.bindings.resolve(ids(0)).get.dispatch(BindingPayload.Params(Map.empty))
        routed = candidate.bindings.resolve(ids(1)).get.dispatch(BindingPayload.Params(Map.empty))
        targeted = candidate.bindings.resolve(ids(2)).get.dispatch(BindingPayload.Params(Map.empty))
        targetAttribute = candidate.tree.root.children(2)
          .asInstanceOf[EvaluatedNode.Element].attributes(1).value
      yield assertTrue(
        owner == Right(BindingDispatch.Owner("owner")),
        routed.exists {
          case BindingDispatch.Routed(ComponentDispatch.Definition(component, "routed")) =>
            component eq PlainComponent
          case _ => false
        },
        targeted.exists {
          case value: BindingDispatch.Targeted =>
            value.target == target && value.message == "targeted"
          case _ => false
        },
        targetAttribute.contains(AttributeValue.ComponentTarget(target))
      )
    },
    test("scopes sibling component deltas despite colliding local ids") {
      val rootCompiled = RenderProgram.compile[String, Nothing] { rootValue =>
        div(
          rootValue,
          liveComponent(PlainComponent, "left", "left"),
          liveComponent(PlainComponent, "right", "right")
        )
      }
      val leftCompiled  = RenderProgram.compile[String, String](value => span(value))
      val rightCompiled = RenderProgram.compile[String, String](value => span(value))
      val leftToken     = Object()
      val rightToken    = Object()
      val leftRef       = ComponentRef.runtime[String](leftToken)
      val rightRef      = ComponentRef.runtime[String](rightToken)

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        leftProgram  <- ZIO.fromEither(leftCompiled)
        rightProgram <- ZIO.fromEither(rightCompiled)
        rootInitial  <- rootProgram.evaluate("root")
        leftInitial  <- leftProgram.evaluate("same")
        rightInitial <- rightProgram.evaluate("before")
        leftRequirement = rootInitial.componentRequirements(0)
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        rightRequirement = rootInitial.componentRequirements(1)
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        rootResolved <- ZIO.fromEither(
                          rootInitial.resolveComponents(
                            Vector(
                              leftRequirement.resolve(leftRef, leftToken, leftInitial),
                              rightRequirement.resolve(rightRef, rightToken, rightInitial)
                            )
                          )
                        )
        leftBase  = leftInitial.commit
        rightBase = rightInitial.commit
        rootBase  = rootResolved.commit
        rootCurrent  <- rootProgram.evaluate("root", Some(rootBase))
        leftCurrent  <- leftProgram.evaluate("same", Some(leftBase))
        rightCurrent <- rightProgram.evaluate("after", Some(rightBase))
        currentLeftRequirement = rootCurrent.componentRequirements(0)
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        currentRightRequirement = rootCurrent.componentRequirements(1)
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        currentResolved <- ZIO.fromEither(
                             rootCurrent.resolveComponents(
                               Vector(
                                 currentLeftRequirement.resolve(leftRef, leftToken, leftCurrent),
                                 currentRightRequirement.resolve(rightRef, rightToken, rightCurrent)
                               )
                             )
                           )
        rootSlot = rootBase.tree.root.children.head.asInstanceOf[EvaluatedNode.Text].slot
        childSlot = rightCurrent.tree.root.children.head
          .asInstanceOf[EvaluatedNode.Text].slot
        delta = TreeDiffer.diff(rootBase.tree, currentResolved.tree)
      yield assertTrue(
        rootSlot.exists(_.value == 1L),
        childSlot.exists(_.value == 1L),
        TreeDiffer.diff(leftBase.tree, leftCurrent.tree) == RenderDelta.Empty,
        delta match
          case RenderDelta.Update(
                _,
                Vector(
                  RenderChange.Component(
                    token,
                    RenderDelta.Update(_, Vector(RenderChange.Text(slot, "after", false)))
                  )
                )
              ) => (token eq rightToken) && slot.value == 1L
          case _ => false
      )
    },
    test("keeps nested component changes recursively scoped") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(PlainComponent, "outer", "outer"))
      )
      val outerCompiled = RenderProgram.compile[Unit, String](_ =>
        div(liveComponent(PlainComponent, "inner", "inner"))
      )
      val innerCompiled = RenderProgram.compile[String, String](value => span(value))
      val outerToken    = Object()
      val innerToken    = Object()
      val outerRef      = ComponentRef.runtime[String](outerToken)
      val innerRef      = ComponentRef.runtime[String](innerToken)

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        outerProgram <- ZIO.fromEither(outerCompiled)
        innerProgram <- ZIO.fromEither(innerCompiled)
        rootInitial  <- rootProgram.evaluate(())
        outerInitial <- outerProgram.evaluate(())
        innerInitial <- innerProgram.evaluate("before")
        innerRequirement = outerInitial.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        outerResolved <- ZIO.fromEither(
                           outerInitial.resolveComponents(
                             Vector(innerRequirement.resolve(innerRef, innerToken, innerInitial))
                           )
                         )
        outerRequirement = rootInitial.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        rootResolved <- ZIO.fromEither(
                          rootInitial.resolveComponents(
                            Vector(outerRequirement.resolve(outerRef, outerToken, outerResolved))
                          )
                        )
        innerBase = innerInitial.commit
        outerBase = outerResolved.commit
        rootBase  = rootResolved.commit
        rootCurrent  <- rootProgram.evaluate((), Some(rootBase))
        outerCurrent <- outerProgram.evaluate((), Some(outerBase))
        innerCurrent <- innerProgram.evaluate("after", Some(innerBase))
        currentInnerRequirement = outerCurrent.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        currentOuterResolved <- ZIO.fromEither(
                                  outerCurrent.resolveComponents(
                                    Vector(
                                      currentInnerRequirement.resolve(
                                        innerRef,
                                        innerToken,
                                        innerCurrent
                                      )
                                    )
                                  )
                                )
        currentOuterRequirement = rootCurrent.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        currentRootResolved <- ZIO.fromEither(
                                 rootCurrent.resolveComponents(
                                   Vector(
                                     currentOuterRequirement.resolve(
                                       outerRef,
                                       outerToken,
                                       currentOuterResolved
                                     )
                                   )
                                 )
                               )
        delta = TreeDiffer.diff(rootBase.tree, currentRootResolved.tree)
      yield assertTrue(
        delta match
          case RenderDelta.Update(
                _,
                Vector(
                  RenderChange.Component(
                    outer,
                    RenderDelta.Update(
                      _,
                      Vector(
                        RenderChange.Component(
                          inner,
                          RenderDelta.Update(_, Vector(_: RenderChange.Text))
                        )
                      )
                    )
                  )
                )
              ) => (outer eq outerToken) && (inner eq innerToken)
          case _ => false
      )
    },
    test("treats a new component token at one declaration as replacement identity") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(PlainComponent, "child", "props"))
      )
      val childCompiled = RenderProgram.compile[String, String](value => span(value))
      val firstToken    = Object()
      val secondToken   = Object()

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        rootInitial  <- rootProgram.evaluate(())
        childInitial <- childProgram.evaluate("same")
        requirement = rootInitial.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        firstResolved <- ZIO.fromEither(
                           rootInitial.resolveComponents(
                             Vector(
                               requirement.resolve(
                                 ComponentRef.runtime[String](firstToken),
                                 firstToken,
                                 childInitial
                               )
                             )
                           )
                         )
        childBase = childInitial.commit
        rootBase  = firstResolved.commit
        rootCurrent  <- rootProgram.evaluate((), Some(rootBase))
        childCurrent <- childProgram.evaluate("same", Some(childBase))
        currentRequirement = rootCurrent.componentRequirements.head
          .asInstanceOf[ComponentRequirement.Plain[String, String, Unit]]
        secondResolved <- ZIO.fromEither(
                            rootCurrent.resolveComponents(
                              Vector(
                                currentRequirement.resolve(
                                  ComponentRef.runtime[String](secondToken),
                                  secondToken,
                                  childCurrent
                                )
                              )
                            )
                          )
        delta = TreeDiffer.diff(rootBase.tree, secondResolved.tree)
      yield assertTrue(
        delta match
          case RenderDelta.Update(
                _,
                Vector(RenderChange.Replace(_, component: EvaluatedNode.Component))
              ) =>
            component.resolution.exists(value => value.instanceToken eq secondToken)
          case _ => false
      )
    },
    test("extracts nested and semantic stream declarations without starting nested views") {
      var nestedSamples = 0
      var linkedSamples = 0
      var constructions = 0
      val staticStream = LiveStream(
        LiveStreamIdentity.fresh(),
        "static-stream",
        0L,
        Vector(LiveStreamEntry("static-row", "static")),
        Vector.empty,
        Vector.empty,
        false
      )
      val signalStream = LiveStream(
        LiveStreamIdentity.fresh(),
        "signal-stream",
        0L,
        Vector(LiveStreamEntry("signal-row", "signal")),
        Vector.empty,
        Vector.empty,
        false
      )
      val compiled = RenderProgram.compile[(String, LiveStream[String]), String] { model =>
        val nested = model.map { value =>
          nestedSamples += 1
          value._1
        }
        val stream = model.map(_._2)
        div(
          liveView("static", {
            constructions += 1
            ChildView
          }),
          liveView("dynamic", nested, sticky = true, value => {
            linkedSamples += 1
            value == "linked"
          }) { _ =>
            constructions += 1
            ChildView
          },
          staticStream.stream((id, value) =>
            span(
              id,
              value,
              liveComponent(PlainComponent, s"component-$id", value),
              liveView(s"nested-$id", ChildView)
            )
          ),
          stream.stream((id, value) =>
            span(
              id,
              value,
              liveComponent(PlainComponent, s"component-$id", value),
              liveView(s"nested-$id", ChildView)
            )
          )
        )
      }

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate("linked" -> signalStream)
        nested = candidate.nestedRequirements
        streams = candidate.streamRequirements
      yield assertTrue(
        nested.map(_.applicationId) ==
          Vector("static", "dynamic", "nested-static-row", "nested-signal-row"),
        nested.map(_.linkParentOnCrash) == Vector(false, true, false, false),
        nested(1).sticky,
        nestedSamples == 1,
        linkedSamples == 1,
        constructions == 0,
        streams.size == 2,
        streams.head.stream eq staticStream,
        streams(1).stream eq signalStream,
        candidate.componentRequirements.map(_.applicationId) ==
          Vector("component-static-row", "component-signal-row")
      )
    }
  )
